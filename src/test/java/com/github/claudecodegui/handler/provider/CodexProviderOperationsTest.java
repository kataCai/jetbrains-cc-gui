package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.settings.CodemossSettingsService;
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
        private final JsonObject provider;
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

        @Override
        public void callJavaScript(String functionName, String... args) {
            this.lastFunctionName = functionName;
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
    }
}
