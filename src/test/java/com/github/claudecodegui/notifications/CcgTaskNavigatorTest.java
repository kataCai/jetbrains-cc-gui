package com.github.claudecodegui.notifications;

import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class CcgTaskNavigatorTest {

    @Test
    public void shouldConstructSafelyOnNonWindowsPlatforms() {
        if (com.github.claudecodegui.util.PlatformUtils.isWindows()) {
            return;
        }

        CcgTaskNavigator navigator = new CcgTaskNavigator();

        assertNotNull(navigator);
    }

    @Test
    public void shouldPreferDetachedWindowBeforeToolWindowSelection() {
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        AtomicReference<String> detachedSession = new AtomicReference<>();
        AtomicReference<String> toolWindowSession = new AtomicReference<>();
        CcgTaskNavigator navigator = new CcgTaskNavigator(
            Runnable::run,
            (project, sessionId) -> {
                detachedSession.set(sessionId);
                return true;
            },
            (project, sessionId) -> {
                toolWindowSession.set(sessionId);
                return true;
            },
            activator
        );
        Project project = createProject(false);

        navigator.navigate(new TaskReminderNavigationTarget(project, "session-detached", "req-1"));

        assertEquals("session-detached", detachedSession.get());
        assertNull(toolWindowSession.get());
        assertNull(activator.lastActivatedProject.get());
    }

    @Test
    public void shouldFallbackToToolWindowActivatorWhenSessionCannotBeLocated() {
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        AtomicReference<String> detachedSession = new AtomicReference<>();
        AtomicReference<String> toolWindowSession = new AtomicReference<>();
        CcgTaskNavigator navigator = new CcgTaskNavigator(
            Runnable::run,
            (project, sessionId) -> {
                detachedSession.set(sessionId);
                return false;
            },
            (project, sessionId) -> {
                toolWindowSession.set(sessionId);
                return false;
            },
            activator
        );
        Project project = createProject(false);

        navigator.navigate(new TaskReminderNavigationTarget(project, "session-fallback", "req-2"));

        assertEquals("session-fallback", detachedSession.get());
        assertEquals("session-fallback", toolWindowSession.get());
        assertSame(project, activator.lastActivatedProject.get());
    }

    @Test
    public void shouldActivateProjectWindowWhenToolWindowSessionIsLocated() {
        RecordingToolWindowActivator activator = new RecordingToolWindowActivator();
        AtomicReference<String> toolWindowSession = new AtomicReference<>();
        CcgTaskNavigator navigator = new CcgTaskNavigator(
            Runnable::run,
            (project, sessionId) -> false,
            (project, sessionId) -> {
                toolWindowSession.set(sessionId);
                return true;
            },
            activator
        );
        Project project = createProject(false);

        navigator.navigate(new TaskReminderNavigationTarget(project, "session-located", "req-3"));

        assertEquals("session-located", toolWindowSession.get());
        assertSame(project, activator.lastRevealedProject.get());
        assertNull(activator.lastActivatedProject.get());
    }

    private static Project createProject(boolean disposed) {
        return (Project) java.lang.reflect.Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isDisposed" -> disposed;
                case "getName" -> "task-navigator-test";
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

    private static class RecordingToolWindowActivator extends CcgToolWindowActivator {
        private final AtomicReference<Project> lastActivatedProject = new AtomicReference<>();
        private final AtomicReference<Project> lastRevealedProject = new AtomicReference<>();

        @Override
        public void activate(Project project) {
            lastActivatedProject.set(project);
        }

        @Override
        public void reveal(Project project) {
            lastRevealedProject.set(project);
        }
    }
}
