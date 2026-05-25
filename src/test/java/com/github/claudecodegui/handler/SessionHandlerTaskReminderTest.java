package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteTaskChannel;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证发送开始时的任务提醒摘要要跟随当前输入，
 * 不能因为提醒触发早于 session.send 而落后一轮。
 */
public class SessionHandlerTaskReminderTest {

    @Test
    public void shouldUseCurrentPromptForRunningReminderOnFirstDispatch() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-reminder-test");
        try {
            RecordingClaudeSession session = new RecordingClaudeSession(createProject(projectDir));
            session.setSessionInfo("session-current", projectDir.toString());
            session.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "5+5=?"));

            HandlerContext context = createContext(projectDir, session);
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher(context);
            TaskStateService taskStateService = new TaskStateService();
            SessionHandler handler = new SessionHandler(context, taskStateService, dispatcher);

            boolean handled = handler.handle("send_message", "{\"text\":\"1+1=\"}");

            assertTrue(handled);
            assertTrue("first dispatch timed out", dispatcher.awaitFirstDispatch());
            assertEquals(TaskState.RUNNING, dispatcher.states.get(0));
            assertEquals("1+1=", dispatcher.latestUserMessagesAtDispatch.get(0));
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    @Test
    public void shouldPassCurrentPromptToReminderDispatcherWhenSessionHistoryStillPointsToPreviousTurn() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-reminder-stale-history-test");
        try {
            RecordingClaudeSession session = new RecordingClaudeSession(createProject(projectDir));
            session.setSessionInfo("session-current", projectDir.toString());
            session.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "2+2=?"));
            session.addUserMessageDuringSend = false;

            HandlerContext context = createContext(projectDir, session);
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher(context);
            TaskStateService taskStateService = new TaskStateService();
            SessionHandler handler = new SessionHandler(context, taskStateService, dispatcher);

            boolean handled = handler.handle("send_message", "{\"text\":\"1+1 =\"}");

            assertTrue(handled);
            assertTrue("first dispatch timed out", dispatcher.awaitFirstDispatch());
            assertEquals(TaskState.RUNNING, dispatcher.states.get(0));
            assertEquals("2+2=?", dispatcher.latestUserMessagesAtDispatch.get(0));
            assertEquals("1+1 =", dispatcher.preferredTaskSummaries.get(0));
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    @Test
    public void shouldPublishRemoteTaskEventsForSendLifecycle() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-remote-event-test");
        try {
            RecordingClaudeSession session = new RecordingClaudeSession(createProject(projectDir));
            session.setSessionInfo("session-remote", projectDir.toString());

            HandlerContext context = createContext(projectDir, session);
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher(context);
            TaskStateService taskStateService = new TaskStateService();
            RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel(2);
            RemoteCollabService remoteCollabService = newRemoteCollabService();
            remoteCollabService.setTaskChannel(channel);
            SessionHandler handler = new SessionHandler(context, taskStateService, dispatcher, remoteCollabService);

            boolean handled = handler.handle("send_message", "{\"text\":\"3+4 = ?\"}");

            assertTrue(handled);
            assertTrue("remote task events timed out", channel.awaitEvents());
            assertEquals(2, channel.events.size());
            assertEquals("running", channel.events.get(0).getTaskState());
            assertEquals("3+4 = ?", channel.events.get(0).getSummary());
            assertEquals("completed", channel.events.get(1).getTaskState());
            assertEquals("3+4 = ?", channel.events.get(1).getSummary());
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    @Test
    public void shouldEmitRecoveredBeforeCompletedWhenProviderMarksRecovered() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-recovered-test");
        try {
            RecordingClaudeSession session = new RecordingClaudeSession(createProject(projectDir));
            session.setSessionInfo("session-recovered", projectDir.toString());
            session.getState().setLastRecoveryMetadata(
                true,
                "runtime_terminated_after_success",
                "promote_to_completed"
            );

            HandlerContext context = createContext(projectDir, session);
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher(context);
            TaskStateService taskStateService = new TaskStateService();
            SessionHandler handler = new SessionHandler(context, taskStateService, dispatcher);

            java.lang.reflect.Method method = SessionHandler.class.getDeclaredMethod("notifySendCompleted");
            method.setAccessible(true);
            method.invoke(handler);

            assertEquals(TaskState.RECOVERED, dispatcher.states.get(0));
            assertEquals(TaskState.COMPLETED, dispatcher.states.get(1));
            assertTrue(dispatcher.reasons.get(0).contains("runtime_terminated_after_success"));
            assertEquals(TaskState.COMPLETED, taskStateService.getCurrentSnapshot().getState());
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    @Test
    public void shouldMarkCancelledWhenNotifySendFailedReceivesInterruptedError() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-cancelled-test");
        try {
            RecordingClaudeSession session = new RecordingClaudeSession(createProject(projectDir));
            session.setSessionInfo("session-cancelled", projectDir.toString());

            HandlerContext context = createContext(projectDir, session);
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher(context);
            TaskStateService taskStateService = new TaskStateService();
            SessionHandler handler = new SessionHandler(context, taskStateService, dispatcher);

            java.lang.reflect.Method method = SessionHandler.class.getDeclaredMethod("notifySendFailed", Throwable.class);
            method.setAccessible(true);
            method.invoke(handler, new RuntimeException("User interrupted"));

            assertEquals(TaskState.CANCELLED, taskStateService.getCurrentSnapshot().getState());
            assertEquals(TaskState.CANCELLED, dispatcher.states.get(0));
            assertEquals("User interrupted", dispatcher.reasons.get(0));
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    @Test
    public void shouldEmitRetryingStateWhenProviderSignalsRetrying() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-retrying-test");
        try {
            RecordingClaudeSession session = new RecordingClaudeSession(createProject(projectDir));
            session.setSessionInfo("session-retrying", projectDir.toString());
            session.getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, "retry this"));

            HandlerContext context = createContext(projectDir, session);
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher(context);
            TaskStateService taskStateService = new TaskStateService();
            SessionHandler handler = new SessionHandler(context, taskStateService, dispatcher);

            handler.notifyRetrying("provider_rate_limit | attempt=1 | delayMs=1200");

            assertEquals(TaskState.RETRYING, taskStateService.getCurrentSnapshot().getState());
            assertEquals(TaskState.RETRYING, dispatcher.states.get(0));
            assertEquals("provider_rate_limit | attempt=1 | delayMs=1200", dispatcher.reasons.get(0));
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    @Test
    public void shouldNotifyFrontendTaskCompletedWhenSendCompletes() throws Exception {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        Path projectDir = Files.createTempDirectory("session-handler-task-completed-js-test");
        try {
            RecordingClaudeSession session = new RecordingClaudeSession(createProject(projectDir));
            session.setSessionInfo("session-task-completed", projectDir.toString());
            RecordingJsCallback jsCallback = new RecordingJsCallback();

            HandlerContext context = createContext(projectDir, session, jsCallback);
            RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher(context);
            TaskStateService taskStateService = new TaskStateService();
            SessionHandler handler = new SessionHandler(context, taskStateService, dispatcher);

            java.lang.reflect.Method method = SessionHandler.class.getDeclaredMethod("notifySendCompleted");
            method.setAccessible(true);
            method.invoke(handler);

            assertTrue(jsCallback.functionNames.contains("onTaskCompleted"));
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    private static HandlerContext createContext(Path projectDir, ClaudeSession session) {
        return createContext(projectDir, session, new RecordingJsCallback());
    }

    private static HandlerContext createContext(
        Path projectDir,
        ClaudeSession session,
        HandlerContext.JsCallback jsCallback
    ) {
        HandlerContext context = new HandlerContext(
            createProject(projectDir),
            new FixedNodeClaudeSDKBridge(),
            new CodexSDKBridge(),
            null,
            jsCallback
        );
        context.setSession(session);
        return context;
    }

    private static Project createProject(Path projectDir) {
        return (Project) Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class<?>[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getBasePath" -> projectDir.toString();
                case "getName" -> "session-handler-test";
                case "isDisposed" -> false;
                case "isOpen" -> true;
                case "getDisposed" -> null;
                case "toString" -> "session-handler-test-project";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            }
        );
    }

    private static RemoteCollabService newRemoteCollabService() throws Exception {
        Constructor<RemoteCollabService> constructor = RemoteCollabService.class.getDeclaredConstructor(RemoteRequestRegistry.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new RemoteRequestRegistry());
    }

    private static class FixedNodeClaudeSDKBridge extends ClaudeSDKBridge {
        @Override
        public String getCachedNodeVersion() {
            return "18.0.0";
        }
    }

    private static class RecordingJsCallback implements HandlerContext.JsCallback {
        private final List<String> functionNames = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            functionNames.add(functionName);
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }
    }

    private static class RecordingClaudeSession extends ClaudeSession {
        private boolean addUserMessageDuringSend = true;

        RecordingClaudeSession(Project project) {
            super(project, new FixedNodeClaudeSDKBridge(), new CodexSDKBridge());
        }

        @Override
        public CompletableFuture<Void> send(
            String input,
            String agentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode
        ) {
            if (addUserMessageDuringSend) {
                getState().addMessage(new ClaudeSession.Message(ClaudeSession.Message.Type.USER, input));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class RecordingTaskReminderDispatcher extends TaskReminderDispatcher {
        private final CountDownLatch firstDispatchLatch = new CountDownLatch(1);
        private final List<TaskState> states = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();
        private final List<String> latestUserMessagesAtDispatch = new ArrayList<>();
        private final List<String> preferredTaskSummaries = new ArrayList<>();

        RecordingTaskReminderDispatcher(HandlerContext context) {
            super(context);
        }

        @Override
        public void dispatch(TaskStateSnapshot snapshot, boolean approvalDialogVisible) {
            states.add(snapshot.getState());
            reasons.add(snapshot.getLatestEvent() != null ? snapshot.getLatestEvent().getReason() : null);
            latestUserMessagesAtDispatch.add(findLatestUserMessage(snapshot));
            firstDispatchLatch.countDown();
        }

        @Override
        public void dispatch(TaskStateSnapshot snapshot, boolean approvalDialogVisible, String preferredTaskSummary) {
            states.add(snapshot.getState());
            reasons.add(snapshot.getLatestEvent() != null ? snapshot.getLatestEvent().getReason() : null);
            latestUserMessagesAtDispatch.add(findLatestUserMessage(snapshot));
            preferredTaskSummaries.add(preferredTaskSummary);
            firstDispatchLatch.countDown();
        }

        boolean awaitFirstDispatch() throws InterruptedException {
            return firstDispatchLatch.await(5, TimeUnit.SECONDS);
        }

        private String findLatestUserMessage(TaskStateSnapshot snapshot) {
            HandlerContext context = (HandlerContext) readField(TaskReminderDispatcher.class, this, "context");
            ClaudeSession session = context != null ? context.getSession() : null;
            if (session == null) {
                return null;
            }
            List<ClaudeSession.Message> messages = session.getMessages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                ClaudeSession.Message message = messages.get(i);
                if (message != null && message.type == ClaudeSession.Message.Type.USER) {
                    return message.content;
                }
            }
            return null;
        }

        private static Object readField(Class<?> type, Object target, String name) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("failed to read field: " + name, e);
            }
        }
    }

    private static class RecordingRemoteTaskChannel implements RemoteTaskChannel {
        private final CountDownLatch eventLatch;
        private final List<RemoteTaskEvent> events = new ArrayList<>();

        RecordingRemoteTaskChannel(int expectedEvents) {
            this.eventLatch = new CountDownLatch(expectedEvents);
        }

        @Override
        public String getChannelId() {
            return "test-remote-channel";
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
            eventLatch.countDown();
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
        }

        boolean awaitEvents() throws InterruptedException {
            return eventLatch.await(5, TimeUnit.SECONDS);
        }
    }
}
