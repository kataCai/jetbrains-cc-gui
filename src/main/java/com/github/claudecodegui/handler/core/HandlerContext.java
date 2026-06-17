package com.github.claudecodegui.handler.core;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

import java.util.function.Consumer;

/**
 * Handler context.
 * Provides all shared resources and callbacks needed by handlers.
 */
public class HandlerContext {

    public static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    public static final String DEFAULT_PROVIDER = "claude";

    private final Project project;
    private final ClaudeSDKBridge claudeSDKBridge;
    private final CodexSDKBridge codexSDKBridge;
    private final CodemossSettingsService settingsService;
    private final JsCallback jsCallback;

    // Mutable state accessed via getters/setters — volatile for thread safety
    private volatile ClaudeSession session;
    private volatile JBCefBrowser browser;
    private volatile String currentModel = DEFAULT_MODEL;
    private volatile String currentProvider = DEFAULT_PROVIDER;
    private volatile Consumer<String> sessionRetryingCallback;
    private volatile Runnable tabSessionPersistenceCallback;
    private volatile boolean disposed = false;

    /**
     * JavaScript callback interface.
     */
    public interface JsCallback {
        void callJavaScript(String functionName, String... args);
        String escapeJs(String str);
    }

    public HandlerContext(
            Project project,
            ClaudeSDKBridge claudeSDKBridge,
            CodexSDKBridge codexSDKBridge,
            CodemossSettingsService settingsService,
            JsCallback jsCallback
    ) {
        this.project = project;
        this.claudeSDKBridge = claudeSDKBridge;
        this.codexSDKBridge = codexSDKBridge;
        this.settingsService = settingsService;
        this.jsCallback = jsCallback;
    }

    // Getters
    public Project getProject() {
        return project;
    }

    public ClaudeSDKBridge getClaudeSDKBridge() {
        return claudeSDKBridge;
    }

    public CodexSDKBridge getCodexSDKBridge() {
        return codexSDKBridge;
    }

    public CodemossSettingsService getSettingsService() {
        return settingsService;
    }

    public ClaudeSession getSession() {
        return session;
    }

    public JBCefBrowser getBrowser() {
        return browser;
    }

    public String getCurrentModel() {
        return currentModel;
    }

    public String getCurrentProvider() {
        return currentProvider;
    }

    /**
     * 返回会话层透传重试信号时要调用的桥接回调。
     *
     * @return 重试信号消费回调；未配置时返回 null
     */
    public Consumer<String> getSessionRetryingCallback() {
        return sessionRetryingCallback;
    }

    public boolean isDisposed() {
        return disposed;
    }

    // Setters
    public void setSession(ClaudeSession session) {
        this.session = session;
    }

    public void setBrowser(JBCefBrowser browser) {
        this.browser = browser;
    }

    public void setCurrentModel(String currentModel) {
        this.currentModel = currentModel;
    }

    public void setCurrentProvider(String currentProvider) {
        this.currentProvider = currentProvider;
    }

    /**
     * 设置会话重试信号的桥接回调。
     *
     * @param sessionRetryingCallback 接收 provider 重试摘要的回调
     * @return 无返回值
     */
    public void setSessionRetryingCallback(Consumer<String> sessionRetryingCallback) {
        this.sessionRetryingCallback = sessionRetryingCallback;
    }

    /**
     * 注册“持久化当前标签运行态快照”的回调。
     * 仅由拥有具体窗口上下文的上层（如 ChatWindowDelegate）注入，
     * handler 侧只负责在 provider/model 等运行态变更后发起请求。
     *
     * @param tabSessionPersistenceCallback 当前标签快照持久化回调
     */
    public void setTabSessionPersistenceCallback(Runnable tabSessionPersistenceCallback) {
        this.tabSessionPersistenceCallback = tabSessionPersistenceCallback;
    }

    public void setDisposed(boolean disposed) {
        this.disposed = disposed;
    }

    // JavaScript callback proxy methods
    public void callJavaScript(String functionName, String... args) {
        jsCallback.callJavaScript(functionName, args);
    }

    public String escapeJs(String str) {
        return jsCallback.escapeJs(str);
    }

    /**
     * Execute JavaScript on the EDT (Event Dispatch Thread).
     */
    public void executeJavaScriptOnEDT(String jsCode) {
        if (browser != null && !disposed) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (browser != null && !disposed) {
                    browser.getCefBrowser().executeJavaScript(jsCode, browser.getCefBrowser().getURL(), 0);
                }
            });
        }
    }

    /**
     * 请求当前标签立即持久化运行态快照。
     * 若当前上下文未注入窗口级持久化能力，则静默跳过。
     */
    public void requestTabSessionPersistence() {
        Runnable callback = tabSessionPersistenceCallback;
        if (callback != null) {
            callback.run();
        }
    }
}
