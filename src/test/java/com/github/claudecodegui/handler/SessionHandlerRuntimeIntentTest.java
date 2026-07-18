package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SendRuntimeIntent;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SessionHandler 发送时 runtimeIntent 解析与会话准备链路测试。
 * 这组测试聚焦本次改造新增的“发送前准备会话”职责边界，确保：
 * 1. 前端 payload 中的 runtimeIntent 会被后端稳定解析；
 * 2. 解析结果会原样传给发送前准备回调；
 * 3. 真正执行 send 的对象是准备回调返回的新会话，而不是旧的活动会话。
 */
public class SessionHandlerRuntimeIntentTest {

    /**
     * 验证普通 send_message 负载里的 runtimeIntent 会被正确解析并传给 preparation callback，
     * 同时本次发送会直接落在 callback 返回的 prepared session 上。
     *
     * @throws Exception 当临时目录、等待锁或测试桩初始化失败时抛出
     */
    @Test
    public void shouldParseRuntimeIntentAndSendWithPreparedSession() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-runtime-intent-test");
        try {
            RecordingClaudeSession currentSession = new RecordingClaudeSession(createProject(projectDir));
            currentSession.setSessionInfo("session-current", projectDir.toString());

            RecordingClaudeSession preparedSession = new RecordingClaudeSession(createProject(projectDir));
            preparedSession.setSessionInfo("session-prepared", projectDir.toString());

            HandlerContext context = createContext(projectDir, currentSession);
            AtomicReference<SendRuntimeIntent> capturedIntent = new AtomicReference<>();
            SessionHandler handler = new SessionHandler(
                    context,
                    null,
                    null,
                    null,
                    runtimeIntent -> {
                        capturedIntent.set(runtimeIntent);
                        return CompletableFuture.completedFuture(preparedSession);
                    }
            );

            boolean handled = handler.handle(
                    "send_message",
                    "{"
                            + "\"text\":\"1+1=\","
                            + "\"runtimeIntent\":{"
                            + "\"sourceKind\":\"chat\","
                            + "\"resolutionPolicy\":\"dynamic_at_execution\","
                            + "\"targetProvider\":\"codex\","
                            + "\"targetRuntimeFamily\":\"codex\","
                            + "\"targetModel\":\"gpt-5.4-mini\","
                            + "\"targetReasoningEffort\":\"medium\","
                            + "\"targetCodexProviderId\":\"BuyCode-Plus\""
                            + "}"
                            + "}"
            );

            assertTrue("send_message 应被 SessionHandler 接管处理", handled);
            assertTrue("prepared session 应在超时前收到真正的 send 调用", preparedSession.awaitSendCalled());

            SendRuntimeIntent runtimeIntent = capturedIntent.get();
            assertNotNull("发送前准备回调必须收到解析后的 runtimeIntent", runtimeIntent);
            assertEquals("chat", runtimeIntent.getSourceKind());
            assertEquals("dynamic_at_execution", runtimeIntent.getResolutionPolicy());
            assertEquals("codex", runtimeIntent.getTargetProvider());
            assertEquals("codex", runtimeIntent.resolveTargetRuntimeFamily());
            assertEquals("gpt-5.4-mini", runtimeIntent.getTargetModel());
            assertEquals("medium", runtimeIntent.getTargetReasoningEffort());
            assertEquals("BuyCode-Plus", runtimeIntent.getTargetCodexProviderId());
            assertEquals("1+1=", preparedSession.lastPlainPrompt);
            assertEquals("旧活动会话不应直接承担本次 send", null, currentSession.lastPlainPrompt);
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    /**
     * 验证当 payload 只声明 `targetModelTier` 而未给出具体模型时，
     * SessionHandler 会在进入发送链路前使用统一解析器补全稳定的 provider/model/reasoning，
     * 避免后续 SessionLifecycleManager 在切段中途再临时猜测目标 runtime。
     *
     * @throws Exception 当临时目录、等待锁或测试桩初始化失败时抛出
     */
    @Test
    public void shouldResolveCodexTargetModelTierBeforePreparation() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-runtime-tier-test");
        try {
            RecordingClaudeSession currentSession = new RecordingClaudeSession(createProject(projectDir));
            currentSession.setSessionInfo("session-current", projectDir.toString());

            RecordingClaudeSession preparedSession = new RecordingClaudeSession(createProject(projectDir));
            preparedSession.setSessionInfo("session-prepared", projectDir.toString());

            HandlerContext context = createContext(projectDir, currentSession);
            AtomicReference<SendRuntimeIntent> capturedIntent = new AtomicReference<>();
            SessionHandler handler = new SessionHandler(
                    context,
                    null,
                    null,
                    runtimeIntent -> {
                        capturedIntent.set(runtimeIntent);
                        return CompletableFuture.completedFuture(preparedSession);
                    }
            );

            boolean handled = handler.handle(
                    "send_message",
                    "{"
                            + "\"text\":\"tier locked task\","
                            + "\"runtimeIntent\":{"
                            + "\"sourceKind\":\"locked_task\","
                            + "\"resolutionPolicy\":\"locked_at_enqueue\","
                            + "\"targetProvider\":\"codex\","
                            + "\"targetRuntimeFamily\":\"codex\","
                            + "\"targetCodexProviderId\":\"BuyCode-Plus\","
                            + "\"targetModelTier\":\"advanced\","
                            + "\"lockedBy\":\"plan_subtask\""
                            + "}"
                            + "}"
            );

            assertTrue("send_message 应被 SessionHandler 接管处理", handled);
            assertTrue("prepared session 应在超时前收到真正的 send 调用", preparedSession.awaitSendCalled());

            SendRuntimeIntent runtimeIntent = capturedIntent.get();
            assertNotNull("发送前准备回调必须收到解析后的 runtimeIntent", runtimeIntent);
            assertEquals("locked_task", runtimeIntent.getSourceKind());
            assertEquals("locked_at_enqueue", runtimeIntent.getResolutionPolicy());
            assertEquals("advanced", runtimeIntent.getTargetModelTier());
            assertEquals("codex", runtimeIntent.getTargetProvider());
            assertEquals("codex", runtimeIntent.resolveTargetRuntimeFamily());
            assertEquals("gpt-5.4", runtimeIntent.getTargetModel());
            assertEquals("high", runtimeIntent.getTargetReasoningEffort());
            assertEquals("BuyCode-Plus", runtimeIntent.getTargetCodexProviderId());
            assertEquals("plan_subtask", runtimeIntent.getLockedBy());
            assertEquals("tier locked task", preparedSession.lastPlainPrompt);
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    /**
     * 验证发送前会话准备回调不会在 SessionHandler 构造阶段被提前触发。
     * 该断言用于锁住“发送期依赖只在真正 send 时才解析”的边界，避免后续再次把 runtime 切换逻辑
     * 错误前移到窗口构造或 handler 注册阶段。
     *
     * @throws Exception 当临时目录、等待锁或测试桩初始化失败时抛出
     */
    @Test
    public void shouldInvokeSessionPreparationOnlyWhenSendActuallyStarts() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-lazy-preparation-test");
        try {
            RecordingClaudeSession currentSession = new RecordingClaudeSession(createProject(projectDir));
            currentSession.setSessionInfo("session-current", projectDir.toString());

            RecordingClaudeSession preparedSession = new RecordingClaudeSession(createProject(projectDir));
            preparedSession.setSessionInfo("session-prepared", projectDir.toString());

            HandlerContext context = createContext(projectDir, currentSession);
            AtomicInteger prepareInvocationCount = new AtomicInteger();
            SessionHandler handler = new SessionHandler(
                    context,
                    null,
                    null,
                    runtimeIntent -> {
                        prepareInvocationCount.incrementAndGet();
                        return CompletableFuture.completedFuture(preparedSession);
                    }
            );

            assertEquals("SessionHandler 构造阶段不应提前触发发送前准备回调", 0, prepareInvocationCount.get());

            boolean handled = handler.handle(
                    "send_message",
                    "{"
                            + "\"text\":\"lazy runtime switch\""
                            + "}"
            );

            assertTrue("send_message 应被 SessionHandler 处理", handled);
            assertTrue("prepared session 应在超时前收到真正的 send 调用", preparedSession.awaitSendCalled());
            assertEquals("发送真正开始后应只触发一次发送前准备回调", 1, prepareInvocationCount.get());
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    /**
     * 创建带固定 Node 版本桥接的 HandlerContext，避免测试受本机 Node 探测状态影响。
     *
     * @param projectDir 当前测试使用的临时项目目录
     * @param session 初始挂载到 context 的活动会话
     * @return 可直接交给 SessionHandler 的最小上下文
     */
    private static HandlerContext createContext(Path projectDir, ClaudeSession session) {
        HandlerContext context = new HandlerContext(
                createProject(projectDir),
                new FixedNodeClaudeSDKBridge(),
                new CodexSDKBridge(),
                null,
                new NoopJsCallback()
        );
        context.setSession(session);
        return context;
    }

    /**
     * 构造最小 Project 代理，只提供 Session/Handler 初始化需要的基础属性。
     *
     * @param projectDir 临时项目目录
     * @return JDK 动态代理生成的 Project 测试桩
     */
    private static Project createProject(Path projectDir) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> projectDir.toString();
                    case "getName" -> "session-handler-runtime-intent-test";
                    case "isDisposed" -> false;
                    case "isOpen" -> true;
                    case "getDisposed" -> null;
                    case "toString" -> "session-handler-runtime-intent-test-project";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                }
        );
    }

    /**
     * 固定返回受支持的 Node 版本，保证测试稳定进入发送主链路。
     */
    private static final class FixedNodeClaudeSDKBridge extends ClaudeSDKBridge {

        /**
         * 返回稳定的缓存 Node 版本，避免测试依赖真实环境配置。
         *
         * @return 固定可用的 Node 版本字符串
         */
        @Override
        public String getCachedNodeVersion() {
            return "18.0.0";
        }
    }

    /**
     * 记录 send 是否真正落到当前会话上的测试会话桩。
     */
    private static final class RecordingClaudeSession extends ClaudeSession {
        private final CountDownLatch sendLatch = new CountDownLatch(1);
        private volatile String lastPlainPrompt;

        /**
         * 构造记录型测试会话。
         *
         * @param project 当前测试项目
         */
        private RecordingClaudeSession(Project project) {
            super(project, new FixedNodeClaudeSDKBridge(), new CodexSDKBridge());
        }

        /**
         * 记录普通文本 send 调用参数，并立即返回成功完成的 future。
         *
         * @param input 用户输入文本
         * @param agentPrompt 当前 agent prompt
         * @param fileTagPaths file tags
         * @param requestedPermissionMode 请求权限模式
         * @return 已完成的 future
         */
        @Override
        public CompletableFuture<Void> send(
                String input,
                String agentPrompt,
                List<String> fileTagPaths,
                String requestedPermissionMode
        ) {
            this.lastPlainPrompt = input;
            sendLatch.countDown();
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 等待当前测试会话收到 send 调用。
         *
         * @return true 表示在超时前收到了调用
         * @throws InterruptedException 等待过程中线程被中断时抛出
         */
        private boolean awaitSendCalled() throws InterruptedException {
            return sendLatch.await(5, TimeUnit.SECONDS);
        }
    }

    /**
     * 屏蔽前端 JS 回调副作用的最小空实现。
     */
    private static final class NoopJsCallback implements HandlerContext.JsCallback {

        /**
         * 忽略测试中的前端回调。
         *
         * @param functionName 回调函数名
         * @param args 回调参数
         */
        @Override
        public void callJavaScript(String functionName, String... args) {
        }

        /**
         * 直接返回原始字符串，满足 HandlerContext 最小契约。
         *
         * @param str 待转义字符串
         * @return 原始字符串
         */
        @Override
        public String escapeJs(String str) {
            return str;
        }
    }
}
