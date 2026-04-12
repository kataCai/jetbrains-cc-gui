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
import static org.junit.Assert.assertTrue;

/**
 * 验证 CodemossSettingsService 对 task reminder 配置的迁移与写入行为。
 * 核心目标是确保旧 soundNotification 配置可以被平滑收敛到新结构。
 */
public class CodemossSettingsServiceTaskReminderMigrationTest {

    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        // 每个测试都可能重写缓存 home 目录，因此必须在结束时恢复。
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldMigrateLegacySoundNotificationIntoTaskReminderSoundOnRead() throws Exception {
        Path tempHome = Files.createTempDirectory("task-reminder-migration-home");
        useTemporaryHomeDirectory(tempHome);

        JsonObject legacyConfig = new JsonObject();
        JsonObject legacySound = new JsonObject();
        legacySound.addProperty("enabled", true);
        legacySound.addProperty("onlyWhenUnfocused", true);
        legacySound.addProperty("selectedSound", "bell");
        legacySound.addProperty("customSoundPath", "/tmp/custom.wav");
        legacyConfig.add("soundNotification", legacySound);

        writeConfig(tempHome, legacyConfig);

        CodemossSettingsService service = new CodemossSettingsService();

        // 首次读取时就应该完成迁移，并返回 canonical 结构。
        JsonObject taskReminder = service.getTaskReminderConfig();
        JsonObject sound = taskReminder.getAsJsonObject("sound");

        // 旧 setter 的写入结果也必须落在新结构里。
        assertTrue(sound.get("enabled").getAsBoolean());
        assertTrue(sound.get("onlyWhenIdeUnfocused").getAsBoolean());
        assertEquals("bell", sound.get("selectedSound").getAsString());
        assertEquals("/tmp/custom.wav", sound.get("customSoundPath").getAsString());

        JsonArray states = sound.getAsJsonArray("states");
        assertEquals(1, states.size());
        assertEquals("completed", states.get(0).getAsString());

        JsonObject persisted = service.readConfig();
        // 迁移不仅要体现在返回值里，也要真正落盘到 taskReminder 节点。
        assertTrue(persisted.has("taskReminder"));
        assertTrue(persisted.getAsJsonObject("taskReminder").has("sound"));
    }

    @Test
    public void shouldWriteSoundSettersIntoCanonicalTaskReminderSound() throws Exception {
        Path tempHome = Files.createTempDirectory("task-reminder-write-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.setSoundNotificationEnabled(true);
        service.setSoundOnlyWhenUnfocused(true);
        service.setSelectedSound("ding");
        service.setCustomSoundPath("/tmp/ding.wav");

        JsonObject config = service.readConfig();
        JsonObject sound = config
            .getAsJsonObject("taskReminder")
            .getAsJsonObject("sound");

        assertTrue(sound.get("enabled").getAsBoolean());
        assertTrue(sound.get("onlyWhenIdeUnfocused").getAsBoolean());
        assertEquals("ding", sound.get("selectedSound").getAsString());
        assertEquals("/tmp/ding.wav", sound.get("customSoundPath").getAsString());
    }

    private void writeConfig(Path tempHome, JsonObject config) throws Exception {
        // 手动构造一个最小配置文件，用于模拟升级前的旧用户环境。
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
