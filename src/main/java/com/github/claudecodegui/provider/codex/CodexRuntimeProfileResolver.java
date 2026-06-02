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

        String providerId = readString(activeProvider, "id");
        if (CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)
                || activeProvider.has("isCodexCliLoginProvider")) {
            return resolveCliLogin(providerId, sessionModel, sessionReasoningEffort);
        }

        String model = firstNonBlank(sessionModel, readSelectedModel(providerId), readFirstModelId(activeProvider));
        if (model.isEmpty()) {
            throw new IllegalStateException("No Codex model configured for provider: " + providerId);
        }

        String reasoningEffort = firstNonBlank(
                sessionReasoningEffort,
                readModelReasoningEffort(activeProvider, model),
                readString(activeProvider, "reasoningEffort"),
                DEFAULT_REASONING_EFFORT
        );
        String authMode = firstNonBlank(readString(activeProvider, "authMode"), "api_key_env");
        String requestMode = firstNonBlank(readString(activeProvider, "requestMode"), DEFAULT_REQUEST_MODE);
        Credential credential = resolveCredential(activeProvider, authMode);

        return new CodexRuntimeProfile(
                providerId,
                model,
                readString(activeProvider, "baseUrl"),
                credential.apiKey,
                authMode,
                requestMode,
                reasoningEffort,
                credential.source
        );
    }

    private CodexRuntimeProfile resolveCliLogin(String providerId, String sessionModel, String sessionReasoningEffort) {
        return new CodexRuntimeProfile(
                providerId,
                firstNonBlank(sessionModel, readSelectedModel(providerId)),
                "",
                "",
                CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN,
                DEFAULT_REQUEST_MODE,
                firstNonBlank(sessionReasoningEffort, DEFAULT_REASONING_EFFORT),
                "codex_cli_login"
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
