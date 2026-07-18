package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.action.SendShortcutSync;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.history.HistoryHandler;
import com.github.claudecodegui.handler.core.MessageDispatcher;
import com.github.claudecodegui.handler.PermissionHandler;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.DaemonBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.remote.debug.TabSessionRestoreDebugTrace;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.session.SessionRuntimeFamily;
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
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chat window instance. Coordinates UI components, session management,
 * and message dispatching. One instance per tab.
 */
public class ClaudeChatWindow {

    /**
     * 标记当前聊天窗口的初始化来源。
     * 仅 `FRESH_NEW_TAB` 会在前端首帧 ready 后应用“新建 Tab 默认快照”，
     * 其余场景继续沿用现有恢复链路，避免误伤已恢复 Tab 和同 Tab 新会话。
     */
    public enum InitializationSource {
        NORMAL,
        FRESH_NEW_TAB
    }

    private static final Logger LOG = Logger.getInstance(ClaudeChatWindow.class);
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";
    private static final int FRONTEND_DEBUG_DETAILS_MAX_LENGTH = 512;
    private static final long PROJECT_STATE_FLUSH_DEBOUNCE_MS = 1500L;
    private static final long WEBVIEW_RECOVERY_MIN_INTERVAL_MS = 10_000L;

    private final JPanel mainPanel;
    private final ClaudeSDKBridge claudeSDKBridge;
    private final CodexSDKBridge codexSDKBridge;
    private final Project project;
    private final CodemossSettingsService settingsService;
    private final HtmlLoader htmlLoader;
    private final InitializationSource initializationSource;

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
    private final AtomicBoolean freshNewTabDefaultsApplied = new AtomicBoolean(false);
    private final Object projectStateFlushLock = new Object();
    private final AtomicInteger projectStateFlushRequestCount = new AtomicInteger(0);
    private final AtomicInteger projectStateFlushExecuteCount = new AtomicInteger(0);
    private volatile ScheduledFuture<?> pendingProjectStateFlush;
    private volatile long lastWebviewRecoveryRequestAtMs = 0L;

    // Daemon event listener for AI title forwarding. Held so it can be removed on dispose.
    private DaemonBridge.DaemonEventListener titleEventListener;
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
        this(project, false, InitializationSource.NORMAL);
    }

    public ClaudeChatWindow(Project project, boolean skipRegister) {
        this(project, skipRegister, InitializationSource.NORMAL);
    }

    /**
     * 创建聊天窗口并指定初始化来源。
     * 新增该构造重载是为了让“新建 Tab 默认值”只绑定到 fresh new tab，
     * 而不影响 IDE 启动恢复、手动强制刷新以及其他普通窗口构建路径。
     *
     * @param project 当前项目
     * @param skipRegister 是否跳过主窗口注册
     * @param initializationSource 当前窗口初始化来源
     */
    public ClaudeChatWindow(Project project, boolean skipRegister, InitializationSource initializationSource) {
        this.project = project;
        this.claudeSDKBridge = new ClaudeSDKBridge();
        this.codexSDKBridge = new CodexSDKBridge();
        this.settingsService = new CodemossSettingsService();
        this.htmlLoader = new HtmlLoader(getClass());
        this.initializationSource = initializationSource == null ? InitializationSource.NORMAL : initializationSource;
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
                () -> {
                    if (canRequestWebviewRecovery("watchdog_recreate")) {
                        webviewInitializer.recreateWebview("watchdog_recreate");
                    }
                },
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
            // 标题同步可能与恢复链路、sessionId 建立同时发生，这里统一走延迟落盘避免阻塞 UI。
            requestProjectStateFlush("tab_title_synced", tabIndex, sessionId);
            LOG.info("[HistoryTitleSync] Persisted synced tab title. tabIndex=" + tabIndex
                    + ", sessionId=" + sessionId + ", title=" + normalizedTitle);
        } else {
            LOG.warn("[HistoryTitleSync] Failed to persist synced tab title because tab index is invalid. sessionId="
                    + sessionId + ", title=" + normalizedTitle);
        }
    }

    public SessionLifecycleManager getSessionLifecycleManager() {
        return sessionLifecycleManager;
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
        CodexSessionBinding restoredBinding = buildCodexSessionBinding(savedState);
        session.getState().setCodexSessionBinding(restoredBinding);
        logActiveRuntimeMutated("restorePersistedTabSessionState");

        String restoredSessionId = isNonEmpty(savedState.sessionId) ? savedState.sessionId : null;
        String restoredCwd = isNonEmpty(savedState.cwd) ? savedState.cwd : session.getCwd();
        session.setSessionInfo(restoredSessionId, restoredCwd);
        tabSessionRestoreState.schedulePersistedRestore(
                restoredSessionId,
                restoredCwd,
                savedState.provider,
                savedState.getEffectiveRuntimeFamily()
        );
        persistTabSessionState();
        TabSessionRestoreDebugTrace.info(
                LOG,
                "persisted_restore_scheduled",
                getTabIndex(),
                restoredSessionId,
                savedState.provider,
                savedState.getEffectiveRuntimeFamily(),
                TabSessionRestoreState.RESTORE_SOURCE_STARTUP,
                false,
                tabSessionRestoreState.getLastTransitionToken()
        );

        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " ClaudeChatWindow.restorePersistedTabSessionState sessionId="
                + restoredSessionId
                + ", provider=" + normalizeValue(savedState.provider)
                + ", runtimeFamily=" + savedState.getEffectiveRuntimeFamily()
                + ", model=" + normalizeValue(savedState.model)
                + ", binding=" + describeCodexBinding(restoredBinding));

        LOG.info("[TabRestore] Restored tab session state: provider=" + savedState.provider
                + ", sessionId=" + savedState.sessionId + ", cwd=" + savedState.cwd + ")");
    }

    /**
     * 恢复持久化的标签页会话绑定状态。
     * 当前历史恢复已经统一收敛到“前端 ready 后由 SessionLifecycleManager 执行”的主链路，
     * 因此这里不再根据 `loadImmediately` 直接触发旧的 `session.loadFromServer()` 抢跑入口，
     * 避免启动恢复与手动重绑阶段再次出现通用恢复和增强恢复并行执行。
     *
     * @param savedState 持久化标签页状态
     * @param loadImmediately 旧语义中的“是否立即恢复历史”，现仅保留参数兼容，不再驱动旧恢复链路
     */
    public void restorePersistedTabSessionState(TabStateService.TabSessionState savedState, boolean loadImmediately) {
        restorePersistedTabSessionState(savedState);
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
        if (!canRequestWebviewRecovery("manual_force_refresh")) {
            return;
        }

        String currentSessionId = session.getSessionId();
        String currentProjectPath = session.getCwd();
        boolean hasMessages = !session.getMessages().isEmpty();
        String runtimeFamily = SessionRuntimeFamily.resolve(
                session.getProvider(),
                null,
                session.getState().getCodexSessionBinding()
        );
        tabSessionRestoreState.scheduleManualRestoreIfNeeded(
                currentSessionId,
                currentProjectPath,
                session.getProvider(),
                runtimeFamily,
                hasMessages
        );

        LOG.info("[TabRefresh] Triggering manual force refresh, sessionId=" + currentSessionId
                + ", hasMessages=" + hasMessages + ", cwd=" + currentProjectPath);
        TabSessionRestoreDebugTrace.info(
                LOG,
                "manual_force_refresh_requested",
                getTabIndex(),
                currentSessionId,
                session.getProvider(),
                runtimeFamily,
                TabSessionRestoreState.RESTORE_SOURCE_MANUAL_REFRESH,
                true,
                tabSessionRestoreState.getLastTransitionToken()
        );
        webviewInitializer.recreateWebview("manual_force_refresh");
    }

    /**
     * 判断当前是否允许发起一次新的 WebView 恢复请求。
     * 这里统一限制手动 force refresh 与 watchdog recreate 的最小触发间隔，
     * 避免窗口已经处于恢复中时再次触发重建，放大 Android Studio 卡顿。
     *
     * @param recoveryReason 本次恢复原因
     * @return 允许发起恢复时返回 true
     */
    private boolean canRequestWebviewRecovery(String recoveryReason) {
        long now = System.currentTimeMillis();
        long elapsed = now - lastWebviewRecoveryRequestAtMs;
        if (elapsed < WEBVIEW_RECOVERY_MIN_INTERVAL_MS) {
            LOG.info("[WebviewRecovery] Skip recovery request: reason=" + recoveryReason
                    + ", elapsedMs=" + elapsed
                    + ", minIntervalMs=" + WEBVIEW_RECOVERY_MIN_INTERVAL_MS);
            return false;
        }
        lastWebviewRecoveryRequestAtMs = now;
        LOG.info("[WebviewRecovery] Accept recovery request: reason=" + recoveryReason
                + ", timestamp=" + now);
        return true;
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
                        if (i > 0) { argsJs.append(", "); }
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

    /**
     * 处理 WebView 通过桥接上报的 JavaScript 消息。
     * 这里除了业务事件外，还负责承接前端 console 日志；为了排查 rich paste 与历史恢复链路，
     * 需要把 `console.info` 显式提升到 IDEA 的 `INFO` 级别，避免关键诊断日志只停留在 debug 通道。
     *
     * @param message 前端桥接上来的原始消息
     */
    void handleJavaScriptMessage(String message) {
        if (message.startsWith("{\"type\":\"console.")) {
            try {
                JsonObject json = new Gson().fromJson(message, JsonObject.class);
                String logType = json.get("type").getAsString();
                JsonArray args = json.getAsJsonArray("args");

                StringBuilder logMessage = new StringBuilder("[Webview] ");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) { logMessage.append(" "); }
                    logMessage.append(args.get(i).toString());
                }

                if ("console.error".equals(logType)) {
                    LOG.warn(logMessage.toString());
                } else if ("console.warn".equals(logType) || "console.info".equals(logType)) {
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

        if ("frontend_debug_log".equals(type)) {
            handleFrontendDebugLog(content);
            return;
        }

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

    /**
     * 处理前端显式桥接回来的诊断日志。
     * 该入口独立于 console monkey-patch，用于保证分发包静音 console 后，
     * rich paste、历史恢复等关键诊断日志依然可以进入 idea.log。
     *
     * @param content 前端发来的结构化 JSON 载荷
     */
    private void handleFrontendDebugLog(String content) {
        try {
            JsonObject payload = new Gson().fromJson(content, JsonObject.class);
            if (payload == null) {
                LOG.warn("[FrontendDebug] Skip empty diagnostic payload");
                return;
            }

            String scope = payload.has("scope") && !payload.get("scope").isJsonNull()
                    ? payload.get("scope").getAsString()
                    : "UnknownScope";
            String message = payload.has("message") && !payload.get("message").isJsonNull()
                    ? payload.get("message").getAsString()
                    : "";
            String details = payload.has("details") && !payload.get("details").isJsonNull()
                    ? payload.get("details").toString()
                    : "{}";

            LOG.info(buildFrontendDebugLogLine(scope, message, details));
        } catch (Exception e) {
            LOG.warn("[FrontendDebug] Failed to parse diagnostic payload: " + e.getMessage(), e);
        }
    }

    /**
     * 构造前端诊断日志最终写入 IDEA 日志的单行文本。
     * 前端已经会先做一轮裁剪与脱敏；这里仍保留 Java 侧兜底，避免后续新增桥接入口
     * 或异常 payload 绕过前端工具后，把超长详情直接刷进 idea.log。
     *
     * @param scope 前端诊断分类
     * @param message 诊断摘要
     * @param details 已序列化的详情字符串
     * @return 可直接写入 IDEA 日志的单行文本
     */
    private static String buildFrontendDebugLogLine(String scope, String message, String details) {
        return "[FrontendDebug][" + scope + "] " + message + " " + truncateFrontendDebugDetails(details);
    }

    /**
     * 对写入 IDEA 日志的前端诊断详情做最终长度保护。
     * 这里只做长度兜底，不再重复解析 JSON 结构，避免额外引入日志链路异常点。
     *
     * @param details 详情字符串
     * @return 裁剪后的详情字符串
     */
    private static String truncateFrontendDebugDetails(String details) {
        if (details == null || details.length() <= FRONTEND_DEBUG_DETAILS_MAX_LENGTH) {
            return details;
        }
        return details.substring(0, FRONTEND_DEBUG_DETAILS_MAX_LENGTH)
                + "...[truncated " + (details.length() - FRONTEND_DEBUG_DETAILS_MAX_LENGTH) + " chars]";
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
                this::onStreamEnded,
                reason -> {
                    if (handlerContext != null && handlerContext.getSessionRetryingCallback() != null) {
                        handlerContext.getSessionRetryingCallback().accept(reason);
                    }
                }
        ) {
            @Override
            public void onSessionIdReceived(String newSessionId) {
                super.onSessionIdReceived(newSessionId);
                sessionId = newSessionId;
                sessionLifecycleManager.onSessionIdAssigned(newSessionId);
                tabSessionRestoreState.markRestoreFinished(newSessionId);
                persistTabSessionState();
            }
        };
        session.setCallback(sessionCallbackAdapter);

        // Wire daemon events directly to frontend (bypasses adapter lifecycle).
        // Calling through sessionCallbackAdapter would silently drop the event
        // if setupSessionCallbacks() is invoked again before the title arrives
        // (adapter.deactivate() → isInactive() → event discarded).
        // Register only once per ClaudeChatWindow; subsequent setupSessionCallbacks()
        // calls reuse the existing listener so the bridge keeps a single registration
        // per window. The listener is removed in dispose().
        if (this.titleEventListener == null) {
            this.titleEventListener = (event, data) -> {
                if ("title_generated".equals(event)) {
                    String genSessionId = data.has("sessionId") ? data.get("sessionId").getAsString() : null;
                    String title = data.has("title") ? data.get("title").getAsString() : null;
                    if (genSessionId != null && title != null) {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (!disposed) {
                                callJavaScript("updateSessionTitle",
                                        JsUtils.escapeJs(genSessionId), JsUtils.escapeJs(title));
                            }
                        });
                    }
                }
            };
            this.claudeSDKBridge.addDaemonEventListener(this.titleEventListener);
        }

        persistTabSessionState();
    }

    /**
     * 处理流式会话完成后的收尾通知。
     * 仅在 Claude 且无错误的场景下展示完成提示；标题和摘要优先使用会话内容，缺失时回退到国际化文案。
     */
    /**
     * 处理流式会话结束后的收尾钩子。
     * 当前 completed 主通知已经统一由任务状态链分发，这里仅保留流生命周期收尾入口，
     * 不再直发成功通知，避免与 TaskReminderDispatcher 的 completed 提醒并行生效。
     *
     * @return 无返回值
     */
    private void onStreamEnded() {
        // continued 场景下历史文件可能在 stream_end 之后才完整可见，这里补触发一次逻辑会话回刷，
        // 避免首次收口 defer 结束后，最后一条 assistant 结果长期停留在物理分段局部视图里。
        if (sessionLifecycleManager != null) {
            sessionLifecycleManager.refreshActiveContinuedLogicalConversationMessagesIfNeeded();
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
        snapshot.runtimeFamily = SessionRuntimeFamily.resolve(
                session.getProvider(),
                null,
                session.getState().getCodexSessionBinding()
        );
        snapshot.sessionId = session.getSessionId();
        snapshot.logicalConversationId = session.getState().getLogicalConversationId();
        snapshot.activeSegmentSessionId = session.getState().getActiveSegmentSessionId();
        snapshot.parentSegmentSessionId = session.getState().getParentSegmentSessionId();
        snapshot.continuationPending = session.getState().isContinuationPending();
        snapshot.continuationSourceSessionId = session.getState().getContinuationSourceSessionId();
        snapshot.cwd = session.getCwd();
        snapshot.model = session.getModel();
        snapshot.permissionMode = session.getPermissionMode();
        snapshot.reasoningEffort = session.getReasoningEffort();
        CodexSessionBinding binding = session.getState().getCodexSessionBinding();
        if (binding != null) {
            snapshot.codexProviderId = binding.getProviderId();
            snapshot.codexRequestMode = binding.getRequestMode();
            snapshot.codexBaseUrlSource = binding.getBaseUrlSource();
            snapshot.codexEffectiveConfigSource = binding.getEffectiveConfigSource();
        }
        TabStateService tabStateService = TabStateService.getInstance(project);
        TabStateService.TabSessionState persistedState = tabStateService.getTabSessionState(tabIndex);
        snapshot.titleBindingMode = persistedState != null
                ? persistedState.getEffectiveTitleBindingMode()
                : TabStateService.TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE;
        normalizeContinuationSnapshotForPersistence(snapshot);

        // 恢复链路和前端 ready 会频繁进入这里；若快照内容未变，则跳过重复写入与重复落盘请求。
        if (areTabSessionStatesEquivalent(persistedState, snapshot)) {
            return;
        }

        if (persistedState != null && tabSessionRestoreState.shouldBlockEmptySessionSnapshotOverwrite(
                persistedState.sessionId,
                snapshot.sessionId
        )) {
            LOG.info("[TabRestore] Skip persisting empty session snapshot during restore, tabIndex=" + tabIndex
                    + ", persistedSessionId=" + persistedState.sessionId);
            return;
        }

        tabStateService.saveTabSessionState(tabIndex, snapshot);
        TabSessionRestoreDebugTrace.info(
                LOG,
                "tab_session_snapshot_persisted",
                tabIndex,
                snapshot.sessionId,
                snapshot.provider,
                snapshot.runtimeFamily,
                "snapshot_persist",
                false,
                tabSessionRestoreState.getLastTransitionToken()
        );
        if (shouldRequestProjectStateFlushAfterSnapshotSave(persistedState, snapshot)) {
            requestProjectStateFlush("session_snapshot_updated", tabIndex, snapshot.sessionId);
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
    private boolean shouldRequestProjectStateFlushAfterSnapshotSave(
            TabStateService.TabSessionState persistedState,
            TabStateService.TabSessionState snapshot
    ) {
        return !areTabSessionStatesEquivalent(persistedState, snapshot);
    }

    /**
     * 在 Tab 快照落盘前归一化 continued 过渡态字段。
     * 仅当快照已经具备稳定的活动分段或真实 sessionId，且缺失 continuation source 锚点时，
     * 才把 `continuationPending` 视为陈旧状态并强制收口；真正 in-flight 的 continued 仍会保留 source 锚点。
     *
     * @param snapshot 当前准备持久化的 Tab 会话快照
     */
    private void normalizeContinuationSnapshotForPersistence(TabStateService.TabSessionState snapshot) {
        if (snapshot == null || !snapshot.continuationPending) {
            return;
        }
        String normalizedSourceSessionId = normalizeValue(snapshot.continuationSourceSessionId);
        String normalizedActiveSegmentSessionId = normalizeValue(snapshot.activeSegmentSessionId);
        String normalizedSessionId = normalizeValue(snapshot.sessionId);
        if (normalizedSourceSessionId != null) {
            return;
        }
        if (normalizedActiveSegmentSessionId == null && normalizedSessionId == null) {
            return;
        }
        // 中文注释：这种组合说明 continued 的真实分段已经稳定落地，但旧的 pending 标记尚未被某次快照清掉。
        // 若继续原样落盘，Tab 恢复后前端会再次把会话误判成“continued 尚未 ready”。
        snapshot.continuationPending = false;
        snapshot.continuationSourceSessionId = null;
    }

    /**
     * 判断两份 Tab 会话快照在持久化语义上是否等价。
     * 该比较用于抑制恢复热路径里的重复 snapshot 写入，避免同一份状态反复触发 IDE 项目保存。
     *
     * @param persistedState 已持久化的旧快照
     * @param snapshot 当前准备写入的新快照
     * @return 两份快照字段等价时返回 true
     */
    private boolean areTabSessionStatesEquivalent(
            TabStateService.TabSessionState persistedState,
            TabStateService.TabSessionState snapshot
    ) {
        if (persistedState == snapshot) {
            return true;
        }
        if (persistedState == null || snapshot == null) {
            return false;
        }
        return Objects.equals(normalizeValue(persistedState.provider), normalizeValue(snapshot.provider))
                && Objects.equals(normalizeValue(persistedState.getEffectiveRuntimeFamily()), normalizeValue(snapshot.getEffectiveRuntimeFamily()))
                && Objects.equals(normalizeValue(persistedState.sessionId), normalizeValue(snapshot.sessionId))
                && Objects.equals(normalizeValue(persistedState.logicalConversationId), normalizeValue(snapshot.logicalConversationId))
                && Objects.equals(normalizeValue(persistedState.activeSegmentSessionId), normalizeValue(snapshot.activeSegmentSessionId))
                && Objects.equals(normalizeValue(persistedState.parentSegmentSessionId), normalizeValue(snapshot.parentSegmentSessionId))
                && persistedState.continuationPending == snapshot.continuationPending
                && Objects.equals(normalizeValue(persistedState.continuationSourceSessionId), normalizeValue(snapshot.continuationSourceSessionId))
                && Objects.equals(normalizeValue(persistedState.cwd), normalizeValue(snapshot.cwd))
                && Objects.equals(normalizeValue(persistedState.model), normalizeValue(snapshot.model))
                && Objects.equals(normalizeValue(persistedState.permissionMode), normalizeValue(snapshot.permissionMode))
                && Objects.equals(normalizeValue(persistedState.reasoningEffort), normalizeValue(snapshot.reasoningEffort))
                && Objects.equals(normalizeValue(persistedState.codexProviderId), normalizeValue(snapshot.codexProviderId))
                && Objects.equals(normalizeValue(persistedState.codexRequestMode), normalizeValue(snapshot.codexRequestMode))
                && Objects.equals(normalizeValue(persistedState.codexBaseUrlSource), normalizeValue(snapshot.codexBaseUrlSource))
                && Objects.equals(normalizeValue(persistedState.codexEffectiveConfigSource), normalizeValue(snapshot.codexEffectiveConfigSource))
                && Objects.equals(normalizeValue(persistedState.getEffectiveTitleBindingMode()), normalizeValue(snapshot.getEffectiveTitleBindingMode()));
    }

    /**
     * 从 Tab 快照恢复 Codex 会话绑定元数据。
     * 仅当快照里存在 providerId 或 model 时才认为该绑定有效，
     * 避免把普通 Claude 会话误标成 Codex 绑定。
     *
     * @param savedState 已持久化的 Tab 会话快照
     * @return 恢复出的 Codex 绑定；不存在有效绑定时返回 null
     */
    private CodexSessionBinding buildCodexSessionBinding(TabStateService.TabSessionState savedState) {
        if (savedState == null) {
            return null;
        }
        CodexSessionBinding binding = new CodexSessionBinding(
                savedState.codexProviderId,
                savedState.model,
                savedState.codexRequestMode,
                savedState.codexBaseUrlSource,
                savedState.codexEffectiveConfigSource
        );
        return binding.isMeaningful() ? binding : null;
    }

    /**
     * 统一格式化标签恢复链路中的 Codex binding 诊断信息。
     * 只输出 provider/model/requestMode/baseUrlSource/effectiveConfigSource，避免把敏感凭据写入日志。
     *
     * @param binding 当前 tab 快照恢复出的 Codex binding
     * @return 稳定的诊断文本；为空时返回 "(null)"
     */
    private String describeCodexBinding(CodexSessionBinding binding) {
        if (binding == null) {
            return "(null)";
        }
        return "{providerId=" + binding.getProviderId()
                + ", model=" + binding.getModel()
                + ", requestMode=" + binding.getRequestMode()
                + ", baseUrlSource=" + binding.getBaseUrlSource()
                + ", effectiveConfigSource=" + binding.getEffectiveConfigSource()
                + "}";
    }

    /**
     * 记录当前标签页活动会话 runtime 已被真实改写。
     * 持久化标签恢复会直接把 provider/model/reasoning/binding 写回当前 session，
     * 因此必须显式打出 `activeRuntimeMutated`，避免后续把这类启动恢复误判成聊天区选择器触发的即时 runtime 污染。
     *
     * @param source 本次 live runtime 改写来源
     */
    private void logActiveRuntimeMutated(String source) {
        if (session == null) {
            return;
        }
        CodexSessionBinding binding = session.getState().getCodexSessionBinding();
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " activeRuntimeMutated"
                + ", source=" + normalizeValue(source)
                + ", sessionId=" + normalizeValue(session.getSessionId())
                + ", provider=" + normalizeValue(session.getProvider())
                + ", runtimeFamily=" + normalizeValue(savedRuntimeFamilyForTrace(binding))
                + ", model=" + normalizeValue(session.getModel())
                + ", reasoningEffort=" + normalizeValue(session.getReasoningEffort())
                + ", binding=" + describeCodexBinding(binding));
    }

    /**
     * 统一推导当前标签页会话运行时家族，避免多处日志重复拼接相同分支。
     *
     * @param binding 当前会话上的 Codex binding
     * @return 归一化后的 runtime family 字符串
     */
    private String savedRuntimeFamilyForTrace(CodexSessionBinding binding) {
        return SessionRuntimeFamily.resolve(
                session != null ? session.getProvider() : null,
                null,
                binding
        );
    }

    /**
     * 主动触发项目级持久化落盘。
     * 仅用于少量关键状态变更，确保 tab 绑定会话和标题在 IDE 快速关闭前也能写入 .idea 状态文件。
     *
     * @param reason 落盘原因，便于日志排查
     * @param tabIndex 当前 Tab 索引
     * @param currentSessionId 当前会话 ID
     */
    private void requestProjectStateFlush(String reason, int tabIndex, String currentSessionId) {
        if (project == null || project.isDisposed()) {
            return;
        }
        int requestCount = projectStateFlushRequestCount.incrementAndGet();
        LOG.info("[TabStateService] Queue project state flush: reason=" + reason
                + ", tabIndex=" + tabIndex
                + ", sessionId=" + currentSessionId
                + ", requestCount=" + requestCount);
        synchronized (projectStateFlushLock) {
            if (pendingProjectStateFlush != null) {
                pendingProjectStateFlush.cancel(false);
            }
            pendingProjectStateFlush = AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    () -> flushProjectStateToDisk(reason, tabIndex, currentSessionId),
                    PROJECT_STATE_FLUSH_DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * 执行真正的项目级状态落盘。
     * 该方法只允许从防抖调度器进入，避免 session 恢复、frontend ready 等热路径直接阻塞 EDT。
     *
     * @param reason 落盘原因
     * @param tabIndex 当前 Tab 索引
     * @param currentSessionId 当前会话 ID
     */
    private void flushProjectStateToDisk(String reason, int tabIndex, String currentSessionId) {
        Runnable saveTask = () -> {
            if (project.isDisposed()) {
                return;
            }
            int executeCount = projectStateFlushExecuteCount.incrementAndGet();
            project.save();
            LOG.info("[TabStateService] Flushed project state: reason=" + reason
                    + ", tabIndex=" + tabIndex
                    + ", sessionId=" + currentSessionId
                    + ", executeCount=" + executeCount
                    + ", requestCount=" + projectStateFlushRequestCount.get());
        };

        Application application = ApplicationManager.getApplication();
        if (application == null) {
            saveTask.run();
            return;
        }
        application.invokeLater(saveTask);
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
        if (this.disposed) { return; }
        this.disposed = true;
        synchronized (projectStateFlushLock) {
            if (pendingProjectStateFlush != null) {
                pendingProjectStateFlush.cancel(false);
                pendingProjectStateFlush = null;
            }
        }

        chatWindowDelegate.dispose();
        editorContextTracker.dispose();
        streamCoalescer.dispose();
        if (sessionCallbackAdapter != null) {
            sessionCallbackAdapter.dispose();
        }
        if (titleEventListener != null && claudeSDKBridge != null) {
            try {
                claudeSDKBridge.removeDaemonEventListener(titleEventListener);
            } catch (Exception e) {
                LOG.warn("Failed to remove daemon event listener: " + e.getMessage());
            }
            titleEventListener = null;
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
            if (session != null) { session.interrupt(); }
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

            @Override
            public boolean shouldApplyFreshNewTabDefaults() {
                return ClaudeChatWindow.this.initializationSource == InitializationSource.FRESH_NEW_TAB;
            }

            @Override
            public boolean areFreshNewTabDefaultsApplied() {
                return freshNewTabDefaultsApplied.get();
            }

            @Override
            public void markFreshNewTabDefaultsApplied() {
                freshNewTabDefaultsApplied.set(true);
            }
        };
    }
}
