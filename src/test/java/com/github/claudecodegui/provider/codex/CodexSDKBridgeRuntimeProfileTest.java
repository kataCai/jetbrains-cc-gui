package com.github.claudecodegui.provider.codex;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Codex bridge 请求级 profile 测试。
 * 用于确认 Java 写给 Node stdin 的参数完全来自当前请求，连续切换 provider 时不会复用旧凭据。
 */
public class CodexSDKBridgeRuntimeProfileTest {

    @Test
    public void shouldBuildStdinFromRequestScopedRuntimeProfile() {
        CodexSDKBridge bridge = new CodexSDKBridge();
        CodexRuntimeProfile profile = new CodexRuntimeProfile(
                "minimax-cn",
                "MiniMax-M2.7",
                "https://api.minimaxi.com/anthropic",
                "secret-value",
                "api_key_env",
                "codex_sdk",
                "medium",
                "apiKeyEnv:MINIMAX_CN_API_KEY"
        );

        JsonObject stdin = bridge.buildStdinInput("hello", "", "/tmp/project", "default", profile, false);

        assertEquals("MiniMax-M2.7", stdin.get("model").getAsString());
        assertEquals("medium", stdin.get("reasoningEffort").getAsString());
        assertEquals("https://api.minimaxi.com/anthropic", stdin.get("baseUrl").getAsString());
        assertEquals("secret-value", stdin.get("apiKey").getAsString());
        assertEquals("minimax-cn", stdin.get("providerId").getAsString());
        assertEquals("api_key_env", stdin.get("authMode").getAsString());
        assertEquals("codex_sdk", stdin.get("requestMode").getAsString());
        assertEquals("apiKeyEnv:MINIMAX_CN_API_KEY", stdin.get("credentialSource").getAsString());
        assertEquals("provider", stdin.get("baseUrlSource").getAsString());
        assertEquals("codemoss_managed_provider", stdin.get("effectiveConfigSource").getAsString());
        assertFalse(stdin.get("fallbackDetected").getAsBoolean());
        assertEquals("codemoss_managed_provider", stdin.get("forcedModelProvider").getAsString());
        assertEquals("", stdin.get("localCodexModelProvider").getAsString());
        assertFalse(stdin.get("localConfigConflictDetected").getAsBoolean());
        assertEquals("codemoss_managed_provider", stdin.get("finalModelProvider").getAsString());
    }

    @Test
    public void shouldNotReusePreviousProviderCredentialsAcrossRequests() {
        CodexSDKBridge bridge = new CodexSDKBridge();
        CodexRuntimeProfile firstProfile = new CodexRuntimeProfile(
                "minimax-cn",
                "MiniMax-M2.7",
                "https://api.minimaxi.com/anthropic",
                "first-secret",
                "api_key_env",
                "codex_sdk",
                "medium",
                "apiKeyEnv:MINIMAX_CN_API_KEY"
        );
        CodexRuntimeProfile secondProfile = new CodexRuntimeProfile(
                "gpt-5-4",
                "gpt-5.4",
                "",
                "",
                CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN,
                "codex_sdk",
                "high",
                "codex_cli_login"
        );

        JsonObject firstStdin = bridge.buildStdinInput("first", "", "/tmp/project", "default", firstProfile, false);
        JsonObject secondStdin = bridge.buildStdinInput("second", "", "/tmp/project", "default", secondProfile, false);

        assertEquals("first-secret", firstStdin.get("apiKey").getAsString());
        assertEquals("", secondStdin.get("apiKey").getAsString());
        assertEquals("", secondStdin.get("baseUrl").getAsString());
        assertEquals("gpt-5.4", secondStdin.get("model").getAsString());
    }
}
