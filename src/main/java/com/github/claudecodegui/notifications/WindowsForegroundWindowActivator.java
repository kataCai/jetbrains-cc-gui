package com.github.claudecodegui.notifications;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.WindowManager;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinUser;

import javax.swing.JFrame;

/**
 * 负责在 Windows 上通过 Win32 API 提升 IDE 主窗口到前台。
 */
public class WindowsForegroundWindowActivator {

    private static final Logger LOG = Logger.getInstance(WindowsForegroundWindowActivator.class);

    private final WindowHandleProvider windowHandleProvider;
    private final Win32Facade win32Facade;

    @FunctionalInterface
    interface WindowHandleProvider {
        long resolve(Project project);
    }

    interface Win32Facade {
        boolean showWindow(long hwnd, int command);

        boolean bringWindowToTop(long hwnd);

        boolean setForegroundWindow(long hwnd);

        long setFocus(long hwnd);

        long getForegroundWindow();

        int getCurrentThreadId();

        int getWindowThreadProcessId(long hwnd);

        boolean attachThreadInput(int sourceThreadId, int targetThreadId, boolean attach);
    }

    public WindowsForegroundWindowActivator() {
        this(WindowsForegroundWindowActivator::resolveWindowHandle, new JnaWin32Facade());
    }

    WindowsForegroundWindowActivator(WindowHandleProvider windowHandleProvider, Win32Facade win32Facade) {
        this.windowHandleProvider = windowHandleProvider != null
            ? windowHandleProvider
            : WindowsForegroundWindowActivator::resolveWindowHandle;
        this.win32Facade = win32Facade != null ? win32Facade : new JnaWin32Facade();
    }

    public boolean tryActivate(Project project) {
        if (project == null || project.isDisposed()) {
            return false;
        }

        long hwnd = windowHandleProvider.resolve(project);
        if (hwnd == 0L) {
            return false;
        }

        if (activateWindow(hwnd)) {
            return true;
        }

        long foregroundWindow = win32Facade.getForegroundWindow();
        if (foregroundWindow == 0L) {
            return false;
        }

        int currentThreadId = win32Facade.getCurrentThreadId();
        int foregroundThreadId = win32Facade.getWindowThreadProcessId(foregroundWindow);
        if (foregroundThreadId == 0 || foregroundThreadId == currentThreadId) {
            return false;
        }

        boolean attached = false;
        try {
            attached = win32Facade.attachThreadInput(currentThreadId, foregroundThreadId, true);
            if (!attached) {
                return false;
            }
            return activateWindow(hwnd);
        } catch (Exception e) {
            LOG.warn("[WindowsForegroundWindowActivator] Failed to activate foreground window: " + e.getMessage(), e);
            return false;
        } finally {
            if (attached) {
                win32Facade.attachThreadInput(currentThreadId, foregroundThreadId, false);
            }
        }
    }

    private boolean activateWindow(long hwnd) {
        win32Facade.showWindow(hwnd, WinUser.SW_RESTORE);
        win32Facade.bringWindowToTop(hwnd);
        boolean activated = win32Facade.setForegroundWindow(hwnd);
        if (activated) {
            win32Facade.setFocus(hwnd);
        }
        return activated;
    }

    private static long resolveWindowHandle(Project project) {
        JFrame frame = WindowManager.getInstance().getFrame(project);
        if (frame == null) {
            return 0L;
        }
        Pointer pointer = Native.getWindowPointer(frame);
        return pointer != null ? Pointer.nativeValue(pointer) : 0L;
    }

    private static final class JnaWin32Facade implements Win32Facade {
        private final User32 user32 = User32.INSTANCE;
        private final Kernel32 kernel32 = Kernel32.INSTANCE;

        @Override
        public boolean showWindow(long hwnd, int command) {
            return user32.ShowWindow(toHwnd(hwnd), command);
        }

        @Override
        public boolean bringWindowToTop(long hwnd) {
            return user32.BringWindowToTop(toHwnd(hwnd));
        }

        @Override
        public boolean setForegroundWindow(long hwnd) {
            return user32.SetForegroundWindow(toHwnd(hwnd));
        }

        @Override
        public long setFocus(long hwnd) {
            WinDef.HWND result = user32.SetFocus(toHwnd(hwnd));
            return result != null ? Pointer.nativeValue(result.getPointer()) : 0L;
        }

        @Override
        public long getForegroundWindow() {
            WinDef.HWND result = user32.GetForegroundWindow();
            return result != null ? Pointer.nativeValue(result.getPointer()) : 0L;
        }

        @Override
        public int getCurrentThreadId() {
            return kernel32.GetCurrentThreadId();
        }

        @Override
        public int getWindowThreadProcessId(long hwnd) {
            return user32.GetWindowThreadProcessId(toHwnd(hwnd), null);
        }

        @Override
        public boolean attachThreadInput(int sourceThreadId, int targetThreadId, boolean attach) {
            return user32.AttachThreadInput(
                new WinDef.DWORD(sourceThreadId),
                new WinDef.DWORD(targetThreadId),
                attach
            );
        }

        private static WinDef.HWND toHwnd(long hwnd) {
            return new WinDef.HWND(Pointer.createConstant(hwnd));
        }
    }
}
