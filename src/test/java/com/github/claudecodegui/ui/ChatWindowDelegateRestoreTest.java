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
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.ui.jcef.JBCefBrowser;
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

    private static Project createProject() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "getName" -> "chat-window-delegate-test";
                    default -> method.getReturnType().isPrimitive() ? defaultPrimitiveValue(method.getReturnType()) : null;
                }
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
        private final List<String> jsFunctionNames = new ArrayList<>();
        private final List<String> jsPayloads = new ArrayList<>();
        private boolean frontendReady;

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
            return settingsService;
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
        }

        @Override
        public void setMessageDispatcher(com.github.claudecodegui.handler.core.MessageDispatcher d) {
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
            return null;
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
