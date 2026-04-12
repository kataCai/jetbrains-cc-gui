package com.github.claudecodegui.taskstate;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeBalloonNotifier;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.util.SoundNotificationService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将统一任务状态分发到不同提醒渠道。
 * 这里的核心职责不是“维护状态”，而是根据策略把同一个 snapshot
 * 同步路由到 popup、balloon、status bar、sound 四种出口。
 */
public class TaskReminderDispatcher {

    private static final int MAX_DEDUP_CACHE_SIZE = 256;

    @FunctionalInterface
    public interface ReminderSoundPlayer {
        void play(TaskState state);
    }

    @FunctionalInterface
    public interface IdeFocusChecker {
        boolean isIdeFocused();
    }

    @FunctionalInterface
    public interface ReminderMessageResolver {
        String resolve(TaskStateSnapshot snapshot);
    }

    private final HandlerContext context;
    private final TaskReminderPolicy policy;
    private final ClaudeBalloonNotifier balloonNotifier;
    private final ReminderSoundPlayer reminderSoundPlayer;
    private final IdeFocusChecker ideFocusChecker;
    private final ReminderMessageResolver reminderMessageResolver;
    private final Gson gson = new Gson();
    // popup 和 balloon 分别维护去重缓存，避免一次状态变更在 React 重挂载、
    // session 恢复或重复消息推送时多次弹出相同提醒。
    private final Map<String, Boolean> popupDedupKeys = new LinkedHashMap<>();
    private final Map<String, Boolean> balloonDedupKeys = new LinkedHashMap<>();

    /**
     * 使用默认策略构建提醒分发器。
     * 生产环境默认会把完成态声音、IDE 焦点判断、气泡提醒等真实依赖全部接上。
     */
    public TaskReminderDispatcher(HandlerContext context) {
        this(
            context,
            TaskReminderPolicy.defaults(),
            new ClaudeBalloonNotifier(),
            SoundNotificationService.getInstance()::playTaskReminderSound,
            () -> ApplicationManager.getApplication().isActive(),
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 供业务代码注入自定义策略和气泡实现的构造方法。
     * 声音播放仍然复用 {@link SoundNotificationService} 的 task reminder 入口。
     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        SoundNotificationService soundNotificationService
    ) {
        this(
            context,
            policy,
            balloonNotifier,
            soundNotificationService::playTaskReminderSound,
            () -> ApplicationManager.getApplication().isActive(),
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 供单元测试或更细粒度定制使用的完整构造方法。
     * 这里把“声音播放”和“IDE 是否聚焦”都抽成函数接口，便于稳定验证分发策略。
     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker
    ) {
        this(
            context,
            policy,
            balloonNotifier,
            reminderSoundPlayer,
            ideFocusChecker,
            TaskReminderDispatcher::buildDefaultReminderMessage
        );
    }

    /**
     * 完整构造方法，允许测试或上层定制提醒文案来源。
     * 这样业务代码仍默认走 bundle，本地测试则可以稳定注入伪翻译器。
     */
    public TaskReminderDispatcher(
        HandlerContext context,
        TaskReminderPolicy policy,
        ClaudeBalloonNotifier balloonNotifier,
        ReminderSoundPlayer reminderSoundPlayer,
        IdeFocusChecker ideFocusChecker,
        ReminderMessageResolver reminderMessageResolver
    ) {
        this.context = context;
        this.policy = policy;
        this.balloonNotifier = balloonNotifier;
        this.reminderSoundPlayer = reminderSoundPlayer;
        this.ideFocusChecker = ideFocusChecker;
        this.reminderMessageResolver = reminderMessageResolver;
    }

    /**
     * 根据当前任务快照向多个提醒渠道分发通知。
     *
     * <p>处理顺序遵循“状态栏 -> 气泡 -> 声音 -> 前端弹窗”的思路：
     * 状态栏最轻、最稳定；popup 最打断用户，因此最后再判断且带去重。
     *
     * @param snapshot 当前聚合后的任务状态快照
     * @param approvalDialogOpen 当前是否已经有审批弹窗打开，用于抑制重复 popup
     */
    public void dispatch(TaskStateSnapshot snapshot, boolean approvalDialogOpen) {
        if (snapshot == null || snapshot.getState() == null || context == null) {
            return;
        }

        boolean ideFocused = ideFocusChecker.isIdeFocused();
        // 所有提醒渠道都基于同一个决策结果，避免“状态栏说完成、弹窗却没出现”
        // 这种由多处独立判断导致的不一致。
        TaskReminderPolicy.ReminderDecision decision = policy.decide(snapshot, approvalDialogOpen, ideFocused);
        String reminderMessage = reminderMessageResolver.resolve(snapshot);
        String dedupKey = buildDedupKey(snapshot);

        if (decision.shouldUpdateStatusBar()) {
            Project project = context.getProject();
            if (project != null && !project.isDisposed()) {
                ClaudeNotifier.showTaskReminderStatus(project, snapshot.getState(), reminderMessage);
            }
        }

        if (decision.shouldShowBalloon() && markDispatched(balloonDedupKeys, dedupKey)) {
            balloonNotifier.showTaskReminder(context.getProject(), snapshot.getState(), reminderMessage);
        }

        if (decision.shouldPlaySound()) {
            reminderSoundPlayer.play(snapshot.getState());
        }

        if (decision.shouldShowPopup() && markDispatched(popupDedupKeys, dedupKey)) {
            dispatchPopup(snapshot, reminderMessage);
        }
    }

    /**
     * 向前端发送 task reminder popup 请求。
     * 如果 React 回调尚未注册，则先写入 window 侧缓存队列，等待前端初始化后回放。
     */
    private void dispatchPopup(TaskStateSnapshot snapshot, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("state", snapshot.getState().getValue());
        payload.addProperty("message", message);
        if (hasText(snapshot.getSessionId())) {
            payload.addProperty("sessionId", snapshot.getSessionId());
        }
        if (hasText(snapshot.getRequestId())) {
            payload.addProperty("requestId", snapshot.getRequestId());
        }

        String escapedPayload = context.escapeJs(gson.toJson(payload));
        // popup 可能早于 React App 完成初始化，此时不能直接丢弃请求。
        // 先把 payload 暂存在 window.__pendingTaskReminderDialogRequests，
        // 待前端挂上 showTaskReminderDialog 回调后再统一回放。
        String jsCode = "(function(){"
            + "var payload='" + escapedPayload + "';"
            + "if (window.showTaskReminderDialog) {"
            + "  window.showTaskReminderDialog(payload);"
            + "} else {"
            + "  window.__pendingTaskReminderDialogRequests = window.__pendingTaskReminderDialogRequests || [];"
            + "  window.__pendingTaskReminderDialogRequests.push(payload);"
            + "}"
            + "})();";
        context.executeJavaScriptOnEDT(jsCode);
    }

    /**
     * 根据状态和最近一次事件原因生成提醒文案。
     * 这里统一收敛文案，避免状态栏、气泡和 popup 各自拼接不同内容。
     */
    private static String buildDefaultReminderMessage(TaskStateSnapshot snapshot) {
        String reason = snapshot.getLatestEvent() != null ? snapshot.getLatestEvent().getReason() : null;

        return switch (snapshot.getState()) {
            case WAITING_CONFIRM -> ClaudeCodeGuiBundle.message("task.reminder.waitingConfirm");
            case FINAL_ERROR -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.finalError");
            case COMPLETED -> ClaudeCodeGuiBundle.message("task.reminder.completed");
            case RECOVERED -> ClaudeCodeGuiBundle.message("task.reminder.recovered");
            case RETRYING -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.retrying");
            case CANCELLED -> hasText(reason)
                ? reason
                : ClaudeCodeGuiBundle.message("task.reminder.cancelled");
            case RUNNING -> ClaudeCodeGuiBundle.message("task.reminder.running");
            case PENDING -> ClaudeCodeGuiBundle.message("task.reminder.pending");
        };
    }

    /**
     * 为一次提醒生成稳定的去重键。
     * 只要状态、session/request 以及最近事件时间戳相同，就视为同一次提醒。
     */
    private String buildDedupKey(TaskStateSnapshot snapshot) {
        String sessionId = snapshot.getSessionId() != null ? snapshot.getSessionId() : "";
        String requestId = snapshot.getRequestId() != null ? snapshot.getRequestId() : "";
        long eventTimestamp = snapshot.getLatestEvent() != null ? snapshot.getLatestEvent().getTimestamp() : 0L;
        // 使用“状态 + session/request + 最新事件时间戳”组合去重，
        // 既能挡住同一事件的重复投递，也不会把下一次真实状态变化误判成重复。
        return snapshot.getState().name() + "|" + sessionId + "|" + requestId + "|" + eventTimestamp;
    }

    /**
     * 尝试把提醒键写入去重缓存。
     *
     * @return true 表示这是首次分发；false 表示已分发过，应跳过
     */
    private boolean markDispatched(Map<String, Boolean> dedupMap, String key) {
        synchronized (dedupMap) {
            if (dedupMap.containsKey(key)) {
                return false;
            }
            dedupMap.put(key, Boolean.TRUE);
            trimDedupMap(dedupMap);
            return true;
        }
    }

    /**
     * 修剪去重缓存，避免长时间运行后内存持续增长。
     * 策略上优先保留最近的提醒记录，因为它们最可能再次被重复投递。
     */
    private void trimDedupMap(Map<String, Boolean> dedupMap) {
        while (dedupMap.size() > MAX_DEDUP_CACHE_SIZE) {
            // LinkedHashMap 按插入顺序移除最旧项，
            // 这样去重缓存不会无限增长，但最近一批提醒仍然可拦截重复弹出。
            Iterator<String> iterator = dedupMap.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 判断字符串是否包含有效文本。
     * 这里主要用于决定是否把 sessionId / requestId 写进前端 payload。
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
