package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HistoryMessageInjectorTest {

    @Test
    public void convertCodexMessagesDeduplicatesDualRecordedUserMessage() {
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", "hello"));
        messages.add(eventUserMessage("2026-04-30T09:40:26.701Z", "hello"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).get("type").getAsString());
        assertEquals("hello", result.get(0).get("content").getAsString());
    }

    @Test
    public void convertCodexMessagesKeepsRepeatedUserMessagesWithDifferentTimestamps() {
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", "hello"));
        messages.add(eventUserMessage("2026-04-30T09:40:27.701Z", "hello"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(2, result.size());
    }

    @Test
    public void convertCodexMessagesDeduplicatesImageWrappedDualRecordedUserMessage() {
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", "<image name=[Image #1]>\n</image>\nhello"));
        messages.add(eventUserMessage("2026-04-30T09:40:26.701Z", "hello"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("<image name=[Image #1]>\n</image>\nhello", result.get(0).get("content").getAsString());
    }

    @Test
    public void convertCodexMessagesStripsAgentsInstructionsFromDuplicatedUserMessage() {
        String text = "<agents-instructions>\n"
                + "# Global Instructions\n\n"
                + "请默认使用中文（简体）回复。\n"
                + "</agents-instructions>\n\n"
                + "hello";
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", text));
        messages.add(eventUserMessage("2026-04-30T09:40:26.701Z", text));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("hello", result.get(0).get("content").getAsString());
        assertEquals("hello", result.get(0)
                .getAsJsonObject("raw")
                .getAsJsonArray("content")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString());
    }

    @Test
    public void convertCodexMessagesRestoresLocalImagesFromEventMessage() throws Exception {
        Path imagePath = Files.createTempFile("codex-history-image", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonArray messages = new JsonArray();
            messages.add(eventUserMessage("2026-05-11T09:02:20.861Z", "hello", imagePath.toString()));

            List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

            assertEquals(1, result.size());
            JsonArray contentBlocks = result.get(0).getAsJsonObject("raw").getAsJsonArray("content");
            assertEquals(2, contentBlocks.size());
            assertEquals("image", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("image/png", contentBlocks.get(0).getAsJsonObject().get("mediaType").getAsString());
            assertTrue(contentBlocks.get(0).getAsJsonObject().get("src").getAsString().startsWith("data:image/png;base64,"));
            assertEquals("text", contentBlocks.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("hello", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void convertCodexMessagesKeepsImageOnlyEventMessage() throws Exception {
        Path imagePath = Files.createTempFile("codex-history-image-only", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonArray messages = new JsonArray();
            messages.add(eventUserMessage("2026-05-11T09:03:20.861Z", "", imagePath.toString()));

            List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

            assertEquals(1, result.size());
            assertEquals("", result.get(0).get("content").getAsString());
            JsonArray contentBlocks = result.get(0).getAsJsonObject("raw").getAsJsonArray("content");
            assertEquals(1, contentBlocks.size());
            assertEquals("image", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
            assertTrue(contentBlocks.get(0).getAsJsonObject().get("src").getAsString().startsWith("data:image/png;base64,"));
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    /**
     * 验证当历史图片缓存文件已经被清理时，历史恢复不会退化为原始协议文本。
     * 断言意图：
     * 1. 用户消息仍然保留 image_missing 结构化占位；
     * 2. raw 元数据会记录声明过的图片数量与缺失数量，供后续去重与复制链路使用。
     */
    @Test
    public void convertCodexMessagesCreatesImageMissingBlockWhenCacheFileWasRemoved() {
        JsonArray messages = new JsonArray();
        messages.add(eventUserMessage("2026-05-11T09:04:20.861Z", "hello", "C:/missing/history-image.png"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        JsonObject raw = result.get(0).getAsJsonObject("raw");
        JsonArray contentBlocks = raw.getAsJsonArray("content");
        assertEquals("image_missing", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("history-image.png", contentBlocks.get(0).getAsJsonObject().get("fileName").getAsString());
        assertEquals(1, raw.get("__declaredLocalImageCount").getAsInt());
        assertEquals(1, raw.get("__missingLocalImageCount").getAsInt());
    }

    /**
     * 验证 Codex 双记录场景里，即使缓存图片已失效，也优先保留声明过 local_images 的 event_msg。
     * 这样会话历史不会被 `<image ...>` 占位文案反向覆盖，复制回聊天输入框时仍能保留兜底语义。
     */
    @Test
    public void convertCodexMessagesPrefersDeclaredImageMessageOverPlaceholderDuplicate() {
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-05-11T09:05:20.861Z", "<image name=[Image #1]>\n</image>\nhello"));
        messages.add(eventUserMessage("2026-05-11T09:05:20.861Z", "hello", "C:/missing/history-image.png"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        JsonArray contentBlocks = result.get(0).getAsJsonObject("raw").getAsJsonArray("content");
        assertEquals("image_missing", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("hello", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
    }

    @Test
    public void convertCodexMessagesStripsAppendedProjectModulesContext() {
        JsonArray messages = new JsonArray();
        messages.add(eventUserMessage(
                "2026-05-11T09:03:20.861Z",
                "只保留用户输入\n\n## Project Modules\n\nThis project contains multiple modules:\n- `idea-claude-code-gui`\n"
        ));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("只保留用户输入", result.get(0).get("content").getAsString());
        JsonArray contentBlocks = result.get(0).getAsJsonObject("raw").getAsJsonArray("content");
        assertEquals(1, contentBlocks.size());
        assertEquals("只保留用户输入", contentBlocks.get(0).getAsJsonObject().get("text").getAsString());
    }

    private static JsonObject responseItemUserMessage(String timestamp, String text) {
        JsonObject line = new JsonObject();
        line.addProperty("timestamp", timestamp);
        line.addProperty("type", "response_item");

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "message");
        payload.addProperty("role", "user");

        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "input_text");
        block.addProperty("text", text);
        content.add(block);

        payload.add("content", content);
        line.add("payload", payload);
        return line;
    }

    private static JsonObject eventUserMessage(String timestamp, String text) {
        JsonObject line = new JsonObject();
        line.addProperty("timestamp", timestamp);
        line.addProperty("type", "event_msg");

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "user_message");
        payload.addProperty("message", text);
        line.add("payload", payload);
        return line;
    }

    private static JsonObject eventUserMessage(String timestamp, String text, String localImagePath) {
        JsonObject line = eventUserMessage(timestamp, text);
        JsonArray localImages = new JsonArray();
        localImages.add(localImagePath);
        line.getAsJsonObject("payload").add("local_images", localImages);
        return line;
    }

    /**
     * 验证当已注册 SessionLifecycleManager 回调时，Codex 历史加载会统一交给主链处理，
     * 而不是继续由 HistoryMessageInjector 自行注入前端。
     */
    @Test
    public void handleLoadSessionDelegatesCodexHistoryToSessionLoadCallbackWhenAvailable() {
        Project project = createProject();
        HandlerContext context = new HandlerContext(
                project,
                null,
                null,
                new CodemossSettingsService(),
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
        HistoryMessageInjector injector = new HistoryMessageInjector(context);
        AtomicReference<String> callbackSessionId = new AtomicReference<>();
        AtomicReference<String> callbackRuntimeFamily = new AtomicReference<>();

        injector.handleLoadSession(
                "{\"sessionId\":\"codex-session-001\",\"provider\":\"codex\",\"runtimeFamily\":\"codex\",\"restoreSource\":\"history_switch\",\"transitionToken\":\"token-001\"}",
                "claude",
                (sessionId, projectPath, provider, runtimeFamily, restoreSource, transitionToken) -> {
                    callbackSessionId.set(sessionId);
                    callbackRuntimeFamily.set(runtimeFamily);
                }
        );

        assertEquals("codex-session-001", callbackSessionId.get());
        assertEquals("codex", callbackRuntimeFamily.get());
    }

    /**
     * 验证增强恢复主链提取 Codex 历史元信息时，会优先返回真实 threadId 与 cwd。
     */
    @Test
    public void extractCodexSessionMetaReturnsActualThreadIdAndCwd() {
        JsonArray messages = new JsonArray();
        JsonObject sessionMeta = new JsonObject();
        sessionMeta.addProperty("type", "session_meta");
        JsonObject payload = new JsonObject();
        payload.addProperty("id", "thread-actual-001");
        payload.addProperty("cwd", "E:/workspace/demo");
        sessionMeta.add("payload", payload);
        messages.add(sessionMeta);

        String[] meta = HistoryMessageInjector.extractCodexSessionMeta(messages);

        assertNotNull(meta);
        assertEquals("thread-actual-001", meta[0]);
        assertEquals("E:/workspace/demo", meta[1]);
    }

    private static Project createProject() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getBasePath" -> System.getProperty("java.io.tmpdir");
                    case "isDisposed" -> false;
                    case "getName" -> "history-message-injector-test";
                    default -> method.getReturnType().isPrimitive() ? defaultPrimitiveValue(method.getReturnType()) : null;
                }
        );
    }

    private static Object defaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        return 0;
    }
}
