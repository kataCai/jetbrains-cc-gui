package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfileResolver;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;

import com.github.claudecodegui.model.DeleteResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

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
     * 测试指定 Codex provider 的真实请求链路。
     * 这里会基于目标 provider 临时解析 runtime profile，并发起一次最小真实请求；
     * 整个过程只读，不切换当前 active provider，也不会落盘修改本地配置。
     *
     * @param content 前端传入的 JSON，至少包含 provider id
     * @return 无返回值；结果通过设置页独立 toast 回调反馈
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
            JsonObject targetProvider = context.getSettingsService().getCodexProviderById(providerId);
            if (targetProvider == null || targetProvider.size() == 0) {
                throw new IllegalArgumentException("Provider not found: " + providerId);
            }

            CodexRuntimeProfile runtimeProfile = new CodexRuntimeProfileResolver(
                    context.getSettingsService(),
                    System::getenv
            ).resolveForProvider(targetProvider, "", "");

            String runtimeSummary = buildRuntimeProfileSummary(runtimeProfile);
            CompletableFuture<SDKResult> testFuture = context.getCodexSDKBridge().sendMessage(
                    "codex-provider-test-" + providerId,
                    "Reply with OK only.",
                    "codex-provider-test-thread-" + UUID.randomUUID(),
                    context.getProject() != null && context.getProject().getBasePath() != null
                            ? context.getProject().getBasePath()
                            : "",
                    Collections.emptyList(),
                    "bypassPermissions",
                    runtimeProfile.getModel(),
                    "",
                    runtimeProfile.getReasoningEffort(),
                    runtimeProfile,
                    createSilentTestCallback()
            );

            testFuture.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    LOG.warn("[ProviderHandler] Codex provider live test failed: " + throwable.getMessage(), throwable);
                    showTestResult(false, "Codex provider test failed: " + throwable.getMessage());
                    return;
                }

                if (result == null || !result.success) {
                    String errorMessage = result != null && result.error != null && !result.error.trim().isEmpty()
                            ? result.error
                            : "Unknown error";
                    LOG.warn("[ProviderHandler] Codex provider live test failed: " + errorMessage);
                    showTestResult(false, "Codex provider test failed: " + errorMessage);
                    return;
                }

                showTestResult(true, "Codex provider test passed: " + runtimeSummary);
            });
        } catch (Exception e) {
            LOG.warn("[ProviderHandler] Codex provider check failed: " + e.getMessage(), e);
            showTestResult(false, "Codex provider test failed: " + e.getMessage());
        }
    }

    /**
     * 构造测试连接结果摘要，明确展示最终生效的 runtime profile。
     *
     * @param runtimeProfile 已解析的请求级 profile
     * @return 供 toast 展示的摘要字符串
     */
    private String buildRuntimeProfileSummary(CodexRuntimeProfile runtimeProfile) {
        StringBuilder summary = new StringBuilder();
        summary.append("model=").append(runtimeProfile.getModel());
        summary.append(", baseUrl=").append(runtimeProfile.getBaseUrl().isEmpty() ? "<default>" : runtimeProfile.getBaseUrl());
        summary.append(", authMode=").append(runtimeProfile.getAuthMode());
        summary.append(", requestMode=").append(runtimeProfile.getRequestMode());
        summary.append(", credentialSource=").append(runtimeProfile.getCredentialSource());
        return summary.toString();
    }

    /**
     * 为 provider 测试构造静默回调，只记录最终错误，不把测试流式消息注入聊天窗口。
     *
     * @return 测试专用消息回调
     */
    private MessageCallback createSilentTestCallback() {
        AtomicReference<String> lastError = new AtomicReference<>("");
        return new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                // 设置页测试只关心最终结果，不展示流式消息。
            }

            @Override
            public void onError(String error) {
                lastError.set(error == null ? "" : error);
            }

            @Override
            public void onComplete(SDKResult result) {
                if (result != null && (result.error == null || result.error.trim().isEmpty())) {
                    result.error = lastError.get();
                }
            }
        };
    }

    /**
     * 统一通过设置页专用测试结果回调展示 provider 测试结果。
     *
     * @param success 是否测试成功
     * @param message 展示消息
     * @return 无返回值
     */
    protected void showTestResult(boolean success, String message) {
        Runnable action = () -> context.callJavaScript(
                "window.showTestResult",
                success ? "true" : "false",
                context.escapeJs(message)
        );
        if (ApplicationManager.getApplication() == null) {
            action.run();
            return;
        }
        ApplicationManager.getApplication().invokeLater(action);
    }
}
