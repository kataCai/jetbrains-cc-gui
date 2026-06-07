package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfileResolver;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Codex provider 相关操作处理器。
 * 该处理器负责设置页中 Codex provider 的增删改查、启用切换、本地 CLI Login 授权切换，
 * 以及“测试连接”这类只读探测操作。
 * 这里的关键约束是：
 * 1. 测试连接必须基于被测 provider 临时解析运行时 profile，不能污染当前 active provider。
 * 2. 返回前端的测试结果必须包含结构化运行时摘要，便于识别是否 fallback 到本地配置。
 * 3. 所有设置页回调都通过统一 JS bridge 返回，避免前后端协议散落在多处。
 */
public class CodexProviderOperations {

    private static final Logger LOG = Logger.getInstance(CodexProviderOperations.class);
    private static final Gson GSON = new Gson();

    private final HandlerContext context;

    /**
     * 创建 Codex provider 操作处理器。
     *
     * @param context 当前处理请求所需的上下文，包含设置服务、SDK bridge 与 JS 回调能力
     */
    public CodexProviderOperations(HandlerContext context) {
        this.context = context;
    }

    /**
     * 获取全部 Codex providers，并回传给设置页。
     */
    public void handleGetCodexProviders() {
        try {
            List<JsonObject> providers = context.getSettingsService().getCodexProviders();
            String providersJson = GSON.toJson(providers);

            invokeLaterOrRun(() ->
                    context.callJavaScript("window.updateCodexProviders", context.escapeJs(providersJson)));
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get Codex providers: " + e.getMessage(), e);
        }
    }

    /**
     * 获取当前本地 `~/.codex` 配置镜像，并回传给设置页。
     */
    public void handleGetCurrentCodexConfig() {
        try {
            JsonObject config = context.getSettingsService().getCurrentCodexConfig();
            String configJson = GSON.toJson(config);

            invokeLaterOrRun(() ->
                    context.callJavaScript("window.updateCurrentCodexConfig", context.escapeJs(configJson)));
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get current Codex config: " + e.getMessage(), e);
        }
    }

    /**
     * 新增 Codex provider，并在成功后刷新 provider 列表。
     *
     * @param content 前端传入的 provider JSON
     */
    public void handleAddCodexProvider(String content) {
        try {
            JsonObject provider = GSON.fromJson(content, JsonObject.class);
            context.getSettingsService().addCodexProvider(provider);

            ApplicationManager.getApplication().invokeLater(this::handleGetCodexProviders);
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to add Codex provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript(
                            "window.showError",
                            context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message(
                                    "provider.addCodexFailed",
                                    e.getMessage()
                            ))
                    ));
        }
    }

    /**
     * 更新指定 Codex provider。
     * 如果被更新的正好是当前 active provider，则一并刷新 active provider 摘要。
     *
     * @param content 前端传入的更新请求 JSON
     */
    public void handleUpdateCodexProvider(String content) {
        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();
            JsonObject updates = data.getAsJsonObject("updates");

            context.getSettingsService().updateCodexProvider(id, updates);

            boolean refreshedActiveProvider = false;
            JsonObject activeProvider = context.getSettingsService().getActiveCodexProvider();
            if (activeProvider != null
                    && activeProvider.has("id")
                    && id.equals(activeProvider.get("id").getAsString())) {
                // Active provider 更新只影响 CC-GUI runtime；下次请求会重新解析 profile，
                // 不再把 IDE 中的 provider 写回 ~/.codex。
                refreshedActiveProvider = true;
            }

            final boolean finalRefreshed = refreshedActiveProvider;
            ApplicationManager.getApplication().invokeLater(() -> {
                handleGetCodexProviders();
                if (finalRefreshed) {
                    handleGetActiveCodexProvider();
                }
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to update Codex provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript(
                            "window.showError",
                            context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message(
                                    "provider.updateCodexFailed",
                                    e.getMessage()
                            ))
                    ));
        }
    }

    /**
     * 删除指定 Codex provider。
     *
     * @param content 前端传入的删除请求 JSON
     */
    public void handleDeleteCodexProvider(String content) {
        LOG.debug("[ProviderHandler] ========== handleDeleteCodexProvider START ==========");
        LOG.debug("[ProviderHandler] Received content: " + content);

        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            LOG.debug("[ProviderHandler] Parsed JSON data: " + data);

            if (!data.has("id")) {
                LOG.error("[ProviderHandler] ERROR: Missing 'id' field in request");
                ApplicationManager.getApplication().invokeLater(() ->
                        context.callJavaScript(
                                "window.showError",
                                context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message(
                                        "provider.deleteCodexMissingId"
                                ))
                        ));
                return;
            }

            String id = data.get("id").getAsString();
            LOG.info("[ProviderHandler] Deleting Codex provider with ID: " + id);

            DeleteResult result = context.getSettingsService().deleteCodexProvider(id);
            LOG.debug("[ProviderHandler] Delete result - success: " + result.isSuccess());

            if (result.isSuccess()) {
                LOG.info("[ProviderHandler] Delete successful, refreshing provider list");
                ApplicationManager.getApplication().invokeLater(this::handleGetCodexProviders);
            } else {
                String errorMsg = result.getUserFriendlyMessage();
                LOG.warn("[ProviderHandler] Delete Codex provider failed: " + errorMsg);
                ApplicationManager.getApplication().invokeLater(() ->
                        context.callJavaScript("window.showError", context.escapeJs(errorMsg)));
            }
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Exception in handleDeleteCodexProvider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript(
                            "window.showError",
                            context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message(
                                    "provider.deleteCodexFailed",
                                    e.getMessage()
                            ))
                    ));
        }

        LOG.debug("[ProviderHandler] ========== handleDeleteCodexProvider END ==========");
    }

    /**
     * 切换当前 active Codex provider。
     *
     * @param content 前端传入的切换请求 JSON
     */
    public void handleSwitchCodexProvider(String content) {
        try {
            JsonObject data = GSON.fromJson(content, JsonObject.class);
            String id = data.get("id").getAsString();

            if (com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(id)) {
                handleSwitchToCodexCliLogin();
                return;
            }

            context.getSettingsService().switchCodexProvider(id);

            ApplicationManager.getApplication().invokeLater(() -> {
                // 切换只更新 ~/.codemoss/config.json，避免把 IDE 里的 runtime provider 导出到 Codex live config。
                String successMsg = com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchSuccess");
                context.callJavaScript("window.showSwitchSuccess", context.escapeJs(successMsg));
                handleGetCodexProviders();
                handleGetActiveCodexProvider();
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to switch Codex provider: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript(
                            "window.showError",
                            context.escapeJs(
                                    com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchFailed")
                                            + ": " + e.getMessage()
                            )
                    ));
        }
    }

    /**
     * 仅授权读取本地 Codex CLI 配置，不直接假定前端一定要通过旧的切换 provider 入口触发。
     * 该桥接用于设置页“先授权、再决定是否切换使用”的拆分交互，成功后只刷新 provider/config/active-provider 摘要。
     *
     * @param content 预留给未来扩展的 JSON 载荷；当前实现不依赖具体字段
     */
    public void handleAuthorizeCodexLocalConfig(String content) {
        try {
            context.getSettingsService().setCodexLocalConfigAuthorized(true);

            JsonObject activeProvider = context.getSettingsService().getActiveCodexProvider();
            boolean shouldActivateCliLogin = activeProvider == null
                    || !activeProvider.has("id")
                    || activeProvider.get("id").isJsonNull()
                    || activeProvider.get("id").getAsString().isBlank();
            if (shouldActivateCliLogin) {
                context.getSettingsService().switchCodexProvider(
                        com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID
                );
            }

            LOG.info("[ProviderHandler] Authorized local Codex config access");

            invokeLaterOrRun(() -> {
                context.callJavaScript(
                        "window.showSwitchSuccess",
                        context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message(
                                "toast.codexCliLoginSwitchSuccess"
                        ))
                );
                handleGetCodexProviders();
                handleGetCurrentCodexConfig();
                handleGetActiveCodexProvider();
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to authorize Codex local config access: " + e.getMessage(), e);
            invokeLaterOrRun(() ->
                    context.callJavaScript(
                            "window.showError",
                            context.escapeJs(
                                    com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchFailed")
                                            + ": " + e.getMessage()
                            )
                    ));
        }
    }

    /**
     * 撤销本地 Codex 配置授权，并停止读取 `~/.codex/{config.toml,auth.json}`。
     *
     * @param content 前端传入的撤销授权请求 JSON
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

            invokeLaterOrRun(() -> {
                context.callJavaScript(
                        "window.showSwitchSuccess",
                        context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message(
                                "toast.codexLocalConfigAuthorizationRevoked"
                        ))
                );
                handleGetCodexProviders();
                handleGetCurrentCodexConfig();
                handleGetActiveCodexProvider();
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to revoke Codex local config authorization: " + e.getMessage(), e);
            invokeLaterOrRun(() ->
                    context.callJavaScript(
                            "window.showError",
                            context.escapeJs(
                                    com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchFailed")
                                            + ": " + e.getMessage()
                            )
                    ));
        }
    }

    /**
     * 切换到本地 Codex CLI Login 模式。
     * 该模式只授权读取 `~/.codex/config.toml` 与 `auth.json`，不会修改它们。
     */
    private void handleSwitchToCodexCliLogin() {
        try {
            context.getSettingsService().setCodexLocalConfigAuthorized(true);
            context.getSettingsService().switchCodexProvider(
                    com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID
            );

            LOG.info("[ProviderHandler] Authorized local Codex config provider");

            invokeLaterOrRun(() -> {
                context.callJavaScript(
                        "window.showSwitchSuccess",
                        context.escapeJs(com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message(
                                "toast.codexCliLoginSwitchSuccess"
                        ))
                );
                handleGetCodexProviders();
                handleGetCurrentCodexConfig();
                handleGetActiveCodexProvider();
            });
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to switch to Codex CLI login: " + e.getMessage(), e);
            invokeLaterOrRun(() ->
                    context.callJavaScript(
                            "window.showError",
                            context.escapeJs(
                                    com.github.claudecodegui.i18n.ClaudeCodeGuiBundle.message("toast.providerSwitchFailed")
                                            + ": " + e.getMessage()
                            )
                    ));
        }
    }

    /**
     * 获取当前 active Codex provider，并回传给设置页。
     */
    public void handleGetActiveCodexProvider() {
        try {
            JsonObject provider = context.getSettingsService().getActiveCodexProvider();
            JsonObject payload = enrichActiveProviderWithRuntimeDiagnostics(provider);
            String providerJson = GSON.toJson(payload);
            Runnable action = () ->
                    context.callJavaScript("window.updateActiveCodexProvider", context.escapeJs(providerJson));
            if (ApplicationManager.getApplication() == null) {
                // 单元测试环境里可能没有 IDE Application 实例，此时直接同步回调，避免因为 invokeLater 缺失而吞掉诊断结果。
                action.run();
                return;
            }
            ApplicationManager.getApplication().invokeLater(action);
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to get active Codex provider: " + e.getMessage(), e);
        }
    }

    /**
     * 为 active provider 附加一份“轻量运行时来源摘要”。
     * 这里故意不走完整发送链路，也不强依赖 API Key/环境变量必须可用；
     * 目标只是让设置页和聊天区能看到“当前大概率会命中谁、是否发生 fallback”的诊断结果，
     * 避免用户只能通过抓包判断当前请求到底走了托管 provider、本地配置还是 SDK 默认值。
     *
     * @param provider 当前激活的 provider 原始配置
     * @return 合并运行时来源摘要后的 provider；若 provider 为空则直接返回原值
     */
    private JsonObject enrichActiveProviderWithRuntimeDiagnostics(JsonObject provider) {
        if (provider == null || provider.size() == 0) {
            return provider;
        }

        JsonObject enrichedProvider = provider.deepCopy();
        String providerId = readString(enrichedProvider, "id");
        JsonObject localCodexState = readCurrentCodexModelStateSafely();
        String localCodexModelProvider = readString(localCodexState, "modelProvider");
        if (com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)
                || enrichedProvider.has("isCodexCliLoginProvider")) {
            enrichedProvider.addProperty("effectiveConfigSource", CodexRuntimeProfile.CONFIG_SOURCE_CLI_LOGIN);
            enrichedProvider.addProperty("endpointSource", "codex_cli_login");
            enrichedProvider.addProperty("fallbackDetected", false);
            enrichedProvider.addProperty("forcedModelProvider", "");
            enrichedProvider.addProperty("localCodexModelProvider", localCodexModelProvider);
            enrichedProvider.addProperty("localConfigConflictDetected", false);
            enrichedProvider.addProperty("finalModelProvider", localCodexModelProvider);
            return enrichedProvider;
        }

        String providerBaseUrl = readString(enrichedProvider, "baseUrl");
        // 设置页的轻量诊断必须与真实发送链路保持一致：
        // 托管 provider 只允许显示 provider 自身 endpoint 或 SDK 默认值，
        // 不能再把 ~/.codex/config.toml 中的本地 endpoint 伪装成当前 provider 的运行时来源。
        String endpointSource = !providerBaseUrl.isEmpty()
                ? "provider"
                : "sdk_default";
        boolean fallbackDetected = !"provider".equals(endpointSource);
        String forcedModelProvider = CodexRuntimeProfile.MANAGED_PROVIDER_FORCED_MODEL_PROVIDER;
        boolean localConfigConflictDetected = !localCodexModelProvider.isEmpty()
                && !forcedModelProvider.equalsIgnoreCase(localCodexModelProvider);

        enrichedProvider.addProperty("effectiveConfigSource", CodexRuntimeProfile.CONFIG_SOURCE_MANAGED_PROVIDER);
        enrichedProvider.addProperty("endpointSource", endpointSource);
        enrichedProvider.addProperty("fallbackDetected", fallbackDetected);
        enrichedProvider.addProperty("forcedModelProvider", forcedModelProvider);
        enrichedProvider.addProperty("localCodexModelProvider", localCodexModelProvider);
        enrichedProvider.addProperty("localConfigConflictDetected", localConfigConflictDetected);
        enrichedProvider.addProperty("finalModelProvider", forcedModelProvider);
        return enrichedProvider;
    }

    /**
     * 在 IDE Application 可用时切回 EDT；单元测试缺少 Application 时直接同步执行，避免因为 invokeLater 不可用而吞掉回调。
     *
     * @param action 需要执行的 UI/bridge 回调逻辑
     */
    private void invokeLaterOrRun(Runnable action) {
        if (ApplicationManager.getApplication() == null) {
            action.run();
            return;
        }
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any());
    }

    /**
     * 测试指定 Codex provider 的真实请求链路。
     * 这里会基于目标 provider 临时解析运行时 profile，并发起一次最小真实请求；
     * 整个过程只读，不切换当前 active provider，也不会落盘修改本地配置。
     *
     * @param content 前端传入的 JSON，请至少包含 provider id
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
                    showTestResult(buildTestResultPayload(
                            false,
                            providerId,
                            targetProvider,
                            runtimeProfile,
                            "Codex provider test failed: " + throwable.getMessage()
                    ));
                    return;
                }

                if (result == null || !result.success) {
                    String errorMessage = result != null && result.error != null && !result.error.trim().isEmpty()
                            ? result.error
                            : "Unknown error";
                    LOG.warn("[ProviderHandler] Codex provider live test failed: " + errorMessage);
                    showTestResult(buildTestResultPayload(
                            false,
                            providerId,
                            targetProvider,
                            runtimeProfile,
                            "Codex provider test failed: " + errorMessage
                    ));
                    return;
                }

                showTestResult(buildTestResultPayload(
                        true,
                        providerId,
                        targetProvider,
                        runtimeProfile,
                        "Codex provider test passed: " + runtimeSummary
                ));
            });
        } catch (Exception e) {
            LOG.warn("[ProviderHandler] Codex provider check failed: " + e.getMessage(), e);
            showTestResult(buildTestResultPayload(
                    false,
                    "",
                    null,
                    null,
                    "Codex provider test failed: " + e.getMessage()
            ));
        }
    }

    /**
     * 构造测试连接提示中展示的运行时摘要。
     * 这里只输出用户排查最关心的命中结果，避免把完整配置对象直接暴露到提示文案里。
     *
     * @param runtimeProfile 已解析完成的请求级运行时 profile
     * @return 适合展示在成功提示中的摘要字符串
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
     * 为 provider 测试构造静默回调。
     * 设置页测试连接只关心最终结果，不应把探测请求的流式消息写进聊天窗口，
     * 因此这里只记录最终错误，并在 `onComplete` 阶段回填到 `SDKResult`。
     *
     * @return 测试连接专用的消息回调
     */
    private MessageCallback createSilentTestCallback() {
        AtomicReference<String> lastError = new AtomicReference<>("");
        return new MessageCallback() {
            @Override
            public void onMessage(String type, String content) {
                // 设置页测试连接只关心最终结果，不展示流式中间消息。
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
     * 基于 provider 与运行时 profile 组装结构化测试结果。
     * 这里优先使用运行时 profile，因为它代表真正送入桥接层的请求参数；
     * 当 profile 尚未解析成功时，再回退到 provider 原始字段，避免前端完全拿不到上下文。
     *
     * @param success 本次测试是否成功
     * @param requestedProviderId 前端请求测试的 provider id
     * @param provider 被测试的 provider 原始配置；解析失败时允许为 null
     * @param runtimeProfile 请求级运行时 profile；解析失败时允许为 null
     * @param message 展示给前端的结果消息
     * @return 可直接序列化并回传前端的 JSON payload
     */
    private JsonObject buildTestResultPayload(
            boolean success,
            String requestedProviderId,
            JsonObject provider,
            CodexRuntimeProfile runtimeProfile,
            String message
    ) {
        JsonObject payload = new JsonObject();
        String resolvedProviderId = runtimeProfile != null && !runtimeProfile.getProviderId().isEmpty()
                ? runtimeProfile.getProviderId()
                : firstNonBlank(requestedProviderId, readString(provider, "id"));
        String resolvedRequestMode = runtimeProfile != null && !runtimeProfile.getRequestMode().isEmpty()
                ? runtimeProfile.getRequestMode()
                : firstNonBlank(readString(provider, "requestMode"), "codex_sdk");
        String resolvedModel = runtimeProfile != null && !runtimeProfile.getModel().isEmpty()
                ? runtimeProfile.getModel()
                : readFirstModelId(provider);
        String resolvedBaseUrl = runtimeProfile != null
                ? runtimeProfile.getBaseUrl()
                : readString(provider, "baseUrl");
        String resolvedCredentialSource = runtimeProfile != null && !runtimeProfile.getCredentialSource().isEmpty()
                ? runtimeProfile.getCredentialSource()
                : inferCredentialSource(provider);
        String resolvedAuthMode = runtimeProfile != null && !runtimeProfile.getAuthMode().isEmpty()
                ? runtimeProfile.getAuthMode()
                : readString(provider, "authMode");
        String resolvedConfigSource = runtimeProfile != null && !runtimeProfile.getEffectiveConfigSource().isEmpty()
                ? runtimeProfile.getEffectiveConfigSource()
                : CodexRuntimeProfile.CONFIG_SOURCE_MANAGED_PROVIDER;
        String resolvedEndpointSource = runtimeProfile != null && !runtimeProfile.getBaseUrlSource().isEmpty()
                ? runtimeProfile.getBaseUrlSource()
                : (resolvedBaseUrl.isEmpty() ? "sdk_default" : "provider");

        payload.addProperty("success", success);
        payload.addProperty("providerId", resolvedProviderId);
        payload.addProperty("requestMode", resolvedRequestMode);
        payload.addProperty("model", resolvedModel);
        payload.addProperty("resolvedBaseUrl", resolvedBaseUrl);
        payload.addProperty("credentialSource", resolvedCredentialSource);
        payload.addProperty("transport", resolvedRequestMode);
        payload.addProperty("effectiveConfigSource", resolvedConfigSource);
        payload.addProperty("fallbackDetected", runtimeProfile != null && runtimeProfile.isFallbackDetected());
        payload.addProperty("authMode", resolvedAuthMode);
        payload.addProperty("endpointSource", resolvedEndpointSource);
        payload.addProperty("forcedModelProvider", runtimeProfile != null ? runtimeProfile.getForcedModelProvider() : "");
        payload.addProperty("localCodexModelProvider", runtimeProfile != null ? runtimeProfile.getLocalCodexModelProvider() : "");
        payload.addProperty(
                "localConfigConflictDetected",
                runtimeProfile != null && runtimeProfile.isLocalConfigConflictDetected()
        );
        payload.addProperty("finalModelProvider", runtimeProfile != null ? runtimeProfile.getFinalModelProvider() : "");
        payload.addProperty("message", message == null ? "" : message);
        return payload;
    }

    /**
     * 安全读取当前本地 Codex 诊断态。
     * 该读取只服务于 UI 风险提示，读取失败时应回退为空对象，避免因为诊断链路异常影响 provider 测试或设置页主流程。
     *
     * @return 当前本地 Codex 诊断态；读取失败时返回空对象
     */
    private JsonObject readCurrentCodexModelStateSafely() {
        try {
            JsonObject state = context.getSettingsService().getCurrentCodexModelState();
            return state == null ? new JsonObject() : state;
        } catch (Exception exception) {
            LOG.warn("[ProviderHandler] Failed to read current Codex model state for diagnostics: " + exception.getMessage());
            return new JsonObject();
        }
    }

    /**
     * 统一通过设置页专用回调展示 provider 测试结果。
     * 前端现在只接收一个结构化 JSON payload，避免旧版 `(success, message)` 协议无法携带运行时诊断字段。
     *
     * @param payload 结构化测试结果
     */
    protected void showTestResult(JsonObject payload) {
        String payloadJson = GSON.toJson(payload);
        Runnable action = () -> context.callJavaScript(
                "window.showTestResult",
                context.escapeJs(payloadJson)
        );
        if (ApplicationManager.getApplication() == null) {
            action.run();
            return;
        }
        ApplicationManager.getApplication().invokeLater(action);
    }

    /**
     * 读取 provider 中声明的第一个模型 id。
     *
     * @param provider provider 原始配置
     * @return 首个模型 id；不存在时返回空串
     */
    private String readFirstModelId(JsonObject provider) {
        if (provider == null || !provider.has("models") || !provider.get("models").isJsonArray()) {
            return "";
        }
        if (provider.getAsJsonArray("models").size() == 0) {
            return "";
        }

        JsonElement firstModel = provider.getAsJsonArray("models").get(0);
        if (firstModel == null || !firstModel.isJsonObject()) {
            return "";
        }
        return readString(firstModel.getAsJsonObject(), "id");
    }

    /**
     * 根据 provider 原始字段推断脱敏后的凭据来源描述。
     *
     * @param provider provider 原始配置
     * @return 脱敏后的凭据来源字符串
     */
    private String inferCredentialSource(JsonObject provider) {
        String apiKeyEnv = readString(provider, "apiKeyEnv");
        if (!apiKeyEnv.isEmpty()) {
            return "apiKeyEnv:" + apiKeyEnv;
        }
        if (!readString(provider, "apiKey").isEmpty()) {
            return "apiKey:inline";
        }
        return "";
    }

    /**
     * 安全读取 JSON 对象中的字符串字段。
     *
     * @param object JSON 对象；允许为 null
     * @param fieldName 字段名
     * @return 去除首尾空白后的字符串；字段不存在时返回空串
     */
    private String readString(JsonObject object, String fieldName) {
        if (object == null || fieldName == null || fieldName.isBlank()) {
            return "";
        }
        if (!object.has(fieldName) || object.get(fieldName).isJsonNull()) {
            return "";
        }
        return object.get(fieldName).getAsString().trim();
    }

    /**
     * 返回第一个非空字符串。
     *
     * @param candidates 候选字符串列表
     * @return 第一个非空字符串；全部为空时返回空串
     */
    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.trim().isEmpty()) {
                return candidate.trim();
            }
        }
        return "";
    }
}
