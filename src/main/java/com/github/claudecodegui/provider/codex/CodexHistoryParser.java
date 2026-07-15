package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.util.TagExtractor;
import com.github.claudecodegui.util.TextSanitizer;
import com.github.claudecodegui.util.UserVisibleTextGateway;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Codex 历史会话解析器。
 * 该类负责把 JSONL 会话文件转换为历史面板和聚合服务可复用的 DTO，
 * 同时在标题提取阶段清理 permissions、skills、AGENTS 等内部注入前缀，
 * 避免历史列表标题泄露运行时内部上下文。
 * 适用场景包括完整历史扫描、会话有效性判断和首条用户消息标题提取；
 * 它只读取现有文件内容，不修改底层历史文件。
 */
class CodexHistoryParser {

    private static final Logger LOG = Logger.getInstance(CodexHistoryParser.class);

    private final Gson gson;

    CodexHistoryParser() {
        this(new Gson());
    }

    CodexHistoryParser(Gson gson) {
        this.gson = gson;
    }

    CodexHistoryReader.SessionInfo parseSessionFile(Path sessionFile) throws IOException {
        CodexHistoryReader.SessionInfo session = new CodexHistoryReader.SessionInfo();

        // Default: derive sessionId from filename; prefer session_meta.id when available
        // to match the thread ID the Codex SDK sends to the frontend via setSessionId.
        String fileName = sessionFile.getFileName().toString();
        session.sessionId = fileName.substring(0, fileName.lastIndexOf(".jsonl"));

        List<CodexHistoryReader.CodexMessage> messages = new ArrayList<>();
        int messageCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }

                try {
                    CodexHistoryReader.CodexMessage msg = this.gson.fromJson(line, CodexHistoryReader.CodexMessage.class);
                    if (msg == null) {
                        continue;
                    }

                    messages.add(msg);

                    if ("session_meta".equals(msg.type) && msg.payload != null) {
                        // Use session_meta.id as the canonical session ID.
                        // This matches the thread_id the Codex SDK returns via [THREAD_ID],
                        // ensuring custom titles saved under this ID are found when loading history.
                        if (msg.payload.has("id") && !msg.payload.get("id").isJsonNull()) {
                            String metaId = msg.payload.get("id").getAsString();
                            if (metaId != null && !metaId.isEmpty()) {
                                session.sessionId = metaId;
                            }
                        }

                        if (msg.payload.has("cwd")) {
                            session.cwd = TextSanitizer.sanitizeInvalidSurrogates(msg.payload.get("cwd").getAsString());
                        }

                        if (msg.payload.has("timestamp")) {
                            String ts = msg.payload.get("timestamp").getAsString();
                            session.firstTimestamp = parseTimestamp(ts);
                            session.lastTimestamp = session.firstTimestamp;
                        }
                    }

                    if ("response_item".equals(msg.type)) {
                        messageCount++;
                    }

                    if (msg.timestamp != null) {
                        long ts = parseTimestamp(msg.timestamp);
                        if (ts > session.lastTimestamp) {
                            session.lastTimestamp = ts;
                        }
                    }
                } catch (Exception e) {
                    LOG.debug("[CodexHistoryReader] Failed to parse line: " + e.getMessage());
                }
            }
        }

        session.messageCount = messageCount;
        session.title = generateTitle(messages);

        return session;
    }

    String generateTitle(List<CodexHistoryReader.CodexMessage> messages) {
        for (CodexHistoryReader.CodexMessage msg : messages) {
            if (!"event_msg".equals(msg.type) || msg.payload == null) {
                continue;
            }
            String title = extractUserMessageTitle(msg.payload);
            if (title != null) {
                return title;
            }
        }
        return null;
    }

    boolean isValidSession(CodexHistoryReader.SessionInfo session) {
        if (session.title == null || session.title.isEmpty()) {
            return false;
        }

        return session.messageCount >= 1;
    }

    /**
     * 从单条 `event_msg/user_message` 载荷中提取历史标题。
     * 这里会先走统一的用户可见文本净化入口，再补充命令消息裁剪与单行截断，
     * 保证历史列表标题与前端真实可见文本保持一致，而不会带出 permissions、skills 或 AGENTS 残留。
     *
     * @param payload Codex 历史中的消息载荷
     * @return 适合历史列表展示的单行标题；非 user_message 或净化后为空时返回 null
     */
    String extractUserMessageTitle(JsonObject payload) {
        if (payload == null) {
            return null;
        }
        if (!payload.has("type") || !"user_message".equals(payload.get("type").getAsString())) {
            return null;
        }
        if (!payload.has("message")) {
            return null;
        }
        String text = payload.get("message").getAsString();
        if (text == null || text.isEmpty()) {
            return null;
        }
        // 中文注释：历史标题必须与前端用户可见文本语义一致，避免继续保留内部注入前缀。
        text = UserVisibleTextGateway.toVisibleUserTextOrEmpty(text);
        if (text.isEmpty()) {
            return null;
        }
        text = TagExtractor.extractCommandMessageContent(text);
        return TextSanitizer.sanitizeAndTruncateSingleLine(text, 45);
    }

    /**
     * Remove known system/instruction XML tag blocks from text.
     * Codex prepends &lt;agents-instructions&gt; blocks to user messages containing
     * AGENTS.md content; these should be stripped before title extraction.
     */
    static String stripSystemTags(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String[] systemTags = {"agents-instructions", "system-reminder", "system-prompt"};
        String result = text;
        for (String tag : systemTags) {
            result = removeTagBlock(result, tag);
        }
        return result.trim();
    }

    /**
     * Remove a complete XML tag block (opening tag through closing tag) from text.
     */
    private static String removeTagBlock(String text, String tagName) {
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = text.indexOf(openTag);
        if (start == -1) {
            return text;
        }
        int end = text.indexOf(closeTag, start);
        if (end == -1) {
            return text;
        }
        return text.substring(0, start) + text.substring(end + closeTag.length());
    }

    long parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Check if a file is non-empty. Shared across index and aggregation services.
     */
    static boolean isNonEmptyFile(Path path) {
        try {
            return Files.size(path) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}
