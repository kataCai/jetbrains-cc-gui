package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证本地 Codex 配置模型同步服务。
 * 该测试集聚焦 Task 2 的混合同步策略：
 * 1. 当本地配置可解析出 `base_url + env_key` 时，应优先走远端 `/v1/models`；
 * 2. 当本地配置缺失安全可复用的 Bearer 凭据时，应退化为 builtin + 当前本地模型兜底。
 */
public class CodexLocalModelSyncServiceTest {

    /**
     * 验证目标：
     * 当本地 `config.toml` 已声明 `model_provider`、`model_providers.<id>.base_url` 和 `env_key` 时，
     * 同步服务应优先走远端模型发现，并把当前本地模型兜底并回结果。
     *
     * 断言意图：
     * 1. `remoteDiscoveryUsed=true`；
     * 2. 结果中保留远端模型顺序；
     * 3. 若当前本地模型未出现在远端列表中，会被补回 discovered models。
     */
    @Test
    public void shouldUseRemoteDiscoveryWhenLocalConfigProvidesBaseUrlAndEnvKey() throws Exception {
        LocalSyncSettingsService settingsService = new LocalSyncSettingsService(
                createLocalConfig(
                        "openai-chat",
                        "gpt-4.1-mini",
                        "https://provider.example.com/v1",
                        "OPENAI_API_KEY"
                ),
                createFallbackModels("gpt-4.1-mini")
        );
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_API_KEY", "secret-value");
        RecordingTransport transport = new RecordingTransport(
                200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4.1\"},{\"id\":\"gpt-4.1-mini\"}]}"
        );
        CodexLocalModelSyncService service = new CodexLocalModelSyncService(
                settingsService,
                env::get,
                new CodexProviderModelDiscoveryService(settingsService, env::get, transport)
        );

        CodexLocalModelSyncService.LocalModelSyncResult result = service.syncLocalModels();

        assertTrue(result.isRemoteDiscoveryUsed());
        assertFalse(result.isFallbackUsed());
        assertEquals("https://provider.example.com/v1/models", transport.requestUri.toString());
        assertEquals("Bearer secret-value", transport.authorizationHeader);
        assertEquals(List.of("gpt-4.1", "gpt-4.1-mini"), readModelIds(result.getDiscoveredModels()));
    }

    /**
     * 验证目标：
     * 当本地配置没有 `env_key` 或环境变量值为空时，不应发起不完整的远端请求，
     * 而应直接回退到 builtin + 当前本地模型兜底。
     *
     * 断言意图：
     * 1. `remoteDiscoveryUsed=false`；
     * 2. 结果仍然包含当前本地生效模型；
     * 3. 不依赖真实网络或本地文件写入。
     */
    /**
     * 验证目标：
     * 当远端 `/v1/models` 没有返回当前本地正在使用的模型时，
     * 同步服务只应把“当前本地模型兜底项”补回结果，而不应把整套 builtin 默认目录一起并入。
     *
     * 断言意图：
     * 1. `remoteDiscoveryUsed=true`，说明本次仍以远端发现结果为主；
     * 2. 结果顺序保持“远端结果在前，本地当前模型兜底在后”；
     * 3. 不相关的 builtin 默认模型不会污染 discovered models。
     */
    @Test
    public void shouldAppendOnlyCurrentLocalModelWhenRemoteDiscoveryMissesIt() throws Exception {
        LocalSyncSettingsService settingsService = new LocalSyncSettingsService(
                createLocalConfig(
                        "openai-chat",
                        "gpt-4.1-mini",
                        "https://provider.example.com/v1",
                        "OPENAI_API_KEY"
                ),
                createFallbackModels("gpt-4.1-mini")
        );
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_API_KEY", "secret-value");
        RecordingTransport transport = new RecordingTransport(
                200,
                "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-4.1\"}]}"
        );
        CodexLocalModelSyncService service = new CodexLocalModelSyncService(
                settingsService,
                env::get,
                new CodexProviderModelDiscoveryService(settingsService, env::get, transport)
        );

        CodexLocalModelSyncService.LocalModelSyncResult result = service.syncLocalModels();

        assertTrue(result.isRemoteDiscoveryUsed());
        assertFalse(result.isFallbackUsed());
        assertEquals(List.of("gpt-4.1", "gpt-4.1-mini"), readModelIds(result.getDiscoveredModels()));
    }


    /**
     * 验证目标：
     * 当本地配置已具备安全远端发现路径，但 `/v1/models` 请求失败时，
     * 同步服务必须向上抛出 IOException，而不是静默回退到 builtin fallback。
     *
     * 断言意图：
     * 1. 调用方能感知失败并决定是否保留旧缓存；
     * 2. 该方法本身不会返回 fallback 成功结果。
     */
    @Test(expected = IOException.class)
    public void shouldPropagateRemoteDiscoveryFailureWithoutFallbackOverwrite() throws Exception {
        LocalSyncSettingsService settingsService = new LocalSyncSettingsService(
                createLocalConfig(
                        "openai-chat",
                        "gpt-4.1-mini",
                        "https://provider.example.com/v1",
                        "OPENAI_API_KEY"
                ),
                createFallbackModels("gpt-4.1-mini")
        );
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_API_KEY", "secret-value");
        RecordingTransport transport = new RecordingTransport(
                500,
                "{\"error\":\"upstream unavailable\"}"
        );
        CodexLocalModelSyncService service = new CodexLocalModelSyncService(
                settingsService,
                env::get,
                new CodexProviderModelDiscoveryService(settingsService, env::get, transport)
        );

        service.syncLocalModels();
    }
    @Test
    public void shouldFallbackToBuiltinCatalogWhenLocalConfigCannotProvideSafeRemoteCredentials() throws Exception {
        LocalSyncSettingsService settingsService = new LocalSyncSettingsService(
                createLocalConfig(
                        "openai-chat",
                        "gpt-4.1-mini",
                        "https://provider.example.com/v1",
                        ""
                ),
                createFallbackModels("gpt-4.1-mini")
        );
        CodexLocalModelSyncService service = new CodexLocalModelSyncService(
                settingsService,
                ignored -> "",
                new CodexProviderModelDiscoveryService(settingsService, ignored -> "")
        );

        CodexLocalModelSyncService.LocalModelSyncResult result = service.syncLocalModels();

        assertFalse(result.isRemoteDiscoveryUsed());
        assertTrue(result.isFallbackUsed());
        assertEquals(List.of("gpt-5.5", "gpt-4.1-mini"), readModelIds(result.getDiscoveredModels()));
    }

    private static JsonObject createLocalConfig(
            String modelProvider,
            String currentModel,
            String baseUrl,
            String envKey
    ) {
        JsonObject config = new JsonObject();
        JsonObject root = new JsonObject();
        JsonObject configToml = new JsonObject();
        configToml.addProperty("model", currentModel);
        configToml.addProperty("model_provider", modelProvider);
        JsonObject modelProviders = new JsonObject();
        JsonObject provider = new JsonObject();
        provider.addProperty("base_url", baseUrl);
        if (envKey != null && !envKey.isBlank()) {
            provider.addProperty("env_key", envKey);
        }
        modelProviders.add(modelProvider, provider);
        configToml.add("model_providers", modelProviders);
        root.add("config", configToml);
        config.add("localConfig", root);
        return root;
    }

    private static JsonArray createFallbackModels(String currentModelId) {
        JsonArray models = new JsonArray();
        JsonObject builtin = new JsonObject();
        builtin.addProperty("id", "gpt-5.5");
        builtin.addProperty("label", "gpt-5.5");
        models.add(builtin);
        JsonObject current = new JsonObject();
        current.addProperty("id", currentModelId);
        current.addProperty("label", currentModelId);
        models.add(current);
        return models;
    }

    private static List<String> readModelIds(JsonArray models) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < models.size(); i++) {
            ids.add(models.get(i).getAsJsonObject().get("id").getAsString());
        }
        return ids;
    }

    /**
     * 本地同步设置服务桩。
     * 该桩只暴露本地配置镜像与 fallback 目录，避免测试依赖真实 ~/.codex 或完整 settings facade。
     */
    private static class LocalSyncSettingsService extends CodemossSettingsService {
        private final JsonObject localConfig;
        private final JsonArray fallbackModels;

        LocalSyncSettingsService(JsonObject localConfig, JsonArray fallbackModels) {
            this.localConfig = localConfig;
            this.fallbackModels = fallbackModels;
        }

        @Override
        public JsonObject getCurrentCodexConfig() {
            return localConfig.deepCopy();
        }

        @Override
        public JsonArray buildCodexCliLoginFallbackModels() {
            return fallbackModels.deepCopy();
        }

        @Override
        public JsonObject getSelectedCodexModel() {
            return new JsonObject();
        }

        @Override
        public JsonObject getCurrentCodexModelState() {
            JsonObject state = new JsonObject();
            if (localConfig.has("config") && localConfig.get("config").isJsonObject()) {
                JsonObject config = localConfig.getAsJsonObject("config");
                if (config.has("model")) {
                    state.add("model", config.get("model").deepCopy());
                }
                if (config.has("model_provider")) {
                    state.addProperty("modelProvider", config.get("model_provider").getAsString());
                }
            }
            return state;
        }
    }

    /**
     * 记录请求参数的传输桩。
     * 该桩不做真实网络访问，只保存本地同步服务最终组装出的 URI 与 Bearer 头。
     */
    private static class RecordingTransport implements CodexProviderModelDiscoveryService.HttpTransport {
        private final int statusCode;
        private final String responseBody;
        private java.net.URI requestUri;
        private String authorizationHeader = "";

        RecordingTransport(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public CodexProviderModelDiscoveryService.TransportResponse get(
                java.net.URI uri,
                String authorizationHeader,
                String acceptHeader
        ) throws IOException {
            this.requestUri = uri;
            this.authorizationHeader = authorizationHeader;
            return new CodexProviderModelDiscoveryService.TransportResponse(statusCode, responseBody);
        }
    }
}
