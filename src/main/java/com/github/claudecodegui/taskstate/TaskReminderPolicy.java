package com.github.claudecodegui.taskstate;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 定义任务提醒各通道的路由规则。
 */
public class TaskReminderPolicy {

    private final Set<TaskState> popupStates;
    private final Set<TaskState> balloonStates;
    private final Set<TaskState> soundStates;
    private final Set<TaskState> systemStates;
    private final Set<TaskState> statusBarStates;
    private final boolean popupOnlyWhenIdeUnfocused;
    private final boolean suppressWaitingPopupWhenApprovalDialogVisible;
    private final boolean balloonOnlyWhenIdeUnfocused;
    private final boolean soundOnlyWhenIdeUnfocused;
    private final boolean systemOnlyWhenIdeUnfocused;

    public TaskReminderPolicy(
        Set<TaskState> popupStates,
        Set<TaskState> balloonStates,
        Set<TaskState> soundStates,
        Set<TaskState> systemStates,
        Set<TaskState> statusBarStates,
        boolean popupOnlyWhenIdeUnfocused,
        boolean suppressWaitingPopupWhenApprovalDialogVisible,
        boolean balloonOnlyWhenIdeUnfocused,
        boolean soundOnlyWhenIdeUnfocused,
        boolean systemOnlyWhenIdeUnfocused
    ) {
        this.popupStates = immutableEnumSet(popupStates);
        this.balloonStates = immutableEnumSet(balloonStates);
        this.soundStates = immutableEnumSet(soundStates);
        this.systemStates = immutableEnumSet(systemStates);
        this.statusBarStates = immutableEnumSet(statusBarStates);
        this.popupOnlyWhenIdeUnfocused = popupOnlyWhenIdeUnfocused;
        this.suppressWaitingPopupWhenApprovalDialogVisible = suppressWaitingPopupWhenApprovalDialogVisible;
        this.balloonOnlyWhenIdeUnfocused = balloonOnlyWhenIdeUnfocused;
        this.soundOnlyWhenIdeUnfocused = soundOnlyWhenIdeUnfocused;
        this.systemOnlyWhenIdeUnfocused = systemOnlyWhenIdeUnfocused;
    }

    /**
     * 兼容旧调用方：未显式传入 system 通道时，默认关闭该通道。
     */
    public TaskReminderPolicy(
        Set<TaskState> popupStates,
        Set<TaskState> balloonStates,
        Set<TaskState> soundStates,
        Set<TaskState> statusBarStates,
        boolean popupOnlyWhenIdeUnfocused,
        boolean suppressWaitingPopupWhenApprovalDialogVisible,
        boolean balloonOnlyWhenIdeUnfocused,
        boolean soundOnlyWhenIdeUnfocused
    ) {
        this(
            popupStates,
            balloonStates,
            soundStates,
            Collections.emptySet(),
            statusBarStates,
            popupOnlyWhenIdeUnfocused,
            suppressWaitingPopupWhenApprovalDialogVisible,
            balloonOnlyWhenIdeUnfocused,
            soundOnlyWhenIdeUnfocused,
            true
        );
    }

    /**
     * 返回当前任务提醒功能的默认策略。
     */
    public static TaskReminderPolicy defaults() {
        return new TaskReminderPolicy(
            EnumSet.of(TaskState.WAITING_CONFIRM, TaskState.FINAL_ERROR),
            EnumSet.of(TaskState.COMPLETED, TaskState.RECOVERED, TaskState.FINAL_ERROR),
            EnumSet.of(TaskState.COMPLETED),
            Collections.emptySet(),
            EnumSet.of(
                TaskState.RUNNING,
                TaskState.WAITING_CONFIRM,
                TaskState.RETRYING,
                TaskState.RECOVERED,
                TaskState.FINAL_ERROR,
                TaskState.COMPLETED,
                TaskState.CANCELLED
            ),
            false,
            true,
            true,
            true,
            true
        );
    }

    /**
     * 根据当前状态、审批弹窗可见性和 IDE 焦点情况计算最终路由结果。
     */
    public ReminderDecision decide(TaskStateSnapshot snapshot, boolean approvalDialogVisible, boolean ideFocused) {
        TaskState state = snapshot != null ? snapshot.getState() : null;
        if (state == null) {
            return ReminderDecision.NONE;
        }

        boolean showPopup = popupStates.contains(state);
        String popupReason = showPopup ? "allowed" : "state_not_enabled";
        if (showPopup && popupOnlyWhenIdeUnfocused && ideFocused) {
            showPopup = false;
            popupReason = "ide_focused_filtered";
        } else if (showPopup
            && suppressWaitingPopupWhenApprovalDialogVisible
            && approvalDialogVisible
            && state == TaskState.WAITING_CONFIRM) {
            showPopup = false;
            popupReason = "approval_dialog_visible";
        }

        boolean showBalloon = balloonStates.contains(state);
        String balloonReason = showBalloon ? "allowed" : "state_not_enabled";
        if (showBalloon && balloonOnlyWhenIdeUnfocused && ideFocused) {
            showBalloon = false;
            balloonReason = "ide_focused_filtered";
        }

        boolean playSound = soundStates.contains(state);
        String soundReason = playSound ? "allowed" : "state_not_enabled";
        if (playSound && soundOnlyWhenIdeUnfocused && ideFocused) {
            playSound = false;
            soundReason = "ide_focused_filtered";
        }

        boolean showSystem = systemStates.contains(state);
        String systemReason = showSystem ? "allowed" : "state_not_enabled";
        if (showSystem && systemOnlyWhenIdeUnfocused && ideFocused) {
            showSystem = false;
            systemReason = "ide_focused_filtered";
        }

        boolean updateStatusBar = statusBarStates.contains(state);

        return new ReminderDecision(
            showPopup,
            showBalloon,
            playSound,
            showSystem,
            updateStatusBar,
            popupReason,
            balloonReason,
            soundReason,
            systemReason
        );
    }

    private static Set<TaskState> immutableEnumSet(Set<TaskState> states) {
        if (states == null || states.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(EnumSet.copyOf(states));
    }

    public static final class ReminderDecision {
        private static final ReminderDecision NONE = new ReminderDecision(
            false,
            false,
            false,
            false,
            false,
            "state_not_enabled",
            "state_not_enabled",
            "state_not_enabled",
            "state_not_enabled"
        );

        private final boolean showPopup;
        private final boolean showBalloon;
        private final boolean playSound;
        private final boolean showSystem;
        private final boolean updateStatusBar;
        private final String popupReason;
        private final String balloonReason;
        private final String soundReason;
        private final String systemReason;

        public ReminderDecision(
            boolean showPopup,
            boolean showBalloon,
            boolean playSound,
            boolean showSystem,
            boolean updateStatusBar,
            String popupReason,
            String balloonReason,
            String soundReason,
            String systemReason
        ) {
            this.showPopup = showPopup;
            this.showBalloon = showBalloon;
            this.playSound = playSound;
            this.showSystem = showSystem;
            this.updateStatusBar = updateStatusBar;
            this.popupReason = popupReason;
            this.balloonReason = balloonReason;
            this.soundReason = soundReason;
            this.systemReason = systemReason;
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

        public boolean shouldShowSystem() {
            return showSystem;
        }

        public String getPopupReason() {
            return popupReason;
        }

        public String getBalloonReason() {
            return balloonReason;
        }

        public String getSoundReason() {
            return soundReason;
        }

        public String getSystemReason() {
            return systemReason;
        }
    }
}
