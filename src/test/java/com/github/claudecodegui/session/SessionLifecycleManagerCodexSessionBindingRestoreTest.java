package com.github.claudecodegui.session;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
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
     * 验证 continued 首发所需的 carryover 快照会优先取最近几轮用户/助手消息，
     * 避免多次切换模型后仍然沿用首轮摘要造成上下文回退。
     */
    @Test
    public void shouldBuildContinuationCarryoverTextFromLatestVisibleMessages() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);
        ClaudeSession sourceSession = new ClaudeSession(project, null, null);
        sourceSession.getState().setSummary("1+1=?");
        sourceSession.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "1+1=?"));
        sourceSession.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "2"));
        sourceSession.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "再+1=?"));
        sourceSession.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "3"));

        String carryoverText = manager.buildContinuationCarryoverTextForTest(sourceSession);

        assertTrue(carryoverText.contains("User: 再+1=?"));
        assertTrue(carryoverText.contains("Assistant: 3"));
        assertFalse("最近轮次快照不应回退成首轮摘要文本", "1+1=?".equals(carryoverText));
    }

    /**
     * 验证存在持久化绑定时，会话恢复流程会同步回填 provider、model 和 SessionState 绑定。
     */
    /**
     * 验证最近轮次快照构建时，会主动排除已经被历史恢复链路污染成可见用户消息的内部 continued 前缀。
     * 该测试覆盖“多次切模后 synthetic user message 被再次带入 carryoverPreview”的回归场景。
     */
    @Test
    public void shouldExcludeSyntheticContinuationUserMessagesFromCarryoverSnapshot() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);
        ClaudeSession sourceSession = new ClaudeSession(project, null, null);
        sourceSession.getState().setSummary("legacy summary");
        sourceSession.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "3"));
        sourceSession.getState().addMessage(new ClaudeSession.Message(
                ClaudeSession.Message.Type.USER,
                "## Conversation Continuation\n"
                        + "You are continuing an existing conversation in a new runtime segment.\n"
                        + "Logical conversation id: logical-001\n"
                        + "Previous segment session id: segment-001\n"
                        + "Recent conversation turns:\n"
                        + "User: 1+1=?\n"
                        + "Assistant: 2\n"
                        + "User: 再+1=?\n"
                        + "Assistant: 3\n"
                        + "Preserve the user's intent and continue from that context unless the latest request overrides it.\n\n"
                        + "再+1=?"
        ));
        sourceSession.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "4"));

        String carryoverText = manager.buildContinuationCarryoverTextForTest(sourceSession);

        assertFalse(carryoverText.contains("## Conversation Continuation"));
        assertFalse(carryoverText.contains("Recent conversation turns:"));
        assertTrue(carryoverText.contains("Assistant: 3"));
        assertTrue(carryoverText.contains("Assistant: 4"));
        assertFalse("污染消息不应继续作为下一次 carryover 的用户轮次参与拼接", carryoverText.contains("User: 再+1=?"));
    }

    /**
     * 验证当前台历史已经被 permissions instructions 与 skills 说明污染时，
     * carryover 快照只会提取清洗后的真实用户输入，而不会把内部运行环境说明再次带入下一轮请求。
     * 该测试覆盖“旧污染历史再次进入 carryoverPreview”这一回归边界。
     */
    @Test
    public void shouldSanitizePollutedVisibleHistoryBeforeReusingCarryoverSnapshot() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);
        ClaudeSession sourceSession = new ClaudeSession(project, null, null);
        sourceSession.getState().addMessage(new ClaudeSession.Message(
                ClaudeSession.Message.Type.USER,
                "<permissions instructions>\n"
                        + "Filesystem sandboxing defines which files can be read or written.\n"
                        + "</permissions instructions>\n\n"
                        + "## Skills\n\n"
                        + "### Skill roots\n\n"
                        + "- `r0` = `D:/Users/example/.agents/skills`\n\n"
                        + "### Available skills\n\n"
                        + "- `firecrawl-search`: Search the web. (file: r0/firecrawl-search/SKILL.md)\n\n"
                        + "### How to use skills\n\n"
                        + "1. Read the skill before doing work.\n\n"
                        + "按照计划继续改造当前工作区"
        ));
        sourceSession.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.ASSISTANT, "收到，继续改造。"));

        String carryoverText = manager.buildContinuationCarryoverTextForTest(sourceSession);

        assertTrue(carryoverText.contains("User: 按照计划继续改造当前工作区"));
        assertFalse(carryoverText.contains("permissions instructions"));
        assertFalse(carryoverText.contains("## Skills"));
        assertFalse(carryoverText.contains("### Skill roots"));
    }

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
        settingsService.saveCodexProviderUnchecked("managed-buycode", "BuyCode");
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
        oldSession.getState().setContinuationCarryoverText("User: 再+1=?\nAssistant: 4");
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
        assertEquals("managed-buycode", segmentRecord.getCodexProviderId());
        assertEquals("BuyCode", segmentRecord.getProviderDisplayName());
        assertEquals("runtime_switch:model", segmentRecord.getCreatedBy());
        assertEquals("recent_turns_snapshot", segmentRecord.getCarryoverMode());

        assertEquals("logical-001", host.getSession().getState().getLogicalConversationId());
        assertEquals("session-002", host.getSession().getState().getActiveSegmentSessionId());
        assertFalse(host.getSession().getState().isContinuationPending());
    }

    /**
     * 验证 continued 创建在后端初始化新 session 后失败时，会立即回滚前后端状态到旧会话。
     * 该用例约束两点：
     * 1. 插件侧当前 session 与 handlerContext 不能继续停留在失败的新 session 上；
     * 2. 前端必须收到显式 abort 信号，清理 continued pending/source/cache，避免标签页永久卡在 not ready。
     */
    @Test
    public void shouldRollbackFailedContinuedSessionCreationToPreviousSession() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);

        ClaudeSession oldSession = new ClaudeSession(project, null, null);
        oldSession.setSessionInfo("session-old-001", System.getProperty("java.io.tmpdir"));
        oldSession.setProvider("codex");
        oldSession.setModel("gpt-5.4");
        oldSession.getState().setLogicalConversationId("logical-001");
        oldSession.getState().setActiveSegmentSessionId("session-old-001");

        ClaudeSession failedSession = new ClaudeSession(project, null, null);
        failedSession.setProvider("codex");
        failedSession.setModel("gpt-5.4");
        failedSession.getState().setLogicalConversationId("logical-001");
        failedSession.getState().setContinuationPending(true);
        failedSession.getState().setContinuationSourceSessionId("session-old-001");

        host.setSession(failedSession);
        host.getHandlerContext().setSession(failedSession);
        host.clearJavaScriptCalls();

        manager.rollbackFailedContinuedSessionCreationForTest(
                oldSession,
                failedSession,
                "slash commands bootstrap failed"
        );

        assertEquals(oldSession, host.getSession());
        assertEquals(oldSession, host.getHandlerContext().getSession());
        assertTrue(host.hasJavaScriptCall("window.abortContinuedSegmentTransition", "session-old-001"));
        assertTrue(host.hasJavaScriptCall("historyLoadComplete"));
        assertTrue(host.hasJavaScriptCall(
                "updateStatus",
                "Failed to continue session: slash commands bootstrap failed"
        ));
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
     * 验证 continued segment 元数据收口后，后端必须主动触发一次逻辑会话聚合回刷。
     * 这个测试先只约束“要发生回刷动作”这一最小行为，避免 continued 完成后前端仍然停留在新物理 session 的局部快照。
     * 后续实现中可以继续收紧为断言具体的聚合消息顺序与边界提示内容。
     */
    @Test
    public void shouldRefreshLogicalConversationMessagesAfterContinuedSegmentCompleted() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        InMemorySettingsService settingsService = new InMemorySettingsService();
        long now = 1_717_000_300_000L;

        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-refresh-001",
                "session-001",
                "session-001",
                "继续会话",
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
                "logical-refresh-001",
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
        oldSession.getState().setLogicalConversationId("logical-refresh-001");
        oldSession.getState().setContinuationPending(true);
        oldSession.getState().setContinuationSourceSessionId("session-001");
        host.setSession(oldSession);
        host.getHandlerContext().setSession(oldSession);

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, settingsService, null);
        manager.setSessionMessagesJson(
                "session-002",
                "[{\"timestamp\":\"2026-07-03T00:00:00Z\",\"type\":\"event_msg\",\"payload\":{\"type\":\"user_message\",\"message\":\"继续\"}}]"
        );

        manager.completeContinuedSegmentForTest(
                "session-001",
                "session-002",
                "managed-buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "model"
        );

        assertTrue("continued 收口后必须先准备历史回刷快照",
                host.hasJavaScriptCall("prepareHistoryRestoreSnapshot"));
        assertTrue("continued authoritative 回刷必须显式携带 runtime_continue_authoritative restoreKind",
                host.hasJavaScriptCallArgument("prepareHistoryRestoreSnapshot", 2, "runtime_continue_authoritative"));
        assertTrue("continued 收口后必须清空旧视图，避免新快照与旧局部快照混杂",
                host.hasJavaScriptCall("clearMessages"));
        assertTrue("continued 收口后必须把聚合消息重新注入前端",
                host.hasJavaScriptCall("updateMessages"));
    }

    /**
     * 验证 continued 收口时，如果新分段历史里还没有任何可见的用户消息，
     * 后端不能立刻执行覆盖式 `clearMessages -> updateMessages` 回刷。
     * 这个用例直接覆盖“新 sessionId 已分配，但历史文件尚未落盘或只剩空壳”时，
     * 不完整聚合快照把前端 optimistic user message 覆盖掉的根因场景。
     */
    @Test
    public void shouldSkipImmediateLogicalConversationRefreshWhenActiveSegmentHasNoVisibleUserMessage() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        InMemorySettingsService settingsService = new InMemorySettingsService();
        long now = 1_717_000_350_000L;

        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-refresh-empty-001",
                "session-001",
                "session-001",
                "继续会话",
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
                "logical-refresh-empty-001",
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
        oldSession.getState().setLogicalConversationId("logical-refresh-empty-001");
        oldSession.getState().setContinuationPending(true);
        oldSession.getState().setContinuationSourceSessionId("session-001");
        host.setSession(oldSession);
        host.getHandlerContext().setSession(oldSession);

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, settingsService, null);
        manager.setSessionMessagesJson("session-002", "[]");

        manager.completeContinuedSegmentForTest(
                "session-001",
                "session-002",
                "managed-buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "model"
        );

        assertFalse("新分段尚无可见用户消息时，不应立刻准备覆盖式历史快照",
                host.hasJavaScriptCall("prepareHistoryRestoreSnapshot"));
        assertFalse("新分段尚无可见用户消息时，不应清空当前前端消息列表",
                host.hasJavaScriptCall("clearMessages"));
        assertFalse("新分段尚无可见用户消息时，不应注入不完整的聚合快照",
                host.hasJavaScriptCall("updateMessages"));
    }

    /**
     * 验证历史恢复任务一旦过期，就不能再把旧会话快照刷回当前窗口。
     * 这个用例直接覆盖 startup restore 与用户后续 createNewSession 并发时，
     * 晚到的旧历史 `clearMessages -> updateMessages` 把当前新会话结果覆盖掉的根因。
     */
    @Test
    public void shouldSkipPushingHistorySnapshotWhenRestoreSessionIsNoLongerActive() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, null, null);
        ClaudeSession staleRestoreSession = new ClaudeSession(project, null, null);
        ClaudeSession currentActiveSession = new ClaudeSession(project, null, null);
        host.setSession(currentActiveSession);

        JsonObject frontendMessage = new JsonObject();
        frontendMessage.addProperty("type", "assistant");
        frontendMessage.addProperty("content", "stale history");
        frontendMessage.addProperty("timestamp", "2026-07-06T15:18:38Z");

        manager.pushFrontendMessagesToFrontendIfSessionCurrentForTest(
                staleRestoreSession,
                java.util.List.of(frontendMessage),
                "session-restore|startup|transition-001",
                1,
                "single_session"
        );

        assertFalse("过期 restore 不应再准备历史恢复快照",
                host.hasJavaScriptCall("prepareHistoryRestoreSnapshot"));
        assertFalse("过期 restore 不应清空当前窗口消息",
                host.hasJavaScriptCall("clearMessages"));
        assertFalse("过期 restore 不应把旧快照重新刷回前端",
                host.hasJavaScriptCall("updateMessages"));

        host.clearJavaScriptCalls();
        manager.pushFrontendMessagesToFrontendIfSessionCurrentForTest(
                currentActiveSession,
                java.util.List.of(frontendMessage),
                "session-active|startup|transition-002",
                1,
                "single_session"
        );

        assertTrue("当前仍活跃的 restore 才允许准备历史恢复快照",
                host.hasJavaScriptCall("prepareHistoryRestoreSnapshot"));
        assertTrue("普通单会话 restore 必须继续显式下发 single_session restoreKind",
                host.hasJavaScriptCallArgument("prepareHistoryRestoreSnapshot", 2, "single_session"));
        assertTrue("当前仍活跃的 restore 才允许清空当前窗口消息",
                host.hasJavaScriptCall("clearMessages"));
        assertTrue("当前仍活跃的 restore 才允许把历史快照刷回前端",
                host.hasJavaScriptCall("updateMessages"));
    }

    /**
     * 验证 continued 分段在历史文件稍后可见时，仍然可以在流结束后的补偿阶段完成逻辑会话回刷。
     * 这个用例对应真实日志中的末轮缺结果场景：初次 continued 收口时历史不可见，
     * 但在 stream_end/send_complete 之后历史已完整落盘，此时必须还能补齐最终 assistant 结果。
     */
    @Test
    public void shouldRefreshContinuedLogicalConversationAfterHistoryBecomesVisibleLater() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        InMemorySettingsService settingsService = new InMemorySettingsService();
        long now = 1_717_000_500_000L;

        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-late-refresh-001",
                "session-001",
                "session-002",
                "继续会话",
                "codex",
                "codex",
                "gpt-5.4",
                2,
                now,
                now,
                false,
                0L
        ));
        settingsService.saveConversationSegmentRecordUnchecked(new ConversationSegmentRecord(
                "session-001",
                "logical-late-refresh-001",
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
        settingsService.saveConversationSegmentRecordUnchecked(new ConversationSegmentRecord(
                "session-002",
                "logical-late-refresh-001",
                "session-001",
                1,
                "codex",
                "codex",
                "gpt-5.4",
                "medium",
                "runtime_switch:model",
                "recent_turns_snapshot",
                now + 1
        ));

        ClaudeSession continuedSession = new ClaudeSession(project, null, null);
        continuedSession.setSessionInfo("session-002", System.getProperty("java.io.tmpdir"));
        continuedSession.setProvider("codex");
        continuedSession.setModel("gpt-5.4");
        continuedSession.setReasoningEffort("medium");
        continuedSession.getState().setLogicalConversationId("logical-late-refresh-001");
        continuedSession.getState().setActiveSegmentSessionId("session-002");
        continuedSession.getState().setParentSegmentSessionId("session-001");
        continuedSession.getState().setContinuationPending(false);
        host.setSession(continuedSession);
        host.getHandlerContext().setSession(continuedSession);

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, settingsService, null);
        manager.setSessionMessagesJson("session-002", "[]");

        manager.refreshActiveContinuedLogicalConversationMessagesIfNeededForTest(continuedSession);

        assertFalse("历史尚未可见时，不应提早执行覆盖式回刷",
                host.hasJavaScriptCall("updateMessages"));

        manager.setSessionMessagesJson(
                "session-002",
                "[{\"timestamp\":\"2026-07-06T15:20:38Z\",\"type\":\"event_msg\",\"payload\":{\"type\":\"user_message\",\"message\":\"再+1=?\"}}]"
        );

        manager.refreshActiveContinuedLogicalConversationMessagesIfNeededForTest(continuedSession);

        assertTrue("历史稍后可见后，流结束补偿阶段必须能准备历史回刷快照",
                host.hasJavaScriptCall("prepareHistoryRestoreSnapshot"));
        assertTrue("历史稍后可见后的 continued authoritative 回刷必须继续携带 runtime_continue_authoritative restoreKind",
                host.hasJavaScriptCallArgument("prepareHistoryRestoreSnapshot", 2, "runtime_continue_authoritative"));
        assertTrue("历史稍后可见后，流结束补偿阶段必须能清空旧局部视图",
                host.hasJavaScriptCall("clearMessages"));
        assertTrue("历史稍后可见后，流结束补偿阶段必须能把完整逻辑快照重新注入前端",
                host.hasJavaScriptCall("updateMessages"));
    }

    /**
     * 验证 `onSessionIdAssigned(...)` 在完成 continued 收口后，会显式通知前端结束 continued 过渡态。
     * 该 bridge 不再依赖“首帧消息快照顺手收口”，用于覆盖 sessionId 先到、首帧稍后到达的竞态场景。
     */
    @Test
    public void shouldBridgeExplicitContinuedTransitionCompletionAfterSessionIdAssigned() {
        Project project = createProject();
        RecordingHost host = new RecordingHost(project);
        InMemorySettingsService settingsService = new InMemorySettingsService();
        long now = 1_717_000_400_000L;

        settingsService.saveLogicalConversationRecordUnchecked(new LogicalConversationRecord(
                "logical-bridge-001",
                "session-001",
                "session-001",
                "继续会话",
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
                "logical-bridge-001",
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

        ClaudeSession continuedSession = new ClaudeSession(project, null, null);
        continuedSession.setSessionInfo("session-001", System.getProperty("java.io.tmpdir"));
        continuedSession.setProvider("codex");
        continuedSession.setModel("gpt-5.4");
        continuedSession.setReasoningEffort("medium");
        continuedSession.getState().setLogicalConversationId("logical-bridge-001");
        continuedSession.getState().setContinuationPending(true);
        continuedSession.getState().setContinuationSourceSessionId("session-001");
        host.setSession(continuedSession);
        host.getHandlerContext().setSession(continuedSession);

        TestableSessionLifecycleManager manager = new TestableSessionLifecycleManager(host, settingsService, null);

        manager.onSessionIdAssigned("session-002");

        assertTrue("continued 收口后必须显式通知前端结束 continued 过渡态",
                host.hasJavaScriptCall("window.completeContinuedSegmentTransition"));
        assertTrue("显式 continued 收口信号必须携带新的真实 sessionId",
                host.hasJavaScriptCall("window.completeContinuedSegmentTransition", "session-002"));
        assertTrue("continued 收口后仍需释放通用 session transition guard",
                host.hasJavaScriptCall("historyLoadComplete"));
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
        private final java.util.Map<String, String> sessionMessagesJsonBySessionId = new java.util.HashMap<>();

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
         * 为指定 sessionId 注入测试用的原始 Codex 历史 JSON。
         * 该数据只用于模拟“sessionId 已分配，但历史文件是否已可见”这一运行时差异，
         * 避免单测依赖真实本地 `.codex/sessions` 落盘时序。
         *
         * @param sessionId 目标 sessionId
         * @param messagesJson 对应的原始历史 JSON
         */
        private void setSessionMessagesJson(String sessionId, String messagesJson) {
            sessionMessagesJsonBySessionId.put(sessionId, messagesJson);
        }

        /**
         * 暴露给测试的“仅当前 restore session 仍活跃时才允许推送快照”入口。
         * 该入口用于直接约束 startup restore 过期后不得再执行 `clearMessages -> updateMessages`。
         *
         * @param expectedSession 发起 restore 的目标会话实例
         * @param frontendMessages 待推送前端快照
         * @param restoreRequestKey 本轮 restore key
         * @param rawMessageCount 原始消息数量
         */
        private void pushFrontendMessagesToFrontendIfSessionCurrentForTest(
                ClaudeSession expectedSession,
                java.util.List<JsonObject> frontendMessages,
                String restoreRequestKey,
                int rawMessageCount,
                String restoreKind
        ) {
            pushFrontendMessagesToFrontendIfSessionCurrent(
                    expectedSession,
                    frontendMessages,
                    restoreRequestKey,
                    rawMessageCount,
                    restoreKind
            );
        }

        /**
         * 暴露给测试的最近轮次快照构建入口。
         * 该入口直接复用生产代码中的 carryover 生成逻辑，用于验证 continued 首发前的上下文提取语义。
         *
         * @param sourceSession 作为上下文来源的旧会话
         * @return 供 continued 首发使用的最近轮次快照文本
         */
        private String buildContinuationCarryoverTextForTest(ClaudeSession sourceSession) {
            return buildContinuationCarryoverText(sourceSession);
        }

        /**
         * 暴露给测试的 active continued 回刷入口。
         * 该入口模拟 `stream_end/send_complete` 之后主动补偿一次 continued 逻辑会话回刷，
         * 用于验证“历史稍后可见”场景下最终 assistant 结果仍能重新写回前端。
         *
         * @param currentSession 当前活动会话
         */
        private void refreshActiveContinuedLogicalConversationMessagesIfNeededForTest(ClaudeSession currentSession) {
            refreshActiveContinuedLogicalConversationMessagesIfNeeded(currentSession);
        }

        /**
         * 提供可控的 Codex 历史读取桩。
         * 若测试未显式注入某个 session 的历史，则回退为空数组，模拟“历史尚未可见”的保守场景。
         *
         * @return 测试专用 Codex 历史读取器
         */
        @Override
        protected CodexHistoryReader createCodexHistoryReader() {
            return new CodexHistoryReader() {
                @Override
                public String getSessionMessagesAsJson(String sessionId) {
                    return sessionMessagesJsonBySessionId.getOrDefault(sessionId, "[]");
                }
            };
        }

        /**
         * 为测试桩提供稳定的聚合消息快照，避免单元测试依赖真实的本地 Codex 历史文件。
         * 这里只验证“continued 收口后必须触发聚合回刷”这一行为，因此返回最小非空快照即可。
         *
         * @param requestedSessionId 本轮回刷对应的最新 sessionId
         * @param logicalConversationId 所属逻辑会话 id
         * @param activeSegmentSessionId 当前活动分段 sessionId
         * @return 供测试断言 bridge 调用的最小聚合消息快照
         */
        @Override
        protected java.util.List<JsonObject> buildContinuedLogicalConversationFrontendMessages(
                String requestedSessionId,
                String logicalConversationId,
                String activeSegmentSessionId
        ) {
            if (inMemorySettingsService == null) {
                return super.buildContinuedLogicalConversationFrontendMessages(
                        requestedSessionId,
                        logicalConversationId,
                        activeSegmentSessionId
                );
            }
            JsonObject message = new JsonObject();
            message.addProperty("type", "assistant");
            message.addProperty("content", "continued-refresh:" + activeSegmentSessionId);
            message.addProperty("timestamp", "2026-07-03T00:00:00Z");
            return java.util.List.of(message);
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

        /**
         * 暴露给测试的 continued 创建失败回滚入口。
         * 该入口直接复用生产代码中的回滚逻辑，用于验证失败链路是否会恢复旧 session 并通知前端清理 continued 状态。
         *
         * @param previousSession continued 创建前的旧会话
         * @param failedSession 已经附着到 host 但随后初始化失败的新会话
         * @param errorMessage 失败原因
         */
        private void rollbackFailedContinuedSessionCreationForTest(
                ClaudeSession previousSession,
                ClaudeSession failedSession,
                String errorMessage
        ) {
            rollbackFailedContinuedSessionCreation(previousSession, failedSession, errorMessage);
        }
    }

    /**
     * 内存版设置服务测试桩。
     * 该实现只覆盖本轮继续分段测试所需的逻辑会话/分段索引读写，避免触达真实磁盘配置。
     */
    private static final class InMemorySettingsService extends CodemossSettingsService {
        private final java.util.Map<String, LogicalConversationRecord> logicalConversationRecords = new java.util.HashMap<>();
        private final java.util.Map<String, ConversationSegmentRecord> conversationSegmentRecords = new java.util.HashMap<>();
        private final java.util.Map<String, JsonObject> codexProviders = new java.util.HashMap<>();

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

        /**
         * 根据 providerId 返回测试桩中的受管 Codex provider 配置。
         * 该入口用于驱动 continued segment 收口时的人类可读供应商展示名解析。
         *
         * @param providerId 目标 provider id
         * @return 预置 provider 配置；未命中时返回 null
         */
        @Override
        public JsonObject getCodexProviderById(String providerId) {
            if (providerId == null) {
                return null;
            }
            return codexProviders.get(providerId);
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

        private void saveCodexProviderUnchecked(String providerId, String displayName) {
            JsonObject provider = new JsonObject();
            provider.addProperty("id", providerId);
            provider.addProperty("name", displayName);
            codexProviders.put(providerId, provider);
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

        private void clearJavaScriptCalls() {
            javaScriptCalls.clear();
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
         * 判断测试桩是否记录过指定的前端 bridge 调用。
         *
         * @param functionName 目标 JavaScript 函数名
         * @return 只要出现过至少一次匹配调用则返回 true
         */
        private boolean hasJavaScriptCall(String functionName) {
            for (JavaScriptCall javaScriptCall : javaScriptCalls) {
                if (javaScriptCall != null && javaScriptCall.hasFunctionName(functionName)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 判断测试桩是否记录过指定函数名与首参的前端 bridge 调用。
         *
         * @param functionName 目标 JavaScript 函数名
         * @param firstArg 目标首个参数
         * @return 命中至少一次完全匹配调用时返回 true
         */
        private boolean hasJavaScriptCall(String functionName, String firstArg) {
            for (JavaScriptCall javaScriptCall : javaScriptCalls) {
                if (javaScriptCall != null
                        && javaScriptCall.hasFunctionName(functionName)
                        && javaScriptCall.hasFirstArgument(firstArg)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 判断测试桩是否记录过指定函数名，并且某个参数位等于期望文本的前端 bridge 调用。
         * 该能力用于验证历史恢复协议中的第三个参数 `restoreKind` 是否按预期下发。
         *
         * @param functionName 目标 JavaScript 函数名
         * @param argIndex 目标参数下标，按 0 开始计数
         * @param expectedArgument 目标参数文本
         * @return 命中至少一次完全匹配调用时返回 true
         */
        private boolean hasJavaScriptCallArgument(String functionName, int argIndex, String expectedArgument) {
            for (JavaScriptCall javaScriptCall : javaScriptCalls) {
                if (javaScriptCall != null
                        && javaScriptCall.hasFunctionName(functionName)
                        && javaScriptCall.hasArgumentAt(argIndex, expectedArgument)) {
                    return true;
                }
            }
            return false;
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

        /**
         * 判断当前记录是否匹配目标 JavaScript 函数名。
         *
         * @param expectedFunctionName 目标函数名
         * @return 名称一致时返回 true
         */
        private boolean hasFunctionName(String expectedFunctionName) {
            return expectedFunctionName != null && expectedFunctionName.equals(functionName);
        }

        /**
         * 判断当前记录的首个参数在语义上是否等于期望值。
         * 后端 bridge 调用会对字符串做 JS 转义，这里统一去掉外围引号后再比较。
         *
         * @param expectedFirstArgument 期望的首参文本
         * @return 首参匹配时返回 true
         */
        private boolean hasFirstArgument(String expectedFirstArgument) {
            if (expectedFirstArgument == null || args == null || args.length == 0 || args[0] == null) {
                return false;
            }
            String actual = args[0];
            if (actual.length() >= 2 && actual.startsWith("\"") && actual.endsWith("\"")) {
                actual = actual.substring(1, actual.length() - 1);
            }
            return expectedFirstArgument.equals(actual);
        }

        /**
         * 判断当前记录中指定参数位在语义上是否等于期望值。
         * 后端 bridge 调用会对字符串做 JS 转义，因此这里沿用首参比较逻辑，统一剥离外层引号后再断言。
         *
         * @param argIndex 目标参数下标，按 0 开始计数
         * @param expectedArgument 期望参数文本
         * @return 指定参数位匹配时返回 true
         */
        private boolean hasArgumentAt(int argIndex, String expectedArgument) {
            if (expectedArgument == null || args == null || argIndex < 0 || argIndex >= args.length || args[argIndex] == null) {
                return false;
            }
            String actual = args[argIndex];
            if (actual.length() >= 2 && actual.startsWith("\"") && actual.endsWith("\"")) {
                actual = actual.substring(1, actual.length() - 1);
            }
            return expectedArgument.equals(actual);
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
