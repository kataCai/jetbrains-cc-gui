package com.github.claudecodegui.settings;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
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

    private CodexProviderManager createManager(
            AtomicReference<JsonObject> configRef,
            CodexSettingsManager codexSettingsManager
    ) {
        Gson gson = new Gson();
        return new CodexProviderManager(
                gson,
                ignored -> configRef.get(),
                updated -> configRef.set(JsonParser.parseString(updated.toString()).getAsJsonObject()),
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
