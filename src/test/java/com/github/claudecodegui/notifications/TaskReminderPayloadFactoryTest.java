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
}
