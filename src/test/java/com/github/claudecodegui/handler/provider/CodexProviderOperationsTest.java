package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
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
        assertEquals("false", jsCallback.lastArgs.get(0));
        assertTrue(jsCallback.lastArgs.get(1).contains("Provider not found"));
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
        assertEquals("false", jsCallback.lastArgs.get(0));
        assertTrue(jsCallback.lastArgs.get(1).contains("API key env is not set"));
    }

    @Test
    public void shouldShowSuccessSummaryWhenLiveTestPasses() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject provider = createManagedProvider();
        JsonObject localModelState = new JsonObject();
        localModelState.addProperty("model", "local-model");
        localModelState.addProperty("reasoningEffort", "high");
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
        assertEquals("true", jsCallback.lastArgs.get(0));
        assertTrue(jsCallback.lastArgs.get(1).contains("Codex provider test passed"));
        /**
         * 验证目标：
         * provider 连通性测试应基于被测 provider 自身的 runtime profile，
         * 不能被 ~/.codex/config.toml 里仅用于展示的当前本地模型污染。
         *
         * 断言意图：
         * 成功摘要里展示的 model 必须来自 provider 自身配置，便于用户判断“被测的是谁”。
         */
        assertTrue(jsCallback.lastArgs.get(1).contains("model=provider-model"));
        assertTrue(jsCallback.lastArgs.get(1).contains("authMode=api_key_env"));
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
    }
}
