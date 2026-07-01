package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CodexHistoryImageCacheServiceTest {

    /**
     * 验证历史图片缓存服务会把附件写入正式缓存目录。
     * 同时覆盖 TTL 清理链路，确保过期文件会被移除，而不是继续长期占用磁盘。
     */
    @Test
    public void cacheAttachmentWritesDurableFileAndCleanupRemovesExpiredFiles() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("codex-history-cache-service");
        Path configRoot = workspaceRoot.resolve(".codemoss");
        try {
            CodexHistoryImageCacheService service = new CodexHistoryImageCacheService(
                    new StubSettingsService(),
                    new StubConfigPathManager(configRoot)
            );

            ClaudeSession.Attachment attachment = new ClaudeSession.Attachment(
                    "demo.png",
                    "image/png",
                    java.util.Base64.getEncoder().encodeToString("png-bytes".getBytes(StandardCharsets.UTF_8))
            );

            CodexHistoryImageCacheService.CacheWriteResult result = service.cacheAttachment(attachment, "session-1", 0);
            assertTrue(result.isHistoryReplayGuaranteed());
            assertTrue(Files.isRegularFile(result.getPath()));
            assertTrue(result.getPath().startsWith(configRoot.resolve("caches").resolve("codex-history-images")));
            Path indexPath = configRoot.resolve("caches").resolve("codex-history-images").resolve("index.json");
            assertTrue(Files.isRegularFile(indexPath));
            JsonArray indexEntries = JsonParser.parseString(Files.readString(indexPath)).getAsJsonArray();
            assertEquals(1, indexEntries.size());
            assertEquals("session-1", indexEntries.get(0).getAsJsonObject().get("sessionId").getAsString());
            assertEquals(0, indexEntries.get(0).getAsJsonObject().get("imageOrder").getAsInt());

            Path expiredFile = configRoot.resolve("caches").resolve("codex-history-images").resolve("expired.png");
            Files.createDirectories(expiredFile.getParent());
            Files.writeString(expiredFile, "old-bytes", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(expiredFile, FileTime.from(Instant.now().minusSeconds(3L * 24L * 3600L)));

            CodexHistoryImageCacheService.CacheCleanupResult cleanupResult = service.cleanupCache();

            assertFalse(Files.exists(expiredFile));
            assertEquals(1, cleanupResult.getExpiredDeletedCount());
            assertEquals(0, cleanupResult.getSizeDeletedCount());
            assertTrue(cleanupResult.getTotalSizeBytesAfterCleanup() >= 0L);
        } finally {
            deleteDirectory(workspaceRoot);
        }
    }

    /**
     * 验证容量上限回收会删除最旧的文件，并把删除统计回写到清理结果中。
     * 该用例覆盖 Task 4 中“基于 maxSizeMb 的容量治理”收口要求，
     * 避免后续只做物理删除而没有可观测的清理统计。
     */
    @Test
    public void cleanupCacheDeletesOldestFilesWhenDirectoryExceedsSizeLimit() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("codex-history-cache-size-limit");
        Path configRoot = workspaceRoot.resolve(".codemoss");
        try {
            CodexHistoryImageCacheService service = new CodexHistoryImageCacheService(
                    new StubSettingsService(30, 1),
                    new StubConfigPathManager(configRoot)
            );

            Path cacheDir = configRoot.resolve("caches").resolve("codex-history-images");
            Files.createDirectories(cacheDir);
            Path oldest = cacheDir.resolve("oldest.png");
            Path newer = cacheDir.resolve("newer.png");
            Files.write(oldest, new byte[700 * 1024]);
            Files.write(newer, new byte[700 * 1024]);
            Files.setLastModifiedTime(oldest, FileTime.from(Instant.now().minusSeconds(120)));
            Files.setLastModifiedTime(newer, FileTime.from(Instant.now().minusSeconds(60)));

            CodexHistoryImageCacheService.CacheCleanupResult cleanupResult = service.cleanupCache();

            assertFalse(Files.exists(oldest));
            assertTrue(Files.exists(newer));
            assertEquals(0, cleanupResult.getExpiredDeletedCount());
            assertEquals(1, cleanupResult.getSizeDeletedCount());
            assertTrue(cleanupResult.getTotalSizeBytesAfterCleanup() <= 1024L * 1024L);
        } finally {
            deleteDirectory(workspaceRoot);
        }
    }

    /**
     * 验证当用户配置的缓存目录不可写时，服务会自动回退到默认缓存目录，
     * 而不是让图片发送链路直接失败。
     * 这里用“自定义路径指向普通文件”模拟不可写目录场景，避免依赖平台文件权限差异。
     */
    @Test
    public void cacheAttachmentFallsBackToDefaultDirectoryWhenCustomDirIsUnavailable() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("codex-history-cache-fallback");
        Path configRoot = workspaceRoot.resolve(".codemoss");
        try {
            Path invalidCustomPath = workspaceRoot.resolve("not-a-directory.txt");
            Files.writeString(invalidCustomPath, "occupied", StandardCharsets.UTF_8);
            CodexHistoryImageCacheService service = new CodexHistoryImageCacheService(
                    new StubSettingsService(invalidCustomPath.toString(), 30, 64),
                    new StubConfigPathManager(configRoot)
            );

            ClaudeSession.Attachment attachment = new ClaudeSession.Attachment(
                    "fallback.png",
                    "image/png",
                    java.util.Base64.getEncoder().encodeToString("fallback-bytes".getBytes(StandardCharsets.UTF_8))
            );

            CodexHistoryImageCacheService.CacheWriteResult result = service.cacheAttachment(attachment, "session-fallback", 0);

            assertTrue(result.isHistoryReplayGuaranteed());
            assertTrue(result.getPath().startsWith(configRoot.resolve("caches").resolve("codex-history-images")));
        } finally {
            deleteDirectory(workspaceRoot);
        }
    }

    private static class StubSettingsService extends CodemossSettingsService {
        private final String customDir;
        private final int retentionDays;
        private final int maxSizeMb;

        private StubSettingsService() {
            this("", 1, 64);
        }

        private StubSettingsService(int retentionDays, int maxSizeMb) {
            this("", retentionDays, maxSizeMb);
        }

        private StubSettingsService(String customDir, int retentionDays, int maxSizeMb) {
            this.customDir = customDir;
            this.retentionDays = retentionDays;
            this.maxSizeMb = maxSizeMb;
        }

        @Override
        public JsonObject getCodexHistoryImageCacheConfig() {
            JsonObject config = new JsonObject();
            config.addProperty("customDir", customDir);
            config.addProperty("retentionDays", retentionDays);
            config.addProperty("maxSizeMb", maxSizeMb);
            return config;
        }
    }

    private static class StubConfigPathManager extends ConfigPathManager {
        private final Path configDir;

        private StubConfigPathManager(Path configDir) {
            this.configDir = configDir;
        }

        @Override
        public Path getConfigDir() {
            return configDir;
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
