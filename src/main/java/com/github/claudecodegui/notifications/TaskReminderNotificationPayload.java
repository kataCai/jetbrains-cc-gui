package com.github.claudecodegui.notifications;

import com.github.claudecodegui.taskstate.TaskState;

/**
 * 统一的任务提醒展示载体。
 * 用于在 system / balloon / popup 等通道之间复用同一份摘要与定位上下文。
 */
public final class TaskReminderNotificationPayload {

    private final TaskState state;
    private final String sessionId;
    private final String requestId;
    private final String taskSummary;
    private final String message;

    public TaskReminderNotificationPayload(
        TaskState state,
        String sessionId,
        String requestId,
        String taskSummary,
        String message
    ) {
        this.state = state;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.taskSummary = taskSummary;
        this.message = message;
    }

    public TaskState getState() {
        return state;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTaskSummary() {
        return taskSummary;
    }

    public String getMessage() {
        return message;
    }
}
