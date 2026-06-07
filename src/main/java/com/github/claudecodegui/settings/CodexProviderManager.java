package com.github.claudecodegui.settings;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.model.DeleteResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Codex Provider Manager
 * Manages Codex provider configurations stored in ~/.codemoss/config.json
 * and resolves CC-GUI runtime provider state without writing ~/.codex by default.
 */
public class CodexProviderManager {
    private static final Logger LOG = Logger.getInstance(CodexProviderManager.class);
    private static final String BACKUP_FILE_NAME = "config.json.bak";
    public static final String CODEX_CLI_LOGIN_PROVIDER_ID = "__codex_cli_login__";
    private static final String CODEX_KEY = "codex";
    private static final String PROVIDERS_KEY = "providers";
    private static final String CURRENT_KEY = "current";
    private static final String SELECTED_MODEL_KEY = "selectedModel";
    private static final String MODELS_KEY = "models";
    private static final String CUSTOM_MODELS_KEY = "customModels";
    private static final String REQUEST_MODE_KEY = "requestMode";
    private static final String AUTH_MODE_KEY = "authMode";
    private static final Set<String> VALID_REQUEST_MODES = Set.of("codex_sdk", "cc_switch_proxy", "custom_adapter");
    private static final Set<String> VALID_AUTH_MODES = Set.of("api_key", "api_key_env", "codex_cli_login", "proxy", "oauth");

    private final Gson gson;
    private final Function<Void, JsonObject> configReader;
    private final Consumer<JsonObject> configWriter;
    private final ConfigPathManager pathManager;
    private final CodexSettingsManager codexSettingsManager;

    public CodexProviderManager(
            Gson gson,
            Function<Void, JsonObject> configReader,
            Consumer<JsonObject> configWriter,
            ConfigPathManager pathManager,
            CodexSettingsManager codexSettingsManager) {
        this.gson = gson;
        this.configReader = configReader;
        this.configWriter = configWriter;
        this.pathManager = pathManager;
        this.codexSettingsManager = codexSettingsManager;
    }

    /**
     * Get all Codex providers
     */
    public List<JsonObject> getCodexProviders() {
        JsonObject config = configReader.apply(null);
        List<JsonObject> result = new ArrayList<>();

        String currentId = null;
        if (config.has(CODEX_KEY) && config.get(CODEX_KEY).isJsonObject()) {
            JsonObject codex = config.getAsJsonObject(CODEX_KEY);
            if (codex.has(CURRENT_KEY) && !codex.get(CURRENT_KEY).isJsonNull()) {
                currentId = codex.get(CURRENT_KEY).getAsString();
            }
        }
        boolean cliLoginAuthorized = isCodexCliLoginAuthorized(config);

        // Add CLI Login virtual provider at the top
        result.add(createCodexCliLoginProviderObject(
                CODEX_CLI_LOGIN_PROVIDER_ID.equals(currentId) && cliLoginAuthorized,
                cliLoginAuthorized
        ));

        if (!config.has(CODEX_KEY)) {
            return result;
        }

        JsonObject codex = config.getAsJsonObject(CODEX_KEY);
        if (!codex.has(PROVIDERS_KEY)) {
            return result;
        }

        JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);

        // Get provider order from config, or use default order (by key)
        List<String> orderedIds = ProviderOrderHelper.getProviderOrder(codex, providers.keySet());

        // Add providers in order
        for (String id : orderedIds) {
            if (providers.has(id)) {
                JsonObject provider = providers.getAsJsonObject(id).deepCopy();
                // Ensure id field exists
                if (!provider.has("id")) {
                    provider.addProperty("id", id);
                }
                // Add isActive flag
                provider.addProperty("isActive", id.equals(currentId));
                result.add(provider);
            }
        }

        return result;
    }

    /**
     * Save provider order.
     */
    public void saveProviderOrder(List<String> orderedIds) throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has(CODEX_KEY)) {
            JsonObject codex = new JsonObject();
            codex.add(PROVIDERS_KEY, new JsonObject());
            codex.addProperty(CURRENT_KEY, "");
            config.add(CODEX_KEY, codex);
        }

        JsonObject codex = config.getAsJsonObject(CODEX_KEY);
        ProviderOrderHelper.setProviderOrder(codex, orderedIds);

        configWriter.accept(config);
        LOG.info("[CodexProviderManager] Saved provider order: " + orderedIds);
    }

    /**
     * Get currently active Codex provider
     */
    public JsonObject getActiveCodexProvider() {
        JsonObject config = configReader.apply(null);

        if (!config.has(CODEX_KEY)) {
            return null;
        }

        JsonObject codex = config.getAsJsonObject(CODEX_KEY);
        if (!codex.has(CURRENT_KEY)) {
            return null;
        }

        String currentId = codex.get(CURRENT_KEY).getAsString();
        if (currentId == null || currentId.isEmpty()) {
            return null;
        }

        // Handle CLI Login virtual provider
        if (CODEX_CLI_LOGIN_PROVIDER_ID.equals(currentId)) {
            if (!isCodexCliLoginAuthorized(config)) {
                return null;
            }
            return createCodexCliLoginProviderObject(true);
        }

        if (!codex.has(PROVIDERS_KEY)) {
            return null;
        }

        JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);

        if (providers.has(currentId)) {
            JsonObject provider = providers.getAsJsonObject(currentId).deepCopy();
            if (!provider.has("id")) {
                provider.addProperty("id", currentId);
            }
            provider.addProperty("isActive", true);
            return provider;
        }

        return null;
    }

    /**
     * 按 providerId 读取 Codex provider 配置。
     * 该方法只做只读查询，不会修改 current 状态，供测试连接和运行时预览复用。
     *
     * @param providerId 目标 provider id
     * @return provider 深拷贝；不存在时返回 null
     */
    public JsonObject getCodexProviderById(String providerId) {
        if (providerId == null || providerId.trim().isEmpty()) {
            return null;
        }

        JsonObject config = configReader.apply(null);
        if (!config.has(CODEX_KEY) || !config.get(CODEX_KEY).isJsonObject()) {
            return null;
        }

        JsonObject codex = config.getAsJsonObject(CODEX_KEY);
        if (CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)) {
            if (!isCodexCliLoginAuthorized(config)) {
                return null;
            }
            return createCodexCliLoginProviderObject(false, true);
        }

        if (!codex.has(PROVIDERS_KEY) || !codex.get(PROVIDERS_KEY).isJsonObject()) {
            return null;
        }

        JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);
        if (!providers.has(providerId) || !providers.get(providerId).isJsonObject()) {
            return null;
        }

        JsonObject provider = providers.getAsJsonObject(providerId).deepCopy();
        if (!provider.has("id")) {
            provider.addProperty("id", providerId);
        }
        return provider;
    }

    /**
     * Add a new Codex provider
     */
    public void addCodexProvider(JsonObject provider) throws IOException {
        if (!provider.has("id")) {
            throw new IllegalArgumentException("Provider must have an id");
        }

        JsonObject config = configReader.apply(null);

        // Ensure codex configuration exists
        JsonObject codex = ensureCodexSection(config);
        JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);

        String id = provider.get("id").getAsString();

        // Check if ID already exists
        if (providers.has(id)) {
            throw new IllegalArgumentException("Provider with id '" + id + "' already exists");
        }

        // Add creation timestamp
        if (!provider.has("createdAt")) {
            provider.addProperty("createdAt", System.currentTimeMillis());
        }

        // Add provider (not auto-activated)
        providers.add(id, normalizeRuntimeProvider(provider));

        configWriter.accept(config);
        LOG.info("[CodexProviderManager] Added provider: " + id);
    }

    /**
     * Save provider (update if exists, add if not)
     */
    public void saveCodexProvider(JsonObject provider) throws IOException {
        if (!provider.has("id")) {
            throw new IllegalArgumentException("Provider must have an id");
        }

        JsonObject config = configReader.apply(null);

        // Ensure codex configuration exists
        JsonObject codex = ensureCodexSection(config);
        JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);

        String id = provider.get("id").getAsString();

        // Preserve createdAt if updating existing provider
        if (providers.has(id)) {
            JsonObject existing = providers.getAsJsonObject(id);
            if (existing.has("createdAt") && !provider.has("createdAt")) {
                provider.addProperty("createdAt", existing.get("createdAt").getAsLong());
            }
        } else {
            if (!provider.has("createdAt")) {
                provider.addProperty("createdAt", System.currentTimeMillis());
            }
        }

        providers.add(id, normalizeRuntimeProvider(provider));
        configWriter.accept(config);
    }

    /**
     * Update an existing Codex provider
     */
    public void updateCodexProvider(String id, JsonObject updates) throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has(CODEX_KEY)) {
            throw new IllegalArgumentException("No codex configuration found");
        }

        JsonObject codex = config.getAsJsonObject(CODEX_KEY);
        JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);

        if (!providers.has(id)) {
            throw new IllegalArgumentException("Provider with id '" + id + "' not found");
        }

        JsonObject provider = providers.getAsJsonObject(id);

        // Merge updates
        for (String key : updates.keySet()) {
            // Don't allow modifying id
            if (key.equals("id")) {
                continue;
            }

            // If value is null (JsonNull), remove the field
            if (updates.get(key).isJsonNull()) {
                provider.remove(key);
            } else {
                provider.add(key, updates.get(key));
            }
        }

        providers.add(id, normalizeRuntimeProvider(provider));
        configWriter.accept(config);
        LOG.info("[CodexProviderManager] Updated provider: " + id);
    }

    /**
     * Delete a Codex provider
     * @param id Provider ID
     * @return DeleteResult with operation status and error details
     */
    public DeleteResult deleteCodexProvider(String id) {
        Path configFilePath = null;
        Path backupFilePath = null;

        try {
            JsonObject config = configReader.apply(null);
            configFilePath = pathManager.getConfigFilePath();
            backupFilePath = pathManager.getConfigDir().resolve(BACKUP_FILE_NAME);

            if (!config.has("codex")) {
                return DeleteResult.failure(
                    DeleteResult.ErrorType.FILE_NOT_FOUND,
                    "No codex configuration found",
                    configFilePath.toString(),
                    "Please add at least one Codex provider first"
                );
            }

            JsonObject codex = config.getAsJsonObject("codex");
            JsonObject providers = codex.getAsJsonObject("providers");

            if (!providers.has(id)) {
                return DeleteResult.failure(
                    DeleteResult.ErrorType.FILE_NOT_FOUND,
                    "Provider with id '" + id + "' not found",
                    null,
                    "Please check if the provider ID is correct"
                );
            }

            // Create backup before deletion
            try {
                Files.copy(configFilePath, backupFilePath, StandardCopyOption.REPLACE_EXISTING);
                LOG.info("[CodexProviderManager] Created backup: " + backupFilePath);
            } catch (IOException e) {
                LOG.warn("[CodexProviderManager] Warning: Failed to create backup: " + e.getMessage());
            }

            // Delete provider
            providers.remove(id);

            // If deleting the active provider, switch to first available
            String currentId = codex.has("current") ? codex.get("current").getAsString() : null;
            if (id.equals(currentId)) {
                if (providers.size() > 0) {
                    String firstKey = providers.keySet().iterator().next();
                    codex.addProperty("current", firstKey);
                    LOG.info("[CodexProviderManager] Switched to provider: " + firstKey);
                } else {
                    codex.addProperty("current", "");
                    LOG.info("[CodexProviderManager] No remaining providers");
                }
            }

            // Remove deleted provider from providerOrder to avoid stale IDs
            ProviderOrderHelper.removeFromOrder(codex, id);

            // Write config
            configWriter.accept(config);
            LOG.info("[CodexProviderManager] Deleted provider: " + id);

            // Remove backup on success
            try {
                Files.deleteIfExists(backupFilePath);
            } catch (IOException e) {
                // Ignore backup deletion failure
            }

            return DeleteResult.success(id);

        } catch (Exception e) {
            // Try to restore from backup
            if (backupFilePath != null && configFilePath != null) {
                try {
                    if (Files.exists(backupFilePath)) {
                        Files.copy(backupFilePath, configFilePath, StandardCopyOption.REPLACE_EXISTING);
                        LOG.info("[CodexProviderManager] Restored from backup after failure");
                    }
                } catch (IOException restoreEx) {
                    LOG.warn("[CodexProviderManager] Failed to restore backup: " + restoreEx.getMessage());
                }
            }

            return DeleteResult.fromException(e, configFilePath != null ? configFilePath.toString() : null);
        }
    }

    /**
     * Switch to a different Codex provider
     */
    public void switchCodexProvider(String id) throws IOException {
        JsonObject config = configReader.apply(null);

        if (!config.has(CODEX_KEY)) {
            JsonObject codexSection = new JsonObject();
            codexSection.add(PROVIDERS_KEY, new JsonObject());
            codexSection.addProperty(CURRENT_KEY, "");
            config.add(CODEX_KEY, codexSection);
        }

        JsonObject codex = config.getAsJsonObject(CODEX_KEY);

        if (id == null || id.trim().isEmpty()) {
            codex.addProperty(CURRENT_KEY, "");
            configWriter.accept(config);
            LOG.info("[CodexProviderManager] Cleared active provider");
            return;
        }

        // CLI Login is a virtual provider — no need to check providers map
        if (!CODEX_CLI_LOGIN_PROVIDER_ID.equals(id)) {
            JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);
            if (providers == null || !providers.has(id)) {
                throw new IllegalArgumentException("Provider with id '" + id + "' not found");
            }
        }

        codex.addProperty(CURRENT_KEY, id);
        configWriter.accept(config);
        LOG.info("[CodexProviderManager] Switched to provider: " + id);
    }

    public JsonObject getSelectedModel() {
        JsonObject config = configReader.apply(null);
        if (!config.has(CODEX_KEY) || !config.get(CODEX_KEY).isJsonObject()) {
            return new JsonObject();
        }
        JsonObject codex = config.getAsJsonObject(CODEX_KEY);
        if (!codex.has(SELECTED_MODEL_KEY) || !codex.get(SELECTED_MODEL_KEY).isJsonObject()) {
            return new JsonObject();
        }
        return codex.getAsJsonObject(SELECTED_MODEL_KEY).deepCopy();
    }

    public void setSelectedModel(String providerId, String modelId) throws IOException {
        JsonObject config = configReader.apply(null);
        JsonObject codex = ensureCodexSection(config);
        String resolvedProviderId = safeTrim(providerId);
        if (resolvedProviderId.isEmpty() && codex.has(CURRENT_KEY) && !codex.get(CURRENT_KEY).isJsonNull()) {
            // 前端只传 modelId 时，使用当前 active provider 作为 selectedModel 归属。
            resolvedProviderId = safeTrim(codex.get(CURRENT_KEY).getAsString());
        }
        JsonObject selectedModel = new JsonObject();
        selectedModel.addProperty("providerId", resolvedProviderId);
        selectedModel.addProperty("modelId", safeTrim(modelId));
        codex.add(SELECTED_MODEL_KEY, selectedModel);
        configWriter.accept(config);
    }

    /**
     * 原子切换 Codex 当前运行时 provider 与 selectedModel。
     * 该方法用于聊天区统一模型目录的选择事件，确保一次操作内同时更新 `codex.current`
     * 和 `codex.selectedModel`，避免出现界面显示已切换 provider/model，
     * 但后端仍沿用旧 active provider 的不一致状态。
     *
     * @param providerId 目标 provider id
     * @param modelId 目标 model id
     * @throws IOException 配置写入失败时抛出
     */
    public void selectModel(String providerId, String modelId) throws IOException {
        String normalizedProviderId = safeTrim(providerId);
        String normalizedModelId = safeTrim(modelId);
        if (normalizedProviderId.isEmpty()) {
            throw new IllegalArgumentException("Provider id is required");
        }
        if (normalizedModelId.isEmpty()) {
            throw new IllegalArgumentException("Model id is required");
        }

        JsonObject config = configReader.apply(null);
        JsonObject codex = ensureCodexSection(config);

        if (CODEX_CLI_LOGIN_PROVIDER_ID.equals(normalizedProviderId)) {
            if (!isCodexCliLoginAuthorized(config)) {
                throw new IllegalStateException("Codex CLI login is not authorized");
            }
        } else {
            JsonObject providers = codex.getAsJsonObject(PROVIDERS_KEY);
            if (providers == null || !providers.has(normalizedProviderId) || !providers.get(normalizedProviderId).isJsonObject()) {
                throw new IllegalArgumentException("Provider with id '" + normalizedProviderId + "' not found");
            }

            JsonObject provider = providers.getAsJsonObject(normalizedProviderId);
            if (!providerContainsModel(provider, normalizedModelId)) {
                throw new IllegalArgumentException(
                        "Model '" + normalizedModelId + "' not found for provider '" + normalizedProviderId + "'"
                );
            }
        }

        codex.addProperty(CURRENT_KEY, normalizedProviderId);
        JsonObject selectedModel = new JsonObject();
        selectedModel.addProperty("providerId", normalizedProviderId);
        selectedModel.addProperty("modelId", normalizedModelId);
        codex.add(SELECTED_MODEL_KEY, selectedModel);
        configWriter.accept(config);
        LOG.info("[CodexProviderManager] Selected model atomically: provider=" + normalizedProviderId
                + ", model=" + normalizedModelId);
    }

    /**
     * Batch save providers
     * @param providers List of providers to save
     * @return Number of successfully saved providers
     */
    public int saveProviders(List<JsonObject> providers) throws IOException {
        int count = 0;
        for (JsonObject provider : providers) {
            try {
                saveCodexProvider(provider);
                count++;
            } catch (Exception e) {
                LOG.warn("Failed to save provider " + provider.get("id") + ": " + e.getMessage());
            }
        }
        return count;
    }

    /**
     * Apply active provider to ~/.codex/ settings files
     */
    public void applyActiveProviderToCodexSettings() throws IOException {
        JsonObject activeProvider = getActiveCodexProvider();
        if (activeProvider == null) {
            LOG.info("[CodexProviderManager] No active provider to sync to ~/.codex/");
            return;
        }
        codexSettingsManager.applyProviderToCodexSettings(activeProvider);
    }

    /**
     * Get current Codex CLI configuration (from ~/.codex/)
     */
    public JsonObject getCurrentCodexConfig() throws IOException {
        return codexSettingsManager.getCurrentCodexConfig();
    }

    private JsonObject ensureCodexSection(JsonObject config) {
        JsonObject codex;
        if (config.has(CODEX_KEY) && config.get(CODEX_KEY).isJsonObject()) {
            codex = config.getAsJsonObject(CODEX_KEY);
        } else {
            codex = new JsonObject();
            config.add(CODEX_KEY, codex);
        }
        if (!codex.has(PROVIDERS_KEY) || !codex.get(PROVIDERS_KEY).isJsonObject()) {
            codex.add(PROVIDERS_KEY, new JsonObject());
        }
        if (!codex.has(CURRENT_KEY)) {
            codex.addProperty(CURRENT_KEY, "");
        }
        return codex;
    }

    private JsonObject normalizeRuntimeProvider(JsonObject provider) {
        JsonObject normalized = provider.deepCopy();
        validateRequiredProviderText(normalized, "id");
        validateRequiredProviderText(normalized, "name");
        normalizeProviderMode(normalized, REQUEST_MODE_KEY, "codex_sdk", VALID_REQUEST_MODES);
        normalizeProviderMode(normalized, AUTH_MODE_KEY, "api_key_env", VALID_AUTH_MODES);
        normalizeModels(normalized);
        return normalized;
    }

    private void validateRequiredProviderText(JsonObject provider, String key) {
        if (!provider.has(key) || provider.get(key).isJsonNull() || safeTrim(provider.get(key).getAsString()).isEmpty()) {
            throw new IllegalArgumentException("Provider must have a non-empty " + key);
        }
    }

    private void normalizeProviderMode(JsonObject provider, String key, String defaultValue, Set<String> allowedValues) {
        String value = provider.has(key) && !provider.get(key).isJsonNull()
                ? safeTrim(provider.get(key).getAsString())
                : "";
        if (value.isEmpty()) {
            provider.addProperty(key, defaultValue);
            return;
        }
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException("Unsupported Codex provider " + key + ": " + value);
        }
    }

    private void normalizeModels(JsonObject provider) {
        if (provider.has(MODELS_KEY) && provider.get(MODELS_KEY).isJsonArray()) {
            provider.add(MODELS_KEY, sanitizeModels(provider.getAsJsonArray(MODELS_KEY)));
            return;
        }
        if (provider.has(CUSTOM_MODELS_KEY) && provider.get(CUSTOM_MODELS_KEY).isJsonArray()) {
            // 兼容历史 customModels，读取后统一落到 models 作为运行时 schema。
            provider.add(MODELS_KEY, sanitizeModels(provider.getAsJsonArray(CUSTOM_MODELS_KEY)));
        }
    }

    private JsonArray sanitizeModels(JsonArray sourceModels) {
        JsonArray result = new JsonArray();
        for (JsonElement element : sourceModels) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject model = element.getAsJsonObject().deepCopy();
            if (!model.has("id") || safeTrim(model.get("id").getAsString()).isEmpty()) {
                continue;
            }
            if (!model.has("label") || safeTrim(model.get("label").getAsString()).isEmpty()) {
                model.addProperty("label", model.get("id").getAsString());
            }
            result.add(model);
        }
        return result;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 判断 provider 是否声明了指定 model id。
     * 这里同时兼容当前主 schema 的 `models` 与历史 `customModels`，
     * 避免旧配置尚未迁移完成时阻断统一模型目录的选择事件。
     *
     * @param provider provider 配置
     * @param modelId 待校验的模型 id
     * @return 存在时返回 true，否则返回 false
     */
    private boolean providerContainsModel(JsonObject provider, String modelId) {
        JsonArray models = provider.has(MODELS_KEY) && provider.get(MODELS_KEY).isJsonArray()
                ? provider.getAsJsonArray(MODELS_KEY)
                : provider.has(CUSTOM_MODELS_KEY) && provider.get(CUSTOM_MODELS_KEY).isJsonArray()
                ? provider.getAsJsonArray(CUSTOM_MODELS_KEY)
                : null;
        if (models == null) {
            return false;
        }

        for (JsonElement modelElement : models) {
            if (modelElement == null || !modelElement.isJsonObject()) {
                continue;
            }
            JsonObject model = modelElement.getAsJsonObject();
            if (model.has("id") && !model.get("id").isJsonNull()
                    && modelId.equals(safeTrim(model.get("id").getAsString()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create virtual CLI Login provider object.
     * Unlike regular providers, this is not stored in config but generated dynamically.
     */
    private JsonObject createCodexCliLoginProviderObject(boolean isActive) {
        return createCodexCliLoginProviderObject(isActive, isActive);
    }

    /**
     * 创建虚拟的 Codex CLI Login provider 对象。
     * 该 provider 不落盘在 providers 列表内，而是根据授权状态动态注入，
     * 以便设置页同时区分“已授权”和“当前是否在用”两种语义。
     *
     * @param isActive 当前是否作为运行时 active provider 生效
     * @param isAuthorized 当前是否允许读取本地 `~/.codex` 配置
     * @return 供前端消费的虚拟 provider 描述
     */
    private JsonObject createCodexCliLoginProviderObject(boolean isActive, boolean isAuthorized) {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", CODEX_CLI_LOGIN_PROVIDER_ID);
        provider.addProperty("name", ClaudeCodeGuiBundle.message("provider.codexCliLogin.name"));
        provider.addProperty("isActive", isActive);
        provider.addProperty("isAuthorized", isAuthorized);
        provider.addProperty("isCodexCliLoginProvider", true);
        return provider;
    }

    /**
     * Check if the current active provider is Codex CLI Login.
     */
    public boolean isCodexCliLoginProviderActive() {
        try {
            JsonObject config = configReader.apply(null);
            if (!config.has("codex")) { return false; }
            JsonObject codex = config.getAsJsonObject("codex");
            if (!codex.has("current")) { return false; }
            return CODEX_CLI_LOGIN_PROVIDER_ID.equals(codex.get("current").getAsString())
                    && isCodexCliLoginAuthorized(config);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCodexCliLoginAuthorized(JsonObject config) {
        if (config == null || !config.has("codex") || !config.get("codex").isJsonObject()) {
            return false;
        }
        JsonObject codex = config.getAsJsonObject("codex");
        return codex.has("localConfigAuthorized")
                && !codex.get("localConfigAuthorized").isJsonNull()
                && codex.get("localConfigAuthorized").getAsBoolean();
    }
}
