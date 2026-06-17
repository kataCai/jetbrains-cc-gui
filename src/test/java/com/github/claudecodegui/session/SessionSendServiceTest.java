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
}
