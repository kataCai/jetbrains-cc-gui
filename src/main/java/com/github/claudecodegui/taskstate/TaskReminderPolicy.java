package com.github.claudecodegui.taskstate;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 定义提醒渠道的路由规则。
 *
 * <p>这个类只回答“当前状态应该走哪些渠道”，不关心具体如何弹窗、如何发声音，
 * 因此可以在测试里被独立验证，也方便后续把策略做成可配置项。
 */
public class TaskReminderPolicy {

    private final Set<TaskState> popupStates;
    private final Set<TaskState> balloonStates;
    private final Set<TaskState> soundStates;
    private final Set<TaskState> statusBarStates;
    private final boolean suppressWaitingPopupWhenApprovalDialogOpen;
    private final boolean balloonOnlyWhenIdeUnfocused;
    private final boolean soundOnlyWhenIdeUnfocused;

    /**
     * 创建一份完整的提醒策略。
     *
     * @param popupStates 哪些状态允许走前端 popup
     * @param balloonStates 哪些状态允许走 IDE balloon
     * @param soundStates 哪些状态允许播声音
     * @param statusBarStates 哪些状态需要同步到状态栏
     * @param suppressWaitingPopupWhenApprovalDialogOpen 审批弹窗已打开时，是否压制等待确认 popup
     * @param balloonOnlyWhenIdeUnfocused 气泡是否仅在 IDE 不聚焦时触发
     * @param soundOnlyWhenIdeUnfocused 声音是否仅在 IDE 不聚焦时触发
     */
    public TaskReminderPolicy(
        Set<TaskState> popupStates,
        Set<TaskState> balloonStates,
        Set<TaskState> soundStates,
        Set<TaskState> statusBarStates,
        boolean suppressWaitingPopupWhenApprovalDialogOpen,
        boolean balloonOnlyWhenIdeUnfocused,
        boolean soundOnlyWhenIdeUnfocused
    ) {
        this.popupStates = immutableEnumSet(popupStates);
        this.balloonStates = immutableEnumSet(balloonStates);
        this.soundStates = immutableEnumSet(soundStates);
        this.statusBarStates = immutableEnumSet(statusBarStates);
        this.suppressWaitingPopupWhenApprovalDialogOpen = suppressWaitingPopupWhenApprovalDialogOpen;
        this.balloonOnlyWhenIdeUnfocused = balloonOnlyWhenIdeUnfocused;
        this.soundOnlyWhenIdeUnfocused = soundOnlyWhenIdeUnfocused;
    }

    /**
     * 返回当前任务提醒功能的默认策略。
     * 默认值偏保守：完成态走轻提示，等待确认/最终失败走更强提醒。
     */
    public static TaskReminderPolicy defaults() {
        return new TaskReminderPolicy(
            // popup 只承担“必须马上关注”的职责，因此默认只覆盖等待审批和最终失败。
            EnumSet.of(TaskState.WAITING_CONFIRM, TaskState.FINAL_ERROR),
            EnumSet.of(TaskState.COMPLETED, TaskState.RECOVERED, TaskState.FINAL_ERROR),
            EnumSet.of(TaskState.COMPLETED),
            EnumSet.of(
                TaskState.RUNNING,
                TaskState.WAITING_CONFIRM,
                TaskState.RETRYING,
                TaskState.RECOVERED,
                TaskState.FINAL_ERROR,
                TaskState.COMPLETED,
                TaskState.CANCELLED
            ),
            true,
            true,
            true
        );
    }

    /**
     * 根据当前状态、弹窗情况和 IDE 焦点情况计算最终路由结果。
     */
    public ReminderDecision decide(TaskStateSnapshot snapshot, boolean approvalDialogOpen, boolean ideFocused) {
        TaskState state = snapshot != null ? snapshot.getState() : null;
        if (state == null) {
            return ReminderDecision.NONE;
        }

        boolean showPopup = popupStates.contains(state);
        if (showPopup && suppressWaitingPopupWhenApprovalDialogOpen
            && approvalDialogOpen && state == TaskState.WAITING_CONFIRM) {
            // IDE 前台已经有审批弹窗时，不再追加 reminder popup，
            // 避免用户被两个“确认继续”的界面来回打断。
            showPopup = false;
        }

        boolean showBalloon = balloonStates.contains(state);
        if (showBalloon && balloonOnlyWhenIdeUnfocused && ideFocused) {
            showBalloon = false;
        }

        boolean playSound = soundStates.contains(state);
        if (playSound && soundOnlyWhenIdeUnfocused && ideFocused) {
            // 声音提醒默认更克制：IDE 正在前台时，用户已经能看到状态变化，
            // 没必要再用提示音打断。
            playSound = false;
        }

        boolean updateStatusBar = statusBarStates.contains(state);

        return new ReminderDecision(showPopup, showBalloon, updateStatusBar, playSound);
    }

    /**
     * 把输入集合收敛成不可变 EnumSet。
     * 这样策略构建完成后就不会被外部调用方再修改。
     */
    private static Set<TaskState> immutableEnumSet(Set<TaskState> states) {
        if (states == null || states.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(states));
    }

    public static final class ReminderDecision {
        private static final ReminderDecision NONE = new ReminderDecision(false, false, false, false);

        private final boolean showPopup;
        private final boolean showBalloon;
        private final boolean updateStatusBar;
        private final boolean playSound;

        /**
         * 一次策略计算的最终结果。
         * Dispatcher 只消费这个结果，不需要再关心内部路由细节。
         */
        public ReminderDecision(boolean showPopup, boolean showBalloon, boolean updateStatusBar, boolean playSound) {
            this.showPopup = showPopup;
            this.showBalloon = showBalloon;
            this.updateStatusBar = updateStatusBar;
            this.playSound = playSound;
        }

        public boolean shouldShowPopup() {
            return showPopup;
        }

        public boolean shouldShowBalloon() {
            return showBalloon;
        }

        public boolean shouldUpdateStatusBar() {
            return updateStatusBar;
        }

        public boolean shouldPlaySound() {
            return playSound;
        }
    }
}
