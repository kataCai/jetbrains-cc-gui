package com.github.claudecodegui.notifications;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * 统一组装任务提醒摘要与展示文案。
 * 任务提醒优先展示当前轮次的可读任务文本，并过滤仅用于内部链路的 tool_result 占位消息。
 */
public class TaskReminderPayloadFactory {

    private static final int MAX_SUMMARY_LENGTH = 80;
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
        String summary = resolveSummary(
            context != null ? context.getSession() : null,
            context != null ? context.getProject() : null,
            sessionId,
            fallbackMessage,
            preferredTaskSummary
        );
        String message = hasText(summary) ? summary : sanitizeHeadlineAndTruncate(fallbackMessage);

        return new TaskReminderNotificationPayload(
            snapshot != null ? snapshot.getState() : null,
            sessionId,
            requestId,
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
        return resolveSummary(
            context != null ? context.getSession() : null,
            context != null ? context.getProject() : null,
            sessionId,
            fallbackMessage,
            preferredTaskSummary
        );
    }

    private String resolveSummary(
        ClaudeSession session,
        Project project,
        String sessionId,
        String fallbackMessage,
        String preferredTaskSummary
    ) {
        String explicitSummary = sanitizeHeadlineAndTruncate(preferredTaskSummary);
        if (hasText(explicitSummary)) {
            return explicitSummary;
        }

        String latestUserMessage = sanitizeHeadlineAndTruncate(findLatestUserMessage(session));
        if (hasText(latestUserMessage)) {
            return latestUserMessage;
        }

        // 任务提醒优先体现当前轮次处理内容；会话 summary 只作为兜底标题使用。
        // ?????????????????????????????????
        // ???????????????????????????
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
     * 任务提醒只展示用户可理解的摘要；tool_result 占位消息只用于内部 block 对齐，不能直接暴露给通知正文。
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

    private static String sanitizeAndTruncate(String value) {
        String normalized = normalizeSummary(value);
        if (!hasText(normalized)) {
            return null;
        }
        if (normalized.length() <= MAX_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
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
     * ???????????????
     * ??????????????????????????????????
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
