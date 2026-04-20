package com.github.claudecodegui.notifications;

import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;

import javax.swing.JFrame;
import java.awt.Frame;

/**
 * 负责激活 IDE 内的 CCG ToolWindow，并在需要时尝试恢复主窗口焦点。
 */
public class CcgToolWindowActivator {

    static final String TOOL_WINDOW_ID = "CCG";

    private final UiInvoker uiInvoker;
    private final ProjectWindowProvider projectWindowProvider;
    private final ToolWindowProvider toolWindowProvider;
    private final PlatformDetector platformDetector;
    private final NativeProjectWindowActivator nativeProjectWindowActivator;

    @FunctionalInterface
    interface UiInvoker {
        void invokeLater(Runnable runnable);
    }

    @FunctionalInterface
    interface ProjectWindowProvider {
        ProjectWindowHandle get(Project project);
    }

    interface ProjectWindowHandle {
        boolean isMinimized();

        void restore();

        void show();

        void toFront();

        void requestFocus();

        boolean isActive();

        void requestAttention();
    }

    @FunctionalInterface
    interface ToolWindowProvider {
        ToolWindowHandle get(Project project);
    }

    interface ToolWindowHandle {
        void show();

        void activate();
    }

    @FunctionalInterface
    interface PlatformDetector {
        boolean isWindows();
    }

    @FunctionalInterface
    interface NativeProjectWindowActivator {
        boolean tryActivate(Project project);
    }

    public CcgToolWindowActivator() {
        this(
            runnable -> ApplicationManager.getApplication().invokeLater(runnable),
            CcgToolWindowActivator::findProjectWindow,
            CcgToolWindowActivator::findToolWindow,
            PlatformUtils::isWindows,
            new WindowsForegroundWindowActivator()::tryActivate
        );
    }

    CcgToolWindowActivator(
        UiInvoker uiInvoker,
        ProjectWindowProvider projectWindowProvider,
        ToolWindowProvider toolWindowProvider,
        PlatformDetector platformDetector,
        NativeProjectWindowActivator nativeProjectWindowActivator
    ) {
        this.uiInvoker = uiInvoker != null
            ? uiInvoker
            : runnable -> ApplicationManager.getApplication().invokeLater(runnable);
        this.projectWindowProvider = projectWindowProvider != null
            ? projectWindowProvider
            : CcgToolWindowActivator::findProjectWindow;
        this.toolWindowProvider = toolWindowProvider != null
            ? toolWindowProvider
            : CcgToolWindowActivator::findToolWindow;
        this.platformDetector = platformDetector != null ? platformDetector : PlatformUtils::isWindows;
        this.nativeProjectWindowActivator = nativeProjectWindowActivator != null
            ? nativeProjectWindowActivator
            : new WindowsForegroundWindowActivator()::tryActivate;
    }

    /**
     * 激活 IDE 并展开 CCG 工具窗口。
     */
    public void activate(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }

        uiInvoker.invokeLater(() -> activateNow(project));
    }

    void activateProjectWindow(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }

        ProjectWindowHandle projectWindow = projectWindowProvider.get(project);
        if (projectWindow == null) {
            return;
        }

        // 先走跨平台的窗口恢复与前置，再按平台补强原生前台激活。
        if (projectWindow.isMinimized()) {
            projectWindow.restore();
        }
        projectWindow.show();
        projectWindow.toFront();
        projectWindow.requestFocus();

        if (projectWindow.isActive()) {
            return;
        }

        boolean nativeActivated = platformDetector.isWindows() && nativeProjectWindowActivator.tryActivate(project);
        if (!nativeActivated && !projectWindow.isActive()) {
            projectWindow.requestAttention();
        }
    }

    private void activateNow(Project project) {
        if (project.isDisposed()) {
            return;
        }

        activateProjectWindow(project);

        ToolWindowHandle toolWindow = toolWindowProvider.get(project);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show();
        toolWindow.activate();
    }

    private static ProjectWindowHandle findProjectWindow(Project project) {
        WindowManager windowManager = WindowManager.getInstance();
        JFrame frame = windowManager.getFrame(project);
        IdeFrame ideFrame = windowManager.getIdeFrame(project);
        if (frame == null && ideFrame == null) {
            return null;
        }
        return new IdeProjectWindowHandle(frame, ideFrame, windowManager);
    }

    private static ToolWindowHandle findToolWindow(Project project) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return null;
        }
        return new IdeToolWindowHandle(toolWindow);
    }

    private static final class IdeProjectWindowHandle implements ProjectWindowHandle {
        private final JFrame frame;
        private final IdeFrame ideFrame;
        private final WindowManager windowManager;

        private IdeProjectWindowHandle(JFrame frame, IdeFrame ideFrame, WindowManager windowManager) {
            this.frame = frame;
            this.ideFrame = ideFrame;
            this.windowManager = windowManager;
        }

        @Override
        public boolean isMinimized() {
            return frame != null && (frame.getExtendedState() & Frame.ICONIFIED) != 0;
        }

        @Override
        public void restore() {
            if (frame != null) {
                frame.setExtendedState(Frame.NORMAL);
            }
        }

        @Override
        public void show() {
            if (frame != null) {
                frame.setVisible(true);
            }
        }

        @Override
        public void toFront() {
            if (frame != null) {
                frame.toFront();
            }
        }

        @Override
        public void requestFocus() {
            if (frame != null) {
                frame.requestFocus();
            }
        }

        @Override
        public boolean isActive() {
            return frame != null && frame.isActive();
        }

        @Override
        public void requestAttention() {
            if (ideFrame != null) {
                windowManager.requestUserAttention(ideFrame, false);
            }
        }
    }

    private static final class IdeToolWindowHandle implements ToolWindowHandle {
        private final ToolWindow toolWindow;

        private IdeToolWindowHandle(ToolWindow toolWindow) {
            this.toolWindow = toolWindow;
        }

        @Override
        public void show() {
            toolWindow.show(null);
        }

        @Override
        public void activate() {
            toolWindow.activate(null);
        }
    }
}
