package com.github.claudecodegui.taskstate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 验证 TaskStateService 的状态聚合与迁移逻辑。
 * 重点确保发送、审批通过/拒绝/超时这些事件能被收敛成正确快照。
 */
public class TaskStateServiceTest {

    @Test
    public void shouldTransitionFromRunningToWaitingConfirmAndBack() {
        TaskStateService service = new TaskStateService();

        // 一次完整的“发送 -> 等待审批 -> 审批通过 -> 恢复运行”链路。
        service.onSendStarted("session-1");
        assertEquals(TaskState.RUNNING, service.getCurrentSnapshot().getState());

        service.onPlanApprovalRequested("req-1");
        assertEquals(TaskState.WAITING_CONFIRM, service.getCurrentSnapshot().getState());

        service.onPlanApprovalApproved("req-1");
        // 审批通过后应恢复到 RUNNING，并继续沿用之前的 sessionId。
        TaskStateSnapshot snapshot = service.getCurrentSnapshot();
        assertEquals(TaskState.RUNNING, snapshot.getState());
        assertEquals("session-1", snapshot.getSessionId());
    }

    @Test
    public void shouldMarkCancelledWhenPlanApprovalRejected() {
        TaskStateService service = new TaskStateService();

        service.onSendStarted("session-1");
        service.onPlanApprovalRequested("req-2");
        service.onPlanApprovalRejected("req-2", "user_rejected");

        // 审批拒绝后应进入 CANCELLED，并保留 requestId 便于提醒层定位上下文。
        TaskStateSnapshot snapshot = service.getCurrentSnapshot();
        assertEquals(TaskState.CANCELLED, snapshot.getState());
        assertEquals("req-2", snapshot.getRequestId());
        assertEquals("user_rejected", snapshot.getLatestEvent().getReason());
    }

    @Test
    public void shouldMarkFinalErrorWhenPlanApprovalTimesOut() {
        TaskStateService service = new TaskStateService();

        service.onSendStarted("session-1");
        service.onPlanApprovalRequested("req-timeout");
        service.onPlanApprovalTimedOut("req-timeout");

        // 审批超时是最终错误，需要记录时间戳供去重与提醒文案使用。
        TaskStateSnapshot snapshot = service.getCurrentSnapshot();
        assertEquals(TaskState.FINAL_ERROR, snapshot.getState());
        assertEquals("req-timeout", snapshot.getRequestId());
        assertNotNull(snapshot.getLatestEvent().getTimestamp());
    }
}
