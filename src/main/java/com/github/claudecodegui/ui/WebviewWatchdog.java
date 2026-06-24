package com.github.claudecodegui.ui;

import com.github.claudecodegui.util.HtmlLoader;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Webview render watchdog for JCEF stall/black-screen recovery.
 * Monitors heartbeat signals from the webview and triggers reload or recreate
 * when the webview becomes unresponsive.
 */
public class WebviewWatchdog {

    private static final Logger LOG = Logger.getInstance(WebviewWatchdog.class);

    private static final long HEARTBEAT_TIMEOUT_MS = 45_000L;
    private static final long WATCHDOG_INTERVAL_MS = 10_000L;
    private static final long RECOVERY_COOLDOWN_MS = 60_000L;
    private static final long RESTORE_SUPPRESSION_MS = 30_000L;

    private volatile long lastHeartbeatAtMs = System.currentTimeMillis();
    private volatile long lastRafAtMs = System.currentTimeMillis();
    private volatile String lastVisibility = null;
    private volatile Boolean lastHasFocus = null;
    private volatile int stallCount = 0;
    private volatile long lastRecoveryAtMs = 0L;
    private volatile long suppressRecoveryUntilMs = 0L;
    private volatile ScheduledFuture<?> watchdogFuture = null;

    private final JPanel mainPanel;
    private final BrowserProvider browserProvider;
    private final HtmlLoader htmlLoader;
    private final Runnable onRecreateWebview;
    private final DisposedCheck disposedCheck;

    /**
     * Provides access to the current browser instance.
     */
    public interface BrowserProvider {
        JBCefBrowser getBrowser();
    }

    /**
     * Checks if the parent component has been disposed.
     */
    public interface DisposedCheck {
        boolean isDisposed();
    }

    /**
     * Checks if the backend is currently streaming.
     * During active streaming, JCEF IPC saturation is expected and reloading
     * the webview would destroy React state while the backend continues working.
     */
    public interface StreamActiveCheck {
        boolean isStreamActive();
    }

    // Extended timeout during active streaming — IPC saturation is expected
    // when pushing large message payloads.  Reloading would destroy React state
    // and the backend would continue pushing to a blank page.
    private static final long STREAMING_HEARTBEAT_TIMEOUT_MS = 180_000L; // 3 minutes

    private final StreamActiveCheck streamActiveCheck;

    public WebviewWatchdog(
            JPanel mainPanel,
            BrowserProvider browserProvider,
            HtmlLoader htmlLoader,
            Runnable onRecreateWebview,
            DisposedCheck disposedCheck,
            StreamActiveCheck streamActiveCheck
    ) {
        this.mainPanel = mainPanel;
        this.browserProvider = browserProvider;
        this.htmlLoader = htmlLoader;
        this.onRecreateWebview = onRecreateWebview;
        this.disposedCheck = disposedCheck;
        this.streamActiveCheck = streamActiveCheck;
    }

    /**
     * Start the watchdog scheduler.
     */
    public void start() {
        if (watchdogFuture != null) {
            return;
        }

        watchdogFuture = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
            try {
                checkHealth();
            } catch (Exception e) {
                LOG.debug("[WebviewWatchdog] Unexpected error: " + e.getMessage(), e);
            }
        }, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the watchdog scheduler.
     */
    public void stop() {
        if (watchdogFuture != null) {
            watchdogFuture.cancel(true);
            watchdogFuture = null;
        }
    }

    /**
     * Handle a heartbeat message from the webview.
     */
    public void handleHeartbeat(String content) {
        long now = System.currentTimeMillis();
        lastHeartbeatAtMs = now;

        if (content == null || content.isEmpty()) {
            lastRafAtMs = now;
            lastVisibility = null;
            lastHasFocus = null;
            return;
        }

        try {
            JsonObject json = new Gson().fromJson(content, JsonObject.class);
            if (json != null) {
                if (json.has("raf")) {
                    lastRafAtMs = json.get("raf").getAsLong();
                } else {
                    lastRafAtMs = now;
                }
                if (json.has("visibility")) {
                    lastVisibility = json.get("visibility").getAsString();
                }
                if (json.has("focus")) {
                    lastHasFocus = json.get("focus").getAsBoolean();
                }
            }
        } catch (Exception ignored) {
            // Non-JSON heartbeat payload (backward compatibility)
            lastRafAtMs = now;
        }
    }

    /**
     * Reset heartbeat timestamps (e.g., after a recovery action).
     */
    public void resetTimestamps() {
        long now = System.currentTimeMillis();
        lastHeartbeatAtMs = now;
        lastRafAtMs = now;
    }

    /**
     * 在恢复链路、reload/recreate 或前端刚 ready 的短窗口内抑制 watchdog 恢复。
     * 这样可以避免 React 尚未完成回放时被再次判定为卡死，从而触发二次放大。
     *
     * @param reason 抑制原因，仅用于日志排查
     */
    public void suppressRecovery(String reason) {
        long now = System.currentTimeMillis();
        suppressRecoveryUntilMs = Math.max(suppressRecoveryUntilMs, now + RESTORE_SUPPRESSION_MS);
        lastHeartbeatAtMs = now;
        lastRafAtMs = now;
        LOG.info("[WebviewWatchdog] Suppressing recovery for " + RESTORE_SUPPRESSION_MS
                + "ms, reason=" + reason
                + ", until=" + suppressRecoveryUntilMs);
    }

    private void checkHealth() {
        if (disposedCheck.isDisposed()) { return; }
        if (!mainPanel.isShowing()) { return; }

        long now = System.currentTimeMillis();
        long heartbeatAgeMs = now - lastHeartbeatAtMs;
        long rafAgeMs = now - lastRafAtMs;

        boolean visible = lastVisibility == null || "visible".equals(lastVisibility);
        boolean focused = lastHasFocus == null || lastHasFocus;
        if (!visible || !focused) {
            return;
        }

        if (now - lastRecoveryAtMs < RECOVERY_COOLDOWN_MS) {
            return;
        }
        if (now < suppressRecoveryUntilMs) {
            return;
        }

        // During active streaming, JCEF IPC saturation is expected with large payloads.
        // Use a much longer timeout to avoid destroying React state unnecessarily.
        // Reloading during streaming causes "fake death": backend continues working
        // but the webview shows empty content because streaming state is lost.
        boolean streaming = streamActiveCheck.isStreamActive();
        long effectiveTimeoutMs = streaming ? STREAMING_HEARTBEAT_TIMEOUT_MS : HEARTBEAT_TIMEOUT_MS;

        boolean stalled = heartbeatAgeMs > effectiveTimeoutMs || rafAgeMs > effectiveTimeoutMs;
        if (!stalled) {
            stallCount = 0;
            return;
        }

        if (disposedCheck.isDisposed()) { return; }

        stallCount += 1;
        String reason = "heartbeatAgeMs=" + heartbeatAgeMs + ", rafAgeMs=" + rafAgeMs;
        LOG.warn("[WebviewWatchdog] Webview appears stalled (" + stallCount + "), attempting recovery. " + reason);

        lastRecoveryAtMs = now;
        // Give the webview a grace window after initiating recovery to avoid repeated triggers.
        lastHeartbeatAtMs = now;
        lastRafAtMs = now;

        if (stallCount <= 1) {
            reload("watchdog_reload");
        } else {
            onRecreateWebview.run();
            stallCount = 0;
        }
    }

    private void reload(String reason) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (disposedCheck.isDisposed()) { return; }
            JBCefBrowser browser = browserProvider.getBrowser();
            if (browser == null) {
                onRecreateWebview.run();
                return;
            }
            try {
                browser.loadHTML(htmlLoader.loadChatHtml());
                mainPanel.revalidate();
                mainPanel.repaint();
            } catch (Exception e) {
                LOG.warn("[WebviewWatchdog] Reload failed: " + e.getMessage(), e);
            }
        });
    }
}
