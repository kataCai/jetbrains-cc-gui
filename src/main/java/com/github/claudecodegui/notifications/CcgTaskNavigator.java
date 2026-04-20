package com.github.claudecodegui.notifications;

import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.ui.detached.DetachedChatFrame;
import com.github.claudecodegui.ui.detached.DetachedWindowManager;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;

import java.awt.Frame;

/**
 * 统一处理任务提醒后的会话定位。
 * 优先激活 detached window，其次选中对应 tab，最后再降级为打开 CCG ToolWindow。
 */
public class CcgTaskNavigator {

    @FunctionalInterface
    public interface UiInvoker {
        void invokeLater(Runnable runnable);
    }

    @FunctionalInterface
    public interface DetachedWindowNavigator {
        boolean navigate(Project project, String sessionId);
    }

    @FunctionalInterface
    public interface ToolWindowSessionNavigator {
        boolean navigate(Project project, String sessionId);
    }

    private final UiInvoker uiInvoker;
    private final DetachedWindowNavigator detachedWindowNavigator;
    private final ToolWindowSessionNavigator toolWindowSessionNavigator;
    private final CcgToolWindowActivator toolWindowActivator;

    public CcgTaskNavigator() {
        this(
            runnable -> ApplicationManager.getApplication().invokeLater(runnable),
            CcgTaskNavigator::navigateDetachedWindow,
            CcgTaskNavigator::navigateToolWindowSession,
            new CcgToolWindowActivator()
        );
    }

    public CcgTaskNavigator(
        UiInvoker uiInvoker,
        DetachedWindowNavigator detachedWindowNavigator,
        ToolWindowSessionNavigator toolWindowSessionNavigator,
        CcgToolWindowActivator toolWindowActivator
    ) {
        this.uiInvoker = uiInvoker != null
            ? uiInvoker
            : runnable -> ApplicationManager.getApplication().invokeLater(runnable);
        this.detachedWindowNavigator = detachedWindowNavigator != null
            ? detachedWindowNavigator
            : CcgTaskNavigator::navigateDetachedWindow;
        this.toolWindowSessionNavigator = toolWindowSessionNavigator != null
            ? toolWindowSessionNavigator
            : CcgTaskNavigator::navigateToolWindowSession;
        this.toolWindowActivator = toolWindowActivator != null ? toolWindowActivator : new CcgToolWindowActivator();
    }

    public void navigate(TaskReminderNavigationTarget target) {
        if (target == null) {
            return;
        }
        Project project = target.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        uiInvoker.invokeLater(() -> navigateNow(target));
    }

    private void navigateNow(TaskReminderNavigationTarget target) {
        Project project = target.getProject();
        String sessionId = normalize(target.getSessionId());
        if (sessionId != null) {
            if (detachedWindowNavigator.navigate(project, sessionId)) {
                return;
            }
            if (toolWindowSessionNavigator.navigate(project, sessionId)) {
                toolWindowActivator.activate(project);
                return;
            }
        }
        toolWindowActivator.activate(project);
    }

    private static boolean navigateDetachedWindow(Project project, String sessionId) {
        DetachedChatFrame frame = DetachedWindowManager.getDetachedFrame(project, sessionId);
        if (frame == null) {
            return false;
        }
        if ((frame.getExtendedState() & Frame.ICONIFIED) != 0) {
            frame.setExtendedState(Frame.NORMAL);
        }
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
        return true;
    }

    private static boolean navigateToolWindowSession(Project project, String sessionId) {
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(CcgToolWindowActivator.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            return false;
        }

        ContentManager contentManager = toolWindow.getContentManager();
        Content matchedContent = findRuntimeContent(contentManager, sessionId);
        if (matchedContent == null) {
            matchedContent = findPersistedContent(project, contentManager, sessionId);
        }
        if (matchedContent == null) {
            return false;
        }

        contentManager.setSelectedContent(matchedContent);
        return true;
    }

    private static Content findRuntimeContent(ContentManager contentManager, String sessionId) {
        for (Content content : contentManager.getContents()) {
            ClaudeChatWindow chatWindow = ClaudeSDKToolWindow.getChatWindowForContent(content);
            if (chatWindow != null && sessionId.equals(normalize(chatWindow.getSessionId()))) {
                return content;
            }
        }
        return null;
    }

    private static Content findPersistedContent(Project project, ContentManager contentManager, String sessionId) {
        TabStateService tabStateService = TabStateService.getInstance(project);
        Content[] contents = contentManager.getContents();
        for (int index = 0; index < contents.length; index++) {
            TabStateService.TabSessionState savedState = tabStateService.getTabSessionState(index);
            if (savedState != null && sessionId.equals(normalize(savedState.sessionId))) {
                return contents[index];
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
