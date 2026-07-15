package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.common.SessionLiteReader;
import com.github.claudecodegui.util.UserVisibleTextGateway;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codex 历史 lite 读取器。
 * 该类只扫描会话文件的头尾和文件属性，快速提取 sessionId、cwd、创建时间、消息数量和首条用户摘要，
 * 用于历史列表首屏加载等对性能敏感的场景。
 * 边界约束是这里只做轻量字符串解析，不构建完整消息树，因此标题摘要必须复用统一净化规则，
 * 避免 lite 列表和完整历史解析在用户可见文本上产生漂移。
 */
public class CodexSessionLiteReader {

    private static final Logger LOG = Logger.getInstance(CodexSessionLiteReader.class);

    private static final Pattern THREAD_ID_PATTERN = Pattern.compile(
            "^thread_[a-zA-Z0-9]{10,}$"
    );

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE
    );

    private final SessionLiteReader liteReader;

    public CodexSessionLiteReader() {
        this.liteReader = new SessionLiteReader();
    }

    /**
     * Codex session info extracted via lite-read.
     */
    public static class CodexLiteSessionInfo {
        public final String sessionId;
        public final String summary;
        public final long lastModified;
        public final long fileSize;
        public final int messageCount;
        public final long createdAt;
        public final String cwd;

        public CodexLiteSessionInfo(
                String sessionId,
                String summary,
                long lastModified,
                long fileSize,
                int messageCount,
                long createdAt,
                String cwd
        ) {
            this.sessionId = sessionId;
            this.summary = summary;
            this.lastModified = lastModified;
            this.fileSize = fileSize;
            this.messageCount = messageCount;
            this.createdAt = createdAt;
            this.cwd = cwd;
        }
    }

    /**
     * Reads a Codex session file and extracts metadata using lite-read.
     *
     * @param sessionPath the path to the session JSONL file
     * @return CodexLiteSessionInfo or null if should be filtered
     */
    public CodexLiteSessionInfo readSessionLite(Path sessionPath) {
        SessionLiteReader.LiteSessionFile lite = this.liteReader.readSessionLite(sessionPath);
        if (lite == null) {
            return null;
        }

        String fileName = sessionPath.getFileName().toString();
        String sessionId = this.extractSessionId(fileName);
        if (sessionId == null) {
            return null;
        }

        return this.parseSessionInfoFromLite(sessionId, lite);
    }

    /**
     * Parses SessionInfo fields from a lite session read (head/tail/stat).
     *
     * @param sessionId the default session ID from filename
     * @param lite      the lite session file data
     * @return CodexLiteSessionInfo or null
     */
    public CodexLiteSessionInfo parseSessionInfoFromLite(
            String sessionId,
            SessionLiteReader.LiteSessionFile lite
    ) {
        if (lite == null || sessionId == null) {
            return null;
        }

        // Extract session_meta.id if available (preferred over filename)
        String metaId = this.extractSessionMetaId(lite.head);
        if (metaId != null) {
            sessionId = metaId;
        }

        // Extract cwd from session_meta
        String cwd = this.extractSessionMetaField(lite.head, "cwd");

        // Extract timestamp from session_meta for createdAt
        String timestamp = this.extractSessionMetaField(lite.head, "timestamp");
        long createdAt = 0;
        if (timestamp != null) {
            try {
                createdAt = this.parseTimestamp(timestamp);
            } catch (Exception e) {
                // Ignore
            }
        }

        // Extract title from first user_message
        String summary = this.extractFirstUserMessageTitle(lite.head);

        // Skip sessions with no title
        if (summary == null || summary.isEmpty()) {
            return null;
        }

        // Count response_item messages (approximate from head)
        int messageCount = this.countResponseItems(lite.head);

        // Use mtime as lastTimestamp (more reliable than last message timestamp)
        long lastModified = lite.mtime;
        if (createdAt == 0) {
            createdAt = lastModified;
        }

        return new CodexLiteSessionInfo(
                sessionId,
                summary,
                lastModified,
                lite.size,
                messageCount,
                createdAt,
                cwd
        );
    }

    /**
     * Extracts session_meta.id from head chunk.
     */
    private String extractSessionMetaId(String head) {
        // Look for {"type":"session_meta", "payload":{"id":"..."}}
        String[] patterns = {"\"type\":\"session_meta\"", "\"type\": \"session_meta\""};
        for (String pattern : patterns) {
            int idx = head.indexOf(pattern);
            if (idx < 0) {
                continue;
            }

            // Find the payload.id within this line
            int lineEnd = head.indexOf('\n', idx);
            String line = lineEnd >= 0 ? head.substring(idx, lineEnd) : head.substring(idx);

            String id = this.liteReader.extractJsonStringField(line, "id");
            if (id != null && (THREAD_ID_PATTERN.matcher(id).matches() || UUID_PATTERN.matcher(id).matches())) {
                return id;
            }
        }
        return null;
    }

    /**
     * Extracts a field from session_meta payload.
     */
    private String extractSessionMetaField(String head, String field) {
        String[] patterns = {"\"type\":\"session_meta\"", "\"type\": \"session_meta\""};
        for (String pattern : patterns) {
            int idx = head.indexOf(pattern);
            if (idx < 0) {
                continue;
            }

            int lineEnd = head.indexOf('\n', idx);
            String line = lineEnd >= 0 ? head.substring(idx, lineEnd) : head.substring(idx);

            return this.liteReader.extractJsonStringField(line, field);
        }
        return null;
    }

    /**
     * 从 lite 头部片段中提取首条用户消息摘要。
     * 该摘要会在历史列表中直接展示，因此必须先去掉 permissions、skills、AGENTS 和 continuation 残留，
     * 再压平成单行文本并截断，保证与完整解析链路的用户可见语义一致。
     *
     * @param head lite 读取到的文件头部文本
     * @return 首条用户摘要；没有可展示文本时返回 null
     */
    private String extractFirstUserMessageTitle(String head) {
        int start = 0;
        while (start < head.length()) {
            int newlineIdx = head.indexOf('\n', start);
            String line = newlineIdx >= 0 ? head.substring(start, newlineIdx) : head.substring(start);
            start = newlineIdx >= 0 ? newlineIdx + 1 : head.length();

            if (!line.contains("\"type\":\"event_msg\"") && !line.contains("\"type\": \"event_msg\"")) {
                continue;
            }

            // Check if payload contains user_message
            if (!line.contains("\"user_message\"")) {
                continue;
            }

            String message = this.liteReader.extractLastJsonStringField(line, "message");
            if (message != null && !message.isEmpty()) {
                // 中文注释：lite 历史摘要需要和完整历史标题共用同一套净化口径，避免列表与详情显示不一致。
                message = UserVisibleTextGateway.toSingleLineVisibleSummary(message);
                if (message.isEmpty()) {
                    continue;
                }

                // Skip auto-generated patterns
                if (message.startsWith("<") && message.length() > 1) {
                    char nextChar = message.charAt(1);
                    if (Character.isLowerCase(nextChar)) {
                        continue;
                    }
                }

                // Truncate to 45 chars
                if (message.length() > 45) {
                    message = message.substring(0, 45).trim() + "\u2026";
                }
                return message;
            }
        }
        return null;
    }

    /**
     * Counts response_item messages in head chunk.
     */
    private int countResponseItems(String head) {
        int count = 0;
        int start = 0;
        while (start < head.length()) {
            int newlineIdx = head.indexOf('\n', start);
            String line = newlineIdx >= 0 ? head.substring(start, newlineIdx) : head.substring(start);
            start = newlineIdx >= 0 ? newlineIdx + 1 : head.length();

            if (line.contains("\"type\":\"response_item\"") || line.contains("\"type\": \"response_item\"")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Extracts session ID from file name.
     */
    private String extractSessionId(String fileName) {
        if (!fileName.endsWith(".jsonl")) {
            return null;
        }
        String sessionId = fileName.substring(0, fileName.length() - 6);

        // Validate: must be UUID or thread_xxx format
        Matcher uuidMatcher = UUID_PATTERN.matcher(sessionId);
        Matcher threadMatcher = THREAD_ID_PATTERN.matcher(sessionId);

        if (uuidMatcher.matches() || threadMatcher.matches()) {
            return sessionId;
        }
        return null;
    }

    /**
     * Parse an ISO timestamp string to epoch milliseconds.
     */
    private long parseTimestamp(String timestamp) {
        try {
            java.time.Instant instant = java.time.Instant.parse(timestamp);
            return instant.toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Returns the underlying SessionLiteReader for reuse.
     */
    public SessionLiteReader getLiteReader() {
        return this.liteReader;
    }
}
