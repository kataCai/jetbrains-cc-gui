package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.session.SessionLifecycleManager.SessionHost;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * SessionLifecycleManager 历史标题回放测试。
 * 该测试聚焦“历史会话恢复后，Java 必须按两参形式回放标题到前端”这一行为契约，
 * 以防止前端回调已升级为 `(sessionId, title)` 后，Java 仍然沿用旧的一参调用导致标题被静默忽略。
 */
public class SessionLifecycleManagerHistoryTitleReplayTest {

    /**
     * 验证 replayRestoredSessionTitle() 会调用 updateSessionTitle(sessionId, title)。
     *
     * @throws Exception 反射调用失败时抛出异常
     */
    @Test
    public void shouldReplayRestoredTitleWithSessionIdAndTitleArguments() throws Exception {
        RecordingHost host = new RecordingHost();
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(
                host,
                "{\"session-123\":{\"customTitle\":\"同步github主线修改\"}}"
        );

        Method replayMethod = SessionLifecycleManager.class.getDeclaredMethod("replayRestoredSessionTitle", String.class);
        replayMethod.setAccessible(true);
        replayMethod.invoke(manager, "session-123");

        assertEquals(1, host.javascriptCalls.size());
        RecordingHost.JavaScriptCall call = host.javascriptCalls.get(0);
        assertEquals("updateSessionTitle", call.functionName);
        assertEquals(2, call.arguments.size());
        assertEquals("session-123", call.arguments.get(0));
        assertEquals("同步github主线修改", call.arguments.get(1));
    }

    /**
     * 为 SessionLifecycleManager 提供可控 titles JSON 的测试子类。
     * 该实现只覆写 titles 读取入口，不改变生产代码的其余行为。
     */
    private static final class TestableSessionLifecycleManager extends SessionLifecycleManager {
        private final String titlesJson;

        /**
         * 构造一个可注入 titles JSON 的测试实例。
         *
         * @param host SessionHost 测试桩
         * @param titlesJson 用于模拟持久化标题文件的 JSON 文本
         */
        private TestableSessionLifecycleManager(SessionHost host, String titlesJson) {
            super(host);
            this.titlesJson = titlesJson;
        }

        /**
         * 返回测试注入的 titles JSON，避免单元测试依赖真实 Node 子进程。
         *
         * @return 测试注入的 titles JSON 文本
         */
        @Override
        protected String loadPersistedSessionTitlesJson() {
            return titlesJson;
        }
    }

    /**
     * 记录 JavaScript 调用的最小 SessionHost 测试桩。
     * 仅实现本测试真正会触达的方法，避免引入无关环境依赖。
     */
    private static final class RecordingHost implements SessionHost {
        private final Project project = createProject();
        private final ClaudeSession session = new ClaudeSession(project, null, null);
        private final HandlerContext handlerContext = new HandlerContext(
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
        private final List<JavaScriptCall> javascriptCalls = new ArrayList<>();

        private RecordingHost() {
            handlerContext.setSession(session);
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
            javascriptCalls.add(new JavaScriptCall(functionName, List.of(args)));
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

        /**
         * 保存一次 JavaScript 调用快照，便于断言函数名与参数列表。
         */
        private record JavaScriptCall(String functionName, List<String> arguments) {
        }
    }

    /**
     * 创建一个最小 Project 代理，满足被测逻辑的非空依赖约束。
     *
     * @return Project 动态代理
     */
    private static Project createProject() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "getBasePath" -> System.getProperty("java.io.tmpdir");
                    case "getName" -> "session-lifecycle-history-title-replay-test";
                    case "toString" -> "session-lifecycle-history-title-replay-test";
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
     * @return 该原始类型的默认值
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
