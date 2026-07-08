package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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
