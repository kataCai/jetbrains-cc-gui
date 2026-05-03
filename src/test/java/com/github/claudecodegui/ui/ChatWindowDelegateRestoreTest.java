package com.github.claudecodegui.ui;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionLifecycleManager;
import com.github.claudecodegui.session.StreamMessageCoalescer;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.ui.toolwindow.TabSessionRestoreState;
import com.intellij.openapi.project.Project;
import com.intellij.ui.content.Content;
import com.intellij.ui.jcef.JBCefBrowser;

import org.junit.Test;

import java.lang.reflect.Proxy;

import javax.swing.JPanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ChatWindowDelegate 恢复链路测试。
 * 验证前端 ready 后会消费待恢复请求并只触发一次历史加载，
 * 避免启动恢复和手动强制刷新在多次 ready 场景下重复执行。
 */
public class ChatWindowDelegateRestoreTest {

    /**
     * 验证前端 ready 后会触发一次待恢复会话加载，并在二次 ready 时不重复恢复。
     */
    @Test
    public void shouldTriggerPendingRestoreOnlyOnceWhenFrontendBecomesReady() {
        RecordingSessionLifecycleManager lifecycleManager = new RecordingSessionLifecycleManager();
        RecordingHost host = new RecordingHost(lifecycleManager);
        ChatWindowDelegate delegate = new ChatWindowDelegate(host);

        host.restoreState.schedulePersistedRestore("session-ready-1", "/workspace/demo");

        delegate.handleFrontendReady();
        delegate.handleFrontendReady();

        assertEquals(1, lifecycleManager.loadHistoryCallCount);
        assertEquals("session-ready-1", lifecycleManager.lastSessionId);
        assertEquals("/workspace/demo", lifecycleManager.lastProjectPath);
        assertTrue(host.frontendReady);
        assertFalse(host.hasPendingRestoreRequest());
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
        private final Project project = createProject();
        private final ClaudeSession session = new ClaudeSession(project, null, null);
        private final RecordingSessionLifecycleManager lifecycleManager;
        private final RecordingStreamMessageCoalescer streamMessageCoalescer;
        private final HandlerContext handlerContext;
        private final JPanel mainPanel = new JPanel();
        private final CodemossSettingsService settingsService = new CodemossSettingsService();
        private final TabSessionRestoreState restoreState = new TabSessionRestoreState();
        private boolean frontendReady;

        private RecordingHost(RecordingSessionLifecycleManager lifecycleManager) {
            this.lifecycleManager = lifecycleManager;
            this.streamMessageCoalescer = new RecordingStreamMessageCoalescer();
            this.handlerContext = new HandlerContext(project, null, null, settingsService, new HandlerContext.JsCallback() {
                @Override
                public void callJavaScript(String functionName, String... args) {
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
            return streamMessageCoalescer;
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
        }

        @Override
        public void sendCurrentPermissionMode() {
        }
    }

    private static final class RecordingStreamMessageCoalescer extends StreamMessageCoalescer {
        private RecordingStreamMessageCoalescer() {
            super(new JsCallbackTarget() {
                @Override
                public void callJavaScript(String functionName, String... args) {
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
                public HandlerContext getHandlerContext() {
                    return null;
                }
            });
        }

        @Override
        public void flush(java.util.function.LongConsumer afterFlushOnEdt) {
        }
    }
}
