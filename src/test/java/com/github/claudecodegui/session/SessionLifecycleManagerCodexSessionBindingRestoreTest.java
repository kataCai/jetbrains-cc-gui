package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * SessionLifecycleManager 中 Codex 会话绑定恢复行为测试。
 * 该测试聚焦“历史会话恢复时是否会把插件侧持久化的 provider/model 绑定重新挂回 SessionState”，
 * 避免会话恢复后继续发送又误命中当前 active provider。
 */
public class SessionLifecycleManagerCodexSessionBindingRestoreTest {

    /**
     * 验证存在持久化绑定时，会话恢复流程会同步回填 provider、model 和 SessionState 绑定。
     */
    @Test
    public void shouldRestoreCodexSessionBindingWhenPresent() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        CodexSessionBinding expectedBinding = new CodexSessionBinding(
                "minimax-provider",
                "MiniMax-M1",
                "codex_sdk",
                "provider",
                "managed_provider"
        );
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, expectedBinding);
        ClaudeSession session = new ClaudeSession(project, null, null);
        session.setProvider("claude");
        session.setModel("claude-sonnet-4-6");

        manager.restoreCodexSessionBindingIfPresent(session, "session-001");

        assertEquals("codex", session.getProvider());
        assertEquals("MiniMax-M1", session.getModel());
        assertNotNull(session.getState().getCodexSessionBinding());
        assertEquals("minimax-provider", session.getState().getCodexSessionBinding().getProviderId());
        assertEquals("managed_provider", session.getState().getCodexSessionBinding().getEffectiveConfigSource());
    }

    /**
     * 验证不存在持久化绑定时，不会错误改写当前会话的 provider/model。
     */
    @Test
    public void shouldKeepSessionUntouchedWhenCodexBindingMissing() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null);
        ClaudeSession session = new ClaudeSession(project, null, null);
        session.setProvider("claude");
        session.setModel("claude-sonnet-4-6");

        manager.restoreCodexSessionBindingIfPresent(session, "session-001");

        assertEquals("claude", session.getProvider());
        assertEquals("claude-sonnet-4-6", session.getModel());
        assertEquals(null, session.getState().getCodexSessionBinding());
    }

    /**
     * 测试专用 SessionLifecycleManager，通过覆写配置服务入口稳定注入指定绑定。
     */
    private static final class TestableSessionLifecycleManager extends SessionLifecycleManager {
        private final CodexSessionBinding binding;

        private TestableSessionLifecycleManager(SessionHost host, CodexSessionBinding binding) {
            super(host);
            this.binding = binding;
        }

        @Override
        protected CodemossSettingsService createSettingsService() {
            return new CodemossSettingsService() {
                @Override
                public CodexSessionBinding getCodexSessionBinding(String sessionId) {
                    return binding;
                }
            };
        }
    }

    /**
     * 最小 SessionHost 测试桩，只提供被测逻辑必需依赖。
     */
    private static final class RecordingHost implements SessionLifecycleManager.SessionHost {
        private final Project project;
        private final HandlerContext handlerContext;
        private ClaudeSession session;

        private RecordingHost(Project project) {
            this.project = project;
            this.session = new ClaudeSession(project, null, null);
            this.handlerContext = new HandlerContext(
                    project,
                    null,
                    null,
                    new CodemossSettingsService(),
                    new HandlerContext.JsCallback() {
                        @Override
                        public void callJavaScript(String functionName, String... args) {
                        }

                        @Override
                        public String escapeJs(String str) {
                            return str;
                        }
                    }
            );
            this.handlerContext.setSession(session);
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
        public void setSession(ClaudeSession session) {
            this.session = session;
        }

        @Override
        public HandlerContext getHandlerContext() {
            return handlerContext;
        }

        @Override
        public StreamMessageCoalescer getStreamCoalescer() {
            return null;
        }

        @Override
        public void clearPendingPermissionRequests() {
        }

        @Override
        public void clearPermissionDecisionMemory() {
        }

        @Override
        public void callJavaScript(String functionName, String... args) {
        }

        @Override
        public boolean isDisposed() {
            return false;
        }

        @Override
        public JBCefBrowser getBrowser() {
            return null;
        }

        @Override
        public void setupSessionCallbacks() {
        }

        @Override
        public void invalidateSessionCallbacks() {
        }

        @Override
        public void setSlashCommandsFetched(boolean fetched) {
        }

        @Override
        public void setFetchedSlashCommandsCount(int count) {
        }
    }

    /**
     * 创建最小 Project 代理，满足会话对象初始化所需的非空依赖。
     */
    private static Project createProject() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "getBasePath" -> System.getProperty("java.io.tmpdir");
                    case "getName" -> "session-lifecycle-codex-binding-restore-test";
                    case "toString" -> "session-lifecycle-codex-binding-restore-test";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive()
                            ? defaultPrimitiveValue(method.getReturnType())
                            : null;
                }
        );
    }

    /**
     * 返回指定原始类型的默认值，供 Project 动态代理兜底使用。
     */
    private static Object defaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        return 0;
    }
}
