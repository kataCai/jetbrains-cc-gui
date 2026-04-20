package com.github.claudecodegui.taskstate;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.notifications.ClaudeBalloonNotifier;
import com.github.claudecodegui.notifications.SystemReminderNotifier;
import com.github.claudecodegui.notifications.TaskReminderNotificationPayload;
import com.github.claudecodegui.session.ClaudeSession;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
    public void shouldIncludeSessionSummaryInPopupPayload() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        context.setSession(sessionWithSummary("Fix task reminder navigation"));
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, true);

        dispatcher.dispatch(snapshot(TaskState.WAITING_CONFIRM, "session-1", "req-summary", "plan_approval_requested"), false);

        assertEquals(1, context.executedJs.size());
        String jsCode = context.executedJs.get(0);
        assertTrue(jsCode.contains("\"taskSummary\":\"Fix task reminder navigation\""));
        assertTrue(jsCode.contains("\"message\":\"Fix task reminder navigation\""));
    }

    @Test
    public void shouldPreferLatestUserMessageOverExistingSessionSummaryInPopupPayload() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        context.setSession(sessionWithSummaryAndUserMessage(
            "Old session title",
            "Implement current task summary for this notification"
        ));
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, true);

        dispatcher.dispatch(snapshot(TaskState.WAITING_CONFIRM, "session-1", "req-current", "plan_approval_requested"), false);

        assertEquals(1, context.executedJs.size());
        String jsCode = context.executedJs.get(0);
        assertTrue(jsCode.contains("\"taskSummary\":\"Implement current task summary for this notification\""));
        assertTrue(jsCode.contains("\"message\":\"Implement current task summary for this notification\""));
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

    @Test
    public void shouldUseInjectedReminderMessageResolver() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = new TaskReminderDispatcher(
            context,
            TaskReminderPolicy.defaults(),
            balloonNotifier,
            state -> soundCalls.incrementAndGet(),
            () -> true,
            snapshot -> "需要确认后才能继续。"
        );

        dispatcher.dispatch(
            snapshot(TaskState.WAITING_CONFIRM, "session-6", "req-6", "plan_approval_requested"),
            false
        );

        // Dispatcher 应真正使用注入的文案解析器，避免把提醒文案写死在分发流程里。
        assertEquals(1, context.executedJs.size());
        assertTrue(context.executedJs.get(0).contains("需要确认后才能继续。"));
    }

    @Test
    public void shouldResolveLatestPolicyOnEachDispatch() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        AtomicReference<TaskReminderPolicy> policyRef = new AtomicReference<>(TaskReminderPolicy.defaults());
        TaskReminderDispatcher dispatcher = new TaskReminderDispatcher(
            context,
            policyRef::get,
            balloonNotifier,
            state -> soundCalls.incrementAndGet(),
            () -> true
        );

        TaskStateSnapshot completed = snapshot(TaskState.COMPLETED, "session-7", "req-7", "send_completed");
        dispatcher.dispatch(completed, false);
        assertEquals(0, soundCalls.get());

        policyRef.set(
            new TaskReminderPolicy(
                java.util.EnumSet.noneOf(TaskState.class),
                java.util.EnumSet.noneOf(TaskState.class),
                java.util.EnumSet.of(TaskState.COMPLETED),
                java.util.EnumSet.of(TaskState.COMPLETED),
                false,
                true,
                true,
                false
            )
        );

        dispatcher.dispatch(
            snapshot(TaskState.COMPLETED, "session-7", "req-8", "send_completed"),
            false
        );

        // 第二次分发应读取到最新策略，使 IDE 前台下的完成态也能播放声音。
        assertEquals(1, soundCalls.get());
    }

    @Test
    public void shouldDispatchPopupPreviewRequestForSettingsSelfCheck() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, true);

        dispatcher.dispatchTestPopup("Preview popup message");

        assertEquals(1, context.executedJs.size());
        assertTrue(context.executedJs.get(0).contains("\"state\":\"waiting_confirm\""));
        assertTrue(context.executedJs.get(0).contains("Preview popup message"));
        assertEquals(0, balloonNotifier.callCount.get());
        assertEquals(0, soundCalls.get());
    }

    @Test
    public void shouldDispatchBalloonPreviewForSettingsSelfCheck() {
        CapturingHandlerContext context = new CapturingHandlerContext(true);
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderDispatcher dispatcher = createDispatcher(context, balloonNotifier, soundCalls, true);

        dispatcher.dispatchTestBalloon("Preview balloon message");

        assertEquals(1, balloonNotifier.callCount.get());
        assertEquals("Preview balloon message", balloonNotifier.messages.get(0));
        assertEquals(0, context.executedJs.size());
        assertEquals(0, soundCalls.get());
    }


    @Test
    public void shouldDispatchSystemReminderWhenPolicyAllowsAndIdeIsUnfocused() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        context.setSession(sessionWithSummary("Summarize completed task"));
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        RecordingSystemReminderNotifier systemNotifier = new RecordingSystemReminderNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderPolicy policy = new TaskReminderPolicy(
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.of(TaskState.COMPLETED),
            java.util.EnumSet.noneOf(TaskState.class),
            false,
            true,
            true,
            true,
            true
        );
        TaskReminderDispatcher dispatcher = new TaskReminderDispatcher(
            context,
            policy,
            balloonNotifier,
            systemNotifier,
            state -> soundCalls.incrementAndGet(),
            () -> false
        );

        dispatcher.dispatch(snapshot(TaskState.COMPLETED, "session-system", "req-system", "send_completed"), false);

        assertEquals(1, systemNotifier.callCount.get());
        assertEquals(0, balloonNotifier.callCount.get());
        assertEquals(0, soundCalls.get());
        assertEquals("Summarize completed task", systemNotifier.messages.get(0));
    }

    @Test
    public void shouldPreferLatestUserMessageForSystemReminderPayload() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        context.setSession(sessionWithSummaryAndUserMessage(
            "Old session title",
            "Summarize the latest task state change"
        ));
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        RecordingSystemReminderNotifier systemNotifier = new RecordingSystemReminderNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderPolicy policy = new TaskReminderPolicy(
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.of(TaskState.COMPLETED),
            java.util.EnumSet.noneOf(TaskState.class),
            false,
            true,
            true,
            true,
            true
        );
        TaskReminderDispatcher dispatcher = new TaskReminderDispatcher(
            context,
            policy,
            balloonNotifier,
            systemNotifier,
            state -> soundCalls.incrementAndGet(),
            () -> false
        );

        dispatcher.dispatch(snapshot(TaskState.COMPLETED, "session-system", "req-latest", "send_completed"), false);

        assertEquals(1, systemNotifier.callCount.get());
        assertEquals("Summarize the latest task state change", systemNotifier.messages.get(0));
    }

    @Test
    public void shouldKeepSameTaskSummaryAcrossStateChangesWithinSameRound() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        ClaudeSession session = sessionWithSummaryAndUserMessage(
            "Old session title",
            "Fix the current reminder summary behavior"
        );
        context.setSession(session);
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        RecordingSystemReminderNotifier systemNotifier = new RecordingSystemReminderNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderPolicy policy = new TaskReminderPolicy(
            java.util.EnumSet.of(TaskState.WAITING_CONFIRM),
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.of(TaskState.COMPLETED),
            java.util.EnumSet.of(TaskState.COMPLETED),
            java.util.EnumSet.noneOf(TaskState.class),
            false,
            true,
            true,
            true,
            true
        );
        TaskReminderDispatcher dispatcher = new TaskReminderDispatcher(
            context,
            policy,
            balloonNotifier,
            systemNotifier,
            state -> soundCalls.incrementAndGet(),
            () -> false
        );

        dispatcher.dispatch(snapshot(TaskState.WAITING_CONFIRM, "session-round", "req-round", "plan_approval_requested"), false);
        session.getState().addMessage(new ClaudeSession.Message(
            ClaudeSession.Message.Type.USER,
            "Prepare the next unrelated task"
        ));

        dispatcher.dispatch(snapshot(TaskState.COMPLETED, "session-round", null, "send_completed"), false);

        assertEquals(1, context.executedJs.size());
        String popupJsCode = context.executedJs.get(0);
        assertTrue(popupJsCode.contains("\"taskSummary\":\"Fix the current reminder summary behavior\""));
        assertEquals(1, systemNotifier.callCount.get());
        assertEquals("Fix the current reminder summary behavior", systemNotifier.messages.get(0));
    }

    @Test
    public void shouldDedupeSystemReminderForSameSnapshot() {
        CapturingHandlerContext context = new CapturingHandlerContext();
        RecordingBalloonNotifier balloonNotifier = new RecordingBalloonNotifier();
        RecordingSystemReminderNotifier systemNotifier = new RecordingSystemReminderNotifier();
        AtomicInteger soundCalls = new AtomicInteger();
        TaskReminderPolicy policy = new TaskReminderPolicy(
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.noneOf(TaskState.class),
            java.util.EnumSet.of(TaskState.COMPLETED),
            java.util.EnumSet.noneOf(TaskState.class),
            false,
            true,
            true,
            true,
            true
        );
        TaskReminderDispatcher dispatcher = new TaskReminderDispatcher(
            context,
            policy,
            balloonNotifier,
            systemNotifier,
            state -> soundCalls.incrementAndGet(),
            () -> false
        );
        TaskStateSnapshot snapshot = snapshot(TaskState.COMPLETED, "session-system", "req-dedup", "send_completed");

        dispatcher.dispatch(snapshot, false);
        dispatcher.dispatch(snapshot, false);

        assertEquals(1, systemNotifier.callCount.get());
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
        return createDispatcher(
            context,
            balloonNotifier,
            new RecordingSystemReminderNotifier(),
            soundCalls,
            ideFocused
        );
    }

    private static TaskReminderDispatcher createDispatcher(
        CapturingHandlerContext context,
        RecordingBalloonNotifier balloonNotifier,
        RecordingSystemReminderNotifier systemNotifier,
        AtomicInteger soundCalls,
        boolean ideFocused
    ) {
        return new TaskReminderDispatcher(
            context,
            TaskReminderPolicy.defaults(),
            balloonNotifier,
            systemNotifier,
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

    private static ClaudeSession sessionWithSummary(String summary) {
        ClaudeSession session = new ClaudeSession(null, null, null);
        session.getState().setSummary(summary);
        return session;
    }

    private static ClaudeSession sessionWithSummaryAndUserMessage(String summary, String latestUserMessage) {
        ClaudeSession session = sessionWithSummary(summary);
        session.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, latestUserMessage));
        return session;
    }

    /**
     * 捕获 executeJavaScriptOnEDT 的测试上下文。
     */
    private static class CapturingHandlerContext extends HandlerContext {
        private final List<String> executedJs = new ArrayList<>();
        private final Project project;

        CapturingHandlerContext() {
            this(false);
        }

        CapturingHandlerContext(boolean provideProject) {
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
            this.project = provideProject
                ? (Project) java.lang.reflect.Proxy.newProxyInstance(
                    Project.class.getClassLoader(),
                    new Class[]{Project.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isDisposed" -> false;
                        case "getName" -> "task-reminder-test";
                        default -> method.getReturnType().isPrimitive() ? defaultPrimitiveValue(method.getReturnType()) : null;
                    }
                )
                : null;
        }

        @Override
        public void executeJavaScriptOnEDT(String jsCode) {
            executedJs.add(jsCode);
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }

        @Override
        public Project getProject() {
            return project;
        }

        private static Object defaultPrimitiveValue(Class<?> primitiveType) {
            if (primitiveType == boolean.class) {
                return false;
            }
            if (primitiveType == char.class) {
                return '\0';
            }
            return 0;
        }
    }

    /**
     * 记录 balloon 调用次数的轻量桩实现。
     */
    private static class RecordingBalloonNotifier extends ClaudeBalloonNotifier {
        private final AtomicInteger callCount = new AtomicInteger();
        private final List<String> messages = new ArrayList<>();

        @Override
        public void showTaskReminder(Project project, TaskState state, String message) {
            record(message);
        }

        @Override
        public void showTaskReminder(Project project, TaskReminderNotificationPayload payload) {
            record(payload != null ? payload.getMessage() : null);
        }

        private void record(String message) {
            callCount.incrementAndGet();
            messages.add(message);
        }
    }

    /**
     * ?? system reminder ??????? dispatcher ??????????????
     */
    private static class RecordingSystemReminderNotifier extends SystemReminderNotifier {
        private final AtomicInteger callCount = new AtomicInteger();
        private final List<String> messages = new ArrayList<>();

        RecordingSystemReminderNotifier() {
            super();
        }

        @Override
        public void showTaskReminder(Project project, TaskState state, String message) {
            record(message);
        }

        @Override
        public void showTaskReminder(Project project, TaskReminderNotificationPayload payload) {
            record(payload != null ? payload.getMessage() : null);
        }

        private void record(String message) {
            callCount.incrementAndGet();
            messages.add(message);
        }
    }
}
