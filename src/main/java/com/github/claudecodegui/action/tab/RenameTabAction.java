package com.github.claudecodegui.action.tab;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.InputValidatorEx;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 当前 CCG ToolWindow 页签重命名动作。
 * 该动作除了更新页签显示名外，还会把标题绑定模式切换为“手动自定义”，
 * 防止后续历史会话标题变更时自动覆盖用户显式指定的 Tab 名称。
 */
public class RenameTabAction extends AnAction implements DumbAware {

    private static final Logger LOG = Logger.getInstance(RenameTabAction.class);

    /**
     * 页签名称最大长度。
     * 该限制用于避免标题过长导致页签区域显示截断和布局抖动。
     */
    private static final int MAX_TAB_NAME_LENGTH = 50;

    public RenameTabAction() {
        super(
            ClaudeCodeGuiBundle.message("action.renameTab.text"),
            ClaudeCodeGuiBundle.message("action.renameTab.description"),
            null
        );
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            LOG.error("[RenameTabAction] Project is null");
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CCG");
        if (toolWindow == null) {
            LOG.error("[RenameTabAction] Tool window not found");
            return;
        }

        ContentManager contentManager = toolWindow.getContentManager();
        Content selectedContent = contentManager.getSelectedContent();
        if (selectedContent == null) {
            LOG.error("[RenameTabAction] No tab selected");
            return;
        }

        String currentName = selectedContent.getDisplayName();

        // 使用当前页签名作为默认值，便于用户在原有基础上微调。
        String newName = Messages.showInputDialog(
            project,
            ClaudeCodeGuiBundle.message("action.renameTab.dialogLabel"),
            ClaudeCodeGuiBundle.message("action.renameTab.dialogTitle"),
            null,
            currentName,
            new TabNameInputValidator()
        );

        if (newName == null) {
            return;
        }

        newName = newName.trim();

        selectedContent.setDisplayName(newName);

        ClaudeChatWindow chatWindow = ClaudeSDKToolWindow.getChatWindowForContent(selectedContent);
        if (chatWindow != null) {
            chatWindow.setOriginalTabName(newName);
        }

        int tabIndex = contentManager.getIndexOfContent(selectedContent);
        if (tabIndex >= 0) {
            TabStateService tabStateService = TabStateService.getInstance(project);
            tabStateService.saveTabName(tabIndex, newName);
            // 用户手动改名后，将当前页签切换到手动标题模式，避免后续历史标题同步覆盖。
            tabStateService.saveTabTitleBindingMode(tabIndex, TabStateService.TITLE_BINDING_MODE_MANUAL_CUSTOM);
        }

        LOG.info(String.format("[RenameTabAction] Renamed tab from '%s' to '%s'", currentName, newName));
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow("CCG");
        if (toolWindow == null) {
            e.getPresentation().setEnabledAndVisible(false);
            return;
        }

        Content selectedContent = toolWindow.getContentManager().getSelectedContent();
        e.getPresentation().setEnabledAndVisible(selectedContent != null);
    }

    /**
     * 页签名称输入校验器。
     * 用于限制空标题和过长标题，避免持久化后出现不可识别或不可展示的页签名称。
     */
    private static class TabNameInputValidator implements InputValidatorEx {
        @Override
        public boolean checkInput(@Nullable String inputString) {
            if (inputString == null) {
                return false;
            }
            String trimmed = inputString.trim();
            return !trimmed.isEmpty() && trimmed.length() <= MAX_TAB_NAME_LENGTH;
        }

        @Override
        public boolean canClose(@Nullable String inputString) {
            return checkInput(inputString);
        }

        @Override
        public @Nullable String getErrorText(@Nullable String inputString) {
            if (inputString == null || inputString.trim().isEmpty()) {
                return ClaudeCodeGuiBundle.message("action.renameTab.error.empty");
            }
            if (inputString.trim().length() > MAX_TAB_NAME_LENGTH) {
                return ClaudeCodeGuiBundle.message("action.renameTab.error.tooLong");
            }
            return null;
        }
    }
}
