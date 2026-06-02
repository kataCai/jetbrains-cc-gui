package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.CodexProviderManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Codex 请求级 runtime profile 解析测试。
 * 这些断言保证每次请求都从 CC-GUI 自有 provider 配置解析参数，避免复用全局 Codex 配置。
 */
public class CodexRuntimeProfileResolverTest {

    @Test
    public void shouldResolveManagedProviderRuntimeProfile() throws Exception {
        TestSettingsService settings = new TestSettingsService(createManagedProvider());
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_CN_API_KEY", "secret-value");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, env::get);

        CodexRuntimeProfile profile = resolver.resolve("", "");

        assertEquals("minimax-cn", profile.getProviderId());
        assertEquals("MiniMax-M2.7", profile.getModel());
        assertEquals("medium", profile.getReasoningEffort());
        assertEquals("https://api.minimaxi.com/anthropic", profile.getBaseUrl());
        assertEquals("secret-value", profile.getApiKey());
        assertEquals("apiKeyEnv:MINIMAX_CN_API_KEY", profile.getCredentialSource());
        assertFalse(profile.isCodexCliLogin());
    }

    @Test
    public void shouldPreferSessionModelAndReasoningEffort() throws Exception {
        TestSettingsService settings = new TestSettingsService(createManagedProvider());
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_CN_API_KEY", "secret-value");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, env::get);

        CodexRuntimeProfile profile = resolver.resolve("gpt-5.4", "high");

        assertEquals("gpt-5.4", profile.getModel());
        assertEquals("high", profile.getReasoningEffort());
    }

    @Test
    public void shouldResolveCliLoginWithoutApiKeyOrBaseUrl() throws Exception {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);
        provider.addProperty("name", "Codex CLI Login");
        provider.addProperty("isCodexCliLoginProvider", true);
        TestSettingsService settings = new TestSettingsService(provider);
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, ignored -> "unexpected");

        CodexRuntimeProfile profile = resolver.resolve("gpt-5.4", "high");

        assertTrue(profile.isCodexCliLogin());
        assertEquals("gpt-5.4", profile.getModel());
        assertEquals("high", profile.getReasoningEffort());
        assertEquals("", profile.getBaseUrl());
        assertEquals("", profile.getApiKey());
    }

    @Test(expected = IllegalStateException.class)
    public void shouldFailWhenApiKeyEnvIsMissing() throws Exception {
        TestSettingsService settings = new TestSettingsService(createManagedProvider());
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, ignored -> null);

        resolver.resolve("", "");
    }

    private JsonObject createManagedProvider() {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", "minimax-cn");
        provider.addProperty("name", "MiniMax CN");
        provider.addProperty("authMode", "api_key_env");
        provider.addProperty("requestMode", "codex_sdk");
        provider.addProperty("baseUrl", "https://api.minimaxi.com/anthropic");
        provider.addProperty("apiKeyEnv", "MINIMAX_CN_API_KEY");
        JsonArray models = new JsonArray();
        JsonObject model = new JsonObject();
        model.addProperty("id", "MiniMax-M2.7");
        model.addProperty("label", "MiniMax M2.7");
        model.addProperty("reasoningEffort", "medium");
        models.add(model);
        provider.add("models", models);
        return provider;
    }

    private static class TestSettingsService extends CodemossSettingsService {
        private final JsonObject activeProvider;

        TestSettingsService(JsonObject activeProvider) {
            this.activeProvider = activeProvider;
        }

        @Override
        public JsonObject getActiveCodexProvider() throws IOException {
            return activeProvider;
        }

        @Override
        public JsonObject getSelectedCodexModel() {
            return new JsonObject();
        }
    }
}
