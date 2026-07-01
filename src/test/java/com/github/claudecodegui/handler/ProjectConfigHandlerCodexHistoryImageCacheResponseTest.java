package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/**
 * 验证 Codex 历史图片缓存设置响应会回传“当前实际可用目录”，
 * 而不是在自定义目录不可用时仍盲目回显用户输入的路径。
 */
public class ProjectConfigHandlerCodexHistoryImageCacheResponseTest {

    /**
     * 验证当自定义缓存目录不可用时，设置页响应中的 `resolvedDir`
     * 会回退到默认缓存目录，避免界面展示与真实落盘目录不一致。
     */
    @Test
    public void codexHistoryImageCacheResponseUsesEffectiveResolvedDirectory() throws Exception {
        Path workspaceRoot = Files.createTempDirectory("project-config-handler-codex-cache");
        Path configRoot = workspaceRoot.resolve(".codemoss");
        try {
            Path invalidCustomPath = workspaceRoot.resolve("not-a-directory.txt");
            Files.writeString(invalidCustomPath, "occupied", StandardCharsets.UTF_8);
            FakeSettingsService settingsService = new FakeSettingsService(invalidCustomPath.toString(), 30, 64);
            TestableProjectConfigHandler handler = new TestableProjectConfigHandler(
                    contextWith(settingsService),
                    settingsService,
                    configRoot
            );

            JsonObject response = handler.exposeBuildCodexHistoryImageCacheResponse();

            assertEquals(invalidCustomPath.toString(), response.get("customDir").getAsString());
            assertEquals(
                    configRoot.resolve("caches").resolve("codex-history-images").toAbsolutePath().normalize().toString(),
                    response.get("resolvedDir").getAsString()
            );
        } finally {
            deleteDirectory(workspaceRoot);
        }
    }

    private HandlerContext contextWith(CodemossSettingsService settingsService) {
        return new HandlerContext(
                null,
                null,
                null,
                settingsService,
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                }
        );
    }

    private static class FakeSettingsService extends CodemossSettingsService {
        private final String customDir;
        private final int retentionDays;
        private final int maxSizeMb;

        private FakeSettingsService(String customDir, int retentionDays, int maxSizeMb) {
            this.customDir = customDir;
            this.retentionDays = retentionDays;
            this.maxSizeMb = maxSizeMb;
        }

        @Override
        public JsonObject getCodexHistoryImageCacheConfig() throws IOException {
            JsonObject config = new JsonObject();
            config.addProperty("customDir", customDir);
            config.addProperty("retentionDays", retentionDays);
            config.addProperty("maxSizeMb", maxSizeMb);
            return config;
        }
    }

    private static class TestableProjectConfigHandler extends ProjectConfigHandler {
        private final Path configRoot;

        private TestableProjectConfigHandler(HandlerContext context, CodemossSettingsService settingsService, Path configRoot) {
            super(context);
            this.configRoot = configRoot;
        }

        /**
         * 通过反射调用私有响应构造逻辑，避免为测试放大生产代码暴露面。
         */
        JsonObject exposeBuildCodexHistoryImageCacheResponse() throws Exception {
            Method method = ProjectConfigHandler.class.getDeclaredMethod("buildCodexHistoryImageCacheResponse");
            method.setAccessible(true);
            return (JsonObject) method.invoke(this);
        }

        /**
         * 测试只关心 handler 是否消费“真实生效目录”，而不是盲目回显 customDir 原值。
         * 目录回退算法本身由缓存服务测试覆盖，这里直接固定预期目录，避免为了测试放大生产代码改动面。
         */
        @Override
        Path resolveEffectiveCodexHistoryImageCacheDir() throws Exception {
            return configRoot.resolve("caches").resolve("codex-history-images");
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
