package com.github.claudecodegui.taskstate;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 TaskReminderPolicy 的纯策略计算结果。
 * 这些测试不关心具体 UI，而是确认不同状态在不同焦点/弹窗条件下会走哪些渠道。
 */
public class TaskReminderPolicyTest {

    @Test
    public void shouldShowWaitingConfirmPopupWhenApprovalDialogNotOpen() {
        TaskReminderPolicy policy = TaskReminderPolicy.defaults();

        TaskReminderPolicy.ReminderDecision decision = policy.decide(
            snapshot(TaskState.WAITING_CONFIRM, "session-1", "req-1", "plan_approval_requested"),
            false,
            true
        );

        // 没有审批弹窗挡在前面时，等待确认应该允许 popup，并同步状态栏。
        assertTrue(decision.shouldShowPopup());
        assertFalse(decision.shouldShowBalloon());
        assertTrue(decision.shouldUpdateStatusBar());
    }

    @Test
    public void shouldSuppressWaitingConfirmPopupWhenApprovalDialogOpen() {
        TaskReminderPolicy policy = TaskReminderPolicy.defaults();

        TaskReminderPolicy.ReminderDecision decision = policy.decide(
            snapshot(TaskState.WAITING_CONFIRM, "session-1", "req-2", "plan_approval_requested"),
            true,
            true
        );

        // 同样是等待确认，但审批弹窗已打开时应压制 popup。
        assertFalse(decision.shouldShowPopup());
        assertTrue(decision.shouldUpdateStatusBar());
    }

    @Test
    public void shouldOnlyAllowCompletionBalloonAndSoundWhenIdeIsUnfocused() {
        TaskReminderPolicy policy = TaskReminderPolicy.defaults();
        TaskStateSnapshot completed = snapshot(TaskState.COMPLETED, "session-2", null, "send_completed");

        TaskReminderPolicy.ReminderDecision focused = policy.decide(completed, false, true);
        // IDE 在前台时，完成态不走气泡和声音。
        assertFalse(focused.shouldShowBalloon());
        assertFalse(focused.shouldPlaySound());

        TaskReminderPolicy.ReminderDecision unfocused = policy.decide(completed, false, false);
        // IDE 不在前台时，完成态允许更主动的提醒方式。
        assertTrue(unfocused.shouldShowBalloon());
        assertTrue(unfocused.shouldPlaySound());
        assertTrue(unfocused.shouldUpdateStatusBar());
    }

    @Test
    public void shouldShowFinalErrorPopupAndBalloonWhenIdeUnfocused() {
        TaskReminderPolicy policy = TaskReminderPolicy.defaults();

        TaskReminderPolicy.ReminderDecision decision = policy.decide(
            snapshot(TaskState.FINAL_ERROR, "session-3", "req-3", "plan_approval_timed_out"),
            false,
            false
        );

        // 最终失败属于高优先级提醒，在 IDE 非前台时应同时允许 popup 与 balloon。
        assertTrue(decision.shouldShowPopup());
        assertTrue(decision.shouldShowBalloon());
        assertFalse(decision.shouldPlaySound());
        assertTrue(decision.shouldUpdateStatusBar());
    }

    private static TaskStateSnapshot snapshot(TaskState state, String sessionId, String requestId, String reason) {
        return new TaskStateSnapshot(
            state,
            sessionId,
            requestId,
            new TaskStateEvent(state, sessionId, requestId, reason, System.currentTimeMillis())
        );
    }
}
