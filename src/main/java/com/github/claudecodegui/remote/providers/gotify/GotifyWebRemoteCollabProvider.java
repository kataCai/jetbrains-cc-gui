package com.github.claudecodegui.remote.providers.gotify;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemoteCollabRequestEnvelope;
import com.github.claudecodegui.remote.RemoteCollabRequestEnvelope.Action;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugActionDescriptor;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderActionHandler;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderDescriptor;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Gotify + Web 远程协作 provider 的最小实现。
 * 该 provider 负责把 IDE 中的任务事件和待处理请求转换为后台工作台请求，并在轮询到结果后回写本地交互。
 */
public class GotifyWebRemoteCollabProvider implements RemoteCollabProviderActionHandler {

    private static final String PROVIDER_ID = "gotify_web";
    private static final String ACTION_HEALTH_CHECK = "health_check";
    private static final String ACTION_SEND_TEST_EVENT = "send_test_event";
    private static final String ACTION_SEND_TEST_PENDING_REQUEST = "send_test_pending_request";
    private static final String ACTION_POLL_RESULTS_ONCE = "poll_results_once";
    private static final RemoteCollabProviderDescriptor DESCRIPTOR = new RemoteCollabProviderDescriptor(
        PROVIDER_ID,
        "Gotify + Web",
        "Remote collaboration via Gotify notifications and web workspace.",
        EnumSet.of(
            RemoteCollabCapability.TASK_EVENT_PUSH,
            RemoteCollabCapability.PENDING_REQUEST_PUSH,
            RemoteCollabCapability.RESULT_POLLING,
            RemoteCollabCapability.HEALTH_CHECK,
            RemoteCollabCapability.WORKSPACE_LINK
        )
    );
    private static final List<RemoteCollabDebugActionDescriptor> DEBUG_ACTIONS = List.of(
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_HEALTH_CHECK, "Health check", "Verify backend reachability."),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_SEND_TEST_EVENT, "Send test event", "Create a minimal task event request."),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_SEND_TEST_PENDING_REQUEST, "Send test pending request", "Create a minimal approval request and track it."),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_POLL_RESULTS_ONCE, "Poll once", "Pull backend results once and try to finish local requests.")
    );

    private final CodemossSettingsService settingsService;
    private final GotifyWorkspaceClient workspaceClient;
    private final GotifyResultPoller resultPoller;
    private volatile RemoteConnectionStatus connectionStatus = RemoteConnectionStatus.DISABLED;
    private volatile String lastWorkspaceLink = "";
    private volatile String lastError = "";

    public GotifyWebRemoteCollabProvider(CodemossSettingsService settingsService) {
        GotifyWorkspaceClient workspaceClient = new GotifyWorkspaceClient();
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.workspaceClient = workspaceClient;
        this.resultPoller = new GotifyResultPoller(workspaceClient);
    }

    GotifyWebRemoteCollabProvider(
        CodemossSettingsService settingsService,
        GotifyWorkspaceClient workspaceClient,
        GotifyResultPoller resultPoller
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.workspaceClient = Objects.requireNonNull(workspaceClient, "workspaceClient");
        this.resultPoller = Objects.requireNonNull(resultPoller, "resultPoller");
    }

    @Override
    public RemoteCollabProviderDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RemoteConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    /**
     * 暴露最近一次创建得到的工作台链接，便于调试页和后续跳转能力复用。
     */
    public String getLastWorkspaceLink() {
        return lastWorkspaceLink;
    }

    /**
     * 初始化 provider：仅在启用时执行健康检查并拉起轮询器。
     */
    @Override
    public void initialize() {
        try {
            JsonObject providerConfig = getProviderConfig();
            if (!isEnabled(providerConfig)) {
                connectionStatus = RemoteConnectionStatus.DISABLED;
                return;
            }
            workspaceClient.healthCheck(providerConfig);
            connectionStatus = RemoteConnectionStatus.CONNECTED;
            lastError = "";
            resultPoller.start(this::safeGetProviderConfig);
        } catch (Exception e) {
            connectionStatus = RemoteConnectionStatus.ERROR;
            lastError = safeMessage(e);
            throw new IllegalStateException("Failed to initialize Gotify/Web provider", e);
        }
    }

    @Override
    public void shutdown() {
        resultPoller.stop();
        connectionStatus = RemoteConnectionStatus.DISABLED;
    }

    /**
     * 将任务状态事件转换为工作台请求；当前最小实现只保留标题、来源和摘要。
     */
    @Override
    public void publishTaskEvent(RemoteTaskEvent event) {
        try {
            GotifyWorkspaceCreateResult result = workspaceClient.createRequest(getProviderConfig(), buildTaskEventRequest(event));
            rememberCreateResult(result);
        } catch (Exception e) {
            connectionStatus = RemoteConnectionStatus.ERROR;
            lastError = safeMessage(e);
            throw new IllegalStateException("Failed to publish task event via Gotify/Web", e);
        }
    }

    /**
     * 将本地待处理请求发送到后台，并登记到轮询器中等待远端动作回传。
     */
    @Override
    public void publishPendingRequest(RemotePendingRequest request) {
        try {
            GotifyWorkspaceCreateResult result = workspaceClient.createRequest(getProviderConfig(), buildPendingRequest(request));
            rememberCreateResult(result);
            resultPoller.track(request, result.getRequestId(), result.getWorkspaceLink());
        } catch (Exception e) {
            connectionStatus = RemoteConnectionStatus.ERROR;
            lastError = safeMessage(e);
            throw new IllegalStateException("Failed to publish pending request via Gotify/Web", e);
        }
    }

    /**
     * 统一执行调试页声明的 provider 动作，保持调试入口和正式链路共用同一套底层逻辑。
     */
    @Override
    public JsonObject executeAction(CodemossSettingsService settingsService, String actionKey, JsonObject request) throws Exception {
        return switch (actionKey) {
            case ACTION_HEALTH_CHECK -> runHealthCheck();
            case ACTION_SEND_TEST_EVENT -> runTestEvent();
            case ACTION_SEND_TEST_PENDING_REQUEST -> runTestPendingRequest();
            case ACTION_POLL_RESULTS_ONCE -> runPollResultsOnce();
            default -> throw new IllegalArgumentException("Unsupported Gotify/Web action: " + actionKey);
        };
    }

    @Override
    public List<RemoteCollabDebugActionDescriptor> getDebugActions() {
        return DEBUG_ACTIONS;
    }

    private JsonObject runHealthCheck() throws IOException, InterruptedException {
        JsonObject result = workspaceClient.healthCheck(getProviderConfig());
        connectionStatus = RemoteConnectionStatus.CONNECTED;
        lastError = "";
        return result;
    }

    private JsonObject runTestEvent() throws IOException, InterruptedException {
        GotifyWorkspaceCreateResult result = workspaceClient.createRequest(
            getProviderConfig(),
            new GotifyWorkspaceRequest(
                "debug-event-" + System.currentTimeMillis(),
                "task_event",
                "Gotify/Web debug event",
                "debug event",
                "This is a debug task event from IDE plugin."
            )
        );
        rememberCreateResult(result);
        JsonObject payload = new JsonObject();
        payload.addProperty("message", "Gotify/Web test event created");
        payload.addProperty("requestId", result.getRequestId());
        payload.addProperty("workspaceLink", result.getWorkspaceLink());
        return payload;
    }

    private JsonObject runTestPendingRequest() throws IOException, InterruptedException {
        RemotePendingRequest debugRequest = new RemotePendingRequest(
            "debug-pending-" + System.currentTimeMillis(),
            RemoteRequestType.PLAN_APPROVAL,
            "debug-session",
            "",
            new JsonObject(),
            ignored -> {
            }
        );
        GotifyWorkspaceCreateResult result = workspaceClient.createRequest(
            getProviderConfig(),
            buildPendingRequest(debugRequest)
        );
        rememberCreateResult(result);
        resultPoller.track(debugRequest, result.getRequestId(), result.getWorkspaceLink());

        JsonObject payload = new JsonObject();
        payload.addProperty("message", "Gotify/Web test pending request created");
        payload.addProperty("requestId", result.getRequestId());
        payload.addProperty("workspaceLink", result.getWorkspaceLink());
        return payload;
    }

    /**
     * 调试页手动轮询也要参与状态收敛：失败时标记 ERROR，成功时恢复为 CONNECTED，并保留已跟踪请求供下一次重试。
     */
    private JsonObject runPollResultsOnce() {
        try {
            JsonObject result = resultPoller.pollOnce(getProviderConfig());
            connectionStatus = RemoteConnectionStatus.CONNECTED;
            lastError = "";
            return result;
        } catch (Exception e) {
            connectionStatus = RemoteConnectionStatus.ERROR;
            lastError = safeMessage(e);
            throw new IllegalStateException("Failed to poll Gotify/Web results", e);
        }
    }

    /**
     * 任务事件在工作台中目前以只读摘要形式展示，因此优先选择 title/summary 等稳定字段。
     */
    private GotifyWorkspaceRequest buildTaskEventRequest(RemoteTaskEvent event) {
        String fallbackId = "task-event-" + System.currentTimeMillis();
        String title = event == null ? "Task event" : readPreferred(event.getTitle(), event.getTaskState(), "Task event");
        String sourceQuestion = event == null ? "" : readPreferred(event.getProjectPath(), event.getSessionId(), "");
        String summary = event == null ? "" : readPreferred(event.getSummary(), event.getTaskState(), "");
        return new GotifyWorkspaceRequest(
            event == null ? fallbackId : readPreferred(event.getRequestId(), fallbackId),
            "task_event",
            title,
            sourceQuestion,
            summary
        );
    }

    /**
     * 把现有多种 RemotePendingRequest 压缩为后台统一的最小请求结构，便于后续再扩展细粒度字段。
     */
    private GotifyWorkspaceRequest buildPendingRequest(RemotePendingRequest request) {
        RemoteCollabRequestEnvelope envelope = RemoteCollabRequestEnvelope.fromPendingRequest(request);
        JsonObject metadata = envelope.getMetadata();
        String requestType = envelope.getRequestType();
        String title = resolvePendingRequestTitle(requestType, metadata, envelope.getSummary());
        String sourceQuestion = resolvePendingRequestSourceQuestion(metadata, envelope.getSummary());
        String summary = readPreferred(envelope.getSummary(), requestType, "Remote review request");
        // 远程工作台只有拿到动作和原始 metadata，才能还原审批/问答的真实交互语义。
        return new GotifyWorkspaceRequest(
            request == null ? "pending-" + System.currentTimeMillis() : request.getRequestId(),
            requestType,
            title,
            sourceQuestion,
            summary,
            envelope.getWorkspaceLink(),
            metadata,
            toActionArray(envelope)
        );
    }

    private String resolvePendingRequestTitle(String requestType, JsonObject metadata, String summary) {
        if (metadata != null && metadata.has("title") && !metadata.get("title").isJsonNull()) {
            return metadata.get("title").getAsString();
        }
        return switch (requestType) {
            case "plan_approval" -> "Plan approval";
            case "ask_user_question" -> "Ask user question";
            case "task_state_action" -> "Task state action";
            default -> readPreferred(summary, "Remote review request");
        };
    }

    /**
     * sourceQuestion 继续保留“来源问题/工作目录”语义，优先使用原 payload 中更贴近上下文的字段；
     * 若 provider 无法提供原始字段，则退化为统一摘要，保证 Web 工作台中仍有可读信息。
     */
    private String resolvePendingRequestSourceQuestion(JsonObject payload, String summary) {
        if (payload == null) {
            return readPreferred(summary, "");
        }
        if (payload.has("question") && !payload.get("question").isJsonNull()) {
            return payload.get("question").getAsString();
        }
        if (payload.has("cwd") && !payload.get("cwd").isJsonNull()) {
            return payload.get("cwd").getAsString();
        }
        if (payload.has("projectPath") && !payload.get("projectPath").isJsonNull()) {
            return payload.get("projectPath").getAsString();
        }
        if (payload.has("title") && !payload.get("title").isJsonNull()) {
            return payload.get("title").getAsString();
        }
        return readPreferred(summary, "");
    }

    /**
     * 统一更新成功态缓存，方便设置页摘要和调试面板读取最近一次创建结果。
     */
    private void rememberCreateResult(GotifyWorkspaceCreateResult result) {
        connectionStatus = RemoteConnectionStatus.CONNECTED;
        lastError = "";
        if (result != null) {
            lastWorkspaceLink = result.getWorkspaceLink();
        }
    }

    private JsonObject getProviderConfig() {
        try {
            return settingsService.getRemoteCollabProviderConfig(PROVIDER_ID);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Gotify/Web provider config", e);
        }
    }

    /**
     * 定时轮询时不能把配置读取异常继续向调度线程抛出，否则会导致后续轮询整体停摆。
     */
    private JsonObject safeGetProviderConfig() {
        try {
            return getProviderConfig();
        } catch (RuntimeException e) {
            lastError = safeMessage(e);
            connectionStatus = RemoteConnectionStatus.ERROR;
            return new JsonObject();
        }
    }

    private boolean isEnabled(JsonObject providerConfig) {
        return providerConfig != null
            && providerConfig.has("enabled")
            && !providerConfig.get("enabled").isJsonNull()
            && providerConfig.get("enabled").getAsBoolean();
    }

    private static String safeMessage(Exception error) {
        return error == null || error.getMessage() == null ? "" : error.getMessage();
    }

    private static String readPreferred(String primary, String fallback) {
        return readPreferred(primary, fallback, "");
    }

    private static String readPreferred(String primary, String fallback, String defaultValue) {
        if (primary != null && !primary.trim().isEmpty()) {
            return primary.trim();
        }
        if (fallback != null && !fallback.trim().isEmpty()) {
            return fallback.trim();
        }
        return defaultValue;
    }

    private JsonArray toActionArray(RemoteCollabRequestEnvelope envelope) {
        JsonArray actions = new JsonArray();
        if (envelope == null) {
            return actions;
        }
        for (Action action : envelope.getActions()) {
            actions.add(action.toJson());
        }
        return actions;
    }
}
