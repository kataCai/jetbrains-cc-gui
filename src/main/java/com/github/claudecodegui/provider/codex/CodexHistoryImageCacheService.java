package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.settings.ConfigPathManager;
import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Codex 历史图片缓存服务。
 * <p>
 * 该服务负责把聊天输入框中的 base64 图片附件持久化为可回放的本地文件，
 * 供 Codex SDK 通过 `local_image.path` 发送，并在后续历史恢复阶段继续读取。
 * 与旧的“发送后立刻删除临时图”不同，这里会把图片写入稳定缓存目录，并按用户配置执行延迟清理。
 * <p>
 * 设计约束：
 * 1. 自定义缓存目录不可用时，自动回退到默认目录，避免发送链路直接失败。
 * 2. 默认目录也不可用时，再回退到临时目录，保证本次会话仍可发送，但不承诺长期可回放。
 * 3. 清理策略只在低频触发，避免每次发送都扫描大目录。
 */
public class CodexHistoryImageCacheService {

    private static final Logger LOG = Logger.getInstance(CodexHistoryImageCacheService.class);
    private static final String DEFAULT_CACHE_ROOT_DIR = "caches";
    private static final String DEFAULT_CACHE_DIR_NAME = "codex-history-images";
    private static final String CACHE_INDEX_FILE_NAME = "index.json";
    private static final long CLEANUP_INTERVAL_MS = 30L * 60L * 1000L;
    private static volatile long lastCleanupAt = 0L;
    private static final Object INDEX_WRITE_LOCK = new Object();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final CodemossSettingsService settingsService;
    private final ConfigPathManager pathManager;

    /**
     * 缓存清理结果。
     * <p>
     * 该结果用于把 TTL 清理、容量回收和最终保留体积统一记录下来，
     * 便于启动阶段、设置保存后或问题排查时快速判断“本次为什么有历史图片失效”。
     */
    public static class CacheCleanupResult {
        private final int expiredDeletedCount;
        private final int sizeDeletedCount;
        private final long totalSizeBytesAfterCleanup;

        /**
         * 构造缓存清理统计结果。
         *
         * @param expiredDeletedCount 因超过保留天数而删除的文件数
         * @param sizeDeletedCount 因超过容量上限而删除的文件数
         * @param totalSizeBytesAfterCleanup 清理完成后目录内剩余文件总字节数
         */
        public CacheCleanupResult(int expiredDeletedCount, int sizeDeletedCount, long totalSizeBytesAfterCleanup) {
            this.expiredDeletedCount = expiredDeletedCount;
            this.sizeDeletedCount = sizeDeletedCount;
            this.totalSizeBytesAfterCleanup = totalSizeBytesAfterCleanup;
        }

        /**
         * 获取 TTL 清理删除文件数。
         *
         * @return 过期删除数量
         */
        public int getExpiredDeletedCount() {
            return expiredDeletedCount;
        }

        /**
         * 获取容量回收删除文件数。
         *
         * @return 因容量上限删除的数量
         */
        public int getSizeDeletedCount() {
            return sizeDeletedCount;
        }

        /**
         * 获取清理后剩余总大小。
         *
         * @return 清理完成后的总字节数
         */
        public long getTotalSizeBytesAfterCleanup() {
            return totalSizeBytesAfterCleanup;
        }
    }

    /**
     * 缓存写入结果。
     * `historyReplayGuaranteed=false` 表示图片最终落在临时兜底目录，本次发送可继续，但历史回放不再强保证。
     */
    public static class CacheWriteResult {
        private final Path path;
        private final boolean historyReplayGuaranteed;

        /**
         * 构造缓存写入结果。
         *
         * @param path 实际落盘路径
         * @param historyReplayGuaranteed 是否位于正式缓存目录，能参与预期的历史回放
         */
        public CacheWriteResult(Path path, boolean historyReplayGuaranteed) {
            this.path = path;
            this.historyReplayGuaranteed = historyReplayGuaranteed;
        }

        /**
         * 获取实际落盘路径。
         *
         * @return 图片文件绝对路径
         */
        public Path getPath() {
            return path;
        }

        /**
         * 判断本次写入是否具备正式历史回放保障。
         *
         * @return true 表示进入正式缓存目录；false 表示仅落到临时兜底目录
         */
        public boolean isHistoryReplayGuaranteed() {
            return historyReplayGuaranteed;
        }
    }

    private static class CacheDirectoryResolution {
        private final Path directory;
        private final boolean historyReplayGuaranteed;

        private CacheDirectoryResolution(Path directory, boolean historyReplayGuaranteed) {
            this.directory = directory;
            this.historyReplayGuaranteed = historyReplayGuaranteed;
        }
    }

    /**
     * 使用默认配置服务构造缓存服务。
     */
    public CodexHistoryImageCacheService() {
        this(new CodemossSettingsService(), new ConfigPathManager());
    }

    /**
     * 供测试或上层注入自定义依赖的构造函数。
     *
     * @param settingsService 配置服务
     * @param pathManager 配置路径管理器
     */
    CodexHistoryImageCacheService(CodemossSettingsService settingsService, ConfigPathManager pathManager) {
        this.settingsService = settingsService;
        this.pathManager = pathManager;
    }

    /**
     * 解析设置页展示所需的默认缓存目录。
     *
     * @return 默认历史图片缓存目录
     */
    public Path getDefaultCacheDirectory() {
        return pathManager.getConfigDir().resolve(DEFAULT_CACHE_ROOT_DIR).resolve(DEFAULT_CACHE_DIR_NAME);
    }

    /**
     * 解析当前配置下真正会被使用的缓存目录。
     * <p>
     * 该方法与实际写入链路复用同一套目录回退逻辑，用于设置页展示和目录选择器初始值解析，
     * 避免界面展示“用户配置目录”而后端真实落盘到默认目录或临时目录时产生误导。
     *
     * @return 当前配置下最终会被使用的缓存目录绝对路径
     * @throws IOException 当所有可用目录都无法创建或写入时抛出
     */
    public Path getEffectiveCacheDirectory() throws IOException {
        return resolveWritableCacheDirectory().directory.toAbsolutePath().normalize();
    }

    /**
     * 把单张聊天图片附件写入历史缓存目录。
     *
     * @param attachment 前端传入的图片附件
     * @param sessionId 当前会话标识，可为空，仅用于日志辅助
     * @param imageOrder 当前消息中的图片顺序，从 0 开始
     * @return 写入结果，包含最终路径与是否具备正式历史回放保障
     * @throws IOException 当所有目录回退都失败时抛出
     */
    public CacheWriteResult cacheAttachment(ClaudeSession.Attachment attachment, String sessionId, int imageOrder) throws IOException {
        if (attachment == null || attachment.data == null || attachment.data.isBlank()) {
            throw new IOException("Image attachment payload is empty");
        }

        maybeCleanupCache();

        byte[] imageBytes = Base64.getDecoder().decode(attachment.data);
        CacheDirectoryResolution resolution = resolveWritableCacheDirectory();
        String extension = getImageExtension(attachment.mediaType, attachment.fileName);
        String fileName = buildCacheFileName(imageBytes, imageOrder, extension);
        Path imagePath = resolution.directory.resolve(fileName);

        Files.createDirectories(resolution.directory);
        Files.write(
                imagePath,
                imageBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        writeCacheIndexEntry(resolution.directory, imagePath, attachment, imageBytes, sessionId, imageOrder);

        LOG.info("[CodexHistoryImageCache] Cached image for session="
                + safe(sessionId)
                + ", path=" + imagePath
                + ", bytes=" + imageBytes.length
                + ", durable=" + resolution.historyReplayGuaranteed);
        return new CacheWriteResult(imagePath, resolution.historyReplayGuaranteed);
    }

    /**
     * 在历史页打开等低频场景下触发一次缓存治理检查。
     * <p>
     * 该入口只复用节流后的统一清理逻辑，不重复实现扫描与删除策略。
     */
    public void triggerHistoryAccessCleanup() {
        maybeCleanupCache();
    }

    /**
     * 按设置页中的 TTL 和容量上限清理缓存目录。
     * 当前实现仅清理正式缓存目录；临时兜底目录交给系统临时目录生命周期处理。
     */
    public CacheCleanupResult cleanupCache() {
        try {
            JsonCacheConfig cacheConfig = readCacheConfig();
            Path cacheDir = resolveConfiguredOrDefaultCacheDir(cacheConfig.customDir);
            if (!Files.isDirectory(cacheDir)) {
                return new CacheCleanupResult(0, 0, 0L);
            }

            List<Path> files = listRegularFiles(cacheDir);
            Instant expireBefore = Instant.now().minus(cacheConfig.retentionDays, ChronoUnit.DAYS);
            int expiredDeletedCount = 0;
            for (Path file : files) {
                try {
                    Instant lastModified = Files.getLastModifiedTime(file).toInstant();
                    if (lastModified.isBefore(expireBefore)) {
                        if (Files.deleteIfExists(file)) {
                            expiredDeletedCount++;
                        }
                    }
                } catch (Exception exception) {
                    LOG.debug("[CodexHistoryImageCache] Failed to delete expired cache image: " + file, exception);
                }
            }

            files = listRegularFiles(cacheDir);
            long maxSizeBytes = cacheConfig.maxSizeMb * 1024L * 1024L;
            long totalSizeBytes = 0L;
            for (Path file : files) {
                totalSizeBytes += Files.size(file);
            }
            if (totalSizeBytes <= maxSizeBytes) {
                CacheCleanupResult result = new CacheCleanupResult(expiredDeletedCount, 0, totalSizeBytes);
                logCleanupResult(cacheDir, result, cacheConfig);
                return result;
            }

            files.sort(Comparator.comparingLong(this::safeLastModifiedMillis));
            int sizeDeletedCount = 0;
            for (Path file : files) {
                if (totalSizeBytes <= maxSizeBytes) {
                    break;
                }
                try {
                    long fileSize = Files.size(file);
                    if (Files.deleteIfExists(file)) {
                        totalSizeBytes -= fileSize;
                        sizeDeletedCount++;
                    }
                } catch (Exception exception) {
                    LOG.debug("[CodexHistoryImageCache] Failed to delete overflow cache image: " + file, exception);
                }
            }
            CacheCleanupResult result = new CacheCleanupResult(expiredDeletedCount, sizeDeletedCount, totalSizeBytes);
            logCleanupResult(cacheDir, result, cacheConfig);
            return result;
        } catch (Exception exception) {
            LOG.warn("[CodexHistoryImageCache] Cleanup failed: " + exception.getMessage(), exception);
            return new CacheCleanupResult(0, 0, 0L);
        }
    }

    /**
     * 低频触发缓存清理，避免每次发送都做全目录扫描。
     */
    private void maybeCleanupCache() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupAt = now;
        cleanupCache();
    }

    /**
     * 读取并归一化缓存配置。
     */
    private JsonCacheConfig readCacheConfig() throws IOException {
        return JsonCacheConfig.fromJson(settingsService.getCodexHistoryImageCacheConfig());
    }

    /**
     * 解析最终可写缓存目录，并按“自定义目录 -> 默认目录 -> 临时目录”的顺序回退。
     */
    private CacheDirectoryResolution resolveWritableCacheDirectory() throws IOException {
        JsonCacheConfig cacheConfig = readCacheConfig();
        Path defaultDir = getDefaultCacheDirectory();

        if (cacheConfig.customDir != null && !cacheConfig.customDir.isBlank()) {
            Path customDir = resolveConfiguredOrDefaultCacheDir(cacheConfig.customDir);
            if (ensureWritableDirectory(customDir)) {
                return new CacheDirectoryResolution(customDir, true);
            }
            LOG.warn("[CodexHistoryImageCache] Custom cache directory is unavailable, fallback to default: " + customDir);
        }

        if (ensureWritableDirectory(defaultDir)) {
            return new CacheDirectoryResolution(defaultDir, true);
        }

        Path tempFallbackDir = Path.of(System.getProperty("java.io.tmpdir"), DEFAULT_CACHE_DIR_NAME);
        if (ensureWritableDirectory(tempFallbackDir)) {
            LOG.warn("[CodexHistoryImageCache] Default cache directory is unavailable, fallback to temp dir: " + tempFallbackDir);
            return new CacheDirectoryResolution(tempFallbackDir, false);
        }

        throw new IOException("No writable Codex history image cache directory is available");
    }

    /**
     * 把自定义目录字符串解析为绝对路径；空值时回退到默认目录。
     */
    private Path resolveConfiguredOrDefaultCacheDir(String customDir) {
        if (customDir == null || customDir.isBlank()) {
            return getDefaultCacheDirectory();
        }
        return Path.of(customDir).toAbsolutePath().normalize();
    }

    /**
     * 确保目录存在且可写。
     */
    private boolean ensureWritableDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            return Files.isDirectory(directory) && Files.isWritable(directory);
        } catch (Exception exception) {
            LOG.debug("[CodexHistoryImageCache] Directory is not writable: " + directory, exception);
            return false;
        }
    }

    /**
     * 生成稳定且可读的缓存文件名，避免重复发送同名图片时互相覆盖。
     */
    private String buildCacheFileName(byte[] imageBytes, int imageOrder, String extension) {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
        String hashPrefix = sha256Prefix(imageBytes);
        return timestamp + "-" + hashPrefix + "-" + imageOrder + extension;
    }

    /**
     * 根据 MIME 类型和原始文件名推断扩展名。
     */
    private String getImageExtension(String mediaType, String fileName) {
        if (fileName != null) {
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex < fileName.length() - 1) {
                return fileName.substring(dotIndex).toLowerCase(Locale.ROOT);
            }
        }
        if (mediaType == null) {
            return ".png";
        }
        return switch (mediaType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            case "image/svg+xml" -> ".svg";
            default -> ".png";
        };
    }

    /**
     * 计算内容哈希前缀，帮助文件名具备去重辨识度。
     */
    private String sha256Prefix(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(6, digest.length); i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            return builder.toString();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    /**
     * 列出目录下的普通文件，忽略子目录和异常项。
     */
    private List<Path> listRegularFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !CACHE_INDEX_FILE_NAME.equalsIgnoreCase(path.getFileName().toString()))
                    .forEach(files::add);
        }
        return files;
    }

    /**
     * 追加最小可用缓存索引元数据。
     * <p>
     * 当前索引主要服务于诊断与后续扩展，不作为主链路恢复的强依赖，
     * 因此索引写入失败只记日志，不阻断发送流程。
     *
     * @param cacheDir 当前生效的缓存目录
     * @param imagePath 图片实际落盘路径
     * @param attachment 原始附件信息
     * @param imageBytes 图片字节
     * @param sessionId 会话标识
     * @param imageOrder 图片顺序
     */
    private void writeCacheIndexEntry(
            Path cacheDir,
            Path imagePath,
            ClaudeSession.Attachment attachment,
            byte[] imageBytes,
            String sessionId,
            int imageOrder
    ) {
        synchronized (INDEX_WRITE_LOCK) {
            try {
                Path indexPath = cacheDir.resolve(CACHE_INDEX_FILE_NAME);
                JsonArray entries = readExistingIndex(indexPath);
                com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
                String cacheFileName = imagePath.getFileName() != null ? imagePath.getFileName().toString() : "";
                entry.addProperty("cacheKey", cacheFileName);
                entry.addProperty("absolutePath", imagePath.toAbsolutePath().normalize().toString());
                entry.addProperty("fileName", attachment.fileName != null && !attachment.fileName.isBlank()
                        ? attachment.fileName
                        : cacheFileName);
                entry.addProperty("mediaType", attachment.mediaType != null ? attachment.mediaType : "");
                entry.addProperty("sizeBytes", imageBytes.length);
                entry.addProperty("sha256", sha256Hex(imageBytes));
                entry.addProperty("createdAt", Instant.now().toString());
                entry.addProperty("lastAccessAt", Instant.now().toString());
                entry.addProperty("sessionId", safe(sessionId));
                entry.addProperty("imageOrder", imageOrder);
                entries.add(entry);
                Files.writeString(
                        indexPath,
                        GSON.toJson(entries),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
            } catch (Exception exception) {
                LOG.warn("[CodexHistoryImageCache] Failed to update cache index: " + exception.getMessage(), exception);
            }
        }
    }

    /**
     * 读取已有索引内容；损坏时回退为空数组。
     *
     * @param indexPath 索引文件路径
     * @return 可追加写入的索引数组
     */
    private JsonArray readExistingIndex(Path indexPath) {
        try {
            if (!Files.isRegularFile(indexPath)) {
                return new JsonArray();
            }
            return JsonParser.parseString(Files.readString(indexPath)).getAsJsonArray();
        } catch (Exception exception) {
            LOG.warn("[CodexHistoryImageCache] Cache index is unreadable, recreating: " + indexPath, exception);
            return new JsonArray();
        }
    }

    /**
     * 统一记录缓存清理结果。
     * <p>
     * 仅在真正执行完扫描后输出统计日志，便于后续从 IDE 日志中定位：
     * 1. 是否发生了 TTL 删除；
     * 2. 是否因为容量上限触发回收；
     * 3. 当前清理策略和剩余体积分别是多少。
     *
     * @param cacheDir 实际参与清理的缓存目录
     * @param result 清理统计结果
     * @param cacheConfig 生效中的缓存配置
     */
    private void logCleanupResult(Path cacheDir, CacheCleanupResult result, JsonCacheConfig cacheConfig) {
        LOG.info("[CodexHistoryImageCache] Cleanup summary: dir="
                + cacheDir
                + ", expiredDeleted=" + result.getExpiredDeletedCount()
                + ", sizeDeleted=" + result.getSizeDeletedCount()
                + ", remainingBytes=" + result.getTotalSizeBytesAfterCleanup()
                + ", retentionDays=" + cacheConfig.retentionDays
                + ", maxSizeMb=" + cacheConfig.maxSizeMb);
    }

    /**
     * 安全读取最后修改时间，失败时把文件视为最旧。
     */
    private long safeLastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception exception) {
            return Long.MIN_VALUE;
        }
    }

    /**
     * 计算完整 SHA-256 字符串，供索引登记使用。
     *
     * @param bytes 原始图片字节
     * @return 完整 SHA-256 十六进制字符串
     */
    private String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder builder = new StringBuilder();
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception exception) {
            return "unknown";
        }
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    /**
     * 用于在服务内部消费的归一化缓存配置。
     */
    private static class JsonCacheConfig {
        private final String customDir;
        private final int retentionDays;
        private final int maxSizeMb;

        private JsonCacheConfig(String customDir, int retentionDays, int maxSizeMb) {
            this.customDir = customDir;
            this.retentionDays = retentionDays;
            this.maxSizeMb = maxSizeMb;
        }

        private static JsonCacheConfig fromJson(com.google.gson.JsonObject json) {
            String customDir = json != null && json.has("customDir") && !json.get("customDir").isJsonNull()
                    ? json.get("customDir").getAsString().trim()
                    : "";
            int retentionDays = json != null && json.has("retentionDays") && !json.get("retentionDays").isJsonNull()
                    ? json.get("retentionDays").getAsInt()
                    : CodemossSettingsService.DEFAULT_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS;
            int maxSizeMb = json != null && json.has("maxSizeMb") && !json.get("maxSizeMb").isJsonNull()
                    ? json.get("maxSizeMb").getAsInt()
                    : CodemossSettingsService.DEFAULT_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB;
            return new JsonCacheConfig(customDir, retentionDays, maxSizeMb);
        }
    }
}
