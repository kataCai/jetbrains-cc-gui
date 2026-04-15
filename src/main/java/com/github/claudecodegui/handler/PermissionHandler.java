package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.permission.PermissionRequest;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import javax.swing.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Permission handler.
 * Handles permission dialog display and decision processing.
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

    // AskUserQuestion request map (requestId -> CompletableFuture<JsonObject>)
    private final Map<String, CompletableFuture<JsonObject>> pendingAskUserQuestionRequests = new ConcurrentHashMap<>();

    // PlanApproval request map (requestId -> CompletableFuture<JsonObject>)
    private final Map<String, CompletableFuture<JsonObject>> pendingPlanApprovalRequests = new ConcurrentHashMap<>();

    // Permission denied callback
    public interface PermissionDeniedCallback {
        void onPermissionDenied();
    }

    private final TaskStateService taskStateService;
    private final TaskReminderDispatcher taskReminderDispatcher;
    // 浠呯敤浜庢彁閱掔瓥鐣ュ垽鏂細濡傛灉瀹℃壒寮圭獥宸茬粡鍦ㄥ墠鍙版墦寮€锛屽氨涓嶅啀棰濆寮瑰嚭 task reminder popup锛?
    // 閬垮厤鐢ㄦ埛闈㈠涓ゅ眰鍐呭鍑犱箮鐩稿悓鐨勫脊绐椼€?
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
        super(context);
        this.taskStateService = taskStateService;
        this.taskReminderDispatcher = taskReminderDispatcher;
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
        LOG.info("[PermissionHandler] 鏄剧ず鏉冮檺璇锋眰瀵硅瘽妗? " + request.getToolName());

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
            LOG.error("[PermissionHandler] 鏄剧ず鏉冮檺寮圭獥澶辫触: " + e.getMessage(), e);
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
        int askUserCount = pendingAskUserQuestionRequests.size();
        int planCount = pendingPlanApprovalRequests.size();

        // Cancel all pending permission requests
        for (Map.Entry<String, CompletableFuture<Integer>> entry : pendingPermissionRequests.entrySet()) {
            entry.getValue().complete(PermissionService.PermissionResponse.DENY.getValue());
        }
        pendingPermissionRequests.clear();

        // Cancel all pending AskUserQuestion requests
        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingAskUserQuestionRequests.entrySet()) {
            entry.getValue().complete(null);
        }
        pendingAskUserQuestionRequests.clear();

        // Cancel all pending PlanApproval requests
        for (Map.Entry<String, CompletableFuture<JsonObject>> entry : pendingPlanApprovalRequests.entrySet()) {
            JsonObject rejected = new com.google.gson.JsonObject();
            rejected.addProperty("approved", false);
            rejected.addProperty("targetMode", "default");
            rejected.addProperty("message", "Session changed");
            entry.getValue().complete(rejected);
        }
        pendingPlanApprovalRequests.clear();

        if (taskStateService != null) {
            // 娓?session 鏃朵笉浠呰娓呮帀寰呭鎵?future锛屼篃瑕佹妸鑱氬悎浠诲姟鐘舵€侀噸缃洖 PENDING锛?
            // 鍚﹀垯鏂扮殑浼氳瘽鍙兘缁ф壙涓婁竴杞?WAITING_CONFIRM / FINAL_ERROR 鐨勫熬鐘舵€併€?
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

        pendingAskUserQuestionRequests.put(requestId, future);

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
                    pendingAskUserQuestionRequests.remove(requestId);
                    // Return empty answers on timeout
                    future.complete(new JsonObject());
                }
            });

        } catch (Exception e) {
            LOG.error("[ASK_USER_QUESTION][SHOW_DIALOG] ERROR: " + e.getMessage(), e);
            pendingAskUserQuestionRequests.remove(requestId);
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

            CompletableFuture<JsonObject> pendingFuture = pendingAskUserQuestionRequests.remove(requestId);

            if (pendingFuture != null) {
                LOG.debug("[ASK_USER_QUESTION][HANDLE_RESPONSE] Completing future with answers: " + answers.toString());
                pendingFuture.complete(answers);
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

        pendingPlanApprovalRequests.put(requestId, future);
        planApprovalDialogVisible = false;
        if (taskStateService != null) {
            // 这里先按“审批弹窗即将占位”分发 WAITING_CONFIRM，
            // 避免 reminder popup 比真正的审批弹窗更早弹出。
            taskStateService.onPlanApprovalRequested(requestId);
            dispatchTaskReminder(true);
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
                    pendingPlanApprovalRequests.remove(requestId);
                    planApprovalDialogVisible = false;
                    if (taskStateService != null) {
                        // 瓒呮椂鍚庣洿鎺ヨ惤鍒?FINAL_ERROR锛岄伩鍏嶇晫闈竴鐩村仠鐣欏湪鈥滅瓑寰呯‘璁も€濈殑鍋囪薄銆?
                        taskStateService.onPlanApprovalTimedOut(requestId);
                        dispatchTaskReminder(false);
                    }
                    // Return rejection on timeout
                    JsonObject timeoutResponse = new JsonObject();
                    timeoutResponse.addProperty("approved", false);
                    timeoutResponse.addProperty("targetMode", "default");
                    timeoutResponse.addProperty("message", "Plan approval timed out");
                    future.complete(timeoutResponse);
                }
            });

        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][SHOW_DIALOG] ERROR: " + e.getMessage(), e);
            pendingPlanApprovalRequests.remove(requestId);
            planApprovalDialogVisible = false;
            JsonObject errorResponse = new JsonObject();
            errorResponse.addProperty("approved", false);
            errorResponse.addProperty("targetMode", "default");
            errorResponse.addProperty("message", "Error showing plan approval dialog");
            future.complete(errorResponse);
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

            CompletableFuture<JsonObject> pendingFuture = pendingPlanApprovalRequests.remove(requestId);

            if (pendingFuture != null) {
                JsonObject result = new JsonObject();
                result.addProperty("approved", approved);
                result.addProperty("targetMode", targetMode);
                LOG.debug("[PLAN_APPROVAL][HANDLE_RESPONSE] Completing future: approved=" + approved + ", targetMode=" + targetMode);
                pendingFuture.complete(result);
                planApprovalDialogVisible = false;
                if (taskStateService != null) {
                    // 瀹℃壒閫氳繃鍚庢仮澶?RUNNING锛涙嫆缁濆悗杩涘叆 CANCELLED銆?
                    // 杩欓噷涓嶇敤璁╁墠绔嚜琛岀寽娴嬬粨鏋滐紝缁熶竴鐢辩姸鎬佹湇鍔＄粰鍑虹粨璁恒€?
                    if (approved) {
                        taskStateService.onPlanApprovalApproved(requestId);
                    } else {
                        taskStateService.onPlanApprovalRejected(requestId, reason);
                    }
                    dispatchTaskReminder(false);
                }
            } else {
                LOG.warn("[PLAN_APPROVAL][HANDLE_RESPONSE] No pending request found for requestId: " + requestId);
            }
        } catch (Exception e) {
            LOG.error("[PLAN_APPROVAL][HANDLE_RESPONSE] ERROR: " + e.getMessage(), e);
        }
    }

    private void dispatchTaskReminder(boolean approvalDialogVisible) {
        if (taskReminderDispatcher != null && taskStateService != null) {
            // ReminderDispatcher 鏄敮涓€鐨勬彁閱掑垎鍙戝嚭鍙ｏ紝鍚庣浠讳綍鐘舵€佸彉鍖栭兘閫氳繃鍚屼竴璺緞鍙戝線
            // popup / balloon / status bar / sound锛岄伩鍏嶅涓?handler 閲嶅鍐冲畾閫氱煡绛栫暐銆?
            taskReminderDispatcher.dispatch(taskStateService.getCurrentSnapshot(), approvalDialogVisible);
        }
    }
}



