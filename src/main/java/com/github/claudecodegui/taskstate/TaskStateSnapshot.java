package com.github.claudecodegui.taskstate;

/**
 * 不可变任务状态快照。
 * 对外只暴露当前稳定视图，避免调用方持有可变对象后自行篡改状态。
 */
public class TaskStateSnapshot {

    private final TaskState state;
    private final String sessionId;
    private final String requestId;
    private final TaskStateEvent latestEvent;

    public TaskStateSnapshot(TaskState state, String sessionId, String requestId, TaskStateEvent latestEvent) {
        this.state = state;
        this.sessionId = sessionId;
        this.requestId = requestId;
        this.latestEvent = latestEvent;
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

    public TaskStateEvent getLatestEvent() {
        return latestEvent;
    }
}
