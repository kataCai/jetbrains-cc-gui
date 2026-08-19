package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexProviderModelDiscoveryService;
import com.github.claudecodegui.provider.codex.CodexLocalModelSyncService;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.CodexProviderManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Codex provider 测试连接逻辑。
 * 重点覆盖：
 * 1. provider 不存在时会走独立 test result 失败提示；
 * 2. 本地环境变量缺失时会在 resolver 阶段失败，但仍回传 provider 上下文；
 * 3. 空模型 provider 走模型发现阶段，而不是直接报 No Codex model configured；
 * 4. 已配置模型的 provider 仍走完整 SDK 消息测试。
 */
public class CodexProviderOperationsTest {

    @Test
    public void shouldShowFailureWhenProviderIsMissing() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(new TestSettingsService(null, new JsonObject()), new RecordingCodexSDKBridge(), jsCallback)
        );

        operations.handleTestCodexProvider("{\"id\":\"missing-provider\"}");

        assertEquals("window.showTestResult", jsCallback.lastFunctionName);
        JsonObject payload = jsCallback.getLastPayload();
        assertEquals(false, payload.get("success").getAsBoolean());
        assertTrue(payload.get("message").getAsString().contains("Provider not found"));
    }

    @Test
    public void shouldShowFailureWhenCredentialEnvIsMissing() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = createManagedProvider();
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(new TestSettingsService(provider, new JsonObject()), new RecordingCodexSDKBridge(), jsCallback)
        );

        operations.handleTestCodexProvider("{\"id\":\"managed-provider\"}");

        assertEquals("window.showTestResult", jsCallback.lastFunctionName);
        JsonObject payload = jsCallback.getLastPayload();
        assertEquals(false, payload.get("success").getAsBoolean());
        assertTrue(payload.get("message").getAsString().contains("API key env is not set"));
        /**
         * 验证目标：
         * runtime profile 解析失败时，测试结果仍必须回传被测 provider 的原始上下文，
         * 不能因为 catch 丢变量而把 endpointSource 误报成 sdk_default。
         *
         * 断言意图：
         * providerId、baseUrl、requestMode、authMode 都来自被测 provider，endpointSource=provider。
         */
        assertEquals("managed-provider", payload.get("providerId").getAsString());
        assertEquals("https://provider.example.com/v1", payload.get("resolvedBaseUrl").getAsString());
        assertEquals("provider", payload.get("endpointSource").getAsString());
        assertEquals("codex_sdk", payload.get("requestMode").getAsString());
        assertEquals("api_key_env", payload.get("authMode").getAsString());
        assertEquals("runtime_profile", payload.get("testStage").getAsString());
        assertEquals("runtime_profile", payload.get("failureStage").getAsString());
        assertEquals(false, payload.get("requiresModel").getAsBoolean());
    }

    /**
     * 验证目标：
     * 空模型 provider 点击测试时，应执行 `/v1/models` 端点与凭据测试，
     * 而不是继续走发送消息 runtime profile 并抛出 No Codex model configured。
     *
     * 前置条件：
     * provider.models 为空，但 Base URL 和凭据字段已配置；discovery service 返回两个模型 id。
     *
     * 断言意图：
     * 1. 成功结果的 testStage 为 model_discovery。
     * 2. requiresModel=true，提示用户还需要导入模型。
     * 3. 不会调用 CodexSDKBridge.sendMessage。
     * 4. 消息不再包含 No Codex model configured。
     */
    @Test
    public void shouldReturnModelDiscoveryStageWhenProviderHasNoModels() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = createEmptyModelProvider();
        TrackingFetchSettingsService settingsService = new TrackingFetchSettingsService(provider, new JsonObject());
        RecordingDiscoveryService discoveryService = new RecordingDiscoveryService(
                settingsService,
                new CodexProviderModelDiscoveryService.DiscoveryResult(List.of("gpt-5.5", "gpt-5.4-mini"), 0, 0)
        );
        RecordingCodexSDKBridge sdkBridge = new RecordingCodexSDKBridge();
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, sdkBridge, jsCallback),
                discoveryService
        );

        operations.handleTestCodexProvider("{\"id\":\"empty-model-provider\"}");

        assertEquals("window.showTestResult", jsCallback.lastFunctionName);
        JsonObject payload = jsCallback.getLastPayload();
        assertEquals(true, payload.get("success").getAsBoolean());
        assertEquals("model_discovery", payload.get("testStage").getAsString());
        assertEquals("", payload.get("failureStage").getAsString());
        assertEquals(true, payload.get("requiresModel").getAsBoolean());
        assertEquals(true, payload.get("canFetchModels").getAsBoolean());
        assertEquals("empty-model-provider", payload.get("providerId").getAsString());
        assertEquals("https://provider.example.com/v1", payload.get("resolvedBaseUrl").getAsString());
        assertEquals("provider", payload.get("endpointSource").getAsString());
        assertTrue(payload.get("message").getAsString().contains("Discovered 2 models"));
        assertFalse(payload.get("message").getAsString().contains("No Codex model configured"));
        assertEquals(0, sdkBridge.getSendMessageCallCount());
        assertEquals("empty-model-provider", discoveryService.getLastProvider().get("id").getAsString());
    }

    /**
     * 验证目标：
     * 已配置模型的 provider 仍必须走完整 SDK 消息测试，不能被新的空模型短路径误伤。
     *
     * 前置条件：
     * provider 已有模型，且通过 inline apiKey 绕过环境变量缺失。
     *
     * 断言意图：
     * testStage=sdk_message，并且 SDK bridge 被调用一次。
     */
    @Test
    public void shouldKeepSdkLiveTestWhenProviderAlreadyHasModels() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = createManagedProvider();
        TestSettingsService settingsService = new TestSettingsService(provider, new JsonObject()) {
            @Override
            public JsonObject getCodexProviderById(String providerId) {
                JsonObject providerObject = super.getCodexProviderById(providerId);
                if (providerObject != null) {
                    providerObject.addProperty("apiKey", "test-secret");
                    providerObject.remove("apiKeyEnv");
                }
                return providerObject;
            }
        };
        RecordingCodexSDKBridge sdkBridge = new RecordingCodexSDKBridge();
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, sdkBridge, jsCallback)
        );

        operations.handleTestCodexProvider("{\"id\":\"managed-provider\"}");

        JsonObject payload = jsCallback.getLastPayload();
        assertEquals(true, payload.get("success").getAsBoolean());
        assertEquals("sdk_message", payload.get("testStage").getAsString());
        assertEquals(false, payload.get("requiresModel").getAsBoolean());
        assertEquals("provider-model", payload.get("model").getAsString());
        assertEquals(1, sdkBridge.getSendMessageCallCount());
    }

    @Test
    public void shouldShowSuccessSummaryWhenLiveTestPasses() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = createManagedProvider();
        JsonObject localModelState = new JsonObject();
        localModelState.addProperty("model", "local-model");
        localModelState.addProperty("reasoningEffort", "high");
        localModelState.addProperty("modelProvider", "LocalOpenAI");
        TestSettingsService settingsService = new TestSettingsService(provider, localModelState) {
            @Override
            public JsonObject getCodexProviderById(String providerId) {
                JsonObject providerObject = super.getCodexProviderById(providerId);
                if (providerObject != null) {
                    providerObject.addProperty("apiKey", "test-secret");
                    providerObject.remove("apiKeyEnv");
                }
                return providerObject;
            }
        };
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback)
        );

        operations.handleTestCodexProvider("{\"id\":\"managed-provider\"}");

        assertEquals("window.showTestResult", jsCallback.lastFunctionName);
        JsonObject payload = jsCallback.getLastPayload();
        assertEquals(true, payload.get("success").getAsBoolean());
        assertTrue(payload.get("message").getAsString().contains("Codex provider test passed"));
        /**
         * 验证目标：
         * provider 连通性测试应基于被测 provider 自身的 runtime profile，
         * 不能被 ~/.codex/config.toml 里仅用于展示的当前本地模型污染。
         *
         * 断言意图：
         * 成功摘要里展示的 model 必须来自 provider 自身配置，便于用户判断“被测的是谁”。
         */
        assertEquals("provider-model", payload.get("model").getAsString());
        assertEquals("api_key_env", payload.get("authMode").getAsString());
        assertEquals("managed-provider", payload.get("providerId").getAsString());
        assertEquals("codex_sdk", payload.get("requestMode").getAsString());
        assertEquals("codemoss_managed_provider", payload.get("forcedModelProvider").getAsString());
        assertEquals("LocalOpenAI", payload.get("localCodexModelProvider").getAsString());
        assertEquals(true, payload.get("localConfigConflictDetected").getAsBoolean());
        assertEquals("codemoss_managed_provider", payload.get("finalModelProvider").getAsString());
    }

    @Test
    public void shouldExposeRuntimeDiagnosticsWhenFetchingActiveCodexProvider() throws Exception {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = createManagedProvider();
        TestSettingsService settingsService = new TestSettingsService(provider, new JsonObject()) {
            @Override
            public JsonObject getCurrentCodexModelState() {
                JsonObject localState = new JsonObject();
                localState.addProperty("model", "local-model");
                localState.addProperty("reasoningEffort", "medium");
                return localState;
            }
        };
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback)
        );

        operations.handleGetActiveCodexProvider();

        assertEquals("window.updateActiveCodexProvider", jsCallback.lastFunctionName);
        JsonObject payload = jsCallback.getLastPayload();
        assertEquals("managed-provider", payload.get("id").getAsString());
        assertEquals("codemoss_managed_provider", payload.get("effectiveConfigSource").getAsString());
        /**
         * 断言意图：
         * 当前这条测试数据本身已经包含 provider.baseUrl，因此轻量诊断摘要应判定运行时 endpoint 直接命中托管 provider，
         * 不应误报为回退到本地 Codex 配置或 SDK 默认值。
         */
        assertEquals(false, payload.get("fallbackDetected").getAsBoolean());
        assertEquals("provider", payload.get("endpointSource").getAsString());
        assertEquals("codemoss_managed_provider", payload.get("forcedModelProvider").getAsString());
        assertEquals("", payload.get("localCodexModelProvider").getAsString());
        assertEquals(false, payload.get("localConfigConflictDetected").getAsBoolean());
        assertEquals("codemoss_managed_provider", payload.get("finalModelProvider").getAsString());
    }

    @Test
    public void shouldIgnoreLocalCodexEndpointWhenActiveManagedProviderHasNoBaseUrl() throws Exception {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = createManagedProvider();
        provider.remove("baseUrl");
        TestSettingsService settingsService = new TestSettingsService(provider, new JsonObject()) {
            @Override
            public JsonObject getCurrentCodexModelState() {
                JsonObject localState = new JsonObject();
                localState.addProperty("model", "local-model");
                localState.addProperty("reasoningEffort", "medium");
                localState.addProperty("baseUrl", "https://local.example.com/v1");
                localState.addProperty("modelProvider", "LocalOpenAI");
                return localState;
            }
        };
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback)
        );

        operations.handleGetActiveCodexProvider();

        JsonObject payload = jsCallback.getLastPayload();
        /**
         * 验证目标：
         * 即使本地 ~/.codex/config.toml 中存在 endpoint，设置页轻量诊断也必须保持与真实发送链路一致，
         * 对托管 provider 只能显示 provider 自身 endpoint 或 SDK 默认值，不能误报为命中本地配置。
         */
        assertEquals("sdk_default", payload.get("endpointSource").getAsString());
        assertEquals(true, payload.get("fallbackDetected").getAsBoolean());
        assertEquals("codemoss_managed_provider", payload.get("forcedModelProvider").getAsString());
        assertEquals("LocalOpenAI", payload.get("localCodexModelProvider").getAsString());
        assertEquals(true, payload.get("localConfigConflictDetected").getAsBoolean());
        assertEquals("codemoss_managed_provider", payload.get("finalModelProvider").getAsString());
    }

    @Test
    public void shouldNotReportManagedConflictForCliLoginActiveProviderDiagnostics() throws Exception {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = new JsonObject();
        provider.addProperty("id", com.github.claudecodegui.settings.CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);
        provider.addProperty("name", "Codex CLI Login");
        provider.addProperty("isCodexCliLoginProvider", true);
        JsonObject localModelState = new JsonObject();
        localModelState.addProperty("modelProvider", "LocalOpenAI");
        TestSettingsService settingsService = new TestSettingsService(provider, localModelState);
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback)
        );

        operations.handleGetActiveCodexProvider();

        JsonObject payload = jsCallback.getLastPayload();
        assertEquals("codex_cli_login", payload.get("effectiveConfigSource").getAsString());
        assertEquals("", payload.get("forcedModelProvider").getAsString());
        assertEquals("LocalOpenAI", payload.get("localCodexModelProvider").getAsString());
        assertEquals(false, payload.get("localConfigConflictDetected").getAsBoolean());
        assertEquals("LocalOpenAI", payload.get("finalModelProvider").getAsString());
    }

    @Test
    public void shouldAuthorizeLocalConfigWithoutForcingProviderSwitchWhenManagedProviderIsActive() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingSettingsService settingsService = new TrackingSettingsService(createManagedProvider(), new JsonObject());
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback)
        );

        operations.handleAuthorizeCodexLocalConfig("");

        assertTrue(settingsService.localConfigAuthorized);
        assertFalse(settingsService.switchCodexProviderCalled);
        assertEquals("window.updateActiveCodexProvider", jsCallback.lastFunctionName);
    }

    /**
     * 验证目标：
     * 当远端模型发现与本地合并都成功时，操作处理器必须同时做到两件事：
     * 1. 给出带统计信息的成功提示；
     * 2. 主动刷新 provider 列表，复用现有 `updateCodexProviders -> loadCodexModelCatalog` 链路。
     *
     * 断言意图：
     * 成功场景下既要看到 `window.showSuccess`，也要看到 `window.updateCodexProviders`，
     * 并且设置服务拿到的 merge 入参必须是 discovery service 返回的模型 id 列表。
     */
    @Test
    public void shouldRefreshProviderListAfterFetchingCodexProviderModels() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService = new TrackingFetchSettingsService(createManagedProvider(), new JsonObject());
        settingsService.mergeResult = new CodexProviderManager.CodexProviderModelMergeResult(2, 1, 0);
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                new RecordingDiscoveryService(
                        settingsService,
                        new CodexProviderModelDiscoveryService.DiscoveryResult(List.of("gpt-5.5", "gpt-5.4-mini"), 0, 0)
                )
        );

        operations.handleFetchCodexProviderModels("{\"id\":\"managed-provider\"}");

        assertEquals("managed-provider", settingsService.lastMergedProviderId);
        assertEquals(List.of("gpt-5.5", "gpt-5.4-mini"), settingsService.lastMergedModelIds);
        assertTrue(jsCallback.hasFunctionCall("window.showSuccess"));
        assertTrue(jsCallback.hasFunctionCall("window.updateCodexProviders"));
        assertTrue(jsCallback.hasFunctionCall("window.updateCodexModelCatalog"));
        assertTrue(jsCallback.containsArgFragment("settings.codexProvider.fetchModelsResult.added"));
        assertTrue(jsCallback.containsArgFragment("\"providerName\":\"Managed Provider\""));
        assertTrue(jsCallback.containsArgFragment("\"addedCount\":2"));
        assertTrue(jsCallback.containsArgFragment("\"mode\":\"i18n\""));
    }

    /**
     * 验证目标：
     * 当远端拉取完成但没有任何新增模型时，设置页不能静默成功，
     * 必须明确告诉用户“本次没有新增，只是跳过了重复/无效项”。
     *
     * 断言意图：
     * 成功提示文案里应包含“no new models”语义，同时不应因为无变化而错误显示新增统计。
     */
    @Test
    public void shouldShowNoChangeSuccessMessageWhenFetchedModelsDoNotAddAnythingNew() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService = new TrackingFetchSettingsService(createManagedProvider(), new JsonObject());
        settingsService.mergeResult = new CodexProviderManager.CodexProviderModelMergeResult(0, 2, 1);
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                new RecordingDiscoveryService(
                        settingsService,
                        new CodexProviderModelDiscoveryService.DiscoveryResult(List.of("provider-model"), 0, 0)
                )
        );

        operations.handleFetchCodexProviderModels("{\"id\":\"managed-provider\"}");

        assertTrue(jsCallback.hasFunctionCall("window.showSuccess"));
        assertTrue(jsCallback.hasFunctionCall("window.updateCodexModelCatalog"));
        assertTrue(jsCallback.containsArgFragment("settings.codexProvider.fetchModelsResult.noNewModels"));
        assertTrue(jsCallback.containsArgFragment("\"addedCount\":0"));
        assertTrue(jsCallback.containsArgFragment("\"mode\":\"i18n\""));
    }

    /**
     * 验证目标：
     * 当 discovery service 明确抛出失败原因时，操作处理器必须把错误直接回传前端，
     * 并停止后续 merge / 列表刷新，避免把失败链路误报成“拉取成功但没有模型”。
     *
     * 断言意图：
     * 失败场景下只应出现 `window.showError`，且设置服务不应收到任何 merge 调用。
     */
    @Test
    public void shouldShowErrorAndSkipMergeWhenFetchingCodexProviderModelsFails() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService = new TrackingFetchSettingsService(createManagedProvider(), new JsonObject());
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                new RecordingDiscoveryService(settingsService, new IOException("Unsupported authMode for model discovery: proxy"))
        );

        operations.handleFetchCodexProviderModels("{\"id\":\"managed-provider\"}");

        assertEquals("window.showError", jsCallback.lastFunctionName);
        assertTrue(jsCallback.lastArgs.get(0).contains("Unsupported authMode"));
        assertEquals("", settingsService.lastMergedProviderId);
    }

    /**
     * 验证目标：
     * 当 `fetch_codex_provider_models` 作用于 CLI Login 虚拟 provider 时，
     * 操作处理器应切换到“本地 Codex 配置同步模型”链路，而不是继续调用 managed provider 的 merge 逻辑。
     *
     * 断言意图：
     * 1. 后端应把同步结果写入 discovered models；
     * 2. 成功后仍需刷新 provider 列表，复用现有前端回调链路；
     * 3. 不应调用 managed provider merge。
     */
    @Test
    public void shouldSyncLocalCodexModelsWhenFetchingCliLoginProviderModels() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService = new TrackingFetchSettingsService(createCliLoginProvider(), new JsonObject());
        settingsService.localSyncResult = new CodexLocalModelSyncService.LocalModelSyncResult(
                createCliDiscoveredModels(),
                true,
                false,
                2,
                0,
                0,
                "https://api.openai.com/v1",
                "config_api_key"
        );
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                new RecordingDiscoveryService(settingsService, new IOException("managed discovery should not be called")),
                new RecordingLocalModelSyncService(settingsService, settingsService.localSyncResult)
        );

        operations.handleFetchCodexProviderModels("{\"id\":\"__codex_cli_login__\"}");

        assertTrue(settingsService.localSyncCalled);
        assertEquals(2, settingsService.savedCliLoginDiscoveredModels.size());
        assertEquals(CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID, settingsService.lastClearedExclusionProviderId);
        assertEquals("", settingsService.lastMergedProviderId);
        assertTrue(jsCallback.hasFunctionCall("window.showSuccess"));
        assertTrue(jsCallback.hasFunctionCall("window.updateCodexProviders"));
        assertTrue(jsCallback.hasFunctionCall("window.updateCodexModelCatalog"));
        // 本地配置同步成功提示也必须走结构化 i18n，而不是英文拼串。
        assertTrue(
                jsCallback.containsArgFragment("settings.codexProvider.fetchModelsResult.localRemoteDiscovered")
                        || jsCallback.containsArgFragment("settings.codexProvider.fetchModelsResult.localFallbackRefreshed")
        );
        assertTrue(jsCallback.containsArgFragment("\"mode\":\"i18n\""));
        assertTrue(jsCallback.containsArgFragment("\"totalCount\":2"));
        assertTrue(jsCallback.containsArgFragment("\"addedCount\":2"));
    }

    /**
     * 验证目标：
     * 当本地 Codex 配置同步后的 discovered models 覆盖掉旧缓存并导致部分旧模型被移除时，
     * 设置页成功提示必须显式告知用户“删除了几个模型”，避免用户误以为刷新没有生效。
     *
     * 前置条件：
     * 1. 旧缓存里存在两个模型；
     * 2. 本次同步结果只保留其中一个模型；
     * 3. 本次链路仍然是成功同步，而不是错误或 fallback 场景。
     *
     * 断言意图：
     * 1. 后端仍会覆盖保存最新 discovered models；
     * 2. 成功提示文案中必须带出 removed 统计；
     * 3. 该统计来自“旧缓存 vs 新结果”的差异，而不是 provider merge 逻辑。
     */
    @Test
    public void shouldReportRemovedModelCountWhenLocalCodexSyncReplacesOldDiscoveredModels() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService = new TrackingFetchSettingsService(createCliLoginProvider(), new JsonObject());
        settingsService.existingCliLoginDiscoveredModels = createCliDiscoveredModels();
        JsonArray refreshedModels = new JsonArray();
        JsonObject remainingModel = new JsonObject();
        remainingModel.addProperty("id", "gpt-4.1");
        remainingModel.addProperty("label", "gpt-4.1");
        refreshedModels.add(remainingModel);
        settingsService.localSyncResult = new CodexLocalModelSyncService.LocalModelSyncResult(
                refreshedModels,
                true,
                false,
                1,
                0,
                0,
                "https://api.openai.com/v1",
                "config_api_key"
        );
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                new RecordingDiscoveryService(settingsService, new IOException("managed discovery should not be called")),
                new RecordingLocalModelSyncService(settingsService, settingsService.localSyncResult)
        );

        operations.handleFetchCodexProviderModels("{\"id\":\"__codex_cli_login__\"}");

        assertEquals(1, settingsService.savedCliLoginDiscoveredModels.size());
        assertTrue(jsCallback.hasFunctionCall("window.showSuccess"));
        assertTrue(jsCallback.containsArgFragment("settings.codexProvider.fetchModelsResult.localRemoteDiscovered"));
        assertTrue(jsCallback.containsArgFragment("settings.codexProvider.fetchModelsResult.removedStaleSuffix"));
        assertTrue(jsCallback.containsArgFragment("\"removedCount\":1"));
        assertTrue(jsCallback.hasFunctionCall("window.updateCodexModelCatalog"));
    }


    /**
     * 验证目标：
     * 当本地同步服务因远端发现失败抛出 IOException 时，handler 必须：
     * 1. 走 showError，而不是伪造成功；
     * 2. 不覆盖已有 discovered models 缓存；
     * 3. 不清理 exclusion。
     */
    @Test
    public void shouldShowErrorAndKeepOldCacheWhenLocalCodexSyncRemoteDiscoveryFails() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService = new TrackingFetchSettingsService(createCliLoginProvider(), new JsonObject());
        settingsService.existingCliLoginDiscoveredModels = createCliDiscoveredModels();
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                new RecordingDiscoveryService(settingsService, new IOException("managed discovery should not be called")),
                new FailingLocalModelSyncService(settingsService, new IOException("remote models endpoint unavailable"))
        );

        operations.handleFetchCodexProviderModels("{\"id\":\"__codex_cli_login__\"}");

        assertTrue(settingsService.localSyncCalled);
        assertEquals(0, settingsService.savedCliLoginDiscoveredModels.size());
        assertEquals("", settingsService.lastClearedExclusionProviderId);
        assertTrue(jsCallback.hasFunctionCall("window.showError"));
        assertFalse(jsCallback.hasFunctionCall("window.showSuccess"));
        assertFalse(jsCallback.hasFunctionCall("window.updateCodexModelCatalog"));
    }

    /**
     * 验证编辑弹窗的草稿级拉模直接消费前端传入的最新配置。
     * 成功后只回传结构化模型结果，不调用 provider merge、provider 列表刷新或统一模型目录刷新，
     * 从而保证用户尚未保存的草稿字段不会被持久化配置覆盖。
     */
    @Test
    public void shouldReturnDraftModelsWithoutPersistingProviderConfiguration() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService =
                new TrackingFetchSettingsService(createManagedProvider(), new JsonObject());
        RecordingDiscoveryService discoveryService = new RecordingDiscoveryService(
                settingsService,
                new CodexProviderModelDiscoveryService.DiscoveryResult(
                        List.of("gpt-5.5", "gpt-5.4"),
                        1,
                        2
                )
        );
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                discoveryService
        );

        operations.handleFetchCodexProviderModelsFromDraft(
                "{\"providerId\":\"draft-provider\",\"name\":\"Unsaved Provider\","
                        + "\"authMode\":\"api_key\",\"requestMode\":\"codex_sdk\","
                        + "\"baseUrl\":\"https://unsaved.example.com/v1\",\"apiKey\":\"sk-unsaved\"}"
        );

        assertEquals("https://unsaved.example.com/v1", discoveryService.lastProvider.get("baseUrl").getAsString());
        assertEquals("sk-unsaved", discoveryService.lastProvider.get("apiKey").getAsString());
        assertEquals("", settingsService.lastMergedProviderId);
        assertTrue(jsCallback.hasFunctionCall("window.onCodexProviderDraftModelsFetched"));
        assertTrue(jsCallback.hasFunctionCall("window.showSuccess"));
        assertFalse(jsCallback.hasFunctionCall("window.updateCodexProviders"));
        assertFalse(jsCallback.hasFunctionCall("window.updateCodexModelCatalog"));
        assertTrue(jsCallback.containsArgFragment("\"providerId\":\"draft-provider\""));
        assertTrue(jsCallback.containsArgFragment("\"modelIds\":[\"gpt-5.5\",\"gpt-5.4\"]"));
        assertTrue(jsCallback.containsArgFragment("\"duplicateCount\":1"));
        assertTrue(jsCallback.containsArgFragment("\"skippedCount\":2"));
    }

    /**
     * 验证草稿级拉模失败时只返回错误，不产生草稿结果回调，也不触发任何持久化或列表刷新副作用。
     */
    @Test
    public void shouldShowErrorAndSkipDraftModelResultWhenDraftDiscoveryFails() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TrackingFetchSettingsService settingsService =
                new TrackingFetchSettingsService(createManagedProvider(), new JsonObject());
        CodexProviderOperations operations = new CodexProviderOperations(
                createContext(settingsService, new RecordingCodexSDKBridge(), jsCallback),
                new RecordingDiscoveryService(settingsService, new IOException("draft discovery failed"))
        );

        operations.handleFetchCodexProviderModelsFromDraft(
                "{\"providerId\":\"draft-provider\",\"name\":\"Unsaved Provider\","
                        + "\"authMode\":\"api_key\",\"requestMode\":\"codex_sdk\","
                        + "\"baseUrl\":\"https://unsaved.example.com/v1\",\"apiKey\":\"sk-unsaved\"}"
        );

        assertEquals("window.showError", jsCallback.lastFunctionName);
        assertFalse(jsCallback.hasFunctionCall("window.onCodexProviderDraftModelsFetched"));
        assertFalse(jsCallback.hasFunctionCall("window.updateCodexProviders"));
        assertEquals("", settingsService.lastMergedProviderId);
    }

    private static HandlerContext createContext(
            CodemossSettingsService settingsService,
            CodexSDKBridge codexSDKBridge,
            RecordingJsCallback jsCallback
    ) {
        return new HandlerContext(
                null,
                null,
                codexSDKBridge,
                settingsService,
                jsCallback
        );
    }

    /**
     * 构造一个没有任何模型、但连接配置完整的托管 provider。
     * 该夹具专门覆盖空模型测试短路径，避免继续复用带 seed-model 的普通夹具。
     *
     * @return models 为空的托管 provider
     */
    private static JsonObject createEmptyModelProvider() {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", "empty-model-provider");
        provider.addProperty("name", "Empty Model Provider");
        provider.addProperty("authMode", "api_key_env");
        provider.addProperty("requestMode", "codex_sdk");
        provider.addProperty("baseUrl", "https://provider.example.com/v1");
        provider.addProperty("apiKeyEnv", "EMPTY_MODEL_CODEX_KEY");
        provider.add("models", new JsonArray());
        return provider;
    }

    private static JsonObject createManagedProvider() {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", "managed-provider");
        provider.addProperty("name", "Managed Provider");
        provider.addProperty("authMode", "api_key_env");
        provider.addProperty("requestMode", "codex_sdk");
        provider.addProperty("baseUrl", "https://provider.example.com/v1");
        provider.addProperty("apiKeyEnv", "MISSING_CODEX_KEY");
        JsonArray models = new JsonArray();
        JsonObject model = new JsonObject();
        model.addProperty("id", "provider-model");
        model.addProperty("label", "Provider Model");
        model.addProperty("reasoningEffort", "medium");
        models.add(model);
        provider.add("models", models);
        return provider;
    }

    private static JsonObject createCliLoginProvider() {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);
        provider.addProperty("name", "Codex CLI Login");
        provider.addProperty("isCodexCliLoginProvider", true);
        provider.addProperty("isAuthorized", true);
        return provider;
    }

    private static JsonArray createCliDiscoveredModels() {
        JsonArray models = new JsonArray();
        JsonObject modelA = new JsonObject();
        modelA.addProperty("id", "gpt-4.1");
        modelA.addProperty("label", "gpt-4.1");
        models.add(modelA);
        JsonObject modelB = new JsonObject();
        modelB.addProperty("id", "gpt-4.1-mini");
        modelB.addProperty("label", "gpt-4.1-mini");
        models.add(modelB);
        return models;
    }

    private static class TestSettingsService extends CodemossSettingsService {
        protected final JsonObject provider;
        private final JsonObject localModelState;

        TestSettingsService(JsonObject provider, JsonObject localModelState) {
            this.provider = provider;
            this.localModelState = localModelState;
        }

        @Override
        public JsonObject getCodexProviderById(String providerId) {
            if (provider == null || !providerId.equals(provider.get("id").getAsString())) {
                return null;
            }
            return provider.deepCopy();
        }

        @Override
        public JsonObject getCurrentCodexModelState() {
            return localModelState.deepCopy();
        }

        @Override
        public JsonObject getSelectedCodexModel() {
            return new JsonObject();
        }

        @Override
        public JsonObject getActiveCodexProvider() throws IOException {
            return provider == null ? null : provider.deepCopy();
        }
    }

    private static class TrackingSettingsService extends TestSettingsService {
        private boolean localConfigAuthorized;
        private boolean switchCodexProviderCalled;

        TrackingSettingsService(JsonObject provider, JsonObject localModelState) {
            super(provider, localModelState);
        }

        @Override
        public void setCodexLocalConfigAuthorized(boolean authorized) {
            this.localConfigAuthorized = authorized;
        }

        @Override
        public void switchCodexProvider(String id) {
            this.switchCodexProviderCalled = true;
        }

        @Override
        public JsonObject getCurrentCodexConfig() {
            JsonObject config = new JsonObject();
            config.addProperty("authorized", localConfigAuthorized);
            return config;
        }
    }

    /**
     * 跟踪“获取模型列表”链路中设置服务的合并入参与 provider 列表回传。
     * 该桩把 discovery -> merge -> updateCodexProviders 三段串起来，
     * 便于测试只聚焦操作处理器的编排责任，而不依赖真实配置落盘。
     */
    private static class TrackingFetchSettingsService extends TestSettingsService {
        private String lastMergedProviderId = "";
        private List<String> lastMergedModelIds = List.of();
        private CodexProviderManager.CodexProviderModelMergeResult mergeResult =
                new CodexProviderManager.CodexProviderModelMergeResult(0, 0, 0);
        private boolean localSyncCalled;
        private JsonArray savedCliLoginDiscoveredModels = new JsonArray();
        private JsonArray existingCliLoginDiscoveredModels = new JsonArray();
        private CodexLocalModelSyncService.LocalModelSyncResult localSyncResult;
        private String lastClearedExclusionProviderId = "";

        TrackingFetchSettingsService(JsonObject provider, JsonObject localModelState) {
            super(provider, localModelState);
        }

        @Override
        public CodexProviderManager.CodexProviderModelMergeResult mergeCodexProviderModels(
                String providerId,
                List<String> fetchedModelIds
        ) {
            this.lastMergedProviderId = providerId;
            this.lastMergedModelIds = new ArrayList<>(fetchedModelIds);
            return mergeResult;
        }

        @Override
        public List<JsonObject> getCodexProviders() {
            List<JsonObject> providers = new ArrayList<>();
            if (provider != null) {
                providers.add(provider.deepCopy());
            }
            return providers;
        }

        /**
         * 模型同步成功后需要推送统一目录；测试里返回空 catalog 即可验证回调被触发。
         */
        @Override
        public JsonObject getCodexModelDisplayConfig() {
            JsonObject config = new JsonObject();
            config.add("catalog", new JsonArray());
            config.add("visibility", new JsonObject());
            return config;
        }

        /**
         * 模拟 settings service 在保存 CLI discovered models 时的副作用。
         * 这里除了记录最新缓存，还要同步记录“已按 provider 维度清理 exclusion”，
         * 以便 handler 测试继续校验完整的同步语义，而不是只校验数组写入。
         *
         * @param discoveredModels 待保存的 CLI discovered models
         */
        @Override
        public void saveCodexCliLoginDiscoveredModels(JsonArray discoveredModels) {
            this.savedCliLoginDiscoveredModels = discoveredModels == null ? new JsonArray() : discoveredModels.deepCopy();
            this.lastClearedExclusionProviderId = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID;
        }

        @Override
        public JsonArray getCodexCliLoginDiscoveredModels() {
            return existingCliLoginDiscoveredModels.deepCopy();
        }

        @Override
        public void clearCodexModelCatalogExclusionsByProviderId(String providerId) {
            this.lastClearedExclusionProviderId = providerId == null ? "" : providerId;
        }
    }

    /**
     * 用于给操作处理器注入可预测结果的 discovery service。
     * 这里通过覆盖 `discoverModels`，把网络访问折叠成固定成功结果或固定异常，
     * 让测试能专注断言 handler 编排，而不是再次覆盖底层 HTTP 细节。
     */
    private static class RecordingDiscoveryService extends CodexProviderModelDiscoveryService {
        private final DiscoveryResult result;
        private final IOException failure;
        private JsonObject lastProvider = new JsonObject();

        RecordingDiscoveryService(CodemossSettingsService settingsService, DiscoveryResult result) {
            super(settingsService, ignored -> "", (uri, authorizationHeader, acceptHeader) -> {
                throw new IOException("Transport should not be called in overridden discovery service");
            });
            this.result = result;
            this.failure = null;
        }

        RecordingDiscoveryService(CodemossSettingsService settingsService, IOException failure) {
            super(settingsService, ignored -> "", (uri, authorizationHeader, acceptHeader) -> {
                throw new IOException("Transport should not be called in overridden discovery service");
            });
            this.result = null;
            this.failure = failure;
        }

        @Override
        public DiscoveryResult discoverModels(JsonObject provider) throws IOException {
            lastProvider = provider == null ? new JsonObject() : provider.deepCopy();
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        /**
         * 返回最近一次 discoverModels 收到的 provider 副本。
         *
         * @return 最近一次入参；尚未调用时返回空对象
         */
        JsonObject getLastProvider() {
            return lastProvider.deepCopy();
        }
    }

    /**
     * 用于给 CLI Login 本地模型同步链路注入可预测结果的同步服务桩。
     * 该桩只记录“是否被调用”和预设结果，避免单元测试依赖真实 ~/.codex 文件或网络请求。
     */
    private static class FailingLocalModelSyncService extends CodexLocalModelSyncService {
        private final TrackingFetchSettingsService settingsService;
        private final IOException failure;

        FailingLocalModelSyncService(TrackingFetchSettingsService settingsService, IOException failure) {
            super(settingsService, ignored -> "", null);
            this.settingsService = settingsService;
            this.failure = failure;
        }

        @Override
        public LocalModelSyncResult syncLocalModels() throws IOException {
            settingsService.localSyncCalled = true;
            throw failure;
        }
    }

    private static class RecordingLocalModelSyncService extends CodexLocalModelSyncService {
        private final TrackingFetchSettingsService settingsService;
        private final LocalModelSyncResult result;

        RecordingLocalModelSyncService(
                TrackingFetchSettingsService settingsService,
                LocalModelSyncResult result
        ) {
            super(settingsService, ignored -> "", null);
            this.settingsService = settingsService;
            this.result = result;
        }

        @Override
        public LocalModelSyncResult syncLocalModels() throws IOException {
            settingsService.localSyncCalled = true;
            return result;
        }
    }

    private static class RecordingCodexSDKBridge extends CodexSDKBridge {
        private int sendMessageCallCount;

        /**
         * 返回测试过程中 sendMessage 被调用的次数。
         *
         * @return 调用次数；未调用时为 0
         */
        int getSendMessageCallCount() {
            return sendMessageCallCount;
        }

        @Override
        public CompletableFuture<SDKResult> sendMessage(
                String channelId,
                String message,
                String threadId,
                String cwd,
                java.util.List<com.github.claudecodegui.session.ClaudeSession.Attachment> attachments,
                String permissionMode,
                String model,
                String agentPrompt,
                String reasoningEffort,
                com.github.claudecodegui.provider.codex.CodexRuntimeProfile runtimeProfile,
                MessageCallback callback
        ) {
            sendMessageCallCount++;
            SDKResult result = SDKResult.success("OK");
            callback.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }
    }

    private static class RecordingJsCallback implements HandlerContext.JsCallback {
        private String lastFunctionName = "";
        private final List<String> lastArgs = new ArrayList<>();
        private final List<String> functionHistory = new ArrayList<>();
        private final List<String> argHistory = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            this.lastFunctionName = functionName;
            this.functionHistory.add(functionName);
            this.lastArgs.clear();
            if (args != null) {
                for (String arg : args) {
                    this.lastArgs.add(arg);
                    this.argHistory.add(arg);
                }
            }
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }

        JsonObject getLastPayload() {
            if (lastArgs.isEmpty()) {
                return new JsonObject();
            }
            JsonElement parsed = com.google.gson.JsonParser.parseString(lastArgs.get(0));
            return parsed.getAsJsonObject();
        }

        /**
         * 判断指定 JS 回调是否在当前测试过程中被触发过。
         *
         * @param functionName 目标 JS 函数名
         * @return 若调用历史中出现过则返回 true
         */
        boolean hasFunctionCall(String functionName) {
            return functionHistory.contains(functionName);
        }

        /**
         * 判断当前测试过程中任一 JS 参数中是否出现指定片段。
         * 该辅助方法用于断言成功提示文案内容，避免测试误依赖“最后一次回调一定是提示框”。
         *
         * @param fragment 需要匹配的参数片段
         * @return 只要任一历史参数包含该片段就返回 true
         */
        boolean containsArgFragment(String fragment) {
            if (fragment == null || fragment.isEmpty()) {
                return false;
            }
            for (String arg : argHistory) {
                if (arg != null && arg.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }
}
