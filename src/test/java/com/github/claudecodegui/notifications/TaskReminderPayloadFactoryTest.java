package com.github.claudecodegui.notifications;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.taskstate.TaskStateEvent;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 验证提醒负载工厂会把系统通知主标题与任务摘要分离。
 * 重点覆盖“稳定 tab 标题优先于 session summary，正文仍保持当前任务摘要”的核心回归场景。
 */
public class TaskReminderPayloadFactoryTest {

    @Test
    public void shouldPreferTabTitleForNotificationTitleWhileKeepingTaskSummaryAsMessage() {
        ClaudeSession session = new ClaudeSession(null, null, null);
        session.getState().setSummary("Session Summary Title");
        session.getState().addMessage(new ClaudeSession.Message(
            ClaudeSession.Message.Type.USER,
            "Summarize the latest user task"
        ));

        HandlerContext context = createContext(session, createProject());
        TaskReminderPayloadFactory factory = new TaskReminderPayloadFactory(
            (project, sessionId) -> "Stable Tab Title"
        );

        TaskReminderNotificationPayload payload = factory.create(
            context,
            snapshot(),
            "Task completed"
        );

        assertEquals("Stable Tab Title", payload.getNotificationTitle());
        assertEquals("Summarize the latest user task", payload.getTaskSummary());
        assertEquals("Summarize the latest user task", payload.getMessage());
    }

    @Test
    public void shouldFallbackToSessionSummaryWhenTabTitleIsMissing() {
        ClaudeSession session = new ClaudeSession(null, null, null);
        session.getState().setSummary("Session Summary Title");

        HandlerContext context = createContext(session, createProject());
        TaskReminderPayloadFactory factory = new TaskReminderPayloadFactory(
            (project, sessionId) -> null
        );

        TaskReminderNotificationPayload payload = factory.create(
            context,
            snapshot(),
            "Task completed"
        );

        assertEquals("Session Summary Title", payload.getNotificationTitle());
        assertEquals("Session Summary Title", payload.getTaskSummary());
        assertEquals("Session Summary Title", payload.getMessage());
    }

    @Test
    public void shouldFallbackToCcGuiWhenTabTitleAndSessionSummaryAreMissing() {
        ClaudeSession session = new ClaudeSession(null, null, null);

        HandlerContext context = createContext(session, createProject());
        TaskReminderPayloadFactory factory = new TaskReminderPayloadFactory(
            (project, sessionId) -> null
        );

        TaskReminderNotificationPayload payload = factory.create(
            context,
            snapshot(),
            "Task completed"
        );

        assertEquals("CC GUI", payload.getNotificationTitle());
        assertEquals("Task completed", payload.getTaskSummary());
        assertEquals("Task completed", payload.getMessage());
    }

    /**
     * 验证 completed 场景下如果聊天区已经补发了 completion task-notification summary，
     * 提醒正文必须优先复用这条最终总结，而不是继续回退到旧的 user message 或 session summary。
     * 这样可以保证聊天区和本地提醒描述的是同一轮任务的最终完成结果。
     */
    @Test
    public void shouldPreferCompletionTaskNotificationSummaryForCompletedReminderMessage() {
        ClaudeSession session = new ClaudeSession(null, null, null);
        session.getState().setSummary("Session Summary Title");
        session.getState().addMessage(new ClaudeSession.Message(
            ClaudeSession.Message.Type.USER,
            "Old user task summary"
        ));
        session.getState().addMessage(new ClaudeSession.Message(
            ClaudeSession.Message.Type.USER,
            "<task-notification><status>completed</status><summary>Completed summary from chat area</summary></task-notification>",
            buildTaskNotificationRaw("completed", "Completed summary from chat area")
        ));

        HandlerContext context = createContext(session, createProject());
        TaskReminderPayloadFactory factory = new TaskReminderPayloadFactory(
            (project, sessionId) -> null
        );

        TaskReminderNotificationPayload payload = factory.create(
            context,
            snapshot(),
            "Task completed"
        );

        assertEquals("Session Summary Title", payload.getNotificationTitle());
        assertEquals("Completed summary from chat area", payload.getTaskSummary());
        assertEquals("Completed summary from chat area", payload.getMessage());
    }

    /**
     * 验证 completed 场景下即使上游显式传入了旧的 preferredTaskSummary，
     * 只要聊天区已经存在最新的 completion task-notification summary，仍必须优先采用聊天区这条最终总结。
     * 该场景用于覆盖 dispatcher 在前置状态缓存过旧摘要后，completed 阶段再次创建 payload 的优先级问题。
     */
    @Test
    public void shouldPreferCompletionTaskNotificationSummaryOverPreferredTaskSummaryWhenCompleted() {
        ClaudeSession session = new ClaudeSession(null, null, null);
        session.getState().setSummary("Session Summary Title");
        session.getState().addMessage(new ClaudeSession.Message(
            ClaudeSession.Message.Type.USER,
            "Old user task summary"
        ));
        session.getState().addMessage(new ClaudeSession.Message(
            ClaudeSession.Message.Type.USER,
            "<task-notification><status>completed</status><summary>Completed summary from chat area</summary></task-notification>",
            buildTaskNotificationRaw("completed", "Completed summary from chat area")
        ));

        HandlerContext context = createContext(session, createProject());
        TaskReminderPayloadFactory factory = new TaskReminderPayloadFactory(
            (project, sessionId) -> null
        );

        TaskReminderNotificationPayload payload = factory.create(
            context,
            snapshot(),
            "Task completed",
            "Cached summary from earlier state"
        );

        assertEquals("Completed summary from chat area", payload.getTaskSummary());
        assertEquals("Completed summary from chat area", payload.getMessage());
    }

    private static TaskStateSnapshot snapshot() {
        return new TaskStateSnapshot(
            TaskState.COMPLETED,
            "session-1",
            "req-1",
            new TaskStateEvent(TaskState.COMPLETED, "session-1", "req-1", "send_completed", System.currentTimeMillis())
        );
    }

    private static HandlerContext createContext(ClaudeSession session, Project project) {
        HandlerContext context = new HandlerContext(
            project,
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

    private static Project createProject() {
        return (Project) java.lang.reflect.Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isDisposed" -> false;
                case "getName" -> "task-reminder-payload-factory-test";
                default -> method.getReturnType().isPrimitive()
                    ? defaultPrimitiveValue(method.getReturnType())
                    : null;
            }
        );
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

    /**
     * 构造最小 task-notification raw 数据，复用生产代码当前依赖的 origin/message/content 结构。
     * 这里显式写入 completed 状态和 summary，便于测试提醒工厂能否正确解析聊天区最终完成摘要。
     *
     * @param status task-notification 状态
     * @param summary task-notification summary 文本
     * @return 可直接挂到 ClaudeSession.Message 上的 raw JSON
     */
    private static com.google.gson.JsonObject buildTaskNotificationRaw(String status, String summary) {
        com.google.gson.JsonObject raw = new com.google.gson.JsonObject();
        com.google.gson.JsonObject origin = new com.google.gson.JsonObject();
        origin.addProperty("kind", "task-notification");
        raw.add("origin", origin);

        com.google.gson.JsonObject message = new com.google.gson.JsonObject();
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        com.google.gson.JsonObject block = new com.google.gson.JsonObject();
        block.addProperty("type", "text");
        block.addProperty(
            "text",
            "<task-notification><status>" + status + "</status><summary>" + summary + "</summary></task-notification>"
        );
        content.add(block);
        message.add("content", content);
        raw.add("message", message);
        return raw;
    }
}
