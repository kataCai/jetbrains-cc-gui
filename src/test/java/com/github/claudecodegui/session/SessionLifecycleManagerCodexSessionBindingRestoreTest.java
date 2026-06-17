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
import static org.junit.Assert.assertNull;

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
        assertNull(session.getState().getCodexSessionBinding());
    }

    /**
     * 验证 Codex 运行时切换后如果前端立即触发新会话创建，新 session 仍会继承旧 session 的 tab 级 binding。
     * 这条用例直接覆盖本次真实故障根因：旧 session 已绑定 MiniMax，但 createNewSession 只复制 provider/model，
     * 没有复制 providerId 等 binding 元数据时，后续发送会错误回退到全局 active provider。
     */
    @Test
    public void shouldCopyCodexSessionBindingWhenCreatingNewSession() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null);
        ClaudeSession oldSession = new ClaudeSession(project, null, null);
        oldSession.setProvider("codex");
        oldSession.setModel("MiniMax-M3");
        oldSession.getState().setCodexSessionBinding(new CodexSessionBinding(
                "managed-minimax",
                "MiniMax-M3",
                "codex_sdk",
                "provider",
                "managed_provider"
        ));

        ClaudeSession newSession = new ClaudeSession(project, null, null);
        newSession.setProvider("codex");
        newSession.setModel("MiniMax-M3");

        manager.copyCodexSessionBindingForTest(oldSession, newSession);

        assertNotNull(newSession.getState().getCodexSessionBinding());
        assertEquals("managed-minimax", newSession.getState().getCodexSessionBinding().getProviderId());
        assertEquals("MiniMax-M3", newSession.getState().getCodexSessionBinding().getModel());
        assertEquals("codex_sdk", newSession.getState().getCodexSessionBinding().getRequestMode());
        assertEquals("provider", newSession.getState().getCodexSessionBinding().getBaseUrlSource());
        assertEquals("managed_provider", newSession.getState().getCodexSessionBinding().getEffectiveConfigSource());
    }

    /**
     * 验证旧 session 不存在 Codex binding 时，复制逻辑不会给新 session 凭空注入脏状态。
     * 这用于约束后续实现保持“只复制有效 binding，不扩大作用范围”的边界。
     */
    @Test
    public void shouldSkipCopyWhenOldSessionHasNoCodexBinding() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null);
        ClaudeSession oldSession = new ClaudeSession(project, null, null);
        oldSession.setProvider("claude");
        oldSession.setModel("claude-sonnet-4-6");

        ClaudeSession newSession = new ClaudeSession(project, null, null);
        newSession.setProvider("claude");
        newSession.setModel("claude-sonnet-4-6");

        manager.copyCodexSessionBindingForTest(oldSession, newSession);

        assertNull(newSession.getState().getCodexSessionBinding());
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

        /**
         * 暴露给测试的受控入口，用于直接验证“旧 session -> 新 session”的 Codex binding 复制行为。
         *
         * @param oldSession 作为绑定来源的旧会话
         * @param newSession 待写入绑定的新会话
         */
        private void copyCodexSessionBindingForTest(ClaudeSession oldSession, ClaudeSession newSession) {
            copyCodexSessionBindingIfPresent(oldSession, newSession);
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
