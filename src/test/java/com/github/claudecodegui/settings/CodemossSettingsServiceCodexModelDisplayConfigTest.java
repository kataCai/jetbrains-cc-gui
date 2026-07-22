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

    @Test
    public void shouldPreferCliLoginDiscoveredModelsOverBuiltinCatalogWhenPresent() throws Exception {
        // 测试目标：验证 CLI Login 已同步的发现模型会覆盖内建默认模型目录，而不是和 builtin 混在一起双份返回。
        // 前置条件：配置里声明了 `codex.cliLoginDiscoveredModels`，同时不存在本地 ~/.codex 兜底模型。
        // 断言意图：目录里应出现发现模型，不应再回退到 `gpt-5.5` 这类 builtin 项。
        Path tempHome = Files.createTempDirectory("codex-cli-login-discovered-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithCliLoginDiscoveredModels(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);
        JsonArray catalog = displayConfig.getAsJsonArray("catalog");

        assertTrue(findCatalogItemByKeyOrNull(catalog, "__codex_cli_login__::remote-gpt-1") != null);
        assertTrue(findCatalogItemByKeyOrNull(catalog, "__codex_cli_login__::remote-gpt-2") != null);
        assertTrue(findCatalogItemByKeyOrNull(catalog, "__codex_cli_login__::gpt-5.5") == null);
    }

    @Test
    public void shouldFilterExcludedCatalogItemsFromCliLoginAndManagedProviders() throws Exception {
        // 测试目标：验证统一目录会在可见性回填前先过滤排除表中的模型，避免被逻辑删除的项继续出现在设置页与聊天区。
        // 前置条件：CLI Login 发现模型和 managed provider 模型都各自命中一条 exclusion 记录。
        // 断言意图：被排除项既不能留在 catalog 中，也不能继续出现在 visibility 映射里。
        Path tempHome = Files.createTempDirectory("codex-model-catalog-exclusion-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithCliLoginDiscoveredModels(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject exclusions = new JsonObject();
        exclusions.addProperty("__codex_cli_login__::remote-gpt-1", true);
        exclusions.addProperty("provider-a::gpt-5", true);
        invokeSaveCodexModelCatalogExclusions(service, exclusions);

        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);
        JsonArray catalog = displayConfig.getAsJsonArray("catalog");
        JsonObject visibility = displayConfig.getAsJsonObject("visibility");

        assertTrue(findCatalogItemByKeyOrNull(catalog, "__codex_cli_login__::remote-gpt-1") == null);
        assertTrue(findCatalogItemByKeyOrNull(catalog, "provider-a::gpt-5") == null);
        assertTrue(findCatalogItemByKeyOrNull(catalog, "__codex_cli_login__::remote-gpt-2") != null);
        assertTrue(findCatalogItemByKeyOrNull(catalog, "provider-a::gpt-5-mini") != null);
        assertFalse(visibility.has("__codex_cli_login__::remote-gpt-1"));
        assertFalse(visibility.has("provider-a::gpt-5"));
    }

    @Test
    public void shouldRoundTripCliLoginDiscoveredModelsAndCatalogExclusionsViaDedicatedAccessors() throws Exception {
        // 测试目标：验证 Task 1 新增的两组基础配置读写接口可独立持久化，不必等到模型目录构建时再手工改原始 JSON。
        // 前置条件：空配置起步，通过专门 accessor 分别写入 discovered models 和 exclusions。
        // 断言意图：读回结果必须保持稳定结构，供后续同步模型和逻辑删除链路直接复用。
        Path tempHome = Files.createTempDirectory("codex-discovered-accessor-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithoutModelDisplay(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonArray discoveredModels = createDiscoveredModels();
        JsonObject exclusions = new JsonObject();
        exclusions.addProperty("__codex_cli_login__::remote-gpt-2", true);

        invokeSaveCodexCliLoginDiscoveredModels(service, discoveredModels);
        invokeSaveCodexModelCatalogExclusions(service, exclusions);

        JsonArray persistedDiscoveredModels = invokeGetCodexCliLoginDiscoveredModels(service);
        JsonObject persistedExclusions = invokeGetCodexModelCatalogExclusions(service);

        assertEquals(2, persistedDiscoveredModels.size());
        assertEquals("remote-gpt-1", persistedDiscoveredModels.get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(persistedExclusions.has("__codex_cli_login__::remote-gpt-2"));
        assertTrue(persistedExclusions.get("__codex_cli_login__::remote-gpt-2").getAsBoolean());
    }

    @Test
    public void shouldDeleteManagedProviderCatalogItemFromProviderModelsInsteadOfOnlyHidingIt() throws Exception {
        // 测试目标：删除 managed provider 来源的目录项时，应直接改写 provider.models，而不是仅写入 exclusion 造成“配置仍在但前端看不见”。
        // 前置条件：provider-a 下存在两个模型，删除其中一个后另一项应继续保留。
        // 断言意图：
        // 1. provider-a::gpt-5 会从 provider.models 中移除；
        // 2. provider-a::gpt-5-mini 仍然保留；
        // 3. 不应为 managed provider 删除额外写入 exclusion 键。
        Path tempHome = Files.createTempDirectory("codex-delete-managed-catalog-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithoutModelDisplay(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject payload = new JsonObject();
        payload.addProperty("key", "provider-a::gpt-5");
        payload.addProperty("providerId", "provider-a");
        payload.addProperty("modelId", "gpt-5");
        payload.addProperty("source", "managed_provider");

        invokeDeleteCodexModelCatalogItem(service, payload);

        JsonObject persistedConfig = service.readConfig();
        JsonObject providerA = persistedConfig
                .getAsJsonObject("codex")
                .getAsJsonObject("providers")
                .getAsJsonObject("provider-a");
        JsonArray providerModels = providerA.getAsJsonArray("models");
        JsonObject exclusions = invokeGetCodexModelCatalogExclusions(service);

        assertTrue(findModelByIdOrNull(providerModels, "gpt-5") == null);
        assertTrue(findModelByIdOrNull(providerModels, "gpt-5-mini") != null);
        assertFalse(exclusions.has("provider-a::gpt-5"));
    }

    @Test
    public void shouldDeleteLocalConfigCatalogItemByExcludingItFromUnifiedCatalog() throws Exception {
        // 测试目标：删除 local_config 来源的兜底目录项时，没有独立 provider/discovered 存储可改写，
        // 因此应回退到 exclusion 语义，保证统一目录后续不再展示该条目。
        // 断言意图：
        // 1. exclusion 表会新增对应 key；
        // 2. 重新读取统一目录后该条目不再出现。
        Path tempHome = Files.createTempDirectory("codex-delete-local-config-catalog-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithAuthorizedCliLoginAndLocalModel(tempHome, "gpt-5.4-local");

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject payload = new JsonObject();
        payload.addProperty("key", "__codex_cli_login__::gpt-5.4-local");
        payload.addProperty("providerId", "__codex_cli_login__");
        payload.addProperty("modelId", "gpt-5.4-local");
        payload.addProperty("source", "local_config");

        invokeDeleteCodexModelCatalogItem(service, payload);

        JsonObject exclusions = invokeGetCodexModelCatalogExclusions(service);
        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);

        assertTrue(exclusions.has("__codex_cli_login__::gpt-5.4-local"));
        assertTrue(exclusions.get("__codex_cli_login__::gpt-5.4-local").getAsBoolean());
        assertTrue(findCatalogItemByKeyOrNull(displayConfig.getAsJsonArray("catalog"), "__codex_cli_login__::gpt-5.4-local") == null);
    }

    @Test
    public void shouldDeleteCliLoginCatalogItemByExcludingItInsteadOfMutatingDiscoveredModels() throws Exception {
        // 测试目标：删除 codex_cli_login 来源目录项时，应写入 exclusion，而不是直接改写 discovered models。
        // 前置条件：配置中已经存在 CLI Login 的 discovered models 缓存。
        // 断言意图：
        // 1. discovered models 原始缓存仍然保留该模型，便于后续同步恢复；
        // 2. exclusion 表会新增对应 key；
        // 3. 重新读取统一目录后该目录项不再展示。
        Path tempHome = Files.createTempDirectory("codex-delete-cli-login-catalog-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithCliLoginDiscoveredModels(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject payload = new JsonObject();
        payload.addProperty("key", "__codex_cli_login__::remote-gpt-1");
        payload.addProperty("providerId", "__codex_cli_login__");
        payload.addProperty("modelId", "remote-gpt-1");
        payload.addProperty("source", "codex_cli_login");

        invokeDeleteCodexModelCatalogItem(service, payload);

        JsonArray discoveredModels = invokeGetCodexCliLoginDiscoveredModels(service);
        JsonObject exclusions = invokeGetCodexModelCatalogExclusions(service);
        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);

        assertTrue(findModelByIdOrNull(discoveredModels, "remote-gpt-1") != null);
        assertTrue(exclusions.has("__codex_cli_login__::remote-gpt-1"));
        assertTrue(exclusions.get("__codex_cli_login__::remote-gpt-1").getAsBoolean());
        assertTrue(findCatalogItemByKeyOrNull(displayConfig.getAsJsonArray("catalog"), "__codex_cli_login__::remote-gpt-1") == null);
    }

    @Test
    public void shouldClearSelectedModelWhenDeletedCatalogItemMatchesCurrentSelection() throws Exception {
        // 测试目标：删除统一目录项时，如果命中当前 selectedModel，必须同步清空选中态。
        // 前置条件：selectedModel 指向 provider-a::gpt-5，随后删除该 managed 模型。
        // 关键场景：避免聊天区继续沿用已失效模型选择。
        // 断言意图：删除后配置里不再保留 selectedModel 节点。
        Path tempHome = Files.createTempDirectory("codex-delete-selected-model-home");
        useTemporaryHomeDirectory(tempHome);

        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());
        JsonObject selectedModel = new JsonObject();
        selectedModel.addProperty("providerId", "provider-a");
        selectedModel.addProperty("modelId", "gpt-5");
        codex.add("selectedModel", selectedModel);
        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject payload = new JsonObject();
        payload.addProperty("key", "provider-a::gpt-5");
        payload.addProperty("providerId", "provider-a");
        payload.addProperty("modelId", "gpt-5");
        payload.addProperty("source", "managed_provider");

        invokeDeleteCodexModelCatalogItem(service, payload);

        JsonObject persistedConfig = service.readConfig();
        JsonObject persistedCodex = persistedConfig.getAsJsonObject("codex");
        assertFalse(persistedCodex.has("selectedModel"));
    }

    @Test
    public void shouldKeepSelectedModelWhenDeletedCatalogItemDoesNotMatch() throws Exception {
        // 测试目标：删除非当前选中模型时，不应误清 selectedModel。
        // 前置条件：selectedModel 指向 provider-a::gpt-5-mini，删除 provider-a::gpt-5。
        // 断言意图：selectedModel 仍保留为 gpt-5-mini。
        Path tempHome = Files.createTempDirectory("codex-delete-non-selected-model-home");
        useTemporaryHomeDirectory(tempHome);

        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());
        JsonObject selectedModel = new JsonObject();
        selectedModel.addProperty("providerId", "provider-a");
        selectedModel.addProperty("modelId", "gpt-5-mini");
        codex.add("selectedModel", selectedModel);
        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject payload = new JsonObject();
        payload.addProperty("key", "provider-a::gpt-5");
        payload.addProperty("providerId", "provider-a");
        payload.addProperty("modelId", "gpt-5");
        payload.addProperty("source", "managed_provider");

        invokeDeleteCodexModelCatalogItem(service, payload);

        JsonObject persistedSelected = service.readConfig()
                .getAsJsonObject("codex")
                .getAsJsonObject("selectedModel");
        assertEquals("provider-a", persistedSelected.get("providerId").getAsString());
        assertEquals("gpt-5-mini", persistedSelected.get("modelId").getAsString());
    }

    @Test
    public void shouldRejectPluginCustomCatalogDeletionAsUnsupported() throws Exception {
        // 测试目标：plugin_custom 当前统一目录尚未产出对应项，删除分支应明确 unsupported。
        // 前置条件：配置中存在常规 managed providers。
        // 断言意图：调用 deleteCodexModelCatalogItem 时抛出 IllegalArgumentException，且错误信息包含 Unsupported。
        Path tempHome = Files.createTempDirectory("codex-delete-plugin-custom-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithoutModelDisplay(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject payload = new JsonObject();
        payload.addProperty("key", "alias-provider::alias-model");
        payload.addProperty("providerId", "alias-provider");
        payload.addProperty("modelId", "alias-model");
        payload.addProperty("source", "plugin_custom");

        try {
            invokeDeleteCodexModelCatalogItem(service, payload);
            fail("plugin_custom catalog deletion should be rejected as unsupported");
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            assertTrue(cause instanceof IllegalArgumentException);
            assertTrue(cause.getMessage().contains("Unsupported"));
        }
    }

    @Test
    public void shouldClearCliLoginScopedExclusionsWhenDiscoveredModelsAreResynced() throws Exception {
        // 测试目标：重新同步本地/CLI 供应商模型后，应按 provider 维度清理 `__codex_cli_login__::*` exclusion，
        // 让此前逻辑删除的 discovered 与 local_config 目录项都能恢复显示。
        // 断言意图：
        // 1. CLI provider 前缀的 exclusion 会被清理；
        // 2. 其他 provider 的 exclusion 保持不变；
        // 3. 刷新后的统一目录能重新包含 CLI discovered 模型和本地当前模型。
        Path tempHome = Files.createTempDirectory("codex-cli-login-exclusion-restore-home");
        useTemporaryHomeDirectory(tempHome);
        writeConfigWithCliLoginDiscoveredModelsAndAuthorizedLocalModel(tempHome, "gpt-5.4-local");

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject exclusions = new JsonObject();
        exclusions.addProperty("__codex_cli_login__::remote-gpt-1", true);
        exclusions.addProperty("__codex_cli_login__::gpt-5.4-local", true);
        exclusions.addProperty("provider-a::gpt-5", true);
        invokeSaveCodexModelCatalogExclusions(service, exclusions);

        invokeSaveCodexCliLoginDiscoveredModels(service, createDiscoveredModels());

        JsonObject persistedExclusions = invokeGetCodexModelCatalogExclusions(service);
        JsonObject displayConfig = invokeGetCodexModelDisplayConfig(service);

        assertFalse(persistedExclusions.has("__codex_cli_login__::remote-gpt-1"));
        assertFalse(persistedExclusions.has("__codex_cli_login__::gpt-5.4-local"));
        assertTrue(persistedExclusions.has("provider-a::gpt-5"));
        assertTrue(findCatalogItemByKeyOrNull(displayConfig.getAsJsonArray("catalog"), "__codex_cli_login__::remote-gpt-1") != null);
        assertTrue(findCatalogItemByKeyOrNull(displayConfig.getAsJsonArray("catalog"), "__codex_cli_login__::gpt-5.4-local") != null);
    }

    @Test
    public void shouldBuildFreshNewTabDefaultsFromRememberedModelAndReasoningFirst() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-new-tab-defaults-home");
        useTemporaryHomeDirectory(tempHome);

        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());
        JsonObject selectedModel = new JsonObject();
        selectedModel.addProperty("providerId", "provider-b");
        selectedModel.addProperty("modelId", "o3");
        codex.add("selectedModel", selectedModel);
        codex.addProperty("lastReasoningEffort", "low");
        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject defaults = service.buildFreshNewTabDefaults();

        assertEquals("codex", defaults.get("provider").getAsString());
        assertEquals("bypassPermissions", defaults.get("permissionMode").getAsString());
        assertEquals("provider-b", defaults.get("codexProviderId").getAsString());
        assertEquals("o3", defaults.get("model").getAsString());
        assertEquals("remembered_model", defaults.get("modelSource").getAsString());
        assertEquals("low", defaults.get("reasoningEffort").getAsString());
        assertEquals("remembered_reasoning", defaults.get("reasoningSource").getAsString());
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

    private JsonArray invokeGetCodexCliLoginDiscoveredModels(CodemossSettingsService service) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("getCodexCliLoginDiscoveredModels");
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose getCodexCliLoginDiscoveredModels()");
            throw e;
        }
        return (JsonArray) method.invoke(service);
    }

    private void invokeSaveCodexCliLoginDiscoveredModels(
            CodemossSettingsService service,
            JsonArray discoveredModels
    ) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("saveCodexCliLoginDiscoveredModels", JsonArray.class);
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose saveCodexCliLoginDiscoveredModels(JsonArray)");
            throw e;
        }
        method.invoke(service, discoveredModels);
    }

    private JsonObject invokeGetCodexModelCatalogExclusions(CodemossSettingsService service) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("getCodexModelCatalogExclusions");
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose getCodexModelCatalogExclusions()");
            throw e;
        }
        return (JsonObject) method.invoke(service);
    }

    private void invokeSaveCodexModelCatalogExclusions(
            CodemossSettingsService service,
            JsonObject exclusions
    ) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("saveCodexModelCatalogExclusions", JsonObject.class);
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose saveCodexModelCatalogExclusions(JsonObject)");
            throw e;
        }
        method.invoke(service, exclusions);
    }

    private void invokeDeleteCodexModelCatalogItem(
            CodemossSettingsService service,
            JsonObject payload
    ) throws Exception {
        Method method;
        try {
            method = CodemossSettingsService.class.getMethod("deleteCodexModelCatalogItem", JsonObject.class);
        } catch (NoSuchMethodException e) {
            fail("CodemossSettingsService should expose deleteCodexModelCatalogItem(JsonObject)");
            throw e;
        }
        method.invoke(service, payload);
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
     * 写入一份包含 CLI Login 发现模型缓存的配置。
     * 该辅助方法让 Task 1 的 discovered-model 优先级测试不依赖真实远端同步链路，
     * 只聚焦“目录构建是否优先读取缓存”这一个断言。
     *
     * @param tempHome 临时 home 目录
     * @throws Exception 写文件失败时抛出
     */
    private void writeConfigWithCliLoginDiscoveredModels(Path tempHome) throws Exception {
        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());
        codex.add("cliLoginDiscoveredModels", createDiscoveredModels());
        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());
    }

    /**
     * 写入带已授权 CLI Login 和当前本地模型兜底项的配置。
     * 该辅助方法用于覆盖 local_config 来源目录项的删除分支，避免测试依赖真实 ~/.codex 文件。
     *
     * @param tempHome 临时 home 目录
     * @param modelId 需要暴露为 local_config 目录项的当前本地模型 id
     * @throws Exception 写文件失败时抛出
     */
    private void writeConfigWithAuthorizedCliLoginAndLocalModel(Path tempHome, String modelId) throws Exception {
        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());
        codex.addProperty("localConfigAuthorized", true);
        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());

        JsonObject localConfig = new JsonObject();
        JsonObject localConfigPayload = new JsonObject();
        localConfigPayload.addProperty("model", modelId);
        localConfig.add("config", localConfigPayload);
        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(tempHome.resolve(".codex").resolve("config.toml"), "model = \"" + modelId + "\"\n");
    }

    /**
     * 写入同时包含 CLI Login discovered models 与已授权本地当前模型的配置。
     * 该辅助方法专门服务于“重新同步后清理 provider 维度 exclusion 并恢复目录项”的测试。
     *
     * @param tempHome 临时 home 目录
     * @param modelId 当前本地模型 id
     * @throws Exception 写文件失败时抛出
     */
    private void writeConfigWithCliLoginDiscoveredModelsAndAuthorizedLocalModel(Path tempHome, String modelId) throws Exception {
        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "provider-a");
        codex.add("providers", createProviders());
        codex.add("cliLoginDiscoveredModels", createDiscoveredModels());
        codex.addProperty("localConfigAuthorized", true);
        config.add("codex", codex);
        Files.writeString(tempHome.resolve(".codemoss").resolve("config.json"), config.toString());

        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(tempHome.resolve(".codex").resolve("config.toml"), "model = \"" + modelId + "\"\n");
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
     * 生成 CLI Login 已同步发现模型缓存。
     * 这里使用两个远端模型 id，便于和内建 `gpt-5.5` 等默认值区分，
     * 从而明确验证“优先 discovered、回退 builtin”的目录构建规则。
     *
     * @return discovered models JSON 数组
     */
    private JsonArray createDiscoveredModels() {
        return createModels(
                createModel("remote-gpt-1", "Remote GPT 1"),
                createModel("remote-gpt-2", "Remote GPT 2")
        );
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

    /**
     * 按复合 key 在目录数组中尝试定位模型项。
     * 该辅助方法专门服务于“应被过滤掉”的断言，避免复用 `findCatalogItemByKey` 时在期望缺失场景下直接 fail。
     *
     * @param catalog 目录数组
     * @param key providerId::modelId 复合 key
     * @return 命中的目录项；不存在时返回 null
     */
    private JsonObject findCatalogItemByKeyOrNull(JsonArray catalog, String key) {
        for (int i = 0; i < catalog.size(); i++) {
            JsonObject item = catalog.get(i).getAsJsonObject();
            if (key.equals(item.get("key").getAsString())) {
                return item;
            }
        }
        return null;
    }

    /**
     * 按 model id 在 provider.models 数组中查找模型节点。
     * 该辅助方法只服务于 managed provider 删除断言，避免测试直接依赖数组顺序。
     *
     * @param models provider.models 数组
     * @param modelId 目标模型 id
     * @return 命中的模型节点；不存在时返回 null
     */
    private JsonObject findModelByIdOrNull(JsonArray models, String modelId) {
        for (int i = 0; i < models.size(); i++) {
            JsonObject item = models.get(i).getAsJsonObject();
            if (modelId.equals(item.get("id").getAsString())) {
                return item;
            }
        }
        return null;
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
