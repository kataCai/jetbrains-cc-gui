package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
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
 * 验证远程协作配置的默认值、归一化与持久化行为。
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
        JsonObject telegram = remoteCollab.getAsJsonObject("telegram");

        assertFalse(remoteCollab.get("enabled").getAsBoolean());
        assertEquals("", telegram.get("botToken").getAsString());
        assertEquals("", telegram.get("botUsername").getAsString());
        assertEquals("", telegram.get("chatId").getAsString());
        assertTrue(telegram.get("pollingEnabled").getAsBoolean());
        assertEquals(1, telegram.get("pollIntervalSeconds").getAsInt());
        assertTrue(telegram.get("singleActive").getAsBoolean());
        assertEquals("disabled", telegram.get("connectionStatus").getAsString());
        assertEquals("", telegram.get("lastError").getAsString());
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
        JsonObject persistedTelegram = remoteCollab.getAsJsonObject("telegram");

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
        JsonObject normalizedTelegram = normalized.getAsJsonObject("telegram");

        assertTrue(normalized.get("enabled").getAsBoolean());
        assertEquals("only-token", normalizedTelegram.get("botToken").getAsString());
        assertEquals("", normalizedTelegram.get("botUsername").getAsString());
        assertEquals(1, normalizedTelegram.get("pollIntervalSeconds").getAsInt());
        assertEquals("disabled", normalizedTelegram.get("connectionStatus").getAsString());
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
