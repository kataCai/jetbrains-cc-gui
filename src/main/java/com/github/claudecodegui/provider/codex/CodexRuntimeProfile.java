package com.github.claudecodegui.provider.codex;

import com.google.gson.JsonObject;

/**
 * Codex 单次请求运行配置。
 * 该对象把 provider/model/baseUrl/apiKey 从全局 bridge 状态中剥离，避免跨请求串用凭据。
 */
public class CodexRuntimeProfile {
    public static final String AUTH_MODE_CLI_LOGIN = "codex_cli_login";

    private final String providerId;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final String authMode;
    private final String requestMode;
    private final String reasoningEffort;
    private final String credentialSource;

    public CodexRuntimeProfile(
            String providerId,
            String model,
            String baseUrl,
            String apiKey,
            String authMode,
            String requestMode,
            String reasoningEffort,
            String credentialSource
    ) {
        this.providerId = safe(providerId);
        this.model = safe(model);
        this.baseUrl = safe(baseUrl);
        this.apiKey = safe(apiKey);
        this.authMode = safe(authMode);
        this.requestMode = safe(requestMode);
        this.reasoningEffort = safe(reasoningEffort);
        this.credentialSource = safe(credentialSource);
    }

    public static CodexRuntimeProfile legacy(String model, String baseUrl, String apiKey, String reasoningEffort) {
        return new CodexRuntimeProfile(
                "",
                model,
                baseUrl,
                apiKey,
                apiKey == null || apiKey.trim().isEmpty() ? AUTH_MODE_CLI_LOGIN : "api_key",
                "codex_sdk",
                reasoningEffort,
                apiKey == null || apiKey.trim().isEmpty() ? "native" : "legacy"
        );
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModel() {
        return model;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getAuthMode() {
        return authMode;
    }

    public String getRequestMode() {
        return requestMode;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public String getCredentialSource() {
        return credentialSource;
    }

    public boolean isCodexCliLogin() {
        return AUTH_MODE_CLI_LOGIN.equals(authMode);
    }

    public JsonObject toDiagnosticJson() {
        JsonObject diagnostic = new JsonObject();
        diagnostic.addProperty("providerId", providerId);
        diagnostic.addProperty("model", model);
        diagnostic.addProperty("authMode", authMode);
        diagnostic.addProperty("requestMode", requestMode);
        diagnostic.addProperty("reasoningEffort", reasoningEffort);
        diagnostic.addProperty("hasBaseUrl", !baseUrl.isEmpty());
        diagnostic.addProperty("hasApiKey", !apiKey.isEmpty());
        diagnostic.addProperty("credentialSource", credentialSource);
        return diagnostic;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
