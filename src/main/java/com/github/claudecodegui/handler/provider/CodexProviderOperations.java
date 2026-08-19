package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexLocalModelSyncService;
import com.github.claudecodegui.provider.codex.CodexProviderModelDiscoveryService;
import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfileResolver;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
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
    private static final String TEST_STAGE_MODEL_DISCOVERY = "model_discovery";
    private static final String TEST_STAGE_SDK_MESSAGE = "sdk_message";
    private static final String TEST_STAGE_RUNTIME_PROFILE = "runtime_profile";
    private static final String DEFAULT_REQUEST_MODE = "codex_sdk";
    private static final String AUTH_MODE_API_KEY = "api_key";
    private static final String AUTH_MODE_API_KEY_ENV = "api_key_env";
    private static final String EMPTY_MODEL_DISCOVERY_UNSUPPORTED_MESSAGE =
            "Codex provider test failed: current authMode/requestMode does not support model discovery for empty-model providers.";

    private final HandlerContext context;
    private final CodexProviderModelDiscoveryService codexProviderModelDiscoveryService;
    private final CodexLocalModelSyncService codexLocalModelSyncService;

    /**
     * 创建 Codex provider 操作处理器。
     *
     * @param context 当前处理请求所需的上下文，包含设置服务、SDK bridge 与 JS 回调能力
     */
    public CodexProviderOperations(HandlerContext context) {
        this(
                context,
                new CodexProviderModelDiscoveryService(context.getSettingsService()),
                new CodexLocalModelSyncService(context.getSettingsService())
        );
    }

    /**
     * 创建可注入模型发现服务的 Codex provider 操作处理器。
     * 该入口主要服务于单元测试，让编排层可以在不发起真实网络请求的前提下验证成功/失败回调逻辑。
     *
     * @param context 当前处理请求所需的上下文，包含设置服务、SDK bridge 与 JS 回调能力
     * @param codexProviderModelDiscoveryService 远端模型发现服务
     */
    CodexProviderOperations(
            HandlerContext context,
            CodexProviderModelDiscoveryService codexProviderModelDiscoveryService,
            CodexLocalModelSyncService codexLocalModelSyncService
    ) {
        this.context = context;
        this.codexProviderModelDiscoveryService = codexProviderModelDiscoveryService;
        this.codexLocalModelSyncService = codexLocalModelSyncService;
    }

    /**
     * 创建仅注入远端模型发现服务的测试入口。
     * 为保持既有单元测试的构造方式稳定，这里在未显式传入本地同步服务时自动补默认实现。
     *
     * @param context 当前处理请求所需的上下文
     * @param codexProviderModelDiscoveryService 远端模型发现服务
     */
    CodexProviderOperations(
            HandlerContext context,
            CodexProviderModelDiscoveryService codexProviderModelDiscoveryService
    ) {
        this(
                context,
                codexProviderModelDiscoveryService,
                new CodexLocalModelSyncService(context.getSettingsService())
        );
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
            invokeLaterOrRun(action);
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
        com.intellij.openapi.application.Application application = ApplicationManager.getApplication();
        // 单元测试环境通常存在 Application，但不会自动帮我们冲刷 invokeLater 队列；
        // 这里与项目内其他 handler 保持一致，测试时直接同步执行，避免诊断/测试结果回调被延后导致断言读到空值。
        if (application == null || application.isDisposed() || application.isUnitTestMode()) {
            action.run();
            return;
        }
        application.invokeLater(action, ModalityState.any());
    }

    /**
     * 测试指定 Codex provider 的连通性。
     * 该方法按模型是否已配置拆成两个阶段：
     * 1. 空模型时只做端点与凭据测试，复用 `/v1/models` 发现链路，不调用 Codex SDK 发送消息。
     * 2. 已有模型时保持完整 SDK 消息测试。
     * 整个过程只读，不切换当前 active provider，也不会落盘修改本地配置。
     *
     * @param content 前端传入的 JSON，请至少包含 provider id
     */
    public void handleTestCodexProvider(String content) {
        String providerId = "";
        JsonObject targetProvider = null;
        String testStage = TEST_STAGE_RUNTIME_PROFILE;
        try {
            JsonObject data = content == null || content.isBlank()
                    ? new JsonObject()
                    : GSON.fromJson(content, JsonObject.class);
            String resolvedProviderId = data != null && data.has("id") && !data.get("id").isJsonNull()
                    ? data.get("id").getAsString()
                    : "";
            if (resolvedProviderId.isBlank()) {
                throw new IllegalArgumentException("Missing provider id");
            }
            providerId = resolvedProviderId;

            JsonObject resolvedProvider = context.getSettingsService().getCodexProviderById(resolvedProviderId);
            if (resolvedProvider == null || resolvedProvider.size() == 0) {
                throw new IllegalArgumentException("Provider not found: " + resolvedProviderId);
            }
            targetProvider = resolvedProvider;

            boolean requiresModel = !hasUsableCodexModel(resolvedProvider);
            boolean canFetchModels = canFetchCodexModels(resolvedProvider);
            if (requiresModel) {
                testStage = TEST_STAGE_MODEL_DISCOVERY;
                showEmptyModelProviderTestResult(resolvedProviderId, resolvedProvider, canFetchModels);
                return;
            }

            CodexRuntimeProfile runtimeProfile = new CodexRuntimeProfileResolver(
                    context.getSettingsService(),
                    System::getenv
            ).resolveForProvider(resolvedProvider, "", "");
            testStage = TEST_STAGE_SDK_MESSAGE;

            String runtimeSummary = buildRuntimeProfileSummary(runtimeProfile);
            CompletableFuture<SDKResult> testFuture = context.getCodexSDKBridge().sendMessage(
                    "codex-provider-test-" + resolvedProviderId,
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
                            resolvedProviderId,
                            resolvedProvider,
                            runtimeProfile,
                            "Codex provider test failed: " + throwable.getMessage(),
                            TEST_STAGE_SDK_MESSAGE,
                            false,
                            canFetchModels
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
                            resolvedProviderId,
                            resolvedProvider,
                            runtimeProfile,
                            "Codex provider test failed: " + errorMessage,
                            TEST_STAGE_SDK_MESSAGE,
                            false,
                            canFetchModels
                    ));
                    return;
                }

                showTestResult(buildTestResultPayload(
                        true,
                        resolvedProviderId,
                        resolvedProvider,
                        runtimeProfile,
                        "Codex provider test passed: " + runtimeSummary,
                        TEST_STAGE_SDK_MESSAGE,
                        false,
                        canFetchModels
                ));
            });
        } catch (Exception e) {
            LOG.warn("[ProviderHandler] Codex provider check failed: " + e.getMessage(), e);
            showTestResult(buildTestResultPayload(
                    false,
                    providerId,
                    targetProvider,
                    null,
                    "Codex provider test failed: " + e.getMessage(),
                    testStage,
                    targetProvider == null || !hasUsableCodexModel(targetProvider),
                    canFetchCodexModels(targetProvider)
            ));
        }
    }

    /**
     * 拉取指定 Codex provider 支持的远端模型列表，并按同 provider 内去重策略合并回本地配置。
     * 该链路只负责设置页的“获取模型列表”动作，不切换 active provider，也不直接操作统一模型目录；
     * 统一目录刷新继续复用现有 `updateCodexProviders -> loadCodexModelCatalog` 回调链路。
     *
     * @param content 前端传入的 JSON，请至少包含 provider id
     */
    public void handleFetchCodexProviderModels(String content) {
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

            if (com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)) {
                handleFetchLocalCodexConfigModels();
                return;
            }

            JsonObject targetProvider = context.getSettingsService().getCodexProviderById(providerId);
            if (targetProvider == null || targetProvider.size() == 0) {
                throw new IllegalArgumentException("Provider not found: " + providerId);
            }

            CodexProviderModelDiscoveryService.DiscoveryResult discoveryResult =
                    codexProviderModelDiscoveryService.discoverModels(targetProvider);
            com.github.claudecodegui.settings.CodexProviderManager.CodexProviderModelMergeResult mergeResult =
                    context.getSettingsService().mergeCodexProviderModels(providerId, discoveryResult.getModelIds());
            String providerName = firstNonBlank(readString(targetProvider, "name"), providerId);
            String successMessage = buildFetchCodexProviderModelsSuccessMessage(providerName, mergeResult);

            // 成功后同时刷新 provider 列表与统一模型目录：
            // Models 面板消费 catalog，不能只依赖 provider 列表的间接刷新链路。
            invokeLaterOrRun(() -> {
                context.callJavaScript("window.showSuccess", context.escapeJs(successMessage));
                if (mergeResult.getAddedCount() > 0) {
                    handleGetCodexProviders();
                }
                handleGetCodexModelCatalog();
            });
        } catch (Exception exception) {
            LOG.warn("[ProviderHandler] Failed to fetch Codex provider models: " + exception.getMessage(), exception);
            invokeLaterOrRun(() ->
                    context.callJavaScript("window.showError", context.escapeJs(exception.getMessage())));
        }
    }

    /**
     * 基于前端编辑弹窗传入的当前 provider 草稿发现模型。
     * 该流程不按 provider id 回读旧配置，不调用持久化 merge，也不刷新 provider 列表或统一模型目录；
     * 成功后只通过专用 JS 回调返回模型 ID 和发现统计，供弹窗本地追加缺失模型。
     *
     * @param content 当前编辑弹窗草稿 JSON，必须包含 providerId、authMode、requestMode 及连接凭据
     */
    public void handleFetchCodexProviderModelsFromDraft(String content) {
        try {
            JsonObject request = content == null || content.isBlank()
                    ? new JsonObject()
                    : GSON.fromJson(content, JsonObject.class);
            String providerId = readString(request, "providerId");
            if (providerId.isBlank()) {
                throw new IllegalArgumentException("Missing provider id for draft model discovery");
            }

            JsonObject draftProvider = request.deepCopy();
            draftProvider.remove("providerId");
            CodexProviderModelDiscoveryService.DiscoveryResult discoveryResult =
                    codexProviderModelDiscoveryService.discoverModels(draftProvider);

            JsonObject resultPayload = new JsonObject();
            resultPayload.addProperty("providerId", providerId);
            resultPayload.add("modelIds", GSON.toJsonTree(discoveryResult.getModelIds()));
            resultPayload.addProperty("duplicateCount", discoveryResult.getDuplicateCount());
            resultPayload.addProperty("skippedCount", discoveryResult.getSkippedCount());

            JsonObject successParams = new JsonObject();
            successParams.addProperty("providerName", firstNonBlank(readString(draftProvider, "name"), providerId));
            successParams.addProperty("fetchedCount", discoveryResult.getModelIds().size());
            successParams.addProperty("duplicateCount", discoveryResult.getDuplicateCount());
            successParams.addProperty("invalidCount", discoveryResult.getSkippedCount());
            String successMessage = buildI18nSuccessPayload(
                    "settings.codexProvider.fetchModelsResult.draftFetched",
                    successParams
            );

            invokeLaterOrRun(() -> {
                context.callJavaScript(
                        "window.onCodexProviderDraftModelsFetched",
                        context.escapeJs(resultPayload.toString())
                );
                context.callJavaScript("window.showSuccess", context.escapeJs(successMessage));
            });
        } catch (Exception exception) {
            LOG.warn("[ProviderHandler] Failed to fetch Codex models from draft: " + exception.getMessage(), exception);
            invokeLaterOrRun(() ->
                    context.callJavaScript("window.showError", context.escapeJs(exception.getMessage())));
        }
    }

    /**
     * 同步本地 Codex 配置卡片的模型目录。
     * 该链路不会走 managed provider 的 merge，而是把同步结果写入 CLI/Login discovered models 缓存，
     * 供统一目录后续优先复用。
     */
    private void handleFetchLocalCodexConfigModels() throws IOException {
        JsonArray previousDiscoveredModels = context.getSettingsService().getCodexCliLoginDiscoveredModels();
        CodexLocalModelSyncService.LocalModelSyncResult syncResult = codexLocalModelSyncService.syncLocalModels();
        // 远端发现失败会向上抛 IOException，这里只有“成功远端发现”或“天然不可发现降级”才会落盘。
        context.getSettingsService().saveCodexCliLoginDiscoveredModels(syncResult.getDiscoveredModels());
        int removedCount = countRemovedCliLoginDiscoveredModels(
                previousDiscoveredModels,
                syncResult.getDiscoveredModels()
        );
        int addedCount = countAddedCliLoginDiscoveredModels(
                previousDiscoveredModels,
                syncResult.getDiscoveredModels()
        );

        String successMessage = buildFetchLocalCodexModelsSuccessMessage(syncResult, addedCount, removedCount);
        invokeLaterOrRun(() -> {
            context.callJavaScript("window.showSuccess", context.escapeJs(successMessage));
            // CLI Login discovered models 主要影响统一目录，必须显式推送 catalog。
            handleGetCodexProviders();
            handleGetCodexModelCatalog();
        });
    }

    /**
     * 读取并推送统一 Codex 模型目录。
     * 该入口与 ModelProviderHandler 的同名能力保持协议一致，供 provider 模型同步链路在成功后直接刷新 Models 面板，
     * 避免只刷新 provider 列表后依赖前端二次请求造成的时序/漏刷风险。
     */
    private void handleGetCodexModelCatalog() {
        try {
            JsonObject catalogConfig = context.getSettingsService().getCodexModelDisplayConfig();
            JsonArray catalog = catalogConfig.has("catalog") && catalogConfig.get("catalog").isJsonArray()
                    ? catalogConfig.getAsJsonArray("catalog")
                    : new JsonArray();
            invokeLaterOrRun(() ->
                    context.callJavaScript("window.updateCodexModelCatalog", context.escapeJs(catalog.toString()))
            );
        } catch (Exception e) {
            LOG.error("[ProviderHandler] Failed to load Codex model catalog after model sync: " + e.getMessage(), e);
            invokeLaterOrRun(() ->
                    context.callJavaScript("window.updateCodexModelCatalog", context.escapeJs(new JsonArray().toString()))
            );
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
     * 组装“获取模型列表”成功后的结构化 i18n 提示。
     * 这里不再直接拼英文原文，而是回传 `{mode,key,params}`，由设置页按当前语言做插值翻译，
     * 同时继续区分“有新增”和“无新增”两类结果，并携带重复/无效项统计。
     *
     * @param providerName 当前操作的 provider 名称
     * @param mergeResult provider 模型合并统计结果
     * @return 可被 `window.showSuccess` 解析的结构化 i18n JSON 字符串
     */
    private String buildFetchCodexProviderModelsSuccessMessage(
            String providerName,
            com.github.claudecodegui.settings.CodexProviderManager.CodexProviderModelMergeResult mergeResult
    ) {
        JsonObject params = new JsonObject();
        params.addProperty("providerName", providerName);
        params.addProperty("addedCount", mergeResult.getAddedCount());
        params.addProperty("duplicateCount", mergeResult.getDuplicateCount());
        params.addProperty("invalidCount", mergeResult.getSkippedCount());
        String key = mergeResult.getAddedCount() <= 0
                ? "settings.codexProvider.fetchModelsResult.noNewModels"
                : "settings.codexProvider.fetchModelsResult.added";
        return buildI18nSuccessPayload(key, params);
    }

    /**
     * 组装本地 Codex 配置卡片“同步模型”成功提示。
     * 这里显式区分“命中远端发现”和“退化到 fallback 目录”两类结果，并在远端发现且存在旧缓存淘汰时附加 suffix key，
     * 避免 Java 侧继续拼自然语言，同时让前端能按当前语言完整展示统计信息。
     *
     * @param syncResult 本地模型同步结果
     * @param removedCount 相比上次缓存被移除的模型数量
     * @return 可被 `window.showSuccess` 解析的结构化 i18n JSON 字符串
     */
    private String buildFetchLocalCodexModelsSuccessMessage(
            CodexLocalModelSyncService.LocalModelSyncResult syncResult,
            int addedCount,
            int removedCount
    ) {
        if (syncResult.isRemoteDiscoveryUsed()) {
            JsonObject params = new JsonObject();
            // totalCount 表示本次结果模型总数；addedCount 表示相对旧缓存真正新增的模型数。
            params.addProperty("totalCount", syncResult.getAddedCount());
            params.addProperty("addedCount", addedCount);
            params.addProperty("baseUrl", firstNonBlank(syncResult.getBaseUrl(), "remote provider"));
            params.addProperty("duplicateCount", syncResult.getDuplicateCount());
            params.addProperty("invalidCount", syncResult.getSkippedCount());
            if (removedCount > 0) {
                JsonObject suffixParams = new JsonObject();
                suffixParams.addProperty("removedCount", removedCount);
                return buildI18nSuccessPayload(
                        "settings.codexProvider.fetchModelsResult.localRemoteDiscovered",
                        params,
                        "settings.codexProvider.fetchModelsResult.removedStaleSuffix",
                        suffixParams
                );
            }
            return buildI18nSuccessPayload(
                    "settings.codexProvider.fetchModelsResult.localRemoteDiscovered",
                    params
            );
        }
        return buildI18nSuccessPayload(
                "settings.codexProvider.fetchModelsResult.localFallbackRefreshed",
                new JsonObject()
        );
    }

    /**
     * 构造设置页可识别的结构化成功提示 payload。
     * 该协议保持 `window.showSuccess` 入参仍是字符串，但内容升级为 JSON，
     * 以便前端按 `mode=i18n` 做参数化翻译，同时兼容历史纯文本成功提示。
     *
     * @param key 前端 locale key
     * @param params 主句插值参数；允许为空对象
     * @return 结构化 i18n JSON 字符串
     */
    private String buildI18nSuccessPayload(String key, JsonObject params) {
        return buildI18nSuccessPayload(key, params, null, null);
    }

    /**
     * 构造可附带后缀句的结构化成功提示 payload。
     * 主句与附加句拆开，避免后端在条件分支里继续拼自然语言；前端会按当前语言分别翻译后再拼接。
     *
     * @param key 主句 locale key
     * @param params 主句插值参数；允许为空对象
     * @param suffixKey 可选后缀句 locale key；为 null 或空白时不输出后缀
     * @param suffixParams 后缀句插值参数；无后缀时忽略
     * @return 结构化 i18n JSON 字符串
     */
    private String buildI18nSuccessPayload(
            String key,
            JsonObject params,
            String suffixKey,
            JsonObject suffixParams
    ) {
        JsonObject payload = new JsonObject();
        payload.addProperty("mode", "i18n");
        payload.addProperty("key", key);
        payload.add("params", params != null ? params : new JsonObject());
        if (suffixKey != null && !suffixKey.isBlank()) {
            payload.addProperty("suffixKey", suffixKey);
            payload.add("suffixParams", suffixParams != null ? suffixParams : new JsonObject());
        }
        return GSON.toJson(payload);
    }

    /**
     * 统计本次 CLI Login discovered models 刷新中被替换掉的旧模型数量。
     * 这里只按模型 id 比较前后两份缓存，供设置页成功提示显式告知“有多少旧模型已不再返回”，
     * 避免用户在远端删模后只看到“刷新成功”却不知道本地目录为什么变少。
     *
     * @param previousModels 刷新前持久化的 discovered models
     * @param currentModels 本次刷新后即将持久化的 discovered models
     * @return 仅存在于旧缓存、但已不在新结果中的模型数量
     */
    private int countRemovedCliLoginDiscoveredModels(JsonArray previousModels, JsonArray currentModels) {
        java.util.LinkedHashSet<String> previousIds = collectModelIds(previousModels);
        java.util.LinkedHashSet<String> currentIds = collectModelIds(currentModels);
        int removedCount = 0;
        for (String previousId : previousIds) {
            if (!currentIds.contains(previousId)) {
                removedCount++;
            }
        }
        return removedCount;
    }

    /**
     * 统计本次 CLI Login discovered models 刷新中相对旧缓存真正新增的模型数量。
     * 成功提示需要区分“结果总数”和“新增数”，避免把全量同步结果误报成新增。
     *
     * @param previousModels 刷新前持久化的 discovered models
     * @param currentModels 本次刷新后即将持久化的 discovered models
     * @return 仅存在于新结果、但旧缓存中不存在的模型数量
     */
    private int countAddedCliLoginDiscoveredModels(JsonArray previousModels, JsonArray currentModels) {
        java.util.LinkedHashSet<String> previousIds = collectModelIds(previousModels);
        java.util.LinkedHashSet<String> currentIds = collectModelIds(currentModels);
        int addedCount = 0;
        for (String currentId : currentIds) {
            if (!previousIds.contains(currentId)) {
                addedCount++;
            }
        }
        return addedCount;
    }

    /**
     * 从 discovered models 数组中抽取合法模型 id 集合。
     * 该辅助方法只服务于前后缓存差异比较，因此仅关心 `id` 字段本身，
     * 并统一忽略空白、空节点和非法结构，避免提示统计受脏数据影响。
     *
     * @param models discovered models 数组
     * @return 去重后的模型 id 集合
     */
    private java.util.LinkedHashSet<String> collectModelIds(JsonArray models) {
        java.util.LinkedHashSet<String> modelIds = new java.util.LinkedHashSet<>();
        if (models == null) {
            return modelIds;
        }
        for (JsonElement element : models) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            String modelId = readString(element.getAsJsonObject(), "id");
            if (!modelId.isEmpty()) {
                modelIds.add(modelId);
            }
        }
        return modelIds;
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
     * 对空模型 provider 执行端点与凭据测试。
     * 该路径会先复用 `canFetchCodexModels` 做能力判断：
     * 1. 若当前 auth/request mode 不支持空模型发现，则直接返回明确失败结果，不再继续触发 `/v1/models`；
     * 2. 仅在 discovery 能力和最小配置都满足时，才真正访问 `/v1/models`；
     * 3. discovery 成功只代表 Base URL 和凭据可用，并不等同于完整 Codex SDK 消息测试已经通过。
     *
     * @param providerId 被测 provider id
     * @param targetProvider 被测 provider 原始配置
     * @param canFetchModels 当前配置是否具备再次拉取模型的条件
     * @throws IOException 当模型发现配置、网络请求或响应解析失败时抛出
     */
    private void showEmptyModelProviderTestResult(
            String providerId,
            JsonObject targetProvider,
            boolean canFetchModels
    ) throws IOException {
        // 空模型测试本质上依赖模型发现链路；当前模式不支持时必须直接短路，避免前端提示与真实行为分叉。
        if (!canFetchModels) {
            showTestResult(buildTestResultPayload(
                    false,
                    providerId,
                    targetProvider,
                    null,
                    EMPTY_MODEL_DISCOVERY_UNSUPPORTED_MESSAGE,
                    TEST_STAGE_MODEL_DISCOVERY,
                    true,
                    false
            ));
            return;
        }
        CodexProviderModelDiscoveryService.DiscoveryResult discoveryResult =
                codexProviderModelDiscoveryService.discoverModels(targetProvider);
        int discoveredCount = discoveryResult.getModelIds().size();
        String message = "Endpoint and credentials are available. Discovered "
                + discoveredCount
                + " models. Import a model before running a full SDK message test.";
        showTestResult(buildTestResultPayload(
                true,
                providerId,
                targetProvider,
                null,
                message,
                TEST_STAGE_MODEL_DISCOVERY,
                true,
                canFetchModels
        ));
    }

    /**
     * 判断 provider 是否已经配置了可用于完整消息测试的模型。
     * 这里只看 provider 自身的 models/customModels，不回退到全局选中模型，
     * 以便前端“空模型提示”和后端测试阶段选择保持一致。
     *
     * @param provider 目标 provider 配置
     * @return true 表示至少存在一个非空模型 id；false 表示应走端点与凭据测试
     */
    private boolean hasUsableCodexModel(JsonObject provider) {
        return !readFirstUsableModelId(provider).isEmpty();
    }

    /**
     * 判断当前 provider 是否具备前端可再次发起模型发现的最小配置。
     * 该判断与发现服务默认值保持一致：requestMode 默认 `codex_sdk`，authMode 默认 `api_key_env`，
     * 同时还要求 Base URL 和 apiKey/apiKeyEnv 至少有一项已填写。
     *
     * @param provider 目标 provider 配置
     * @return true 表示可以继续拉取模型；false 表示模式不受支持或配置不完整
     */
    private boolean canFetchCodexModels(JsonObject provider) {
        if (provider == null || provider.size() == 0) {
            return false;
        }
        String requestMode = firstNonBlank(readString(provider, "requestMode"), DEFAULT_REQUEST_MODE);
        String authMode = firstNonBlank(readString(provider, "authMode"), AUTH_MODE_API_KEY_ENV);
        if (!DEFAULT_REQUEST_MODE.equals(requestMode)) {
            return false;
        }
        if (!AUTH_MODE_API_KEY.equals(authMode) && !AUTH_MODE_API_KEY_ENV.equals(authMode)) {
            return false;
        }
        if (readString(provider, "baseUrl").isEmpty()) {
            return false;
        }
        return !readString(provider, "apiKey").isEmpty() || !readString(provider, "apiKeyEnv").isEmpty();
    }

    /**
     * 读取 provider 中第一个可用模型 id。
     * 该读取语义必须与 `CodexRuntimeProfileResolver` 完全一致：
     * 1. 只要 `models` 字段存在，就只认 `models`，即使它是空数组；
     * 2. 仅当 `models` 字段缺失时，才回退历史 `customModels`；
     * 3. 继续跳过空白 id，避免把空对象误判成已配置模型。
     *
     * @param provider provider 原始配置
     * @return 第一个非空模型 id；不存在时返回空串
     */
    private String readFirstUsableModelId(JsonObject provider) {
        if (provider == null) {
            return "";
        }
        if (provider.has("models") && provider.get("models").isJsonArray()) {
            return readFirstModelIdFromArray(provider, "models");
        }
        return readFirstModelIdFromArray(provider, "customModels");
    }

    /**
     * 从指定模型数组中读取第一个非空 id。
     *
     * @param provider provider 原始配置
     * @param fieldName 数组字段名，通常是 `models` 或 `customModels`
     * @return 第一个非空模型 id；数组不存在或全无效时返回空串
     */
    private String readFirstModelIdFromArray(JsonObject provider, String fieldName) {
        if (provider == null || fieldName == null || fieldName.isBlank()) {
            return "";
        }
        if (!provider.has(fieldName) || !provider.get(fieldName).isJsonArray()) {
            return "";
        }
        JsonArray models = provider.getAsJsonArray(fieldName);
        for (JsonElement element : models) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            String modelId = readString(element.getAsJsonObject(), "id");
            if (!modelId.isEmpty()) {
                return modelId;
            }
        }
        return "";
    }

    /**
     * 基于 provider 与运行时 profile 组装结构化测试结果。
     * 这里优先使用运行时 profile，因为它代表真正送入桥接层的请求参数；
     * 当 profile 尚未解析成功时，再回退到 provider 原始字段，避免前端完全拿不到上下文。
     * endpointSource 在缺少 runtime profile 时，只要 provider 自身带有 baseUrl 就回退为 `provider`，
     * 禁止再误报成 `sdk_default`。
     *
     * @param success 本次测试是否成功
     * @param requestedProviderId 前端请求测试的 provider id
     * @param provider 被测试的 provider 原始配置；解析失败时允许为 null
     * @param runtimeProfile 请求级运行时 profile；解析失败或端点测试阶段允许为 null
     * @param message 展示给前端的结果消息
     * @param testStage 本次测试实际执行到的阶段
     * @param requiresModel 是否仍需要用户先配置或导入模型
     * @param canFetchModels 当前配置是否允许继续拉取模型
     * @return 可直接序列化并回传前端的 JSON payload
     */
    private JsonObject buildTestResultPayload(
            boolean success,
            String requestedProviderId,
            JsonObject provider,
            CodexRuntimeProfile runtimeProfile,
            String message,
            String testStage,
            boolean requiresModel,
            boolean canFetchModels
    ) {
        JsonObject payload = new JsonObject();
        String resolvedProviderId = runtimeProfile != null && !runtimeProfile.getProviderId().isEmpty()
                ? runtimeProfile.getProviderId()
                : firstNonBlank(requestedProviderId, readString(provider, "id"));
        String resolvedRequestMode = runtimeProfile != null && !runtimeProfile.getRequestMode().isEmpty()
                ? runtimeProfile.getRequestMode()
                : firstNonBlank(readString(provider, "requestMode"), DEFAULT_REQUEST_MODE);
        String resolvedModel = runtimeProfile != null && !runtimeProfile.getModel().isEmpty()
                ? runtimeProfile.getModel()
                : readFirstUsableModelId(provider);
        String resolvedBaseUrl = runtimeProfile != null
                ? runtimeProfile.getBaseUrl()
                : readString(provider, "baseUrl");
        String resolvedCredentialSource = runtimeProfile != null && !runtimeProfile.getCredentialSource().isEmpty()
                ? runtimeProfile.getCredentialSource()
                : inferCredentialSource(provider);
        String resolvedAuthMode = runtimeProfile != null && !runtimeProfile.getAuthMode().isEmpty()
                ? runtimeProfile.getAuthMode()
                : firstNonBlank(readString(provider, "authMode"), AUTH_MODE_API_KEY_ENV);
        String resolvedConfigSource = runtimeProfile != null && !runtimeProfile.getEffectiveConfigSource().isEmpty()
                ? runtimeProfile.getEffectiveConfigSource()
                : CodexRuntimeProfile.CONFIG_SOURCE_MANAGED_PROVIDER;
        String resolvedEndpointSource = runtimeProfile != null && !runtimeProfile.getBaseUrlSource().isEmpty()
                ? runtimeProfile.getBaseUrlSource()
                : (resolvedBaseUrl.isEmpty() ? "sdk_default" : "provider");
        String resolvedTestStage = firstNonBlank(testStage, TEST_STAGE_RUNTIME_PROFILE);

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
        payload.addProperty("testStage", resolvedTestStage);
        payload.addProperty("failureStage", success ? "" : resolvedTestStage);
        payload.addProperty("requiresModel", requiresModel);
        payload.addProperty("canFetchModels", canFetchModels);
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
        invokeLaterOrRun(action);
    }

    /**
     * 根据 provider 原始字段推断脱敏后的凭据来源描述。
     * 这里需要与 discovery/runtime 解析链路保持同一优先级和标签语义：
     * 1. inline `apiKey` 优先于 `apiKeyEnv`；
     * 2. inline key 统一标记为 `apiKey`，避免同一来源在不同测试阶段出现两个名字；
     * 3. 仅在两者都缺失时返回空串。
     *
     * @param provider provider 原始配置
     * @return 脱敏后的凭据来源字符串
     */
    private String inferCredentialSource(JsonObject provider) {
        if (!readString(provider, "apiKey").isEmpty()) {
            return "apiKey";
        }
        String apiKeyEnv = readString(provider, "apiKeyEnv");
        if (!apiKeyEnv.isEmpty()) {
            return "apiKeyEnv:" + apiKeyEnv;
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
