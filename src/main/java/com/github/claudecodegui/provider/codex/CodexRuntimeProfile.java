package com.github.claudecodegui.provider.codex;

import com.google.gson.JsonObject;

/**
 * Codex 单次请求运行配置。
 * 该对象把 provider/model/baseUrl/apiKey 从全局 bridge 状态中剥离，避免跨请求串用凭据。
 */
public class CodexRuntimeProfile {
    public static final String AUTH_MODE_CLI_LOGIN = "codex_cli_login";
    public static final String CONFIG_SOURCE_MANAGED_PROVIDER = "codemoss_managed_provider";
    public static final String CONFIG_SOURCE_CLI_LOGIN = "codex_cli_login";
    public static final String CONFIG_SOURCE_LEGACY_BRIDGE = "legacy_bridge";
    public static final String MANAGED_PROVIDER_FORCED_MODEL_PROVIDER = "codemoss_managed_provider";

    private final String providerId;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final String authMode;
    private final String requestMode;
    private final String reasoningEffort;
    private final String credentialSource;
    private final String baseUrlSource;
    private final String effectiveConfigSource;
    private final boolean fallbackDetected;
    private final String forcedModelProvider;
    private final String localCodexModelProvider;
    private final boolean localConfigConflictDetected;
    private final String finalModelProvider;

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
        this(
                providerId,
                model,
                baseUrl,
                apiKey,
                authMode,
                requestMode,
                reasoningEffort,
                credentialSource,
                baseUrl == null || baseUrl.trim().isEmpty() ? "sdk_default" : "provider",
                AUTH_MODE_CLI_LOGIN.equals(authMode) ? CONFIG_SOURCE_CLI_LOGIN : CONFIG_SOURCE_MANAGED_PROVIDER,
                false,
                AUTH_MODE_CLI_LOGIN.equals(authMode) ? "" : MANAGED_PROVIDER_FORCED_MODEL_PROVIDER,
                "",
                false,
                AUTH_MODE_CLI_LOGIN.equals(authMode) ? "" : MANAGED_PROVIDER_FORCED_MODEL_PROVIDER
        );
    }

    public CodexRuntimeProfile(
            String providerId,
            String model,
            String baseUrl,
            String apiKey,
            String authMode,
            String requestMode,
            String reasoningEffort,
            String credentialSource,
            String baseUrlSource,
            String effectiveConfigSource,
            boolean fallbackDetected,
            String forcedModelProvider,
            String localCodexModelProvider,
            boolean localConfigConflictDetected,
            String finalModelProvider
    ) {
        this.providerId = safe(providerId);
        this.model = safe(model);
        this.baseUrl = safe(baseUrl);
        this.apiKey = safe(apiKey);
        this.authMode = safe(authMode);
        this.requestMode = safe(requestMode);
        this.reasoningEffort = safe(reasoningEffort);
        this.credentialSource = safe(credentialSource);
        this.baseUrlSource = safe(baseUrlSource);
        this.effectiveConfigSource = safe(effectiveConfigSource);
        this.fallbackDetected = fallbackDetected;
        this.forcedModelProvider = safe(forcedModelProvider);
        this.localCodexModelProvider = safe(localCodexModelProvider);
        this.localConfigConflictDetected = localConfigConflictDetected;
        this.finalModelProvider = safe(finalModelProvider);
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
                apiKey == null || apiKey.trim().isEmpty() ? "native" : "legacy",
                baseUrl == null || baseUrl.trim().isEmpty() ? "sdk_default" : "legacy_runtime_profile",
                CONFIG_SOURCE_LEGACY_BRIDGE,
                baseUrl == null || baseUrl.trim().isEmpty(),
                apiKey == null || apiKey.trim().isEmpty() ? "" : MANAGED_PROVIDER_FORCED_MODEL_PROVIDER,
                "",
                false,
                apiKey == null || apiKey.trim().isEmpty() ? "" : MANAGED_PROVIDER_FORCED_MODEL_PROVIDER
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

    public String getBaseUrlSource() {
        return baseUrlSource;
    }

    public String getEffectiveConfigSource() {
        return effectiveConfigSource;
    }

    public boolean isFallbackDetected() {
        return fallbackDetected;
    }

    /**
     * 返回本次请求由 GUI/bridge 显式注入给底层 CLI 的 model_provider。
     * 托管 provider 场景下该值用于表达“GUI 命中了哪个 provider 路由”，
     * CLI Login 模式则保持为空，避免把本地直连误判为 managed provider 冲突。
     *
     * @return 请求级强制注入的 model_provider；不存在时返回空串
     */
    public String getForcedModelProvider() {
        return forcedModelProvider;
    }

    /**
     * 返回本地 ~/.codex/config.toml 中声明的 model_provider 诊断值。
     * 该字段只用于风险可视化，不参与本次请求参数组装，避免读取诊断信息后反向污染运行时行为。
     *
     * @return 本地 Codex 配置声明的 model_provider；未声明时返回空串
     */
    public String getLocalCodexModelProvider() {
        return localCodexModelProvider;
    }

    /**
     * 判断当前请求是否存在“GUI 已命中托管 provider，但本地 CLI 配置仍声明了其他 model_provider”的风险。
     * 该判断只在 managed provider 模式下成立；CLI Login 模式必须显式返回 false，避免误报。
     *
     * @return true 表示建议在 UI 中展示本地配置干扰风险；false 表示当前语义下无冲突
     */
    public boolean isLocalConfigConflictDetected() {
        return localConfigConflictDetected;
    }

    /**
     * 返回底层 CLI 最终应生效的 model_provider 诊断值。
     * 托管 provider 场景下优先展示请求级强制值；CLI Login 模式则回退到本地配置值，便于区分两类来源。
     *
     * @return 诊断语义下的最终 model_provider
     */
    public String getFinalModelProvider() {
        return finalModelProvider;
    }

    public boolean isCodexCliLogin() {
        return AUTH_MODE_CLI_LOGIN.equals(authMode);
    }

    /**
     * 构造运行时诊断摘要。
     * 这里明确只输出可安全暴露的来源信息与布尔状态，不输出原始 apiKey，避免日志泄漏凭据。
     *
     * @return 供 Java/Node 统一记录的诊断 JSON
     */
    public JsonObject toDiagnosticJson() {
        JsonObject diagnostic = new JsonObject();
        diagnostic.addProperty("providerId", providerId);
        diagnostic.addProperty("model", model);
        diagnostic.addProperty("resolvedBaseUrl", baseUrl);
        diagnostic.addProperty("authMode", authMode);
        diagnostic.addProperty("requestMode", requestMode);
        diagnostic.addProperty("reasoningEffort", reasoningEffort);
        diagnostic.addProperty("hasBaseUrl", !baseUrl.isEmpty());
        diagnostic.addProperty("hasApiKey", !apiKey.isEmpty());
        diagnostic.addProperty("credentialSource", credentialSource);
        diagnostic.addProperty("baseUrlSource", baseUrlSource);
        diagnostic.addProperty("effectiveConfigSource", effectiveConfigSource);
        diagnostic.addProperty("fallbackDetected", fallbackDetected);
        diagnostic.addProperty("forcedModelProvider", forcedModelProvider);
        diagnostic.addProperty("localCodexModelProvider", localCodexModelProvider);
        diagnostic.addProperty("localConfigConflictDetected", localConfigConflictDetected);
        diagnostic.addProperty("finalModelProvider", finalModelProvider);
        return diagnostic;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
