package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SessionSendServiceTest {

    @Test
    public void normalizeRequestedPermissionModeRejectsBlankAndUnknownValues() {
        assertNull(SessionSendService.normalizeRequestedPermissionMode(null));
        assertNull(SessionSendService.normalizeRequestedPermissionMode(" "));
        assertNull(SessionSendService.normalizeRequestedPermissionMode("dangerouslyAllowEverything"));
    }

    @Test
    public void resolveEffectivePermissionModePrefersRequestedModeWhenValid() {
        assertEquals(
                "acceptEdits",
                SessionSendService.resolveEffectivePermissionMode("claude", "acceptEdits", "default")
        );
    }

    @Test
    public void resolveEffectivePermissionModeFallsBackToSessionModeAndDowngradesCodexPlan() {
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("codex", null, "plan")
        );
        assertEquals(
                "default",
                SessionSendService.resolveEffectivePermissionMode("claude", null, null)
        );
    }

    @Test
    public void getCodexRuntimeAccessErrorRequiresAuthorizationOrManagedProvider() {
        assertEquals(
                "Codex local configuration access is not authorized. Please authorize local ~/.codex access or enable a managed Codex provider first.",
                SessionSendService.getCodexRuntimeAccessError("inactive")
        );
        assertNull(SessionSendService.getCodexRuntimeAccessError("managed"));
        assertNull(SessionSendService.getCodexRuntimeAccessError("cli_login"));
    }

    @Test
    public void describeCodexBindingForTraceShouldExposeCoreFields() {
        CodexSessionBinding binding = new CodexSessionBinding(
                "managed-minimax",
                "MiniMax-M3",
                "codex_sdk",
                "provider",
                "managed_provider"
        );

        String description = SessionSendService.describeCodexBindingForTrace(binding);

        assertTrue(description.contains("providerId=managed-minimax"));
        assertTrue(description.contains("model=MiniMax-M3"));
        assertTrue(description.contains("requestMode=codex_sdk"));
        assertTrue(description.contains("baseUrlSource=provider"));
        assertTrue(description.contains("effectiveConfigSource=managed_provider"));
    }

    @Test
    public void runtimeProfileSelectionShouldPreferSessionBindingProvider() {
        CodexSessionBinding binding = new CodexSessionBinding(
                "managed-minimax",
                "MiniMax-M3",
                "codex_sdk",
                "provider",
                "managed_provider"
        );
        CodexRuntimeProfile profile = new CodexRuntimeProfile(
                "managed-minimax",
                "MiniMax-M3",
                "https://api.minimax.chat",
                "masked-key",
                "api_key",
                "codex_sdk",
                "medium",
                "apiKey",
                "provider",
                "codemoss_managed_provider",
                false,
                CodexRuntimeProfile.MANAGED_PROVIDER_FORCED_MODEL_PROVIDER,
                "",
                false,
                CodexRuntimeProfile.MANAGED_PROVIDER_FORCED_MODEL_PROVIDER
        );

        assertEquals(
                "session_binding",
                SessionSendService.determineCodexRuntimeProfileTraceSource(binding, profile)
        );
    }

    @Test
    public void runtimeProfileSelectionShouldFallbackToActiveProviderWhenBindingMissing() {
        CodexRuntimeProfile profile = new CodexRuntimeProfile(
                "active-openai",
                "gpt-5.4",
                "",
                "masked-key",
                "api_key",
                "codex_sdk",
                "medium",
                "apiKey",
                "sdk_default",
                "codemoss_managed_provider",
                true,
                CodexRuntimeProfile.MANAGED_PROVIDER_FORCED_MODEL_PROVIDER,
                "",
                false,
                CodexRuntimeProfile.MANAGED_PROVIDER_FORCED_MODEL_PROVIDER
        );

        assertEquals(
                "active_provider_fallback",
                SessionSendService.determineCodexRuntimeProfileTraceSource(null, profile)
        );
    }

    /**
     * 验证 continued segment 首发时优先注入最近对话快照，而不是继续复用首轮摘要。
     * 该约束直接覆盖“多次切换模型后仍沿用第一次 1+1 摘要，导致上下文回退”的回归场景。
     */
    @Test
    public void buildCodexContinuationCarryoverPrefixShouldPreferRecentConversationSnapshotOverSummary() {
        SessionState state = new SessionState();
        state.setContinuationPending(true);
        state.setLogicalConversationId("logical-001");
        state.setContinuationSourceSessionId("segment-001");
        state.setSummary("1+1=?");
        state.setContinuationCarryoverText("User: 再+1=?\nAssistant: 4");

        String prefix = SessionSendService.buildCodexContinuationCarryoverPrefix(state);

        assertTrue(prefix.contains("Conversation Continuation"));
        assertTrue(prefix.contains("logical-001"));
        assertTrue(prefix.contains("segment-001"));
        assertTrue(prefix.contains("Recent conversation turns"));
        assertTrue(prefix.contains("User: 再+1=?"));
        assertTrue(prefix.contains("Assistant: 4"));
        assertFalse(prefix.contains("Previous conversation summary: 1+1=?"));
    }

    /**
     * 验证当最近轮次快照缺失时，仍会回退到旧的摘要字段，避免历史兼容路径完全失效。
     */
    @Test
    public void buildCodexContinuationCarryoverPrefixShouldFallbackToSummaryWhenSnapshotMissing() {
        SessionState state = new SessionState();
        state.setContinuationPending(true);
        state.setLogicalConversationId("logical-001");
        state.setContinuationSourceSessionId("segment-001");
        state.setSummary("继续修复历史会话跨模型切换后的上下文延续问题");

        String prefix = SessionSendService.buildCodexContinuationCarryoverPrefix(state);

        assertTrue(prefix.contains("Previous conversation summary: 继续修复历史会话跨模型切换后的上下文延续问题"));
    }

    /**
     * 验证普通发送不会平白注入 carryover 前缀，避免污染非 continued segment 的首条输入。
     */
    @Test
    public void buildCodexContinuationCarryoverPrefixShouldBeEmptyForRegularSessions() {
        SessionState state = new SessionState();
        state.setContinuationPending(false);
        state.setLogicalConversationId("logical-001");
        state.setContinuationSourceSessionId("segment-001");
        state.setSummary("should not be used");

        assertEquals("", SessionSendService.buildCodexContinuationCarryoverPrefix(state));
    }

    /**
     * 验证 continued segment 的上下文延续前缀构造不应依赖具体 provider。
     * 该约束用于覆盖“切换到 Claude 运行时后没有 carryover，导致继续会话首条消息冷启动”的回归场景。
     */
    @Test
    public void buildContinuationCarryoverPrefixShouldAlsoSupportClaudeRuntime() {
        SessionState state = new SessionState();
        state.setContinuationPending(true);
        state.setLogicalConversationId("logical-claude-001");
        state.setContinuationSourceSessionId("segment-claude-001");
        state.setSummary("继续整理上一次 Claude 会话里的修复结论");

        String prefix = SessionSendService.buildContinuationCarryoverPrefix(state);

        assertTrue(prefix.contains("Conversation Continuation"));
        assertTrue(prefix.contains("logical-claude-001"));
        assertTrue(prefix.contains("segment-claude-001"));
        assertTrue(prefix.contains("继续整理上一次 Claude 会话里的修复结论"));
    }

    /**
     * 验证全局 active provider 与会话 binding 分叉时，trace 工具方法会明确返回 `diverged`。
     * 该断言用于保护后续诊断日志，确保排查时能够一眼看出“全局状态”和“请求级绑定”是否已经不一致。
     */
    @Test
    public void shouldMarkProviderConsistencyAsDivergedWhenBindingAndActiveProviderDiffer() {
        CodexSessionBinding binding = new CodexSessionBinding(
                "managed-minimax",
                "MiniMax-M3",
                "codex_sdk",
                "provider",
                "managed_provider"
        );
        com.google.gson.JsonObject activeProvider = new com.google.gson.JsonObject();
        activeProvider.addProperty("id", "__codex_cli_login__");
        activeProvider.addProperty("authMode", CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN);
        activeProvider.addProperty("requestMode", "codex_sdk");
        activeProvider.addProperty("isCodexCliLoginProvider", true);

        assertEquals(
                "diverged",
                SessionSendService.determineCodexProviderConsistencyForTrace(binding, activeProvider)
        );
        assertTrue(SessionSendService.describeActiveCodexProviderForTrace(activeProvider).contains("providerId=__codex_cli_login__"));
    }
}
