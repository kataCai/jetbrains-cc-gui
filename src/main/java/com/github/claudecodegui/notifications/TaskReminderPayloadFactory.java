package com.github.claudecodegui.notifications;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.intellij.openapi.project.Project;

import java.util.List;

/**
 * 统一组装任务提醒摘要与展示文案。
 * 当前优先复用 session summary，再按最近用户消息、tab 名称和状态兜底回退。
 */
public class TaskReminderPayloadFactory {

    private static final int MAX_SUMMARY_LENGTH = 80;

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
        String message = hasText(summary) ? summary : sanitizeAndTruncate(fallbackMessage);

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
        String sessionId = firstNonBlank(
            snapshot != null ? snapshot.getSessionId() : null,
            context != null && context.getSession() != null ? context.getSession().getSessionId() : null
        );
        return resolveSummary(
            context != null ? context.getSession() : null,
            context != null ? context.getProject() : null,
            sessionId,
            fallbackMessage,
            null
        );
    }

    private String resolveSummary(
        ClaudeSession session,
        Project project,
        String sessionId,
        String fallbackMessage,
        String preferredTaskSummary
    ) {
        String explicitSummary = sanitizeAndTruncate(preferredTaskSummary);
        if (hasText(explicitSummary)) {
            return explicitSummary;
        }

        String latestUserMessage = sanitizeAndTruncate(findLatestUserMessage(session));
        if (hasText(latestUserMessage)) {
            return latestUserMessage;
        }

        // 任务提醒优先体现当前轮次处理内容；会话 summary 只作为兜底标题使用。
        String sessionSummary = sanitizeAndTruncate(session != null ? session.getSummary() : null);
        if (hasText(sessionSummary)) {
            return sessionSummary;
        }

        String tabDisplayName = sanitizeAndTruncate(tabDisplayNameResolver.resolve(project, sessionId));
        if (hasText(tabDisplayName)) {
            return tabDisplayName;
        }

        return sanitizeAndTruncate(fallbackMessage);
    }

    private String findLatestUserMessage(ClaudeSession session) {
        if (session == null) {
            return null;
        }
        List<ClaudeSession.Message> messages = session.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ClaudeSession.Message message = messages.get(i);
            if (message != null && message.type == ClaudeSession.Message.Type.USER && hasText(message.content)) {
                return message.content;
            }
        }
        return null;
    }

    private static String sanitizeAndTruncate(String value) {
        if (!hasText(value)) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_SUMMARY_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
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
