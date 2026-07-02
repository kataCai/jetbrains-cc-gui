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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, expectedBinding);
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
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);
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
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);
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
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);
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
     * 验证继续分段时会在同一逻辑会话下创建新的分段记录，并更新最新分段指针。
     * 该用例覆盖本次改造最关键的生命周期语义：切模型/切供应商不再创建孤立新会话，而是继续同一逻辑会话。
     */
    @Test
    public void shouldCreateContinuedSegmentWithinSameLogicalConversation() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        InMemorySettingsService settingsService = new InMemorySettingsService();
        long now = 1_717_000_000_000L;

        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-001",
                "session-001",
                "session-001",
                "原始任务",
                "codex",
                "codex",
                "gpt-5.4",
                1,
                now,
                now,
                false,
                0L
        ));
        settingsService.saveConversationSegmentRecordUnchecked(new ConversationSegmentRecord(
                "session-001",
                "logical-001",
                "",
                0,
                "codex",
                "codex",
                "gpt-5.4",
                "medium",
                "new_session",
                "none",
                now
        ));

        ClaudeSession oldSession = new ClaudeSession(project, null, null);
        oldSession.setSessionInfo("session-001", System.getProperty("java.io.tmpdir"));
        oldSession.setProvider("codex");
        oldSession.setModel("gpt-5.4");
        oldSession.setReasoningEffort("medium");
        oldSession.getState().setCodexSessionBinding(new CodexSessionBinding(
                "codex-cli-login",
                "gpt-5.4",
                "codex_sdk",
                "codex_cli_login",
                "codex_cli_login"
        ));
        host.setSession(oldSession);
        host.getHandlerContext().setSession(oldSession);

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, settingsService, null);

        manager.completeContinuedSegmentForTest(
                "session-001",
                "session-002",
                "managed-buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "model"
        );

        LogicalConversationRecord logicalRecord = settingsService.getLogicalConversationRecordUnchecked("logical-001");
        assertNotNull(logicalRecord);
        assertEquals("logical-001", logicalRecord.getLogicalConversationId());
        assertEquals("session-001", logicalRecord.getRootSessionId());
        assertEquals("session-002", logicalRecord.getLatestSessionId());
        assertEquals(2, logicalRecord.getSegmentCount());
        assertEquals("gpt-5.4", logicalRecord.getLastModel());

        ConversationSegmentRecord segmentRecord = settingsService.getConversationSegmentRecordUnchecked("session-002");
        assertNotNull(segmentRecord);
        assertEquals("logical-001", segmentRecord.getLogicalConversationId());
        assertEquals("session-001", segmentRecord.getParentSessionId());
        assertEquals(1, segmentRecord.getSegmentIndex());
        assertEquals("codex", segmentRecord.getProvider());
        assertEquals("gpt-5.4", segmentRecord.getModel());
        assertEquals("medium", segmentRecord.getReasoningEffort());
        assertEquals("runtime_switch:model", segmentRecord.getCreatedBy());
        assertEquals("session_summary", segmentRecord.getCarryoverMode());

        assertEquals("logical-001", host.getSession().getState().getLogicalConversationId());
        assertEquals("session-002", host.getSession().getState().getActiveSegmentSessionId());
        assertFalse(host.getSession().getState().isContinuationPending());
    }

    /**
     * 验证 legacy 旧会话在首次继续时，会自动为源分段补齐 segmentIndex=0 的分段索引。
     * 该用例直接约束“旧会话原本没有 conversation segment 元数据时，历史聚合不能只记录新分段”的修复语义。
     */
    @Test
    public void shouldBackfillSourceSegmentMetadataWhenContinuingLegacySessionFirstTime() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        InMemorySettingsService settingsService = new InMemorySettingsService();
        long now = 1_717_000_100_000L;

        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-legacy-001",
                "legacy-session-001",
                "legacy-session-001",
                "Legacy Root",
                "codex",
                "codex",
                "gpt-5.4",
                1,
                now,
                now,
                false,
                0L
        ));

        ClaudeSession oldSession = new ClaudeSession(project, null, null);
        oldSession.setSessionInfo("legacy-session-001", System.getProperty("java.io.tmpdir"));
        oldSession.setProvider("codex");
        oldSession.setModel("gpt-5.4");
        oldSession.setReasoningEffort("medium");
        oldSession.getState().setSummary("继续沿用旧会话的上下文摘要");
        oldSession.getState().setLogicalConversationId("logical-legacy-001");
        oldSession.getState().setCodexSessionBinding(new CodexSessionBinding(
                "codex-cli-login",
                "gpt-5.4",
                "codex_sdk",
                "codex_cli_login",
                "codex_cli_login"
        ));
        host.setSession(oldSession);
        host.getHandlerContext().setSession(oldSession);

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, settingsService, null);

        manager.completeContinuedSegmentForTest(
                "legacy-session-001",
                "continued-session-002",
                "managed-buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "model"
        );

        ConversationSegmentRecord sourceSegment = settingsService.getConversationSegmentRecordUnchecked("legacy-session-001");
        assertNotNull(sourceSegment);
        assertEquals("logical-legacy-001", sourceSegment.getLogicalConversationId());
        assertEquals(0, sourceSegment.getSegmentIndex());
        assertEquals("", sourceSegment.getParentSessionId());
        assertEquals("codex", sourceSegment.getProvider());
        assertEquals("gpt-5.4", sourceSegment.getModel());
        assertEquals("medium", sourceSegment.getReasoningEffort());

        ConversationSegmentRecord newSegment = settingsService.getConversationSegmentRecordUnchecked("continued-session-002");
        assertNotNull(newSegment);
        assertEquals(1, newSegment.getSegmentIndex());

        LogicalConversationRecord logicalRecord = settingsService.getLogicalConversationRecordUnchecked("logical-legacy-001");
        assertNotNull(logicalRecord);
        assertEquals("legacy-session-001", logicalRecord.getRootSessionId());
        assertEquals("continued-session-002", logicalRecord.getLatestSessionId());
        assertEquals(2, logicalRecord.getSegmentCount());
    }

    /**
     * 验证首次继续 legacy 会话时，源分段回填记录必须保留旧分段自己的运行时信息，
     * 不能被新分段目标 provider/model 覆盖。
     */
    @Test
    public void shouldBackfillSourceSegmentUsingSourceRuntimeMetadataInsteadOfTargetRuntime() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        InMemorySettingsService settingsService = new InMemorySettingsService();
        long now = 1_717_000_200_000L;

        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-cross-provider-001",
                "legacy-session-claude-001",
                "legacy-session-claude-001",
                "Legacy Claude Root",
                "claude",
                "claude",
                "claude-sonnet-4-6",
                1,
                now,
                now,
                false,
                0L
        ));

        ClaudeSession oldSession = new ClaudeSession(project, null, null);
        oldSession.setSessionInfo("legacy-session-claude-001", System.getProperty("java.io.tmpdir"));
        oldSession.setProvider("claude");
        oldSession.setModel("claude-sonnet-4-6");
        oldSession.setReasoningEffort("");
        oldSession.getState().setSummary("legacy claude summary");
        oldSession.getState().setLogicalConversationId("logical-cross-provider-001");
        host.setSession(oldSession);
        host.getHandlerContext().setSession(oldSession);

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, settingsService, null);

        manager.completeContinuedSegmentForTest(
                "legacy-session-claude-001",
                "continued-session-codex-002",
                "managed-buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "provider"
        );

        ConversationSegmentRecord sourceSegment = settingsService.getConversationSegmentRecordUnchecked("legacy-session-claude-001");
        assertNotNull(sourceSegment);
        assertEquals("claude", sourceSegment.getProvider());
        assertEquals("claude", sourceSegment.getRuntimeFamily());
        assertEquals("claude-sonnet-4-6", sourceSegment.getModel());
        assertEquals("", sourceSegment.getReasoningEffort());

        ConversationSegmentRecord newSegment = settingsService.getConversationSegmentRecordUnchecked("continued-session-codex-002");
        assertNotNull(newSegment);
        assertEquals("codex", newSegment.getProvider());
        assertEquals("codex", newSegment.getRuntimeFamily());
        assertEquals("gpt-5.4", newSegment.getModel());
        assertEquals("medium", newSegment.getReasoningEffort());
    }

    /**
     * 验证逻辑会话元数据删除时，需要把主记录与全部分段索引一起清理。
     * 该用例约束后续历史删除链路不能只删 latestSessionId 对应的单条分段，否则历史聚合会留下悬空索引。
     */
    @Test
    public void shouldDeleteLogicalConversationAndAllSegmentRecordsTogether() {
        InMemorySettingsService settingsService = new InMemorySettingsService();
        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-001",
                "session-001",
                "session-002",
                "原始任务",
                "codex",
                "codex",
                "gpt-5.4",
                2,
                1L,
                2L,
                false,
                0L
        ));
        settingsService.saveConversationSegmentRecordUnchecked(new ConversationSegmentRecord(
                "session-001",
                "logical-001",
                "",
                0,
                "codex-cli-login",
                "codex",
                "gpt-5.4",
                "medium",
                "initial",
                "none",
                1L
        ));
        settingsService.saveConversationSegmentRecordUnchecked(new ConversationSegmentRecord(
                "session-002",
                "logical-001",
                "session-001",
                1,
                "buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "runtime_switch:provider",
                "session_summary",
                2L
        ));

        settingsService.deleteLogicalConversationCascade("logical-001");

        assertNull(settingsService.getLogicalConversationRecordUnchecked("logical-001"));
        assertNull(settingsService.getConversationSegmentRecordUnchecked("session-001"));
        assertNull(settingsService.getConversationSegmentRecordUnchecked("session-002"));
    }

    /**
     * 测试专用 SessionLifecycleManager，通过覆写配置服务入口稳定注入指定绑定。
     */
    private static final class TestableSessionLifecycleManager extends SessionLifecycleManager {
        private final InMemorySettingsService inMemorySettingsService;
        private final CodexSessionBinding binding;

        private TestableSessionLifecycleManager(
                SessionHost host,
                InMemorySettingsService inMemorySettingsService,
                CodexSessionBinding binding
        ) {
            super(host);
            this.inMemorySettingsService = inMemorySettingsService;
            this.binding = binding;
        }

        @Override
        protected CodemossSettingsService createSettingsService() {
            if (inMemorySettingsService != null) {
                return inMemorySettingsService;
            }
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

        /**
         * 暴露给测试的继续分段收口入口。
         * 该入口直接模拟新分段已经拿到真实 sessionId 后的元数据补齐动作，避免单元测试依赖完整 SDK 发送链路。
         *
         * @param sourceSessionId 原分段 sessionId
         * @param newSessionId 新分段 sessionId
         * @param targetCodexProviderId 新分段绑定的 Codex provider id
         * @param targetProvider 新分段 provider
         * @param targetModel 新分段 model
         * @param targetReasoningEffort 新分段 reasoning
         * @param switchReason 切换原因
         */
        private void completeContinuedSegmentForTest(
                String sourceSessionId,
                String newSessionId,
                String targetCodexProviderId,
                String targetProvider,
                String targetModel,
                String targetReasoningEffort,
                String switchReason
        ) {
            completeContinuedSegment(
                    sourceSessionId,
                    newSessionId,
                    targetCodexProviderId,
                    targetProvider,
                    "codex",
                    targetModel,
                    targetReasoningEffort,
                    switchReason
            );
        }
    }

    /**
     * 内存版设置服务测试桩。
     * 该实现只覆盖本轮继续分段测试所需的逻辑会话/分段索引读写，避免触达真实磁盘配置。
     */
    private static final class InMemorySettingsService extends CodemossSettingsService {
        private final java.util.Map<String, LogicalConversationRecord> logicalConversationRecords = new java.util.HashMap<>();
        private final java.util.Map<String, ConversationSegmentRecord> conversationSegmentRecords = new java.util.HashMap<>();

        @Override
        public void saveLogicalConversationRecord(LogicalConversationRecord record) {
            saveLogicalConversationRecordUnchecked(record);
        }

        @Override
        public LogicalConversationRecord getLogicalConversationRecord(String logicalConversationId) {
            return getLogicalConversationRecordUnchecked(logicalConversationId);
        }

        @Override
        public void saveConversationSegmentRecord(ConversationSegmentRecord record) {
            saveConversationSegmentRecordUnchecked(record);
        }

        @Override
        public ConversationSegmentRecord getConversationSegmentRecord(String sessionId) {
            return getConversationSegmentRecordUnchecked(sessionId);
        }

        @Override
        public java.util.List<ConversationSegmentRecord> listConversationSegments(String logicalConversationId) {
            java.util.List<ConversationSegmentRecord> records = new java.util.ArrayList<>();
            for (ConversationSegmentRecord record : conversationSegmentRecords.values()) {
                if (logicalConversationId.equals(record.getLogicalConversationId())) {
                    records.add(record);
                }
            }
            records.sort(java.util.Comparator.comparingInt(ConversationSegmentRecord::getSegmentIndex));
            return records;
        }

        private void saveLogicalConversationRecordUnchecked(LogicalConversationRecord record) {
            logicalConversationRecords.put(record.getLogicalConversationId(), record);
        }

        private LogicalConversationRecord getLogicalConversationRecordUnchecked(String logicalConversationId) {
            return logicalConversationRecords.get(logicalConversationId);
        }

        private void saveConversationSegmentRecordUnchecked(ConversationSegmentRecord record) {
            conversationSegmentRecords.put(record.getSessionId(), record);
        }

        private ConversationSegmentRecord getConversationSegmentRecordUnchecked(String sessionId) {
            return conversationSegmentRecords.get(sessionId);
        }

        /**
         * 按逻辑会话级联删除主记录与全部分段索引。
         * 该方法用于模拟历史删除链路的目标语义，确保测试可以直接约束“整条逻辑会话删除”后的元数据清理结果。
         *
         * @param logicalConversationId 目标逻辑会话 id
         */
        @Override
        public void deleteLogicalConversationCascade(String logicalConversationId) {
            logicalConversationRecords.remove(logicalConversationId);
            java.util.List<String> sessionIdsToDelete = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, ConversationSegmentRecord> entry : conversationSegmentRecords.entrySet()) {
                if (logicalConversationId.equals(entry.getValue().getLogicalConversationId())) {
                    sessionIdsToDelete.add(entry.getKey());
                }
            }
            for (String sessionId : sessionIdsToDelete) {
                conversationSegmentRecords.remove(sessionId);
            }
        }
    }

    /**
     * 最小 SessionHost 测试桩，只提供被测逻辑必需依赖。
     */
    private static final class RecordingHost implements SessionLifecycleManager.SessionHost {
        private final Project project;
        private final HandlerContext handlerContext;
        private final java.util.List<JavaScriptCall> javaScriptCalls = new java.util.ArrayList<>();
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
            javaScriptCalls.add(new JavaScriptCall(functionName, args));
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
     * 记录 JavaScript 回调调用，供生命周期测试验证前端 bridge 是否被触发。
     */
    private static final class JavaScriptCall {
        private final String functionName;
        private final String[] args;

        private JavaScriptCall(String functionName, String... args) {
            this.functionName = functionName;
            this.args = args;
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
