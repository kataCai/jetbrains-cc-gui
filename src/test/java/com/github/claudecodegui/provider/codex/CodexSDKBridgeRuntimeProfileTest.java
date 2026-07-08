package com.github.claudecodegui.provider.codex;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * 验证 Codex Java bridge 写入 Node stdin 的运行时 profile 组装逻辑。
 * 该测试类聚焦请求级 provider/profile 的透传边界，确保托管 provider 与 CLI Login
 * 在连续切换、全局状态分叉时，仍然只按当前请求语义决定是否保留凭据与 endpoint。
 */
public class CodexSDKBridgeRuntimeProfileTest {

    /**
     * 验证托管 provider 请求会把当前请求自己的 model、apiKey、baseUrl 等字段完整写入 stdin。
     * 该场景覆盖正常的 request-scoped provider 透传链路，确保后续 Node 侧可以继续注入
     * request-scoped 的 `CODEX_API_KEY`，而不是退回到本地 CLI Login 配置。
     */
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

    /**
     * 验证即使全局状态仍处于 CLI Login，请求级托管 provider 也不能被误判成 CLI Login。
     * 这是本次认证回归的直接保护用例：若这里被错误清空 `apiKey/baseUrl`，下游就会再次报
     * `Missing environment variable: CODEX_API_KEY`。
     */
    @Test
    public void shouldKeepManagedProviderCredentialsWhenGlobalStateIsCliLogin() {
        CodexSDKBridge bridge = new CodexSDKBridge();
        CodexRuntimeProfile profile = new CodexRuntimeProfile(
                "buycode-plus",
                "gpt-5.4-mini",
                "https://console.buycodekey.com/v1",
                "managed-secret",
                "api_key",
                "codex_sdk",
                "high",
                "apiKey"
        );

        JsonObject stdin = bridge.buildStdinInput("hello", "", "/tmp/project", "default", profile, true);

        assertEquals("https://console.buycodekey.com/v1", stdin.get("baseUrl").getAsString());
        assertEquals("managed-secret", stdin.get("apiKey").getAsString());
        assertEquals("codemoss_managed_provider", stdin.get("effectiveConfigSource").getAsString());
    }

    /**
     * 验证连续请求切换到 CLI Login 后，不会复用上一个托管 provider 的凭据。
     * 该用例同时覆盖 CLI Login 请求在没有全局 CLI Login 标记参与时，仍会按请求级语义
     * 正确清空 `apiKey/baseUrl`，避免托管 provider 凭据串线。
     */
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

    /**
     * 验证当前请求一旦命中 CLI Login，就算全局 active provider 不是 CLI Login，
     * 也必须只按请求级语义清空 `apiKey/baseUrl`，避免误复用上一个托管 provider 的凭据。
     */
    @Test
    public void shouldMaskCredentialsForCliLoginRequestEvenWhenGlobalStateIsManagedProvider() {
        CodexSDKBridge bridge = new CodexSDKBridge();
        CodexRuntimeProfile cliLoginProfile = new CodexRuntimeProfile(
                "gpt-5-4",
                "gpt-5.4",
                "",
                "",
                CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN,
                "codex_sdk",
                "high",
                "codex_cli_login"
        );

        JsonObject stdin = bridge.buildStdinInput("hello", "", "/tmp/project", "default", cliLoginProfile, false);

        assertEquals("", stdin.get("apiKey").getAsString());
        assertEquals("", stdin.get("baseUrl").getAsString());
        assertEquals("gpt-5.4", stdin.get("model").getAsString());
    }
}
