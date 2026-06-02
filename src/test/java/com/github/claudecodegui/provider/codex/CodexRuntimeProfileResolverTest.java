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

    @Test
    public void shouldPreferSelectedModelOverDisplayOnlyLocalCodexStateForManagedProvider() throws Exception {
        JsonObject provider = createManagedProvider();
        TestSettingsService settings = new TestSettingsService(provider);
        settings.setSelectedModel("minimax-cn", "selected-model");
        settings.setCurrentCodexModelState("local-model", "high", "https://local.example.com/v1");
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_CN_API_KEY", "secret-value");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, env::get);

        CodexRuntimeProfile profile = resolver.resolve("", "");

        /**
         * 验证目标：
         * ~/.codex/config.toml 里的 model / reasoningEffort 仅用于前端展示当前本地 CLI 状态，
         * 不能反向覆盖 CC-GUI 托管 provider 已持久化的 selected model。
         *
         * 断言意图：
         * 1. 托管 provider 真实请求仍使用当前 provider 归属的 selected model；
         * 2. baseUrl 仍优先取 provider 自身配置，不被本地展示态配置污染。
         */
        assertEquals("selected-model", profile.getModel());
        assertEquals("medium", profile.getReasoningEffort());
        assertEquals("https://api.minimaxi.com/anthropic", profile.getBaseUrl());
    }

    @Test
    public void shouldFallbackToLocalBaseUrlWhenProviderBaseUrlIsMissing() throws Exception {
        JsonObject provider = createManagedProvider();
        provider.remove("baseUrl");
        TestSettingsService settings = new TestSettingsService(provider);
        settings.setCurrentCodexModelState("local-model", "medium", "https://local.example.com/v1");
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_CN_API_KEY", "secret-value");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, env::get);

        CodexRuntimeProfile profile = resolver.resolveForProvider(provider, "", "");

        /**
         * 验证目标：
         * 当 provider 自身缺少 baseUrl 时，可以回退使用本地 CLI 展示态同步过来的 baseUrl；
         * 但模型本身仍应来自 provider 配置，而不是顺带把本地 model 一起带入真实请求。
         */
        assertEquals("MiniMax-M2.7", profile.getModel());
        assertEquals("https://local.example.com/v1", profile.getBaseUrl());
    }

    @Test
    public void shouldResolveForSpecifiedProviderWithoutActiveProviderSwitch() throws Exception {
        JsonObject activeProvider = createManagedProvider();
        JsonObject targetProvider = createManagedProvider();
        targetProvider.addProperty("id", "target-provider");
        targetProvider.addProperty("apiKeyEnv", "TARGET_CODEX_KEY");
        JsonArray targetModels = new JsonArray();
        JsonObject targetModel = new JsonObject();
        targetModel.addProperty("id", "target-model");
        targetModel.addProperty("label", "Target Model");
        targetModel.addProperty("reasoningEffort", "low");
        targetModels.add(targetModel);
        targetProvider.add("models", targetModels);

        TestSettingsService settings = new TestSettingsService(activeProvider);
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_CN_API_KEY", "secret-value");
        env.put("TARGET_CODEX_KEY", "target-secret");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, env::get);

        CodexRuntimeProfile profile = resolver.resolveForProvider(targetProvider, "", "");

        assertEquals("target-provider", profile.getProviderId());
        assertEquals("target-model", profile.getModel());
        assertEquals("target-secret", profile.getApiKey());
        assertEquals("low", profile.getReasoningEffort());
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
        private JsonObject selectedModel = new JsonObject();
        private JsonObject currentCodexModelState = new JsonObject();

        TestSettingsService(JsonObject activeProvider) {
            this.activeProvider = activeProvider;
        }

        @Override
        public JsonObject getActiveCodexProvider() throws IOException {
            return activeProvider;
        }

        @Override
        public JsonObject getSelectedCodexModel() {
            return selectedModel.deepCopy();
        }

        @Override
        public JsonObject getCurrentCodexModelState() {
            return currentCodexModelState.deepCopy();
        }

        void setSelectedModel(String providerId, String modelId) {
            selectedModel = new JsonObject();
            selectedModel.addProperty("providerId", providerId);
            selectedModel.addProperty("modelId", modelId);
        }

        void setCurrentCodexModelState(String model, String reasoningEffort, String baseUrl) {
            currentCodexModelState = new JsonObject();
            if (model != null) {
                currentCodexModelState.addProperty("model", model);
            }
            if (reasoningEffort != null) {
                currentCodexModelState.addProperty("reasoningEffort", reasoningEffort);
            }
            if (baseUrl != null) {
                currentCodexModelState.addProperty("baseUrl", baseUrl);
            }
        }
    }
}
