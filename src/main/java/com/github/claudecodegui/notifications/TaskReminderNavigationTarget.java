package com.github.claudecodegui.notifications;

import com.intellij.openapi.project.Project;

/**
 * 任务提醒点击后的统一定位目标。
 * 当前最小集合以 project + sessionId + requestId 为主，便于各通知通道复用。
 */
public final class TaskReminderNavigationTarget {

    private final Project project;
    private final String sessionId;
    private final String requestId;

    public TaskReminderNavigationTarget(Project project, String sessionId, String requestId) {
        this.project = project;
        this.sessionId = sessionId;
        this.requestId = requestId;
    }

    public Project getProject() {
        return project;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRequestId() {
        return requestId;
    }
}
