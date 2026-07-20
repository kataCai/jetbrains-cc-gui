package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Codex provider runtime 配置测试。
 * 重点锁定 CC-GUI provider 切换只更新自有配置，不再默认写入 Codex live config。
 */
public class CodexProviderManagerRuntimeProfileTest {

    @Test
    public void shouldSwitchManagedProviderWithoutApplyingCodexLiveConfig() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        RecordingCodexSettingsManager codexSettingsManager = new RecordingCodexSettingsManager();
        CodexProviderManager manager = createManager(configRef, codexSettingsManager);
        manager.addCodexProvider(createManagedProvider("managed", "Managed Provider"));

        manager.switchCodexProvider("managed");

        assertEquals("managed", configRef.get().getAsJsonObject("codex").get("current").getAsString());
        assertEquals(0, codexSettingsManager.applyCount.get());
    }

    @Test
    public void shouldNormalizeLegacyCustomModelsToRuntimeModels() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        CodexProviderManager manager = createManager(configRef, new RecordingCodexSettingsManager());
        JsonObject provider = createManagedProviderWithoutModels("legacy", "Legacy Provider");
        JsonArray customModels = new JsonArray();
        JsonObject model = new JsonObject();
        model.addProperty("id", "MiniMax-M2.7");
        model.addProperty("label", "MiniMax M2.7");
        customModels.add(model);
        provider.add("customModels", customModels);

        manager.addCodexProvider(provider);

        JsonObject saved = configRef.get()
                .getAsJsonObject("codex")
                .getAsJsonObject("providers")
                .getAsJsonObject("legacy");
        assertTrue(saved.has("models"));
        assertEquals("MiniMax-M2.7", saved.getAsJsonArray("models").get(0).getAsJsonObject().get("id").getAsString());
    }

    @Test
    public void shouldPreserveCcSwitchProxySchemaFields() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        CodexProviderManager manager = createManager(configRef, new RecordingCodexSettingsManager());
        JsonObject provider = createManagedProvider("proxy-provider", "Proxy Provider");
        provider.addProperty("requestMode", "cc_switch_proxy");
        provider.remove("baseUrl");
        provider.remove("apiKeyEnv");
        provider.addProperty("authMode", "proxy");
        JsonObject ccSwitchProxy = new JsonObject();
        ccSwitchProxy.addProperty("proxyEndpoint", "http://127.0.0.1:15721");
        ccSwitchProxy.addProperty("providerRoute", "minimax");
        ccSwitchProxy.addProperty("requestPath", "/v1/responses");
        JsonObject requestHeaders = new JsonObject();
        requestHeaders.addProperty("x-route", "minimax");
        ccSwitchProxy.add("requestHeaders", requestHeaders);
        provider.add("ccSwitchProxy", ccSwitchProxy);

        manager.addCodexProvider(provider);

        JsonObject saved = configRef.get()
                .getAsJsonObject("codex")
                .getAsJsonObject("providers")
                .getAsJsonObject("proxy-provider");
        assertEquals("cc_switch_proxy", saved.get("requestMode").getAsString());
        assertTrue(saved.has("ccSwitchProxy"));
        assertEquals(
                "http://127.0.0.1:15721",
                saved.getAsJsonObject("ccSwitchProxy").get("proxyEndpoint").getAsString()
        );
        assertEquals(
                "minimax",
                saved.getAsJsonObject("ccSwitchProxy").get("providerRoute").getAsString()
        );
    }

    @Test
    public void shouldPreserveCustomAdapterSchemaFields() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        CodexProviderManager manager = createManager(configRef, new RecordingCodexSettingsManager());
        JsonObject provider = createManagedProvider("adapter-provider", "Adapter Provider");
        provider.addProperty("requestMode", "custom_adapter");
        provider.remove("baseUrl");
        JsonObject customAdapter = new JsonObject();
        customAdapter.addProperty("adapterId", "minimax-adapter");
        customAdapter.addProperty("adapterEndpoint", "http://127.0.0.1:8080/adapter/codex");
        JsonObject adapterHeaders = new JsonObject();
        adapterHeaders.addProperty("Authorization", "Bearer adapter");
        customAdapter.add("adapterHeaders", adapterHeaders);
        JsonObject adapterExtras = new JsonObject();
        adapterExtras.addProperty("provider", "minimax");
        adapterExtras.addProperty("mode", "responses");
        customAdapter.add("adapterExtras", adapterExtras);
        provider.add("customAdapter", customAdapter);

        manager.addCodexProvider(provider);

        JsonObject saved = configRef.get()
                .getAsJsonObject("codex")
                .getAsJsonObject("providers")
                .getAsJsonObject("adapter-provider");
        assertEquals("custom_adapter", saved.get("requestMode").getAsString());
        assertTrue(saved.has("customAdapter"));
        assertEquals(
                "minimax-adapter",
                saved.getAsJsonObject("customAdapter").get("adapterId").getAsString()
        );
        assertEquals(
                "http://127.0.0.1:8080/adapter/codex",
                saved.getAsJsonObject("customAdapter").get("adapterEndpoint").getAsString()
        );
    }

    @Test
    public void shouldPersistSelectedCodexModel() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        CodexProviderManager manager = createManager(configRef, new RecordingCodexSettingsManager());

        manager.setSelectedModel("managed", "gpt-5.4");

        JsonObject selected = manager.getSelectedModel();
        assertEquals("managed", selected.get("providerId").getAsString());
        assertEquals("gpt-5.4", selected.get("modelId").getAsString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidRequestMode() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        CodexProviderManager manager = createManager(configRef, new RecordingCodexSettingsManager());
        JsonObject provider = createManagedProvider("bad", "Bad Provider");
        provider.addProperty("requestMode", "invalid_mode");

        manager.addCodexProvider(provider);
    }

    @Test
    public void shouldKeepCliLoginAuthorizationSeparateFromManagedRuntime() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        CodexProviderManager manager = createManager(configRef, new RecordingCodexSettingsManager());
        manager.addCodexProvider(createManagedProvider("managed", "Managed Provider"));

        manager.switchCodexProvider("managed");

        assertFalse(configRef.get().getAsJsonObject("codex").has("localConfigAuthorized"));
        assertEquals("managed", manager.getActiveCodexProvider().get("id").getAsString());
    }

    /**
     * 验证目标：
     * 当远端返回的模型列表包含“已存在模型 + 新模型 + 重复项 + 空白项”时，
     * provider manager 必须只在当前 provider 内做去重追加，并保留旧模型的人工元数据。
     *
     * 断言意图：
     * 1. 旧模型的 label / description / reasoningEffort 不被远端结果覆盖。
     * 2. 新模型按远端首次出现顺序追加到列表尾部，并默认使用 `label=id`。
     * 3. 统计信息能区分新增、重复和无效项，供设置页提示直接复用。
     */
    @Test
    public void shouldMergeFetchedModelsWithoutOverwritingExistingMetadata() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        CodexProviderManager manager = createManager(configRef, new RecordingCodexSettingsManager());
        JsonObject provider = createManagedProviderWithoutModels("managed", "Managed Provider");
        JsonArray models = new JsonArray();
        JsonObject existingModel = new JsonObject();
        existingModel.addProperty("id", "gpt-5.4");
        existingModel.addProperty("label", "Custom GPT-5.4");
        existingModel.addProperty("description", "keep-me");
        existingModel.addProperty("reasoningEffort", "high");
        models.add(existingModel);
        provider.add("models", models);
        manager.addCodexProvider(provider);

        CodexProviderManager.CodexProviderModelMergeResult result = manager.mergeCodexProviderModels(
                "managed",
                List.of("gpt-5.4", " gpt-5.5 ", "gpt-5.5", "", "gpt-5.4-mini")
        );

        JsonArray savedModels = configRef.get()
                .getAsJsonObject("codex")
                .getAsJsonObject("providers")
                .getAsJsonObject("managed")
                .getAsJsonArray("models");
        assertEquals(3, savedModels.size());
        assertEquals("gpt-5.4", savedModels.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("Custom GPT-5.4", savedModels.get(0).getAsJsonObject().get("label").getAsString());
        assertEquals("keep-me", savedModels.get(0).getAsJsonObject().get("description").getAsString());
        assertEquals("high", savedModels.get(0).getAsJsonObject().get("reasoningEffort").getAsString());
        assertEquals("gpt-5.5", savedModels.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("gpt-5.5", savedModels.get(1).getAsJsonObject().get("label").getAsString());
        assertEquals("gpt-5.4-mini", savedModels.get(2).getAsJsonObject().get("id").getAsString());
        assertEquals(2, result.getAddedCount());
        assertEquals(2, result.getDuplicateCount());
        assertEquals(1, result.getSkippedCount());
    }

    /**
     * 验证目标：
     * 当远端返回的模型全部都已存在，或者只包含空白无效项时，
     * provider manager 不应再回写配置文件，避免制造无意义的磁盘改动和列表抖动。
     *
     * 断言意图：
     * 1. `addedCount` 为 0。
     * 2. 配置写入次数不因“无变化合并”而增加。
     * 3. 统计里能保留重复与无效项数量，便于前端给出“无新增，已跳过重复项”的提示。
     */
    @Test
    public void shouldSkipConfigWriteWhenFetchedModelsDoNotAddAnythingNew() throws Exception {
        AtomicReference<JsonObject> configRef = new AtomicReference<>(createConfig());
        AtomicInteger writeCount = new AtomicInteger();
        RecordingCodexSettingsManager codexSettingsManager = new RecordingCodexSettingsManager();
        CodexProviderManager manager = createManager(configRef, codexSettingsManager, writeCount);
        manager.addCodexProvider(createManagedProvider("managed", "Managed Provider"));
        int writesAfterAdd = writeCount.get();

        CodexProviderManager.CodexProviderModelMergeResult result = manager.mergeCodexProviderModels(
                "managed",
                List.of("gpt-5.4", " gpt-5.4 ", "")
        );

        assertEquals(0, result.getAddedCount());
        assertEquals(2, result.getDuplicateCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(writesAfterAdd, writeCount.get());
    }

    private CodexProviderManager createManager(
            AtomicReference<JsonObject> configRef,
            CodexSettingsManager codexSettingsManager
    ) {
        return createManager(configRef, codexSettingsManager, new AtomicInteger());
    }

    /**
     * 创建可统计写入次数的 provider manager。
     * 该辅助方法用于验证“无新增模型时不落盘”这类依赖写入副作用的场景，
     * 避免测试只能通过最终配置内容间接猜测是否发生过多余写入。
     *
     * @param configRef 当前配置快照引用
     * @param codexSettingsManager 本地 Codex 设置管理器桩
     * @param writeCount 配置写入次数计数器
     * @return 绑定到内存配置桩的 provider manager
     */
    private CodexProviderManager createManager(
            AtomicReference<JsonObject> configRef,
            CodexSettingsManager codexSettingsManager,
            AtomicInteger writeCount
    ) {
        Gson gson = new Gson();
        return new CodexProviderManager(
                gson,
                ignored -> configRef.get(),
                updated -> {
                    writeCount.incrementAndGet();
                    configRef.set(JsonParser.parseString(updated.toString()).getAsJsonObject());
                },
                null,
                codexSettingsManager
        );
    }

    private JsonObject createConfig() {
        JsonObject config = new JsonObject();
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "");
        codex.add("providers", new JsonObject());
        config.add("codex", codex);
        return config;
    }

    private JsonObject createManagedProvider(String id, String name) {
        JsonObject provider = createManagedProviderWithoutModels(id, name);
        JsonArray models = new JsonArray();
        JsonObject model = new JsonObject();
        model.addProperty("id", "gpt-5.4");
        model.addProperty("label", "GPT-5.4");
        model.addProperty("reasoningEffort", "high");
        models.add(model);
        provider.add("models", models);
        return provider;
    }

    private JsonObject createManagedProviderWithoutModels(String id, String name) {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", id);
        provider.addProperty("name", name);
        provider.addProperty("authMode", "api_key_env");
        provider.addProperty("requestMode", "codex_sdk");
        provider.addProperty("apiKeyEnv", "TEST_CODEX_KEY");
        return provider;
    }

    private static class RecordingCodexSettingsManager extends CodexSettingsManager {
        private final AtomicInteger applyCount = new AtomicInteger();

        RecordingCodexSettingsManager() {
            super(new Gson());
        }

        @Override
        public void applyProviderToCodexSettings(JsonObject provider) throws IOException {
            // 测试通过计数确认默认 provider 切换不会写入真实 Codex live config。
            applyCount.incrementAndGet();
        }
    }
}
