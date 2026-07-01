package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.CodexMessageConverter;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.SessionRuntimeFamily;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.session.SessionState;
import com.github.claudecodegui.util.JsUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

/**
 * Service for loading session messages and injecting them into the frontend.
 * Handles both Claude and Codex session loading.
 */
public class HistoryMessageInjector {

    private static final Logger LOG = Logger.getInstance(HistoryMessageInjector.class);
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";

    private final HandlerContext context;

    HistoryMessageInjector(HandlerContext context) {
        this.context = context;
    }

    /**
     * Load a history session.
     */
    void handleLoadSession(String sessionId, String currentProvider, HistoryHandler.SessionLoadCallback sessionLoadCallback) {
        String provider = currentProvider;
        String runtimeFamily = null;
        String restoreSource = "history_switch";
        String transitionToken = null;
        String resolvedSessionId = sessionId;

        try {
            JsonObject payload = new Gson().fromJson(sessionId, JsonObject.class);
            if (payload != null) {
                if (payload.has("sessionId") && !payload.get("sessionId").isJsonNull()) {
                    resolvedSessionId = payload.get("sessionId").getAsString();
                }
                if (payload.has("provider") && !payload.get("provider").isJsonNull()) {
                    provider = payload.get("provider").getAsString();
                }
                if (payload.has("runtimeFamily") && !payload.get("runtimeFamily").isJsonNull()) {
                    runtimeFamily = payload.get("runtimeFamily").getAsString();
                }
                if (payload.has("restoreSource") && !payload.get("restoreSource").isJsonNull()) {
                    restoreSource = payload.get("restoreSource").getAsString();
                }
                if (payload.has("transitionToken") && !payload.get("transitionToken").isJsonNull()) {
                    transitionToken = payload.get("transitionToken").getAsString();
                }
            }
        } catch (Exception ignored) {
            // Backward compatible: legacy payload is the raw sessionId string.
        }

        String projectPath = context.getProject().getBasePath();
        if (projectPath == null) {
            LOG.warn("[HistoryHandler] Project base path is null");
            return;
        }
        String resolvedRuntimeFamily = SessionRuntimeFamily.resolve(
                provider,
                runtimeFamily,
                context.getSession() != null ? context.getSession().getState().getCodexSessionBinding() : null
        );
        LOG.info("[HistoryHandler] Loading history session: " + resolvedSessionId
                + " from project: " + projectPath + ", provider: " + provider
                + ", runtimeFamily=" + resolvedRuntimeFamily
                + ", restoreSource=" + restoreSource
                + ", transitionToken=" + transitionToken
                + ", currentProvider=" + currentProvider);

        if (sessionLoadCallback != null) {
            sessionLoadCallback.onLoadSession(
                    resolvedSessionId,
                    projectPath,
                    provider,
                    resolvedRuntimeFamily,
                    restoreSource,
                    transitionToken
            );
        } else if (SessionRuntimeFamily.CODEX.equals(resolvedRuntimeFamily)) {
            // 兼容没有注册 SessionLifecycleManager 回调的旧调用场景，避免 Codex 历史完全不可用。
            loadCodexSession(resolvedSessionId);
        } else {
            LOG.warn("[HistoryHandler] WARNING: No session load callback set");
        }
    }

    /**
     * Load a Codex session.
     * Reads session messages directly and injects them into the frontend, while restoring session state.
     */
    private void loadCodexSession(String sessionId) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始加载 Codex 会话 ==========");
            LOG.info("[HistoryHandler] SessionId: " + sessionId);

            try {
                CodexHistoryReader codexReader = new CodexHistoryReader();
                String messagesJson = codexReader.getSessionMessagesAsJson(sessionId);
                JsonArray messages = JsonParser.parseString(messagesJson).getAsJsonArray();

                LOG.info("[HistoryHandler] 读取到 " + messages.size() + " 条 Codex 消息");

                // Extract session metadata and restore session state
                String[] sessionMeta = extractSessionMeta(messages);
                String threadIdToUse = sessionMeta[0] != null ? sessionMeta[0] : sessionId;
                String cwd = sessionMeta[1];

                context.getSession().setSessionInfo(threadIdToUse, cwd);
                applyCodexSessionBinding(threadIdToUse);
                restoreCodexMessagesToSessionState(context.getSession().getState(), messages);
                LOG.info("[HistoryHandler] 恢复 Codex 会话状态: threadId=" + threadIdToUse + " (from sessionId=" + sessionId + "), cwd=" + cwd);

                List<JsonObject> frontendMessages = convertCodexMessagesToFrontendBatch(messages);
                injectBatchToFrontend(frontendMessages);

                // Notify frontend that history messages have finished loading, trigger Markdown re-rendering
                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.historyLoadComplete) { " +
                                            "  try { " +
                                            "    window.historyLoadComplete(); " +
                                            "  } catch(e) { " +
                                            "    console.error('[HistoryHandler] historyLoadComplete callback failed:', e); " +
                                            "  } " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });

                LOG.info("[HistoryHandler] ========== Codex 会话加载完成 ==========");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 加载 Codex 会话失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误");
                    String jsCode = "if (window.addErrorMessage) { " +
                                            "  window.addErrorMessage('加载 Codex 会话失败: " + errorMsg + "'); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * Extract Codex session metadata (threadId and cwd).
     *
     * @return String[2]: [0]=actualThreadId, [1]=cwd
     */
    private String[] extractSessionMeta(JsonArray messages) {
        return extractCodexSessionMeta(messages);
    }

    /**
     * 提取 Codex 历史消息中的会话元信息。
     * 这里统一返回真实 threadId 与 cwd，供历史恢复链路复用，避免多处各自解析导致规则漂移。
     *
     * @param messages Codex 原始历史消息数组
     * @return 长度为 2 的数组：[0] 为实际 threadId，[1] 为 cwd；缺失时对应元素为 null
     */
    public static String[] extractCodexSessionMeta(JsonArray messages) {
        String cwd = null;
        String actualThreadId = null;

        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            if (msg.has("type") && "session_meta".equals(msg.get("type").getAsString())) {
                if (msg.has("payload")) {
                    JsonObject payload = msg.getAsJsonObject("payload");
                    if (payload.has("cwd")) {
                        cwd = payload.get("cwd").getAsString();
                    }
                    if (payload.has("id")) {
                        actualThreadId = payload.get("id").getAsString();
                    }
                    break;
                }
            }
        }

        return new String[]{actualThreadId, cwd};
    }

    /**
     * 将 Codex 历史消息批量转换为前端消息列表。
     * 只统一前端注入协议，不改变 Codex 历史文件格式与标题数据来源。
     */
    public static List<JsonObject> convertCodexMessagesToFrontendBatch(JsonArray messages) {
        List<JsonObject> frontendMessages = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            JsonObject frontendMsg = convertCodexMessageToFrontend(msg);
            if (frontendMsg != null) {
                addCodexFrontendMessage(frontendMessages, frontendMsg);
            }
        }
        return frontendMessages;
    }

    /**
     * 为历史恢复后的前端消息快照构造稳定签名。
     * 该签名只关心“界面最终可见语义”，尤其会显式纳入图片块、失效图片占位块与文本块的关键信息，
     * 供前端判断同一 restore key 下的 `updateMessages` 是否只是重复注入同一份历史快照。
     *
     * @param frontendMessages 最终准备注入前端的消息列表
     * @return 稳定的历史快照签名
     */
    public static String buildFrontendSnapshotSignature(List<JsonObject> frontendMessages) {
        CRC32 checksum = new CRC32();
        updateSnapshotChecksum(checksum, "count:" + (frontendMessages != null ? frontendMessages.size() : 0));
        if (frontendMessages == null) {
            return "0-" + Long.toHexString(checksum.getValue());
        }

        for (JsonObject message : frontendMessages) {
            appendFrontendMessageSignature(checksum, message);
        }
        return frontendMessages.size() + "-" + Long.toHexString(checksum.getValue());
    }

    private static void addCodexFrontendMessage(List<JsonObject> frontendMessages, JsonObject incoming) {
        if (frontendMessages.isEmpty()) {
            frontendMessages.add(incoming);
            return;
        }

        int lastIndex = frontendMessages.size() - 1;
        JsonObject previous = frontendMessages.get(lastIndex);
        if (isDuplicateAdjacentCodexUserMessage(previous, incoming)) {
            frontendMessages.set(lastIndex, preferRicherUserMessage(previous, incoming));
            return;
        }

        frontendMessages.add(incoming);
    }

    private static boolean isDuplicateAdjacentCodexUserMessage(JsonObject previous, JsonObject incoming) {
        if (!isUserMessage(previous) || !isUserMessage(incoming)) {
            return false;
        }

        String previousTimestamp = getStringProperty(previous, "timestamp");
        String incomingTimestamp = getStringProperty(incoming, "timestamp");
        if (previousTimestamp == null || !previousTimestamp.equals(incomingTimestamp)) {
            return false;
        }

        String previousContent = getStringProperty(previous, "content");
        String incomingContent = getStringProperty(incoming, "content");
        return previousContent != null
            && normalizeDuplicateUserContent(previousContent).equals(normalizeDuplicateUserContent(incomingContent));
    }

    private static JsonObject preferRicherUserMessage(JsonObject previous, JsonObject incoming) {
        int previousScore = getUserMessageSemanticScore(previous);
        int incomingScore = getUserMessageSemanticScore(incoming);
        if (incomingScore != previousScore) {
            return incomingScore > previousScore ? incoming : previous;
        }
        return getRawContentBlockCount(incoming) > getRawContentBlockCount(previous) ? incoming : previous;
    }

    private static String normalizeDuplicateUserContent(String content) {
        if (content == null) {
            return "";
        }
        return content
            .replaceAll("(?m)^<image[^\\r\\n]*>\\R?", "")
            .replaceAll("(?m)^</image>\\R?", "")
            .trim();
    }

    private static boolean isUserMessage(JsonObject message) {
        return "user".equals(getStringProperty(message, "type"));
    }

    private static String getStringProperty(JsonObject object, String propertyName) {
        if (object == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        return object.get(propertyName).getAsString();
    }

    /**
     * 把单条前端消息的稳定语义追加到快照校验和。
     *
     * @param checksum 当前累计校验和
     * @param message 单条前端消息
     */
    private static void appendFrontendMessageSignature(CRC32 checksum, JsonObject message) {
        if (message == null) {
            updateSnapshotChecksum(checksum, "message:null");
            return;
        }

        updateSnapshotChecksum(checksum, "type:" + getStringProperty(message, "type"));
        updateSnapshotChecksum(checksum, "timestamp:" + getStringProperty(message, "timestamp"));
        updateSnapshotChecksum(checksum, "content:" + getStringProperty(message, "content"));

        JsonObject raw = message.has("raw") && message.get("raw").isJsonObject()
                ? message.getAsJsonObject("raw")
                : null;
        JsonArray rawBlocks = extractFrontendRawBlocks(raw);
        updateSnapshotChecksum(checksum, "rawBlockCount:" + rawBlocks.size());
        for (JsonElement blockElement : rawBlocks) {
            appendFrontendRawBlockSignature(checksum, blockElement);
        }
    }

    /**
     * 提取前端消息中的 raw block 数组。
     * 兼容 `raw.message.content` 与 `raw.content` 两种结构，避免签名逻辑与具体来源过度耦合。
     *
     * @param raw 前端消息 raw 对象
     * @return 可遍历的 raw block 数组；没有内容时返回空数组
     */
    private static JsonArray extractFrontendRawBlocks(JsonObject raw) {
        if (raw == null) {
            return new JsonArray();
        }
        if (raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject innerMessage = raw.getAsJsonObject("message");
            if (innerMessage.has("content") && innerMessage.get("content").isJsonArray()) {
                return innerMessage.getAsJsonArray("content");
            }
        }
        if (raw.has("content") && raw.get("content").isJsonArray()) {
            return raw.getAsJsonArray("content");
        }
        return new JsonArray();
    }

    /**
     * 把单个 raw block 的关键语义追加到快照校验和。
     * 对图片类 block 会显式纳入 `src/mediaType/alt` 或失效占位原因，确保图片恢复结果变化时签名同步变化。
     *
     * @param checksum 当前累计校验和
     * @param blockElement 单个 raw block
     */
    private static void appendFrontendRawBlockSignature(CRC32 checksum, JsonElement blockElement) {
        if (blockElement == null || !blockElement.isJsonObject()) {
            updateSnapshotChecksum(checksum, "block:" + String.valueOf(blockElement));
            return;
        }

        JsonObject block = blockElement.getAsJsonObject();
        String type = getStringProperty(block, "type");
        updateSnapshotChecksum(checksum, "blockType:" + type);
        if ("text".equals(type)) {
            updateSnapshotChecksum(checksum, "text:" + getStringProperty(block, "text"));
            return;
        }
        if ("image".equals(type)) {
            updateSnapshotChecksum(checksum, "imageSrc:" + getStringProperty(block, "src"));
            updateSnapshotChecksum(checksum, "imageMediaType:" + getStringProperty(block, "mediaType"));
            updateSnapshotChecksum(checksum, "imageAlt:" + getStringProperty(block, "alt"));
            return;
        }
        if ("image_missing".equals(type)) {
            updateSnapshotChecksum(checksum, "missingFileName:" + getStringProperty(block, "fileName"));
            updateSnapshotChecksum(checksum, "missingMediaType:" + getStringProperty(block, "mediaType"));
            updateSnapshotChecksum(checksum, "missingPath:" + getStringProperty(block, "originalPath"));
            updateSnapshotChecksum(checksum, "missingReason:" + getStringProperty(block, "reason"));
            return;
        }
        updateSnapshotChecksum(checksum, block.toString());
    }

    /**
     * 以 UTF-8 字节流方式累加快照校验和。
     * 这里统一把 `null` 转成显式字面量，避免不同空值分支生成不一致签名。
     *
     * @param checksum 当前累计校验和
     * @param value 待累加字符串
     */
    private static void updateSnapshotChecksum(CRC32 checksum, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        checksum.update(bytes, 0, bytes.length);
        checksum.update('\n');
    }

    private static int getRawContentBlockCount(JsonObject message) {
        if (message == null || !message.has("raw") || !message.get("raw").isJsonObject()) {
            return 0;
        }

        JsonObject raw = message.getAsJsonObject("raw");
        if (raw.has("content") && raw.get("content").isJsonArray()) {
            return raw.getAsJsonArray("content").size();
        }
        if (raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject rawMessage = raw.getAsJsonObject("message");
            if (rawMessage.has("content") && rawMessage.get("content").isJsonArray()) {
                return rawMessage.getAsJsonArray("content").size();
            }
        }
        return 0;
    }

    /**
     * 计算用户消息的语义丰富度。
     * 对 Codex 双记录场景，显式声明过 `local_images` 的 event_msg 应优先于只剩占位文案的副本。
     *
     * @param message 候选用户消息
     * @return 语义丰富度分数，越高越应该被保留
     */
    private static int getUserMessageSemanticScore(JsonObject message) {
        if (message == null || !message.has("raw") || !message.get("raw").isJsonObject()) {
            return 0;
        }
        JsonObject raw = message.getAsJsonObject("raw");
        int score = getRawContentBlockCount(message);
        if (raw.has("__hasDeclaredLocalImages") && !raw.get("__hasDeclaredLocalImages").isJsonNull()
                && raw.get("__hasDeclaredLocalImages").getAsBoolean()) {
            score += 1000;
        }
        if (raw.has("__declaredLocalImageCount") && !raw.get("__declaredLocalImageCount").isJsonNull()) {
            score += raw.get("__declaredLocalImageCount").getAsInt() * 100;
        }
        if (raw.has("__missingLocalImageCount") && !raw.get("__missingLocalImageCount").isJsonNull()) {
            score += raw.get("__missingLocalImageCount").getAsInt() * 10;
        }
        return score;
    }

    /**
     * 将 Codex 历史消息恢复到后端 SessionState，保证历史加载后继续发送时，
     * 后端内存态与前端显示态使用同一份消息基线。
     */
    public static void restoreCodexMessagesToSessionState(SessionState state, JsonArray messages) {
        state.clearMessages();
        List<JsonObject> frontendMessages = convertCodexMessagesToFrontendBatch(messages);
        for (JsonObject frontendMsg : frontendMessages) {
            ClaudeSession.Message restoredMessage = toSessionMessage(frontendMsg);
            if (restoredMessage != null) {
                state.addMessage(restoredMessage);
            }
        }
    }

    /**
     * 将前端统一消息结构恢复为会话内存消息结构。
     */
    private static ClaudeSession.Message toSessionMessage(JsonObject frontendMsg) {
        if (frontendMsg == null || !frontendMsg.has("type")) {
            return null;
        }

        String type = frontendMsg.get("type").getAsString();
        ClaudeSession.Message.Type messageType;
        switch (type) {
            case "user":
                messageType = ClaudeSession.Message.Type.USER;
                break;
            case "assistant":
                messageType = ClaudeSession.Message.Type.ASSISTANT;
                break;
            case "system":
                messageType = ClaudeSession.Message.Type.SYSTEM;
                break;
            case "error":
                messageType = ClaudeSession.Message.Type.ERROR;
                break;
            default:
                return null;
        }

        String content = frontendMsg.has("content") ? frontendMsg.get("content").getAsString() : "";
        JsonObject raw = frontendMsg.has("raw") && frontendMsg.get("raw").isJsonObject()
            ? frontendMsg.getAsJsonObject("raw")
            : null;
        return raw != null
            ? new ClaudeSession.Message(messageType, content, raw.deepCopy())
            : new ClaudeSession.Message(messageType, content);
    }

    /**
     * 将单条 Codex 历史消息转换为前端消息。
     * Handles both event_msg (user messages) and response_item (assistant/tool messages).
     */
    public static JsonObject convertCodexMessageToFrontend(JsonObject msg) {
        if (!msg.has("type")) {
            return null;
        }

        String type = msg.get("type").getAsString();
        JsonObject payload = msg.has("payload") && msg.get("payload").isJsonObject()
                ? msg.getAsJsonObject("payload") : null;
        if (payload == null) {
            return null;
        }

        String timestamp = msg.has("timestamp") ? msg.get("timestamp").getAsString() : null;

        // Handle event_msg containing user_message
        if ("event_msg".equals(type)) {
            return convertEventMsgToFrontend(payload, timestamp);
        }

        // Handle response_item (assistant messages, function calls, etc.)
        if ("response_item".equals(type)) {
            if (!payload.has("type")) {
                return null;
            }
            String payloadType = payload.get("type").getAsString();

            if ("message".equals(payloadType)) {
                return CodexMessageConverter.convertCodexMessageToFrontend(payload, timestamp);
            }
            if ("function_call".equals(payloadType)) {
                return CodexMessageConverter.convertFunctionCallToToolUse(payload, timestamp);
            }
            if ("function_call_output".equals(payloadType)) {
                return CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, timestamp);
            }
            if ("custom_tool_call".equals(payloadType)) {
                return CodexMessageConverter.convertCustomToolCallToToolUse(payload, timestamp);
            }
        }

        return null;
    }

    /**
     * Convert event_msg with user_message payload to frontend format.
     */
    private static JsonObject convertEventMsgToFrontend(JsonObject payload, String timestamp) {
        if (!payload.has("type") || !"user_message".equals(payload.get("type").getAsString())) {
            return null;
        }
        boolean hasLocalImages = hasLocalImages(payload);
        if (!payload.has("message") || payload.get("message").isJsonNull()) {
            if (!hasLocalImages) {
                return null;
            }
        }

        String content = "";
        if (payload.has("message") && !payload.get("message").isJsonNull()) {
            content = CodexMessageConverter.stripSystemTags(payload.get("message").getAsString());
        }
        if ((content == null || content.isBlank()) && !hasLocalImages) {
            return null;
        }
        if (content == null) {
            content = "";
        }

        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "user");
        frontendMsg.addProperty("content", content);

        // Build raw structure compatible with MessageParser
        JsonObject rawObj = new JsonObject();
        JsonArray contentBlocks = buildUserMessageContentBlocks(payload, content);
        rawObj.add("content", contentBlocks);
        rawObj.addProperty("role", "user");
        rawObj.addProperty("__hasDeclaredLocalImages", hasLocalImages);
        rawObj.addProperty("__declaredLocalImageCount", getDeclaredLocalImageCount(payload));
        rawObj.addProperty("__missingLocalImageCount", countMissingLocalImages(contentBlocks));
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }

        return frontendMsg;
    }

    private static JsonArray buildUserMessageContentBlocks(JsonObject payload, String content) {
        JsonArray contentBlocks = new JsonArray();
        appendLocalImageBlocks(payload, contentBlocks);

        if (content != null && !content.isBlank()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", content);
            contentBlocks.add(textBlock);
        }
        return contentBlocks;
    }

    private static boolean hasLocalImages(JsonObject payload) {
        return payload.has("local_images")
            && payload.get("local_images").isJsonArray()
            && payload.getAsJsonArray("local_images").size() > 0;
    }

    private static int getDeclaredLocalImageCount(JsonObject payload) {
        return hasLocalImages(payload) ? payload.getAsJsonArray("local_images").size() : 0;
    }

    private static void appendLocalImageBlocks(JsonObject payload, JsonArray contentBlocks) {
        if (!payload.has("local_images") || !payload.get("local_images").isJsonArray()) {
            return;
        }

        JsonArray localImages = payload.getAsJsonArray("local_images");
        for (JsonElement imageElement : localImages) {
            if (!imageElement.isJsonPrimitive()) {
                continue;
            }
            String imagePath = imageElement.getAsString();
            JsonObject imageBlock = createLocalImageBlock(imagePath);
            if (imageBlock != null) {
                contentBlocks.add(imageBlock);
            }
        }
    }

    private static JsonObject createLocalImageBlock(String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) {
            return null;
        }

        try {
            Path path = Path.of(imagePath);
            if (!Files.isRegularFile(path)) {
                LOG.debug("[HistoryMessageInjector] Skip missing local image: " + imagePath);
                return createMissingLocalImageBlock(path, "cache_missing");
            }

            String mediaType = Files.probeContentType(path);
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = guessImageMediaType(path);
            }
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = "image/png";
            }

            String base64Data = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            JsonObject imageBlock = new JsonObject();
            imageBlock.addProperty("type", "image");
            imageBlock.addProperty("src", "data:" + mediaType + ";base64," + base64Data);
            imageBlock.addProperty("mediaType", mediaType);
            imageBlock.addProperty("alt", path.getFileName() != null ? path.getFileName().toString() : "image");
            return imageBlock;
        } catch (Exception e) {
            LOG.warn("[HistoryMessageInjector] Failed to restore local image from Codex history: " + imagePath, e);
            return createMissingLocalImageBlock(Path.of(imagePath), "cache_unreadable");
        }
    }

    /**
     * 构造图片缓存失效占位块。
     * 即使真实图片字节已经不可恢复，也要保留图片的文件名、原路径和失效原因，供界面与复制链路兜底。
     *
     * @param path 原始图片路径
     * @param reason 失效原因
     * @return 结构化的图片失效占位块
     */
    private static JsonObject createMissingLocalImageBlock(Path path, String reason) {
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image_missing");
        imageBlock.addProperty("fileName", path.getFileName() != null ? path.getFileName().toString() : "image");
        imageBlock.addProperty("mediaType", guessImageMediaType(path));
        imageBlock.addProperty("originalPath", path.toAbsolutePath().normalize().toString());
        imageBlock.addProperty("reason", reason);
        return imageBlock;
    }

    private static int countMissingLocalImages(JsonArray contentBlocks) {
        int missingCount = 0;
        for (JsonElement blockElement : contentBlocks) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            JsonObject block = blockElement.getAsJsonObject();
            if (block.has("type") && "image_missing".equals(block.get("type").getAsString())) {
                missingCount++;
            }
        }
        return missingCount;
    }

    private static String guessImageMediaType(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase() : "";
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png";
    }

    /**
     * 批量注入前端消息，复用 updateMessages 链路，避免长历史逐条追加导致最新消息显示滞后。
     */
    private void applyCodexSessionBinding(String threadIdToUse) {
        if (threadIdToUse == null || threadIdToUse.trim().isEmpty() || context.getSession() == null) {
            return;
        }
        try {
            CodexSessionBinding binding = context.getSettingsService().getCodexSessionBinding(threadIdToUse);
            if (binding == null || !binding.isMeaningful()) {
                LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.applyCodexSessionBinding skip threadId="
                        + threadIdToUse + ", reason=missing_binding");
                return;
            }
            context.getSession().setProvider("codex");
            if (binding.getModel() != null && !binding.getModel().trim().isEmpty()) {
                context.getSession().setModel(binding.getModel());
            }
            context.getSession().getState().setCodexSessionBinding(binding);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.applyCodexSessionBinding restored threadId="
                    + threadIdToUse + ", binding=" + describeBinding(binding));
            LOG.info("[HistoryHandler] Restored Codex session binding for threadId=" + threadIdToUse
                    + ", providerId=" + binding.getProviderId()
                    + ", model=" + binding.getModel());
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to restore Codex session binding: " + e.getMessage(), e);
        }
    }

    /**
     * 统一格式化历史恢复链路中的 Codex binding 诊断字段。
     * 这里只输出非敏感元数据，便于和 SessionLifecycleManager/SessionSendService 的 trace 日志串联比对。
     *
     * @param binding 当前历史会话恢复出的 Codex binding
     * @return 稳定的诊断文本；为空时返回 "(null)"
     */
    private String describeBinding(CodexSessionBinding binding) {
        if (binding == null) {
            return "(null)";
        }
        return "{providerId=" + binding.getProviderId()
                + ", model=" + binding.getModel()
                + ", requestMode=" + binding.getRequestMode()
                + ", baseUrlSource=" + binding.getBaseUrlSource()
                + ", effectiveConfigSource=" + binding.getEffectiveConfigSource()
                + "}";
    }

    private void injectBatchToFrontend(List<JsonObject> frontendMessages) {
        String messagesJson = new Gson().toJson(frontendMessages);
        String escapedMessagesJson = JsUtils.escapeJs(messagesJson);

        ApplicationManager.getApplication().invokeLater(() -> {
            String jsCode = "if (window.clearMessages) { window.clearMessages(); } " +
                                    "if (window.updateMessages) { window.updateMessages('" + escapedMessagesJson + "'); } " +
                                    "if (window.historyLoadComplete) { window.historyLoadComplete(); }";
            context.executeJavaScriptOnEDT(jsCode);
        });
    }
}
