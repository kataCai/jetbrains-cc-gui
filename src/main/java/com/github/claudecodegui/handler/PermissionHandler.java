package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.permission.PermissionRequest;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskStateEvent;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 权限相关消息处理器。
 * 除了本地权限弹窗展示与回写外，也负责把 AskUserQuestion / PlanApproval 注册到远程协作链路，
 * 保证手机端操作和 IDE 前端操作最终都汇聚到同一条 completion 路径。
 */
public class PermissionHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(PermissionHandler.class);

    // Permission request timeout (5 minutes), consistent with Node-side PERMISSION_TIMEOUT_MS
    private static final long PERMISSION_TIMEOUT_SECONDS = 300;

    private static final String[] SUPPORTED_TYPES = {
        "permission_decision",
        "ask_user_question_response",
        "plan_approval_response",
        "plan_approval_dialog_visibility"
    };

    // Permission request map
    private final Map<String, CompletableFuture<Integer>> pendingPermissionRequests = new ConcurrentHashMap<>();

    // 这里保留 requestId 集合，只负责当前窗口级清理；真正的 completion 路径统一走 RemoteRequestRegistry。
    private final Set<String> pendingAskUserQuestionRequestIds = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingPlanApprovalRequestIds = ConcurrentHashMap.newKeySet();

    // Permission denied callback
    public interface PermissionDeniedCallback {
        void onPermissionDenied();
    }

    private final TaskStateService taskStateService;
    private final TaskReminderDispatcher taskReminderDispatcher;
    private final RemoteRequestRegistry remoteRequestRegistry;
    private final RemoteCollabService remoteCollabService;
    // 仅用于提醒策略判断：如果审批弹窗已经在前台打开，就不再额外弹出 task reminder popup，
    // 避免用户同时面对两层内容几乎相同的弹窗。
    private volatile boolean planApprovalDialogVisible;
    private PermissionDeniedCallback deniedCallback;

    public PermissionHandler(HandlerContext context) {
        this(context, null, null);
    }

    public PermissionHandler(HandlerContext context, TaskStateService taskStateService) {
        this(context, taskStateService, null);
    }

    public PermissionHandler(
        HandlerContext context,
        TaskStateService taskStateService,
        TaskReminderDispatcher taskReminderDispatcher
    ) {
        this(
            context,
            taskStateService,
            taskReminderDispatcher,
            RemoteRequestRegistry.getGlobalInstance(),
            RemoteCollabService.getInstance()
        );
    }

    PermissionHandler(
        HandlerContext context,
        TaskStateService taskStateService,
        TaskReminderDispatcher taskReminderDispatcher,
        RemoteRequestRegistry remoteRequestRegistry
    ) {
        this(
            context,
            taskStateService,
            taskReminderDispatcher,
            remoteRequestRegistry,
            RemoteCollabService.getInstance()
        );
    }

    PermissionHandler(
        HandlerContext context,
        TaskStateService taskStateService,
        TaskReminderDispatcher taskReminderDispatcher,
        RemoteRequestRegistry remoteRequestRegistry,
        RemoteCollabService remoteCollabService
    ) {
        super(context);
        this.taskStateService = taskStateService;
        this.taskReminderDispatcher = taskReminderDispatcher;
        this.remoteRequestRegistry = remoteRequestRegistry;
        this.remoteCollabService = remoteCollabService;
    }

    public void setPermissionDeniedCallback(PermissionDeniedCallback callback) {
        this.deniedCallback = callback;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        if ("permission_decision".equals(type)) {
            LOG.debug("[PERM_DEBUG][BRIDGE_RECV] Received permission_decision from JS");
            LOG.debug("[PERM_DEBUG][BRIDGE_RECV] Content: " + content);
            handlePermissionDecision(content);
            return true;
        } else if ("ask_user_question_response".equals(type)) {
            LOG.debug("[ASK_USER_QUESTION][BRIDGE_RECV] Received ask_user_question_response from JS");
            LOG.debug("[ASK_USER_QUESTION][BRIDGE_RECV] Content: " + content);
            handleAskUserQuestionResponse(content);
            return true;
        } else if ("plan_approval_dialog_visibility".equals(type)) {
            LOG.debug("[PLAN_APPROVAL][BRIDGE_RECV] Received plan_approval_dialog_visibility from JS");
            LOG.debug("[PLAN_APPROVAL][BRIDGE_RECV] Content: " + content);
            handlePlanApprovalDialogVisibility(content);
            return true;
        } else if ("plan_approval_response".equals(type)) {
            LOG.debug("[PLAN_APPROVAL][BRIDGE_RECV] Received plan_approval_response from JS");
            LOG.debug("[PLAN_APPROVAL][BRIDGE_RECV] Content: " + content);
            handlePlanApprovalResponse(content);
            return true;
        }
        return false;
    }

    /**
     * Show the frontend permission dialog.
     */
    public CompletableFuture<Integer> showFrontendPermissionDialog(String toolName, JsonObject inputs) {
        String channelId = UUID.randomUUID().toString();
        CompletableFuture<Integer> future = new CompletableFuture<>();

        LOG.info("[PERM_SHOW] showFrontendPermissionDialog called: channelId=" + channelId + ", toolName=" + toolName);

        pendingPermissionRequests.put(channelId, future);
        LOG.info("[PERM_SHOW] Stored pending request, total pending: " + pendingPermissionRequests.size());

        try {
            Gson gson = new Gson();
            JsonObject requestData = new JsonObject();
            requestData.addProperty("channelId", channelId);
            requestData.addProperty("toolName", toolName);
            requestData.add("inputs", inputs);

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                LOG.info("[PERM_SHOW] Executing JS to show dialog for channelId=" + channelId);
                String jsCode = "(function retryShowDialog(retries) { " +
                    "  if (window.showPermissionDialog) { " +
                    "    window.showPermissionDialog('" + escapedJson + "'); " +
                    "  } else if (retries > 0) { " +
                    "    setTimeout(function() { retryShowDialog(retries - 1); }, 200); " +
                    "  } else { " +
                    "    console.error('[PERM_DEBUG][JS] FAILED: showPermissionDialog not available!'); " +
                    "  } " +
                    "})(30);";

                context.executeJavaScriptOnEDT(jsCode);
            });

            // Timeout handling (give users enough time to review the context)
            CompletableFuture.delayedExecutor(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
                if (!future.isDone()) {
                    LOG.warn("[PERM_SHOW] Timeout! Removing pending request for channelId=" + channelId);
                    pendingPermissionRequests.remove(channelId);
                    future.complete(PermissionService.PermissionResponse.DENY.getValue());
                }
            });

        } catch (Exception e) {
            LOG.error("[PERM_SHOW] ERROR: " + e.getMessage(), e);
            pendingPermissionRequests.remove(channelId);
            future.complete(PermissionService.PermissionResponse.DENY.getValue());
        }

        return future;
    }

    /**
     * Show permission request dialog (from PermissionRequest).
     */
    public void showPermissionDialog(PermissionRequest request) {
        LOG.info("[PermissionHandler] 显示权限请求对话框: " + request.getToolName());

        try {
            Gson gson = new Gson();
            JsonObject requestData = new JsonObject();
            requestData.addProperty("channelId", request.getChannelId());
            requestData.addProperty("toolName", request.getToolName());

            JsonObject inputsJson = gson.toJsonTree(request.getInputs()).getAsJsonObject();
            requestData.add("inputs", inputsJson);

            if (request.getSuggestions() != null) {
                requestData.add("suggestions", request.getSuggestions());
            }

            String requestJson = gson.toJson(requestData);
            String escapedJson = escapeJs(requestJson);

            // Get the project associated with the permission request
            Project targetProject = request.getProject();
            if (targetProject == null) {
                LOG.warn("[PermissionHandler] PermissionRequest has no project, fallback to current context window");
                targetProject = this.context.getProject();
            }

            // Get the window instance for the target project
            com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow targetWindow =
                com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow.getChatWindow(targetProject);

            if (targetWindow == null) {
                LOG.error("[PermissionHandler] Error: cannot find window instance for project " + targetProject.getName());
                // If target window is not found, deny the permission request
                this.context.getSession().handlePermissionDecision(
                    request.getChannelId(),
                    false,
                    false,
                    "Failed to show permission dialog: window not found"
                );
                notifyPermissionDenied();
                return;
            }

            // Execute JavaScript in the target window to show the dialog
            String jsCode = "if (window.showPermissionDialog) { " +
                "  window.showPermissionDialog('" + escapedJson + "'); " +
                "}";

            targetWindow.executeJavaScriptCode(jsCode);

        } catch (Exception e) {
            LOG.error("[PermissionHandler] 显示权限弹窗失败: " + e.getMessage(), e);
            this.context.getSession().handlePermissionDecision(
                request.getChannelId(),
                false,
                false,
                "Failed to show permission dialog: " + e.getMessage()
            );
            notifyPermissionDenied();
        }
    }

    /**
     * Handle permission decision messages from JavaScript.
     */
    private void handlePermissionDecision(String jsonContent) {
        LOG.info("[PERM_DECISION] Received permission decision from JS");
        LOG.debug("[PERM_DEBUG][HANDLE_DECISION] Content: " + jsonContent);
        try {
            Gson gson = new Gson();
            JsonObject decision = gson.fromJson(jsonContent, JsonObject.class);

            String channelId = decision.get("channelId").getAsString();
            boolean allow = decision.get("allow").getAsBoolean();
            boolean remember = decision.get("remember").getAsBoolean();
            String rejectMessage = "";
            if (decision.has("rejectMessage") && !decision.get("rejectMessage").isJsonNull()) {
                rejectMessage = decision.get("rejectMessage").getAsString();
            }

            LOG.info("[PERM_DECISION] channelId=" + channelId + ", allow=" + allow + ", remember=" + remember);
            LOG.info("[PERM_DECISION] pendingPermissionRequests size before remove: " + pendingPermissionRequests.size());

            CompletableFuture<Integer> pendingFuture = pendingPermissionRequests.remove(channelId);

            if (pendingFuture != null) {
                LOG.info("[PERM_DECISION] Found pending future, completing with allow=" + allow);
                int responseValue;
                if (allow) {
                    responseValue = remember ?
                        PermissionService.PermissionResponse.ALLOW_ALWAYS.getValue() :
                        PermissionService.PermissionResponse.ALLOW.getValue();
                } else {
                    responseValue = PermissionService.PermissionResponse.DENY.getValue();
                }
                pendingFuture.complete(responseValue);
                LOG.info("[PERM_DECISION] Future completed with value=" + responseValue);

                if (!allow) {
                    notifyPermissionDenied();
                }
            } else {
                LOG.warn("[PERM_DECISION] No pending future found for channelId=" + channelId + ", falling back to session handler");
                LOG.warn("[PERM_DECISION] Current pendingPermissionRequests keys: " + pendingPermissionRequests.keySet());
                // Handle permission request from Session
                if (remember) {
                    context.getSession().handlePermissionDecisionAlways(channelId, allow);
                } else {
                    context.getSession().handlePermissionDecision(channelId, allow, false, rejectMessage);
                }
                if (!allow) {
                    notifyPermissionDenied();
                }
            }
        } catch (Exception e) {
            LOG.error("[PERM_DECISION] ERROR: " + e.getMessage(), e);
        }
    }

    /**
     * Notify that permission was denied.
     */
    private void notifyPermissionDenied() {
        if (deniedCallback != null) {
            deniedCallback.onPermissionDenied();
        }
    }

    /**
     * Clear all pending permission requests.
     * Called during session switching or history restoration to prevent old requests from interfering with the new session.
     */
    public void clearPendingRequests() {
        LOG.info("[PERM_CLEAR] Clearing all pending permission requests");

        int permissionCount = pendingPermissionRequests.size();
        int askUserCount = pendingAskUserQuestionRequestIds.size();
        int planCount = pendingPlanApprovalRequestIds.size();

        // Cancel all pending permission requests
        for (Map.Entry<String, CompletableFuture<Integer>> entry : pendingPermissionRequests.entrySet()) {
            entry.getValue().complete(PermissionService.PermissionResponse.DENY.getValue());
        }
        pendingPermissionRequests.clear();

        for (String requestId : new ArrayList<>(pendingAskUserQuestionRequestIds)) {
            completeRemotePendingRequest(requestId, new JsonObject(), pendingAskUserQuestionRequestIds);
        }

        for (String requestId : new ArrayList<>(pendingPlanApprovalRequestIds)) {
            completeRemotePendingRequest(
                requestId,
                createPlanApprovalResult(false, "default", "Session changed"),
                pendingPlanApprovalRequestIds
            );
        }

        if (taskStateService != null) {
            // 清理 session 时不仅要清掉待确认 future，也要把聚合任务状态重置回 PENDING，
            // 否则新的会话可能继承上一轮 WAITING_CONFIRM / FINAL_ERROR 的尾状态。
            taskStateService.onSessionCleared(context.getSession() != null ? context.getSession().getSessionId() : null);
            dispatchTaskReminder(false);
        }
        planApprovalDialogVisible = false;

        LOG.info("[PERM_CLEAR] Cleared: " + permissionCount + " permission, " +
                 askUserCount + " askUser, " + planCount + " plan requests");
    }

    /**
     * Show AskUserQuestion dialog (implements PermissionService.AskUserQuestionDialogShower interface).
     */
    public CompletableFuture<JsonObject> showAskUserQuestionDialog(String requestId, JsonObject questionsData) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] Starting showAskUserQuestionDialog");
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] requestId=" + requestId);
        LOG.debug("[ASK_USER_QUESTION][SHOW_DIALOG] questionsData=" + questionsData.toString());

        registerRemotePendingRequest(
            requestId,
            RemoteRequestType.ASK_USER_QUESTION,
            questionsData,
            future,
            pendingAskUserQuestionRequestIds
        );

        try {
            Gson gson = new Gson();
            String requestJson = gson.toJson(questionsData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                String jsCode = "(function retryShowAskUserQuestion(retries) { " +
                    "  if (window.showAskUserQuestionDialog) { " +
                    "    window.showAskUserQuestionDialog('" + escapedJson + "'); " +
                    "  } else if (retries > 0) { " +
                    "    setTimeout(function() { retryShowAskUserQuestion(retries - 1); }, 200); " +
                    "  } else { " +
                    "    console.error('[ASK_USER_QUESTION][JS] FAILED: showAskUserQuestionDialog not available!'); " +
                    "  } " +
                    "})(30);";

                context.executeJavaScriptOnEDT(jsCode);
            });

            // Timeout handling (consistent with regular permission requests: 5 minutes)
            CompletableFuture.delayedExecutor(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
                if (!future.isDone()) {
                    LOG.warn("[ASK_USER_QUESTION][SHOW_DIALOG] Timeout! Removing pending request for requestId=" + requestId);
                    completeRemotePendingRequest(requestId, new JsonObject(), pendingAskUserQuestionRequestIds);
                }
            });

        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][SHOW_DIALOG] ERROR: " + e.getMessage(), e);
            removeRemotePendingRequest(requestId, pendingAskUserQuestionRequestIds);
            future.complete(new JsonObject());
        }

        return future;
    }

    /**
     * Handle AskUserQuestion response messages from JavaScript.
     */
    private void handleAskUserQuestionResponse(String jsonContent) {
        LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] Received response from JS: " + jsonContent);
        try {
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonContent, JsonObject.class);

            String requestId = response.get("requestId").getAsString();
            JsonObject answers = response.has("answers") && !response.get("answers").isJsonNull()
                ? response.get("answers").getAsJsonObject()
                : new JsonObject();

            if (completeRemotePendingRequest(requestId, answers, pendingAskUserQuestionRequestIds)) {
                LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] Completing future with answers: " + answers.toString());
            } else {
                LOG.warn("[ASK_USER_QUESTION][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
            }
        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][HANDLE_RESPONSE] ERROR: " + e.getMessage(), e);
        }
    }

    /**
     * Show PlanApproval dialog (implements PermissionService.PlanApprovalDialogShower interface).
     */
    public CompletableFuture<JsonObject> showPlanApprovalDialog(String requestId, JsonObject planData) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();

        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] Starting showPlanApprovalDialog");
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] requestId=" + requestId);
        LOG.debug("[PLAN_APPROVAL][SHOW_DIALOG] planData=" + planData.toString());

        registerRemotePendingRequest(
            requestId,
            RemoteRequestType.PLAN_APPROVAL,
            planData,
            future,
            pendingPlanApprovalRequestIds
        );
        planApprovalDialogVisible = false;
        if (taskStateService != null) {
            // 进入 WAITING_CONFIRM 时立即同步任务提醒，
            // 避免远程提醒比本地审批弹窗更早出现造成重复打扰。
            taskStateService.onPlanApprovalRequested(requestId);
            dispatchTaskReminder(true);
            publishRemoteTaskEvent(planData);
        }

        try {
            Gson gson = new Gson();
            String requestJson = gson.toJson(planData);
            String escapedJson = escapeJs(requestJson);

            ApplicationManager.getApplication().invokeLater(() -> {
                String jsCode = "(function retryShowPlanApproval(retries) { " +
                    "  if (window.showPlanApprovalDialog) { " +
                    "    window.showPlanApprovalDialog('" + escapedJson + "'); " +
                    "  } else if (retries > 0) { " +
                    "    setTimeout(function() { retryShowPlanApproval(retries - 1); }, 200); " +
                    "  } else { " +
                    "    console.error('[PLAN_APPROVAL][JS] FAILED: showPlanApprovalDialog not available!'); " +
                    "  } " +
                    "})(30);";

                context.executeJavaScriptOnEDT(jsCode);
            });

            // Timeout handling (consistent with other permission requests: 5 minutes)
            CompletableFuture.delayedExecutor(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
                if (!future.isDone()) {
                    LOG.warn("[PLAN_APPROVAL][SHOW_DIALOG] Timeout requestId=" + requestId);
                    completeRemotePendingRequest(
                        requestId,
                        createPlanApprovalResult(false, "default", "Plan approval timed out"),
                        pendingPlanApprovalRequestIds
                    );
                    planApprovalDialogVisible = false;
                    if (taskStateService != null) {
                        // 超时后直接进入 FINAL_ERROR，并同步远程状态，
                        // 避免手机端仍停留在“等待确认”的旧提示。
                        taskStateService.onPlanApprovalTimedOut(requestId);
                        dispatchTaskReminder(false);
                        publishRemoteTaskEvent(planData);
                    }
                    // Return rejection on timeout
                }
            });

        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][SHOW_DIALOG] ERROR: " + e.getMessage(), e);
            removeRemotePendingRequest(requestId, pendingPlanApprovalRequestIds);
            planApprovalDialogVisible = false;
            future.complete(createPlanApprovalResult(false, "default", "Error showing plan approval dialog"));
        }

        return future;
    }

    private void handlePlanApprovalDialogVisibility(String jsonContent) {
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(jsonContent, JsonObject.class);
            if (payload == null) {
                return;
            }

            boolean visible = payload.has("visible") && payload.get("visible").getAsBoolean();
            String requestId = payload.has("requestId") && !payload.get("requestId").isJsonNull()
                ? payload.get("requestId").getAsString()
                : null;

            if (taskStateService == null) {
                planApprovalDialogVisible = visible;
                return;
            }

            String currentRequestId = taskStateService.getCurrentSnapshot() != null
                ? taskStateService.getCurrentSnapshot().getRequestId()
                : null;
            if (requestId != null && currentRequestId != null && !requestId.equals(currentRequestId)) {
                LOG.debug("[PLAN_APPROVAL][VISIBILITY] Ignore stale visibility event, requestId=" + requestId
                    + ", currentRequestId=" + currentRequestId);
                return;
            }

            planApprovalDialogVisible = visible;
            dispatchTaskReminder(planApprovalDialogVisible);
        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][VISIBILITY] ERROR: " + e.getMessage(), e);
        }
    }

    /**
     * Handle PlanApproval response messages from JavaScript.
     */
    private void handlePlanApprovalResponse(String jsonContent) {
        LOG.debug("[PLAN_APPROVAL][HANDLE_RESPONSE] Received response from JS: " + jsonContent);
        try {
            Gson gson = new Gson();
            JsonObject response = gson.fromJson(jsonContent, JsonObject.class);

            String requestId = response.get("requestId").getAsString();
            boolean approved = response.has("approved") && response.get("approved").getAsBoolean();
            String targetMode = response.has("targetMode") ? response.get("targetMode").getAsString() : "default";
            String reason = response.has("message") && !response.get("message").isJsonNull()
                ? response.get("message").getAsString()
                : "plan_approval_rejected";

            if (completeRemotePendingRequest(
                requestId,
                createPlanApprovalResult(approved, targetMode, reason),
                pendingPlanApprovalRequestIds
            )) {
                LOG.debug("[PLAN_APPROVAL][HANDLE_RESPONSE] Completing future: approved=" + approved + ", targetMode=" + targetMode);
                planApprovalDialogVisible = false;
                if (taskStateService != null) {
                    // 审批通过后恢复 RUNNING，拒绝后进入 CANCELLED，
                    // 并让远程通道复用同一份聚合快照，避免不同端各自猜测结果。
                    if (approved) {
                        taskStateService.onPlanApprovalApproved(requestId);
                    } else {
                        taskStateService.onPlanApprovalRejected(requestId, reason);
                    }
                    dispatchTaskReminder(false);
                    publishRemoteTaskEvent(null);
                }
            } else {
                LOG.warn("[PLAN_APPROVAL][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
            }
        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][HANDLE_RESPONSE] ERROR: " + e.getMessage(), e);
        }
    }

    private void registerRemotePendingRequest(
        String requestId,
        RemoteRequestType requestType,
        JsonObject payload,
        CompletableFuture<JsonObject> future,
        Set<String> localRequestIds
    ) {
        localRequestIds.add(requestId);
        remoteRequestRegistry.register(new RemotePendingRequest(
            requestId,
            requestType,
            getCurrentSessionId(),
            resolveProjectPath(payload),
            payload,
            result -> {
                localRequestIds.remove(requestId);
                future.complete(result == null ? new JsonObject() : result);
            }
        ));
    }

    private boolean completeRemotePendingRequest(String requestId, JsonObject result, Set<String> localRequestIds) {
        boolean completed = remoteRequestRegistry.complete(requestId, result);
        if (!completed) {
            localRequestIds.remove(requestId);
        }
        return completed;
    }

    private void removeRemotePendingRequest(String requestId, Set<String> localRequestIds) {
        remoteRequestRegistry.remove(requestId);
        localRequestIds.remove(requestId);
    }

    private JsonObject createPlanApprovalResult(boolean approved, String targetMode, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("approved", approved);
        result.addProperty("targetMode", targetMode == null || targetMode.isEmpty() ? "default" : targetMode);
        result.addProperty("message", message == null ? "" : message);
        return result;
    }

    private String getCurrentSessionId() {
        return context.getSession() != null ? context.getSession().getSessionId() : null;
    }

    private String resolveProjectPath(JsonObject payload) {
        if (payload != null && payload.has("cwd") && !payload.get("cwd").isJsonNull()) {
            return payload.get("cwd").getAsString();
        }
        return context.getProject() != null ? context.getProject().getBasePath() : null;
    }

    private void dispatchTaskReminder(boolean approvalDialogVisible) {
        if (taskReminderDispatcher != null && taskStateService != null) {
            // ReminderDispatcher 统一负责 popup、balloon、status bar、sound 等提醒形式，
            // 这里仅传递当前快照与审批弹窗可见性，避免 handler 自己分叉提醒逻辑。
            taskReminderDispatcher.dispatch(taskStateService.getCurrentSnapshot(), approvalDialogVisible);
        }
    }

    private void publishRemoteTaskEvent(JsonObject payload) {
        if (remoteCollabService == null || taskStateService == null) {
            return;
        }
        TaskStateSnapshot snapshot = taskStateService.getCurrentSnapshot();
        if (snapshot == null || snapshot.getState() == null) {
            return;
        }
        remoteCollabService.publishTaskEvent(new RemoteTaskEvent(
            snapshot.getSessionId() != null ? snapshot.getSessionId() : getCurrentSessionId(),
            resolveProjectPath(payload),
            snapshot.getRequestId(),
            snapshot.getState().getValue(),
            snapshot.getState().getValue(),
            resolveRemoteTaskSummary(snapshot, payload)
        ));
    }

    private String resolveRemoteTaskSummary(TaskStateSnapshot snapshot, JsonObject payload) {
        if (payload != null) {
            String question = readString(payload, "question");
            if (question != null) {
                return question;
            }
            String title = readString(payload, "title");
            if (title != null) {
                return title;
            }
        }
        TaskStateEvent latestEvent = snapshot.getLatestEvent();
        return latestEvent != null ? latestEvent.getReason() : null;
    }

    private String readString(JsonObject payload, String key) {
        if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
            return null;
        }
        String value = payload.get(key).getAsString();
        return value == null || value.trim().isEmpty() ? null : value;
    }
}

