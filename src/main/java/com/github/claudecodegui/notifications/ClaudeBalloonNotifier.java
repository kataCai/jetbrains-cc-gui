package com.github.claudecodegui.notifications;

import com.github.claudecodegui.taskstate.TaskState;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

/**
 * 轻量 IDE 气泡提醒。
 * 统一复用任务摘要，并在可定位时追加“打开任务”动作。
 */
public class ClaudeBalloonNotifier {

    private static final Logger LOG = Logger.getInstance(ClaudeBalloonNotifier.class);
    private static final String NOTIFICATION_GROUP_ID = "CC GUI Notifications";
    private static final String OPEN_TASK_ACTION_LABEL = "Open task";

    public interface NotificationHandle {
        void addAction(String label, Runnable action);

        void notify(Project project);
    }

    public interface NotificationGateway {
        NotificationHandle create(String content, NotificationType type);
    }

    private final NotificationGateway notificationGateway;
    private final CcgTaskNavigator taskNavigator;

    public ClaudeBalloonNotifier() {
        this(new IdeNotificationGateway(), new CcgTaskNavigator());
    }

    public ClaudeBalloonNotifier(NotificationGateway notificationGateway, CcgTaskNavigator taskNavigator) {
        this.notificationGateway = notificationGateway != null ? notificationGateway : new IdeNotificationGateway();
        this.taskNavigator = taskNavigator != null ? taskNavigator : new CcgTaskNavigator();
    }

    public void showTaskReminder(Project project, TaskState state, String message) {
        showTaskReminder(
            project,
            new TaskReminderNotificationPayload(state, null, null, message, message)
        );
    }

    public void showTaskReminder(Project project, TaskReminderNotificationPayload payload) {
        if (project == null || project.isDisposed() || payload == null || payload.getState() == null) {
            return;
        }

        NotificationType type = toNotificationType(payload.getState());
        String displayMessage = "Claude: " + payload.getMessage();
        LOG.info("[ClaudeBalloonNotifier] state=" + payload.getState().getValue()
            + ", type=" + type
            + ", group=" + NOTIFICATION_GROUP_ID
            + ", message=" + payload.getMessage());

        NotificationHandle notification = notificationGateway.create(displayMessage, type);
        if (hasText(payload.getSessionId()) || hasText(payload.getRequestId())) {
            TaskReminderNavigationTarget target = new TaskReminderNavigationTarget(
                project,
                payload.getSessionId(),
                payload.getRequestId()
            );
            notification.addAction(OPEN_TASK_ACTION_LABEL, () -> taskNavigator.navigate(target));
        }
        notification.notify(project);
    }

    private NotificationType toNotificationType(TaskState state) {
        return switch (state) {
            case FINAL_ERROR -> NotificationType.ERROR;
            case COMPLETED, RECOVERED -> NotificationType.INFORMATION;
            default -> NotificationType.WARNING;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class IdeNotificationGateway implements NotificationGateway {
        @Override
        public NotificationHandle create(String content, NotificationType type) {
            Notification notification = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                .createNotification(content, type);
            return new IdeNotificationHandle(notification);
        }
    }

    private static final class IdeNotificationHandle implements NotificationHandle {
        private final Notification notification;

        private IdeNotificationHandle(Notification notification) {
            this.notification = notification;
        }

        @Override
        public void addAction(String label, Runnable action) {
            notification.addAction(NotificationAction.createSimple(label, action));
        }

        @Override
        public void notify(Project project) {
            notification.notify(project);
        }
    }
}
