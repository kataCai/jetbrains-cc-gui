package com.github.claudecodegui.remote.providers.gotify;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Gotify/Web 请求结果轮询器。
 * 负责维护“后台 requestId -> 本地 pending request”的映射，并在后台动作就绪后把结果回写到 IDE。
 */
public final class GotifyResultPoller {

    private final GotifyWorkspaceClient workspaceClient;
    private final ScheduledExecutorService executorService;
    private final Map<String, TrackedRequest> trackedRequests = new LinkedHashMap<>();
    private ScheduledFuture<?> pollFuture;

    public GotifyResultPoller(GotifyWorkspaceClient workspaceClient) {
        this(workspaceClient, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "cc-gui-gotify-poller");
            thread.setDaemon(true);
            return thread;
        }));
    }

    GotifyResultPoller(GotifyWorkspaceClient workspaceClient, ScheduledExecutorService executorService) {
        this.workspaceClient = Objects.requireNonNull(workspaceClient, "workspaceClient");
        this.executorService = Objects.requireNonNull(executorService, "executorService");
    }

    /**
     * 按当前配置启动定时轮询；重复启动时会先清理旧任务，避免出现并发轮询。
     */
    public synchronized void start(Supplier<JsonObject> configSupplier) {
        stop();
        JsonObject initialConfig = configSupplier == null ? new JsonObject() : safeConfig(configSupplier.get());
        int intervalSeconds = readPositiveInt(initialConfig, "resultPollIntervalSeconds", 3);
        if (intervalSeconds <= 0) {
            return;
        }
        pollFuture = executorService.scheduleWithFixedDelay(
            () -> runScheduledPoll(configSupplier, initialConfig),
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
    }

    /**
     * 停止当前轮询任务；不会清空跟踪中的请求，便于重新初始化后继续轮询。
     */
    public synchronized void stop() {
        if (pollFuture != null) {
            pollFuture.cancel(true);
            pollFuture = null;
        }
    }

    /**
     * 记录一个待轮询的本地请求；只有创建成功并拿到后台 requestId 时才会进入跟踪集合。
     */
    public synchronized void track(RemotePendingRequest localRequest, String backendRequestId, String workspaceLink) {
        if (localRequest == null || backendRequestId == null || backendRequestId.trim().isEmpty()) {
            return;
        }
        trackedRequests.put(backendRequestId.trim(), new TrackedRequest(localRequest, workspaceLink));
    }

    /**
     * 执行一次同步轮询，并把已完成的后台动作回写到本地 pending request。
     */
    public synchronized JsonObject pollOnce(JsonObject providerConfig) throws IOException, InterruptedException {
        JsonArray completedRequestIds = new JsonArray();
        Iterator<Map.Entry<String, TrackedRequest>> iterator = trackedRequests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TrackedRequest> entry = iterator.next();
            String backendRequestId = entry.getKey();
            TrackedRequest trackedRequest = entry.getValue();
            GotifyWorkspacePollResult result = workspaceClient.getResult(providerConfig, backendRequestId);
            if (!isActionReady(result)) {
                continue;
            }
            trackedRequest.localRequest.complete(translateActionResult(trackedRequest.localRequest, result.getLatestAction()));
            completedRequestIds.add(backendRequestId);
            iterator.remove();
        }

        JsonObject summary = new JsonObject();
        summary.add("completedRequestIds", completedRequestIds);
        summary.addProperty("remainingTrackedCount", trackedRequests.size());
        return summary;
    }

    /**
     * 定时轮询入口：故意吞掉异常，避免单次后台故障直接杀死整个调度线程。
     */
    private void runScheduledPoll(Supplier<JsonObject> configSupplier, JsonObject initialConfig) {
        try {
            JsonObject config = configSupplier == null ? initialConfig : safeConfig(configSupplier.get());
            pollOnce(config);
        } catch (Exception ignore) {
            // 保持调度器存活，详细错误交由调试动作和上层日志观察。
        }
    }

    /**
     * 当前仅在后台明确收到动作时才回写，避免把“仍在处理中”的请求提前完成。
     */
    private boolean isActionReady(GotifyWorkspacePollResult result) {
        if (result == null || result.getLatestAction() == null) {
            return false;
        }
        String status = result.getStatus();
        return "action_received".equals(status) || "completed".equals(status);
    }

    /**
     * 把后台动作翻译为本地 RemotePendingRequest 所需的统一结果结构。
     */
    private JsonObject translateActionResult(RemotePendingRequest request, GotifyWorkspaceAction action) {
        JsonObject payload = action == null ? new JsonObject() : action.getPayload();
        if (request.getRequestType() == RemoteRequestType.PLAN_APPROVAL) {
            return translatePlanApprovalResult(action, payload);
        }
        JsonObject answers = payload.deepCopy();
        if (action != null && !action.getActionType().isEmpty()) {
            answers.addProperty("actionType", action.getActionType());
        }
        return answers;
    }

    /**
     * 将工作台审批动作兼容为现有 PlanApproval 结果结构，减少上层适配改动。
     */
    private JsonObject translatePlanApprovalResult(GotifyWorkspaceAction action, JsonObject payload) {
        JsonObject result = new JsonObject();
        String actionType = action == null ? "" : action.getActionType();
        boolean approved = "approve".equals(actionType);
        result.addProperty("approved", approved);
        result.addProperty(
            "targetMode",
            approved ? readString(payload, "targetMode", "acceptEdits") : "default"
        );
        result.addProperty("message", readString(payload, "comment", approved ? "approved" : "rejected"));
        return result;
    }

    private static JsonObject safeConfig(JsonObject config) {
        return config == null ? new JsonObject() : config.deepCopy();
    }

    private static int readPositiveInt(JsonObject source, String key, int fallback) {
        if (source == null || key == null || !source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            int value = source.get(key).getAsInt();
            return value > 0 ? value : fallback;
        } catch (RuntimeException ignore) {
            return fallback;
        }
    }

    private static String readString(JsonObject source, String key, String fallback) {
        if (source == null || key == null || !source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        String value = source.get(key).getAsString();
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static final class TrackedRequest {
        private final RemotePendingRequest localRequest;
        @SuppressWarnings("unused")
        private final String workspaceLink;

        private TrackedRequest(RemotePendingRequest localRequest, String workspaceLink) {
            this.localRequest = localRequest;
            this.workspaceLink = workspaceLink == null ? "" : workspaceLink.trim();
        }
    }
}