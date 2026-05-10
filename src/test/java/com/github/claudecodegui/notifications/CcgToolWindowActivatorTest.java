package com.github.claudecodegui.notifications;

import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

public class CcgToolWindowActivatorTest {

    @Test
    public void shouldConstructSafelyOnNonWindowsPlatforms() {
        if (com.github.claudecodegui.util.PlatformUtils.isWindows()) {
            return;
        }

        CcgToolWindowActivator activator = new CcgToolWindowActivator();

        assertNotNull(activator);
    }

    @Test
    public void shouldRestoreMinimizedProjectWindowBeforeShowingToolWindow() {
        List<String> operations = new ArrayList<>();
        RecordingProjectWindow window = new RecordingProjectWindow(true, true, operations);
        RecordingToolWindow toolWindow = new RecordingToolWindow(operations);
        CcgToolWindowActivator activator = new CcgToolWindowActivator(
            Runnable::run,
            project -> window,
            project -> toolWindow,
            () -> false,
            (project, restoreWindow) -> false
        );

        activator.activate(createProject(false));

        assertEquals(
            List.of("restore", "showWindow", "toFront", "requestFocus", "showToolWindow", "activateToolWindow"),
            operations
        );
    }

    @Test
    public void shouldRequestUserAttentionWhenWindowRemainsInactive() {
        List<String> operations = new ArrayList<>();
        RecordingProjectWindow window = new RecordingProjectWindow(false, false, operations);
        RecordingToolWindow toolWindow = new RecordingToolWindow(operations);
        CcgToolWindowActivator activator = new CcgToolWindowActivator(
            Runnable::run,
            project -> window,
            project -> toolWindow,
            () -> false,
            (project, restoreWindow) -> false
        );

        activator.activate(createProject(false));

        assertEquals(
            List.of("showWindow", "toFront", "requestFocus", "requestAttention", "showToolWindow", "activateToolWindow"),
            operations
        );
    }

    @Test
    public void shouldUseWindowsNativeActivationBeforeRequestingAttention() {
        List<String> operations = new ArrayList<>();
        RecordingProjectWindow window = new RecordingProjectWindow(false, false, operations);
        RecordingToolWindow toolWindow = new RecordingToolWindow(operations);
        CcgToolWindowActivator activator = new CcgToolWindowActivator(
            Runnable::run,
            project -> window,
            project -> toolWindow,
            () -> true,
            (project, restoreWindow) -> {
                operations.add("nativeActivate");
                return true;
            }
        );

        activator.activate(createProject(false));

        assertEquals(
            List.of("showWindow", "toFront", "requestFocus", "nativeActivate", "showToolWindow", "activateToolWindow"),
            operations
        );
    }

    @Test
    public void shouldFallbackToAttentionWhenWindowsNativeActivationFails() {
        List<String> operations = new ArrayList<>();
        RecordingProjectWindow window = new RecordingProjectWindow(false, false, operations);
        RecordingToolWindow toolWindow = new RecordingToolWindow(operations);
        CcgToolWindowActivator activator = new CcgToolWindowActivator(
            Runnable::run,
            project -> window,
            project -> toolWindow,
            () -> true,
            (project, restoreWindow) -> {
                operations.add("nativeActivate");
                return false;
            }
        );

        activator.activate(createProject(false));

        assertEquals(
            List.of(
                "showWindow",
                "toFront",
                "requestFocus",
                "nativeActivate",
                "requestAttention",
                "showToolWindow",
                "activateToolWindow"
            ),
            operations
        );
    }

    @Test
    public void shouldPassVisibleWindowStateToNativeActivatorWithoutRestore() {
        List<String> operations = new ArrayList<>();
        RecordingProjectWindow window = new RecordingProjectWindow(false, false, operations);
        RecordingToolWindow toolWindow = new RecordingToolWindow(operations);
        AtomicBoolean restoreWindow = new AtomicBoolean(true);
        CcgToolWindowActivator activator = new CcgToolWindowActivator(
            Runnable::run,
            project -> window,
            project -> toolWindow,
            () -> true,
            (project, minimized) -> {
                operations.add("nativeActivate");
                restoreWindow.set(minimized);
                return true;
            }
        );

        activator.activate(createProject(false));

        assertFalse(restoreWindow.get());
        assertEquals(
            List.of("showWindow", "toFront", "requestFocus", "nativeActivate", "showToolWindow", "activateToolWindow"),
            operations
        );
    }

    @Test
    public void shouldRevealToolWindowWithoutActivatingContents() {
        List<String> operations = new ArrayList<>();
        RecordingProjectWindow window = new RecordingProjectWindow(false, false, operations);
        RecordingToolWindow toolWindow = new RecordingToolWindow(operations);
        CcgToolWindowActivator activator = new CcgToolWindowActivator(
            Runnable::run,
            project -> window,
            project -> toolWindow,
            () -> false,
            (project, restoreWindow) -> false
        );

        activator.reveal(createProject(false));

        assertEquals(
            List.of("showWindow", "toFront", "requestFocus", "requestAttention", "showToolWindow"),
            operations
        );
    }

    @Test
    public void shouldStillShowToolWindowWhenProjectWindowIsUnavailable() {
        List<String> operations = new ArrayList<>();
        RecordingToolWindow toolWindow = new RecordingToolWindow(operations);
        CcgToolWindowActivator activator = new CcgToolWindowActivator(
            Runnable::run,
            project -> null,
            project -> toolWindow,
            () -> true,
            (project, restoreWindow) -> {
                operations.add("nativeActivate");
                return true;
            }
        );

        activator.activate(createProject(false));

        assertEquals(List.of("showToolWindow", "activateToolWindow"), operations);
    }

    private static Project createProject(boolean disposed) {
        return (Project) java.lang.reflect.Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isDisposed" -> disposed;
                case "getName" -> "tool-window-activator-test";
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

    private static class RecordingProjectWindow implements CcgToolWindowActivator.ProjectWindowHandle {
        private final boolean minimized;
        private final boolean active;
        private final List<String> operations;

        private RecordingProjectWindow(boolean minimized, boolean active, List<String> operations) {
            this.minimized = minimized;
            this.active = active;
            this.operations = operations;
        }

        @Override
        public boolean isMinimized() {
            return minimized;
        }

        @Override
        public void restore() {
            operations.add("restore");
        }

        @Override
        public void show() {
            operations.add("showWindow");
        }

        @Override
        public void toFront() {
            operations.add("toFront");
        }

        @Override
        public void requestFocus() {
            operations.add("requestFocus");
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void requestAttention() {
            operations.add("requestAttention");
        }
    }

    private static class RecordingToolWindow implements CcgToolWindowActivator.ToolWindowHandle {
        private final List<String> operations;

        private RecordingToolWindow(List<String> operations) {
            this.operations = operations;
        }

        @Override
        public void show() {
            operations.add("showToolWindow");
        }

        @Override
        public void activate() {
            operations.add("activateToolWindow");
        }
    }
}
