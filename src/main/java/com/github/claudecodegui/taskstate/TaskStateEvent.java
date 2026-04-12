package com.github.claudecodegui.taskstate;

/**
 * 轻量级状态事件。
 * TaskStateService 会不断产生 event，并把“最新事件 + 当前状态”封装成 snapshot，
 * 供提醒分发层做去重和文案选择。
 */
public class TaskStateEvent {

    private final TaskState state;
    private final String sessionId;
    private final String requestId;
    private final String reason;
    private final long timestamp;

    public TaskStateEvent(TaskState state, String sessionId, String requestId, String reason, long timestamp) {
        this.state = state;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.reason = reason;
        this.timestamp = timestamp;
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

    public String getReason() {
        return reason;
    }

    public Long getTimestamp() {
        return timestamp;
    }
}
