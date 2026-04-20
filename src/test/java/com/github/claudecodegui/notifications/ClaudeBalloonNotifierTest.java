package com.github.claudecodegui.notifications;

import com.github.claudecodegui.taskstate.TaskState;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class ClaudeBalloonNotifierTest {

    @Test
    public void shouldAddOpenTaskActionWhenReminderHasNavigationContext() {
        RecordingNotificationGateway gateway = new RecordingNotificationGateway();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        ClaudeBalloonNotifier notifier = new ClaudeBalloonNotifier(gateway, navigator);
        Project project = createProject(false);

        notifier.showTaskReminder(
            project,
            new TaskReminderNotificationPayload(
                TaskState.COMPLETED,
                "session-balloon",
                "req-balloon",
                "Fix popup navigation",
                "Fix popup navigation"
            )
        );

        assertEquals(1, gateway.notifications.size());
        RecordingNotification notification = gateway.notifications.get(0);
        assertEquals("Claude: Fix popup navigation", notification.content);
        assertEquals(NotificationType.INFORMATION, notification.type);
        assertEquals(1, notification.actions.size());

        notification.actions.get(0).run();

        assertNotNull(navigator.lastTarget.get());
        assertSame(project, navigator.lastTarget.get().getProject());
        assertEquals("session-balloon", navigator.lastTarget.get().getSessionId());
        assertEquals("req-balloon", navigator.lastTarget.get().getRequestId());
    }

    @Test
    public void shouldSkipOpenTaskActionWhenReminderHasNoNavigationContext() {
        RecordingNotificationGateway gateway = new RecordingNotificationGateway();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        ClaudeBalloonNotifier notifier = new ClaudeBalloonNotifier(gateway, navigator);
        Project project = createProject(false);

        notifier.showTaskReminder(
            project,
            new TaskReminderNotificationPayload(
                TaskState.FINAL_ERROR,
                null,
                null,
                "Task ended with an error",
                "Task ended with an error"
            )
        );

        assertEquals(1, gateway.notifications.size());
        assertEquals(0, gateway.notifications.get(0).actions.size());
        assertNull(navigator.lastTarget.get());
    }

    private static Project createProject(boolean disposed) {
        return (Project) java.lang.reflect.Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isDisposed" -> disposed;
                case "getName" -> "balloon-notifier-test";
                default -> method.getReturnType().isPrimitive()
                    ? defaultPrimitiveValue(method.getReturnType())
                    : null;
            }
        );
    }

    private static Object defaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        return 0;
    }

    private static class RecordingTaskNavigator extends CcgTaskNavigator {
        private final AtomicReference<TaskReminderNavigationTarget> lastTarget = new AtomicReference<>();

        RecordingTaskNavigator() {
            super(Runnable::run, (project, sessionId) -> false, (project, sessionId) -> false, new CcgToolWindowActivator());
        }

        @Override
        public void navigate(TaskReminderNavigationTarget target) {
            lastTarget.set(target);
        }
    }

    private static class RecordingNotificationGateway implements ClaudeBalloonNotifier.NotificationGateway {
        private final List<RecordingNotification> notifications = new ArrayList<>();

        @Override
        public ClaudeBalloonNotifier.NotificationHandle create(String content, NotificationType type) {
            RecordingNotification notification = new RecordingNotification(content, type);
            notifications.add(notification);
            return notification;
        }
    }

    private static class RecordingNotification implements ClaudeBalloonNotifier.NotificationHandle {
        private final String content;
        private final NotificationType type;
        private final List<Runnable> actions = new ArrayList<>();

        private RecordingNotification(String content, NotificationType type) {
            this.content = content;
            this.type = type;
        }

        @Override
        public void addAction(String label, Runnable action) {
            actions.add(action);
        }

        @Override
        public void notify(Project project) {
        }
    }
}
