package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ?????????? provider ???????????????????
 */
public class CodemossSettingsServiceRemoteCollabTest {

    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldReturnNormalizedDefaultRemoteCollabConfig() throws Exception {
        Path tempHome = Files.createTempDirectory("remote-collab-default-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();

        JsonObject remoteCollab = service.getRemoteCollabConfig();
        JsonObject debug = remoteCollab.getAsJsonObject("debug");
        JsonObject providers = remoteCollab.getAsJsonObject("providers");
        JsonObject telegram = providers.getAsJsonObject("telegram");
        JsonObject gotifyWeb = providers.getAsJsonObject("gotify_web");
        JsonObject feishu = providers.getAsJsonObject("feishu");

        assertFalse(remoteCollab.get("enabled").getAsBoolean());
        assertFalse(debug.get("enabled").getAsBoolean());
        assertEquals("telegram", remoteCollab.get("interactiveProviderId").getAsString());
        assertEquals(1, remoteCollab.getAsJsonArray("notifyProviderIds").size());
        assertEquals("telegram", remoteCollab.getAsJsonArray("notifyProviderIds").get(0).getAsString());

        assertTrue(telegram.get("enabled").getAsBoolean());
        assertEquals("", telegram.get("botToken").getAsString());
        assertEquals("", telegram.get("botUsername").getAsString());
        assertEquals("", telegram.get("chatId").getAsString());
        assertTrue(telegram.get("pollingEnabled").getAsBoolean());
        assertEquals(1, telegram.get("pollIntervalSeconds").getAsInt());
        assertTrue(telegram.get("singleActive").getAsBoolean());
        assertEquals("disabled", telegram.get("connectionStatus").getAsString());
        assertEquals("", telegram.get("lastError").getAsString());

        assertFalse(gotifyWeb.get("enabled").getAsBoolean());
        assertEquals("", gotifyWeb.get("serverUrl").getAsString());
        assertEquals("", gotifyWeb.get("apiToken").getAsString());
        assertEquals("", gotifyWeb.get("workspaceBaseUrl").getAsString());
        assertEquals(3, gotifyWeb.get("resultPollIntervalSeconds").getAsInt());
        assertEquals("disabled", gotifyWeb.get("connectionStatus").getAsString());
        assertEquals("", gotifyWeb.get("lastError").getAsString());

        assertFalse(feishu.get("enabled").getAsBoolean());
        assertEquals("", feishu.get("appId").getAsString());
        assertEquals("", feishu.get("appSecret").getAsString());
        assertEquals("", feishu.get("encryptKey").getAsString());
        assertEquals("", feishu.get("verificationToken").getAsString());
        assertEquals("", feishu.get("botName").getAsString());
        assertEquals("", feishu.get("boundOpenId").getAsString());
        assertEquals("", feishu.get("boundChatId").getAsString());
        assertEquals("", feishu.get("bindingToken").getAsString());
        assertEquals("long_poll", feishu.get("eventMode").getAsString());
        assertEquals("disabled", feishu.get("connectionStatus").getAsString());
        assertEquals("", feishu.get("lastError").getAsString());
    }

    @Test
    public void shouldPersistTelegramConfigAndEnabledFlag() throws Exception {
        Path tempHome = Files.createTempDirectory("remote-collab-save-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject telegram = new JsonObject();
        telegram.addProperty("botToken", "123:abc");
        telegram.addProperty("botUsername", "cc_gui_bot");
        telegram.addProperty("chatId", "998877");
        telegram.addProperty("pollingEnabled", true);
        telegram.addProperty("pollIntervalSeconds", 3);
        telegram.addProperty("singleActive", false);
        telegram.addProperty("connectionStatus", "connected");
        telegram.addProperty("lastError", "");

        service.saveTelegramConfig(telegram);
        service.setRemoteCollabEnabled(true);

        JsonObject remoteCollab = service.getRemoteCollabConfig();
        JsonObject persistedTelegram = remoteCollab
            .getAsJsonObject("providers")
            .getAsJsonObject("telegram");

        assertTrue(remoteCollab.get("enabled").getAsBoolean());
        assertEquals("123:abc", persistedTelegram.get("botToken").getAsString());
        assertEquals("cc_gui_bot", persistedTelegram.get("botUsername").getAsString());
        assertEquals("998877", persistedTelegram.get("chatId").getAsString());
        assertEquals(3, persistedTelegram.get("pollIntervalSeconds").getAsInt());
        assertFalse(persistedTelegram.get("singleActive").getAsBoolean());
        assertEquals("connected", persistedTelegram.get("connectionStatus").getAsString());
    }

    @Test
    public void shouldBackfillMissingTelegramFieldsFromPersistedConfig() throws Exception {
        Path tempHome = Files.createTempDirectory("remote-collab-migration-home");
        useTemporaryHomeDirectory(tempHome);

        JsonObject config = new JsonObject();
        JsonObject remoteCollab = new JsonObject();
        remoteCollab.addProperty("enabled", true);
        JsonObject telegram = new JsonObject();
        telegram.addProperty("botToken", "only-token");
        remoteCollab.add("telegram", telegram);
        config.add("remoteCollab", remoteCollab);
        writeConfig(tempHome, config);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject normalized = service.getRemoteCollabConfig();
        JsonObject normalizedTelegram = normalized
            .getAsJsonObject("providers")
            .getAsJsonObject("telegram");

        assertTrue(normalized.get("enabled").getAsBoolean());
        assertEquals("telegram", normalized.get("interactiveProviderId").getAsString());
        assertEquals(1, normalized.getAsJsonArray("notifyProviderIds").size());
        assertEquals("telegram", normalized.getAsJsonArray("notifyProviderIds").get(0).getAsString());
        assertEquals("only-token", normalizedTelegram.get("botToken").getAsString());
        assertEquals("", normalizedTelegram.get("botUsername").getAsString());
        assertEquals(1, normalizedTelegram.get("pollIntervalSeconds").getAsInt());
        assertEquals("disabled", normalizedTelegram.get("connectionStatus").getAsString());
    }

    @Test
    public void shouldNormalizeWholeRemoteCollabConfigIntoProviderTree() throws Exception {
        Path tempHome = Files.createTempDirectory("remote-collab-provider-tree-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject remoteCollab = new JsonObject();
        remoteCollab.addProperty("enabled", true);

        JsonObject debug = new JsonObject();
        debug.addProperty("enabled", true);
        remoteCollab.add("debug", debug);
        remoteCollab.addProperty("interactiveProviderId", " gotify_web ");
        JsonArray notifyProviderIds = new JsonArray();
        notifyProviderIds.add("telegram");
        notifyProviderIds.add("gotify_web");
        notifyProviderIds.add("telegram");
        notifyProviderIds.add("");
        remoteCollab.add("notifyProviderIds", notifyProviderIds);

        JsonObject providers = new JsonObject();
        JsonObject telegram = new JsonObject();
        telegram.addProperty("enabled", true);
        telegram.addProperty("pollIntervalSeconds", 0);
        providers.add("telegram", telegram);

        JsonObject gotifyWeb = new JsonObject();
        gotifyWeb.addProperty("enabled", true);
        gotifyWeb.addProperty("serverUrl", "https://gotify.example");
        gotifyWeb.addProperty("apiToken", "secret-token");
        gotifyWeb.addProperty("workspaceBaseUrl", "https://workspace.example");
        gotifyWeb.addProperty("resultPollIntervalSeconds", 0);
        providers.add("gotify_web", gotifyWeb);

        JsonObject feishu = new JsonObject();
        feishu.addProperty("enabled", true);
        feishu.addProperty("appId", "cli_a1");
        feishu.addProperty("appSecret", "secret-a1");
        feishu.addProperty("eventMode", " long_poll ");
        providers.add("feishu", feishu);
        remoteCollab.add("providers", providers);

        service.saveRemoteCollabConfig(remoteCollab);

        JsonObject normalized = service.getRemoteCollabConfig();
        JsonObject normalizedProviders = normalized.getAsJsonObject("providers");
        JsonObject normalizedTelegram = normalizedProviders.getAsJsonObject("telegram");
        JsonObject normalizedGotifyWeb = normalizedProviders.getAsJsonObject("gotify_web");
        JsonObject normalizedFeishu = normalizedProviders.getAsJsonObject("feishu");

        assertTrue(normalized.get("enabled").getAsBoolean());
        assertTrue(normalized.getAsJsonObject("debug").get("enabled").getAsBoolean());
        assertEquals("gotify_web", normalized.get("interactiveProviderId").getAsString());
        assertEquals(2, normalized.getAsJsonArray("notifyProviderIds").size());
        assertEquals("telegram", normalized.getAsJsonArray("notifyProviderIds").get(0).getAsString());
        assertEquals("gotify_web", normalized.getAsJsonArray("notifyProviderIds").get(1).getAsString());
        assertEquals(1, normalizedTelegram.get("pollIntervalSeconds").getAsInt());
        assertTrue(normalizedGotifyWeb.get("enabled").getAsBoolean());
        assertEquals("https://gotify.example", normalizedGotifyWeb.get("serverUrl").getAsString());
        assertEquals("secret-token", normalizedGotifyWeb.get("apiToken").getAsString());
        assertEquals("https://workspace.example", normalizedGotifyWeb.get("workspaceBaseUrl").getAsString());
        assertEquals(1, normalizedGotifyWeb.get("resultPollIntervalSeconds").getAsInt());
        assertTrue(normalizedFeishu.get("enabled").getAsBoolean());
        assertEquals("cli_a1", normalizedFeishu.get("appId").getAsString());
        assertEquals("secret-a1", normalizedFeishu.get("appSecret").getAsString());
        assertEquals("long_poll", normalizedFeishu.get("eventMode").getAsString());
    }

    private void writeConfig(Path tempHome, JsonObject config) throws Exception {
        Path configDir = tempHome.resolve(".codemoss");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("config.json"), config.toString(), StandardCharsets.UTF_8);
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
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
