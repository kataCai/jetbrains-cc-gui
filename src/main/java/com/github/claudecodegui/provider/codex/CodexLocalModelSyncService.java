package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * 本地 Codex 配置模型同步服务。
 * 该服务只负责把“设置页本地 Codex 配置卡片的同步模型按钮”收敛成一个稳定策略：
 * 1. 若本地 `~/.codex/config.toml` 能稳定解析到 `base_url + env_key`，则尝试走远端 `/v1/models` 发现；
 * 2. 若当前为 CLI Login / OAuth，或缺失安全可复用的 Bearer 凭据，则退化为“内建模型 + 当前本地生效模型兜底”；
 * 3. 不修改 `~/.codex/config.toml` 或 `auth.json`，只返回可持久化的 discovered models 缓存。
 */
public class CodexLocalModelSyncService {

    private static final String MODEL_PROVIDER_KEY = "model_provider";
    private static final String MODEL_PROVIDERS_KEY = "model_providers";
    private static final String BASE_URL_KEY = "base_url";
    private static final String ENV_KEY_KEY = "env_key";
    private static final String FALLBACK_PROVIDER_ID = "openai";

    private final CodemossSettingsService settingsService;
    private final Function<String, String> environmentReader;
    private final CodexProviderModelDiscoveryService discoveryService;

    /**
     * 创建默认本地模型同步服务。
     *
     * @param settingsService 设置服务，用于读取本地配置镜像与内建目录能力
     */
    public CodexLocalModelSyncService(CodemossSettingsService settingsService) {
        this(settingsService, System::getenv, null);
    }

    /**
     * 创建可注入依赖的本地模型同步服务。
     * 该入口主要服务于单元测试，允许替换环境变量读取器和远端 discovery 服务。
     *
     * @param settingsService 设置服务，用于读取本地配置镜像与内建目录能力
     * @param environmentReader 读取环境变量值的函数
     * @param discoveryService 远端模型发现服务；为 null 时会按默认依赖创建
     */
    public CodexLocalModelSyncService(
            CodemossSettingsService settingsService,
            Function<String, String> environmentReader,
            CodexProviderModelDiscoveryService discoveryService
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.environmentReader = Objects.requireNonNull(environmentReader, "environmentReader");
        this.discoveryService = discoveryService != null
                ? discoveryService
                : new CodexProviderModelDiscoveryService(settingsService, environmentReader);
    }

    /**
     * 执行本地 Codex 配置模型同步。
     * 该方法不会直接写配置，只返回一个包含 discovered models 与降级信息的结果对象，
     * 由上层编排决定是否持久化以及如何提示用户。
     *
     * @return 本次同步的结构化结果
     * @throws IOException 读取本地配置或远端发现失败时抛出
     */
    public LocalModelSyncResult syncLocalModels() throws IOException {
        JsonArray fallbackModels = settingsService.buildCodexCliLoginFallbackModels();
        JsonObject localConfig = settingsService.getCurrentCodexConfig();
        JsonObject provider = buildDiscoverableProviderFromLocalConfig(localConfig);
        if (provider == null) {
            return new LocalModelSyncResult(
                    fallbackModels,
                    false,
                    true,
                    fallbackModels.size(),
                    0,
                    0,
                    "",
                    "fallback_builtin"
            );
        }

        String baseUrl = readString(provider, "baseUrl");
        // 已具备安全远端发现路径时，远端失败必须向上抛出，由上层决定报错并保留旧缓存；
        // 不能在这里静默回退到 builtin fallback，否则会把已有 discovered models 覆盖成降级目录。
        CodexProviderModelDiscoveryService.DiscoveryResult discoveryResult = discoveryService.discoverModels(provider);
        JsonArray discoveredModels = toDiscoveredModels(discoveryResult);
        JsonArray currentLocalModelFallback = extractCurrentLocalModelFallback(fallbackModels, localConfig);
        JsonArray mergedModels = mergeFallbackCurrentModel(discoveredModels, currentLocalModelFallback);
        return new LocalModelSyncResult(
                mergedModels,
                true,
                false,
                mergedModels.size(),
                discoveryResult.getDuplicateCount(),
                discoveryResult.getSkippedCount(),
                baseUrl,
                "config_api_key"
        );
    }

    /**
     * 从本地 `config.toml` 镜像中提炼一个可直接用于远端模型发现的临时 provider。
     * 当前仅支持 `model_providers.<id>.env_key + base_url` 这条安全可控路径；
     * 若缺失任一关键字段，则返回 null，让上层自然降级到 fallback 目录。
     *
     * @param localConfig `getCurrentCodexConfig()` 返回的本地配置镜像
     * @return 可供 discovery service 复用的临时 provider；不满足条件时返回 null
     */
    private JsonObject buildDiscoverableProviderFromLocalConfig(JsonObject localConfig) {
        if (localConfig == null
                || !localConfig.has("config")
                || !localConfig.get("config").isJsonObject()) {
            return null;
        }

        JsonObject config = localConfig.getAsJsonObject("config");
        String providerId = readString(config, MODEL_PROVIDER_KEY);
        if (providerId.isEmpty()) {
            providerId = FALLBACK_PROVIDER_ID;
        }
        if (!config.has(MODEL_PROVIDERS_KEY) || !config.get(MODEL_PROVIDERS_KEY).isJsonObject()) {
            return null;
        }
        JsonObject providers = config.getAsJsonObject(MODEL_PROVIDERS_KEY);
        if (!providers.has(providerId) || !providers.get(providerId).isJsonObject()) {
            return null;
        }
        JsonObject selectedProvider = providers.getAsJsonObject(providerId);
        String baseUrl = readString(selectedProvider, BASE_URL_KEY);
        String envKey = readString(selectedProvider, ENV_KEY_KEY);
        if (baseUrl.isEmpty() || envKey.isEmpty()) {
            return null;
        }
        String envValue = environmentReader.apply(envKey);
        if (envValue == null || envValue.trim().isEmpty()) {
            return null;
        }

        JsonObject provider = new JsonObject();
        provider.addProperty("id", providerId);
        provider.addProperty("name", providerId);
        provider.addProperty("authMode", "api_key_env");
        provider.addProperty("requestMode", "codex_sdk");
        provider.addProperty("baseUrl", baseUrl);
        provider.addProperty("apiKeyEnv", envKey);
        JsonArray models = new JsonArray();
        String currentModel = readString(config, "model");
        if (!currentModel.isEmpty()) {
            JsonObject seedModel = new JsonObject();
            seedModel.addProperty("id", currentModel);
            seedModel.addProperty("label", currentModel);
            models.add(seedModel);
        }
        provider.add("models", models);
        return provider;
    }

    /**
     * 把 discovery service 返回的 id 列表转换成可持久化的 discovered model 节点。
     *
     * @param discoveryResult 远端模型发现结果
     * @return 标准化 discovered models 数组
     */
    private JsonArray toDiscoveredModels(CodexProviderModelDiscoveryService.DiscoveryResult discoveryResult) {
        JsonArray models = new JsonArray();
        for (String modelId : discoveryResult.getModelIds()) {
            String normalizedModelId = modelId == null ? "" : modelId.trim();
            if (normalizedModelId.isEmpty()) {
                continue;
            }
            JsonObject model = new JsonObject();
            model.addProperty("id", normalizedModelId);
            model.addProperty("label", normalizedModelId);
            models.add(model);
        }
        return models;
    }

    /**
     * 把 fallback 目录中的“当前本地生效模型兜底项”并回发现结果。
     * 这样即使远端返回结果不包含当前正在使用的模型，设置页与聊天区仍能继续看到该模型目录项。
     *
     * @param primaryModels discovery 得到的主模型数组
     * @param fallbackModels 仅包含当前本地模型兜底项的数组；远端成功时不应把整套 builtin 目录一并混入
     * @return 合并后的 discovered models 数组
     */
    private JsonArray mergeFallbackCurrentModel(JsonArray primaryModels, JsonArray fallbackModels) {
        JsonArray merged = new JsonArray();
        java.util.LinkedHashSet<String> seenIds = new java.util.LinkedHashSet<>();

        appendModelsIfAbsent(merged, seenIds, primaryModels);
        appendModelsIfAbsent(merged, seenIds, fallbackModels);
        return merged;
    }

    /**
     * 从 fallback 目录中提炼“当前本地生效模型”的单项兜底数组。
     * 远端发现成功时，设置页只需要保证当前本地模型不会因为远端缺项而消失，
     * 不应顺带把 builtin 默认模型目录全部并回 discovered models。
     *
     * @param fallbackModels builtin + 当前本地模型组成的完整 fallback 数组
     * @param localConfig 当前本地 `config.toml` 镜像
     * @return 仅包含当前本地模型兜底项的数组；若无法解析当前模型，则返回空数组
     */
    private JsonArray extractCurrentLocalModelFallback(JsonArray fallbackModels, JsonObject localConfig) {
        JsonArray currentModelFallback = new JsonArray();
        if (fallbackModels == null) {
            return currentModelFallback;
        }
        String currentModelId = readCurrentLocalModelId(localConfig);
        if (currentModelId.isEmpty()) {
            return currentModelFallback;
        }
        for (JsonElement element : fallbackModels) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            if (!currentModelId.equals(readString(model, "id"))) {
                continue;
            }
            currentModelFallback.add(model.deepCopy());
            break;
        }
        return currentModelFallback;
    }

    /**
     * 读取本地 `config.toml` 镜像中的当前模型 id。
     * 该值决定远端成功路径下允许回补的唯一 fallback 项，避免把无关 builtin 模型误写入 discovered models。
     *
     * @param localConfig 当前本地配置镜像
     * @return 当前模型 id；缺失时返回空串
     */
    private String readCurrentLocalModelId(JsonObject localConfig) {
        if (localConfig == null
                || !localConfig.has("config")
                || !localConfig.get("config").isJsonObject()) {
            return "";
        }
        return readString(localConfig.getAsJsonObject("config"), "model");
    }

    /**
     * 追加一组模型节点，并按 `id` 去重。
     *
     * @param target 目标数组
     * @param seenIds 已出现模型 id 集合
     * @param source 待追加的模型数组
     */
    private void appendModelsIfAbsent(JsonArray target, java.util.Set<String> seenIds, JsonArray source) {
        if (source == null) {
            return;
        }
        for (JsonElement element : source) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            String modelId = readString(model, "id");
            if (modelId.isEmpty() || !seenIds.add(modelId)) {
                continue;
            }
            target.add(model.deepCopy());
        }
    }

    /**
     * 安全读取对象中的字符串字段。
     *
     * @param object 源对象
     * @param key 字段名
     * @return 去空白后的字符串；缺失时返回空串
     */
    private String readString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString().trim();
    }
    /**
     * 本地模型同步结果。
     * 该对象同时承载发现结果、是否发生远端发现、是否走了降级路径，以及展示提示所需的统计与来源摘要。
     */
    public static class LocalModelSyncResult {
        private final JsonArray discoveredModels;
        private final boolean remoteDiscoveryUsed;
        private final boolean fallbackUsed;
        private final int addedCount;
        private final int duplicateCount;
        private final int skippedCount;
        private final String baseUrl;
        private final String syncSource;

        /**
         * 创建一份本地模型同步结果。
         *
         * @param discoveredModels 可持久化的 discovered models 数组
         * @param remoteDiscoveryUsed 是否实际走了远端 `/v1/models`
         * @param fallbackUsed 是否走了降级 fallback
         * @param addedCount 结果模型数，用于提示文案
         * @param duplicateCount 远端重复项数
         * @param skippedCount 远端无效项数
         * @param baseUrl 本次尝试命中的 baseUrl
         * @param syncSource 本次同步来源摘要
         */
        public LocalModelSyncResult(
                JsonArray discoveredModels,
                boolean remoteDiscoveryUsed,
                boolean fallbackUsed,
                int addedCount,
                int duplicateCount,
                int skippedCount,
                String baseUrl,
                String syncSource
        ) {
            this.discoveredModels = discoveredModels == null ? new JsonArray() : discoveredModels.deepCopy();
            this.remoteDiscoveryUsed = remoteDiscoveryUsed;
            this.fallbackUsed = fallbackUsed;
            this.addedCount = addedCount;
            this.duplicateCount = duplicateCount;
            this.skippedCount = skippedCount;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.syncSource = syncSource == null ? "" : syncSource;
        }

        /**
         * 返回可持久化的 discovered models 数组。
         *
         * @return discovered models 深拷贝
         */
        public JsonArray getDiscoveredModels() {
            return discoveredModels.deepCopy();
        }

        /**
         * 返回本次是否实际走了远端发现。
         *
         * @return `true` 表示命中了 `/v1/models`
         */
        public boolean isRemoteDiscoveryUsed() {
            return remoteDiscoveryUsed;
        }

        /**
         * 返回本次是否发生了降级。
         *
         * @return `true` 表示回退到 fallback 目录
         */
        public boolean isFallbackUsed() {
            return fallbackUsed;
        }

        /**
         * 返回本次产出的模型数。
         *
         * @return 结果模型数
         */
        public int getAddedCount() {
            return addedCount;
        }

        /**
         * 返回远端重复项统计。
         *
         * @return 重复项数
         */
        public int getDuplicateCount() {
            return duplicateCount;
        }

        /**
         * 返回远端无效项统计。
         *
         * @return 无效项数
         */
        public int getSkippedCount() {
            return skippedCount;
        }

        /**
         * 返回本次同步尝试命中的 baseUrl。
         *
         * @return baseUrl；未知时为空串
         */
        public String getBaseUrl() {
            return baseUrl;
        }

        /**
         * 返回本次同步来源摘要。
         *
         * @return 来源标识
         */
        public String getSyncSource() {
            return syncSource;
        }
    }
}
