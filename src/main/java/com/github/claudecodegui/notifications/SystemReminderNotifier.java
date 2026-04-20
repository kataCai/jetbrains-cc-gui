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
    private static final String NOTIFICATION_TITLE = "CC GUI";

    @FunctionalInterface
    public interface TrayImageLoader {
        Image load() throws Exception;
    }

    private final SystemTrayFacade systemTrayFacade;
    private final CcgToolWindowActivator toolWindowActivator;
    private final TrayImageLoader trayImageLoader;
    private final AtomicReference<Project> latestProjectRef = new AtomicReference<>();

    private volatile SystemTrayFacade.TrayIconHandle trayIconHandle;

    public SystemReminderNotifier() {
        this(new SystemTrayFacade(), new CcgToolWindowActivator(), SystemReminderNotifier::loadDefaultTrayImage);
    }

    /**
     * 测试专用构造器，允许注入托盘抽象和图标加载逻辑。
     */
    public SystemReminderNotifier(
        SystemTrayFacade systemTrayFacade,
        CcgToolWindowActivator toolWindowActivator,
        TrayImageLoader trayImageLoader
    ) {
        this.systemTrayFacade = systemTrayFacade;
        this.toolWindowActivator = toolWindowActivator;
        this.trayImageLoader = trayImageLoader;
    }

    /**
     * 发送系统通知；环境不支持时静默跳过，不影响主流程。
     */
    public void showTaskReminder(Project project, TaskState state, String message) {
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
            handle.displayMessage(NOTIFICATION_TITLE, message, toMessageType(state));
        } catch (Exception e) {
            LOG.warn("[SystemReminderNotifier] Failed to display system reminder: " + e.getMessage(), e);
        }
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
                    () -> toolWindowActivator.activate(latestProjectRef.get())
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

    private static Image loadDefaultTrayImage() throws IOException {
        try (InputStream inputStream = SystemReminderNotifier.class.getResourceAsStream("/icons/logo-16.png")) {
            if (inputStream == null) {
                return null;
            }
            return ImageIO.read(inputStream);
        }
    }
}
