package com.github.claudecodegui.taskstate;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.notifications.ClaudeBalloonNotifier;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 TaskReminderDispatcher 的多渠道分发行为。
 * 重点覆盖 popup 抑制、去重、焦点门控以及 balloon/sound 分发边界。
 */
public class TaskReminderDispatcherTest {

    @Test
    public void shouldNotDispatchPopupWhenApprovalDialogAlreadyOpen() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, true);

        // 审批弹窗已经打开时，不应再次弹出 task reminder popup。
        dispatcher.dispatch(snapshot(TaskState.WAITING_CONFIRM, "session-1", "req-1", "plan_approval_requested"), true);

        assertEquals(0, context.executedJs.size());
    }

    @Test
    public void shouldDispatchPopupScriptWhenWaitingConfirmWithoutApprovalDialog() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, true);

        dispatcher.dispatch(snapshot(TaskState.WAITING_CONFIRM, "session-1", "req-2", "plan_approval_requested"), false);

        // 没有审批弹窗占位时，应向前端注入包含 state/requestId 的 popup 脚本。
        assertEquals(1, context.executedJs.size());
        String jsCode = context.executedJs.get(0);
        assertTrue(jsCode.contains("window.showTaskReminderDialog"));
        assertTrue(jsCode.contains("__pendingTaskReminderDialogRequests"));
        assertTrue(jsCode.contains("\"state\":\"waiting_confirm\""));
        assertTrue(jsCode.contains("\"requestId\":\"req-2\""));
    }

    @Test
    public void shouldDedupePopupForSameSnapshot() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, true);
        TaskStateSnapshot snapshot = snapshot(TaskState.WAITING_CONFIRM, "session-3", "req-3", "plan_approval_requested");

        dispatcher.dispatch(snapshot, false);
        dispatcher.dispatch(snapshot, false);

        // 同一个 snapshot 重复分发时，popup 只能出现一次。
        assertEquals(1, context.executedJs.size());
    }

    @Test
    public void shouldDedupeBalloonForSameSnapshot() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, false);
        TaskStateSnapshot snapshot = snapshot(TaskState.COMPLETED, "session-4", null, "send_completed");

        dispatcher.dispatch(snapshot, false);
        dispatcher.dispatch(snapshot, false);

        // balloon 需要去重，但声音按当前策略允许重复播放。
        assertEquals(1, balloonNotifier.callCount.get());
        assertEquals(2, soundCalls.get());
    }

    @Test
    public void shouldGateCompletedSoundAndBalloonByIdeFocus() {
        TaskStateSnapshot snapshot = snapshot(TaskState.COMPLETED, "session-5", null, "send_completed");

        CapturingHandlerContext focusedContext = new CapturingHandlerContext();
        RecordingBalloonNotifier focusedBalloon = new RecordingBalloonNotifier();
        AtomicInteger focusedSoundCalls = new AtomicInteger();
        TaskReminderDispatcher focusedDispatcher = createDispatcher(focusedContext, focusedBalloon, focusedSoundCalls, true);

        focusedDispatcher.dispatch(snapshot, false);

        // IDE 在前台时，完成态只需轻量更新状态栏，不再额外用气泡/声音打断用户。
        assertEquals(0, focusedBalloon.callCount.get());
        assertEquals(0, focusedSoundCalls.get());

        CapturingHandlerContext unfocusedContext = new CapturingHandlerContext();
        RecordingBalloonNotifier unfocusedBalloon = new RecordingBalloonNotifier();
        AtomicInteger unfocusedSoundCalls = new AtomicInteger();
        TaskReminderDispatcher unfocusedDispatcher = createDispatcher(unfocusedContext, unfocusedBalloon, unfocusedSoundCalls, false);

        unfocusedDispatcher.dispatch(snapshot, false);

        // IDE 不在前台时，完成态允许走气泡和声音提醒，帮助用户感知后台任务结束。
        assertEquals(1, unfocusedBalloon.callCount.get());
        assertEquals(1, unfocusedSoundCalls.get());
    }

    /**
     * 构造一个便于测试的 dispatcher，把气泡、声音和焦点判断都替换成可观测实现。
     */
    private static TaskReminderDispatcher createDispatcher(
        CapturingHandlerContext context,
        RecordingBalloonNotifier balloonNotifier,
        AtomicInteger soundCalls,
        boolean ideFocused
    ) {
        return new TaskReminderDispatcher(
            context,
            TaskReminderPolicy.defaults(),
            balloonNotifier,
            state -> soundCalls.incrementAndGet(),
            () -> ideFocused
        );
    }

    /**
     * 构造一个最小可用的任务快照，供各个测试场景复用。
     */
    private static TaskStateSnapshot snapshot(TaskState state, String sessionId, String requestId, String reason) {
        return new TaskStateSnapshot(
            state,
            sessionId,
            requestId,
            new TaskStateEvent(state, sessionId, requestId, reason, System.currentTimeMillis())
        );
    }

    /**
     * 捕获 executeJavaScriptOnEDT 的测试上下文。
     */
    private static class CapturingHandlerContext extends HandlerContext {
        private final List<String> executedJs = new ArrayList<>();

        CapturingHandlerContext() {
            super(
                null,
                null,
                null,
                null,
                new JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                }
            );
        }

        @Override
        public void executeJavaScriptOnEDT(String jsCode) {
            executedJs.add(jsCode);
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }
    }

    /**
     * 记录 balloon 调用次数的轻量桩实现。
     */
    private static class RecordingBalloonNotifier extends ClaudeBalloonNotifier {
        private final AtomicInteger callCount = new AtomicInteger();

        @Override
        public void showTaskReminder(Project project, TaskState state, String message) {
            callCount.incrementAndGet();
        }
    }
}
