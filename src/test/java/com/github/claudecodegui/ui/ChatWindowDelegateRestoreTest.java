package com.github.claudecodegui.ui;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.ui.toolwindow.TabSessionRestoreState;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import org.junit.Test;

import javax.swing.JPanel;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ChatWindowDelegate 恢复链路测试。
 * 验证 frontend ready 后既会消费待恢复请求，也会把当前标签的运行态快照优先回推给前端。
 */
public class ChatWindowDelegateRestoreTest {

    @Test
    public void shouldTriggerPendingRestoreOnlyOnceWhenFrontendBecomesReady() {
        RecordingSessionLifecycleManager lifecycleManager = new RecordingSessionLifecycleManager();
        RecordingHost host = new RecordingHost(lifecycleManager);
        ChatWindowDelegate delegate = new ChatWindowDelegate(host);

        host.restoreState.schedulePersistedRestore("session-ready-1", "/workspace/demo", "claude", "claude");

        delegate.handleFrontendReady();
        delegate.handleFrontendReady();

        assertEquals(1, lifecycleManager.loadHistoryCallCount);
        assertEquals("session-ready-1", lifecycleManager.lastSessionId);
        assertEquals("/workspace/demo", lifecycleManager.lastProjectPath);
        assertEquals("claude", lifecycleManager.lastRuntimeFamily);
        assertTrue(host.frontendReady);
        assertFalse(host.hasPendingRestoreRequest());
    }

    @Test
    public void shouldReplayTabRuntimeStateToFrontendWhenFrontendBecomesReady() {
        RecordingSessionLifecycleManager lifecycleManager = new RecordingSessionLifecycleManager();
        RecordingHost host = new RecordingHost(lifecycleManager);
        host.session.setProvider("codex");
        host.session.setModel("gpt-5.4");
        host.session.setPermissionMode("default");
        host.session.setReasoningEffort("high");
        host.session.setSessionInfo("session-codex-1", "/workspace/demo");
        host.session.getState().setCodexSessionBinding(new CodexSessionBinding(
                "provider-b",
                "gpt-5.4",
                "codex_sdk",
                "provider",
                "codemoss_managed_provider"
        ));

        ChatWindowDelegate delegate = new ChatWindowDelegate(host);
        delegate.handleFrontendReady();

        JsonObject payload = host.findFirstPayload("window.restoreTabRuntimeState");
        assertEquals("codex", payload.get("provider").getAsString());
        assertEquals("codex", payload.get("runtimeFamily").getAsString());
        assertEquals("gpt-5.4", payload.get("model").getAsString());
        assertEquals("default", payload.get("permissionMode").getAsString());
        assertEquals("high", payload.get("reasoningEffort").getAsString());
        assertEquals("provider-b", payload.get("codexProviderId").getAsString());
    }

    @Test
    public void shouldApplyFreshNewTabDefaultsOnlyForFreshNewTabWindows() {
        RecordingSessionLifecycleManager lifecycleManager = new RecordingSessionLifecycleManager();
        RecordingHost host = new RecordingHost(lifecycleManager);
        host.applyFreshNewTabDefaults = true;
        host.freshNewTabDefaults = new JsonObject();
        host.freshNewTabDefaults.addProperty("provider", "codex");
        host.freshNewTabDefaults.addProperty("permissionMode", "bypassPermissions");
        host.freshNewTabDefaults.addProperty("model", "gpt-5.4");
        host.freshNewTabDefaults.addProperty("reasoningEffort", "low");
        host.freshNewTabDefaults.addProperty("codexProviderId", "provider-a");

        ChatWindowDelegate delegate = new ChatWindowDelegate(host);
        delegate.handleFrontendReady();

        JsonObject payload = host.findFirstPayload("window.applyNewTabDefaults");
        assertEquals("codex", payload.get("provider").getAsString());
        assertEquals("gpt-5.4", payload.get("model").getAsString());
        assertEquals("low", payload.get("reasoningEffort").getAsString());
        assertEquals("provider-a", payload.get("codexProviderId").getAsString());
        assertEquals("codex", host.session.getProvider());
        assertEquals("gpt-5.4", host.session.getModel());
        assertEquals("low", host.session.getReasoningEffort());
        assertEquals("provider-a", host.session.getState().getCodexSessionBinding().getProviderId());
    }

    @Test
    public void shouldApplyFreshNewTabDefaultsAtMostOncePerTabLifecycle() {
        RecordingSessionLifecycleManager lifecycleManager = new RecordingSessionLifecycleManager();
        RecordingHost host = new RecordingHost(lifecycleManager);
        host.applyFreshNewTabDefaults = true;
        host.freshNewTabDefaults = new JsonObject();
        host.freshNewTabDefaults.addProperty("provider", "codex");
        host.freshNewTabDefaults.addProperty("permissionMode", "bypassPermissions");
        host.freshNewTabDefaults.addProperty("model", "gpt-5.4");
        host.freshNewTabDefaults.addProperty("reasoningEffort", "low");
        host.freshNewTabDefaults.addProperty("codexProviderId", "provider-a");

        ChatWindowDelegate delegate = new ChatWindowDelegate(host);
        delegate.handleFrontendReady();
        delegate.handleFrontendReady();

        long applyCalls = host.jsFunctionNames.stream()
                .filter("window.applyNewTabDefaults"::equals)
                .count();
        assertEquals(1L, applyCalls);
        assertTrue(host.freshNewTabDefaultsApplied);
    }

    /**
     * 验证聊天窗口尚未挂载 SessionLifecycleManager 时，handler 初始化也不能直接抛异常。
     * 该场景对应真实窗口构造顺序：delegate 会先创建并注册 handler，随后才把 lifecycle manager 赋值到 host。
     * 回归目标是确保发送前准备逻辑改为惰性解析后，不再在初始化阶段提前绑定空的 lifecycle manager。
     */
    @Test
    public void shouldInitializeHandlersWhenSessionLifecycleManagerIsNotReadyYet() {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = com.intellij.openapi.util.Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        try {
            RecordingHost host = new RecordingHost(null);
            ChatWindowDelegate delegate = new ChatWindowDelegate(host);

            delegate.initializeHandlers();

            assertTrue("initializeHandlers 完成后应写回 handlerContext", host.handlerContextAssigned);
            assertTrue("initializeHandlers 完成后应注册 messageDispatcher", host.messageDispatcherAssigned);
        } finally {
            if (testDisposable != null) {
                com.intellij.openapi.util.Disposer.dispose(testDisposable);
            }
        }
    }

    private static Project createProject() {
        MessageBus messageBus = createMessageBus();
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "getName" -> "chat-window-delegate-test";
                    case "getMessageBus" -> messageBus;
                    default -> method.getReturnType().isPrimitive() ? defaultPrimitiveValue(method.getReturnType()) : null;
                }
        );
    }

    /**
     * 创建最小 MessageBus 测试桩，满足 PromptHandler 初始化阶段对 `connect()` 的依赖。
     * 当前测试不关心真正的 VFS 订阅行为，因此这里只提供可连接、可订阅、可断开的空实现。
     *
     * @return 供 Project 代理返回的最小 MessageBus
     */
    private static MessageBus createMessageBus() {
        MessageBusConnection connection = createMessageBusConnection();
        return (MessageBus) Proxy.newProxyInstance(
                MessageBus.class.getClassLoader(),
                new Class[]{MessageBus.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "connect" -> connection;
                    default -> method.getReturnType().isPrimitive() ? defaultPrimitiveValue(method.getReturnType()) : null;
                }
        );
    }

    /**
     * 创建最小 MessageBusConnection 测试桩。
     * 该桩只需要吞掉 `subscribe()`、`disconnect()` 等调用，
     * 避免 PromptFileWatcher 在测试里因为缺少 IDE 真实消息总线而提前失败。
     *
     * @return 空操作的 MessageBusConnection
     */
    private static MessageBusConnection createMessageBusConnection() {
        return (MessageBusConnection) Proxy.newProxyInstance(
                MessageBusConnection.class.getClassLoader(),
                new Class[]{MessageBusConnection.class},
                (proxy, method, args) -> method.getReturnType().isPrimitive()
                        ? defaultPrimitiveValue(method.getReturnType())
                        : null
        );
    }

    private static Object defaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        return 0;
    }

    private static final class RecordingHost implements ChatWindowDelegate.DelegateHost {
        private final Gson gson = new Gson();
        private final Project project = createProject();
        private final ClaudeSession session = new ClaudeSession(project, null, null);
        private final RecordingSessionLifecycleManager lifecycleManager;
        private final HandlerContext handlerContext;
        private final JPanel mainPanel = new JPanel();
        private final CodemossSettingsService settingsService = new CodemossSettingsService();
        private final TabSessionRestoreState restoreState = new TabSessionRestoreState();
        private final WebviewWatchdog webviewWatchdog = new WebviewWatchdog(
                mainPanel,
                () -> null,
                null,
                () -> { },
                () -> false,
                () -> false
        );
        private final List<String> jsFunctionNames = new ArrayList<>();
        private final List<String> jsPayloads = new ArrayList<>();
        private boolean handlerContextAssigned;
        private boolean messageDispatcherAssigned;
        private boolean frontendReady;
        private boolean applyFreshNewTabDefaults;
        private boolean freshNewTabDefaultsApplied;
        private JsonObject freshNewTabDefaults;

        private RecordingHost(RecordingSessionLifecycleManager lifecycleManager) {
            this.lifecycleManager = lifecycleManager;
            this.handlerContext = new HandlerContext(project, null, null, settingsService, new HandlerContext.JsCallback() {
                @Override
                public void callJavaScript(String functionName, String... args) {
                    RecordingHost.this.callJavaScript(functionName, args);
                }

                @Override
                public String escapeJs(String str) {
                    return str;
                }
            });
            handlerContext.setSession(session);
        }

        private boolean hasPendingRestoreRequest() {
            return restoreState.hasPendingRestoreRequest();
        }

        private JsonObject findFirstPayload(String functionName) {
            for (int i = 0; i < jsFunctionNames.size(); i++) {
                if (functionName.equals(jsFunctionNames.get(i))) {
                    return gson.fromJson(unescapeJsString(jsPayloads.get(i)), JsonObject.class);
                }
            }
            return new JsonObject();
        }

        private String unescapeJsString(String value) {
            if (value == null) {
                return "";
            }
            return value
                    .replace("\\\\", "\\")
                    .replace("\\\"", "\"")
                    .replace("\\'", "'")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r");
        }

        @Override
        public Project getProject() {
            return project;
        }

        @Override
        public ClaudeSDKBridge getClaudeSDKBridge() {
            return null;
        }

        @Override
        public CodexSDKBridge getCodexSDKBridge() {
            return null;
        }

        @Override
        public ClaudeSession getSession() {
            return session;
        }

        @Override
        public CodemossSettingsService getSettingsService() {
            return new CodemossSettingsService() {
                @Override
                public JsonObject buildFreshNewTabDefaults() {
                    return freshNewTabDefaults == null ? new JsonObject() : freshNewTabDefaults.deepCopy();
                }

                @Override
                public JsonObject getCodexProviderById(String providerId) {
                    JsonObject provider = new JsonObject();
                    provider.addProperty("id", providerId);
                    provider.addProperty("requestMode", "codex_sdk");
                    JsonArray models = new JsonArray();
                    JsonObject model = new JsonObject();
                    model.addProperty("id", "gpt-5.4");
                    models.add(model);
                    provider.add("models", models);
                    return provider;
                }
            };
        }

        @Override
        public JPanel getMainPanel() {
            return mainPanel;
        }

        @Override
        public JBCefBrowser getBrowser() {
            return null;
        }

        @Override
        public boolean isDisposed() {
            return false;
        }

        @Override
        public void callJavaScript(String fn, String... args) {
            jsFunctionNames.add(fn);
            jsPayloads.add(args != null && args.length > 0 ? args[0] : "");
        }

        @Override
        public Content getParentContent() {
            return null;
        }

        @Override
        public String getOriginalTabName() {
            return null;
        }

        @Override
        public void setOriginalTabName(String name) {
        }

        @Override
        public String getSessionId() {
            return session.getSessionId();
        }

        @Override
        public HandlerContext getHandlerContext() {
            return handlerContext;
        }

        @Override
        public void setHandlerContext(HandlerContext ctx) {
            handlerContextAssigned = ctx != null;
        }

        @Override
        public void setMessageDispatcher(com.github.claudecodegui.handler.core.MessageDispatcher d) {
            messageDispatcherAssigned = d != null;
        }

        @Override
        public void setPermissionHandler(com.github.claudecodegui.handler.PermissionHandler h) {
        }

        @Override
        public void setHistoryHandler(com.github.claudecodegui.handler.history.HistoryHandler h) {
        }

        @Override
        public SessionLifecycleManager getSessionLifecycleManager() {
            return lifecycleManager;
        }

        @Override
        public StreamMessageCoalescer getStreamCoalescer() {
            return null;
        }

        @Override
        public WebviewWatchdog getWebviewWatchdog() {
            return webviewWatchdog;
        }

        @Override
        public com.github.claudecodegui.handler.PermissionHandler getPermissionHandler() {
            return null;
        }

        @Override
        public void interruptDueToPermissionDenial() {
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
        }

        @Override
        public void setFetchedSlashCommandsCount(int count) {
        }

        @Override
        public void persistTabSessionState() {
        }

        @Override
        public TabSessionRestoreState.RestoreRequest consumePendingRestoreRequest() {
            return restoreState.consumePendingRestoreRequest();
        }

        @Override
        public void markPendingRestoreStarted() {
            restoreState.markRestoreStarted();
        }

        @Override
        public void updateSessionTitle(String title) {
        }

        @Override
        public boolean shouldApplyFreshNewTabDefaults() {
            return applyFreshNewTabDefaults;
        }

        @Override
        public boolean areFreshNewTabDefaultsApplied() {
            return freshNewTabDefaultsApplied;
        }

        @Override
        public void markFreshNewTabDefaultsApplied() {
            freshNewTabDefaultsApplied = true;
        }
    }

    private static final class RecordingSessionLifecycleManager extends SessionLifecycleManager {
        private int loadHistoryCallCount;
        private String lastSessionId;
        private String lastProjectPath;
        private String lastRuntimeFamily;
        private String lastRestoreSource;
        private String lastTransitionToken;

        private RecordingSessionLifecycleManager() {
            super(new SessionLifecycleManager.SessionHost() {
                @Override public Project getProject() { return null; }
                @Override public ClaudeSDKBridge getClaudeSDKBridge() { return null; }
                @Override public CodexSDKBridge getCodexSDKBridge() { return null; }
                @Override public ClaudeSession getSession() { return null; }
                @Override public void setSession(ClaudeSession session) { }
                @Override public HandlerContext getHandlerContext() { return null; }
                @Override public StreamMessageCoalescer getStreamCoalescer() { return null; }
                @Override public void clearPendingPermissionRequests() { }
                @Override public void clearPermissionDecisionMemory() { }
                @Override public void callJavaScript(String functionName, String... args) { }
                @Override public boolean isDisposed() { return false; }
                @Override public JBCefBrowser getBrowser() { return null; }
                @Override public void setupSessionCallbacks() { }
                @Override public void invalidateSessionCallbacks() { }
                @Override public void setSlashCommandsFetched(boolean fetched) { }
                @Override public void setFetchedSlashCommandsCount(int count) { }
            });
        }

        @Override
        public void loadHistorySession(String sessionId, String projectPath) {
            loadHistoryCallCount++;
            lastSessionId = sessionId;
            lastProjectPath = projectPath;
            lastRuntimeFamily = "claude";
            lastRestoreSource = "history_switch";
        }

        @Override
        public void loadHistorySession(
                String sessionId,
                String projectPath,
                String provider,
                String runtimeFamily,
                String restoreSource,
                String transitionToken
        ) {
            loadHistoryCallCount++;
            lastSessionId = sessionId;
            lastProjectPath = projectPath;
            lastRuntimeFamily = runtimeFamily;
            lastRestoreSource = restoreSource;
            lastTransitionToken = transitionToken;
        }

        @Override
        public void sendCurrentPermissionMode() {
        }
    }
}
