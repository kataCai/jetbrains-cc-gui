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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * SessionLifecycleManager 历史恢复幂等保护测试。
 * 该测试聚焦“同一 sessionId + transitionToken + restoreSource 在同一恢复周期内只能被受理一次”的边界，
 * 避免重复恢复请求再次打断当前会话、重复清空前端消息，最终造成同一历史快照被双重注入。
 */
public class SessionLifecycleManagerHistoryRestoreIdempotencyTest {

    /**
     * 验证同一恢复 key 在首次完成释放前，只允许第一条请求进入执行。
     * 该场景对应前端或后端链路意外重复派发同一历史恢复请求时的最小兜底。
     */
    @Test
    public void shouldRejectDuplicateHistoryRestoreRequestUntilPreviousCycleFinishes() {
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(new RecordingHost(createProject()));

        assertTrue(manager.acquireForTest("session-001", "history_switch", "transition-001"));
        assertFalse(manager.acquireForTest("session-001", "history_switch", "transition-001"));

        manager.finishForTest("session-001", "history_switch", "transition-001");

        assertFalse(manager.acquireForTest("session-001", "history_switch", "transition-001"));
        assertTrue(manager.acquireForTest("session-001", "history_switch", "transition-002"));
    }

    /**
     * 验证恢复 key 的拼接顺序稳定且不会因为空值崩溃。
     * 这样前后端日志与重复恢复判断才能在缺失 transitionToken 的补偿链路下仍保持一致。
     */
    @Test
    public void shouldBuildStableHistoryRestoreRequestKey() {
        assertEquals(
                "session-001|history_switch|transition-001",
                SessionLifecycleManager.buildHistoryRestoreRequestKey(
                        "session-001",
                        "history_switch",
                        "transition-001"
                )
        );
        assertEquals(
                "session-002|history_switch|(none)",
                SessionLifecycleManager.buildHistoryRestoreRequestKey(
                        "session-002",
                        "history_switch",
                        null
                )
        );
    }

    /**
     * 测试专用 SessionLifecycleManager。
     * 通过受控包装方法暴露恢复 key 的申请与释放行为，避免把测试耦合到完整的异步历史恢复流程。
     */
    private static final class TestableSessionLifecycleManager extends SessionLifecycleManager {
        private TestableSessionLifecycleManager(SessionHost host) {
            super(host);
        }

        /**
         * 申请一条测试专用的历史恢复 key。
         *
         * @param sessionId 会话 ID
         * @param restoreSource 恢复来源
         * @param transitionToken 前端切换令牌
         * @return `true` 表示首次受理；`false` 表示被幂等保护拒绝
         */
        private boolean acquireForTest(String sessionId, String restoreSource, String transitionToken) {
            return tryAcquireHistoryRestoreRequest(sessionId, restoreSource, transitionToken) != null;
        }

        /**
         * 释放测试中已完成的历史恢复 key，模拟真实恢复流程结束。
         *
         * @param sessionId 会话 ID
         * @param restoreSource 恢复来源
         * @param transitionToken 前端切换令牌
         */
        private void finishForTest(String sessionId, String restoreSource, String transitionToken) {
            finishHistoryRestoreRequest(buildHistoryRestoreRequestKey(sessionId, restoreSource, transitionToken));
        }
    }

    /**
     * 最小 SessionHost 测试桩。
     * 当前测试只验证幂等 key 状态机，因此只提供 SessionLifecycleManager 构造所需的最小依赖。
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
     * 创建最小 Project 代理，满足 SessionLifecycleManager 初始化时的非空依赖。
     *
     * @return 可用于测试的 Project 代理
     */
    private static Project createProject() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "getBasePath" -> System.getProperty("java.io.tmpdir");
                    case "getName" -> "session-lifecycle-history-restore-idempotency-test";
                    case "toString" -> "session-lifecycle-history-restore-idempotency-test";
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
     *
     * @param primitiveType 原始类型
     * @return 对应默认值
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
