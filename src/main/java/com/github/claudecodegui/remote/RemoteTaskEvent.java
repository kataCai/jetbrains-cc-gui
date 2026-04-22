package com.github.claudecodegui.remote;

/**
 * 远程协作通道要发送的统一任务事件。
 */
public final class RemoteTaskEvent {

    private final String sessionId;
    private final String projectPath;
    private final String requestId;
    private final String taskState;
    private final String title;
    private final String summary;

    public RemoteTaskEvent(
        String sessionId,
        String projectPath,
        String requestId,
        String taskState,
        String title,
        String summary
    ) {
        this.sessionId = sessionId;
        this.projectPath = projectPath;
        this.requestId = requestId;
        this.taskState = taskState;
        this.title = title;
        this.summary = summary;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTaskState() {
        return taskState;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }
}
