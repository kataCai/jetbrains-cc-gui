package com.github.claudecodegui.notifications;

import com.github.claudecodegui.taskstate.TaskState;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * 验证系统通知提醒器的降级、复用与点击回调行为。
 */
public class SystemReminderNotifierTest {

    @Test
    public void shouldConstructSafelyOnNonWindowsPlatforms() {
        if (com.github.claudecodegui.util.PlatformUtils.isWindows()) {
            return;
        }

        SystemReminderNotifier notifier = new SystemReminderNotifier();

        assertNotNull(notifier);
    }

    @Test
    public void shouldSkipWhenEnvironmentDoesNotSupportSystemTray() {
        RecordingSystemTrayFacade trayFacade = new RecordingSystemTrayFacade();
        trayFacade.headless = true;
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        SystemReminderNotifier notifier = new SystemReminderNotifier(
            trayFacade,
            activator,
            navigator,
            () -> createTestImage()
        );

        notifier.showTaskReminder(createProject(false), TaskState.COMPLETED, "Task completed");

        assertEquals(0, trayFacade.createCalls);
        assertEquals(0, trayFacade.displayedMessages.size());
        assertNull(activator.lastActivatedProject.get());
    }

    @Test
    public void shouldReuseTrayIconAcrossMultipleReminders() {
        RecordingSystemTrayFacade trayFacade = new RecordingSystemTrayFacade();
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        SystemReminderNotifier notifier = new SystemReminderNotifier(
            trayFacade,
            activator,
            navigator,
            () -> createTestImage()
        );
        Project project = createProject(false);

        notifier.showTaskReminder(project, TaskState.COMPLETED, "First");
        notifier.showTaskReminder(project, TaskState.FINAL_ERROR, "Second");

        assertEquals(1, trayFacade.createCalls);
        assertEquals(2, trayFacade.displayedMessages.size());
        assertEquals("First", trayFacade.displayedMessages.get(0).message);
        assertEquals("Second", trayFacade.displayedMessages.get(1).message);
    }

    @Test
    public void shouldUsePayloadNotificationTitleWhenDisplayingSystemNotification() {
        RecordingSystemTrayFacade trayFacade = new RecordingSystemTrayFacade();
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        SystemReminderNotifier notifier = new SystemReminderNotifier(
            trayFacade,
            activator,
            navigator,
            SystemReminderNotifierTest::createTestImage
        );

        notifier.showTaskReminder(
            createProject(false),
            new TaskReminderNotificationPayload(
                TaskState.COMPLETED,
                "session-title",
                "req-title",
                "Stable Session Title",
                "Current task summary",
                "Current task summary"
            )
        );

        assertEquals(1, trayFacade.displayedMessages.size());
        assertEquals("Stable Session Title", trayFacade.displayedMessages.get(0).title);
        assertEquals("Current task summary", trayFacade.displayedMessages.get(0).message);
    }

    @Test
    public void shouldFallbackToCcGuiWhenPayloadNotificationTitleIsBlank() {
        RecordingSystemTrayFacade trayFacade = new RecordingSystemTrayFacade();
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        SystemReminderNotifier notifier = new SystemReminderNotifier(
            trayFacade,
            activator,
            navigator,
            SystemReminderNotifierTest::createTestImage
        );

        notifier.showTaskReminder(
            createProject(false),
            new TaskReminderNotificationPayload(
                TaskState.COMPLETED,
                "session-title",
                "req-title",
                "   ",
                "Current task summary",
                "Current task summary"
            )
        );

        assertEquals(1, trayFacade.displayedMessages.size());
        assertEquals("CC GUI", trayFacade.displayedMessages.get(0).title);
        assertEquals("Current task summary", trayFacade.displayedMessages.get(0).message);
    }

    @Test
    public void shouldHandleTrayInitializationFailureGracefully() {
        RecordingSystemTrayFacade trayFacade = new RecordingSystemTrayFacade();
        trayFacade.failOnCreate = true;
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        SystemReminderNotifier notifier = new SystemReminderNotifier(
            trayFacade,
            activator,
            navigator,
            SystemReminderNotifierTest::createTestImage
        );

        notifier.showTaskReminder(createProject(false), TaskState.WAITING_CONFIRM, "Need approval");

        assertEquals(1, trayFacade.createCalls);
        assertEquals(0, trayFacade.displayedMessages.size());
        assertNull(activator.lastActivatedProject.get());
    }

    @Test
    public void shouldNavigateToLatestTaskTargetWhenNotificationIsClicked() {
        RecordingSystemTrayFacade trayFacade = new RecordingSystemTrayFacade();
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        SystemReminderNotifier notifier = new SystemReminderNotifier(
            trayFacade,
            activator,
            navigator,
            SystemReminderNotifierTest::createTestImage
        );
        Project firstProject = createProject(false);
        Project secondProject = createProject(false);

        notifier.showTaskReminder(
            firstProject,
            new TaskReminderNotificationPayload(TaskState.COMPLETED, "session-1", "req-1", "First", "First")
        );
        notifier.showTaskReminder(
            secondProject,
            new TaskReminderNotificationPayload(TaskState.FINAL_ERROR, "session-2", "req-2", "Second", "Second")
        );
        trayFacade.clickLastTrayIcon();

        assertSame(secondProject, navigator.lastTarget.get().getProject());
        assertEquals("session-2", navigator.lastTarget.get().getSessionId());
        assertEquals("req-2", navigator.lastTarget.get().getRequestId());
        assertNull(activator.lastActivatedProject.get());
    }

    @Test
    public void shouldSkipDisposedProject() {
        RecordingSystemTrayFacade trayFacade = new RecordingSystemTrayFacade();
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        RecordingTaskNavigator navigator = new RecordingTaskNavigator();
        SystemReminderNotifier notifier = new SystemReminderNotifier(
            trayFacade,
            activator,
            navigator,
            SystemReminderNotifierTest::createTestImage
        );

        notifier.showTaskReminder(createProject(true), TaskState.COMPLETED, "Task completed");

        assertEquals(0, trayFacade.createCalls);
        assertEquals(0, trayFacade.displayedMessages.size());
    }

    private static Image createTestImage() {
        return new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    }

    private static Project createProject(boolean disposed) {
        return (Project) java.lang.reflect.Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isDisposed" -> disposed;
                case "getName" -> "system-reminder-test";
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

    /**
     * 记录系统托盘交互，避免测试依赖真实桌面环境。
     */
    private static class RecordingSystemTrayFacade extends SystemTrayFacade {
        private boolean headless;
        private boolean failOnCreate;
        private int createCalls;
        private final List<DisplayedMessage> displayedMessages = new ArrayList<>();
        private Runnable clickHandler;

        @Override
        public boolean isHeadless() {
            return headless;
        }

        @Override
        public boolean isSupported() {
            return !headless;
        }

        @Override
        public TrayIconHandle createTrayIcon(Image image, String toolTip, Runnable onClick) throws AWTException {
            createCalls++;
            if (failOnCreate) {
                throw new AWTException("boom");
            }
            this.clickHandler = onClick;
            return (title, message, messageType) -> displayedMessages.add(
                new DisplayedMessage(title, message, messageType)
            );
        }

        private void clickLastTrayIcon() {
            if (clickHandler != null) {
                clickHandler.run();
            }
        }
    }

    /**
     * 记录通知点击后的工具窗口激活目标。
     */
    private static class RecordingToolWindowActivator extends CcgToolWindowActivator {
        private final AtomicReference<Project> lastActivatedProject = new AtomicReference<>();

        @Override
        public void activate(Project project) {
            lastActivatedProject.set(project);
        }
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

    private record DisplayedMessage(
        String title,
        String message,
        SystemTrayFacade.TrayMessageType messageType
    ) {
    }
}
