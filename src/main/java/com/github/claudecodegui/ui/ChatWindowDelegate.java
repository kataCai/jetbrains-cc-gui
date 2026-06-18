package com.github.claudecodegui.ui;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeBalloonNotifier;
import com.github.claudecodegui.notifications.SystemReminderNotifier;
import com.github.claudecodegui.notifications.TaskReminderPayloadFactory;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.session.SessionRuntimeFamily;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.handler.AgentHandler;
import com.github.claudecodegui.handler.ClipboardHandler;
import com.github.claudecodegui.handler.ContextHandler;
import com.github.claudecodegui.handler.CodexMcpServerHandler;
import com.github.claudecodegui.handler.DependencyHandler;
import com.github.claudecodegui.handler.DiffHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.history.HistoryHandler;
import com.github.claudecodegui.handler.McpServerHandler;
import com.github.claudecodegui.handler.core.MessageDispatcher;
import com.github.claudecodegui.handler.PermissionHandler;
import com.github.claudecodegui.handler.PromptEnhancerHandler;
import com.github.claudecodegui.handler.PromptHandler;
import com.github.claudecodegui.handler.provider.ProviderHandler;
import com.github.claudecodegui.handler.RewindHandler;
import com.github.claudecodegui.handler.SessionHandler;
import com.github.claudecodegui.handler.SettingsHandler;
import com.github.claudecodegui.handler.SkillHandler;
import com.github.claudecodegui.handler.TabHandler;
import com.github.claudecodegui.handler.TaskReminderNavigationHandler;
import com.github.claudecodegui.handler.WindowEventHandler;
import com.github.claudecodegui.notifications.CcgTaskNavigator;
import com.github.claudecodegui.handler.file.FileExportHandler;
import com.github.claudecodegui.handler.file.FileHandler;
import com.github.claudecodegui.handler.file.OpenClassHandler;
import com.github.claudecodegui.handler.file.UndoFileHandler;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskReminderPolicyFactory;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.MessageJsonConverter;
import com.github.claudecodegui.util.SoundNotificationService;
import com.github.claudecodegui.ui.toolwindow.TabSessionRestoreState;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Delegates for initialization setup and runtime operations:
 * handler registration, permission setup, tab status, QuickFix, and frontend ready handling.
 */
public class ChatWindowDelegate {

    private static final Logger LOG = Logger.getInstance(ChatWindowDelegate.class);
    private static final String NODE_PATH_PROPERTY_KEY = "claude.code.node.path";
    private static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";
    private static final int STATUS_RESET_DELAY_SECONDS = 5;

    public enum TabAnswerStatus {
        IDLE,
        ANSWERING,
        COMPLETED
    }

    public interface DelegateHost {
        Project getProject();
        ClaudeSDKBridge getClaudeSDKBridge();
        CodexSDKBridge getCodexSDKBridge();
        ClaudeSession getSession();
        CodemossSettingsService getSettingsService();
        JPanel getMainPanel();
        JBCefBrowser getBrowser();
        boolean isDisposed();
        void callJavaScript(String fn, String... args);
        Content getParentContent();
        String getOriginalTabName();
        void setOriginalTabName(String name);
        String getSessionId();
        HandlerContext getHandlerContext();
        void setHandlerContext(HandlerContext ctx);
        void setMessageDispatcher(MessageDispatcher d);
        void setPermissionHandler(PermissionHandler h);
        void setHistoryHandler(HistoryHandler h);
        SessionLifecycleManager getSessionLifecycleManager();
        StreamMessageCoalescer getStreamCoalescer();
        WebviewWatchdog getWebviewWatchdog();
        PermissionHandler getPermissionHandler();
        void interruptDueToPermissionDenial();
        boolean isFrontendReady();
        void setFrontendReady(boolean ready);
        void setSlashCommandsFetched(boolean fetched);
        void setFetchedSlashCommandsCount(int count);
        void persistTabSessionState();
        TabSessionRestoreState.RestoreRequest consumePendingRestoreRequest();
        void markPendingRestoreStarted();
        void updateSessionTitle(String title);
        boolean shouldApplyFreshNewTabDefaults();
    }

    private final DelegateHost host;
    private TabAnswerStatus currentTabStatus = TabAnswerStatus.IDLE;
    private ScheduledFuture<?> statusResetTask;
    private volatile String pendingQuickFixPrompt = null;
    private volatile MessageCallback pendingQuickFixCallback = null;

    public ChatWindowDelegate(DelegateHost host) {
        this.host = host;
    }

    public void loadNodePathFromSettings() {
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedNodePath = props.getValue(NODE_PATH_PROPERTY_KEY);

            if (savedNodePath != null && !savedNodePath.trim().isEmpty()) {
                String path = savedNodePath.trim();
                claudeSDKBridge.setNodeExecutable(path);
                codexSDKBridge.setNodeExecutable(path);
                claudeSDKBridge.verifyAndCacheNodePath(path);
                LOG.info("Using manually configured Node.js path: " + path);
            } else {
                LOG.info("No saved Node.js path found, attempting auto-detection...");
                com.github.claudecodegui.model.NodeDetectionResult detected =
                    claudeSDKBridge.detectNodeWithDetails();

                if (detected != null && detected.isFound() && detected.getNodePath() != null) {
                    String detectedPath = detected.getNodePath();
                    String detectedVersion = detected.getNodeVersion();

                    props.setValue(NODE_PATH_PROPERTY_KEY, detectedPath);
                    claudeSDKBridge.setNodeExecutable(detectedPath);
                    codexSDKBridge.setNodeExecutable(detectedPath);
                    claudeSDKBridge.verifyAndCacheNodePath(detectedPath);

                    LOG.info("Auto-detected Node.js: " + detectedPath + " (" + detectedVersion + ")");
                } else {
                    LOG.warn("Failed to auto-detect Node.js path. Error: " +
                        (detected != null ? detected.getErrorMessage() : "Unknown error"));
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to load Node.js path: " + e.getMessage(), e);
        }
    }

    public void loadPermissionModeFromSettings() {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
            if (savedMode != null && !savedMode.trim().isEmpty()) {
                String mode = savedMode.trim();
                ClaudeSession session = host.getSession();
                if (session != null) {
                    session.setPermissionMode(mode);
                    host.persistTabSessionState();
                    LOG.info("Loaded permission mode from settings: " + mode);
                    com.github.claudecodegui.notifications.ClaudeNotifier.setMode(host.getProject(), mode);
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to load permission mode: " + e.getMessage());
        }
    }

    public void savePermissionModeToSettings(String mode) {
        try {
            PropertiesComponent props = PropertiesComponent.getInstance();
            props.setValue(PERMISSION_MODE_PROPERTY_KEY, mode);
            LOG.info("Saved permission mode to settings: " + mode);
        } catch (Exception e) {
            LOG.warn("Failed to save permission mode: " + e.getMessage());
        }
    }

    public void syncActiveProvider() {
        try {
            CodemossSettingsService settingsService = host.getSettingsService();
            if (settingsService.isLocalProviderActive()) {
                LOG.info("[ClaudeSDKToolWindow] Local provider active, skipping startup sync");
                return;
            }
            settingsService.applyActiveProviderToClaudeSettings();
        } catch (Exception e) {
            LOG.warn("Failed to sync active provider on startup: " + e.getMessage());
        }
    }

    public String setupPermissionService() {
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        Project project = host.getProject();
        String sessionId = claudeSDKBridge.getSessionId();

        if ((sessionId == null || sessionId.isEmpty()) && codexSDKBridge != null) {
            sessionId = codexSDKBridge.getSessionId();
        }

        if (sessionId == null || sessionId.isEmpty()) {
            LOG.warn("Failed to get session ID from bridges, generating fallback UUID");
            sessionId = java.util.UUID.randomUUID().toString();
        }

        claudeSDKBridge.setSessionId(sessionId);
        if (codexSDKBridge != null) {
            codexSDKBridge.setSessionId(sessionId);
        }
        LOG.info("Unified bridge sessionId for PermissionService routing: " + sessionId);

        PermissionService permissionService = PermissionService.getInstance(project, sessionId);
        permissionService.start();
        permissionService.registerDialogShower(project, (toolName, inputs) ->
            host.getPermissionHandler().showFrontendPermissionDialog(toolName, inputs));
        permissionService.registerAskUserQuestionDialogShower(project, (requestId, questionsData) ->
            host.getPermissionHandler().showAskUserQuestionDialog(requestId, questionsData));
        permissionService.registerPlanApprovalDialogShower(project, (requestId, planData) ->
            host.getPermissionHandler().showPlanApprovalDialog(requestId, planData));
        LOG.info("Started permission service with frontend dialog, AskUserQuestion dialog, and PlanApproval dialog for project: " + project.getName());
        return sessionId;
    }

    public void initializeHandlers() {
        Project project = host.getProject();
        ClaudeSDKBridge claudeSDKBridge = host.getClaudeSDKBridge();
        CodexSDKBridge codexSDKBridge = host.getCodexSDKBridge();
        CodemossSettingsService settingsService = host.getSettingsService();

        HandlerContext.JsCallback jsCallback = new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                host.callJavaScript(functionName, args);
            }
            @Override
            public String escapeJs(String str) {
                return JsUtils.escapeJs(str);
            }
        };

        HandlerContext handlerContext = new HandlerContext(project, claudeSDKBridge, codexSDKBridge, settingsService, jsCallback);
        handlerContext.setSession(host.getSession());
        handlerContext.setTabSessionPersistenceCallback(host::persistTabSessionState);
        host.setHandlerContext(handlerContext);

        MessageDispatcher messageDispatcher = new MessageDispatcher();
        host.setMessageDispatcher(messageDispatcher);
        // TaskStateService 负责把“发送、审批、重试、完成”等离散后端事件收敛成单一任务状态；
        // Dispatcher 再把这个统一状态分发到前端弹窗、状态栏、气泡和声音渠道。
        TaskStateService taskStateService = new TaskStateService();
        TaskReminderPolicyFactory taskReminderPolicyFactory = new TaskReminderPolicyFactory();
        // 提醒策略需要跟随设置页实时变化，因此这里注入“按次解析最新配置”的 provider，
        // 避免窗口初始化时把默认策略缓存死，导致用户修改设置后仍然不生效。
        TaskReminderPayloadFactory payloadFactory = new TaskReminderPayloadFactory(
            ChatWindowDelegate::resolveStableTabTitle
        );
        TaskReminderDispatcher taskReminderDispatcher = new TaskReminderDispatcher(
            handlerContext,
            () -> taskReminderPolicyFactory.fromSettingsService(settingsService),
            new ClaudeBalloonNotifier(),
            new SystemReminderNotifier(),
            SoundNotificationService.getInstance()::playTaskReminderSound,
            () -> ApplicationManager.getApplication().isActive(),
            snapshot -> resolveDefaultReminderMessage(snapshot),
            payloadFactory
        );

        messageDispatcher.registerHandler(new ProviderHandler(handlerContext));
        messageDispatcher.registerHandler(new McpServerHandler(handlerContext));
        messageDispatcher.registerHandler(new CodexMcpServerHandler(handlerContext, settingsService.getCodexMcpServerManager()));
        messageDispatcher.registerHandler(new SkillHandler(handlerContext));
        messageDispatcher.registerHandler(new FileHandler(handlerContext));
        messageDispatcher.registerHandler(new SettingsHandler(handlerContext, taskReminderDispatcher));
        SessionHandler sessionHandler = new SessionHandler(handlerContext, taskStateService, taskReminderDispatcher);
        handlerContext.setSessionRetryingCallback(sessionHandler::notifyRetrying);
        messageDispatcher.registerHandler(sessionHandler);
        messageDispatcher.registerHandler(new ContextHandler(handlerContext));
        messageDispatcher.registerHandler(new FileExportHandler(handlerContext));
        messageDispatcher.registerHandler(new DiffHandler(handlerContext));
        messageDispatcher.registerHandler(new PromptEnhancerHandler(handlerContext));
        messageDispatcher.registerHandler(new AgentHandler(handlerContext));
        messageDispatcher.registerHandler(new PromptHandler(handlerContext));
        messageDispatcher.registerHandler(new TabHandler(handlerContext));
        messageDispatcher.registerHandler(new TaskReminderNavigationHandler(handlerContext, new CcgTaskNavigator()));
        messageDispatcher.registerHandler(new RewindHandler(handlerContext));
        messageDispatcher.registerHandler(new UndoFileHandler(handlerContext));
        messageDispatcher.registerHandler(new DependencyHandler(handlerContext));
        messageDispatcher.registerHandler(new ClipboardHandler(handlerContext));

        messageDispatcher.registerHandler(new WindowEventHandler(handlerContext, new WindowEventHandler.Callback() {
            @Override public void onHeartbeat(String content) { host.getWebviewWatchdog().handleHeartbeat(content); }
            @Override public void onTabLoadingChanged(boolean loading) { updateTabLoadingState(loading); }
            @Override public void onTabStatusChanged(String statusStr) {
                TabAnswerStatus status;
                switch (statusStr) {
                    case "answering":
                        status = TabAnswerStatus.ANSWERING;
                        break;
                    case "completed":
                        status = TabAnswerStatus.COMPLETED;
                        break;
                    default:
                        status = TabAnswerStatus.IDLE;
                        break;
                }
                updateTabStatus(status);
            }
            @Override public void onCreateNewSession() {
                host.getSessionLifecycleManager().createNewSession();
            }
            @Override public void onFrontendReady() { handleFrontendReady(); }
            @Override public void onRefreshSlashCommands() {
                host.getSessionLifecycleManager().fetchSlashCommandsOnStartup();
            }
        }));

        PermissionHandler permissionHandler = new PermissionHandler(
            handlerContext,
            taskStateService,
            taskReminderDispatcher
        );
        // SessionHandler 和 PermissionHandler 共用同一套任务状态服务，
        // 才能把“发送中 -> 等待审批 -> 恢复执行 -> 完成/失败”串成一条连续时间线。
        permissionHandler.setPermissionDeniedCallback(host::interruptDueToPermissionDenial);
        host.setPermissionHandler(permissionHandler);
        messageDispatcher.registerHandler(permissionHandler);

        HistoryHandler historyHandler = new HistoryHandler(handlerContext);
        historyHandler.setSessionLoadCallback((sessionId, projectPath, provider, runtimeFamily, restoreSource, transitionToken) ->
            host.getSessionLifecycleManager().loadHistorySession(
                sessionId,
                projectPath,
                provider,
                runtimeFamily,
                restoreSource,
                transitionToken
            ));
        host.setHistoryHandler(historyHandler);
        messageDispatcher.registerHandler(historyHandler);

        LOG.info("Registered " + messageDispatcher.getHandlerCount() + " message handlers");
    }

    public void initializeStatusBar() {
        ApplicationManager.getApplication().invokeLater(() -> {
            Project project = host.getProject();
            if (project == null || host.isDisposed()) { return; }

            ClaudeSession session = host.getSession();
            String mode = session != null ? session.getPermissionMode() : "default";
            com.github.claudecodegui.notifications.ClaudeNotifier.setMode(project, mode);

            String model = session != null ? session.getModel() : "claude-sonnet-4-6";
            com.github.claudecodegui.notifications.ClaudeNotifier.setModel(project, model);

            try {
                CodemossSettingsService settingsService = host.getSettingsService();
                String selectedId = settingsService.getSelectedAgentId();
                if (selectedId != null) {
                    JsonObject agent = settingsService.getAgent(selectedId);
                    if (agent != null) {
                        String agentName = agent.has("name") ? agent.get("name").getAsString() : "Agent";
                        com.github.claudecodegui.notifications.ClaudeNotifier.setAgent(project, agentName);
                    }
                }
            } catch (Exception e) {
                LOG.warn("Failed to set initial agent in status bar: " + e.getMessage());
            }
        });
    }

    public void updateTabStatus(TabAnswerStatus status) {
        Content parentContent = host.getParentContent();
        String originalTabName = host.getOriginalTabName();
        if (parentContent == null || originalTabName == null) {
            LOG.warn("[TabStatus] Cannot update - parentContent or originalTabName is null");
            return;
        }

        if (status == currentTabStatus) {
            LOG.debug("[TabStatus] Skipping redundant update for tab: " + originalTabName);
            return;
        }

        currentTabStatus = status;

        if (statusResetTask != null && !statusResetTask.isDone()) {
            statusResetTask.cancel(false);
            statusResetTask = null;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            String tabName = originalTabName;
            String currentDisplayName = parentContent.getDisplayName();
            if (currentDisplayName != null && !currentDisplayName.startsWith(tabName)) {
                tabName = currentDisplayName.endsWith("...")
                    ? currentDisplayName.substring(0, currentDisplayName.length() - 3)
                    : currentDisplayName;
                host.setOriginalTabName(tabName);
                LOG.debug("[TabStatus] Detected external rename, updated originalTabName to: " + tabName);
            }

            String displayName;
            switch (status) {
                case ANSWERING:
                    displayName = tabName + "...";
                    LOG.debug("[TabStatus] Set answering state for tab: " + displayName);
                    break;
                case COMPLETED:
                    String completedText = ClaudeCodeGuiBundle.message("tab.status.completed");
                    displayName = tabName + " (" + completedText + ")";
                    LOG.debug("[TabStatus] Set completed state for tab: " + displayName);

                    statusResetTask = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            updateTabStatus(TabAnswerStatus.IDLE);
                        });
                    }, STATUS_RESET_DELAY_SECONDS, TimeUnit.SECONDS);
                    break;
                case IDLE:
                default:
                    displayName = tabName;
                    LOG.debug("[TabStatus] Restored idle state for tab: " + displayName);
                    break;
            }
            parentContent.setDisplayName(displayName);
        });
    }

    @Deprecated
    public void updateTabLoadingState(boolean loading) {
        updateTabStatus(loading ? TabAnswerStatus.ANSWERING : TabAnswerStatus.IDLE);
    }

    public void sendQuickFixMessage(String prompt, boolean isQuickFix, MessageCallback callback) {
        ClaudeSession session = host.getSession();
        if (session == null) {
            LOG.warn("QuickFix: Session is null, cannot send message");
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError("Session not initialized. Please wait for the tool window to fully load.");
            });
            return;
        }

        session.getContextCollector().setQuickFix(isQuickFix);

        if (!host.isFrontendReady()) {
            LOG.info("QuickFix: Frontend not ready, queuing message for later");
            pendingQuickFixPrompt = prompt;
            pendingQuickFixCallback = callback;
            return;
        }

        executeQuickFixInternal(prompt, callback);
    }

    private void executePendingQuickFix(String prompt, MessageCallback callback) {
        ClaudeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError("Session not available");
            });
            return;
        }
        executeQuickFixInternal(prompt, callback);
    }

    private void executeQuickFixInternal(String prompt, MessageCallback callback) {
        String escapedPrompt = JsUtils.escapeJs(prompt);
        host.callJavaScript("addUserMessage", escapedPrompt);
        host.callJavaScript("showLoading", "true");

        host.getSession().send(prompt, null, (String) null).thenRun(() -> {
            List<ClaudeSession.Message> messages = host.getSession().getMessages();
            if (!messages.isEmpty()) {
                ClaudeSession.Message last = messages.get(messages.size() - 1);
                if (last.type == ClaudeSession.Message.Type.ASSISTANT && last.content != null) {
                    ApplicationManager.getApplication().invokeLater(() -> {
                        callback.onComplete(SDKResult.success(last.content));
                    });
                }
            }
        }).exceptionally(ex -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                callback.onError(ex.getMessage());
            });
            return null;
        });
    }

    public void handleFrontendReady() {
        LOG.info("Received frontend_ready signal, frontend is now ready to receive data");
        host.setFrontendReady(true);

        host.callJavaScript(
            "window.updateLinkifyCapabilities",
            JsUtils.escapeJs(OpenClassHandler.buildCapabilitiesJson())
        );
        replayCurrentSessionStateToFrontend();
        applyFreshNewTabDefaultsIfNeeded();
        host.getSessionLifecycleManager().sendCurrentPermissionMode();
        triggerPendingSessionRestoreIfNeeded();
        host.persistTabSessionState();

        if (pendingQuickFixPrompt != null && pendingQuickFixCallback != null) {
            LOG.info("Processing pending QuickFix message after frontend ready");
            String prompt = pendingQuickFixPrompt;
            MessageCallback callback = pendingQuickFixCallback;
            pendingQuickFixPrompt = null;
            pendingQuickFixCallback = null;
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                executePendingQuickFix(prompt, callback);
            });
        }

        StreamMessageCoalescer streamCoalescer = host.getStreamCoalescer();
        if (streamCoalescer != null) {
            streamCoalescer.flush(null);
        }
    }

    /**
     * 在前端 ready 后按需触发一次待恢复会话加载。
     * 该逻辑统一承接启动自动恢复和手动强制刷新后的补恢复请求，并通过“消费即清空”避免重复恢复。
     */
    private void triggerPendingSessionRestoreIfNeeded() {
        TabSessionRestoreState.RestoreRequest request = host.consumePendingRestoreRequest();
        if (request == null) {
            return;
        }

        LOG.info("[TabRestore] Triggering pending restore after frontend ready, sessionId="
                + request.getSessionId()
                + ", projectPath=" + request.getProjectPath()
                + ", displayProvider=" + request.getDisplayProvider()
                + ", runtimeFamily=" + request.getRuntimeFamily()
                + ", restoreSource=" + request.getRestoreSource()
                + ", transitionToken=" + request.getTransitionToken()
                + ", manualRefresh=" + request.isManualRefreshTriggered());
        host.markPendingRestoreStarted();
        host.getSessionLifecycleManager().loadHistorySession(
                request.getSessionId(),
                request.getProjectPath(),
                request.getDisplayProvider(),
                request.getRuntimeFamily(),
                request.getRestoreSource(),
                request.getTransitionToken()
        );
    }

    private void replayCurrentSessionStateToFrontend() {
        ClaudeSession session = host.getSession();
        if (session == null || host.isDisposed()) {
            return;
        }

        try {
            String sessionId = session.getSessionId();
            if (sessionId != null && !sessionId.trim().isEmpty()) {
                host.callJavaScript("setSessionId", JsUtils.escapeJs(sessionId));
            }

            JsonObject runtimePayload = new JsonObject();
            runtimePayload.addProperty("provider", hasText(session.getProvider()) ? session.getProvider() : "");
            runtimePayload.addProperty("runtimeFamily", SessionRuntimeFamily.resolve(
                    session.getProvider(),
                    null,
                    session.getState().getCodexSessionBinding()
            ));
            runtimePayload.addProperty("model", hasText(session.getModel()) ? session.getModel() : "");
            runtimePayload.addProperty("permissionMode", hasText(session.getPermissionMode()) ? session.getPermissionMode() : "");
            runtimePayload.addProperty("reasoningEffort", hasText(session.getReasoningEffort()) ? session.getReasoningEffort() : "");
            CodexSessionBinding codexBinding = session.getState().getCodexSessionBinding();
            runtimePayload.addProperty("codexProviderId",
                    codexBinding != null && hasText(codexBinding.getProviderId()) ? codexBinding.getProviderId() : "");
            host.callJavaScript("window.restoreTabRuntimeState", JsUtils.escapeJs(runtimePayload.toString()));

            List<ClaudeSession.Message> messages = session.getMessages();
            if (!messages.isEmpty()) {
                String messagesJson = MessageJsonConverter.convertMessagesToJson(messages);
                host.callJavaScript("updateMessages", JsUtils.escapeJs(messagesJson));
            }

            host.callJavaScript("showLoading", String.valueOf(session.isLoading()));
            host.callJavaScript("showThinkingStatus", String.valueOf(false));

            String summary = session.getSummary();
            if (summary != null && !summary.trim().isEmpty()) {
                host.callJavaScript("showSummary", JsUtils.escapeJs(summary));
            }

            // FIX: Restore streaming state after webview reload.
            // When the watchdog reloads the webview during active streaming, the frontend's
            // isStreamingRef is reset to false, causing all onContentDelta callbacks to be
            // silently dropped.  Re-sending onStreamStart ensures the frontend accepts
            // subsequent streaming deltas and the stall watchdog is properly initialized.
            // 测试桩或极早期恢复阶段可能尚未提供 coalescer；
            // 这里按“当前没有活跃流”处理即可，不应阻塞会话恢复主链路。
            StreamMessageCoalescer streamCoalescer = host.getStreamCoalescer();
            boolean streamActive = streamCoalescer != null && streamCoalescer.isStreamActive();
            if (streamActive) {
                LOG.debug("Replaying streaming state to frontend (session was actively streaming during reload)");
                host.callJavaScript("onStreamStart");
            }

            LOG.info("Replayed current session state to frontend: sessionId="
                    + (sessionId != null ? sessionId : "(none)")
                    + ", messages=" + messages.size()
                    + ", loading=" + session.isLoading()
                    + ", streaming=" + streamActive);
        } catch (Exception e) {
            LOG.warn("Failed to replay current session state to frontend: " + e.getMessage(), e);
        }
    }

    /**
     * 在 fresh new tab 首次 ready 时应用“新建 Tab 默认快照”。
     * 这里不仅要通知前端更新选择器，还必须同步后端 session 的 provider/model/reasoning/binding，
     * 否则 UI 默认值与后续真正发消息时使用的运行态会出现偏差。
     */
    private void applyFreshNewTabDefaultsIfNeeded() {
        if (!host.shouldApplyFreshNewTabDefaults()) {
            return;
        }

        ClaudeSession session = host.getSession();
        if (session == null || hasText(session.getSessionId()) || !session.getMessages().isEmpty()) {
            return;
        }

        try {
            JsonObject defaults = host.getSettingsService().buildFreshNewTabDefaults();
            String provider = hasText(defaults.get("provider") != null ? defaults.get("provider").getAsString() : null)
                    ? defaults.get("provider").getAsString().trim()
                    : "codex";
            String model = hasText(defaults.get("model") != null ? defaults.get("model").getAsString() : null)
                    ? defaults.get("model").getAsString().trim()
                    : "";
            String permissionMode = hasText(defaults.get("permissionMode") != null ? defaults.get("permissionMode").getAsString() : null)
                    ? defaults.get("permissionMode").getAsString().trim()
                    : "bypassPermissions";
            String reasoningEffort = hasText(defaults.get("reasoningEffort") != null ? defaults.get("reasoningEffort").getAsString() : null)
                    ? defaults.get("reasoningEffort").getAsString().trim()
                    : "medium";
            String codexProviderId = hasText(defaults.get("codexProviderId") != null ? defaults.get("codexProviderId").getAsString() : null)
                    ? defaults.get("codexProviderId").getAsString().trim()
                    : "";

            session.setProvider(provider);
            session.setPermissionMode(permissionMode);
            if (hasText(model)) {
                session.setModel(model);
            }
            session.setReasoningEffort(reasoningEffort);
            session.getState().setCodexSessionBinding(buildFreshNewTabCodexBinding(codexProviderId, model));

            host.callJavaScript("window.applyNewTabDefaults", JsUtils.escapeJs(defaults.toString()));
            host.persistTabSessionState();
            LOG.info("[FreshNewTab] Applied fresh new tab defaults: " + defaults);
        } catch (Exception e) {
            LOG.warn("[FreshNewTab] Failed to apply fresh new tab defaults: " + e.getMessage(), e);
        }
    }

    /**
     * 根据 fresh new tab 默认值构建最小可用的 Codex 会话绑定。
     * 这里复用设置层中的 provider 查询，保证前端命中的 providerId 能在发送链路里继续生效。
     *
     * @param providerId 默认快照解析出的 Codex provider id
     * @param modelId 默认快照解析出的 Codex model id
     * @return 可写入当前 session 的 Codex 绑定；无法构建时返回 null
     */
    private CodexSessionBinding buildFreshNewTabCodexBinding(String providerId, String modelId) {
        if (!hasText(providerId) || !hasText(modelId)) {
            return null;
        }

        try {
            JsonObject provider = host.getSettingsService().getCodexProviderById(providerId);
            if (provider == null) {
                return null;
            }

            boolean isCliLoginProvider = provider.has("isCodexCliLoginProvider")
                    && !provider.get("isCodexCliLoginProvider").isJsonNull()
                    && provider.get("isCodexCliLoginProvider").getAsBoolean();
            if (isCliLoginProvider) {
                return new CodexSessionBinding(
                        providerId,
                        modelId,
                        "codex_sdk",
                        "codex_cli_login",
                        "codex_cli_login"
                );
            }

            String requestMode = provider.has("requestMode") && !provider.get("requestMode").isJsonNull()
                    ? provider.get("requestMode").getAsString().trim()
                    : "codex_sdk";
            String baseUrlSource = provider.has("baseUrl")
                    && !provider.get("baseUrl").isJsonNull()
                    && hasText(provider.get("baseUrl").getAsString())
                    ? "provider"
                    : "sdk_default";
            return new CodexSessionBinding(
                    providerId,
                    modelId,
                    hasText(requestMode) ? requestMode : "codex_sdk",
                    baseUrlSource,
                    "codemoss_managed_provider"
            );
        } catch (Exception e) {
            LOG.warn("[FreshNewTab] Failed to build Codex binding for providerId=" + providerId + ": " + e.getMessage());
            return null;
        }
    }

    public void dispose() {
        if (statusResetTask != null && !statusResetTask.isDone()) {
            statusResetTask.cancel(false);
            statusResetTask = null;
            LOG.debug("[TabStatus] Cancelled pending status reset task");
        }
    }

    /**
     * 为系统通知解析稳定的会话标题。
     * 这里故意只依赖持久化 tab 状态，而不直接访问 ToolWindow / ContentManager 等运行态 UI 对象，
     * 避免在非 EDT 的任务提醒分发链路中引入线程断言、disposed race 或 UI 访问异常。
     *
     * @param project 当前项目
     * @param sessionId 会话 ID
     * @return 稳定会话标题；无法解析时返回 null
     */
    private static String resolveStableTabTitle(Project project, String sessionId) {
        if (project == null || project.isDisposed() || !hasText(sessionId)) {
            return null;
        }

        TabStateService tabStateService = TabStateService.getInstance(project);
        int tabCount = tabStateService.getTabCount();
        for (int index = 0; index < tabCount; index++) {
            TabStateService.TabSessionState tabSessionState = tabStateService.getTabSessionState(index);
            if (tabSessionState == null || !sessionId.equals(tabSessionState.sessionId)) {
                continue;
            }

            String persistedTitle = hasText(tabStateService.getTabName(index))
                ? tabStateService.getTabName(index).trim()
                : null;
            if (hasText(persistedTitle)) {
                return persistedTitle;
            }
        }

        return null;
    }

    /**
     * 统一生成默认提醒文案。
     * 这里保持与 TaskReminderDispatcher 的默认策略一致，用于在自定义 payloadFactory 注入场景下继续复用同一套状态文案。
     *
     * @param snapshot 当前任务快照
     * @return 默认提醒文案
     */
    private static String resolveDefaultReminderMessage(TaskStateSnapshot snapshot) {
        String reason = snapshot != null && snapshot.getLatestEvent() != null
            ? snapshot.getLatestEvent().getReason()
            : null;

        return switch (snapshot.getState()) {
            case WAITING_CONFIRM -> ClaudeCodeGuiBundle.message("task.reminder.waitingConfirm");
            case FINAL_ERROR -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.finalError");
            case COMPLETED -> ClaudeCodeGuiBundle.message("task.reminder.completed");
            case RECOVERED -> ClaudeCodeGuiBundle.message("task.reminder.recovered");
            case RETRYING -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.retrying");
            case CANCELLED -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.cancelled");
            case RUNNING -> ClaudeCodeGuiBundle.message("task.reminder.running");
            case PENDING -> ClaudeCodeGuiBundle.message("task.reminder.pending");
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
