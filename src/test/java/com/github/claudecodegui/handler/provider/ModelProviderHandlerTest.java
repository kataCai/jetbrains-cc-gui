package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.UsagePushService;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ModelProviderHandler 模型解析与标签页级 Codex 运行态回归测试。
 * 该测试集既覆盖 Claude/Codex 模型解析，也覆盖“聊天页选择只影响当前标签”的隔离约束。
 */
public class ModelProviderHandlerTest {

    @Test
    public void shouldPreferMainModelOverrideForAllClaudeModelFamilies() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "glm-4.7");
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "ignored-sonnet");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-opus-4-6", env);

        assertEquals("glm-4.7", resolved);
    }

    @Test
    public void shouldUseFamilySpecificMappingForSelectedClaudeModel() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_HAIKU_MODEL", "haiku-proxy");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-haiku-4-5", env);

        assertEquals("haiku-proxy", resolved);
    }

    @Test
    public void shouldIgnoreSmallFastModelForHaikuResolution() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_SMALL_FAST_MODEL", "legacy-haiku-proxy");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-haiku-4-5", env);

        assertEquals("claude-haiku-4-5", resolved);
    }

    @Test
    public void shouldNotApplySonnetMappingToAlreadyCustomModelIds() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "glm-4.7");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("deepseek-v3", env);

        assertEquals("deepseek-v3", resolved);
    }

    @Test
    public void shouldUseResolvedModelForContextLimitWhenCapacitySuffixExists() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "glm-4.7[1M]");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-sonnet-4-6", env);

        assertEquals("glm-4.7[1M]", resolved);
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit(resolved));
    }

    @Test
    public void shouldKeepExpectedContextLimitsForVisibleCodexModels() {
        assertEquals(400_000, ModelProviderHandler.getModelContextLimit("gpt-5.5"));
        assertEquals(400_000, ModelProviderHandler.getModelContextLimit("gpt-5.4-mini"));
        assertEquals(258_000, ModelProviderHandler.getModelContextLimit("gpt-5.2"));
        assertEquals(258_000, ModelProviderHandler.getModelContextLimit("gpt-5.3-codex"));
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("gpt-5.4"));
        assertEquals(258_000, ModelProviderHandler.getModelContextLimit("gpt-5.2-codex"));
    }

    @Test
    public void shouldReturnCorrectContextLimitsForClaudeModels() {
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-sonnet-4-6"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-opus-4-7"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-opus-4-6"));
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("claude-sonnet-4-6[1m]"));
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("claude-opus-4-7[1m]"));
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("claude-opus-4-6[1m]"));
        assertEquals(200_000, ModelProviderHandler.getModelContextLimit("claude-haiku-4-5"));
    }

    @Test
    public void shouldParseCapacitySuffixForCustomContextLimits() {
        assertEquals(500_000, ModelProviderHandler.getModelContextLimit("custom-model[500k]"));
        assertEquals(2_000_000, ModelProviderHandler.getModelContextLimit("custom-model[2m]"));
        assertEquals(100_000, ModelProviderHandler.getModelContextLimit("custom-model[100K]"));
    }

    @Test
    public void shouldReturnCatalogArrayForCodexModelCatalogCallback() throws Exception {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        JsonObject catalogConfig = new JsonObject();
        JsonArray catalog = new JsonArray();
        JsonObject item = new JsonObject();
        item.addProperty("key", "provider::model");
        item.addProperty("providerId", "provider");
        item.addProperty("modelId", "model");
        item.addProperty("visible", true);
        catalog.add(item);
        catalogConfig.add("catalog", catalog);
        catalogConfig.add("visibility", new JsonObject());

        HandlerContext context = new HandlerContext(
                null,
                null,
                null,
                new CatalogOnlySettingsService(catalogConfig),
                jsCallback
        );
        ModelProviderHandler handler = new ModelProviderHandler(
                context,
                new UsagePushService(context)
        );

        handler.handleGetCodexModelCatalog();

        assertEquals("window.updateCodexModelCatalog", jsCallback.lastFunctionName);
        assertTrue(jsCallback.lastArg.startsWith("["));
    }

    @Test
    public void shouldKeepSelectCodexModelScopedToCurrentTab() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TabScopedSettingsService settingsService = new TabScopedSettingsService();
        settingsService.addProvider("provider-a", "gpt-5.4");

        Project project = createProject();
        ClaudeSession session = new ClaudeSession(project, null, null);
        HandlerContext context = new HandlerContext(project, null, null, settingsService, jsCallback);
        context.setSession(session);
        AtomicInteger persistCount = new AtomicInteger();
        context.setTabSessionPersistenceCallback(persistCount::incrementAndGet);

        ModelProviderHandler handler = new ModelProviderHandler(context, new UsagePushService(context));
        handler.handleSelectCodexModel("{\"providerId\":\"provider-a\",\"modelId\":\"gpt-5.4\"}");

        assertFalse(settingsService.selectCodexModelCalled);
        assertEquals("provider-a", settingsService.lastSetSelectedProviderId);
        assertEquals("gpt-5.4", settingsService.lastSetSelectedModelId);
        assertEquals("codex", session.getProvider());
        assertEquals("gpt-5.4", session.getModel());
        CodexSessionBinding binding = session.getState().getCodexSessionBinding();
        assertNotNull(binding);
        assertEquals("provider-a", binding.getProviderId());
        assertEquals("gpt-5.4", binding.getModel());
        assertEquals(1, persistCount.get());
        assertTrue(jsCallback.functionNames.contains("window.restoreTabRuntimeState"));

        JsonObject runtimePayload = jsCallback.findFirstPayload("window.restoreTabRuntimeState");
        assertEquals("codex", runtimePayload.get("provider").getAsString());
        assertEquals("gpt-5.4", runtimePayload.get("model").getAsString());
        assertEquals("provider-a", runtimePayload.get("codexProviderId").getAsString());
    }

    @Test
    public void shouldSwitchTabCodexProviderWithoutMutatingGlobalCurrentProvider() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TabScopedSettingsService settingsService = new TabScopedSettingsService();
        settingsService.addProvider("provider-a", "gpt-5.4");
        settingsService.addProvider("provider-b", "gpt-5.5");
        settingsService.setInitialSelectedModel("provider-b", "gpt-5.5");

        Project project = createProject();
        ClaudeSession session = new ClaudeSession(project, null, null);
        session.setProvider("codex");
        session.setModel("gpt-5.4");
        session.getState().setCodexSessionBinding(new CodexSessionBinding(
                "provider-a",
                "gpt-5.4",
                "codex_sdk",
                "provider",
                "codemoss_managed_provider"
        ));

        HandlerContext context = new HandlerContext(project, null, null, settingsService, jsCallback);
        context.setSession(session);
        AtomicInteger persistCount = new AtomicInteger();
        context.setTabSessionPersistenceCallback(persistCount::incrementAndGet);

        ModelProviderHandler handler = new ModelProviderHandler(context, new UsagePushService(context));
        handler.handleSetTabCodexProvider("{\"providerId\":\"provider-b\"}");

        assertFalse(settingsService.selectCodexModelCalled);
        assertEquals("", settingsService.lastSetSelectedProviderId);
        assertEquals("provider-b", session.getState().getCodexSessionBinding().getProviderId());
        assertEquals("gpt-5.5", session.getModel());
        assertEquals(1, persistCount.get());

        JsonObject runtimePayload = jsCallback.findFirstPayload("window.restoreTabRuntimeState");
        assertEquals("provider-b", runtimePayload.get("codexProviderId").getAsString());
        assertEquals("gpt-5.5", runtimePayload.get("model").getAsString());
    }

    @Test
    public void describeCodexBindingForTraceShouldExposeSessionScopedSelection() {
        CodexSessionBinding binding = new CodexSessionBinding(
                "provider-b",
                "gpt-5.5",
                "codex_sdk",
                "provider",
                "managed_provider"
        );

        String description = ModelProviderHandler.describeCodexBindingForTrace(binding);

        assertTrue(description.contains("providerId=provider-b"));
        assertTrue(description.contains("model=gpt-5.5"));
        assertTrue(description.contains("requestMode=codex_sdk"));
        assertTrue(description.contains("baseUrlSource=provider"));
        assertTrue(description.contains("effectiveConfigSource=managed_provider"));
    }

    @Test
    public void shouldPersistLastCodexReasoningEffortWhenUserChangesReasoning() throws Exception {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TabScopedSettingsService settingsService = new TabScopedSettingsService();
        HandlerContext context = new HandlerContext(createProject(), null, null, settingsService, jsCallback);
        context.setSession(new ClaudeSession(createProject(), null, null));

        ModelProviderHandler handler = new ModelProviderHandler(context, new UsagePushService(context));
        handler.handleSetReasoningEffort("{\"reasoningEffort\":\"low\"}");

        assertEquals("low", context.getSession().getReasoningEffort());
        assertEquals("low", settingsService.lastReasoningEffort);
    }

    private static Project createProject() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isDisposed" -> false;
                    case "getName" -> "model-provider-handler-test";
                    default -> method.getReturnType().isPrimitive() ? defaultPrimitiveValue(method.getReturnType()) : null;
                }
        );
    }

    private static Object defaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        return 0;
    }

    private static class CatalogOnlySettingsService extends CodemossSettingsService {
        private final JsonObject catalogConfig;

        CatalogOnlySettingsService(JsonObject catalogConfig) {
            this.catalogConfig = catalogConfig;
        }

        @Override
        public JsonObject getCodexModelDisplayConfig() {
            return catalogConfig.deepCopy();
        }
    }

    private static class TabScopedSettingsService extends CodemossSettingsService {
        private final Map<String, JsonObject> providers = new HashMap<>();
        private final JsonObject selectedModel = new JsonObject();
        private boolean selectCodexModelCalled;
        private String lastSetSelectedProviderId = "";
        private String lastSetSelectedModelId = "";
        private String lastReasoningEffort = "";

        void addProvider(String providerId, String... modelIds) {
            JsonObject provider = new JsonObject();
            provider.addProperty("id", providerId);
            JsonArray models = new JsonArray();
            for (String modelId : modelIds) {
                JsonObject model = new JsonObject();
                model.addProperty("id", modelId);
                models.add(model);
            }
            provider.add("models", models);
            providers.put(providerId, provider);
        }

        void setInitialSelectedModel(String providerId, String modelId) {
            selectedModel.addProperty("providerId", providerId);
            selectedModel.addProperty("modelId", modelId);
        }

        @Override
        public void setSelectedCodexModel(String providerId, String modelId) {
            lastSetSelectedProviderId = providerId;
            lastSetSelectedModelId = modelId;
            selectedModel.addProperty("providerId", providerId);
            selectedModel.addProperty("modelId", modelId);
        }

        @Override
        public void selectCodexModel(String providerId, String modelId) {
            selectCodexModelCalled = true;
        }

        @Override
        public JsonObject getSelectedCodexModel() {
            return selectedModel.deepCopy();
        }

        @Override
        public JsonObject getCodexProviderById(String providerId) {
            JsonObject provider = providers.get(providerId);
            return provider == null ? null : provider.deepCopy();
        }

        @Override
        public void setLastCodexReasoningEffort(String reasoningEffort) {
            lastReasoningEffort = reasoningEffort;
        }
    }

    private static class RecordingJsCallback implements HandlerContext.JsCallback {
        private final Gson gson = new Gson();
        private final List<String> functionNames = new ArrayList<>();
        private final List<String> payloads = new ArrayList<>();
        private String lastFunctionName = "";
        private String lastArg = "";

        @Override
        public void callJavaScript(String functionName, String... args) {
            this.lastFunctionName = functionName;
            this.lastArg = args != null && args.length > 0 ? args[0] : "";
            this.functionNames.add(functionName);
            this.payloads.add(this.lastArg);
        }

        JsonObject findFirstPayload(String functionName) {
            for (int i = 0; i < functionNames.size(); i++) {
                if (functionName.equals(functionNames.get(i))) {
                    return gson.fromJson(payloads.get(i), JsonObject.class);
                }
            }
            return new JsonObject();
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }
    }
}
