package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.UsagePushService;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ModelProviderHandler 模型解析与上下文容量映射回归测试。
 * 这组测试覆盖两类关键行为：
 * 1. Claude 模型在 provider/env 映射后的真实模型解析；
 * 2. Claude/Codex/GPT 以及自定义容量后缀模型的上下文限制计算。
 * 并轨时需要同时保留本地主线补充的 GPT/Codex 容量映射和 upstream 新增的容量解析断言，避免任一侧能力回退。
 */
public class ModelProviderHandlerTest {

    /**
     * 验证主模型覆盖项优先级最高。
     * 当前置了 ANTHROPIC_MODEL 时，不应再回退到 Sonnet/Opus/Haiku 的家族映射。
     */
    @Test
    public void shouldPreferMainModelOverrideForAllClaudeModelFamilies() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_MODEL", "glm-4.7");
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "ignored-sonnet");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-opus-4-6", env);

        assertEquals("glm-4.7", resolved);
    }

    /**
     * 验证 Claude 家族模型会按家族粒度读取映射配置。
     * 这里覆盖 Haiku 家族，确保不同默认模型键的回退逻辑不被并轨破坏。
     */
    @Test
    public void shouldUseFamilySpecificMappingForSelectedClaudeModel() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_HAIKU_MODEL", "haiku-proxy");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-haiku-4-5", env);

        assertEquals("haiku-proxy", resolved);
    }

    /**
     * 验证非 Claude 自定义模型 ID 不会误套用 Claude Sonnet 映射。
     * 该场景覆盖接入第三方代理模型时的边界，避免把任意模型名都按 Claude 规则重写。
     */
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

    /**
     * 验证当映射后的真实模型自带容量后缀时，会优先按真实模型容量推导上下文限制。
     * 这能保证 UI 上选择 Claude 模型、但 provider 实际落到代理模型时，容量条仍然准确。
     */
    @Test
    public void shouldUseResolvedModelForContextLimitWhenCapacitySuffixExists() {
        JsonObject env = new JsonObject();
        env.addProperty("ANTHROPIC_DEFAULT_SONNET_MODEL", "glm-4.7[1M]");

        String resolved = ModelProviderHandler.resolveConfiguredClaudeModel("claude-sonnet-4-6", env);

        assertEquals("glm-4.7[1M]", resolved);
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit(resolved));
    }

    /**
     * 验证当前 UI 可见的 Codex/GPT 模型上下文限制映射。
     * 这里同时覆盖本地主线补充的 gpt-5.5/gpt-5.4-mini/gpt-5.2，
     * 以及 upstream 引入的 gpt-5.3-codex/gpt-5.4/gpt-5.2-codex，避免并轨后能力表回退。
     */
    @Test
    public void shouldKeepExpectedContextLimitsForVisibleCodexModels() {
        assertEquals(400_000, ModelProviderHandler.getModelContextLimit("gpt-5.5"));
        assertEquals(400_000, ModelProviderHandler.getModelContextLimit("gpt-5.4-mini"));
        assertEquals(258_000, ModelProviderHandler.getModelContextLimit("gpt-5.2"));
        assertEquals(258_000, ModelProviderHandler.getModelContextLimit("gpt-5.3-codex"));
        assertEquals(1_000_000, ModelProviderHandler.getModelContextLimit("gpt-5.4"));
        assertEquals(258_000, ModelProviderHandler.getModelContextLimit("gpt-5.2-codex"));
    }

    /**
     * 验证 Claude 模型的基础容量和 [1m] 后缀容量映射。
     * 该测试保证并轨后不破坏 200k / 1M 的容量语义，也覆盖 Haiku 无 1M 档位的边界。
     */
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

    /**
     * 验证自定义模型名中的容量后缀解析。
     * 这里覆盖 k/m 和大小写混用场景，确保后续 provider 返回代理模型名时仍能正确推导上下文限制。
     */
    @Test
    public void shouldParseCapacitySuffixForCustomContextLimits() {
        assertEquals(500_000, ModelProviderHandler.getModelContextLimit("custom-model[500k]"));
        assertEquals(2_000_000, ModelProviderHandler.getModelContextLimit("custom-model[2m]"));
        assertEquals(100_000, ModelProviderHandler.getModelContextLimit("custom-model[100K]"));
    }

    /**
     * 验证模型目录回调继续维持“数组载荷”协议，而不是把 visibility 包装对象直接透传到前端。
     */
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

    private static class RecordingJsCallback implements HandlerContext.JsCallback {
        private String lastFunctionName = "";
        private String lastArg = "";

        @Override
        public void callJavaScript(String functionName, String... args) {
            this.lastFunctionName = functionName;
            this.lastArg = args != null && args.length > 0 ? args[0] : "";
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }
    }
}
