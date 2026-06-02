package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.model.DeleteResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.List;
import java.util.Objects;

/**
 * Handles Codex provider CRUD operations and switching.
 */
public class CodexProviderOperations {

    private static final Logger LOG = Logger.getInstance(CodexProviderOperations.class);
    private static final Gson GSON = new Gson();

    private final HandlerContext context;

    public CodexProviderOperations(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get all Codex providers
     */
    public void handleGetCodexProviders() {
        try {
            List<JsonObject> providers = context.getSettingsService().getCodexProviders();
            String providersJson = GSON.toJson(providers);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.updateCodexProviders", context.escapeJs(providersJson));
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get Codex providers: " + e.getMessage(), e);
        }
    }

    /**
     * Get current Codex CLI configuration (~/.codex/)
     */
    public void handleGetCurrentCodexConfig() {
        try {
            JsonObject config = context.getSettingsService().getCurrentCodexConfig();
            String configJson = GSON.toJson(config);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.updateCurrentCodexConfig", context.escapeJs(configJson));
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get current Codex config: " + e.getMessage(), e);
        }
    }

    /**
     * Add Codex provider
     */
    public void handleAddCodexProvider(String content) {
        try {
            JsonObject provider = GSON.fromJson(content, JsonObject.class);
            context.getSettingsService().addCodexProvider(provider);

            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetCodexProviders(); // Refresh list
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to add Codex provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showError", context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("provider.addCodexFailed", e.getMessage())));
            });
        }
    }

    /**
     * Update Codex provider
     */
    public void handleUpdateCodexProvider(String content) {
        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            JsonObject updates = data.getAsJsonObject("updates");

            context.getSettingsService().updateCodexProvider(id, updates);

            boolean refreshedActiveProvider = false;
            JsonObject activeProvider = context.getSettingsService().getActiveCodexProvider();
            if (activeProvider != null &&
                        activeProvider.has("id") &&
                        id.equals(activeProvider.get("id").getAsString())) {
                // Active provider 更新只影响 CC-GUI runtime，下次请求会重新解析 profile，不再默认写 ~/.codex。
                refreshedActiveProvider = true;
            }

            final boolean finalRefreshed = refreshedActiveProvider;
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetCodexProviders(); // Refresh list
                if (finalRefreshed) {
                    handleGetActiveCodexProvider(); // Refresh active provider config
                }
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to update Codex provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showError", context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("provider.updateCodexFailed", e.getMessage())));
            });
        }
    }

    /**
     * Delete Codex provider
     */
    public void handleDeleteCodexProvider(String content) {
        LOG.debug("[ProviderHandler] ========== handleDeleteCodexProvider START ==========");
        LOG.debug("[ProviderHandler] Received content: " + content);

        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            LOG.debug("[ProviderHandler] Parsed JSON data: " + data);

            if (!data.has("id")) {
                LOG.error("[ProviderHandler] ERROR: Missing 'id' field in request");
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.callJavaScript("window.showError", context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("provider.deleteCodexMissingId")));
                });
                return;
            }

            String id = data.get("id").getAsString();
            LOG.info("[ProviderHandler] Deleting Codex provider with ID: " + id);

            DeleteResult result = context.getSettingsService().deleteCodexProvider(id);
            LOG.debug("[ProviderHandler] Delete result - success: " + result.isSuccess());

            if (result.isSuccess()) {
                LOG.info("[ProviderHandler] Delete successful, refreshing provider list");
                ApplicationManager.getApplication().invokeLater(() -> {
                    handleGetCodexProviders(); // Refresh list
                });
            } else {
                String errorMsg = result.getUserFriendlyMessage();
                LOG.warn("[ProviderHandler] Delete Codex provider failed: " + errorMsg);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.callJavaScript("window.showError", context.escapeJs(errorMsg));
                });
            }
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Exception in handleDeleteCodexProvider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showError", context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("provider.deleteCodexFailed", e.getMessage())));
            });
        }

        LOG.debug("[ProviderHandler] ========== handleDeleteCodexProvider END ==========");
    }

    /**
     * Switch Codex provider
     */
    public void handleSwitchCodexProvider(String content) {
        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();

            // Handle Codex CLI Login virtual provider
            if (com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(id)) {
                handleSwitchToCodexCliLogin();
                return;
            }

            context.getSettingsService().switchCodexProvider(id);

            ApplicationManager.getApplication().invokeLater(() -> {
                // 切换只更新 ~/.codemoss/config.json，避免把 IDE 内 runtime provider 导出到 Codex live config。
                String successMsg = com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchSuccess");
                context.callJavaScript("window.showSwitchSuccess", context.escapeJs(successMsg));
                handleGetCodexProviders(); // Refresh provider list
                handleGetActiveCodexProvider(); // Refresh active provider config
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to switch Codex provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showError", context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchFailed") + ": " + e.getMessage()));
            });
        }
    }

    /**
     * Revoke local Codex config authorization and stop reading ~/.codex/{config.toml,auth.json}.
     */
    public void handleRevokeCodexLocalConfigAuthorization(String content) {
        try {
            JsonObject data = content == null || content.isBlank()
                    ? new JsonObject()
                    : GSON.fromJson(content, JsonObject.class);
            String fallbackProviderId = data != null && data.has("fallbackProviderId")
                    && !data.get("fallbackProviderId").isJsonNull()
                    ? data.get("fallbackProviderId").getAsString()
                    : "";

            JsonObject activeProvider = context.getSettingsService().getActiveCodexProvider();
            boolean wasCliLoginActive = activeProvider != null
                    && activeProvider.has("id")
                    && com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID
                    .equals(activeProvider.get("id").getAsString());

            context.getSettingsService().setCodexLocalConfigAuthorized(false);

            if (wasCliLoginActive) {
                // 撤销 CLI Login 后只切回 CC-GUI managed provider，不再把 fallback 写入 ~/.codex。
                context.getSettingsService().switchCodexProvider(fallbackProviderId);
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showSwitchSuccess", context.escapeJs(
                        com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.codexLocalConfigAuthorizationRevoked")));
                handleGetCodexProviders();
                handleGetCurrentCodexConfig();
                handleGetActiveCodexProvider();
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to revoke Codex local config authorization: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showError", context.escapeJs(
                        com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchFailed") + ": " + e.getMessage()));
            });
        }
    }

    /**
     * Handle switching to authorized local Codex config mode.
     * This grants read access to ~/.codex/config.toml and auth.json without modifying them.
     */
    private void handleSwitchToCodexCliLogin() {
        try {
            context.getSettingsService().setCodexLocalConfigAuthorized(true);

            // Update config.json to set CLI login as current
            context.getSettingsService().switchCodexProvider(
                    com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);

            LOG.info("[ProviderHandler] Authorized local Codex config provider");

            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showSwitchSuccess", context.escapeJs(
                        com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.codexCliLoginSwitchSuccess")));
                handleGetCodexProviders();
                handleGetCurrentCodexConfig();
                handleGetActiveCodexProvider();
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to switch to Codex CLI login: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.showError", context.escapeJs(
                        com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchFailed") + ": " + e.getMessage()));
            });
        }
    }

    /**
     * Get currently active Codex provider
     */
    public void handleGetActiveCodexProvider() {
        try {
            JsonObject provider = context.getSettingsService().getActiveCodexProvider();
            String providerJson = GSON.toJson(provider);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.updateActiveCodexProvider", context.escapeJs(providerJson));
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get active Codex provider: " + e.getMessage(), e);
        }
    }

    /**
     * 只验证 provider 的运行时必要字段，不发起真实模型请求。
     */
    public void handleTestCodexProvider(String content) {
        try {
            JsonObject data = content == null || content.isBlank()
                    ? new JsonObject()
                    : GSON.fromJson(content, JsonObject.class);
            String providerId = data != null && data.has("id") && !data.get("id").isJsonNull()
                    ? data.get("id").getAsString()
                    : "";
            if (providerId.isBlank()) {
                throw new IllegalArgumentException("Missing provider id");
            }

            List<JsonObject> providers = context.getSettingsService().getCodexProviders();
            JsonObject targetProvider = providers.stream()
                    .filter(Objects::nonNull)
                    .filter(provider -> provider.has("id") && providerId.equals(provider.get("id").getAsString()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

            String requestMode = targetProvider.has("requestMode") && !targetProvider.get("requestMode").isJsonNull()
                    ? targetProvider.get("requestMode").getAsString()
                    : "codex_sdk";
            String authMode = targetProvider.has("authMode") && !targetProvider.get("authMode").isJsonNull()
                    ? targetProvider.get("authMode").getAsString()
                    : "api_key_env";
            boolean hasModels = targetProvider.has("models")
                    && targetProvider.get("models").isJsonArray()
                    && targetProvider.getAsJsonArray("models").size() > 0;
            boolean hasBaseUrl = targetProvider.has("baseUrl")
                    && !targetProvider.get("baseUrl").isJsonNull()
                    && !targetProvider.get("baseUrl").getAsString().trim().isEmpty();
            boolean hasApiKey = targetProvider.has("apiKey")
                    && !targetProvider.get("apiKey").isJsonNull()
                    && !targetProvider.get("apiKey").getAsString().trim().isEmpty();
            boolean hasApiKeyEnv = targetProvider.has("apiKeyEnv")
                    && !targetProvider.get("apiKeyEnv").isJsonNull()
                    && !targetProvider.get("apiKeyEnv").getAsString().trim().isEmpty();

            if (!hasModels && !com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)) {
                throw new IllegalStateException("No model configured for provider");
            }
            if (!"codex_cli_login".equals(authMode) && !hasApiKey && !hasApiKeyEnv) {
                throw new IllegalStateException("No credential source configured");
            }

            String credentialSource = hasApiKeyEnv ? "env" : hasApiKey ? "local" : "cli_login";
            StringBuilder message = new StringBuilder("Codex provider check passed");
            message.append(": requestMode=").append(requestMode);
            message.append(", authMode=").append(authMode);
            message.append(", hasModels=").append(hasModels);
            message.append(", hasBaseUrl=").append(hasBaseUrl);
            message.append(", credentialSource=").append(credentialSource);
            if (!"codex_sdk".equals(requestMode)) {
                message.append(", note=request mode reserved for proxy or adapter");
            }

            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showSwitchSuccess", context.escapeJs(message.toString())));
        } catch (Exception e) {
            LOG.warn("[ProviderHandler] Codex provider check failed: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("Codex provider check failed: " + e.getMessage())));
        }
    }
}
