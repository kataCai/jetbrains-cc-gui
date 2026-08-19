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
        assertEquals("codemoss_managed_provider", profile.getForcedModelProvider());
        assertEquals("", profile.getLocalCodexModelProvider());
        assertFalse(profile.isLocalConfigConflictDetected());
        assertEquals("codemoss_managed_provider", profile.getFinalModelProvider());
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
    public void shouldUseSdkDefaultWhenProviderBaseUrlIsMissingAndIgnoreLocalBaseUrl() throws Exception {
        JsonObject provider = createManagedProvider();
        provider.remove("baseUrl");
        TestSettingsService settings = new TestSettingsService(provider);
        settings.setCurrentCodexModelState("local-model", "medium", "https://local.example.com/v1");
        settings.setCurrentCodexModelProviderState("LocalOpenAI");
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_CN_API_KEY", "secret-value");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, env::get);

        CodexRuntimeProfile profile = resolver.resolveForProvider(provider, "", "");

        /**
         * 验证目标：
         * 当 provider 自身缺少 baseUrl 时，托管 provider 只能走 SDK 默认 endpoint，
         * 不能因为本地 ~/.codex/config.toml 中存在 endpoint 就被污染到真实请求链路。
         */
        assertEquals("MiniMax-M2.7", profile.getModel());
        assertEquals("", profile.getBaseUrl());
        assertEquals("sdk_default", profile.getBaseUrlSource());
        assertTrue(profile.isFallbackDetected());
        assertEquals("codemoss_managed_provider", profile.getForcedModelProvider());
        assertEquals("LocalOpenAI", profile.getLocalCodexModelProvider());
        assertTrue(profile.isLocalConfigConflictDetected());
        assertEquals("codemoss_managed_provider", profile.getFinalModelProvider());
    }

    @Test
    public void shouldNotReportManagedConflictForCliLoginMode() throws Exception {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);
        provider.addProperty("name", "Codex CLI Login");
        provider.addProperty("isCodexCliLoginProvider", true);
        TestSettingsService settings = new TestSettingsService(provider);
        settings.setCurrentCodexModelProviderState("LocalOpenAI");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, ignored -> "unexpected");

        CodexRuntimeProfile profile = resolver.resolve("gpt-5.4", "high");

        assertTrue(profile.isCodexCliLogin());
        assertEquals("", profile.getForcedModelProvider());
        assertEquals("LocalOpenAI", profile.getLocalCodexModelProvider());
        assertFalse(profile.isLocalConfigConflictDetected());
        assertEquals("LocalOpenAI", profile.getFinalModelProvider());
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

    /**
     * 验证目标：
     * `CodexRuntimeProfileResolver` 作为真实发送链路的基准实现，必须坚持“`models` 存在时不再回退 `customModels`”。
     *
     * 前置条件：
     * provider 显式保存 `models=[]`，同时残留一条历史 `customModels`。
     *
     * 断言意图：
     * 即使旧字段里还有模型，resolver 仍应抛出 “No Codex model configured”，
     * 避免把“显式清空模型列表”的 provider 误判成仍可发送消息。
     */
    @Test(expected = IllegalStateException.class)
    public void shouldNotFallbackToLegacyCustomModelsWhenModelsArrayExists() throws Exception {
        JsonObject provider = createManagedProvider();
        provider.add("models", new JsonArray());
        JsonArray customModels = new JsonArray();
        JsonObject legacyModel = new JsonObject();
        legacyModel.addProperty("id", "legacy-custom-model");
        legacyModel.addProperty("label", "Legacy Custom Model");
        customModels.add(legacyModel);
        provider.add("customModels", customModels);
        TestSettingsService settings = new TestSettingsService(provider);
        Map<String, String> env = new HashMap<>();
        env.put("MINIMAX_CN_API_KEY", "secret-value");
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settings, env::get);

        resolver.resolveForProvider(provider, "", "");
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

        /**
         * 为测试注入本地 ~/.codex/config.toml 中声明的 model_provider。
         * 该字段只用于诊断，帮助验证托管 provider 是否正确暴露“GUI 命中但本地仍有干扰风险”的状态。
         *
         * @param modelProvider 本地配置中的 model_provider；传入空值表示清空该诊断字段
         */
        void setCurrentCodexModelProviderState(String modelProvider) {
            if (currentCodexModelState == null || currentCodexModelState.size() == 0) {
                currentCodexModelState = new JsonObject();
            }
            if (modelProvider == null || modelProvider.trim().isEmpty()) {
                currentCodexModelState.remove("modelProvider");
                return;
            }
            currentCodexModelState.addProperty("modelProvider", modelProvider.trim());
        }
    }
}
