package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.jcef.JBCefBrowser;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * SessionLifecycleManager 发送时静默 runtime 切段回归测试。
 * 这组测试约束本次后端改造最关键的两类语义：
 * 1. 发送前判断 runtime diff 时，允许在“空白新会话”场景原地更新 runtime；
 * 2. 发送前静默切段成功后，sessionId 收口仍会补齐 continued 元数据，但不会再走旧的显式 ready/toast 提示链路。
 */
public class SessionLifecycleManagerSendTimeRuntimeSwitchTest {

    /**
     * 验证当 runtimeIntent 与当前活动会话没有差异时，prepareSessionForSend 直接复用当前会话。
     *
     * @throws Exception 当临时目录或上下文初始化失败时抛出
     */
    @Test
    public void shouldReuseCurrentSessionWhenRuntimeIntentMatchesActiveRuntime() throws Exception {
        Path projectDir = Files.createTempDirectory("session-lifecycle-send-time-reuse-test");
        RecordingHost host = new RecordingHost(createProject(projectDir), projectDir);
        ClaudeSession currentSession = host.getSession();
        currentSession.setSessionInfo("session-current", projectDir.toString());
        currentSession.setProvider("codex");
        currentSession.setModel("gpt-5.4-mini");
        currentSession.setReasoningEffort("medium");
        currentSession.getState().setCodexSessionBinding(new CodexSessionBinding(
                "BuyCode-Plus",
                "gpt-5.4-mini",
                "codex_sdk",
                "provider",
                "managed_provider"
        ));
        currentSession.getState().setLogicalConversationId("logical-001");
        currentSession.getState().setActiveSegmentSessionId("session-current");

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host);
        SendRuntimeIntent runtimeIntent = new SendRuntimeIntent(
                "chat",
                "dynamic_at_execution",
                "codex",
                "codex",
                "gpt-5.4-mini",
                "medium",
                "BuyCode-Plus",
                ""
        );

        ClaudeSession preparedSession = manager.prepareSessionForSend(runtimeIntent).get();

        assertSame("无 runtime diff 时应直接复用当前活动会话", currentSession, preparedSession);
        assertFalse("复用当前会话时不应留下 send-time pending 标记", currentSession.getState().isSendTimeRuntimeSwitchPending());
    }

    /**
     * 验证当 Codex 发送目标缺失 targetCodexProviderId 时，发送前准备流程会直接失败。
     * 这个用例覆盖本次“模型切换后始终续接到 Codex CLI Login / gpt-5.4-mini”的根因：
     * 如果继续容忍空 providerId 参与 diff 判断，后端就会把缺字段误算成 codex_provider，
     * 进而在每次发送时静默创建新的 continued segment。
     *
     * @throws Exception 当临时目录或上下文初始化失败时抛出
     */
    @Test
    public void shouldFailFastWhenCodexRuntimeIntentMissesTargetProviderId() throws Exception {
        Path projectDir = Files.createTempDirectory("session-lifecycle-send-time-missing-provider-test");
        RecordingHost host = new RecordingHost(createProject(projectDir), projectDir);
        ClaudeSession currentSession = host.getSession();
        currentSession.setSessionInfo("session-current", projectDir.toString());
        currentSession.setProvider("codex");
        currentSession.setModel("gpt-5.4-mini");
        currentSession.setReasoningEffort("medium");
        currentSession.getState().setCodexSessionBinding(new CodexSessionBinding(
                "BuyCode-Plus",
                "gpt-5.4-mini",
                "codex_sdk",
                "provider",
                "managed_provider"
        ));
        currentSession.getState().setLogicalConversationId("logical-001");
        currentSession.getState().setActiveSegmentSessionId("session-current");

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host);
        SendRuntimeIntent runtimeIntent = new SendRuntimeIntent(
                "chat",
                "dynamic_at_execution",
                "codex",
                "codex",
                "gpt-5.4-mini",
                "medium",
                "",
                ""
        );

        /**
         * 中文注释：
         * 这里要求明确抛错，而不是静默退回当前 binding。
         * 一旦静默回退，前端真正的 provider 丢失问题就会被掩盖，最终表现为“看似切换成功，实际始终在旧 provider 上发送”。
         */
        ExecutionException error = assertThrows(
                "缺失 targetCodexProviderId 时应显式终止发送前准备，避免继续误判 codex_provider",
                ExecutionException.class,
                () -> manager.prepareSessionForSend(runtimeIntent).get()
        );

        assertTrue("异常根因必须是参数校验失败，而不是后续 continued 创建过程中的次生错误",
                error.getCause() instanceof IllegalArgumentException);
        assertSame("失败后必须保持当前活动会话不变，不能提前挂载新的 prepared session", currentSession, host.getSession());
        assertFalse("失败后不应向前端误发 continued 完成信号", host.hasJavaScriptCall("window.completeContinuedSegmentTransition"));
    }

    /**
     * 验证发送时静默切段在拿到真实 sessionId 后，只做无感收口：
     * 仍同步 continued 完成元数据给前端，但跳过旧的 historyLoadComplete 和 ready toast。
     *
     * @throws Exception 当临时目录或上下文初始化失败时抛出
     */
    @Test
    public void shouldSkipLegacyReadySignalsWhenSendTimeRuntimeSwitchSessionIdAssigned() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-lifecycle-send-time-assigned-test");
        try {
            RecordingHost host = new RecordingHost(createProject(projectDir), projectDir);
            ClaudeSession currentSession = host.getSession();
            currentSession.setSessionInfo("session-source", projectDir.toString());
            currentSession.setProvider("codex");
            currentSession.setModel("gpt-5.4-mini");
            currentSession.setReasoningEffort("medium");
            currentSession.getState().setLogicalConversationId("logical-001");
            currentSession.getState().setActiveSegmentSessionId("session-source");
            currentSession.getState().setCodexSessionBinding(new CodexSessionBinding(
                    "BuyCode-Plus",
                    "gpt-5.4-mini",
                    "codex_sdk",
                    "provider",
                    "managed_provider"
            ));

            TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host);
            SendRuntimeIntent runtimeIntent = new SendRuntimeIntent(
                    "chat",
                    "dynamic_at_execution",
                    "codex",
                    "codex",
                    "gpt-5.4",
                    "high",
                    "BuyCode-Pro",
                    ""
            );

            ClaudeSession preparedSession = manager.prepareSessionForSend(runtimeIntent).get();
            assertTrue("静默切段成功后应返回新的 prepared session", preparedSession != currentSession);
            assertTrue("新 prepared session 在拿到真实 sessionId 前必须保持 continuationPending", preparedSession.getState().isContinuationPending());
            assertTrue("静默切段成功后必须显式标记 send-time pending，供收口阶段区分显式 continued", preparedSession.getState().isSendTimeRuntimeSwitchPending());

            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
                testDisposable = null;
            }
            manager.onSessionIdAssigned("session-new");

            assertTrue("静默切段成功后仍应同步 continued 完成元数据给前端", host.hasJavaScriptCall("window.completeContinuedSegmentTransition"));
            assertFalse("静默切段不应再走旧的 historyLoadComplete ready guard 释放链路", host.hasJavaScriptCall("historyLoadComplete"));
            assertFalse("静默切段不应再触发旧的 continued ready toast", host.hasJavaScriptCall("updateStatus"));
            assertFalse("sessionId 收口完成后必须清空 continuationPending", preparedSession.getState().isContinuationPending());
            assertFalse("sessionId 收口完成后必须清空 send-time pending 标记", preparedSession.getState().isSendTimeRuntimeSwitchPending());
            assertEquals("session-new", preparedSession.getState().getActiveSegmentSessionId());
            assertEquals("session-source", preparedSession.getState().getParentSegmentSessionId());
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    /**
     * 针对 SessionLifecycleManager 的可控测试子类。
     * 通过覆盖历史读取、Slash Commands 和设置服务入口，隔离真实文件系统和 UI 副作用，
     * 让测试只关注 send-time runtime switch 的会话/回调语义。
     */
    private static final class TestableSessionLifecycleManager extends SessionLifecycleManager {
        private final CodemossSettingsService settingsService = new InMemorySettingsService();

        /**
         * 构造测试用生命周期管理器。
         *
         * @param host 当前测试 host
         */
        private TestableSessionLifecycleManager(SessionHost host) {
            super(host);
        }

        /**
         * 使用内存版设置服务，避免测试写入真实插件配置。
         *
         * @return 仅覆盖当前测试所需元数据接口的设置服务桩
         */
        @Override
        protected CodemossSettingsService createSettingsService() {
            return settingsService;
        }

        /**
         * 返回稳定的“新分段已有可见消息”结果，避免 continued 收口路径进入延迟重试分支。
         *
         * @param sessionId 待读取的活动分段 sessionId
         * @return 最小可见 user 消息列表
         */
        @Override
        protected List<JsonObject> loadVisibleFrontendMessagesFromSession(String sessionId) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "user");
            message.addProperty("content", "hello");
            return Collections.singletonList(message);
        }

        /**
         * 返回稳定的逻辑会话聚合快照，确保 continued 收口时能进入 authoritative restore 分支。
         *
         * @param requestedSessionId 本轮回刷的目标 sessionId
         * @param logicalConversationId 逻辑会话 id
         * @param activeSegmentSessionId 当前活动分段 id
         * @return 最小但可用的前端消息批次
         */
        @Override
        protected List<JsonObject> buildContinuedLogicalConversationFrontendMessages(
                String requestedSessionId,
                String logicalConversationId,
                String activeSegmentSessionId
        ) {
            JsonObject message = new JsonObject();
            message.addProperty("type", "assistant");
            message.addProperty("content", "done");
            return Collections.singletonList(message);
        }

        /**
         * 测试不关心 Slash Commands 刷新，因此直接禁用该副作用。
         */
        @Override
        public void fetchSlashCommandsOnStartup() {
        }
    }

    /**
     * 使用内存结构承接 continued 元数据持久化的最小设置服务桩。
     */
    private static final class InMemorySettingsService extends CodemossSettingsService {
        private final List<ConversationSegmentRecord> segmentRecords = new ArrayList<>();
        private final java.util.Map<String, LogicalConversationRecord> logicalRecords = new java.util.HashMap<>();

        /**
         * 返回指定逻辑会话下当前已记录的分段列表。
         *
         * @param logicalConversationId 逻辑会话 id
         * @return 仅包含匹配逻辑会话的分段记录
         */
        @Override
        public List<ConversationSegmentRecord> listConversationSegments(String logicalConversationId) {
            List<ConversationSegmentRecord> results = new ArrayList<>();
            for (ConversationSegmentRecord record : segmentRecords) {
                if (record != null && logicalConversationId != null
                        && logicalConversationId.equals(record.getLogicalConversationId())) {
                    results.add(record);
                }
            }
            return results;
        }

        /**
         * 记录分段元数据到内存，供同一测试链路后续查询。
         *
         * @param record 待保存的分段记录
         */
        @Override
        public void saveConversationSegmentRecord(ConversationSegmentRecord record) {
            if (record != null) {
                segmentRecords.add(record);
            }
        }

        /**
         * 按逻辑会话 id 返回内存中的逻辑会话记录。
         *
         * @param logicalConversationId 逻辑会话 id
         * @return 匹配的逻辑会话记录；不存在时返回 null
         */
        @Override
        public LogicalConversationRecord getLogicalConversationRecord(String logicalConversationId) {
            return logicalRecords.get(logicalConversationId);
        }

        /**
         * 保存逻辑会话记录到内存。
         *
         * @param record 待保存的逻辑会话记录
         */
        @Override
        public void saveLogicalConversationRecord(LogicalConversationRecord record) {
            if (record != null) {
                logicalRecords.put(record.getLogicalConversationId(), record);
            }
        }

        /**
         * 当前测试不依赖按 sessionId 追溯分段记录，因此始终返回 null。
         *
         * @param sessionId 分段 sessionId
         * @return 恒为 null
         * @throws IOException 为保持与父类签名一致而保留
         */
        @Override
        public ConversationSegmentRecord getConversationSegmentRecord(String sessionId) throws IOException {
            return null;
        }
    }

    /**
     * 记录 SessionLifecycleManager 与前端、上下文交互的最小 host 测试桩。
     */
    private static final class RecordingHost implements SessionLifecycleManager.SessionHost {
        private final Project project;
        private final HandlerContext handlerContext;
        private final List<String> javaScriptFunctions = new ArrayList<>();
        private ClaudeSession session;

        /**
         * 构造测试 host，并创建默认活动会话与 HandlerContext。
         *
         * @param project 当前测试项目
         * @param projectDir 当前测试项目目录
         */
        private RecordingHost(Project project, Path projectDir) {
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
                            javaScriptFunctions.add(functionName);
                        }

                        @Override
                        public String escapeJs(String str) {
                            return str;
                        }
                    }
            );
            this.session.setSessionInfo(null, projectDir.toString());
            this.handlerContext.setSession(this.session);
            this.handlerContext.setCurrentProvider("claude");
            this.handlerContext.setCurrentModel("claude-sonnet-4-6");
        }

        /**
         * 判断某个前端回调函数名是否被调用过。
         *
         * @param functionName 目标函数名
         * @return true 表示调用历史中包含该函数
         */
        private boolean hasJavaScriptCall(String functionName) {
            return javaScriptFunctions.contains(functionName);
        }

        /**
         * 返回当前测试项目。
         *
         * @return 当前项目桩
         */
        @Override
        public Project getProject() {
            return project;
        }

        /**
         * 测试不依赖 Claude bridge，直接返回 null。
         *
         * @return null
         */
        @Override
        public com.github.claudecodegui.provider.claude.ClaudeSDKBridge getClaudeSDKBridge() {
            return null;
        }

        /**
         * 测试不依赖 Codex bridge，直接返回 null。
         *
         * @return null
         */
        @Override
        public com.github.claudecodegui.provider.codex.CodexSDKBridge getCodexSDKBridge() {
            return null;
        }

        /**
         * 返回当前挂载到 host 的活动会话。
         *
         * @return 当前活动会话
         */
        @Override
        public ClaudeSession getSession() {
            return session;
        }

        /**
         * 更新 host 当前活动会话，并同步给 HandlerContext。
         *
         * @param session 新活动会话
         */
        @Override
        public void setSession(ClaudeSession session) {
            this.session = session;
            this.handlerContext.setSession(session);
        }

        /**
         * 返回绑定在 host 上的 HandlerContext。
         *
         * @return 最小测试上下文
         */
        @Override
        public HandlerContext getHandlerContext() {
            return handlerContext;
        }

        /**
         * 测试不依赖流式合并器，直接返回 null。
         *
         * @return null
         */
        @Override
        public StreamMessageCoalescer getStreamCoalescer() {
            return null;
        }

        /**
         * 测试不维护待批权限请求，此处无副作用。
         */
        @Override
        public void clearPendingPermissionRequests() {
        }

        /**
         * 测试不维护权限决策记忆，此处无副作用。
         */
        @Override
        public void clearPermissionDecisionMemory() {
        }

        /**
         * 记录所有前端 JS 调用函数名，便于断言静默切段收口行为。
         *
         * @param functionName JS 函数名
         * @param args JS 参数
         */
        @Override
        public void callJavaScript(String functionName, String... args) {
            javaScriptFunctions.add(functionName);
        }

        /**
         * 测试 host 始终视为未释放。
         *
         * @return false
         */
        @Override
        public boolean isDisposed() {
            return false;
        }

        /**
         * 测试不依赖真实浏览器组件。
         *
         * @return null
         */
        @Override
        public JBCefBrowser getBrowser() {
            return null;
        }

        /**
         * 测试不注册真实 session callbacks。
         */
        @Override
        public void setupSessionCallbacks() {
        }

        /**
         * 测试不维护真实 session callbacks。
         */
        @Override
        public void invalidateSessionCallbacks() {
        }

        /**
         * 测试不关心 Slash Commands fetched 标记。
         *
         * @param fetched 是否已拉取
         */
        @Override
        public void setSlashCommandsFetched(boolean fetched) {
        }

        /**
         * 测试不关心 Slash Commands 数量。
         *
         * @param count 数量
         */
        @Override
        public void setFetchedSlashCommandsCount(int count) {
        }
    }

    /**
     * 构造最小 Project 代理，满足 Session/HandlerContext 初始化需求。
     *
     * @param projectDir 当前测试项目目录
     * @return Project 动态代理
     */
    private static Project createProject(Path projectDir) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "isOpen" -> true;
                    case "getBasePath" -> projectDir.toString();
                    case "getName" -> "session-lifecycle-send-time-runtime-switch-test";
                    case "toString" -> "session-lifecycle-send-time-runtime-switch-test";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType().isPrimitive()
                            ? defaultPrimitiveValue(method.getReturnType())
                            : null;
                }
        );
    }

    /**
     * 为 Project 动态代理补齐原始类型的默认返回值。
     *
     * @param primitiveType 原始类型
     * @return 对应类型的零值
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
