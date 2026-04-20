package com.github.claudecodegui.notifications;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;

/**
 * 封装 AWT 系统托盘访问，隔离静态 API 依赖，便于单测替换。
 */
public class SystemTrayFacade {

    /**
     * 通知消息级别。
     */
    public enum TrayMessageType {
        ERROR,
        INFO,
        WARNING,
        NONE
    }

    /**
     * 已注册托盘图标的最小操作接口。
     */
    @FunctionalInterface
    public interface TrayIconHandle {
        void displayMessage(String title, String message, TrayMessageType messageType);
    }

    /**
     * 是否处于无图形界面环境。
     */
    public boolean isHeadless() {
        return GraphicsEnvironment.isHeadless();
    }

    /**
     * 当前运行环境是否支持系统托盘。
     */
    public boolean isSupported() {
        return SystemTray.isSupported();
    }

    /**
     * 创建并注册托盘图标，同时绑定点击回调。
     */
    public TrayIconHandle createTrayIcon(Image image, String toolTip, Runnable onClick) throws AWTException {
        TrayIcon trayIcon = new TrayIcon(image, toolTip);
        trayIcon.setImageAutoSize(true);
        if (onClick != null) {
            trayIcon.addActionListener(event -> onClick.run());
        }
        SystemTray.getSystemTray().add(trayIcon);
        return (title, message, messageType) -> trayIcon.displayMessage(
            title,
            message,
            toAwtMessageType(messageType)
        );
    }

    private TrayIcon.MessageType toAwtMessageType(TrayMessageType messageType) {
        return switch (messageType) {
            case ERROR -> TrayIcon.MessageType.ERROR;
            case INFO -> TrayIcon.MessageType.INFO;
            case WARNING -> TrayIcon.MessageType.WARNING;
            case NONE -> TrayIcon.MessageType.NONE;
        };
    }
}
