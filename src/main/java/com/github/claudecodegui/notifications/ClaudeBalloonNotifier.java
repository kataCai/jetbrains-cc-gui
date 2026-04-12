package com.github.claudecodegui.notifications;

import com.github.claudecodegui.taskstate.TaskState;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;

/**
 * 轻量级 IDE 气泡提醒。
 * 这里只负责把聚合后的提醒结果映射成 IntelliJ Notification，
 * 不负责决定“该不该提醒”或“提醒一次还是多次”。
 */
public class ClaudeBalloonNotifier {

    private static final String NOTIFICATION_GROUP_ID = "CC GUI Notifications";

    public void showTaskReminder(Project project, TaskState state, String message) {
        if (project == null || project.isDisposed() || state == null) {
            return;
        }

        // 状态到 NotificationType 的映射保持尽量保守：
        // 真的失败用 ERROR，明确成功用 INFORMATION，其余中间态用 WARNING 让用户感知“仍需关注”。
        NotificationType type = toNotificationType(state);
        String displayMessage = "Claude: " + message;

        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(displayMessage, type)
            .notify(project);
    }

    private NotificationType toNotificationType(TaskState state) {
        return switch (state) {
            case FINAL_ERROR -> NotificationType.ERROR;
            case COMPLETED, RECOVERED -> NotificationType.INFORMATION;
            default -> NotificationType.WARNING;
        };
    }
}
