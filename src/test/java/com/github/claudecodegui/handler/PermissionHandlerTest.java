package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.permission.PermissionService;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskChannel;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.google.gson.JsonObject;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PermissionHandler 合并回归测试。
 *
 * <p>这份测试同时覆盖两类契约：</p>
 * <p>1. 当前主线已经验证过的远程协作 / task reminder / 审批状态聚合语义。</p>
 * <p>2. upstream v0.4.3 新引入的 permission dialog timeout safety-net 配置能力。</p>
 *
 * <p>测试目标不是逐行复刻实现，而是锁定最容易在并轨中被冲掉的行为边界：
 * 支持的消息类型、审批可见性与提醒联动、远程请求注册与完成路径、会话切换清理、
 * 以及基于设置项的后端兜底超时策略。</p>
 */
public class PermissionHandlerTest {

    /**
     * 验证 handler 暴露给桥接层的 IPC 类型集合。
     * 这里除了基础的 permission / ask / plan 响应外，还要确保并轨后新增的
     * {@code plan_approval_dialog_visibility} 没有被遗漏，否则前端审批弹窗可见性
     * 无法回流到提醒抑制逻辑。
     */
    @Test
    public void shouldExposeAllSupportedIpcTypes() {
        PermissionHandler handler = new PermissionHandler(createContext());

        String[] actual = handler.getSupportedTypes().clone();
        String[] expected = {
            "permission_decision",
            "ask_user_question_response",
            "plan_approval_response",
            "plan_approval_dialog_visibility"
        };

        Arrays.sort(actual);
        Arrays.sort(expected);
        assertArrayEquals(expected, actual);
    }

    /**
     * 验证未知消息类型会显式返回 false。
     * 这是桥接层的分发契约：当前 handler 拒绝处理后，其它 handler 才有机会继续兜底。
     */
    @Test
    public void shouldReturnFalseForUnknownType() {
        PermissionHandler handler = new PermissionHandler(createContext());
        assertFalse(handler.handle("totally_unknown_type", "{}"));
    }

    /**
     * 验证审批弹窗可见性事件会驱动 waiting_confirm 的提醒抑制状态切换。
     * 这是当前主线为避免“审批弹窗 + task reminder popup”双重打扰而加的语义，
     * 并轨后必须继续保留。
     */
    @Test
    public void shouldDispatchReminderWhenPlanApprovalDialogBecomesVisible() {
        TaskStateService taskStateService = new TaskStateService();
        taskStateService.onPlanApprovalRequested("req-visible");
        RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher();
        PermissionHandler handler = new PermissionHandler(
            createContext(),
            taskStateService,
            dispatcher
        );

        boolean handled = handler.handle(
            "plan_approval_dialog_visibility",
            "{\"requestId\":\"req-visible\",\"visible\":true}"
        );

        assertTrue(handled);
        assertEquals(1, dispatcher.approvalDialogVisibleFlags.size());
        assertTrue(dispatcher.approvalDialogVisibleFlags.get(0));
        assertEquals(TaskState.WAITING_CONFIRM, dispatcher.snapshots.get(0).getState());
    }

    /**
     * 验证进入计划审批等待态时，应立即按“审批弹窗已占位”来分发提醒。
     * 这样即使真正的前端弹窗晚几十毫秒出现，也不会先闪出重复的 reminder popup。
     */
    @Test
    public void shouldSuppressReminderPopupBeforePlanApprovalDialogActuallyShows() throws Exception {
        runWithMockApplication(() -> {
            TaskStateService taskStateService = new TaskStateService();
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher();
            PermissionHandler handler = new PermissionHandler(
                createContext(),
                taskStateService,
                dispatcher
            );

            handler.showPlanApprovalDialog("req-pending", new JsonObject());

            assertEquals(1, dispatcher.approvalDialogVisibleFlags.size());
            assertTrue(dispatcher.approvalDialogVisibleFlags.get(0));
            assertEquals(TaskState.WAITING_CONFIRM, dispatcher.snapshots.get(0).getState());
        });
    }

    /**
     * 验证计划审批发起时，会同步向远程协作端发布 waiting_confirm 事件。
     * 这是手机端/远程端获得当前审批状态的入口，不能因为并轨引入的新超时配置而丢失。
     */
    @Test
    public void shouldPublishRemoteWaitingConfirmEventWhenPlanApprovalRequested() throws Exception {
        runWithMockApplication(() -> {
            TaskStateService taskStateService = new TaskStateService();
            RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel();
            RemoteCollabService remoteCollabService = newRemoteCollabService();
            remoteCollabService.setTaskChannel(channel);
            JsonObject planData = new JsonObject();
            planData.addProperty("cwd", "E:/demo");
            planData.addProperty("title", "Review plan");

            PermissionHandler handler = new PermissionHandler(
                createContext(),
                taskStateService,
                null,
                RemoteRequestRegistry.getGlobalInstance(),
                remoteCollabService
            );

            handler.showPlanApprovalDialog("req-remote-plan", planData);

            assertEquals(1, channel.events.size());
            assertEquals("waiting_confirm", channel.events.get(0).getTaskState());
            assertEquals("Review plan", channel.events.get(0).getSummary());
        });
    }

    /**
     * 验证 waiting_confirm 远程事件在 payload 未提供 title/question 时，
     * 会优先回退到当前会话统一任务摘要，而不是直接暴露内部 reason 串。
     */
    @Test
    public void shouldPreferSessionTaskSummaryForRemoteWaitingConfirmEventWhenPayloadSummaryMissing() throws Exception {
        runWithMockApplication(() -> {
            TaskStateService taskStateService = new TaskStateService();
            RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel();
            RemoteCollabService remoteCollabService = newRemoteCollabService();
            remoteCollabService.setTaskChannel(channel);

            PermissionHandler handler = new PermissionHandler(
                createContext(sessionWithSummaryAndUserMessage("Old session title", "Review the generated implementation plan")),
                taskStateService,
                null,
                RemoteRequestRegistry.getGlobalInstance(),
                remoteCollabService
            );

            handler.showPlanApprovalDialog("req-remote-summary", new JsonObject());

            assertEquals(1, channel.events.size());
            assertEquals("waiting_confirm", channel.events.get(0).getTaskState());
            assertEquals("Review the generated implementation plan", channel.events.get(0).getSummary());
        });
    }

    /**
     * 验证审批通过后的远程事件仍复用统一任务摘要来源。
     * 这能防止远程端直接看到内部状态 reason，而不是用户真正识别的任务标题。
     */
    @Test
    public void shouldPreferSessionTaskSummaryForRemoteApprovedEvent() throws Exception {
        TaskStateService taskStateService = new TaskStateService();
        RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel();
        RemoteCollabService remoteCollabService = newRemoteCollabService();
        remoteCollabService.setTaskChannel(channel);
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        JsonObject planData = new JsonObject();

        PermissionHandler handler = new PermissionHandler(
            createContext(sessionWithSummaryAndUserMessage("Old session title", "Approve the staged refactor plan")),
            taskStateService,
            null,
            registry,
            remoteCollabService
        );

        handler.showPlanApprovalDialog("req-remote-approved", planData);
        handler.handle(
            "plan_approval_response",
            "{\"requestId\":\"req-remote-approved\",\"approved\":true,\"targetMode\":\"acceptEdits\"}"
        );

        assertEquals(2, channel.events.size());
        assertEquals("running", channel.events.get(1).getTaskState());
        assertEquals("Approve the staged refactor plan", channel.events.get(1).getSummary());
    }

    /**
     * 验证 AskUserQuestion 会注册到统一的 RemoteRequestRegistry，
     * 并且前端回复后走共享 completion 路径收口。
     */
    @Test
    public void shouldRegisterAskUserQuestionInRemoteRequestRegistryAndCompleteViaSharedPath() throws Exception {
        runWithMockApplication(() -> {
            RemoteRequestRegistry registry = new RemoteRequestRegistry();
            PermissionHandler handler = new PermissionHandler(createContext(), null, null, registry);
            JsonObject questionsData = new JsonObject();
            questionsData.addProperty("cwd", "E:/demo");

            CompletableFuture<JsonObject> future = handler.showAskUserQuestionDialog("req-ask", questionsData);

            assertNotNull(registry.get("req-ask"));
            assertEquals(RemoteRequestType.ASK_USER_QUESTION, registry.get("req-ask").getRequestType());

            handler.handle("ask_user_question_response", "{\"requestId\":\"req-ask\",\"answers\":{\"question\":\"answer\"}}");

            JsonObject answers = future.get(1, TimeUnit.SECONDS);
            assertEquals("answer", answers.get("question").getAsString());
            assertNull(registry.get("req-ask"));
        });
    }

    /**
     * 验证 PlanApproval 会注册到统一的 RemoteRequestRegistry，
     * 并且前端审批结果能够回到共享 completion 路径。
     */
    @Test
    public void shouldRegisterPlanApprovalInRemoteRequestRegistryAndCompleteViaSharedPath() throws Exception {
        runWithMockApplication(() -> {
            RemoteRequestRegistry registry = new RemoteRequestRegistry();
            PermissionHandler handler = new PermissionHandler(createContext(), null, null, registry);
            JsonObject planData = new JsonObject();
            planData.addProperty("cwd", "E:/demo");

            CompletableFuture<JsonObject> future = handler.showPlanApprovalDialog("req-plan", planData);

            assertNotNull(registry.get("req-plan"));
            assertEquals(RemoteRequestType.PLAN_APPROVAL, registry.get("req-plan").getRequestType());

            handler.handle("plan_approval_response", "{\"requestId\":\"req-plan\",\"approved\":true,\"targetMode\":\"acceptEdits\"}");

            JsonObject result = future.get(1, TimeUnit.SECONDS);
            assertTrue(result.get("approved").getAsBoolean());
            assertEquals("acceptEdits", result.get("targetMode").getAsString());
            assertNull(registry.get("req-plan"));
        });
    }

    /**
     * 验证 permission_decision 会把本地 pending future 收敛成 ALLOW。
     * 这里直接反射注入本地 map，是为了只测 dispatch 本身，不依赖前端弹窗建立路径。
     */
    @Test
    public void shouldDispatchPermissionDecisionAndCompleteAllowFuture() throws Exception {
        PermissionHandler handler = new PermissionHandler(createContext());
        CompletableFuture<Integer> future = new CompletableFuture<>();
        injectPermissionFuture(handler, "ch-allow", future);

        String content = "{\"channelId\":\"ch-allow\",\"allow\":true,\"remember\":false}";
        assertTrue(handler.handle("permission_decision", content));

        Integer result = future.get(2, TimeUnit.SECONDS);
        assertEquals(PermissionService.PermissionResponse.ALLOW.getValue(), result.intValue());
        assertTrue(getPermissionMap(handler).isEmpty());
    }

    /**
     * 验证 remember=true 会被映射成 ALLOW_ALWAYS。
     * 这是权限记忆策略最直接的桥接契约，不能因为并轨引入新分支后被改回普通 ALLOW。
     */
    @Test
    public void shouldDispatchPermissionDecisionAndCompleteAllowAlwaysFuture() throws Exception {
        PermissionHandler handler = new PermissionHandler(createContext());
        CompletableFuture<Integer> future = new CompletableFuture<>();
        injectPermissionFuture(handler, "ch-allow-always", future);

        String content = "{\"channelId\":\"ch-allow-always\",\"allow\":true,\"remember\":true}";
        assertTrue(handler.handle("permission_decision", content));

        Integer result = future.get(2, TimeUnit.SECONDS);
        assertEquals(PermissionService.PermissionResponse.ALLOW_ALWAYS.getValue(), result.intValue());
    }

    /**
     * 验证会话切换时，所有本地 pending permission future 都会被兜底拒绝。
     * 这能防止旧会话弹窗残留，把新会话链路卡死在等待态。
     */
    @Test
    public void shouldCompleteAllPermissionFuturesWithDenyWhenClearingPendingRequests() throws Exception {
        PermissionHandler handler = new PermissionHandler(createContext());
        CompletableFuture<Integer> f1 = new CompletableFuture<>();
        CompletableFuture<Integer> f2 = new CompletableFuture<>();
        injectPermissionFuture(handler, "ch-1", f1);
        injectPermissionFuture(handler, "ch-2", f2);

        handler.clearPendingRequests();

        assertEquals(PermissionService.PermissionResponse.DENY.getValue(), f1.get(1, TimeUnit.SECONDS).intValue());
        assertEquals(PermissionService.PermissionResponse.DENY.getValue(), f2.get(1, TimeUnit.SECONDS).intValue());
        assertTrue(getPermissionMap(handler).isEmpty());
    }

    /**
     * 验证会话切换时，AskUserQuestion 的 future 会通过 RemoteRequestRegistry 收口成空对象。
     * 当前实现用空对象表达“没有回答”，这和直接返回 null 的旧实现不同，因此需要固定住。
     */
    @Test
    public void shouldCompleteAskUserQuestionFutureWithEmptyJsonWhenClearingPendingRequests() throws Exception {
        runWithMockApplication(() -> {
            RemoteRequestRegistry registry = new RemoteRequestRegistry();
            PermissionHandler handler = new PermissionHandler(createContext(), null, null, registry);
            JsonObject questionsData = new JsonObject();
            questionsData.addProperty("cwd", "E:/demo");

            CompletableFuture<JsonObject> future = handler.showAskUserQuestionDialog("req-clear-ask", questionsData);
            handler.clearPendingRequests();

            JsonObject result = future.get(1, TimeUnit.SECONDS);
            assertNotNull(result);
            assertEquals(0, result.size());
            assertNull(registry.get("req-clear-ask"));
        });
    }

    /**
     * 验证会话切换时，PlanApproval 的 future 会被兜底拒绝并带上统一文案。
     * 这样前端、远程协作端和后端状态机对“会话已切换导致审批失效”的认知保持一致。
     */
    @Test
    public void shouldCompletePlanApprovalFutureWithRejectionWhenClearingPendingRequests() throws Exception {
        runWithMockApplication(() -> {
            RemoteRequestRegistry registry = new RemoteRequestRegistry();
            PermissionHandler handler = new PermissionHandler(createContext(), null, null, registry);
            JsonObject planData = new JsonObject();
            planData.addProperty("cwd", "E:/demo");

            CompletableFuture<JsonObject> future = handler.showPlanApprovalDialog("req-clear-plan", planData);
            handler.clearPendingRequests();

            JsonObject result = future.get(1, TimeUnit.SECONDS);
            assertNotNull(result);
            assertFalse(result.get("approved").getAsBoolean());
            assertEquals("Session changed", result.get("message").getAsString());
            assertNull(registry.get("req-clear-plan"));
        });
    }

    /**
     * 验证 safety-net 超时秒数会读取设置项，并叠加 buffer。
     * 这里固定住的是“配置超时 + buffer”的契约，而不是某个硬编码秒数常量。
     */
    @Test
    public void shouldUseConfiguredDialogTimeoutPlusBufferForSafetyNet() {
        PermissionHandler handler = new PermissionHandler(createContext(null, new FakeSettingsService(120)));
        long expected = 120L + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        assertEquals(expected, handler.getSafetyNetTimeoutSeconds());
    }

    /**
     * 验证当 settingsService 缺失时，safety-net 会回退到默认超时而不是退成极大值。
     * 否则一旦上下文缺少设置服务，就可能把一次异常等待放大成近乎“永远不结束”。
     */
    @Test
    public void shouldFallBackToDefaultTimeoutPlusBufferWhenSettingsServiceIsNull() {
        PermissionHandler handler = new PermissionHandler(createContext());
        long expected = CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
            + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        assertEquals(expected, handler.getSafetyNetTimeoutSeconds());
    }

    /**
     * 验证当读取设置项抛错时，safety-net 会回退到默认超时。
     * 这是防止配置文件损坏或 IO 异常直接把权限弹窗链路卡死的最后一道保护。
     */
    @Test
    public void shouldFallBackToDefaultTimeoutPlusBufferWhenSettingsServiceThrows() {
        PermissionHandler handler = new PermissionHandler(createContext(null, new FailingSettingsService()));
        long expected = CodemossSettingsService.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS
            + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS;
        assertEquals(expected, handler.getSafetyNetTimeoutSeconds());
    }

    /**
     * 验证 future 在超时前已完成时，safety-net 调度任务会被取消。
     * 这能避免同一请求在正常 completion 后，又被后端超时线程再次误处理。
     */
    @Test
    public void shouldCancelScheduledSafetyNetWhenFutureCompletesFirst() {
        FakeSafetyNetScheduler scheduler = new FakeSafetyNetScheduler();
        PermissionHandler handler = new PermissionHandler(
            createContext(null, new FakeSettingsService(120)),
            null,
            null,
            new RemoteRequestRegistry(),
            RemoteCollabService.getInstance(),
            scheduler
        );
        CompletableFuture<Integer> future = new CompletableFuture<>();

        handler.scheduleSafetyNet(future, () -> future.complete(42));

        assertEquals(120L + CodemossSettingsService.PERMISSION_SAFETY_NET_BUFFER_SECONDS, scheduler.lastDelaySeconds);
        assertFalse(scheduler.task.cancelled);

        future.complete(7);

        assertTrue(scheduler.task.cancelled);
        assertEquals(Integer.valueOf(7), future.join());
    }

    /**
     * 验证当 safety-net 赢得竞争时，future 会按兜底结果完成。
     * 这条测试固定住 `scheduleSafetyNet()` 的最低保证：即使前端完全失联，
     * 后端仍能结束这次请求。
     */
    @Test
    public void shouldLetSafetyNetCompleteFutureWhenTimeoutWinsRace() {
        FakeSafetyNetScheduler scheduler = new FakeSafetyNetScheduler();
        PermissionHandler handler = new PermissionHandler(
            createContext(null, new FakeSettingsService(30)),
            null,
            null,
            new RemoteRequestRegistry(),
            RemoteCollabService.getInstance(),
            scheduler
        );
        CompletableFuture<Integer> future = new CompletableFuture<>();

        handler.scheduleSafetyNet(future, () -> future.complete(42));
        scheduler.runnable.run();

        assertEquals(Integer.valueOf(42), future.join());
        assertTrue(scheduler.task.cancelled);
    }

    /**
     * 构造 PermissionHandler 测试上下文，并按需挂入会话对象与设置服务。
     * 这样同一套辅助方法既能服务远程协作摘要回归，也能服务 safety-net 配置回归。
     *
     * @param session 当前测试场景需要挂载的会话；无会话时可传 null
     * @param settingsService 当前测试场景需要挂载的设置服务；无设置时可传 null
     * @return 可供 PermissionHandler 使用的测试上下文
     */
    private static HandlerContext createContext(ClaudeSession session, CodemossSettingsService settingsService) {
        HandlerContext context = new HandlerContext(
            null,
            null,
            null,
            settingsService,
            new HandlerContext.JsCallback() {
                @Override
                public void callJavaScript(String functionName, String... args) {
                }

                @Override
                public String escapeJs(String str) {
                    return str;
                }
            }
        );
        context.setSession(session);
        return context;
    }

    /**
     * 构造无会话、无设置的默认测试上下文。
     *
     * @return 默认测试上下文
     */
    private static HandlerContext createContext() {
        return createContext(null, null);
    }

    /**
     * 构造仅带会话对象的测试上下文。
     *
     * @param session 当前测试场景需要挂载的会话
     * @return 含会话的测试上下文
     */
    private static HandlerContext createContext(ClaudeSession session) {
        return createContext(session, null);
    }

    /**
     * 创建新的远程协作服务实例，并为当前测试隔离 request registry。
     * 这样可以避免共享单例 registry 污染其它用例。
     *
     * @return 隔离后的 RemoteCollabService
     * @throws Exception 反射构造失败时抛出
     */
    private static RemoteCollabService newRemoteCollabService() throws Exception {
        Constructor<RemoteCollabService> constructor = RemoteCollabService.class.getDeclaredConstructor(RemoteRequestRegistry.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new RemoteRequestRegistry());
    }

    /**
     * 构造带会话标题与最新用户任务描述的最小会话对象。
     * 用于验证 PermissionHandler 在远程端展示时，会优先复用统一任务摘要来源。
     *
     * @param summary 会话级摘要
     * @param latestUserMessage 最新一条用户任务描述
     * @return 带基础消息历史的最小会话
     */
    private static ClaudeSession sessionWithSummaryAndUserMessage(String summary, String latestUserMessage) {
        ClaudeSession session = new ClaudeSession(null, null, null);
        session.getState().setSummary(summary);
        session.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, latestUserMessage));
        return session;
    }

    /**
     * 为需要 IntelliJ Application 上下文的测试场景补一个临时 MockApplication。
     * AskUser / PlanApproval 展示路径会走 {@code ApplicationManager.getApplication().invokeLater(...)},
     * 没有应用实例时会直接走异常分支，导致无法验证真正的注册与清理行为。
     *
     * @param action 需要在 mock application 环境下执行的测试逻辑
     * @throws Exception 用例内部抛出的异常
     */
    private static void runWithMockApplication(ThrowingRunnable action) throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }
        try {
            action.run();
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    /**
     * 反射读取本地 permission future map。
     * 仅用于验证 permission_decision 与 clearPendingRequests 的本地 pending 收口契约。
     *
     * @param handler 被测 handler
     * @return 本地 permission future map
     * @throws Exception 反射失败时抛出
     */
    @SuppressWarnings("unchecked")
    private static Map<String, CompletableFuture<Integer>> getPermissionMap(PermissionHandler handler) throws Exception {
        Field field = PermissionHandler.class.getDeclaredField("pendingPermissionRequests");
        field.setAccessible(true);
        return (Map<String, CompletableFuture<Integer>>) field.get(handler);
    }

    /**
     * 往本地 permission future map 注入测试数据。
     *
     * @param handler 被测 handler
     * @param key 权限请求 channelId
     * @param future 待完成的测试 future
     * @throws Exception 反射失败时抛出
     */
    private static void injectPermissionFuture(PermissionHandler handler, String key, CompletableFuture<Integer> future) throws Exception {
        getPermissionMap(handler).put(key, future);
    }

    /**
     * 供 runWithMockApplication 使用的可抛异常函数接口。
     * 这样测试逻辑内部可以直接抛 checked exception，不需要在 lambda 里额外包一层 RuntimeException。
     */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * 记录 task reminder 分发结果，用于断言审批弹窗可见性与 WAITING_CONFIRM 的联动。
     */
    private static class RecordingTaskReminderDispatcher extends TaskReminderDispatcher {
        private final List<Boolean> approvalDialogVisibleFlags = new ArrayList<>();
        private final List<TaskStateSnapshot> snapshots = new ArrayList<>();

        RecordingTaskReminderDispatcher() {
            super(createContext());
        }

        @Override
        public void dispatch(TaskStateSnapshot snapshot, boolean approvalDialogVisible) {
            approvalDialogVisibleFlags.add(approvalDialogVisible);
            snapshots.add(snapshot);
        }
    }

    /**
     * 记录远程任务事件，用于断言 waiting_confirm / running 等状态是否按预期同步出去。
     */
    private static class RecordingRemoteTaskChannel implements RemoteTaskChannel {
        private final List<RemoteTaskEvent> events = new ArrayList<>();

        @Override
        public String getChannelId() {
            return "test-telegram";
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return RemoteConnectionStatus.CONNECTED;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
            events.add(event);
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
        }
    }

    /**
     * 可控的 settingsService 替身。
     * 用于验证 PermissionHandler 是否会把用户配置的权限超时读入 safety-net。
     */
    private static class FakeSettingsService extends CodemossSettingsService {
        private final int timeoutSeconds;

        FakeSettingsService(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        @Override
        public int getPermissionDialogTimeoutSeconds() throws IOException {
            return timeoutSeconds;
        }
    }

    /**
     * 始终抛错的 settingsService 替身。
     * 用于验证读取配置失败时，PermissionHandler 是否会回退到默认超时。
     */
    private static class FailingSettingsService extends CodemossSettingsService {
        @Override
        public int getPermissionDialogTimeoutSeconds() throws IOException {
            throw new IOException("simulated settings read failure");
        }
    }

    /**
     * 可控的 safety-net 调度器替身。
     * 它不会真的等待秒数，而是把 runnable 和 delay 记录下来，供测试手动触发。
     */
    private static class FakeSafetyNetScheduler implements PermissionHandler.SafetyNetScheduler {
        private Runnable runnable;
        private long lastDelaySeconds;
        private FakeCancellableTask task;

        @Override
        public PermissionHandler.CancellableTask schedule(Runnable task, long delaySeconds) {
            this.runnable = task;
            this.lastDelaySeconds = delaySeconds;
            this.task = new FakeCancellableTask();
            return this.task;
        }
    }

    /**
     * 记录 cancel() 是否被调用的可取消任务替身。
     * 用于验证 future 正常完成后，后端 safety-net 是否会被及时撤销。
     */
    private static class FakeCancellableTask implements PermissionHandler.CancellableTask {
        private boolean cancelled;

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
