package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskChannel;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.google.gson.JsonObject;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证审批弹窗可见性事件会驱动 waiting_confirm 的提醒抑制状态切换。
 */
public class PermissionHandlerTest {

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

    @Test
    public void shouldSuppressReminderPopupBeforePlanApprovalDialogActuallyShows() {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        TaskStateService taskStateService = new TaskStateService();
        RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher();
        try {
            PermissionHandler handler = new PermissionHandler(
                createContext(),
                taskStateService,
                dispatcher
            );

            handler.showPlanApprovalDialog("req-pending", new com.google.gson.JsonObject());

            assertEquals(1, dispatcher.approvalDialogVisibleFlags.size());
            // 进入 WAITING_CONFIRM 时应立即按“审批弹窗已占位”来分发，避免先弹 reminder popup。
            assertTrue(dispatcher.approvalDialogVisibleFlags.get(0));
            assertEquals(TaskState.WAITING_CONFIRM, dispatcher.snapshots.get(0).getState());
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    @Test
    public void shouldPublishRemoteWaitingConfirmEventWhenPlanApprovalRequested() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        TaskStateService taskStateService = new TaskStateService();
        RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel();
        RemoteCollabService remoteCollabService = newRemoteCollabService();
        remoteCollabService.setTaskChannel(channel);
        JsonObject planData = new JsonObject();
        planData.addProperty("cwd", "E:/demo");
        planData.addProperty("title", "Review plan");

        try {
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
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    /**
     * 验证 waiting_confirm 远程事件在 payload 未提供 question/title 时，
     * 仍应优先复用统一任务摘要来源，而不是回退成内部原因串 plan_approval_requested。
     * 这样远程协作端看到的摘要才能和本地提醒、聊天区保持同一轮任务语义。
     */
    @Test
    public void shouldPreferSessionTaskSummaryForRemoteWaitingConfirmEventWhenPayloadSummaryMissing() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        TaskStateService taskStateService = new TaskStateService();
        RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel();
        RemoteCollabService remoteCollabService = newRemoteCollabService();
        remoteCollabService.setTaskChannel(channel);

        try {
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
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    /**
     * 验证计划审批通过后的远程事件也应继续复用统一任务摘要来源，
     * 避免把 plan_approval_approved 这种内部状态原因直接暴露给远程协作端。
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

    @Test
    public void shouldRegisterAskUserQuestionInRemoteRequestRegistryAndCompleteViaSharedPath() throws Exception {
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
    }

    @Test
    public void shouldRegisterPlanApprovalInRemoteRequestRegistryAndCompleteViaSharedPath() throws Exception {
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
    }

    private static HandlerContext createContext() {
        return createContext(null);
    }

    /**
     * 构造 PermissionHandler 测试上下文，并按需挂入会话对象。
     * 这样可以在远程事件测试里复用与真实链路一致的 session 摘要来源。
     *
     * @param session 当前测试场景需要挂载的会话；无会话时可传 null
     * @return 可供 PermissionHandler 使用的测试上下文
     */
    private static HandlerContext createContext(ClaudeSession session) {
        HandlerContext context = new HandlerContext(
            null,
            null,
            null,
            null,
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

    private static RemoteCollabService newRemoteCollabService() throws Exception {
        Constructor<RemoteCollabService> constructor = RemoteCollabService.class.getDeclaredConstructor(RemoteRequestRegistry.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new RemoteRequestRegistry());
    }

    /**
     * 构造带会话标题与最新用户问题的最小会话对象，
     * 用于验证 PermissionHandler 是否会复用统一任务摘要来源而不是内部 reason。
     *
     * @param summary 会话级摘要
     * @param latestUserMessage 最新一条用户任务描述
     * @return 带基础消息历史的会话
     */
    private static ClaudeSession sessionWithSummaryAndUserMessage(String summary, String latestUserMessage) {
        ClaudeSession session = new ClaudeSession(null, null, null);
        session.getState().setSummary(summary);
        session.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, latestUserMessage));
        return session;
    }

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
}
