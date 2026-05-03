package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.action.SendShortcutSync;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.history.HistoryHandler;
import com.github.claudecodegui.handler.core.MessageDispatcher;
import com.github.claudecodegui.handler.PermissionHandler;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionCallbackAdapter;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.SessionLoadService;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.ui.ChatWindowDelegate;
import com.github.claudecodegui.ui.EditorContextTracker;
import com.github.claudecodegui.ui.WebviewInitializer;
import com.github.claudecodegui.ui.WebviewWatchdog;
import com.github.claudecodegui.util.HtmlLoader;
import com.github.claudecodegui.util.JsUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.jcef.JBCefBrowser;

import javax.swing.*;
import java.awt.*;

/**
 * Chat window instance. Coordinates UI components, session management,
 * and message dispatching. One instance per tab.
 */
public class ClaudeChatWindow {

    private static final Logger LOG = Logger.getInstance(ClaudeChatWindow.class);

    private final JPanel mainPanel;
    private final ClaudeSDKBridge claudeSDKBridge;
    private final CodexSDKBridge codexSDKBridge;
    private final Project project;
    private final CodemossSettingsService settingsService;
    private final HtmlLoader htmlLoader;

    private Content parentContent;
    private String originalTabName;
    private volatile String sessionId = null;

    private JBCefBrowser browser;
    private ClaudeSession session;
    private final WebviewWatchdog webviewWatchdog;
    private final StreamMessageCoalescer streamCoalescer;

    private volatile boolean disposed = false;
    private volatile boolean initialized = false;
    private volatile boolean frontendReady = false;
    private volatile boolean slashCommandsFetched = false;
    private volatile int fetchedSlashCommandsCount = 0;

    private HandlerContext handlerContext;
    private MessageDispatcher messageDispatcher;
    private PermissionHandler permissionHandler;
    private HistoryHandler historyHandler;
    private final SessionLifecycleManager sessionLifecycleManager;
    private final TabSessionRestoreState tabSessionRestoreState;

    // Delegates
    private WebviewInitializer webviewInitializer;
    private final EditorContextTracker editorContextTracker;
    private final ChatWindowDelegate chatWindowDelegate;
    private SessionCallbackAdapter sessionCallbackAdapter;

    public ClaudeChatWindow(Project project) {
        this(project, false);
    }

    public ClaudeChatWindow(Project project, boolean skipRegister) {
        this.project = project;
        this.claudeSDKBridge = new ClaudeSDKBridge();
        this.codexSDKBridge = new CodexSDKBridge();
        this.settingsService = new CodemossSettingsService();
        this.htmlLoader = new HtmlLoader(getClass());
        this.mainPanel = new JPanel(new BorderLayout());

        this.mainPanel.setBackground(com.github.claudecodegui.util.ThemeConfigService.getBackgroundColor());

        this.streamCoalescer = new StreamMessageCoalescer(new StreamMessageCoalescer.JsCallbackTarget() {
            @Override
            public void callJavaScript(String functionName, String... args) {
                ClaudeChatWindow.this.callJavaScript(functionName, args);
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }
        });

        this.webviewWatchdog = new WebviewWatchdog(
                mainPanel,
                () -> browser,
                htmlLoader,
                () -> webviewInitializer.recreateWebview("watchdog_recreate"),
                () -> disposed,
                () -> streamCoalescer.isStreamActive()
        );

        this.session = new ClaudeSession(project, claudeSDKBridge, codexSDKBridge);

        this.chatWindowDelegate = new ChatWindowDelegate(createDelegateHost());
        chatWindowDelegate.loadPermissionModeFromSettings();
        chatWindowDelegate.loadNodePathFromSettings();
        chatWindowDelegate.syncActiveProvider();
        chatWindowDelegate.initializeHandlers();
        this.sessionId = chatWindowDelegate.setupPermissionService();

        this.sessionLifecycleManager = new SessionLifecycleManager(new SessionLifecycleManager.SessionHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public ClaudeSession getSession() {
                return session;
            }

            @Override
            public void setSession(ClaudeSession s) {
                session = s;
                persistTabSessionState();
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public StreamMessageCoalescer getStreamCoalescer() {
                return streamCoalescer;
            }

            @Override
            public void clearPendingPermissionRequests() {
                permissionHandler.clearPendingRequests();
            }

            @Override
            public void clearPermissionDecisionMemory() {
                try {
                    if (sessionId != null && !sessionId.isEmpty()) {
                        PermissionService permissionService = PermissionService.getInstance(project, sessionId);
                        permissionService.clearDecisionMemory();
                    }
                } catch (Exception e) {
                    LOG.warn("Failed to clear permission decision memory: " + e.getMessage());
                }
            }

            @Override
            public void callJavaScript(String fn, String... args) {
                ClaudeChatWindow.this.callJavaScript(fn, args);
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public void setupSessionCallbacks() {
                ClaudeChatWindow.this.setupSessionCallbacks();
            }

            @Override
            public void invalidateSessionCallbacks() {
                if (sessionCallbackAdapter != null) {
                    sessionCallbackAdapter.deactivate();
                }
            }

            @Override
            public void setSlashCommandsFetched(boolean fetched) {
                slashCommandsFetched = fetched;
            }

            @Override
            public void setFetchedSlashCommandsCount(int count) {
                fetchedSlashCommandsCount = count;
            }
        });
        this.tabSessionRestoreState = new TabSessionRestoreState();

        this.editorContextTracker = new EditorContextTracker(project, new EditorContextTracker.ContextCallback() {
            @Override
            public void addSelectionInfo(String info) {
                callJavaScript("addSelectionInfo", info);
            }

            @Override
            public void clearSelectionInfo() {
                callJavaScript("clearSelectionInfo");
            }
        });
        editorContextTracker.registerListeners();

        this.webviewInitializer = new WebviewInitializer(createWebviewHost());

        setupSessionCallbacks();
        initializeSessionInfo();

        // Delay JCEF browser creation to avoid service initialization conflicts
        // during JBCefApp$Holder class init (ProxyMigrationService dependency).
        // Operations that depend on browser readiness are also deferred.
        ToolWindowManager.getInstance(this.project).invokeLater(() -> {
            if (!this.disposed) {
                this.webviewInitializer.createUIComponents();
                registerSessionLoadListener();
                this.initialized = true;
                LOG.info("Window instance fully initialized, project: " + this.project.getName());
            }
        });

        if (!skipRegister) {
            registerInstance();
        }
        chatWindowDelegate.initializeStatusBar();
        SendShortcutSync.syncFromSettings();
    }

    // ==================== Public API ====================

    public void setParentContent(Content content) {
        if (this.parentContent != null && this.parentContent != content) {
            ClaudeSDKToolWindow.unregisterContentMapping(this.parentContent);
            LOG.debug("[MultiTab] Unregistered old Content -> ClaudeChatWindow mapping");
        }

        this.parentContent = content;
        if (content != null) {
            ClaudeSDKToolWindow.registerContentMapping(content, this);
            LOG.debug("[MultiTab] Registered Content -> ClaudeChatWindow mapping for: " + content.getDisplayName());

            if (this.originalTabName == null) {
                String displayName = content.getDisplayName();
                this.originalTabName = displayName.endsWith("...")
                        ? displayName.substring(0, displayName.length() - 3)
                        : displayName;
                LOG.debug("[TabLoading] Auto-initialized original tab name: " + this.originalTabName);
            }

            persistTabSessionState();
        }
    }

    public void setOriginalTabName(String name) {
        this.originalTabName = (name != null && name.endsWith("..."))
                ? name.substring(0, name.length() - 3)
                : name;
        LOG.debug("[TabLoading] Set original tab name: " + this.originalTabName);
    }

    public boolean isDisposed() {
        return disposed;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public Content getParentContent() {
        return parentContent;
    }

    public JPanel getContent() {
        return mainPanel;
    }

    public ClaudeSDKBridge getClaudeSDKBridge() {
        return claudeSDKBridge;
    }

    public CodexSDKBridge getCodexSDKBridge() {
        return codexSDKBridge;
    }

    /**
     * Get the project associated with this chat window.
     *
     * @return the current project.
     */
    public Project getProject() {
        return this.project;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ClaudeSession getSession() {
        return session;
    }

    /**
     * 让当前窗口的页签标题跟随会话标题同步更新。
     * 该方法会同时更新显示标题、originalTabName 以及持久化快照，
     * 避免后续状态切换再次把标题回滚到旧值。
     *
     * @param newTitle 最新会话标题
     */
    public void syncTabTitleFromSessionTitle(String newTitle) {
        if (!isNonEmpty(newTitle)) {
            LOG.warn("[HistoryTitleSync] Skip tab title sync because title is empty.");
            return;
        }

        String normalizedTitle = newTitle.trim();
        LOG.info("[HistoryTitleSync] Apply session title to tab. sessionId=" + sessionId
                + ", oldTitle=" + (parentContent != null ? parentContent.getDisplayName() : null)
                + ", newTitle=" + normalizedTitle);
        if (parentContent != null) {
            parentContent.setDisplayName(normalizedTitle);
        }
        setOriginalTabName(normalizedTitle);

        int tabIndex = getTabIndex();
        if (tabIndex >= 0) {
            TabStateService tabStateService = TabStateService.getInstance(project);
            tabStateService.saveTabName(tabIndex, normalizedTitle);
            tabStateService.saveTabTitleBindingMode(tabIndex, TabStateService.TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE);
            flushProjectStateToDisk("tab_title_synced", tabIndex, sessionId);
            LOG.info("[HistoryTitleSync] Persisted synced tab title. tabIndex=" + tabIndex
                    + ", sessionId=" + sessionId + ", title=" + normalizedTitle);
        } else {
            LOG.warn("[HistoryTitleSync] Failed to persist synced tab title because tab index is invalid. sessionId="
                    + sessionId + ", title=" + normalizedTitle);
        }
    }

    public void restorePersistedTabSessionState(TabStateService.TabSessionState savedState) {
        if (savedState == null || session == null) {
            return;
        }

        tabSessionRestoreState.primePersistedRestoreProtection(savedState.sessionId);

        if (savedState.permissionMode != null && !savedState.permissionMode.trim().isEmpty()) {
            session.setPermissionMode(savedState.permissionMode);
        }
        if (savedState.provider != null && !savedState.provider.trim().isEmpty()) {
            session.setProvider(savedState.provider);
        }
        if (savedState.model != null && !savedState.model.trim().isEmpty()) {
            session.setModel(savedState.model);
        }
        if (savedState.reasoningEffort != null && !savedState.reasoningEffort.trim().isEmpty()) {
            session.setReasoningEffort(savedState.reasoningEffort);
        }

        String restoredSessionId = isNonEmpty(savedState.sessionId) ? savedState.sessionId : null;
        String restoredCwd = isNonEmpty(savedState.cwd) ? savedState.cwd : session.getCwd();
        session.setSessionInfo(restoredSessionId, restoredCwd);
        tabSessionRestoreState.schedulePersistedRestore(restoredSessionId, restoredCwd);
        persistTabSessionState();

        LOG.info("[TabRestore] Restored tab session state: provider=" + savedState.provider
                + ", sessionId=" + savedState.sessionId + ", cwd=" + savedState.cwd + ")");
    }

    public void addCodeSnippetFromExternal(String selectionInfo) {
        addCodeSnippet(selectionInfo);
    }

    public void updateTabStatus(ChatWindowDelegate.TabAnswerStatus status) {
        chatWindowDelegate.updateTabStatus(status);
    }

    @Deprecated
    public void updateTabLoadingState(boolean loading) {
        chatWindowDelegate.updateTabLoadingState(loading);
    }

    public void sendQuickFixMessage(String prompt, boolean isQuickFix, MessageCallback callback) {
        chatWindowDelegate.sendQuickFixMessage(prompt, isQuickFix, callback);
    }

    /**
     * 强制刷新当前 Tab 窗口。
     * 该入口用于用户右键页签时手动恢复空白窗口：先重建 WebView，再在前端 ready 后按需恢复当前会话历史。
     * 当窗口内已经保留消息时，仅重建视图而不重复加载历史，避免覆盖运行态消息。
     */
    public void forceRefreshWindow() {
        if (disposed || webviewInitializer == null || session == null) {
            return;
        }

        String currentSessionId = session.getSessionId();
        String currentProjectPath = session.getCwd();
        boolean hasMessages = !session.getMessages().isEmpty();
        tabSessionRestoreState.scheduleManualRestoreIfNeeded(currentSessionId, currentProjectPath, hasMessages);

        LOG.info("[TabRefresh] Triggering manual force refresh, sessionId=" + currentSessionId
                + ", hasMessages=" + hasMessages + ", cwd=" + currentProjectPath);
        webviewInitializer.recreateWebview("manual_force_refresh");
    }

    public void executeJavaScriptCode(String jsCode) {
        if (this.disposed || this.browser == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!this.disposed && this.browser != null) {
                this.browser.getCefBrowser().executeJavaScript(jsCode, this.browser.getCefBrowser().getURL(), 0);
            }
        });
    }

    // ==================== JavaScript Bridge ====================

    private static final java.util.regex.Pattern SAFE_JS_FUNCTION_NAME =
            java.util.regex.Pattern.compile("^[a-zA-Z_$][a-zA-Z0-9_$.]*$");

    void callJavaScript(String functionName, String... args) {
        if (disposed || browser == null) {
            LOG.warn("Cannot call JS function " + functionName + ": disposed=" + disposed + ", browser=" + (browser == null ? "null" : "exists"));
            return;
        }

        if (functionName == null || !SAFE_JS_FUNCTION_NAME.matcher(functionName).matches()) {
            LOG.error("Invalid JavaScript function name rejected: " + functionName);
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposed || browser == null) {
                return;
            }
            try {
                String callee = functionName;
                if (!functionName.contains(".")) {
                    callee = "window." + functionName;
                }

                StringBuilder argsJs = new StringBuilder();
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) argsJs.append(", ");
                        String arg = args[i] == null ? "" : args[i];
                        argsJs.append("'").append(arg).append("'");
                    }
                }

                String checkAndCall =
                        "(function() {" +
                                "  try {" +
                                "    if (typeof " + callee + " === 'function') {" +
                                "      " + callee + "(" + argsJs + ");" +
                                "    }" +
                                "  } catch (e) {" +
                                "    console.error('[Backend->Frontend] Failed to call " + functionName + ":', e);" +
                                "  }" +
                                "})();";

                browser.getCefBrowser().executeJavaScript(checkAndCall, browser.getCefBrowser().getURL(), 0);
            } catch (Exception e) {
                LOG.warn("Failed to call JS function: " + functionName + ", error: " + e.getMessage(), e);
            }
        });
    }

    void handleJavaScriptMessage(String message) {
        if (message.startsWith("{\"type\":\"console.")) {
            try {
                JsonObject json = new Gson().fromJson(message, JsonObject.class);
                String logType = json.get("type").getAsString();
                JsonArray args = json.getAsJsonArray("args");

                StringBuilder logMessage = new StringBuilder("[Webview] ");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) logMessage.append(" ");
                    logMessage.append(args.get(i).toString());
                }

                if ("console.error".equals(logType)) {
                    LOG.warn(logMessage.toString());
                } else if ("console.warn".equals(logType)) {
                    LOG.info(logMessage.toString());
                } else {
                    LOG.debug(logMessage.toString());
                }
            } catch (Exception e) {
                LOG.warn("Failed to parse console log: " + e.getMessage());
            }
            return;
        }

        String[] parts = message.split(":", 2);
        if (parts.length < 1) {
            LOG.error("Invalid message format");
            return;
        }

        String type = parts[0];
        String content = parts.length > 1 ? parts[1] : "";

        if ("update_title".equals(type)) {
            LOG.info("[HistoryTitleSync] Bridge received update_title. content=" + content);
        }
        if ("sync_current_tab_title".equals(type)) {
            LOG.info("[HistoryTitleSync] Bridge received sync_current_tab_title. content=" + content);
            handleSyncCurrentTabTitle(content);
            return;
        }
        if ("ccg_debug_title_commit".equals(type)) {
            LOG.info("[HistoryTitleSync] Bridge received ccg_debug_title_commit. content=" + content);
            return;
        }

        if (messageDispatcher.dispatch(type, content)) {
            return;
        }

        LOG.warn("Unknown message type: " + type);
    }

    // ==================== Session Delegates ====================

    private void setupSessionCallbacks() {
        if (this.sessionCallbackAdapter != null) {
            this.sessionCallbackAdapter.deactivate();
        }
        this.sessionCallbackAdapter = new SessionCallbackAdapter(
                streamCoalescer,
                new SessionCallbackAdapter.JsTarget() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                        ClaudeChatWindow.this.callJavaScript(functionName, args);
                    }
                },
                permissionHandler,
                () -> slashCommandsFetched,
                this::onStreamEnded
        ) {
            @Override
            public void onSessionIdReceived(String newSessionId) {
                super.onSessionIdReceived(newSessionId);
                sessionId = newSessionId;
                tabSessionRestoreState.markRestoreFinished(newSessionId);
                persistTabSessionState();
            }
        };
        session.setCallback(sessionCallbackAdapter);
        persistTabSessionState();
    }

    private void onStreamEnded() {
        if (session == null) {
            return;
        }
        if ("claude".equals(session.getProvider()) && session.getError() == null) {
            com.github.claudecodegui.notifications.ClaudeNotifier.showSuccess(
                project,
                com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("task.send.completed")
            );
        }
    }

    private void initializeSessionInfo() {
        String workingDirectory = sessionLifecycleManager.determineWorkingDirectory();
        session.setSessionInfo(null, workingDirectory);
        persistTabSessionState();
        LOG.info("Initialized with working directory: " + workingDirectory);
    }

    private void registerSessionLoadListener() {
        SessionLoadService.getInstance().setListener((sessionId, projectPath) -> {
            ApplicationManager.getApplication().invokeLater(() ->
                    sessionLifecycleManager.loadHistorySession(sessionId, projectPath));
        });
    }

    private void registerInstance() {
        ClaudeSDKToolWindow.registerWindow(project, this);
    }

    private void interruptDueToPermissionDenial() {
        this.session.interrupt().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
            callJavaScript("onPermissionDenied");
            callJavaScript("onStreamEnd");
            callJavaScript("showLoading", "false");
            com.github.claudecodegui.notifications.ClaudeNotifier.clearStatus(project);
        }));
    }

    private int getTabIndex() {
        Content content = this.parentContent;
        if (content == null) {
            return -1;
        }
        ContentManager contentManager = content.getManager();
        if (contentManager == null) {
            return -1;
        }
        return contentManager.getIndexOfContent(content);
    }

    private void persistTabSessionState() {
        if (project == null || project.isDisposed() || session == null) {
            return;
        }

        int tabIndex = getTabIndex();
        if (tabIndex < 0) {
            return;
        }

        TabStateService.TabSessionState snapshot = new TabStateService.TabSessionState();
        snapshot.provider = session.getProvider();
        snapshot.sessionId = session.getSessionId();
        snapshot.cwd = session.getCwd();
        snapshot.model = session.getModel();
        snapshot.permissionMode = session.getPermissionMode();
        snapshot.reasoningEffort = session.getReasoningEffort();
        TabStateService tabStateService = TabStateService.getInstance(project);
        TabStateService.TabSessionState persistedState = tabStateService.getTabSessionState(tabIndex);
        snapshot.titleBindingMode = persistedState != null
                ? persistedState.getEffectiveTitleBindingMode()
                : TabStateService.TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE;

        if (persistedState != null && tabSessionRestoreState.shouldBlockEmptySessionSnapshotOverwrite(
                persistedState.sessionId,
                snapshot.sessionId
        )) {
            LOG.info("[TabRestore] Skip persisting empty session snapshot during restore, tabIndex=" + tabIndex
                    + ", persistedSessionId=" + persistedState.sessionId);
            return;
        }

        tabStateService.saveTabSessionState(tabIndex, snapshot);
        if (shouldFlushProjectStateAfterSnapshotSave(persistedState, snapshot)) {
            flushProjectStateToDisk("session_snapshot_updated", tabIndex, snapshot.sessionId);
        }
    }

    /**
     * 判断本次会话快照更新后，是否需要主动触发项目级落盘。
     * 重点覆盖“运行中后置拿到真实 sessionId，但 IDE 关闭前尚未来得及自动保存”的场景，
     * 避免下次启动只能恢复到空会话。
     *
     * @param persistedState 保存前的旧快照
     * @param snapshot 准备写回的新快照
     * @return 需要立即落盘时返回 true
     */
    private boolean shouldFlushProjectStateAfterSnapshotSave(
            TabStateService.TabSessionState persistedState,
            TabStateService.TabSessionState snapshot
    ) {
        String persistedSessionId = persistedState != null ? normalizeValue(persistedState.sessionId) : null;
        String currentSessionId = normalizeValue(snapshot.sessionId);
        return currentSessionId != null && !currentSessionId.equals(persistedSessionId);
    }

    /**
     * 主动触发项目级持久化落盘。
     * 仅用于少量关键状态变更，确保 tab 绑定会话和标题在 IDE 快速关闭前也能写入 .idea 状态文件。
     *
     * @param reason 落盘原因，便于日志排查
     * @param tabIndex 当前 Tab 索引
     * @param currentSessionId 当前会话 ID
     */
    private void flushProjectStateToDisk(String reason, int tabIndex, String currentSessionId) {
        if (project == null || project.isDisposed()) {
            return;
        }

        Runnable saveTask = () -> {
            if (project.isDisposed()) {
                return;
            }
            project.save();
            LOG.info("[TabStateService] Forced project state flush: reason=" + reason
                    + ", tabIndex=" + tabIndex + ", sessionId=" + currentSessionId);
        };

        Application application = ApplicationManager.getApplication();
        if (application == null) {
            saveTask.run();
            return;
        }
        if (application.isDispatchThread()) {
            saveTask.run();
            return;
        }
        application.invokeAndWait(saveTask);
    }

    /**
     * 规范化可空字符串，统一空白值判断。
     *
     * @param value 原始字符串
     * @return 去空白后的值；若为空则返回 null
     */
    private String normalizeValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 处理无 sessionId 场景下的当前窗口 Tab 标题同步请求。
     * 该入口只更新当前窗口，不参与基于 sessionId 的历史标题广播。
     *
     * @param content 前端传入的 JSON 字符串，格式为 { "title": "..." }
     */
    private void handleSyncCurrentTabTitle(String content) {
        try {
            JsonObject request = new Gson().fromJson(content, JsonObject.class);
            if (request == null || !request.has("title") || request.get("title").isJsonNull()) {
                return;
            }
            String title = request.get("title").getAsString();
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed) {
                    syncTabTitleFromSessionTitle(title);
                }
            });
        } catch (Exception e) {
            LOG.warn("[HistoryTitleSync] Failed to parse sync_current_tab_title payload: " + e.getMessage(), e);
        }
    }

    private boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }

    TabSessionRestoreState.RestoreRequest consumePendingRestoreRequest() {
        return tabSessionRestoreState.consumePendingRestoreRequest();
    }

    void markPendingRestoreStarted() {
        tabSessionRestoreState.markRestoreStarted();
    }

    // ==================== Code Snippets ====================

    private void addCodeSnippet(String selectionInfo) {
        if (selectionInfo != null && !selectionInfo.isEmpty()) {
            callJavaScript("addCodeSnippet", JsUtils.escapeJs(selectionInfo));
        }
    }

    // ==================== Dispose ====================

    public synchronized void dispose() {
        if (this.disposed) return;
        this.disposed = true;

        chatWindowDelegate.dispose();
        editorContextTracker.dispose();
        streamCoalescer.dispose();
        if (sessionCallbackAdapter != null) {
            sessionCallbackAdapter.dispose();
        }
        webviewWatchdog.stop();

        try {
            if (this.sessionId != null && !this.sessionId.isEmpty()) {
                PermissionService permissionService = PermissionService.getInstance(project, this.sessionId);
                permissionService.unregisterDialogShower(project);
                permissionService.unregisterAskUserQuestionDialogShower(project);
                permissionService.unregisterPlanApprovalDialogShower(project);
                PermissionService.removeInstance(this.sessionId);
                LOG.info("Removed PermissionService instance for sessionId: " + this.sessionId);
            }
        } catch (Exception e) {
            LOG.warn("Failed to unregister dialog showers or remove session instance: " + e.getMessage());
        }

        LOG.info("Starting window resource cleanup, project: " + project.getName());

        handlerContext.setDisposed(true);

        if (parentContent != null) {
            ClaudeSDKToolWindow.unregisterContentMapping(parentContent);
            LOG.debug("[MultiTab] Removed Content -> ClaudeChatWindow mapping during dispose");
        }

        ClaudeSDKToolWindow.unregisterWindow(project, this);

        try {
            if (session != null) session.interrupt();
        } catch (Exception e) {
            LOG.warn("Failed to clean up session: " + e.getMessage());
        }

        try {
            if (claudeSDKBridge != null) {
                int activeCount = claudeSDKBridge.getActiveProcessCount();
                if (activeCount > 0) {
                    LOG.info("Cleaning up " + activeCount + " active Claude process(es)...");
                }
                claudeSDKBridge.cleanupAllProcesses();
            }
        } catch (Exception e) {
            LOG.warn("Failed to clean up Claude processes: " + e.getMessage());
        }

        try {
            if (codexSDKBridge != null) {
                int activeCount = codexSDKBridge.getActiveProcessCount();
                if (activeCount > 0) {
                    LOG.info("Cleaning up " + activeCount + " active Codex process(es)...");
                }
                codexSDKBridge.cleanupAllProcesses();
            }
        } catch (Exception e) {
            LOG.warn("Failed to clean up Codex processes: " + e.getMessage());
        }

        try {
            if (browser != null) {
                browser.dispose();
                browser = null;
            }
        } catch (Exception e) {
            LOG.warn("Failed to clean up browser: " + e.getMessage());
        }

        if (messageDispatcher != null) {
            messageDispatcher.clear();
        }

        LOG.info("Window resources fully cleaned up, project: " + project.getName());
    }

    // ==================== Host Interface Factories ====================

    private WebviewInitializer.WebviewHost createWebviewHost() {
        return new WebviewInitializer.WebviewHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public JPanel getMainPanel() {
                return mainPanel;
            }

            @Override
            public HtmlLoader getHtmlLoader() {
                return htmlLoader;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public void setBrowser(JBCefBrowser b) {
                browser = b;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public void handleJavaScriptMessage(String msg) {
                ClaudeChatWindow.this.handleJavaScriptMessage(msg);
            }

            @Override
            public WebviewWatchdog getWebviewWatchdog() {
                return webviewWatchdog;
            }

            @Override
            public void setFrontendReady(boolean ready) {
                frontendReady = ready;
            }
        };
    }

    private ChatWindowDelegate.DelegateHost createDelegateHost() {
        return new ChatWindowDelegate.DelegateHost() {
            @Override
            public Project getProject() {
                return project;
            }

            @Override
            public ClaudeSDKBridge getClaudeSDKBridge() {
                return claudeSDKBridge;
            }

            @Override
            public CodexSDKBridge getCodexSDKBridge() {
                return codexSDKBridge;
            }

            @Override
            public ClaudeSession getSession() {
                return session;
            }

            @Override
            public CodemossSettingsService getSettingsService() {
                return settingsService;
            }

            @Override
            public JPanel getMainPanel() {
                return mainPanel;
            }

            @Override
            public JBCefBrowser getBrowser() {
                return browser;
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }

            @Override
            public Content getParentContent() {
                return parentContent;
            }

            @Override
            public String getOriginalTabName() {
                return originalTabName;
            }

            @Override
            public void setOriginalTabName(String name) {
                ClaudeChatWindow.this.setOriginalTabName(name);
            }

            @Override
            public String getSessionId() {
                return sessionId;
            }

            @Override
            public HandlerContext getHandlerContext() {
                return handlerContext;
            }

            @Override
            public void setHandlerContext(HandlerContext ctx) {
                handlerContext = ctx;
            }

            @Override
            public void setMessageDispatcher(MessageDispatcher d) {
                messageDispatcher = d;
            }

            @Override
            public void setPermissionHandler(PermissionHandler h) {
                permissionHandler = h;
            }

            @Override
            public void setHistoryHandler(HistoryHandler h) {
                historyHandler = h;
            }

            @Override
            public SessionLifecycleManager getSessionLifecycleManager() {
                return sessionLifecycleManager;
            }

            @Override
            public StreamMessageCoalescer getStreamCoalescer() {
                return streamCoalescer;
            }

            @Override
            public WebviewWatchdog getWebviewWatchdog() {
                return webviewWatchdog;
            }

            @Override
            public PermissionHandler getPermissionHandler() {
                return permissionHandler;
            }

            @Override
            public void callJavaScript(String fn, String... args) {
                ClaudeChatWindow.this.callJavaScript(fn, args);
            }

            @Override
            public void interruptDueToPermissionDenial() {
                ClaudeChatWindow.this.interruptDueToPermissionDenial();
            }

            @Override
            public boolean isFrontendReady() {
                return frontendReady;
            }

            @Override
            public void setFrontendReady(boolean ready) {
                frontendReady = ready;
            }

            @Override
            public void setSlashCommandsFetched(boolean fetched) {
                slashCommandsFetched = fetched;
            }

            @Override
            public void setFetchedSlashCommandsCount(int count) {
                fetchedSlashCommandsCount = count;
            }

            @Override
            public void persistTabSessionState() {
                ClaudeChatWindow.this.persistTabSessionState();
            }

            @Override
            public TabSessionRestoreState.RestoreRequest consumePendingRestoreRequest() {
                return ClaudeChatWindow.this.consumePendingRestoreRequest();
            }

            @Override
            public void markPendingRestoreStarted() {
                ClaudeChatWindow.this.markPendingRestoreStarted();
            }

            @Override
            public void updateSessionTitle(String title) {
                ClaudeChatWindow.this.callJavaScript("updateSessionTitle", JsUtils.escapeJs(title));
            }
        };
    }
}
