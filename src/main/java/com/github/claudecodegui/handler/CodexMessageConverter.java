package com.github.claudecodegui.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.github.claudecodegui.util.UserVisibleTextGateway;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codex message format conversion utilities.
 * <p>
 * Contains static methods for converting Codex message formats to Claude-compatible
 * frontend formats. Extracted from HistoryHandler to improve separation of concerns.
 * <p>
 * Note: {@link #SESSION_FILE_MAP} maintains minimal state to track file-writing sessions
 * across related exec_command / write_stdin pairs. Call {@link #clearSessionState()} when
 * a new conversation starts to avoid stale entries.
 */
public class CodexMessageConverter {

    /** Maximum number of tracked file-writing sessions to prevent unbounded growth. */
    private static final int MAX_SESSION_ENTRIES = 256;

    // Tracks file-writing sessions so later write_stdin events can display the target file.
    // Uses a bounded LRU map to prevent memory leaks over long IDE sessions.
    private static final Map<Integer, String> SESSION_FILE_MAP =
        Collections.synchronizedMap(new LinkedHashMap<Integer, String>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                return size() > MAX_SESSION_ENTRIES;
            }
        });

    // Extracts a destination path from common shell write patterns.
    // Note: The echo/printf alternative uses [^>]* instead of .* to prevent greedy matching across redirections.
    private static final Pattern WRITE_CMD_PATTERN = Pattern.compile(
        "cat\\s*>\\s*([^\\s;|&]+)|tee\\s+(?:-[a-zA-Z]+\\s+)*([^\\s;|&]+)|(?:echo|printf)\\s+[^>]*>\\s*([^\\s;|&]+)"
    );

    private CodexMessageConverter() {
        // Utility class, no instantiation.
    }

    /**
     * Safely extract a string from a JsonElement, handling null, primitives, and structured types.
     * Returns the primitive string value when possible, falls back to {@code toString()} for
     * arrays/objects, and returns the given default for null or missing elements.
     */
    private static String safeGetAsString(JsonElement elem, String defaultValue) {
        if (elem == null || elem.isJsonNull()) {
            return defaultValue;
        }
        if (elem.isJsonPrimitive()) {
            return elem.getAsString();
        }
        return elem.toString();
    }

    /**
     * Clear session tracking state. Should be called when a new conversation starts
     * to avoid stale session-to-file mappings.
     */
    public static void clearSessionState() {
        SESSION_FILE_MAP.clear();
    }

    /**
     * Convert Codex content to Claude-format content blocks.
     * Codex: [{type: "input_text", text: "..."}, {type: "text", text: "..."}]
     * Claude: [{type: "text", text: "..."}]
     */
    public static JsonArray convertToClaudeContentBlocks(JsonElement contentElem) {
        JsonArray claudeBlocks = new JsonArray();

        if (contentElem == null) {
            return claudeBlocks;
        }

        // Handle string type - convert to a single text block
        if (contentElem.isJsonPrimitive()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", contentElem.getAsString());
            claudeBlocks.add(textBlock);
            return claudeBlocks;
        }

        // Handle array type
        if (contentElem.isJsonArray()) {
            JsonArray contentArray = contentElem.getAsJsonArray();

            for (JsonElement item : contentArray) {
                if (item.isJsonObject()) {
                    JsonObject itemObj = item.getAsJsonObject();
                    String type = itemObj.has("type") ? itemObj.get("type").getAsString() : null;

                    if (type != null) {
                        JsonObject claudeBlock = new JsonObject();

                        // Convert Codex "input_text" and "output_text" to Claude "text"
                        if ("input_text".equals(type) || "output_text".equals(type) || "text".equals(type)) {
                            claudeBlock.addProperty("type", "text");
                            if (itemObj.has("text")) {
                                claudeBlock.addProperty("text", itemObj.get("text").getAsString());
                            }
                            claudeBlocks.add(claudeBlock);
                        }
                        // Handle tool use (if present in Codex)
                        else if ("tool_use".equals(type)) {
                            claudeBlock.addProperty("type", "tool_use");
                            if (itemObj.has("id")) {
                                claudeBlock.addProperty("id", itemObj.get("id").getAsString());
                            }
                            if (itemObj.has("name")) {
                                claudeBlock.addProperty("name", itemObj.get("name").getAsString());
                            }
                            if (itemObj.has("input")) {
                                claudeBlock.add("input", itemObj.get("input"));
                            }
                            claudeBlocks.add(claudeBlock);
                        }
                        // Handle tool result
                        else if ("tool_result".equals(type)) {
                            claudeBlock.addProperty("type", "tool_result");
                            if (itemObj.has("tool_use_id")) {
                                claudeBlock.addProperty("tool_use_id", itemObj.get("tool_use_id").getAsString());
                            }
                            if (itemObj.has("content")) {
                                claudeBlock.add("content", itemObj.get("content"));
                            }
                            if (itemObj.has("is_error")) {
                                claudeBlock.addProperty("is_error", itemObj.get("is_error").getAsBoolean());
                            }
                            claudeBlocks.add(claudeBlock);
                        }
                        // Handle thinking block
                        else if ("thinking".equals(type)) {
                            claudeBlock.addProperty("type", "thinking");
                            if (itemObj.has("thinking")) {
                                claudeBlock.addProperty("thinking", itemObj.get("thinking").getAsString());
                            }
                            if (itemObj.has("text")) {
                                claudeBlock.addProperty("text", itemObj.get("text").getAsString());
                            }
                            claudeBlocks.add(claudeBlock);
                        }
                        // Handle image
                        else if ("image".equals(type)) {
                            claudeBlock.addProperty("type", "image");
                            if (itemObj.has("src")) {
                                claudeBlock.addProperty("src", itemObj.get("src").getAsString());
                            }
                            if (itemObj.has("mediaType")) {
                                claudeBlock.addProperty("mediaType", itemObj.get("mediaType").getAsString());
                            }
                            if (itemObj.has("alt")) {
                                claudeBlock.addProperty("alt", itemObj.get("alt").getAsString());
                            }
                            claudeBlocks.add(claudeBlock);
                        }
                        // Other unknown types, try to keep as-is
                        else {
                            claudeBlocks.add(itemObj);
                        }
                    }
                }
            }

            return claudeBlocks;
        }

        // Handle object type - treat as a single block
        if (contentElem.isJsonObject()) {
            claudeBlocks.add(contentElem.getAsJsonObject());
            return claudeBlocks;
        }

        return claudeBlocks;
    }

    /**
     * 按消息角色将 Codex 内容块转换为前端可见的 Claude text block。
     * 这里显式区分 user 与非 user 的可见来源，避免 assistant/system 错误吞入 runtime 注入的 `input_text`。
     *
     * @param contentElem Codex 原始 content 字段
     * @param role 当前消息角色
     * @return 仅包含当前角色允许暴露给前端的可见 block
     */
    public static JsonArray convertToVisibleClaudeContentBlocks(JsonElement contentElem, String role) {
        JsonArray claudeBlocks = new JsonArray();
        if (contentElem == null) {
            return claudeBlocks;
        }

        String normalizedRole = normalizeMessageRole(role);
        if (contentElem.isJsonPrimitive()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", contentElem.getAsString());
            claudeBlocks.add(textBlock);
            return claudeBlocks;
        }

        if (contentElem.isJsonArray()) {
            JsonArray contentArray = contentElem.getAsJsonArray();
            for (JsonElement item : contentArray) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject itemObj = item.getAsJsonObject();
                String type = itemObj.has("type") ? itemObj.get("type").getAsString() : "";
                if (isVisibleTextBlockTypeForRole(type, normalizedRole)) {
                    JsonObject textBlock = new JsonObject();
                    textBlock.addProperty("type", "text");
                    if (itemObj.has("text")) {
                        textBlock.addProperty("text", itemObj.get("text").getAsString());
                    }
                    claudeBlocks.add(textBlock);
                    continue;
                }
                if (isTextLikeBlockType(type)) {
                    continue;
                }
                JsonArray converted = convertToClaudeContentBlocks(itemObj);
                for (JsonElement convertedItem : converted) {
                    claudeBlocks.add(convertedItem);
                }
            }
            return claudeBlocks;
        }

        if (contentElem.isJsonObject()) {
            JsonObject contentObj = contentElem.getAsJsonObject();
            if (contentObj.has("text")) {
                JsonObject textBlock = new JsonObject();
                textBlock.addProperty("type", "text");
                textBlock.addProperty("text", contentObj.get("text").getAsString());
                claudeBlocks.add(textBlock);
            }
            return claudeBlocks;
        }

        return claudeBlocks;
    }

    /**
     * Extract text content from a Codex content field.
     * Codex content can be in string, object, or array format.
     */
    public static String extractContentAsString(JsonElement contentElem) {
        if (contentElem == null) {
            return null;
        }

        // Handle string type
        if (contentElem.isJsonPrimitive()) {
            return contentElem.getAsString();
        }

        // Handle array type
        if (contentElem.isJsonArray()) {
            JsonArray contentArray = contentElem.getAsJsonArray();
            StringBuilder sb = new StringBuilder();

            for (JsonElement item : contentArray) {
                if (item.isJsonObject()) {
                    JsonObject itemObj = item.getAsJsonObject();

                    // Flatten supported text-like blocks into a single preview string for the frontend.
                    if (itemObj.has("type") && "text".equals(itemObj.get("type").getAsString())) {
                        if (itemObj.has("text")) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(itemObj.get("text").getAsString());
                        }
                    }
                    // Extract input_text type (Codex user messages)
                    else if (itemObj.has("type") && "input_text".equals(itemObj.get("type").getAsString())) {
                        if (itemObj.has("text")) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(itemObj.get("text").getAsString());
                        }
                    }
                    // Extract output_text type (Codex AI assistant messages)
                    else if (itemObj.has("type") && "output_text".equals(itemObj.get("type").getAsString())) {
                        if (itemObj.has("text")) {
                            if (sb.length() > 0) {
                                sb.append("\n");
                            }
                            sb.append(itemObj.get("text").getAsString());
                        }
                    }
                }
            }

            return sb.toString();
        }

        // Handle object type
        if (contentElem.isJsonObject()) {
            JsonObject contentObj = contentElem.getAsJsonObject();
            if (contentObj.has("text")) {
                return contentObj.get("text").getAsString();
            }
        }

        return null;
    }

    /**
     * 按角色提取前端真正可见的文本内容。
     * `user` 允许读取 `input_text`，`assistant` 允许读取 `output_text`，其它角色只读取普通 `text`，
     * 从源头阻断非 user 消息误把内部 continuation / permissions / skills 注入文本暴露到前端。
     *
     * @param contentElem Codex 原始 content 字段
     * @param role 当前消息角色
     * @return 该角色允许暴露的可见文本；无可见文本时返回 null
     */
    public static String extractVisibleContentAsString(JsonElement contentElem, String role) {
        if (contentElem == null) {
            return null;
        }

        String normalizedRole = normalizeMessageRole(role);
        if (contentElem.isJsonPrimitive()) {
            return contentElem.getAsString();
        }

        if (contentElem.isJsonArray()) {
            JsonArray contentArray = contentElem.getAsJsonArray();
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : contentArray) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject itemObj = item.getAsJsonObject();
                String type = itemObj.has("type") ? itemObj.get("type").getAsString() : "";
                if (!isVisibleTextBlockTypeForRole(type, normalizedRole) || !itemObj.has("text")) {
                    continue;
                }
                appendVisibleText(sb, itemObj.get("text").getAsString());
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        if (contentElem.isJsonObject()) {
            JsonObject contentObj = contentElem.getAsJsonObject();
            if (contentObj.has("text")) {
                return contentObj.get("text").getAsString();
            }
        }

        return null;
    }

    /**
     * Convert Codex regular message to frontend format.
     */
    public static JsonObject convertCodexMessageToFrontend(JsonObject payload, String timestamp) {
        String role = payload.has("role") ? payload.get("role").getAsString() : "user";
        String contentStr = extractVisibleContentAsString(payload.get("content"), role);
        boolean userMessage = "user".equals(role);
        JsonArray visibleClaudeContentBlocks = convertToVisibleClaudeContentBlocks(payload.get("content"), role);

        if (userMessage) {
            // 中文注释：user 消息要逐个 block 净化，只删除被注入的文本说明，
            // 不能因为净化后没有文本就把图片、附件等真实可见内容一并丢掉。
            visibleClaudeContentBlocks = sanitizeVisibleUserClaudeContentBlocks(visibleClaudeContentBlocks);
            // 中文注释：上面旧注释曾把赋值语句吞进注释，这里显式保留真正生效的净化逻辑。
            visibleClaudeContentBlocks = sanitizeVisibleUserClaudeContentBlocks(visibleClaudeContentBlocks);
            visibleClaudeContentBlocks = sanitizeVisibleUserClaudeContentBlocks(visibleClaudeContentBlocks);
            contentStr = extractTextFromVisibleClaudeBlocks(visibleClaudeContentBlocks);
            if (visibleClaudeContentBlocks.size() == 0) {
                return null;
            }
        } else if (containsHighConfidenceInternalResidue(contentStr)) {
            return null;
        }

        // Filter out system messages
        if (contentStr != null && (isSystemMessage(contentStr) || isBridgeDiagnosticNoise(contentStr))) {
            return null;
        }
        if ((contentStr == null || contentStr.isBlank()) && visibleClaudeContentBlocks.size() == 0) {
            return null;
        }

        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", role);

        if (payload.has("content")) {
            if (contentStr != null && !contentStr.isEmpty()) {
                frontendMsg.addProperty("content", contentStr);
            }

            // 中文注释：user 消息的前台 content 与 raw.content 必须同源于净化后的文本。
            // 否则 MessageParser 后续优先解析 raw 时，会把 AGENTS、skills 或 continuation 注入块重新展示出来。
            JsonArray claudeContentBlocks = userMessage
            // 中文注释：raw.content 直接复用净化后的可见 block，避免再退化成仅文本结构。
                    ? textContentBlocks(contentStr) : visibleClaudeContentBlocks;
            JsonObject rawObj = new JsonObject();
            rawObj.add("content", visibleClaudeContentBlocks);
            rawObj.addProperty("role", role);
            frontendMsg.add("raw", rawObj);
        }

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }

        return frontendMsg;
    }

    /**
     * 判断文本是否为稳定的系统噪声前缀。
     * 这里只保留与业务正文无关、且格式高度固定的桥接输出前缀；
     * `AGENTS.md instructions` / `<INSTRUCTIONS>` / `<environment_context>` 不再在这里直接丢弃，
     * 统一交由高置信内部残留结构判定处理，避免 assistant 正常讲解文档格式时被误删。
     */
    public static boolean isSystemMessage(String contentStr) {
        return contentStr.startsWith("Warning:") ||
               contentStr.startsWith("Tool result:") ||
               contentStr.startsWith("Exit code:");
    }

    /**
     * 判断文本是否为 bridge/SDK 层诊断噪声，而不是模型或用户的真实对话内容。
     * 规则只匹配高置信前缀，例如桥接解析失败和 Windows 进程树清理命令；
     * 不做正文包含式过滤，避免用户正常讨论 `taskkill` 或解析错误时被误删。
     *
     * @param contentStr 待判断的文本内容
     * @return true 表示该文本应只进入诊断日志，不应进入聊天消息
     */
    public static boolean isBridgeDiagnosticNoise(String contentStr) {
        if (contentStr == null) {
            return false;
        }
        String normalized = contentStr.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return normalized.startsWith("Failed to parse item:")
                || normalized.startsWith("[Failed to parse item:");
    }

    /**
     * 判断文本是否命中高置信内部 prompt / skills / continuation 残留。
     * 规则只匹配完整结构锚点组合，不对单个 `SKILL.md`、`## Skills` 或普通文档讨论做弱包含过滤，
     * 避免误伤用户正常讨论技能文档或 assistant 给出的代码示例。
     *
     * @param contentStr 待检查的可见文本
     * @return true 表示该文本高度疑似后台注入残留，不应继续显示给前端
     */
    public static boolean containsHighConfidenceInternalResidue(String contentStr) {
        if (contentStr == null) {
            return false;
        }
        String normalized = contentStr.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isEmpty()) {
            return false;
        }

        boolean hasPermissionsBlock = normalized.contains("<permissions instructions>")
                && normalized.contains("</permissions instructions>")
                && normalized.contains("Filesystem sandboxing defines which files can be read or written.");
        boolean hasSkillsSection = normalized.contains("## Skills")
                && normalized.contains("SKILL.md")
                && normalized.contains("### Skill roots")
                && normalized.contains("### Available skills")
                && normalized.contains("### How to use skills");
        boolean hasContinuationSection = normalized.contains("## Conversation Continuation")
                && normalized.contains("Logical conversation id:")
                && normalized.contains("Previous segment session id:")
                && normalized.contains("Preserve the user's intent and continue from that context unless the latest request overrides it.");
        boolean hasAgentsInstructionSection = isHighConfidenceAgentsResidue(normalized);
        return hasPermissionsBlock || hasSkillsSection || hasContinuationSection || hasAgentsInstructionSection;
    }

    /**
     * 判断文本是否包含 AGENTS 指令头。
     * 这里只匹配真实注入块常见的文档头或外层包装，避免单凭普通正文中提到 “AGENTS.md instructions” 就命中。
     *
     * @param normalized 已归一化换行并 trim 的文本
     * @return true 表示命中 AGENTS 指令头或外层包装
     */
    private static boolean hasAgentsInstructionHeader(String normalized) {
        return normalized.startsWith("# AGENTS.md instructions")
                || normalized.startsWith("<agents-instructions>");
    }

    /**
     * 判断文本是否包含完整的 `<INSTRUCTIONS>` 包裹块。
     *
     * @param normalized 已归一化换行并 trim 的文本
     * @return true 表示同时存在开始和结束标签
     */
    private static boolean hasInstructionsEnvelope(String normalized) {
        return normalized.contains("<INSTRUCTIONS>") && normalized.contains("</INSTRUCTIONS>");
    }

    /**
     * 判断文本是否包含完整的 `<environment_context>` 包裹块。
     *
     * @param normalized 已归一化换行并 trim 的文本
     * @return true 表示同时存在开始和结束标签
     */
    private static boolean hasEnvironmentContextEnvelope(String normalized) {
        return normalized.contains("<environment_context>") && normalized.contains("</environment_context>");
    }

    /**
     * 判断 environment_context 块中是否包含真实运行时环境子字段。
     * 只有命中这些字段，才认为文本高度疑似后台真实注入；单纯展示标签示例不应触发过滤。
     *
     * @param normalized 已归一化换行并 trim 的文本
     * @return true 表示包含至少一个真实运行时环境字段
     */
    private static boolean hasEnvironmentContextRuntimeFields(String normalized) {
        return normalized.contains("<cwd>")
                || normalized.contains("<shell>")
                || normalized.contains("<current_date>")
                || normalized.contains("<timezone>")
                || normalized.contains("<current-date>");
    }

    /**
     * 判断文本是否命中高置信 AGENTS 内部注入残留。
     * 必须同时满足 AGENTS 头、`<INSTRUCTIONS>` 包裹、`<environment_context>` 包裹以及至少一个真实环境字段，
     * 才视为后台真实注入；否则 assistant 正常讲解文档格式时应继续展示。
     *
     * @param normalized 已归一化换行并 trim 的文本
     * @return true 表示文本高度疑似后台 AGENTS 注入残留
     */
    private static boolean isHighConfidenceAgentsResidue(String normalized) {
        return hasAgentsInstructionHeader(normalized)
                && hasInstructionsEnvelope(normalized)
                && hasEnvironmentContextEnvelope(normalized)
                && hasEnvironmentContextRuntimeFields(normalized);
    }

    /**
     * Strip internal instruction blocks that are prepended before sending to Codex.
     * These blocks are useful model context, but should not be rendered as user history.
     */
    public static String stripSystemTags(String text) {
        return UserVisibleTextGateway.toVisibleUserText(text);
    }

    /**
     * 逐个净化 user 可见 block。
     * 文本 block 会收敛为真正允许展示给用户的文本，非文本 block 原样保留，
     * 这样后端历史恢复既能去掉注入说明，又不会误删纯图片、附件等消息。
     *
     * @param visibleBlocks 已按角色过滤后的前端可见 block
     * @return 净化后的 block 列表；若全部 block 都不可见则返回空数组
     */
    private static JsonArray sanitizeVisibleUserClaudeContentBlocks(JsonArray visibleBlocks) {
        JsonArray sanitizedBlocks = new JsonArray();
        if (visibleBlocks == null) {
            return sanitizedBlocks;
        }
        for (JsonElement blockElement : visibleBlocks) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            JsonObject blockObject = blockElement.getAsJsonObject();
            String type = blockObject.has("type") ? safeGetAsString(blockObject.get("type"), "") : "";
            if (!"text".equals(type)) {
                sanitizedBlocks.add(blockObject.deepCopy());
                continue;
            }
            String originalText = blockObject.has("text") ? safeGetAsString(blockObject.get("text"), null) : null;
            String sanitizedText = stripSystemTags(originalText);
            if (sanitizedText == null || sanitizedText.isBlank()) {
                continue;
            }
            JsonObject sanitizedTextBlock = blockObject.deepCopy();
            sanitizedTextBlock.addProperty("text", sanitizedText);
            sanitizedBlocks.add(sanitizedTextBlock);
        }
        return sanitizedBlocks;
    }

    /**
     * 从可见 block 中提取最终展示文本。
     * 若 block 中只剩图片、附件等非文本内容，则返回 null，交由 `raw.content` 承载完整结构。
     *
     * @param visibleBlocks 前端可见 block 列表
     * @return 顶层消息展示文本；不存在文本时返回 null
     */
    private static String extractTextFromVisibleClaudeBlocks(JsonArray visibleBlocks) {
        if (visibleBlocks == null || visibleBlocks.size() == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement blockElement : visibleBlocks) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            JsonObject blockObject = blockElement.getAsJsonObject();
            String type = blockObject.has("type") ? safeGetAsString(blockObject.get("type"), "") : "";
            if (!"text".equals(type) || !blockObject.has("text")) {
                continue;
            }
            appendVisibleText(sb, safeGetAsString(blockObject.get("text"), null));
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static JsonArray textContentBlocks(String text) {
        JsonArray content = new JsonArray();
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);
        content.add(textBlock);
        return content;
    }

    /**
     * 归一化消息角色，避免空值或未知大小写导致可见文本提取规则漂移。
     *
     * @param role 原始角色字符串
     * @return 归一化后的角色；缺失时回退为 `user`
     */
    private static String normalizeMessageRole(String role) {
        return role == null || role.isBlank() ? "user" : role.trim();
    }

    /**
     * 判断某类 text-like block 是否允许对当前角色可见。
     *
     * @param blockType Codex content block type
     * @param normalizedRole 已归一化的消息角色
     * @return true 表示该 block 的文本允许进入当前角色的前端可见内容
     */
    private static boolean isVisibleTextBlockTypeForRole(String blockType, String normalizedRole) {
        if ("user".equals(normalizedRole)) {
            return "input_text".equals(blockType) || "text".equals(blockType);
        }
        if ("assistant".equals(normalizedRole)) {
            return "output_text".equals(blockType) || "text".equals(blockType);
        }
        return "text".equals(blockType);
    }

    /**
     * 判断 block type 是否属于 text / input_text / output_text 这类文本块。
     *
     * @param blockType Codex content block type
     * @return true 表示该 block 为 text-like block
     */
    private static boolean isTextLikeBlockType(String blockType) {
        return "text".equals(blockType) || "input_text".equals(blockType) || "output_text".equals(blockType);
    }

    /**
     * 以统一换行策略拼接多段可见文本。
     *
     * @param sb 目标拼接器
     * @param text 当前待追加文本
     */
    private static void appendVisibleText(StringBuilder sb, String text) {
        if (text == null) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("\n");
        }
        sb.append(text);
    }

    /**
     * Convert Codex function_call to Claude tool_use format.
     */
    public static JsonObject convertFunctionCallToToolUse(JsonObject payload, String timestamp) {
        String toolName = payload.has("name") ? payload.get("name").getAsString() : "unknown";
        JsonElement toolInput = parseToolArguments(payload);

        // Normalize tool identities first so downstream input conversion can target the displayed tool name.
        toolName = convertToolName(toolName, toolInput);

        // Filter out ignored tools (e.g., write_stdin)
        if (toolName == null) {
            return null;
        }

        toolInput = convertToolInput(toolName, toolInput);

        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "assistant");

        // Build tool_use format
        JsonObject toolUse = new JsonObject();
        toolUse.addProperty("type", "tool_use");
        toolUse.addProperty("id", payload.has("call_id") ? payload.get("call_id").getAsString() : "unknown");
        toolUse.addProperty("name", toolName);

        if (toolInput != null) {
            toolUse.add("input", toolInput);
        }

        JsonArray content = new JsonArray();
        content.add(toolUse);

        frontendMsg.addProperty("content", "Tool: " + toolName);

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "assistant");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }

        return frontendMsg;
    }

    /**
     * Parse tool call arguments.
     */
    public static JsonElement parseToolArguments(JsonObject payload) {
        if (!payload.has("arguments")) {
            return null;
        }
        try {
            return JsonParser.parseString(payload.get("arguments").getAsString());
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /**
     * Smart tool name conversion (shell_command -> read/glob, update_plan -> todowrite).
     *
     * @return converted tool name, or null if the tool should be filtered out (e.g. write_stdin).
     */
    public static String convertToolName(String toolName, JsonElement toolInput) {
        if ("shell_command".equals(toolName) && toolInput != null && toolInput.isJsonObject()) {
            JsonObject inputObj = toolInput.getAsJsonObject();
            if (inputObj.has("command")) {
                String command = inputObj.get("command").getAsString().trim();
                // List/find commands -> glob (consistent with ai-bridge smartToolName)
                if (command.matches("^(ls|find|tree)\\b.*")) {
                    return "glob";
                }
                // File viewing commands -> read
                if (command.matches("^(pwd|cat|head|tail|file|stat)\\b.*")) {
                    return "read";
                }
                // Search commands -> glob
                if (command.matches("^(grep|rg|ack|ag)\\b.*")) {
                    return "glob";
                }
            }
        }
        if ("update_plan".equals(toolName) && toolInput != null && toolInput.isJsonObject()) {
            JsonObject inputObj = toolInput.getAsJsonObject();
            if (inputObj.has("plan") && inputObj.get("plan").isJsonArray()) {
                return "todowrite";
            }
        }
        // Ignore write_stdin - it's waiting for previous command result
        if ("write_stdin".equals(toolName)) {
            return null;
        }
        return toolName;
    }

    /**
     * Convert tool input (update_plan -> todowrite format conversion).
     * Also tracks exec_command sessions and enriches write_stdin with file paths.
     */
    public static JsonElement convertToolInput(String toolName, JsonElement toolInput) {
        // Capture the write target when a terminal session starts writing to a file.
        if ("exec_command".equals(toolName) && toolInput != null && toolInput.isJsonObject()) {
            JsonObject inputObj = toolInput.getAsJsonObject();
            if (inputObj.has("cmd") && inputObj.has("session_id")) {
                String cmd = inputObj.get("cmd").getAsString();
                int sessionId = inputObj.get("session_id").getAsInt();

                // The regex covers redirection and tee-based writes used by the coding agents.
                Matcher matcher = WRITE_CMD_PATTERN.matcher(cmd);
                if (matcher.find()) {
                    String filePath = matcher.group(1) != null ? matcher.group(1) :
                                    (matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
                    if (filePath != null) {
                        SESSION_FILE_MAP.put(sessionId, filePath.trim());
                    }
                }
            }
        }

        // Enrich incremental writes with the previously discovered destination path.
        if ("write".equals(toolName) && toolInput != null && toolInput.isJsonObject()) {
            JsonObject inputObj = toolInput.getAsJsonObject();
            if (inputObj.has("session_id")) {
                int sessionId = inputObj.get("session_id").getAsInt();
                String filePath = SESSION_FILE_MAP.get(sessionId);
                if (filePath != null) {
                    JsonObject enriched = new JsonObject();
                    for (String key : inputObj.keySet()) {
                        enriched.add(key, inputObj.get(key));
                    }
                    enriched.addProperty("file_path", filePath);
                    return enriched;
                }
            }
        }

        // Translate plan updates into the todo structure expected by the Claude-style frontend.
        if (!"todowrite".equals(toolName) || toolInput == null || !toolInput.isJsonObject()) {
            return toolInput;
        }

        JsonObject inputObj = toolInput.getAsJsonObject();
        if (!inputObj.has("plan") || !inputObj.get("plan").isJsonArray()) {
            return toolInput;
        }

        JsonArray planArray = inputObj.getAsJsonArray("plan");
        JsonArray todosArray = new JsonArray();

        for (int j = 0; j < planArray.size(); j++) {
            if (planArray.get(j).isJsonObject()) {
                JsonObject planItem = planArray.get(j).getAsJsonObject();
                JsonObject todoItem = new JsonObject();

                if (planItem.has("step")) {
                    todoItem.addProperty("content", planItem.get("step").getAsString());
                    todoItem.addProperty("activeForm", planItem.get("step").getAsString());
                }
                todoItem.addProperty("status", planItem.has("status") ? planItem.get("status").getAsString() : "pending");
                todoItem.addProperty("id", String.valueOf(j));

                todosArray.add(todoItem);
            }
        }

        JsonObject newInput = new JsonObject();
        newInput.add("todos", todosArray);
        return newInput;
    }

    /**
     * Convert Codex function_call_output to Claude tool_result format.
     */
    public static JsonObject convertFunctionCallOutputToToolResult(JsonObject payload, String timestamp) {
        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "user");

        JsonObject toolResult = new JsonObject();
        toolResult.addProperty("type", "tool_result");
        toolResult.addProperty("tool_use_id", payload.has("call_id") ? payload.get("call_id").getAsString() : "unknown");

        String output = safeGetAsString(payload.get("output"), "");
        toolResult.addProperty("content", output);

        JsonArray content = new JsonArray();
        content.add(toolResult);

        frontendMsg.addProperty("content", "[tool_result]");

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "user");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }

        return frontendMsg;
    }

    /**
     * Convert Codex custom_tool_call to Claude tool_use format.
     * Handles apply_patch and other custom tools.
     */
    public static JsonObject convertCustomToolCallToToolUse(JsonObject payload, String timestamp) {
        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "assistant");

        String toolName = payload.has("name") ? payload.get("name").getAsString() : "unknown";

        String toolInput = safeGetAsString(payload.get("input"), "");

        JsonObject toolUse = new JsonObject();
        toolUse.addProperty("type", "tool_use");
        toolUse.addProperty("id", payload.has("call_id") ? payload.get("call_id").getAsString() : "unknown");
        toolUse.addProperty("name", toolName);

        JsonObject input = new JsonObject();
        input.addProperty("patch", toolInput);

        // Surface the first touched file so the frontend can show a concrete target for patch-based edits.
        if ("apply_patch".equals(toolName)
                && (toolInput.contains("*** Add File:") || toolInput.contains("*** Update File:"))) {
            String[] lines = toolInput.split("\n");
            for (String line : lines) {
                if (line.startsWith("*** Add File:") || line.startsWith("*** Update File:")) {
                    String filePath = line.substring(line.indexOf(":") + 1).trim();
                    input.addProperty("file_path", filePath);
                    break;
                }
            }
        }

        toolUse.add("input", input);

        JsonArray content = new JsonArray();
        content.add(toolUse);

        frontendMsg.addProperty("content", "Tool: " + toolName);

        JsonObject rawObj = new JsonObject();
        rawObj.add("content", content);
        rawObj.addProperty("role", "assistant");
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }

        return frontendMsg;
    }
}
