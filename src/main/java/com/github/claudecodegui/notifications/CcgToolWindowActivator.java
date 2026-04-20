package com.github.claudecodegui.notifications;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;

/**
 * 负责激活 IDE 内的 CCG ToolWindow，供系统通知点击后复用。
 */
public class CcgToolWindowActivator {

    static final String TOOL_WINDOW_ID = "CCG";

    /**
     * 激活 IDE 并展开 CCG 工具窗口。
     */
    public void activate(Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow == null) {
                return;
            }
            toolWindow.show(null);
            toolWindow.activate(null);
        });
    }
}
