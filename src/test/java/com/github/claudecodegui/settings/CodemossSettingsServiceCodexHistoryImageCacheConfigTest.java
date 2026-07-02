package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/**
 * 验证 Codex 历史图片缓存配置的读写与钳制逻辑。
 * 覆盖目标：
 * 1. 默认配置存在且值稳定；
 * 2. 非法 retentionDays / maxSizeMb 会被钳制；
 * 3. 自定义目录会按原样持久化，空目录会回退为空串表示使用默认目录。
 */
public class CodemossSettingsServiceCodexHistoryImageCacheConfigTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void returnsDefaultHistoryImageCacheConfigWhenMissing() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-history-cache-config-default");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject config = service.getCodexHistoryImageCacheConfig();

        assertEquals("", config.get("customDir").getAsString());
        assertEquals(30, config.get("retentionDays").getAsInt());
        assertEquals(1024, config.get("maxSizeMb").getAsInt());
    }

    @Test
    public void persistsAndClampsHistoryImageCacheConfig() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-history-cache-config-persist");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexHistoryImageCacheConfig("C:/cache-dir", 0, 0);

        JsonObject config = service.getCodexHistoryImageCacheConfig();
        assertEquals("C:/cache-dir", config.get("customDir").getAsString());
        assertEquals(1, config.get("retentionDays").getAsInt());
        assertEquals(64, config.get("maxSizeMb").getAsInt());
    }

    @Test
    public void normalizesOversizedHistoryImageCacheConfigValues() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-history-cache-config-max");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexHistoryImageCacheConfig("", 9999, 999999);

        JsonObject config = service.getCodexHistoryImageCacheConfig();
        assertEquals("", config.get("customDir").getAsString());
        assertEquals(365, config.get("retentionDays").getAsInt());
        assertEquals(10240, config.get("maxSizeMb").getAsInt());
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
