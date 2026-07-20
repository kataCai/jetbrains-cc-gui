package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexProviderModelDiscoveryService;
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
 * 2. 本地环境变量缺失时会在 resolver 阶段失败；
 * 3. bridge 返回成功时会展示最终生效 runtime profile 摘要。
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
        assertTrue(jsCallback.lastArgs.stream().anyMatch(arg -> arg.contains("Managed Provider")));
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

        assertEquals("window.showSuccess", jsCallback.lastFunctionName);
        assertTrue(jsCallback.lastArgs.get(0).contains("No new models"));
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
    }

    /**
     * 用于给操作处理器注入可预测结果的 discovery service。
     * 这里通过覆盖 `discoverModels`，把网络访问折叠成固定成功结果或固定异常，
     * 让测试能专注断言 handler 编排，而不是再次覆盖底层 HTTP 细节。
     */
    private static class RecordingDiscoveryService extends CodexProviderModelDiscoveryService {
        private final DiscoveryResult result;
        private final IOException failure;

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
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static class RecordingCodexSDKBridge extends CodexSDKBridge {
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
            SDKResult result = SDKResult.success("OK");
            callback.onComplete(result);
            return CompletableFuture.completedFuture(result);
        }
    }

    private static class RecordingJsCallback implements HandlerContext.JsCallback {
        private String lastFunctionName = "";
        private final List<String> lastArgs = new ArrayList<>();
        private final List<String> functionHistory = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            this.lastFunctionName = functionName;
            this.functionHistory.add(functionName);
            this.lastArgs.clear();
            if (args != null) {
                for (String arg : args) {
                    this.lastArgs.add(arg);
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
    }
}
