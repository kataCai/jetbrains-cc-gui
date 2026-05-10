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
        boolean tryActivate(Project project, boolean restoreWindow);
    }

    public CcgToolWindowActivator() {
        this(
            runnable -> ApplicationManager.getApplication().invokeLater(runnable),
            CcgToolWindowActivator::findProjectWindow,
            CcgToolWindowActivator::findToolWindow,
            PlatformUtils::isWindows,
            CcgToolWindowActivator::tryActivateOnWindows
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
            : CcgToolWindowActivator::tryActivateOnWindows;
    }

    /**
     * 激活 IDE 并展开 CCG 工具窗口。
     */
    public void activate(Project project) {
        open(project, true);
    }

    /**
     * 仅揭示 CCG 面板，不再强制把焦点切入 ToolWindow 内容区。
     * 系统通知点击场景只需要把 IDE 和对应面板带回前台；继续强制 activate ToolWindow
     * 会额外触发 IntelliJ 的内容聚焦链路，在 Windows 全屏场景下更容易引起宿主窗口状态变化。
     */
    public void reveal(Project project) {
        open(project, false);
    }

    private void open(Project project, boolean activateToolWindowContents) {
        if (project == null || project.isDisposed()) {
            return;
        }

        uiInvoker.invokeLater(() -> activateNow(project, activateToolWindowContents));
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
        // 只有最小化窗口才允许恢复窗口状态；普通/全屏窗口只做前置与聚焦。
        boolean minimized = projectWindow.isMinimized();
        if (minimized) {
            projectWindow.restore();
        }
        projectWindow.show();
        projectWindow.toFront();
        projectWindow.requestFocus();

        if (projectWindow.isActive()) {
            return;
        }

        boolean nativeActivated = platformDetector.isWindows()
            && nativeProjectWindowActivator.tryActivate(project, minimized);
        if (!nativeActivated && !projectWindow.isActive()) {
            projectWindow.requestAttention();
        }
    }

    private void activateNow(Project project, boolean activateToolWindowContents) {
        if (project.isDisposed()) {
            return;
        }

        activateProjectWindow(project);

        ToolWindowHandle toolWindow = toolWindowProvider.get(project);
        if (toolWindow == null) {
            return;
        }
        toolWindow.show();
        if (activateToolWindowContents) {
            toolWindow.activate();
        }
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

    private static boolean tryActivateOnWindows(Project project, boolean restoreWindow) {
        return new WindowsForegroundWindowActivator().tryActivate(project, restoreWindow);
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
                // 这里只处理最小化恢复，不能把其他窗口状态（例如全屏）强制改成普通窗口。
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
