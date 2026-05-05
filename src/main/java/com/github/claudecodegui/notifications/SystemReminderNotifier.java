package com.github.claudecodegui.notifications;

import com.github.claudecodegui.taskstate.TaskState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 负责发送系统级任务提醒，并在点击通知后回到 CCG ToolWindow。
 */
public class SystemReminderNotifier {

    private static final Logger LOG = Logger.getInstance(SystemReminderNotifier.class);
    private static final String TRAY_TOOLTIP = "CC GUI";
    private static final String DEFAULT_NOTIFICATION_TITLE = "CC GUI";

    @FunctionalInterface
    public interface TrayImageLoader {
        Image load() throws Exception;
    }

    private final SystemTrayFacade systemTrayFacade;
    private final CcgToolWindowActivator toolWindowActivator;
    private final CcgTaskNavigator taskNavigator;
    private final TrayImageLoader trayImageLoader;
    private final AtomicReference<Project> latestProjectRef = new AtomicReference<>();
    private final AtomicReference<TaskReminderNavigationTarget> latestNavigationTargetRef = new AtomicReference<>();

    private volatile SystemTrayFacade.TrayIconHandle trayIconHandle;

    public SystemReminderNotifier() {
        this(
            new SystemTrayFacade(),
            new CcgToolWindowActivator(),
            new CcgTaskNavigator(),
            SystemReminderNotifier::loadDefaultTrayImage
        );
    }

    /**
     * 测试专用构造器，允许注入托盘抽象和图标加载逻辑。
     *
     * @param systemTrayFacade 托盘抽象
     * @param toolWindowActivator 工具窗口激活器
     * @param taskNavigator 任务导航器
     * @param trayImageLoader 托盘图标加载器
     */
    public SystemReminderNotifier(
        SystemTrayFacade systemTrayFacade,
        CcgToolWindowActivator toolWindowActivator,
        CcgTaskNavigator taskNavigator,
        TrayImageLoader trayImageLoader
    ) {
        this.systemTrayFacade = systemTrayFacade;
        this.toolWindowActivator = toolWindowActivator;
        this.taskNavigator = taskNavigator != null ? taskNavigator : new CcgTaskNavigator();
        this.trayImageLoader = trayImageLoader;
    }

    /**
     * 发送系统通知；环境不支持时静默跳过，不影响主流程。
     *
     * @param project 当前项目
     * @param state 当前任务状态
     * @param message 系统通知正文
     */
    public void showTaskReminder(Project project, TaskState state, String message) {
        showTaskReminder(
            project,
            new TaskReminderNotificationPayload(state, null, null, message, message)
        );
    }

    public void showTaskReminder(Project project, TaskReminderNotificationPayload payload) {
        if (payload == null) {
            return;
        }
        latestNavigationTargetRef.set(new TaskReminderNavigationTarget(project, payload.getSessionId(), payload.getRequestId()));
        showTaskReminderInternal(
            project,
            payload.getState(),
            resolveNotificationTitle(payload),
            payload.getMessage()
        );
    }

    /**
     * 发送系统通知。
     * 托盘 tooltip 始终保持固定产品名；只有系统通知主标题会按会话标题变化。
     *
     * @param project 当前项目
     * @param state 当前任务状态
     * @param notificationTitle 系统通知主标题
     * @param message 系统通知正文
     */
    private void showTaskReminderInternal(Project project, TaskState state, String notificationTitle, String message) {
        if (project == null || project.isDisposed() || state == null || message == null || message.trim().isEmpty()) {
            return;
        }

        latestProjectRef.set(project);

        if (systemTrayFacade.isHeadless()) {
            LOG.info("[SystemReminderNotifier] Skip system reminder because environment is headless");
            return;
        }
        if (!systemTrayFacade.isSupported()) {
            LOG.info("[SystemReminderNotifier] Skip system reminder because system tray is not supported");
            return;
        }

        SystemTrayFacade.TrayIconHandle handle = ensureTrayIconHandle();
        if (handle == null) {
            return;
        }

        try {
            handle.displayMessage(notificationTitle, message, toMessageType(state));
        } catch (Exception e) {
            LOG.warn("[SystemReminderNotifier] Failed to display system reminder: " + e.getMessage(), e);
        }
    }

    /**
     * 解析系统通知主标题。
     * 当 payload 中没有可用标题时，统一回退到固定产品名 `CC GUI`。
     *
     * @param payload 当前提醒负载
     * @return 最终用于系统通知的主标题
     */
    private String resolveNotificationTitle(TaskReminderNotificationPayload payload) {
        if (payload == null) {
            return DEFAULT_NOTIFICATION_TITLE;
        }
        String notificationTitle = payload.getNotificationTitle();
        if (notificationTitle == null || notificationTitle.trim().isEmpty()) {
            return DEFAULT_NOTIFICATION_TITLE;
        }
        return notificationTitle.trim();
    }

    private SystemTrayFacade.TrayIconHandle ensureTrayIconHandle() {
        if (trayIconHandle != null) {
            return trayIconHandle;
        }
        synchronized (this) {
            if (trayIconHandle != null) {
                return trayIconHandle;
            }
            try {
                Image image = trayImageLoader.load();
                if (image == null) {
                    LOG.warn("[SystemReminderNotifier] Skip system reminder because tray icon image is unavailable");
                    return null;
                }
                trayIconHandle = systemTrayFacade.createTrayIcon(
                    image,
                    TRAY_TOOLTIP,
                    this::handleNotificationClick
                );
                return trayIconHandle;
            } catch (Exception e) {
                LOG.warn("[SystemReminderNotifier] Failed to initialize system tray icon: " + e.getMessage(), e);
                return null;
            }
        }
    }

    private SystemTrayFacade.TrayMessageType toMessageType(TaskState state) {
        return switch (state) {
            case FINAL_ERROR, CANCELLED -> SystemTrayFacade.TrayMessageType.ERROR;
            case COMPLETED, RECOVERED -> SystemTrayFacade.TrayMessageType.INFO;
            case WAITING_CONFIRM, RETRYING, RUNNING, PENDING -> SystemTrayFacade.TrayMessageType.WARNING;
        };
    }

    private void handleNotificationClick() {
        TaskReminderNavigationTarget target = latestNavigationTargetRef.get();
        if (target != null && target.getProject() != null && !target.getProject().isDisposed()) {
            taskNavigator.navigate(target);
            return;
        }
        toolWindowActivator.activate(latestProjectRef.get());
    }

    private static Image loadDefaultTrayImage() throws IOException {
        try (InputStream inputStream = SystemReminderNotifier.class.getResourceAsStream("/icons/logo-16.png")) {
            if (inputStream == null) {
                return null;
            }
            return ImageIO.read(inputStream);
        }
    }
}
