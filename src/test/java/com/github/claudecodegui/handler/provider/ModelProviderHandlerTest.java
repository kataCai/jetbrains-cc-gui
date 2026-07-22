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

    /**
     * 验证目标：设置页删除单个模型目录项时，handler 应提供独立入口并在成功后刷新统一目录。
     * 前置条件：后端配置服务已经能按来源处理删除逻辑；这里的 handler 只负责桥接调用与目录回推。
     * 断言意图：
     * 1. handler 会把完整目录项 payload 交给 settings service；
     * 2. 删除成功后会重新推送最新 catalog；
     * 3. 不会误走“保存 visibility 配置”的旧链路。
     */
    @Test
    public void shouldDeleteCodexModelCatalogItemAndRefreshCatalog() throws Exception {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        CatalogDeleteSettingsService settingsService = new CatalogDeleteSettingsService();
        HandlerContext context = new HandlerContext(
                null,
                null,
                null,
                settingsService,
                jsCallback
        );
        ModelProviderHandler handler = new ModelProviderHandler(context, new UsagePushService(context));

        handler.handleDeleteCodexModelCatalogItem(
                "{\"key\":\"minimax::MiniMax-M3\",\"providerId\":\"minimax\",\"modelId\":\"MiniMax-M3\",\"source\":\"managed_provider\"}"
        );

        assertEquals("minimax::MiniMax-M3", settingsService.lastDeletedCatalogKey);
        assertEquals("managed_provider", settingsService.lastDeletedCatalogSource);
        assertTrue(settingsService.deleteCatalogItemCalled);
        assertTrue(jsCallback.functionNames.contains("window.updateCodexModelCatalog"));
    }

    @Test
    public void shouldKeepSelectCodexModelScopedToCurrentTabWithoutMutatingActiveSession() {
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
        assertEquals("codex", context.getCurrentProvider());
        assertEquals("gpt-5.4", context.getCurrentModel());
        // 中文注释：聊天区下拉切换只更新“下一条消息想用的目标模型”，
        // 当前正在运行的物理 session 必须保持原样，避免选择器立刻污染 live runtime。
        assertTrue(session.getState().getCodexSessionBinding() == null);
        assertFalse("gpt-5.4".equals(session.getModel()));
        assertEquals(0, persistCount.get());
        assertFalse(jsCallback.functionNames.contains("window.restoreTabRuntimeState"));
    }

    @Test
    public void shouldSwitchTabCodexProviderWithoutMutatingActiveSession() {
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
        assertEquals("provider-b", settingsService.lastSetSelectedProviderId);
        assertEquals("gpt-5.5", settingsService.lastSetSelectedModelId);
        assertEquals("codex", context.getCurrentProvider());
        assertEquals("gpt-5.5", context.getCurrentModel());
        // 中文注释：provider 下拉同样属于 desired selection，旧 session 绑定仍应保持 provider-a。
        assertEquals("provider-a", session.getState().getCodexSessionBinding().getProviderId());
        assertEquals("gpt-5.4", session.getModel());
        assertEquals(0, persistCount.get());
        assertFalse(jsCallback.functionNames.contains("window.restoreTabRuntimeState"));
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

        assertEquals("low", settingsService.lastReasoningEffort);
        assertFalse("low".equals(context.getSession().getReasoningEffort()));
    }

    @Test
    public void shouldKeepSelectionOnlyEventsOutOfActiveSessionForClaudeProviderAndModel() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        TabScopedSettingsService settingsService = new TabScopedSettingsService();
        HandlerContext context = new HandlerContext(createProject(), null, null, settingsService, jsCallback);
        ClaudeSession session = new ClaudeSession(createProject(), null, null);
        session.setProvider("claude");
        session.setModel("claude-sonnet-4-6");
        context.setSession(session);

        ModelProviderHandler handler = new ModelProviderHandler(context, new UsagePushService(context));
        handler.handleSetProvider("{\"provider\":\"codex\"}");
        handler.handleSetModel("{\"model\":\"gpt-5.5\"}");

        // 中文注释：上下文中的 desired provider/model 可以更新，供新会话或发送时 runtime intent 解析使用；
        // 但当前活动 session 仍必须保留旧运行态，不能被聊天区选择器即时改写。
        assertEquals("codex", context.getCurrentProvider());
        assertEquals("gpt-5.5", context.getCurrentModel());
        assertEquals("claude", session.getProvider());
        assertEquals("claude-sonnet-4-6", session.getModel());
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

    /**
     * 用于验证“删除目录项”handler 桥接行为的设置服务桩。
     * 该桩只记录删除入参与刷新后的目录内容，不承担真实配置持久化职责。
     */
    private static class CatalogDeleteSettingsService extends CodemossSettingsService {
        private boolean deleteCatalogItemCalled;
        private String lastDeletedCatalogKey = "";
        private String lastDeletedCatalogSource = "";

        @Override
        public void deleteCodexModelCatalogItem(JsonObject payload) {
            deleteCatalogItemCalled = true;
            lastDeletedCatalogKey = payload.get("key").getAsString();
            lastDeletedCatalogSource = payload.get("source").getAsString();
        }

        @Override
        public JsonObject getCodexModelDisplayConfig() {
            JsonObject config = new JsonObject();
            JsonArray catalog = new JsonArray();
            JsonObject item = new JsonObject();
            item.addProperty("key", "minimax::MiniMax-M3-Preview");
            item.addProperty("providerId", "minimax");
            item.addProperty("modelId", "MiniMax-M3-Preview");
            item.addProperty("visible", true);
            catalog.add(item);
            config.add("catalog", catalog);
            config.add("visibility", new JsonObject());
            return config;
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
