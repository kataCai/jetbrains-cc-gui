package com.github.claudecodegui.notifications;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * 统一组装任务提醒标题与正文。
 * 任务摘要保持“当前轮次优先”，系统通知主标题则保持会话标题语义，避免两者相互覆盖。
 */
public class TaskReminderPayloadFactory {

    private static final int MAX_SUMMARY_LENGTH = 80;
    private static final String DEFAULT_NOTIFICATION_TITLE = "CC GUI";
    private static final String TOOL_RESULT_PLACEHOLDER = "[tool_result]";

    @FunctionalInterface
    public interface TabDisplayNameResolver {
        String resolve(Project project, String sessionId);
    }

    private final TabDisplayNameResolver tabDisplayNameResolver;

    public TaskReminderPayloadFactory() {
        this((project, sessionId) -> null);
    }

    public TaskReminderPayloadFactory(TabDisplayNameResolver tabDisplayNameResolver) {
        this.tabDisplayNameResolver = tabDisplayNameResolver != null
            ? tabDisplayNameResolver
            : (project, sessionId) -> null;
    }

    public TaskReminderNotificationPayload create(
        HandlerContext context,
        TaskStateSnapshot snapshot,
        String fallbackMessage
    ) {
        return create(context, snapshot, fallbackMessage, null);
    }

    /**
     * 创建提醒负载。
     * 标题与正文分别解析，正文继续优先采用当前轮次任务摘要，标题则优先采用稳定会话标题。
     *
     * @param context 处理上下文
     * @param snapshot 当前任务快照
     * @param fallbackMessage 默认提醒正文
     * @param preferredTaskSummary 调用方显式传入的任务摘要
     * @return 已拆分标题与正文的提醒负载
     */
    public TaskReminderNotificationPayload create(
        HandlerContext context,
        TaskStateSnapshot snapshot,
        String fallbackMessage,
        String preferredTaskSummary
    ) {
        String sessionId = firstNonBlank(
            snapshot != null ? snapshot.getSessionId() : null,
            context != null && context.getSession() != null ? context.getSession().getSessionId() : null
        );
        String requestId = snapshot != null ? snapshot.getRequestId() : null;
        String taskSummary = resolveTaskSummary(
            context != null ? context.getSession() : null,
            context != null ? context.getProject() : null,
            sessionId,
            snapshot != null ? snapshot.getState() : null,
            fallbackMessage,
            preferredTaskSummary
        );
        String message = hasText(taskSummary) ? taskSummary : sanitizeHeadlineAndTruncate(fallbackMessage);
        String notificationTitle = resolveNotificationTitle(
            context != null ? context.getSession() : null,
            context != null ? context.getProject() : null,
            sessionId
        );

        return new TaskReminderNotificationPayload(
            snapshot != null ? snapshot.getState() : null,
            sessionId,
            requestId,
            notificationTitle,
            message,
            message
        );
    }

    public String resolveTaskSummaryCandidate(
        HandlerContext context,
        TaskStateSnapshot snapshot,
        String fallbackMessage
    ) {
        return resolveTaskSummaryCandidate(context, snapshot, fallbackMessage, null);
    }

    public String resolveTaskSummaryCandidate(
        HandlerContext context,
        TaskStateSnapshot snapshot,
        String fallbackMessage,
        String preferredTaskSummary
    ) {
        String sessionId = firstNonBlank(
            snapshot != null ? snapshot.getSessionId() : null,
            context != null && context.getSession() != null ? context.getSession().getSessionId() : null
        );
        return resolveTaskSummary(
            context != null ? context.getSession() : null,
            context != null ? context.getProject() : null,
            sessionId,
            snapshot != null ? snapshot.getState() : null,
            fallbackMessage,
            preferredTaskSummary
        );
    }

    /**
     * 解析提醒正文摘要。
     * 当前轮次任务优先，只有缺失可读任务摘要时才退回会话级信息。
     *
     * @param session 当前会话
     * @param project 当前项目
     * @param sessionId 会话 ID
     * @param fallbackMessage 默认提醒正文
     * @param preferredTaskSummary 调用方显式传入的任务摘要
     * @return 最适合作为提醒正文的摘要
     */
    private String resolveTaskSummary(
        ClaudeSession session,
        Project project,
        String sessionId,
        TaskState state,
        String fallbackMessage,
        String preferredTaskSummary
    ) {
        String explicitSummary = sanitizeHeadlineAndTruncate(preferredTaskSummary);
        String completionTaskNotificationSummary = sanitizeHeadlineAndTruncate(
            findLatestCompletionTaskNotificationSummary(session, state)
        );
        if (hasText(completionTaskNotificationSummary)) {
            return completionTaskNotificationSummary;
        }

        if (hasText(explicitSummary)) {
            return explicitSummary;
        }

        String latestUserMessage = sanitizeHeadlineAndTruncate(findLatestUserMessage(session));
        if (hasText(latestUserMessage)) {
            return latestUserMessage;
        }

        String sessionSummary = sanitizeHeadlineAndTruncate(session != null ? session.getSummary() : null);
        if (hasText(sessionSummary)) {
            return sessionSummary;
        }

        String tabDisplayName = sanitizeHeadlineAndTruncate(tabDisplayNameResolver.resolve(project, sessionId));
        if (hasText(tabDisplayName)) {
            return tabDisplayName;
        }

        return sanitizeHeadlineAndTruncate(fallbackMessage);
    }

    /**
     * 解析系统通知主标题。
     * 主标题优先反映会话标题，不直接复用任务摘要。
     *
     * @param session 当前会话
     * @param project 当前项目
     * @param sessionId 会话 ID
     * @return 系统通知主标题；无可用标题时回退到固定产品名
     */
    private String resolveNotificationTitle(ClaudeSession session, Project project, String sessionId) {
        String tabDisplayName = sanitizeHeadlineAndTruncate(tabDisplayNameResolver.resolve(project, sessionId));
        if (hasText(tabDisplayName)) {
            return tabDisplayName;
        }

        String sessionSummary = sanitizeHeadlineAndTruncate(session != null ? session.getSummary() : null);
        if (hasText(sessionSummary)) {
            return sessionSummary;
        }

        return DEFAULT_NOTIFICATION_TITLE;
    }

    private String findLatestUserMessage(ClaudeSession session) {
        if (session == null) {
            return null;
        }
        List<ClaudeSession.Message> messages = session.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ClaudeSession.Message message = messages.get(i);
            if (isVisibleUserMessage(message)) {
                return message.content;
            }
        }
        return null;
    }

    /**
     * 仅在 completed 场景扫描聊天区最近的 completion task-notification summary，
     * 让本地提醒优先复用聊天区最终展示的完成总结，避免回退到更早的 user message 或 session summary。
     *
     * @param session 当前会话
     * @param state 当前提醒状态
     * @return 最近一条 completion task-notification 的 summary；不存在时返回 null
     */
    private String findLatestCompletionTaskNotificationSummary(ClaudeSession session, TaskState state) {
        if (session == null || state != TaskState.COMPLETED) {
            return null;
        }
        List<ClaudeSession.Message> messages = session.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            String summary = extractCompletionTaskNotificationSummary(messages.get(i));
            if (hasText(summary)) {
                return summary;
            }
        }
        return null;
    }

    /**
     * 从单条消息中提取 completion task-notification summary。
     * 只有 raw 显式标记为 task-notification 且 status=completed 时才视为可复用的完成总结，
     * 防止普通 XML 文本或其他状态消息误进入 completed 提醒正文。
     *
     * @param message 待解析的会话消息
     * @return completion task-notification summary；不匹配时返回 null
     */
    private String extractCompletionTaskNotificationSummary(ClaudeSession.Message message) {
        if (message == null || message.raw == null || !isTaskNotificationRaw(message.raw)) {
            return null;
        }
        String rawText = extractLastTextBlock(message.raw);
        if (!hasText(rawText)) {
            return null;
        }
        String status = extractXmlTagValue(rawText, "status");
        if (!"completed".equals(status)) {
            return null;
        }
        return extractXmlTagValue(rawText, "summary");
    }

    /**
     * 任务提醒只展示用户可理解的摘要；tool_result 占位消息只用于内部 block 对齐，不应直接暴露给通知正文。
     */
    private static boolean isVisibleUserMessage(ClaudeSession.Message message) {
        if (message == null || message.type != ClaudeSession.Message.Type.USER) {
            return false;
        }
        String normalized = normalizeSummary(message.content);
        if (!hasText(normalized)) {
            return false;
        }
        return !isToolResultOnlyUserMessage(message, normalized);
    }

    private static boolean isToolResultOnlyUserMessage(ClaudeSession.Message message, String normalizedContent) {
        if (TOOL_RESULT_PLACEHOLDER.equals(normalizedContent)) {
            return true;
        }
        JsonObject raw = message.raw;
        if (raw == null) {
            return false;
        }
        JsonElement content = raw.has("content") ? raw.get("content") : null;
        if (content == null && raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject rawMessage = raw.getAsJsonObject("message");
            content = rawMessage.has("content") ? rawMessage.get("content") : null;
        }
        if (content == null || !content.isJsonArray()) {
            return false;
        }

        JsonArray contentArray = content.getAsJsonArray();
        boolean hasToolResult = false;
        boolean hasVisibleContent = false;
        for (JsonElement element : contentArray) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive()) {
                if (hasText(normalizeSummary(element.getAsString()))) {
                    hasVisibleContent = true;
                }
                continue;
            }
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject block = element.getAsJsonObject();
            String blockType = block.has("type") && !block.get("type").isJsonNull()
                ? block.get("type").getAsString()
                : null;
            if ("tool_result".equals(blockType)) {
                hasToolResult = true;
                continue;
            }
            if ("text".equals(blockType) && block.has("text") && !block.get("text").isJsonNull()) {
                if (hasText(normalizeSummary(block.get("text").getAsString()))) {
                    hasVisibleContent = true;
                }
                continue;
            }
            hasVisibleContent = true;
        }
        return hasToolResult && !hasVisibleContent;
    }

    /**
     * 判断 raw 是否来自 task-notification。
     * 当前链路依赖 origin.kind 的显式标记，避免把普通消息正文误判成 completion summary。
     *
     * @param raw 消息 raw JSON
     * @return true 表示该 raw 为 task-notification
     */
    private static boolean isTaskNotificationRaw(JsonObject raw) {
        if (raw == null || !raw.has("origin") || !raw.get("origin").isJsonObject()) {
            return false;
        }
        JsonObject origin = raw.getAsJsonObject("origin");
        return origin.has("kind")
            && !origin.get("kind").isJsonNull()
            && "task-notification".equals(origin.get("kind").getAsString());
    }

    /**
     * 提取 raw.message.content 中最后一个 text block 文本。
     * completion task-notification 当前通过 text block 承载 XML 内容，这里保持最小解析实现即可。
     *
     * @param raw 消息 raw JSON
     * @return 最后一个 text block 的文本；不存在时返回 null
     */
    private static String extractLastTextBlock(JsonObject raw) {
        if (raw == null) {
            return null;
        }
        JsonElement content = raw.has("content") ? raw.get("content") : null;
        if ((content == null || !content.isJsonArray()) && raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject rawMessage = raw.getAsJsonObject("message");
            content = rawMessage.has("content") ? rawMessage.get("content") : null;
        }
        if (content == null || !content.isJsonArray()) {
            return null;
        }

        JsonArray contentArray = content.getAsJsonArray();
        String lastText = null;
        for (JsonElement element : contentArray) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            if (block.has("type")
                && !block.get("type").isJsonNull()
                && "text".equals(block.get("type").getAsString())
                && block.has("text")
                && !block.get("text").isJsonNull()) {
                lastText = block.get("text").getAsString();
            }
        }
        return lastText;
    }

    /**
     * 从 task-notification XML 文本中提取指定标签值。
     * 这里只做最小字符串查找，足以支撑当前 completed 提醒摘要的 status/summary 读取需求。
     *
     * @param rawText 包含 task-notification XML 的文本
     * @param tagName 目标标签名
     * @return 标签内容；不存在或为空时返回 null
     */
    private static String extractXmlTagValue(String rawText, String tagName) {
        if (!hasText(rawText) || !hasText(tagName)) {
            return null;
        }
        String startTag = "<" + tagName + ">";
        String endTag = "</" + tagName + ">";
        int start = rawText.indexOf(startTag);
        int end = rawText.indexOf(endTag);
        if (start < 0 || end <= start) {
            return null;
        }
        String value = rawText.substring(start + startTag.length(), end).trim();
        return hasText(value) ? value : null;
    }

    private static String sanitizeHeadlineAndTruncate(String value) {
        String normalized = extractHeadline(value);
        if (!hasText(normalized)) {
            return null;
        }
        if (normalized.length() <= MAX_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }

    /**
     * 统一提取首条可读标题行，避免多行问题把整段提示词带入通知。
     *
     * @param value 原始文本
     * @return 首条可读标题行
     */
    private static String extractHeadline(String value) {
        if (!hasText(value)) {
            return null;
        }
        String[] lines = value.split("\\R");
        for (String line : lines) {
            String normalizedLine = normalizeSummary(line);
            if (hasText(normalizedLine)) {
                return normalizedLine;
            }
        }
        return normalizeSummary(value);
    }

    private static String normalizeSummary(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return TOOL_RESULT_PLACEHOLDER.equals(normalized) ? null : normalized;
    }

    private static String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            if (hasText(candidate)) {
                return candidate.trim();
            }
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
