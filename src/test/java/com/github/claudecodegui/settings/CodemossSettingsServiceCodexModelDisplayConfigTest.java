package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Codex 模型目录与展示配置测试。
 * 这组测试只覆盖 Task 1 的 schema / config 基建：
 * 1. 读写 `codex.modelDisplay`；
 * 2. 缺失 `modelDisplay` 时的默认迁移；
 * 3. 复合 key 与目录项的后端 JSON 结构。
 */
public class CodemossSettingsServiceCodexModelDisplayConfigTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldExposeModelDisplayConfigSchemaForDiscoveredModels() throws Exception {
        // 测试目标：验证后端读接口会把当前可发现模型归一化成稳定 schema，并生成 providerId::modelId 复合 key。
        // 前置条件：配置中存在两个 provider，且每个 provider 挂有可发现的 models 数组。
        // 断言意图：catalog 必须包含每个模型项，visibility 必须按复合 key 建立可见状态，缺失配置时默认 visible=true。
        Path tempHome = Files.createTempDirectory("codex-model-display-schema-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithoutModelDisplay(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);

        JsonArray catalog = displayConfig.getAsJsonArray("catalog");
        JsonObject visibility = displayConfig.getAsJsonObject("visibility");

        JsonObject providerAGpt5 = findCatalogItemByKey(catalog, "provider-a::gpt-5");
        assertTrue(catalog.size() >= 3);
        assertEquals("provider-a::gpt-5", providerAGpt5.get("key").getAsString());
        assertEquals("provider-a", providerAGpt5.get("providerId").getAsString());
        assertEquals("gpt-5", providerAGpt5.get("modelId").getAsString());
        assertEquals("GPT-5", providerAGpt5.get("label").getAsString());
        assertEquals("managed_provider", providerAGpt5.get("source").getAsString());
        assertTrue(providerAGpt5.get("runnable").getAsBoolean());
        assertTrue(providerAGpt5.get("visible").getAsBoolean());

        assertTrue(visibility.has("provider-a::gpt-5"));
        assertTrue(visibility.getAsJsonObject("provider-a::gpt-5").get("visible").getAsBoolean());
        assertTrue(visibility.getAsJsonObject("provider-a::gpt-5-mini").get("visible").getAsBoolean());
        assertTrue(visibility.getAsJsonObject("provider-b::o3").get("visible").getAsBoolean());
    }

    @Test
    public void shouldPersistModelDisplayVisibilityConfig() throws Exception {
        // 测试目标：验证保存接口只更新 `codex.modelDisplay`，并且下次读取时能恢复同一份可见性配置。
        // 前置条件：当前配置已有可发现模型，随后显式保存部分模型 hidden。
        // 断言意图：读取结果里的 visibility 与 catalog.visible 都必须反映最新持久化结果。
        Path tempHome = Files.createTempDirectory("codex-model-display-persist-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithoutModelDisplay(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject toSave = new JsonObject();
        JsonObject providerAVisible = new JsonObject();
        providerAVisible.addProperty("visible", false);
        JsonObject providerBVisible = new JsonObject();
        providerBVisible.addProperty("visible", true);
        toSave.add("provider-a::gpt-5", providerAVisible);
        toSave.add("provider-b::o3", providerBVisible);

        invokeSaveCodexModelDisplayConfig(service, toSave);
        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);

        assertFalse(displayConfig.getAsJsonObject("visibility")
                .getAsJsonObject("provider-a::gpt-5")
                .get("visible")
                .getAsBoolean());
        assertTrue(displayConfig.getAsJsonObject("visibility")
                .getAsJsonObject("provider-b::o3")
                .get("visible")
                .getAsBoolean());

        JsonObject providerAGpt5 = findCatalogItemByKey(displayConfig.getAsJsonArray("catalog"), "provider-a::gpt-5");
        assertFalse(providerAGpt5.get("visible").getAsBoolean());
    }

    @Test
    public void shouldMigrateMissingModelDisplayToAllVisibleForDiscoveredModels() throws Exception {
        // 测试目标：覆盖兼容迁移逻辑，确保旧配置缺失 `modelDisplay` 时不会丢失模型展示能力。
        // 前置条件：配置文件里只有 codex.providers.models，没有任何 modelDisplay 字段。
        // 断言意图：返回结果必须为每个可发现模型生成 visible=true，且写回配置后落盘结构仍保持同一套复合 key。
        Path tempHome = Files.createTempDirectory("codex-model-display-migration-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithoutModelDisplay(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);
        JsonObject persistedConfig = service.readConfig();

        assertTrue(displayConfig.getAsJsonObject("visibility")
                .getAsJsonObject("provider-a::gpt-5")
                .get("visible")
                .getAsBoolean());
        assertTrue(persistedConfig.getAsJsonObject("codex").has("modelDisplay"));
        assertTrue(persistedConfig.getAsJsonObject("codex")
                .getAsJsonObject("modelDisplay")
                .getAsJsonObject("provider-a::gpt-5")
                .get("visible")
                .getAsBoolean());
    }

    @Test
    public void shouldIgnoreInvalidVisibleValueTypesAndFallbackToDefaultVisible() throws Exception {
        // 测试目标：覆盖脏配置容错，确保 `visible` 不是布尔值时不会导致读取接口抛异常。
        // 前置条件：旧配置中的 modelDisplay 条目存在，但 visible 被手工改成了对象。
        // 断言意图：后端应忽略非法值，回退为默认 visible=true，并把修正后的布尔结构写回配置。
        Path tempHome = Files.createTempDirectory("codex-model-display-invalid-visible-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithInvalidVisibleValue(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);
        JsonObject persistedConfig = service.readConfig();

        assertTrue(displayConfig.getAsJsonObject("visibility").has("provider-a::gpt-5"));
        assertTrue(displayConfig.getAsJsonObject("visibility")
                .getAsJsonObject("provider-a::gpt-5")
                .get("visible")
                .getAsBoolean());
        assertTrue(displayConfig.getAsJsonArray("catalog")
                .get(0)
                .getAsJsonObject()
                .get("visible")
                .getAsBoolean());
        assertTrue(persistedConfig.getAsJsonObject("codex")
                .getAsJsonObject("modelDisplay")
                .getAsJsonObject("provider-a::gpt-5")
                .get("visible")
                .getAsBoolean());
    }

    private JsonObject invokeGetCodexModelDisplayConfig(CodemossSettingsService service) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("getCodexModelDisplayConfig");
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose getCodexModelDisplayConfig()");
            throw e;
        }
        return (JsonObject) method.invoke(service);
    }

    private void invokeSaveCodexModelDisplayConfig(CodemossSettingsService service, JsonObject visibility) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("saveCodexModelDisplayConfig", JsonObject.class);
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose saveCodexModelDisplayConfig(JsonObject)");
            throw e;
        }
        method.invoke(service, visibility);
    }

    /**
     * 写入一份不包含 modelDisplay 的旧版配置，供兼容迁移测试复用。
     *
     * @param tempHome 临时 home 目录
     * @throws Exception 写文件失败时抛出
     */
    private void writeConfigWithoutModelDisplay(Path tempHome) throws Exception {
        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());
        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());
    }

    /**
     * 写入包含非法 visible 类型的脏配置，验证读取接口会忽略坏值并回写为默认布尔结构。
     *
     * @param tempHome 临时 home 目录
     * @throws Exception 写文件失败时抛出
     */
    private void writeConfigWithInvalidVisibleValue(Path tempHome) throws Exception {
        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());

        JsonObject modelDisplay = new JsonObject();
        JsonObject invalidEntry = new JsonObject();
        invalidEntry.add("visible", new JsonObject());
        modelDisplay.add("provider-a::gpt-5", invalidEntry);
        codex.add("modelDisplay", modelDisplay);

        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());
    }

    /**
     * 生成测试用 provider 集合，模拟当前可发现模型目录来源。
     *
     * @return 包含多个 provider 及其 models 的 JSON 对象
     */
    private JsonObject createProviders() {
        JsonObject providers = new JsonObject();
        providers.add("provider-a", createProvider("Provider A", createModels(
                createModel("gpt-5", "GPT-5"),
                createModel("gpt-5-mini", "GPT-5 Mini")
        )));
        providers.add("provider-b", createProvider("Provider B", createModels(
                createModel("o3", "O3")
        )));
        return providers;
    }

    /**
     * 生成单个 provider 节点。
     *
     * @param name provider 展示名
     * @param models provider 下可发现模型
     * @return provider JSON
     */
    private JsonObject createProvider(String name, JsonArray models) {
        JsonObject provider = new JsonObject();
        provider.addProperty("name", name);
        provider.addProperty("authMode", "api_key_env");
        provider.addProperty("requestMode", "codex_sdk");
        provider.add("models", models);
        return provider;
    }

    /**
     * 生成测试模型数组。
     *
     * @param models 模型节点列表
     * @return JSON 数组
     */
    private JsonArray createModels(JsonObject... models) {
        JsonArray result = new JsonArray();
        for (JsonObject model : models) {
            result.add(model);
        }
        return result;
    }

    /**
     * 生成单个测试模型节点。
     *
     * @param modelId 模型 id
     * @param label 模型展示名
     * @return 模型 JSON
     */
    private JsonObject createModel(String modelId, String label) {
        JsonObject model = new JsonObject();
        model.addProperty("id", modelId);
        model.addProperty("label", label);
        return model;
    }

    /**
     * 按复合 key 在目录数组中定位指定模型项，避免测试依赖目录拼接顺序。
     *
     * @param catalog 目录数组
     * @param key providerId::modelId 复合 key
     * @return 命中的目录项
     */
    private JsonObject findCatalogItemByKey(JsonArray catalog, String key) {
        for (int i = 0; i < catalog.size(); i++) {
            JsonObject item = catalog.get(i).getAsJsonObject();
            if (key.equals(item.get("key").getAsString())) {
                return item;
            }
        }
        fail("Catalog item not found for key: " + key);
        return new JsonObject();
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
        Files.createDirectories(tempHome.resolve(".codemoss"));
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
