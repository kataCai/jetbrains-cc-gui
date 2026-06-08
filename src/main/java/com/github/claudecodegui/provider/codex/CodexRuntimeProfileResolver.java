package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.CodexProviderManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.function.Function;

/**
 * Codex 请求级运行配置解析器。
 * 解析结果只服务于单次发送，避免通过写入 ~/.codex 来切换 CC-GUI 内部 provider。
 */
public class CodexRuntimeProfileResolver {
    private static final String DEFAULT_REQUEST_MODE = "codex_sdk";
    private static final String DEFAULT_REASONING_EFFORT = "medium";

    private final CodemossSettingsService settingsService;
    private final Function<String, String> environmentReader;

    public CodexRuntimeProfileResolver(CodemossSettingsService settingsService, Function<String, String> environmentReader) {
        this.settingsService = settingsService;
        this.environmentReader = environmentReader;
    }

    public CodexRuntimeProfile resolve(String sessionModel, String sessionReasoningEffort) throws IOException {
        JsonObject activeProvider = settingsService.getActiveCodexProvider();
        if (activeProvider == null || activeProvider.size() == 0) {
            throw new IllegalStateException("No active Codex provider configured");
        }

        return resolveForProvider(activeProvider, sessionModel, sessionReasoningEffort);
    }

    /**
     * 基于指定 provider 解析单次请求的 runtime profile。
     * 该入口供测试连接和发送前预览复用，避免依赖当前 active provider。
     *
     * @param provider 目标 provider 配置
     * @param sessionModel 会话显式指定模型
     * @param sessionReasoningEffort 会话显式指定推理强度
     * @return 解析后的请求级 profile
     * @throws IOException 读取本地 Codex 默认配置失败时抛出
     */
    public CodexRuntimeProfile resolveForProvider(
            JsonObject provider,
            String sessionModel,
            String sessionReasoningEffort
    ) throws IOException {
        JsonObject activeProvider = provider;
        if (activeProvider == null || activeProvider.size() == 0) {
            throw new IllegalStateException("No Codex provider configured");
        }

        String providerId = readString(activeProvider, "id");
        JsonObject localCodexState = settingsService.getCurrentCodexModelState();
        String localCodexModelProvider = readString(localCodexState, "modelProvider");
        if (CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)
                || activeProvider.has("isCodexCliLoginProvider")) {
            return resolveCliLogin(providerId, sessionModel, sessionReasoningEffort, localCodexModelProvider);
        }

        String model = firstNonBlank(
                sessionModel,
                readSelectedModel(providerId),
                readFirstModelId(activeProvider)
        );
        if (model.isEmpty()) {
            throw new IllegalStateException("No Codex model configured for provider: " + providerId);
        }

        // ~/.codex/config.toml 里的 model / reasoningEffort 仅用于 GUI 展示当前本地 CLI 状态，
        // 不能覆盖 CC-GUI 托管 provider 自身的选中模型与模型级推理强度。
        String reasoningEffort = firstNonBlank(
                sessionReasoningEffort,
                readModelReasoningEffort(activeProvider, model),
                readString(activeProvider, "reasoningEffort"),
                DEFAULT_REASONING_EFFORT
        );
        String authMode = firstNonBlank(readString(activeProvider, "authMode"), "api_key_env");
        String requestMode = firstNonBlank(readString(activeProvider, "requestMode"), DEFAULT_REQUEST_MODE);
        Credential credential = resolveCredential(activeProvider, authMode);
        String providerBaseUrl = readString(activeProvider, "baseUrl");
        // 托管 provider 的 endpoint 只能来自当前 provider 自身配置；
        // 若未配置，则显式回退到 SDK 默认值，禁止再透传 ~/.codex/config.toml 中的本地 endpoint。
        String resolvedBaseUrl = providerBaseUrl;
        String baseUrlSource = !providerBaseUrl.isEmpty()
                ? "provider"
                : "sdk_default";
        boolean fallbackDetected = !"provider".equals(baseUrlSource);
        String forcedModelProvider = CodexRuntimeProfile.MANAGED_PROVIDER_FORCED_MODEL_PROVIDER;
        boolean localConfigConflictDetected = !localCodexModelProvider.isEmpty()
                && !forcedModelProvider.equalsIgnoreCase(localCodexModelProvider);

        return new CodexRuntimeProfile(
                providerId,
                model,
                resolvedBaseUrl,
                credential.apiKey,
                authMode,
                requestMode,
                reasoningEffort,
                credential.source,
                baseUrlSource,
                CodexRuntimeProfile.CONFIG_SOURCE_MANAGED_PROVIDER,
                fallbackDetected,
                forcedModelProvider,
                localCodexModelProvider,
                localConfigConflictDetected,
                forcedModelProvider
        );
    }

    /**
     * 解析 Codex CLI Login 模式的请求级 profile。
     * CLI Login 语义下底层本就应继续遵循本地 ~/.codex 配置，因此这里保留本地 model_provider 诊断值，
     * 同时显式关闭 managed provider 冲突标记，避免设置页把合法的本地直连误报成风险。
     *
     * @param providerId CLI Login 虚拟 provider id
     * @param sessionModel 会话级显式模型
     * @param sessionReasoningEffort 会话级显式推理强度
     * @param localCodexModelProvider 本地 ~/.codex/config.toml 中声明的 model_provider
     * @return CLI Login 模式下的请求级 profile
     */
    private CodexRuntimeProfile resolveCliLogin(
            String providerId,
            String sessionModel,
            String sessionReasoningEffort,
            String localCodexModelProvider
    ) {
        return new CodexRuntimeProfile(
                providerId,
                firstNonBlank(sessionModel, readSelectedModel(providerId)),
                "",
                "",
                CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN,
                DEFAULT_REQUEST_MODE,
                firstNonBlank(sessionReasoningEffort, DEFAULT_REASONING_EFFORT),
                "codex_cli_login",
                "codex_cli_login",
                CodexRuntimeProfile.CONFIG_SOURCE_CLI_LOGIN,
                false,
                "",
                localCodexModelProvider,
                false,
                localCodexModelProvider
        );
    }

    private Credential resolveCredential(JsonObject provider, String authMode) {
        if (CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN.equals(authMode)) {
            return new Credential("", "codex_cli_login");
        }
        String apiKey = readString(provider, "apiKey");
        if (!apiKey.isEmpty()) {
            return new Credential(apiKey, "apiKey");
        }
        String apiKeyEnv = readString(provider, "apiKeyEnv");
        if (!apiKeyEnv.isEmpty()) {
            String envValue = environmentReader.apply(apiKeyEnv);
            if (envValue == null || envValue.trim().isEmpty()) {
                throw new IllegalStateException("Codex provider API key env is not set: " + apiKeyEnv);
            }
            return new Credential(envValue.trim(), "apiKeyEnv:" + apiKeyEnv);
        }
        if ("api_key".equals(authMode) || "api_key_env".equals(authMode)) {
            throw new IllegalStateException("Codex provider API key is not configured");
        }
        return new Credential("", authMode);
    }

    private String readSelectedModel(String providerId) {
        JsonObject selected = settingsService.getSelectedCodexModel();
        if (selected == null || selected.size() == 0) {
            return "";
        }
        String selectedProviderId = readString(selected, "providerId");
        if (!selectedProviderId.isEmpty() && !selectedProviderId.equals(providerId)) {
            return "";
        }
        return readString(selected, "modelId");
    }

    private String readFirstModelId(JsonObject provider) {
        JsonArray models = readModels(provider);
        if (models.size() == 0) {
            return "";
        }
        return readString(models.get(0).getAsJsonObject(), "id");
    }

    private String readModelReasoningEffort(JsonObject provider, String modelId) {
        JsonArray models = readModels(provider);
        for (int i = 0; i < models.size(); i++) {
            JsonObject model = models.get(i).getAsJsonObject();
            if (modelId.equals(readString(model, "id"))) {
                return readString(model, "reasoningEffort");
            }
        }
        return "";
    }

    private JsonArray readModels(JsonObject provider) {
        if (provider.has("models") && provider.get("models").isJsonArray()) {
            return provider.getAsJsonArray("models");
        }
        if (provider.has("customModels") && provider.get("customModels").isJsonArray()) {
            return provider.getAsJsonArray("customModels");
        }
        return new JsonArray();
    }

    private String readString(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString().trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    private static class Credential {
        private final String apiKey;
        private final String source;

        Credential(String apiKey, String source) {
            this.apiKey = apiKey;
            this.source = source;
        }
    }
}
