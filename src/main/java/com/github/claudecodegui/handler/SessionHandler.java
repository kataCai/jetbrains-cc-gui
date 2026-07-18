package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.model.NodeDetectionResult;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.session.SendRuntimeIntent;
import com.github.claudecodegui.session.SessionState;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskStateEvent;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 会话管理消息处理器。
 * 负责发送消息、会话切换与重置，同时把当前任务摘要同步到任务提醒和远程协作通道，
 * 让本地弹窗、状态栏、Telegram 看到的是同一份“当前问题”。
 */
public class SessionHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(SessionHandler.class);

    private static final String[] SUPPORTED_TYPES = {
            "send_message",
            "send_message_with_attachments",
            "interrupt_session",
            "restart_session"
            // Note: create_new_session should not be handled here; it should be handled by ClaudeSDKToolWindow.createNewSession()
    };

    private final TaskStateService taskStateService;
    private final TaskReminderDispatcher taskReminderDispatcher;
    private final RemoteCollabService remoteCollabService;
    private final SessionSendPreparation sessionSendPreparation;

    /**
     * 发送前会话准备回调。
     * 该回调允许 SessionHandler 在真正调用 `session.send(...)` 前，
     * 先让上层按本条消息携带的 runtimeIntent 决定是否需要静默切段并切换当前会话。
     */
    @FunctionalInterface
    public interface SessionSendPreparation {
        /**
         * 按当前消息携带的 runtimeIntent 准备本次发送要使用的会话。
         *
         * @param runtimeIntent 当前发送请求解析得到的运行时意图；为空时表示沿用现有会话
         * @return 准备完成后应被本次发送直接复用的会话；若无需切换则通常返回当前会话
         */
        CompletableFuture<ClaudeSession> prepareSessionForSend(SendRuntimeIntent runtimeIntent);
    }

    /**
     * 统一承载 send_message / send_message_with_attachments 解析结果。
     * 该对象只保留发送链路真正需要的稳定字段，避免普通文本发送与附件发送各自维护一套解析和调度逻辑。
     */
    private static final class SendRequestPayload {
        private final String prompt;
        private final List<ClaudeSession.Attachment> attachments;
        private final String agentPrompt;
        private final List<String> fileTagPaths;
        private final String requestedPermissionMode;
        private final SendRuntimeIntent runtimeIntent;

        /**
         * 构造统一发送载荷。
         *
         * @param prompt 用户输入的主文本内容
         * @param attachments 附件列表；普通文本发送时传入 null
         * @param agentPrompt 当前 Tab 绑定的 agent prompt；未指定时可为 null
         * @param fileTagPaths 需要注入上下文的文件绝对路径列表；没有时可为 null
         * @param requestedPermissionMode 本条消息请求的权限模式；为空时沿用会话模式
         * @param runtimeIntent 本条消息声明的 send-time runtime 意图；缺失时传空意图对象
         */
        private SendRequestPayload(
                String prompt,
                List<ClaudeSession.Attachment> attachments,
                String agentPrompt,
                List<String> fileTagPaths,
                String requestedPermissionMode,
                SendRuntimeIntent runtimeIntent
        ) {
            this.prompt = prompt;
            this.attachments = attachments;
            this.agentPrompt = agentPrompt;
            this.fileTagPaths = fileTagPaths;
            this.requestedPermissionMode = requestedPermissionMode;
            this.runtimeIntent = runtimeIntent != null ? runtimeIntent : SendRuntimeIntent.empty();
        }

        /**
         * 判断当前载荷是否属于附件发送链路。
         *
         * @return true 表示本次发送需要走带附件的 session.send 重载
         */
        private boolean hasAttachments() {
            return attachments != null;
        }
    }

    public SessionHandler(HandlerContext context) {
        this(context, null, null);
    }

    public SessionHandler(HandlerContext context, TaskStateService taskStateService) {
        this(context, taskStateService, null);
    }

    public SessionHandler(
        HandlerContext context,
        TaskStateService taskStateService,
        TaskReminderDispatcher taskReminderDispatcher
    ) {
        this(
            context,
            taskStateService,
            taskReminderDispatcher,
            RemoteCollabService.getInstance(),
            runtimeIntent -> CompletableFuture.completedFuture(context.getSession())
        );
    }

    /**
     * 构造支持发送前会话准备回调的 SessionHandler。
     * 生产链路可通过该入口把 send-time runtime switch 准备逻辑注入进来，
     * 这样普通 send_message / send_message_with_attachments 就能在真正发送前复用已切好的目标会话。
     *
     * @param context handler 上下文
     * @param taskStateService 任务状态服务；为空时跳过任务状态同步
     * @param taskReminderDispatcher 任务提醒分发器；为空时跳过提醒
     * @param sessionSendPreparation 发送前会话准备回调；为空时默认直接复用当前会话
     */
    public SessionHandler(
        HandlerContext context,
        TaskStateService taskStateService,
        TaskReminderDispatcher taskReminderDispatcher,
        SessionSendPreparation sessionSendPreparation
    ) {
        this(
            context,
            taskStateService,
            taskReminderDispatcher,
            RemoteCollabService.getInstance(),
            sessionSendPreparation
        );
    }

    SessionHandler(
        HandlerContext context,
        TaskStateService taskStateService,
        TaskReminderDispatcher taskReminderDispatcher,
        RemoteCollabService remoteCollabService
    ) {
        this(
            context,
            taskStateService,
            taskReminderDispatcher,
            remoteCollabService,
            runtimeIntent -> CompletableFuture.completedFuture(context.getSession())
        );
    }

    SessionHandler(
        HandlerContext context,
        TaskStateService taskStateService,
        TaskReminderDispatcher taskReminderDispatcher,
        RemoteCollabService remoteCollabService,
        SessionSendPreparation sessionSendPreparation
    ) {
        super(context);
        this.taskStateService = taskStateService;
        this.taskReminderDispatcher = taskReminderDispatcher;
        this.remoteCollabService = remoteCollabService;
        this.sessionSendPreparation = sessionSendPreparation != null
                ? sessionSendPreparation
                : runtimeIntent -> CompletableFuture.completedFuture(context.getSession());
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            case "send_message":
                LOG.debug("[SessionHandler] 处理: send_message");
                dispatchPlainSendMessage(content);
                return true;
            case "send_message_with_attachments":
                LOG.debug("[SessionHandler] 处理: send_message_with_attachments");
                dispatchAttachmentSendMessage(content);
                return true;
            case "interrupt_session":
                LOG.debug("[SessionHandler] 处理: interrupt_session");
                handleInterruptSession();
                return true;
            case "restart_session":
                LOG.debug("[SessionHandler] 处理: restart_session");
                handleRestartSession();
                return true;
            default:
                return false;
        }
    }

    /**
     * Resolves the cached Node.js version, attempting recovery when the cache is stale.
     * If the version is absent but a cached path exists, re-verifies the path to restore
     * the detection result (e.g. after a new window resets the cache via setNodeExecutable).
     *
     * @return the Node.js version string, or null if detection fails entirely
     */
    private String resolveNodeVersion() {
        String nodeVersion = context.getClaudeSDKBridge().getCachedNodeVersion();
        if (nodeVersion != null) {
            return nodeVersion;
        }
        // Version absent — try to recover using the cached path (path may still be valid).
        String cachedPath = context.getClaudeSDKBridge().getCachedNodePath();
        if (cachedPath == null || cachedPath.isEmpty()) {
            return null;
        }
        LOG.info("[SessionHandler] Node version cache miss, re-verifying path: " + cachedPath);
        NodeDetectionResult recovery = context.getClaudeSDKBridge().verifyAndCacheNodePath(cachedPath);
        if (recovery != null && recovery.isFound()) {
            return recovery.getNodeVersion();
        }
        return null;
    }

    /**
     * Send message to Claude
     * [FIX] Now parses JSON format to extract text, agent info and file tags
     */
    private void handleSendMessage(String content) {
        String nodeVersion = this.resolveNodeVersion();
        if (nodeVersion == null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("addErrorMessage", escapeJs("未检测到有效的 Node.js 版本，请在设置中配置或重新打开工具窗口。"));
            });
            return;
        }
        if (!NodeDetector.isVersionSupported(nodeVersion)) {
            int minVersion = NodeDetector.MIN_NODE_MAJOR_VERSION;
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("addErrorMessage", escapeJs(
                        "Node.js 版本过低 (" + nodeVersion + ")，插件需要 v" + minVersion + " 或更高版本才能正常运行。请在设置中配置正确的 Node.js 路径。"));
            });
            return;
        }

        // [FIX] Parse JSON format to extract text, agent info and file tags
        String prompt;
        String agentPrompt = null;
        java.util.List<String> fileTagPaths = null;
        String requestedPermissionMode = null;
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(content, JsonObject.class);
            prompt = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                             ? payload.get("text").getAsString()
                             : content; // Fallback to raw content if not JSON

            // Extract agent prompt from the message
            if (payload != null && payload.has("agent") && !payload.get("agent").isJsonNull()) {
                JsonObject agent = payload.getAsJsonObject("agent");
                if (agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[SessionHandler] Using agent from message: " + agentName);
                }
            }

            // [FIX] Extract file tags from the message (for Codex context injection)
            if (payload != null && payload.has("fileTags") && payload.get("fileTags").isJsonArray()) {
                JsonArray fileTagsArray = payload.getAsJsonArray("fileTags");
                fileTagPaths = new java.util.ArrayList<>();
                for (int i = 0; i < fileTagsArray.size(); i++) {
                    JsonObject fileTag = fileTagsArray.get(i).getAsJsonObject();
                    if (fileTag.has("absolutePath") && !fileTag.get("absolutePath").isJsonNull()) {
                        fileTagPaths.add(fileTag.get("absolutePath").getAsString());
                    }
                }
                if (!fileTagPaths.isEmpty()) {
                    LOG.info("[SessionHandler] Extracted " + fileTagPaths.size() + " file tags for context injection");
                }
            }

            // Extract requested permission mode from payload (optional, backward compatible)
            if (payload != null && payload.has("permissionMode") && !payload.get("permissionMode").isJsonNull()) {
                String mode = payload.get("permissionMode").getAsString();
                if (SessionState.isValidPermissionMode(mode)) {
                    requestedPermissionMode = mode;
                } else {
                    LOG.warn("[SessionHandler] Ignoring invalid permissionMode from payload: " + mode);
                }
            }
        } catch (Exception e) {
            // If parsing fails, treat content as plain text (backward compatibility)
            LOG.debug("[SessionHandler] Message is plain text, not JSON: " + e.getMessage());
            prompt = content;
        }

        final String finalPrompt = prompt;
        final String finalAgentPrompt = agentPrompt;
        final java.util.List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;

        CompletableFuture.runAsync(() -> {
            String currentWorkingDir = determineWorkingDirectory();
            String previousCwd = context.getSession().getCwd();

            if (!currentWorkingDir.equals(previousCwd)) {
                context.getSession().setCwd(currentWorkingDir);
                LOG.info("[SessionHandler] Updated working directory: " + currentWorkingDir);
            }

            // Capture project for use in async callbacks
            var project = context.getProject();
            if (project != null) {
                ClaudeNotifier.setWaiting(project);
            }
            // [FIX] Pass agent prompt and file tags directly to session
            CompletableFuture<Void> sendFuture = context.getSession().send(
                finalPrompt,
                finalAgentPrompt,
                finalFileTagPaths,
                finalRequestedPermissionMode
            );
            // 先让 session.send 把本轮用户消息写入会话内存态，再触发 RUNNING 提醒；
            // 否则提醒摘要会读取到上一轮最后一条消息，表现为“通知慢一拍”。 
            notifySendStarted(finalPrompt);

            sendFuture
                .thenRun(() -> {
                    notifySendCompleted();
                    // completed 的主通知出口已经统一收口到 TaskReminderDispatcher。
                    // 这里不再为 Codex 额外直发 ClaudeNotifier.showSuccess，避免同一轮完成
                    // 既走状态机提醒链，又走旧的系统 toast 链，导致用户收到两次完成通知。
                })
                .exceptionally(ex -> {
                    notifySendFailed(ex);
                    LOG.error("Failed to send message", ex);
                    if (project != null) {
                        ClaudeNotifier.showError(
                            project,
                            ClaudeCodeGuiBundle.message(
                                "task.send.failed",
                                ex != null ? ex.getMessage() : ClaudeCodeGuiBundle.message("file.unknownError")
                            )
                        );
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callJavaScript("addErrorMessage", escapeJs("发送失败: " + ex.getMessage()));
                    });
                    return null;
                    });
        });
    }

    /**
     * Send message with attachments.
     * [FIX] Now extracts agent info and file tags from payload.
     */
    private void handleSendMessageWithAttachments(String content) {
        try {
            Gson gson = new Gson();
            JsonObject payload = gson.fromJson(content, JsonObject.class);
            String text = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                                  ? payload.get("text").getAsString()
                                  : "";

            java.util.List<ClaudeSession.Attachment> atts = new java.util.ArrayList<>();
            if (payload != null && payload.has("attachments") && payload.get("attachments").isJsonArray()) {
                JsonArray arr = payload.getAsJsonArray("attachments");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject a = arr.get(i).getAsJsonObject();
                    String fileName = a.has("fileName") && !a.get("fileName").isJsonNull()
                                              ? a.get("fileName").getAsString()
                                              : ("attachment-" + System.currentTimeMillis());
                    String mediaType = a.has("mediaType") && !a.get("mediaType").isJsonNull()
                                               ? a.get("mediaType").getAsString()
                                               : "application/octet-stream";
                    String data = a.has("data") && !a.get("data").isJsonNull()
                                          ? a.get("data").getAsString()
                                          : "";
                    atts.add(new ClaudeSession.Attachment(fileName, mediaType, data));
                }
            }

            // [FIX] Extract agent prompt from the payload for per-tab agent selection
            String agentPrompt = null;
            String requestedPermissionMode = null;
            if (payload != null && payload.has("agent") && !payload.get("agent").isJsonNull()) {
                JsonObject agent = payload.getAsJsonObject("agent");
                if (agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[SessionHandler] Using agent from attachment message: " + agentName);
                }
            }

            // [FIX] Extract file tags from the payload (for Codex context injection)
            java.util.List<String> fileTagPaths = null;
            if (payload != null && payload.has("fileTags") && payload.get("fileTags").isJsonArray()) {
                JsonArray fileTagsArray = payload.getAsJsonArray("fileTags");
                fileTagPaths = new java.util.ArrayList<>();
                for (int i = 0; i < fileTagsArray.size(); i++) {
                    JsonObject fileTag = fileTagsArray.get(i).getAsJsonObject();
                    if (fileTag.has("absolutePath") && !fileTag.get("absolutePath").isJsonNull()) {
                        fileTagPaths.add(fileTag.get("absolutePath").getAsString());
                    }
                }
                if (!fileTagPaths.isEmpty()) {
                    LOG.info("[SessionHandler] Extracted " + fileTagPaths.size() + " file tags for attachment message");
                }
            }

            if (payload != null && payload.has("permissionMode") && !payload.get("permissionMode").isJsonNull()) {
                String mode = payload.get("permissionMode").getAsString();
                if (SessionState.isValidPermissionMode(mode)) {
                    requestedPermissionMode = mode;
                } else {
                    LOG.warn("[SessionHandler] Ignoring invalid permissionMode from attachment payload: " + mode);
                }
            }

            sendMessageWithAttachments(text, atts, agentPrompt, fileTagPaths, requestedPermissionMode);
        } catch (Exception e) {
            LOG.error("[SessionHandler] 解析附件负载失败: " + e.getMessage(), e);
            handleSendMessage(content);
        }
    }

    /**
     * Send message with attachments to Claude
     * [FIX] Now accepts agent prompt and file tags parameters
     */
    private void sendMessageWithAttachments(
        String prompt,
        List<ClaudeSession.Attachment> attachments,
        String agentPrompt,
        java.util.List<String> fileTagPaths,
        String requestedPermissionMode
    ) {
        // Version check (consistent with handleSendMessage)
        String nodeVersion = this.resolveNodeVersion();
        if (nodeVersion == null) {
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("addErrorMessage", escapeJs("未检测到有效的 Node.js 版本，请在设置中配置或重新打开工具窗口。"));
            });
            return;
        }
        if (!NodeDetector.isVersionSupported(nodeVersion)) {
            int minVersion = NodeDetector.MIN_NODE_MAJOR_VERSION;
            ApplicationManager.getApplication().invokeLater(() -> {
                callJavaScript("addErrorMessage", escapeJs(
                        "Node.js 版本过低 (" + nodeVersion + ")，插件需要 v" + minVersion + " 或更高版本才能正常运行。请在设置中配置正确的 Node.js 路径。"));
            });
            return;
        }

        final String finalAgentPrompt = agentPrompt;
        final java.util.List<String> finalFileTagPaths = fileTagPaths;
        final String finalRequestedPermissionMode = requestedPermissionMode;

        CompletableFuture.runAsync(() -> {
            String currentWorkingDir = determineWorkingDirectory();
            String previousCwd = context.getSession().getCwd();
            if (!currentWorkingDir.equals(previousCwd)) {
                context.getSession().setCwd(currentWorkingDir);
                LOG.info("[SessionHandler] Updated working directory: " + currentWorkingDir);
            }

            // Capture project for use in async callbacks
            var project = context.getProject();
            if (project != null) {
                ClaudeNotifier.setWaiting(project);
            }
            // [FIX] Pass agent prompt and file tags directly to session
            CompletableFuture<Void> sendFuture = context.getSession().send(
                prompt,
                attachments,
                finalAgentPrompt,
                finalFileTagPaths,
                finalRequestedPermissionMode
            );
            // 附件发送也需要等 session 先写入当前这轮用户消息，
            // 否则 RUNNING 提醒会沿用上一轮摘要。
            notifySendStarted(prompt);

            sendFuture
                .thenRun(() -> {
                    notifySendCompleted();
                    // 附件发送完成后的 completed 提醒同样统一交给 TaskReminderDispatcher，
                    // 不再额外直发旧成功 toast，防止与统一任务提醒链重复。
                })
                .exceptionally(ex -> {
                    notifySendFailed(ex);
                    LOG.error("Failed to send message with attachments", ex);
                    if (project != null) {
                        ClaudeNotifier.showError(
                            project,
                            ClaudeCodeGuiBundle.message(
                                "task.send.failed",
                                ex != null ? ex.getMessage() : ClaudeCodeGuiBundle.message("file.unknownError")
                            )
                        );
                    }
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callJavaScript("addErrorMessage", escapeJs("发送失败: " + ex.getMessage()));
                    });
                    return null;
                    });
        });
    }

    /**
     * 处理普通文本发送的新统一入口。
     * 这里不会再直接依赖当前 live session 的 runtime，而是先解析出本条消息的 runtimeIntent，
     * 再交给统一的 send-time preparation 链路决定是否需要静默切段。
     *
     * @param content 前端发来的原始消息载荷；可能是 JSON，也可能是旧版纯文本
     */
    private void dispatchPlainSendMessage(String content) {
        String prompt = content;
        JsonObject payload = null;
        try {
            payload = new Gson().fromJson(content, JsonObject.class);
            if (payload != null && payload.has("text") && !payload.get("text").isJsonNull()) {
                prompt = payload.get("text").getAsString();
            }
        } catch (Exception e) {
            // 中文注释：旧版前端仍可能直接发送纯文本。
            // 此时继续把整段文本作为 prompt，保持历史兼容。
            LOG.debug("[SessionHandler] Message is plain text, not JSON: " + e.getMessage());
        }
        try {
            dispatchParsedSendRequest(buildSendRequestPayloadForDispatch(payload, prompt, null));
        } catch (Exception e) {
            LOG.error("[SessionHandler] Failed to prepare plain send payload: " + e.getMessage(), e);
            reportSendPayloadPreparationFailed(e);
        }
    }

    /**
     * 处理带附件发送的新统一入口。
     * 若附件载荷解析失败，则回退到普通文本入口，避免因老版本 payload 或临时异常直接丢失用户发送。
     *
     * @param content 前端发来的附件消息 JSON 载荷
     */
    private void dispatchAttachmentSendMessage(String content) {
        try {
            JsonObject payload = new Gson().fromJson(content, JsonObject.class);
            String prompt = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                    ? payload.get("text").getAsString()
                    : "";
            try {
                dispatchParsedSendRequest(
                        buildSendRequestPayloadForDispatch(payload, prompt, extractAttachmentsFromPayload(payload))
                );
            } catch (Exception e) {
                LOG.error("[SessionHandler] Failed to prepare attachment send payload: " + e.getMessage(), e);
                reportSendPayloadPreparationFailed(e);
            }
        } catch (Exception e) {
            LOG.error("[SessionHandler] 解析附件载荷失败: " + e.getMessage(), e);
            dispatchPlainSendMessage(content);
        }
    }

    /**
     * 把前端 payload 归一化为统一发送载荷。
     * 这里集中解析 agent、fileTags、permissionMode 与 runtimeIntent，确保普通文本与附件消息共用同一套协议消费逻辑。
     *
     * @param payload 已解析的 JSON 载荷；旧版纯文本场景可为 null
     * @param fallbackPrompt 当 payload 中没有 text 字段时使用的回退文本
     * @param attachments 附件列表；普通文本发送时传入 null
     * @return 统一后的发送载荷
     */
    private SendRequestPayload buildSendRequestPayloadForDispatch(
            JsonObject payload,
            String fallbackPrompt,
            List<ClaudeSession.Attachment> attachments
    ) {
        String prompt = payload != null && payload.has("text") && !payload.get("text").isJsonNull()
                ? payload.get("text").getAsString()
                : fallbackPrompt;

        String agentPrompt = null;
        if (payload != null && payload.has("agent") && !payload.get("agent").isJsonNull()) {
            JsonObject agent = payload.getAsJsonObject("agent");
            if (agent != null && agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                agentPrompt = agent.get("prompt").getAsString();
                String agentName = agent.has("name") && !agent.get("name").isJsonNull()
                        ? agent.get("name").getAsString()
                        : "Unknown";
                LOG.info("[SessionHandler] Using agent from message: " + agentName);
            }
        }

        List<String> fileTagPaths = null;
        if (payload != null && payload.has("fileTags") && payload.get("fileTags").isJsonArray()) {
            JsonArray fileTagsArray = payload.getAsJsonArray("fileTags");
            fileTagPaths = new java.util.ArrayList<>();
            for (int i = 0; i < fileTagsArray.size(); i++) {
                JsonObject fileTag = fileTagsArray.get(i).getAsJsonObject();
                if (fileTag.has("absolutePath") && !fileTag.get("absolutePath").isJsonNull()) {
                    fileTagPaths.add(fileTag.get("absolutePath").getAsString());
                }
            }
            if (!fileTagPaths.isEmpty()) {
                LOG.info("[SessionHandler] Extracted " + fileTagPaths.size() + " file tags for context injection");
            }
        }

        String requestedPermissionMode = null;
        if (payload != null && payload.has("permissionMode") && !payload.get("permissionMode").isJsonNull()) {
            String mode = payload.get("permissionMode").getAsString();
            if (SessionState.isValidPermissionMode(mode)) {
                requestedPermissionMode = mode;
            } else {
                LOG.warn("[SessionHandler] Ignoring invalid permissionMode from payload: " + mode);
            }
        }

        SendRuntimeIntent parsedRuntimeIntent = SendRuntimeIntent.fromPayload(payload);
        SendRuntimeIntent.ModelTierResolutionResult modelTierResolution = parsedRuntimeIntent.resolveTargetModelTier();
        SendRuntimeIntent runtimeIntent = modelTierResolution.getResolvedIntent();
        if (parsedRuntimeIntent.hasTargetModelTier()) {
            LOG.info("[SessionHandler] runtimeIntentModelTierResolved"
                    + ", targetModelTier=" + parsedRuntimeIntent.getTargetModelTier()
                    + ", resolvedProvider=" + runtimeIntent.getTargetProvider()
                    + ", resolvedModel=" + runtimeIntent.getTargetModel()
                    + ", resolvedReasoningEffort=" + runtimeIntent.getTargetReasoningEffort()
                    + ", mappingSource=" + modelTierResolution.getMappingSource()
                    + ", resolvedFromTier=" + modelTierResolution.isResolvedFromTier());
        }
        LOG.info("[SessionHandler] sendRuntimeIntentParsed runtimeIntent=" + runtimeIntent.toLogString()
                + ", hasAttachments=" + (attachments != null)
                + ", fileTagCount=" + (fileTagPaths != null ? fileTagPaths.size() : 0));

        return new SendRequestPayload(
                prompt,
                attachments,
                agentPrompt,
                fileTagPaths,
                requestedPermissionMode,
                runtimeIntent
        );
    }

    /**
     * 统一向前端报告“发送前 payload 归一化/运行时意图解析失败”。
     * 这类失败发生在真正进入 session.send 之前，因此不能依赖后续异步发送链路的失败回调兜底。
     *
     * @param error 当前解析阶段抛出的异常
     */
    private void reportSendPayloadPreparationFailed(Exception error) {
        String message = error != null && error.getMessage() != null
                ? error.getMessage()
                : "Unknown send payload preparation error";
        resetFrontendLoadingAfterSendFailure();
        ApplicationManager.getApplication().invokeLater(() ->
                callJavaScript("addErrorMessage", escapeJs("发送失败: " + message))
        );
    }

    /**
     * 解析附件列表。
     * 该方法只负责结构转换，不记录附件正文内容，避免诊断日志误带出大体积或敏感数据。
     *
     * @param payload 前端传来的附件消息 JSON 载荷
     * @return 归一化后的附件列表；无附件时返回空列表
     */
    private List<ClaudeSession.Attachment> extractAttachmentsFromPayload(JsonObject payload) {
        List<ClaudeSession.Attachment> attachments = new java.util.ArrayList<>();
        if (payload == null || !payload.has("attachments") || !payload.get("attachments").isJsonArray()) {
            return attachments;
        }
        JsonArray array = payload.getAsJsonArray("attachments");
        for (int i = 0; i < array.size(); i++) {
            JsonObject attachment = array.get(i).getAsJsonObject();
            String fileName = attachment.has("fileName") && !attachment.get("fileName").isJsonNull()
                    ? attachment.get("fileName").getAsString()
                    : ("attachment-" + System.currentTimeMillis());
            String mediaType = attachment.has("mediaType") && !attachment.get("mediaType").isJsonNull()
                    ? attachment.get("mediaType").getAsString()
                    : "application/octet-stream";
            String data = attachment.has("data") && !attachment.get("data").isJsonNull()
                    ? attachment.get("data").getAsString()
                    : "";
            attachments.add(new ClaudeSession.Attachment(fileName, mediaType, data));
        }
        return attachments;
    }

    /**
     * 统一执行发送前检查、send-time runtime preparation 与真实发送。
     * 相比旧链路，这里会先让上层根据 runtimeIntent 准备目标会话，再把本次发送直接落到准备后的 session 上。
     *
     * @param request 统一发送载荷
     */
    private void dispatchParsedSendRequest(SendRequestPayload request) {
        String nodeVersion = this.resolveNodeVersion();
        if (nodeVersion == null) {
            reportMissingNodeVersion();
            return;
        }
        if (!NodeDetector.isVersionSupported(nodeVersion)) {
            reportUnsupportedNodeVersion(nodeVersion);
            return;
        }

        final String finalPrompt = request.prompt;
        final var project = context.getProject();
        CompletableFuture
                .supplyAsync(this::determineWorkingDirectory)
                .thenCompose(currentWorkingDir ->
                        sessionSendPreparation.prepareSessionForSend(request.runtimeIntent)
                                .thenCompose(preparedSession -> {
                                    ClaudeSession targetSession = preparedSession != null
                                            ? preparedSession
                                            : context.getSession();
                                    if (targetSession == null) {
                                        CompletableFuture<Void> failed = new CompletableFuture<>();
                                        failed.completeExceptionally(
                                                new IllegalStateException("Session is unavailable for send")
                                        );
                                        return failed;
                                    }

                                    String previousCwd = targetSession.getCwd();
                                    if (!currentWorkingDir.equals(previousCwd)) {
                                        targetSession.setCwd(currentWorkingDir);
                                        LOG.info("[SessionHandler] Updated working directory: " + currentWorkingDir);
                                    }

                                    if (project != null) {
                                        ClaudeNotifier.setWaiting(project);
                                    }

                                    CompletableFuture<Void> sendFuture = request.hasAttachments()
                                            ? targetSession.send(
                                                    request.prompt,
                                                    request.attachments,
                                                    request.agentPrompt,
                                                    request.fileTagPaths,
                                                    request.requestedPermissionMode
                                            )
                                            : targetSession.send(
                                                    request.prompt,
                                                    request.agentPrompt,
                                                    request.fileTagPaths,
                                                    request.requestedPermissionMode
                                            );
                                    // 中文注释：必须在 session.send 已经把本轮用户消息写入会话之后再标记 RUNNING，
                                    // 否则提醒摘要会继续读取上一轮消息，出现“通知慢一拍”的错位。
                                    notifySendStarted(finalPrompt);
                                    return sendFuture;
                                })
                )
                .thenRun(this::notifySendCompleted)
                .exceptionally(ex -> {
                    Throwable rootCause = unwrapThrowable(ex);
                    // 中文注释：send-time 链路一旦失败，先立即关闭前端 loading，
                    // 避免后续任务状态更新、系统通知或错误提示中的任一步骤抛错时，
                    // 把 WebView 永久留在“响应中”状态。
                    resetFrontendLoadingAfterSendFailure();
                    notifySendFailed(rootCause);
                    LOG.error("Failed to send message", rootCause);
                    reportSendFailure(project, rootCause);
                    return null;
                });
    }

    /**
     * 在 Node.js 版本缺失时向前端提示错误。
     */
    private void reportMissingNodeVersion() {
        resetFrontendLoadingAfterSendFailure();
        ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("addErrorMessage", escapeJs("未检测到有效的 Node.js 版本，请在设置中配置或重新打开工具窗口。"));
        });
    }

    /**
     * 在 Node.js 版本过低时向前端提示错误。
     *
     * @param nodeVersion 当前检测到的 Node.js 版本
     */
    private void reportUnsupportedNodeVersion(String nodeVersion) {
        int minVersion = NodeDetector.MIN_NODE_MAJOR_VERSION;
        resetFrontendLoadingAfterSendFailure();
        ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("addErrorMessage", escapeJs(
                    "Node.js 版本过低 (" + nodeVersion + ")，插件需要 v" + minVersion + " 或更高版本才能正常运行。请在设置中配置正确的 Node.js 路径。"));
        });
    }

    /**
     * 统一上报发送失败。
     * 这里同时负责系统通知与前端错误消息，避免 send-time runtime preparation 失败时只落日志、不反馈用户。
     *
     * @param project 当前工程；为空时仅回推前端错误消息
     * @param throwable 触发失败的真实异常
     */
    private void reportSendFailure(com.intellij.openapi.project.Project project, Throwable throwable) {
        if (project != null) {
            ClaudeNotifier.showError(
                    project,
                    ClaudeCodeGuiBundle.message(
                            "task.send.failed",
                            throwable != null && throwable.getMessage() != null
                                    ? throwable.getMessage()
                                    : ClaudeCodeGuiBundle.message("file.unknownError")
                    )
            );
        }
        String message = throwable != null && throwable.getMessage() != null
                ? throwable.getMessage()
                : ClaudeCodeGuiBundle.message("file.unknownError");
        resetFrontendLoadingAfterSendFailure();
        ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("addErrorMessage", escapeJs("发送失败: " + message));
        });
    }

    /**
     * 在 send-time 失败路径中统一关闭前端 loading。
     * 这类失败可能发生在真正进入 provider 流式阶段之前，因此这里只清理 loading，
     * 不伪造 `onStreamEnd`，避免把未开始的流错误上报为一次真实的 turn 级结束事件。
     */
    private void resetFrontendLoadingAfterSendFailure() {
        callJavaScript("showLoading", "false");
    }

    /**
     * 解包 CompletableFuture 链路抛出的包装异常。
     *
     * @param throwable CompletableFuture 传播出来的原始异常
     * @return 尽量靠近根因的真实异常；若原始异常为空则返回 null
     */
    private Throwable unwrapThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof java.util.concurrent.CompletionException
                && current.getCause() != null
                && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Interrupt the current session.
     */
    private void handleInterruptSession() {
        context.getSession().interrupt().thenRun(() -> {
            if (taskStateService != null) {
                // 用户主动中断时记为 CANCELLED，而不是 FAILED。
                // 后续提醒策略可以据此决定是否只更新状态栏、不打断用户。
                taskStateService.onCancelled(getSessionId(), "interrupt_session");
                dispatchTaskReminder(false);
                publishRemoteTaskEvent(null);
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                // [FIX] Notify frontend that stream has ended and reset loading state
                // This ensures streamActive flag is reset and loading=false takes effect
                context.callJavaScript("onStreamEnd");
                context.callJavaScript("showLoading", "false");
            });
        });
    }

    /**
     * Restart the session.
     */
    private void handleRestartSession() {
        context.getSession().restart().thenRun(() -> {
            if (taskStateService != null) {
                // restart 对用户来说意味着“放弃上一轮并重新开始”，
                // 因此先结束旧轮状态，再由下一次 send_started 拉起新一轮 RUNNING。
                taskStateService.onCancelled(getSessionId(), "restart_session");
                dispatchTaskReminder(false);
                publishRemoteTaskEvent(null);
            }
            ApplicationManager.getApplication().invokeLater(() -> {});
        });
    }

    private void notifySendStarted(String preferredTaskSummary) {
        if (taskStateService != null) {
            taskStateService.onSendStarted(getSessionId());
            LOG.info(
                "[TaskLifecycle] eventType=send_started"
                    + ", eventSource=SessionHandler.notifySendStarted"
                    + ", provider=" + context.getSession().getProvider()
                    + ", sessionId=" + getSessionId()
                    + ", taskState=" + taskStateService.getCurrentSnapshot().getState().getValue()
                    + ", summary=" + (preferredTaskSummary != null ? preferredTaskSummary : "(none)")
            );
            dispatchTaskReminder(false, preferredTaskSummary);
            publishRemoteTaskEvent(preferredTaskSummary);
        }
    }

    /**
     * 接收 provider 透传的自动重试中间态，并同步写入统一任务状态链。
     * 当前只保留轻量级原因摘要，优先保证 RETRYING 能被提醒系统、状态栏和远程协作消费。
     *
     * @param reason provider 侧给出的重试原因或重试摘要
     * @return 无返回值
     */
    public void notifyRetrying(String reason) {
        if (taskStateService != null) {
            taskStateService.onRetrying(getSessionId(), reason);
            dispatchTaskReminder(false);
            publishRemoteTaskEvent(null);
        }
    }

    /**
     * 收口一次 send 的成功完成状态。
     * 如果 provider 在成功结果里显式标记了“恢复后完成”，这里会先写入 RECOVERED，
     * 再进入 COMPLETED，避免把恢复场景再次压平成普通成功。
     */
    private void notifySendCompleted() {
        if (taskStateService != null) {
            String sessionId = getSessionId();
            ClaudeSession session = context.getSession();
            if (session != null && session.getState().isLastRecovered()) {
                String recoveryReason = buildRecoveryReason(session);
                taskStateService.onRecovered(sessionId, recoveryReason);
                dispatchTaskReminder(false);
            }
            taskStateService.onSendCompleted(sessionId);
            LOG.info(
                "[TaskLifecycle] eventType=task_completed"
                    + ", eventSource=SessionHandler.notifySendCompleted"
                    + ", provider=" + (session != null ? session.getProvider() : "(none)")
                    + ", sessionId=" + sessionId
                    + ", taskState=" + taskStateService.getCurrentSnapshot().getState().getValue()
                    + ", busy=" + (session != null && session.getState() != null && session.getState().isBusy())
                    + ", loading=" + (session != null && session.getState() != null && session.getState().isLoading())
            );
            dispatchTaskReminder(false);
            publishRemoteTaskEvent(null);
            if (session != null) {
                session.getState().clearLastRecoveryMetadata();
            }
        }
        // completed 只允许由 send 最终成功收口时显式触发，
        // 不能再借道 stream_end / onStreamEnd 等 turn 级信号回推。
        context.callJavaScript("onTaskCompleted");
    }

    private void notifySendFailed(Throwable throwable) {
        if (taskStateService != null) {
            // Codex 恢复链路会把“可取消”“可恢复”的场景编码进异常文本。
            // 这里先做最小解析，把状态机从“一律最终失败”升级为可区分取消/恢复/失败。
            String reason = throwable != null ? throwable.getMessage() : "send_failed";
            if (reason != null && (
                reason.contains("recoveryAction=mark_cancelled")
                    || reason.contains("User interrupted")
                    || reason.contains("interrupt_session")
            )) {
                taskStateService.onCancelled(getSessionId(), reason);
            } else if (reason != null && reason.contains("recovered=true")) {
                taskStateService.onRecovered(getSessionId(), reason);
                taskStateService.onSendCompleted(getSessionId());
            } else {
                // 失败原因尽量沿用真实异常，方便前端弹窗、状态栏和日志看到同一份上下文。
                taskStateService.onSendFailed(getSessionId(), reason);
            }
            LOG.info(
                "[TaskLifecycle] eventType=task_failed"
                    + ", eventSource=SessionHandler.notifySendFailed"
                    + ", provider=" + context.getSession().getProvider()
                    + ", sessionId=" + getSessionId()
                    + ", taskState=" + taskStateService.getCurrentSnapshot().getState().getValue()
                    + ", reason=" + reason
            );
            dispatchTaskReminder(false);
            publishRemoteTaskEvent(null);
        }
    }

    /**
     * 把 provider 成功结果中的恢复元信息压缩成任务状态可读原因。
     * 这里只暴露分类和动作两个字段，避免把 Node 侧完整错误噪音再次回灌到提醒文案里。
     */
    private String buildRecoveryReason(ClaudeSession session) {
        if (session == null) {
            return "recovered";
        }
        String category = session.getState().getLastRecoveryCategory();
        String action = session.getState().getLastRecoveryAction();
        if ((category == null || category.trim().isEmpty()) && (action == null || action.trim().isEmpty())) {
            return "recovered";
        }
        return "recovered"
            + (category != null && !category.trim().isEmpty() ? " | category=" + category : "")
            + (action != null && !action.trim().isEmpty() ? " | action=" + action : "");
    }

    private void dispatchTaskReminder(boolean approvalDialogOpen) {
        dispatchTaskReminder(approvalDialogOpen, null);
    }

    private void dispatchTaskReminder(boolean approvalDialogOpen, String preferredTaskSummary) {
        if (taskReminderDispatcher != null && taskStateService != null) {
            taskReminderDispatcher.dispatch(
                taskStateService.getCurrentSnapshot(),
                approvalDialogOpen,
                preferredTaskSummary
            );
        }
    }

    private void publishRemoteTaskEvent(String preferredTaskSummary) {
        if (remoteCollabService == null || taskStateService == null) {
            return;
        }
        TaskStateSnapshot snapshot = taskStateService.getCurrentSnapshot();
        if (snapshot == null) {
            return;
        }
        remoteCollabService.publishTaskEvent(new RemoteTaskEvent(
            snapshot.getSessionId() != null ? snapshot.getSessionId() : getSessionId(),
            resolveProjectPath(),
            snapshot.getRequestId(),
            snapshot.getState().getValue(),
            snapshot.getState().getValue(),
            resolveRemoteEventSummary(snapshot, preferredTaskSummary)
        ));
    }

    private String resolveProjectPath() {
        if (context.getProject() != null && context.getProject().getBasePath() != null) {
            return context.getProject().getBasePath();
        }
        ClaudeSession session = context.getSession();
        return session != null ? session.getCwd() : null;
    }

    private String resolveRemoteEventSummary(TaskStateSnapshot snapshot, String preferredTaskSummary) {
        if (preferredTaskSummary != null && !preferredTaskSummary.trim().isEmpty()) {
            return preferredTaskSummary;
        }
        ClaudeSession session = context.getSession();
        if (session != null) {
            List<ClaudeSession.Message> messages = session.getMessages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                ClaudeSession.Message message = messages.get(i);
                if (message != null && message.type == ClaudeSession.Message.Type.USER && message.content != null) {
                    return message.content;
                }
            }
        }
        TaskStateEvent latestEvent = snapshot.getLatestEvent();
        return latestEvent != null ? latestEvent.getReason() : null;
    }

    private String getSessionId() {
        // 统一从当前 session 取 id，避免调用方各自判空后拼 reason，
        // 也方便后续如果 sessionId 获取方式调整时只改这里。
        ClaudeSession session = context.getSession();
        return session != null ? session.getSessionId() : null;
    }

    /**
     * Determine the appropriate working directory.
     */
    private String determineWorkingDirectory() {
        String projectPath = context.getProject().getBasePath();

        // Prefer the user-configured working directory first
        // (relative paths are resolved only when projectPath is valid).
        if (projectPath != null && new File(projectPath).exists()) {
            try {
                com.github.claudecodegui.settings.CodemossSettingsService settingsService =
                        new com.github.claudecodegui.settings.CodemossSettingsService();
                String customWorkingDir = settingsService.getCustomWorkingDirectory(projectPath);

                if (customWorkingDir != null && !customWorkingDir.isEmpty()) {
                    // Resolve relative paths against the project root.
                    File workingDirFile = new File(customWorkingDir);
                    if (!workingDirFile.isAbsolute()) {
                        workingDirFile = new File(projectPath, customWorkingDir);
                    }

                    // Validate that the directory exists.
                    if (workingDirFile.exists() && workingDirFile.isDirectory()) {
                        String resolvedPath = workingDirFile.getAbsolutePath();
                        LOG.info("[SessionHandler] Using custom working directory: " + resolvedPath);
                        return resolvedPath;
                    } else {
                        LOG.warn("[SessionHandler] Custom working directory does not exist: " + workingDirFile.getAbsolutePath() + ", falling back");
                    }
                }
            } catch (Exception e) {
                LOG.warn("[SessionHandler] Failed to read custom working directory: " + e.getMessage());
            }
        }

        // When projectPath is invalid (null or missing), try the active file's
        // parent directory first — typical case: single-file temporary project
        // (projectPath in /tmp) while the actual file is under the user's home.
        if (projectPath == null || !new File(projectPath).exists()) {
            String activeFileDir = resolveWorkingDirectoryFromActiveFile(projectPath);
            if (activeFileDir != null && !activeFileDir.isEmpty()) {
                return activeFileDir;
            }
            String userHome = PlatformUtils.getHomeDirectory();
            LOG.warn("[SessionHandler] Using user home directory as fallback: " + userHome);
            return userHome;
        }

        // Use project root as the default working directory.
        return projectPath;
    }

    /**
     * Tries to infer a working directory from the currently active file.
     * Returns the parent directory only when the file is outside project root;
     * otherwise returns null.
     */
    private String resolveWorkingDirectoryFromActiveFile(String projectPath) {
        try {
            VirtualFile[] selectedFiles = ApplicationManager.getApplication().runReadAction(
                    (com.intellij.openapi.util.Computable<VirtualFile[]>) () ->
                            FileEditorManager.getInstance(context.getProject()).getSelectedFiles()
            );
            if (selectedFiles == null || selectedFiles.length == 0) {
                return null;
            }

            for (VirtualFile selectedFile : selectedFiles) {
                if (selectedFile == null || !selectedFile.isInLocalFileSystem()) {
                    continue;
                }

                String selectedPath = selectedFile.getPath();
                if (selectedPath == null || selectedPath.isEmpty()) {
                    continue;
                }

                File localFile = new File(selectedPath);
                if (!localFile.exists()) {
                    continue;
                }

                String filePath = localFile.getAbsolutePath();
                String candidateDir = localFile.isDirectory()
                        ? filePath
                        : localFile.getParent();
                if (candidateDir == null || candidateDir.isEmpty()) {
                    continue;
                }

                if (projectPath != null && !projectPath.isEmpty() && isPathWithin(filePath, projectPath)) {
                    continue;
                }

                LOG.info("[SessionHandler] Active file is outside project root, using its parent as working directory: "
                        + candidateDir + " (activeFile=" + filePath + ", projectPath=" + projectPath + ")");
                return candidateDir;
            }
        } catch (Exception e) {
            LOG.debug("[SessionHandler] Failed to resolve working directory from active file: " + e.getMessage());
        }

        return null;
    }

    /**
     * Checks whether childPath is inside basePath (including equality).
     */
    private boolean isPathWithin(String childPath, String basePath) {
        if (childPath == null || basePath == null) {
            return false;
        }

        try {
            Path child = Paths.get(childPath).toAbsolutePath().normalize();
            Path base = Paths.get(basePath).toAbsolutePath().normalize();
            return child.startsWith(base);
        } catch (Exception ignored) {
            return childPath.startsWith(basePath);
        }
    }
}
