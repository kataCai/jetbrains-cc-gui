package com.github.claudecodegui.action.tab;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;

/**
 * Tab 强制刷新动作。
 * 用于在用户发现当前聊天窗口内容区空白、前端未正常渲染时，
 * 通过当前选中页签触发一次 WebView 重建，并在前端 ready 后按需恢复该页签最近绑定的历史会话。
 */
public class ForceRefreshTabAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(ForceRefreshTabAction.class);

    /**
     * 初始化动作文案。
     * 文案统一从资源文件读取，避免工具窗口头部菜单与右键菜单出现多语言不一致。
     */
    public ForceRefreshTabAction() {
        super(
                ClaudeCodeGuiBundle.message("action.forceRefreshTab.text"),
                ClaudeCodeGuiBundle.message("action.forceRefreshTab.description"),
                null
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    /**
     * 执行当前页签的强制刷新。
     * 仅在已选中页签且能定位到对应 ClaudeChatWindow 时生效，避免误操作到无效内容页。
     *
     * @param e IntelliJ 动作事件
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        ClaudeChatWindow chatWindow = resolveSelectedChatWindow(e.getProject());
        if (chatWindow == null) {
            return;
        }

        LOG.info("[ForceRefreshTabAction] Triggering force refresh for selected tab");
        chatWindow.forceRefreshWindow();
    }

    /**
     * 更新动作可见性。
     * 只有在 CCG 工具窗口存在且当前存在选中页签时，才展示该动作，避免在无效上下文中暴露入口。
     *
     * @param e IntelliJ 动作事件
     */
    @Override
    public void update(@NotNull AnActionEvent e) {
        ClaudeChatWindow chatWindow = resolveSelectedChatWindow(e.getProject());
        e.getPresentation().setEnabledAndVisible(chatWindow != null);
    }

    /**
     * 解析当前选中页签对应的聊天窗口实例。
     *
     * @param project 当前项目
     * @return 选中页签对应的聊天窗口；若当前上下文无有效页签则返回 null
     */
    private ClaudeChatWindow resolveSelectedChatWindow(Project project) {
        if (project == null) {
            LOG.warn("[ForceRefreshTabAction] Project is null");
            return null;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ClaudeSDKToolWindow.TOOL_WINDOW_ID);
        if (toolWindow == null) {
            LOG.warn("[ForceRefreshTabAction] Tool window not found");
            return null;
        }

        ContentManager contentManager = toolWindow.getContentManager();
        Content selectedContent = contentManager.getSelectedContent();
        if (selectedContent == null) {
            LOG.warn("[ForceRefreshTabAction] No selected tab found");
            return null;
        }

        ClaudeChatWindow chatWindow = ClaudeSDKToolWindow.getChatWindowForContent(selectedContent);
        if (chatWindow == null) {
            LOG.warn("[ForceRefreshTabAction] Cannot find chat window for selected tab: " + selectedContent.getDisplayName());
        }
        return chatWindow;
    }
}
