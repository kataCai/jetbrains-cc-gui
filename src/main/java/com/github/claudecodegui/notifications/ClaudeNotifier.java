package com.github.claudecodegui.notifications;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.util.SoundNotificationService;
import com.github.claudecodegui.util.SystemNotificationService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Claude 通知与状态栏更新工具。
 * 统一收口状态栏文案、任务完成提示音、系统气泡预览以及远程协作提示，避免不同入口各自拼接提示内容。
 * 这里同时兼容旧调用路径和新的“基于会话摘要/最后回复生成通知内容”能力，便于并轨阶段逐步替换调用方。
 */
public class ClaudeNotifier {

    /**
     * 测试专用的完成通知拦截器。
     * 用于把 showSuccess 的副作用替换成可观测回调，避免单元测试真的弹系统通知或播放声音。
     */
    @FunctionalInterface
    public interface SuccessNotificationInterceptor {
        /**
         * 拦截一次完成通知直发调用。
         *
         * @param project 当前项目
         * @param title 通知标题
         * @param message 通知正文
         * @param playSound 是否原本会播放完成提示音
         * @return 无返回值
         */
        void onShow(@NotNull Project project, @Nullable String title, String message, boolean playSound);
    }

    // Pre-compiled patterns for {@link #condenseForToast}, reused across notifications.
    private static final Pattern CODE_FENCE_OPEN = Pattern.compile("```[a-zA-Z0-9_+\\-]*\\n");
    private static final Pattern CODE_FENCE_CLOSE = Pattern.compile("```");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // Cap how much of an assistant message we run regex over. The toast itself only
    // shows ~220 codepoints; processing the whole message (which can be tens of KB)
    // is wasteful and never adds visible content.
    private static final int CONDENSE_MAX_INPUT = 4096;
    private static volatile SuccessNotificationInterceptor successNotificationInterceptor;

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
        showSuccess(project, null, message, true);
    }

    /**
     * 显示成功提示，并允许调用方决定是否播放完成提示音。
     * 该重载主要用于已接入任务提醒分发器的路径，避免提醒分发器和这里重复播报。
     *
     * @param project 当前项目
     * @param message 提示正文
     * @param playSound 是否播放完成提示音
     */
    public static void showSuccess(@NotNull Project project, String message, boolean playSound) {
        showSuccess(project, null, message, playSound);
    }

    /**
     * 显示任务完成通知，并允许调用方传入动态标题。
     * 为空时系统通知回退到默认标题，同时保留旧调用路径的行为兼容性。
     *
     * @param project 当前项目
     * @param title 可选标题
     * @param message 提示正文或最后回复预览
     */
    public static void showSuccess(@NotNull Project project, @Nullable String title, String message) {
        showSuccess(project, title, message, true);
    }

    /**
     * 显示任务完成通知，同时支持动态标题和可选提示音控制。
     * 该方法是并轨后的统一收口入口：旧路径可只传 message，新路径可传 title + preview，
     * 接入 task reminder dispatcher 的路径则可关闭这里的提示音，避免双重播报。
     *
     * @param project 当前项目
     * @param title 可选标题；为空时由系统通知回退到默认标题
     * @param message 提示正文或最后一段回复预览
     * @param playSound 是否播放完成提示音
     */
    public static void showSuccess(
        @NotNull Project project,
        @Nullable String title,
        String message,
        boolean playSound
    ) {
        SuccessNotificationInterceptor interceptor = successNotificationInterceptor;
        if (interceptor != null) {
            interceptor.onShow(project, title, message, playSound);
            return;
        }
        show(project, "Claude [OK]", message, 5000);
        SystemNotificationService.getInstance().showVisualNotificationToast(project, title, message);
        if (playSound) {
            SoundNotificationService.getInstance().playTaskCompleteSound();
        }
    }

    /**
     * 从会话中提取通知标题。
     * 优先使用会话摘要；如果摘要为空，则返回 null，让系统通知回退到默认标题。
     *
     * @param session 当前会话
     * @return 可用于系统通知的标题；没有摘要时返回 null
     */
    @Nullable
    public static String buildTitleFromSession(@Nullable ClaudeSession session) {
        if (session == null) {
            return null;
        }
        String summary = session.getSummary();
        if (summary == null) {
            return null;
        }
        String trimmed = summary.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 从最近一条助手消息构建简短预览文本。
     * 如果没有可见的助手文本，则回退到传入的 fallback。
     * 这里容忍消息列表在跨线程复制期间出现的读取异常，避免把预览构建失败放大成任务完成回调失败。
     *
     * @param session 当前会话
     * @param fallback 无法提取预览时的回退文本
     * @return 通知预览文本
     */
    public static String buildPreviewFromSession(@Nullable ClaudeSession session, String fallback) {
        if (session == null) {
            return fallback;
        }
        try {
            List<ClaudeSession.Message> messages = session.getMessages();
            if (messages == null || messages.isEmpty()) {
                return fallback;
            }
            String taskNotificationSummary = extractLatestTaskNotificationSummary(messages);
            if (taskNotificationSummary != null && !taskNotificationSummary.isEmpty()) {
                String preview = condenseForToast(taskNotificationSummary);
                if (!preview.isEmpty()) {
                    return preview;
                }
            }
            for (int i = messages.size() - 1; i >= 0; i--) {
                ClaudeSession.Message m = messages.get(i);
                if (m == null || m.type != ClaudeSession.Message.Type.ASSISTANT) {
                    continue;
                }
                // Prefer the last text block from raw JSON: in tool-use turns the
                // accumulated m.content concatenates ALL text segments (including
                // pre-tool-call prose), so the preview would show mid-turn text
                // instead of the final answer.
                String content = extractLastTextFromRaw(m);
                if (content == null || content.isEmpty()) {
                    content = m.content;
                }
                if (content == null || content.isEmpty()) {
                    // Tool-call frames are emitted as ASSISTANT with empty text content;
                    // skip them so the preview prefers actual assistant prose.
                    continue;
                }
                String preview = condenseForToast(content);
                if (!preview.isEmpty()) {
                    return preview;
                }
            }
        } catch (Exception e) {
            return fallback;
        }
        return fallback;
    }

    /**
     * 提取聊天区最近一条 task-notification 的 summary。
     * 该摘要对应用户最终可见的统一结束说明，应优先用于系统通知预览，
     * 避免聊天区显示“已完成摘要”，而系统通知仍回退到中途 assistant 分析文本。
     *
     * @param messages 当前会话消息列表
     * @return 最近一条 task-notification 的 summary；不存在时返回 null
     */
    @Nullable
    private static String extractLatestTaskNotificationSummary(@NotNull List<ClaudeSession.Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ClaudeSession.Message message = messages.get(i);
            if (message == null || message.raw == null) {
                continue;
            }
            String summary = extractTaskNotificationSummary(message.raw);
            if (summary != null && !summary.isBlank()) {
                return summary.trim();
            }
        }
        return null;
    }

    /**
     * 把多行文本和代码块压缩成单行预览，便于在系统通知里展示。
     * 这里只做空白折叠和代码围栏去除，最终截断仍由系统通知层负责。
     *
     * @param raw 原始文本
     * @return 压缩后的单行预览
     */
    private static String condenseForToast(String raw) {
        String input = raw.length() > CONDENSE_MAX_INPUT ? raw.substring(0, CONDENSE_MAX_INPUT) : raw;
        String stripped = CODE_FENCE_OPEN.matcher(input).replaceAll("");
        stripped = CODE_FENCE_CLOSE.matcher(stripped).replaceAll("");
        return WHITESPACE_RUN.matcher(stripped).replaceAll(" ").trim();
    }

    /**
     * 从助手消息的 raw JSON 中提取最后一个 text block 的文本。
     * 工具调用场景下 raw content 往往包含前置说明和最终回答多个文本块，这里只取最后一个，
     * 让完成通知更贴近真正的最终输出。
     *
     * @param m 助手消息
     * @return 最后一个文本块内容；没有时返回 null
     */
    @Nullable
    private static String extractLastTextFromRaw(@NotNull ClaudeSession.Message m) {
        JsonObject raw = m.raw;
        if (raw == null || !raw.has("message") || !raw.get("message").isJsonObject()) {
            return null;
        }
        try {
            JsonObject message = raw.getAsJsonObject("message");
            if (!message.has("content") || !message.get("content").isJsonArray()) {
                return null;
            }
            JsonArray contentArray = message.getAsJsonArray("content");
            String lastText = null;
            for (JsonElement element : contentArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                if (block.has("type")
                    && "text".equals(block.get("type").getAsString())
                    && block.has("text")) {
                    lastText = block.get("text").getAsString();
                }
            }
            return lastText;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 raw JSON 中提取 task-notification 的 summary。
     * 兼容当前 Node 侧补发的 raw 结构：raw.origin.kind=task-notification，正文存放在 text block 内。
     *
     * @param raw 消息 raw JSON
     * @return task-notification summary；不存在或不匹配时返回 null
     */
    @Nullable
    private static String extractTaskNotificationSummary(@Nullable JsonObject raw) {
        if (raw == null || !isTaskNotificationRaw(raw)) {
            return null;
        }
        String text = extractLastTextBlock(raw);
        if (text == null || text.isBlank()) {
            return null;
        }
        int summaryStart = text.indexOf("<summary>");
        int summaryEnd = text.indexOf("</summary>");
        if (summaryStart < 0 || summaryEnd <= summaryStart) {
            return null;
        }
        return text.substring(summaryStart + "<summary>".length(), summaryEnd).trim();
    }

    /**
     * 判断当前 raw 是否为 task-notification。
     * 仅在 origin.kind 明确为 task-notification 时才视为统一结束说明，
     * 避免误把普通 user 文本中的 XML 片段识别为完成摘要。
     *
     * @param raw 消息 raw JSON
     * @return true 表示该 raw 对应 task-notification
     */
    private static boolean isTaskNotificationRaw(@NotNull JsonObject raw) {
        if (!raw.has("origin") || !raw.get("origin").isJsonObject()) {
            return false;
        }
        JsonObject origin = raw.getAsJsonObject("origin");
        return origin.has("kind")
            && !origin.get("kind").isJsonNull()
            && "task-notification".equals(origin.get("kind").getAsString());
    }

    /**
     * 提取 raw.message.content 中最后一个 text block 的文本。
     * 用于统一处理 assistant 最终文本与 task-notification XML 文本的读取逻辑。
     *
     * @param raw 消息 raw JSON
     * @return 最后一个 text block 的文本；不存在时返回 null
     */
    @Nullable
    private static String extractLastTextBlock(@NotNull JsonObject raw) {
        if (!raw.has("message") || !raw.get("message").isJsonObject()) {
            return null;
        }
        try {
            JsonObject message = raw.getAsJsonObject("message");
            if (!message.has("content") || !message.get("content").isJsonArray()) {
                return null;
            }
            JsonArray contentArray = message.getAsJsonArray("content");
            String lastText = null;
            for (JsonElement element : contentArray) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject block = element.getAsJsonObject();
                if (block.has("type")
                    && "text".equals(block.get("type").getAsString())
                    && block.has("text")
                    && !block.get("text").isJsonNull()) {
                    lastText = block.get("text").getAsString();
                }
            }
            return lastText;
        } catch (Exception e) {
            return null;
        }
    }

    public static void showError(@NotNull Project project, String message) {
        show(project, "Claude [ERR]", message, 8000);
    }

    public static void showWarning(@NotNull Project project, String message) {
        show(project, "Claude [WARN]", message, 6000);
    }

    public static void showTaskReminderStatus(@NotNull Project project, @NotNull TaskState state, String message) {
        // 状态栏只接收 ready / waiting / error / success 这一层较粗的状态，
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

    /**
     * 设置测试专用的完成通知拦截器。
     * 正常运行时应保持为 null；单元测试可通过该入口观测旧完成通知出口是否仍被调用。
     *
     * @param interceptor 测试拦截器；传 null 表示恢复真实通知行为
     * @return 无返回值
     */
    public static void setSuccessNotificationInterceptorForTest(
        @Nullable SuccessNotificationInterceptor interceptor
    ) {
        successNotificationInterceptor = interceptor;
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
        if (used == 0) { return ""; }
        String usedStr = formatNumber(used);
        if (max > 0) {
            String maxStr = formatNumber(max);
            return String.format("[%s / %s ctx]", usedStr, maxStr);
        }
        return String.format("[%s ctx]", usedStr);
    }

    public static void setModel(@NotNull Project project, String model) {
        com.intellij.openapi.application.Application application = ApplicationManager.getApplication();
        // 中文注释：单测环境没有完整 IDE Application，也不需要刷新状态栏；
        // 这里安全跳过，避免让纯状态流测试被 UI 基础设施误伤。
        if (application == null || application.isDisposed()) {
            return;
        }
        application.invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) { widget.setModel(model); }
        });
    }

    public static void setMode(@NotNull Project project, String mode) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) { widget.setMode(mode); }
        });
    }

    public static void setAgent(@NotNull Project project, String agent) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) { widget.setAgent(agent); }
        });
    }

    public static void updateRemoteCollabStatus(
        @NotNull Project project,
        String connectionStatus,
        boolean receivingUpdates
    ) {
        ApplicationManager.getApplication().invokeLater(() -> {
            ClaudeStatusBarWidget widget = ClaudeStatusBarWidget.Factory.getWidget(project);
            if (widget != null) {
                widget.setRemoteHint(buildRemoteHint(connectionStatus, receivingUpdates));
            }
        });
    }

    private static String formatNumber(int num) {
        if (num < 1000) { return String.valueOf(num); }
        if (num < 1000000) { return String.format("%.1fk", num / 1000.0); }
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

    private static String buildRemoteHint(String connectionStatus, boolean receivingUpdates) {
        if (connectionStatus == null || connectionStatus.isBlank() || "disabled".equals(connectionStatus)) {
            return "";
        }
        return switch (connectionStatus) {
            case "connected" -> receivingUpdates ? "[TG 已连接]" : "[TG 仅发送]";
            case "connecting" -> "[TG 连接中]";
            case "error" -> "[TG 异常]";
            case "disconnected" -> "[TG 未绑定]";
            default -> "[TG " + connectionStatus + "]";
        };
    }
}
