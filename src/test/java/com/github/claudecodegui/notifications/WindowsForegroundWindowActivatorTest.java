package com.github.claudecodegui.notifications;

import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WindowsForegroundWindowActivatorTest {

    @Test
    public void shouldUseDirectForegroundActivationWhenFirstAttemptSucceeds() {
        List<String> operations = new ArrayList<>();
        WindowsForegroundWindowActivator activator = new WindowsForegroundWindowActivator(
            project -> 1001L,
            new RecordingWin32Facade(operations, true)
        );

        boolean activated = activator.tryActivate(createProject(false));

        assertTrue(activated);
        assertEquals(
            List.of("showWindow", "bringWindowToTop", "setForegroundWindow", "setFocus"),
            operations
        );
    }

    @Test
    public void shouldRetryWithAttachedInputWhenDirectForegroundActivationFails() {
        List<String> operations = new ArrayList<>();
        WindowsForegroundWindowActivator activator = new WindowsForegroundWindowActivator(
            project -> 1002L,
            new RecordingWin32Facade(operations, false)
        );

        boolean activated = activator.tryActivate(createProject(false));

        assertTrue(activated);
        assertEquals(
            List.of(
                "showWindow",
                "bringWindowToTop",
                "setForegroundWindow",
                "getForegroundWindow",
                "getCurrentThreadId",
                "getWindowThreadProcessId",
                "attachThreadInput:true",
                "showWindow",
                "bringWindowToTop",
                "setForegroundWindow",
                "setFocus",
                "attachThreadInput:false"
            ),
            operations
        );
    }

    @Test
    public void shouldReturnFalseWhenNoNativeWindowHandleExists() {
        List<String> operations = new ArrayList<>();
        WindowsForegroundWindowActivator activator = new WindowsForegroundWindowActivator(
            project -> 0L,
            new RecordingWin32Facade(operations, true)
        );

        boolean activated = activator.tryActivate(createProject(false));

        assertFalse(activated);
        assertEquals(List.of(), operations);
    }

    private static Project createProject(boolean disposed) {
        return (Project) java.lang.reflect.Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isDisposed" -> disposed;
                case "getName" -> "windows-foreground-activator-test";
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

    private static class RecordingWin32Facade implements WindowsForegroundWindowActivator.Win32Facade {
        private final List<String> operations;
        private boolean firstForegroundAttempt;

        private RecordingWin32Facade(List<String> operations, boolean firstForegroundAttempt) {
            this.operations = operations;
            this.firstForegroundAttempt = firstForegroundAttempt;
        }

        @Override
        public boolean showWindow(long hwnd, int command) {
            operations.add("showWindow");
            return true;
        }

        @Override
        public boolean bringWindowToTop(long hwnd) {
            operations.add("bringWindowToTop");
            return true;
        }

        @Override
        public boolean setForegroundWindow(long hwnd) {
            operations.add("setForegroundWindow");
            boolean result = firstForegroundAttempt;
            firstForegroundAttempt = true;
            return result;
        }

        @Override
        public long setFocus(long hwnd) {
            operations.add("setFocus");
            return hwnd;
        }

        @Override
        public long getForegroundWindow() {
            operations.add("getForegroundWindow");
            return 2002L;
        }

        @Override
        public int getCurrentThreadId() {
            operations.add("getCurrentThreadId");
            return 11;
        }

        @Override
        public int getWindowThreadProcessId(long hwnd) {
            operations.add("getWindowThreadProcessId");
            return 22;
        }

        @Override
        public boolean attachThreadInput(int sourceThreadId, int targetThreadId, boolean attach) {
            operations.add("attachThreadInput:" + attach);
            return true;
        }
    }
}
