package com.github.claudecodegui.notifications;

import com.github.claudecodegui.taskstate.TaskState;

/**
 * 统一的任务提醒展示载体。
 * 用于在 system / balloon / popup 等渠道之间复用同一份标题、摘要与定位上下文。
 */
public final class TaskReminderNotificationPayload {

    private final TaskState state;
    private final String sessionId;
    private final String requestId;
    private final String notificationTitle;
    private final String taskSummary;
    private final String message;

    /**
     * 创建任务提醒负载。
     * 兼容旧调用方只传正文的场景，此时系统通知主标题由下游通知器使用默认值兜底。
     *
     * @param state 当前任务状态
     * @param sessionId 会话 ID
     * @param requestId 请求 ID
     * @param taskSummary 当前任务摘要
     * @param message 当前提醒正文
     */
    public TaskReminderNotificationPayload(
        TaskState state,
        String sessionId,
        String requestId,
        String taskSummary,
        String message
    ) {
        this(state, sessionId, requestId, null, taskSummary, message);
    }

    /**
     * 创建任务提醒负载。
     * 显式区分系统通知主标题与任务正文，避免两者语义混用。
     *
     * @param state 当前任务状态
     * @param sessionId 会话 ID
     * @param requestId 请求 ID
     * @param notificationTitle 系统通知主标题
     * @param taskSummary 当前任务摘要
     * @param message 当前提醒正文
     */
    public TaskReminderNotificationPayload(
        TaskState state,
        String sessionId,
        String requestId,
        String notificationTitle,
        String taskSummary,
        String message
    ) {
        this.state = state;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.notificationTitle = notificationTitle;
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

    /**
     * 获取系统通知主标题。
     *
     * @return 系统通知主标题；为空时由通知器使用默认标题兜底
     */
    public String getNotificationTitle() {
        return notificationTitle;
    }

    public String getTaskSummary() {
        return taskSummary;
    }

    public String getMessage() {
        return message;
    }
}
