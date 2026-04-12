package com.github.claudecodegui.notifications;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.util.SoundNotificationService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Simple utility to update the Claude Status Bar Widget.
 */
public class ClaudeNotifier {

    public static void setThinking(@NotNull Project project) {
        update(project, "thinking", ClaudeCodeGuiBundle.message("notifier.thinking"));
    }

    public static void setGenerating(@NotNull Project project) {
        update(project, "generating", ClaudeCodeGuiBundle.message("notifier.generating"));
    }

    public static void setWaiting(@NotNull Project project) {
        update(project, "waiting", ClaudeCodeGuiBundle.message("notifier.waiting"));
    }

    public static void showSuccess(@NotNull Project project, String message) {
        showSuccess(project, message, true);
    }

    public static void showSuccess(@NotNull Project project, String message, boolean playSound) {
        show(project, "Claude ✓", message, 5000);

        if (playSound) {
            // 兼容旧调用路径：没有接入 task reminder dispatcher 的地方，
            // 仍然沿用这里的完成提示音。
            SoundNotificationService.getInstance().playTaskCompleteSound();
        }
    }

    public static void showError(@NotNull Project project, String message) {
        show(project, "Claude ✗", message, 8000);
    }

    public static void showWarning(@NotNull Project project, String message) {
        show(project, "Claude ⚠", message, 6000);
    }

    public static void showTaskReminderStatus(@NotNull Project project, @NotNull TaskState state, String message) {
        // 状态栏只接受 ready / waiting / error / success 这一层较粗的状态，
        // 因此这里把更细粒度的任务状态折叠后再更新，避免前端和 IDE widget 各自维护映射。
        String status = switch (state) {
            case RUNNING, WAITING_CONFIRM, RETRYING, PENDING -> "waiting";
            case FINAL_ERROR, CANCELLED -> "error";
            case COMPLETED, RECOVERED -> "success";
        };
        update(project, status, message);
    }

    public static void clearStatus(@NotNull Project project) {
        update(project, "ready", null);
    }
    
    public static void setTokenUsage(@NotNull Project project, int usedTokens, int maxTokens) {
        String tokenInfo = formatTokenUsage(usedTokens, maxTokens);
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) {
                widget.setTokenInfo(tokenInfo);
            }
        });
    }

    private static String formatTokenUsage(int used, int max) {
        if (used == 0) return "";
        String usedStr = formatNumber(used);
        if (max > 0) {
            String maxStr = formatNumber(max);
            return String.format("[%s / %s ctx]", usedStr, maxStr);
        }
        return String.format("[%s ctx]", usedStr);
    }
    
    public static void setModel(@NotNull Project project, String model) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) widget.setModel(model);
        });
    }

    public static void setMode(@NotNull Project project, String mode) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) widget.setMode(mode);
        });
    }

    public static void setAgent(@NotNull Project project, String agent) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) widget.setAgent(agent);
        });
    }

    private static String formatNumber(int num) {
        if (num < 1000) return String.valueOf(num);
        if (num < 1000000) return String.format("%.1fk", num / 1000.0);
        return String.format("%.1fm", num / 1000000.0);
    }

    private static void update(@NotNull Project project, String status, String details) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) {
                widget.updateStatus(status, details);
            }
        });
    }

    private static void show(@NotNull Project project, String text, String tooltip, long duration) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) {
                widget.show(text, tooltip, duration);
            }
        });
    }
}
