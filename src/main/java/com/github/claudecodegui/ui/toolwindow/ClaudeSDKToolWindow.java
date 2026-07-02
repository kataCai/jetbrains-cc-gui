package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.TabStateService;
import com.github.claudecodegui.startup.BridgePreloader;
import com.github.claudecodegui.ui.detached.DetachedWindowManager;
import com.github.claudecodegui.util.PlatformUtils;
import com.intellij.openapi.actionSystem.ActionGroup;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.impl.ToolWindowImpl;
import com.intellij.openapi.wm.impl.content.BaseLabel;
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi;
import com.intellij.ui.JBColor;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Claude SDK 工具窗口。
 * 负责 CCG 工具窗口的初始化、Tab 恢复、生命周期清理以及工具栏行为组装。
 * 实现 DumbAware 以支持在 IDE 索引构建期间使用。
 */
public class ClaudeSDKToolWindow implements ToolWindowFactory, DumbAware {

    private static final Logger LOG = Logger.getInstance(ClaudeSDKToolWindow.class);
    public static final String TOOL_WINDOW_ID = "CCG";
    public static final String TOOL_WINDOW_DISPLAY_NAME = "CC GUI";
    private static final Map<Project, ClaudeChatWindow> instances = new ConcurrentHashMap<>();
    private static final Map<Content, ClaudeChatWindow> contentToWindowMap = new ConcurrentHashMap<>();
    private static volatile boolean shutdownHookRegistered = false;
    private static final String TAB_NAME_PREFIX = "AI";
    private static final Set<Content> detachingContents =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    public static ClaudeChatWindow getChatWindow(Project project) {
        return instances.get(project);
    }

    public static String getNextTabName(ToolWindow toolWindow) {
        if (toolWindow == null) {
            return TAB_NAME_PREFIX + "1";
        }

        ContentManager contentManager = toolWindow.getContentManager();
        int maxNumber = 0;

        for (Content content : contentManager.getContents()) {
            String displayName = content.getDisplayName();
            if (displayName != null && displayName.startsWith(TAB_NAME_PREFIX)) {
                try {
                    int number = Integer.parseInt(displayName.substring(TAB_NAME_PREFIX.length()));
                    if (number > maxNumber) {
                        maxNumber = number;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        return TAB_NAME_PREFIX + (maxNumber + 1);
    }

    static void registerWindow(Project project, ClaudeChatWindow window) {
        synchronized (instances) {
            ClaudeChatWindow oldInstance = instances.get(project);
            if (oldInstance != null && oldInstance != window) {
                LOG.warn("Window instance already exists for project " + project.getName() + ", replacing old instance");
                oldInstance.dispose();
            }
            instances.put(project, window);
        }
    }

    static void unregisterWindow(Project project, ClaudeChatWindow window) {
        synchronized (instances) {
            if (instances.get(project) == window) {
                instances.remove(project);
            }
        }
    }

    static void registerContentMapping(Content content, ClaudeChatWindow window) {
        contentToWindowMap.put(content, window);
    }

    static void unregisterContentMapping(Content content) {
        contentToWindowMap.remove(content);
    }

    private static Set<ClaudeChatWindow> collectProjectChatWindows(@NotNull Project project) {
        Set<ClaudeChatWindow> windows = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        ClaudeChatWindow mainWindow = instances.get(project);
        if (mainWindow != null) {
            windows.add(mainWindow);
        }
        for (ClaudeChatWindow window : contentToWindowMap.values()) {
            if (window != null && project.equals(window.getProject())) {
                windows.add(window);
            }
        }
        windows.addAll(DetachedWindowManager.getAllDetachedChatWindows(project));
        return windows;
    }

    private static Set<ClaudeChatWindow> collectAllChatWindows() {
        Set<ClaudeChatWindow> windows = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        windows.addAll(instances.values());
        windows.addAll(contentToWindowMap.values());
        windows.addAll(DetachedWindowManager.getAllDetachedChatWindows());
        return windows;
    }

    private static String resolveRestoredTabName(@NotNull TabStateService tabStateService, int index) {
        String savedName = tabStateService.getTabName(index);
        if (savedName != null && !savedName.isEmpty()) {
            LOG.info("[TabManager] Restored tab " + index + " name from storage: " + savedName);
            return savedName;
        }
        return TAB_NAME_PREFIX + (index + 1);
    }

    private static void cleanupWindowProcesses(@NotNull ClaudeChatWindow window) {
        try {
            if (window.getClaudeSDKBridge() != null) {
                window.getClaudeSDKBridge().cleanupAllProcesses();
            }
            if (window.getCodexSDKBridge() != null) {
                window.getCodexSDKBridge().cleanupAllProcesses();
            }
        } catch (Exception e) {
            LOG.error("[ShutdownHook] Error cleaning up processes: " + e.getMessage(), e);
        }
    }

    private static void disposeProjectChatWindows(@NotNull Project project) {
        Set<ClaudeChatWindow> windows = collectProjectChatWindows(project);
        if (windows.isEmpty()) {
            return;
        }
        LOG.info("[ToolWindow] Disposing " + windows.size() + " chat window(s) for project: " + project.getName());
        for (ClaudeChatWindow window : new HashSet<>(windows)) {
            if (window != null && !window.isDisposed()) {
                try {
                    window.dispose();
                } catch (Exception e) {
                    LOG.error("[ToolWindow] Failed to dispose chat window for project: " + project.getName(), e);
                }
            }
        }
    }

    /**
     * Mark a Content as being detached (moving to a floating window).
     * This prevents the contentRemoved listener from disposing the associated ClaudeChatWindow.
     */
    public static void markContentAsDetaching(Content content) {
        detachingContents.add(content);
    }

    public static void unmarkContentAsDetaching(Content content) {
        detachingContents.remove(content);
    }

    static boolean isContentDetaching(Content content) {
        return detachingContents.contains(content);
    }

    public static ClaudeChatWindow getChatWindowForContent(Content content) {
        return content != null ? contentToWindowMap.get(content) : null;
    }

    /**
     * 按 sessionId 同步当前项目内所有匹配窗口的页签标题。
     * 仅仍处于“跟随会话标题”模式的窗口会被自动覆盖，手动自定义标题的窗口保持不变。
     *
     * @param project 当前项目
     * @param sessionId 会话 ID
     * @param newTitle 新标题
     */
    public static void syncTabTitlesBySessionId(@NotNull Project project, @NotNull String sessionId, @NotNull String newTitle) {
        String normalizedSessionId = normalize(sessionId);
        String normalizedTitle = normalize(newTitle);
        if (normalizedSessionId == null || normalizedTitle == null) {
            LOG.warn("[HistoryTitleSync] Skip sync because sessionId or title is empty. sessionId="
                    + sessionId + ", title=" + newTitle);
            return;
        }

        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            LOG.warn("[HistoryTitleSync] Skip sync because tool window is null. sessionId=" + normalizedSessionId);
            return;
        }

        Content[] contents = toolWindow.getContentManager().getContents();
        TabStateService tabStateService = TabStateService.getInstance(project);
        LOG.info("[HistoryTitleSync] Start syncing tab titles. sessionId=" + normalizedSessionId
                + ", title=" + normalizedTitle + ", tabCount=" + contents.length);
        for (int index = 0; index < contents.length; index++) {
            Content content = contents[index];
            TabStateService.TabSessionState savedState = tabStateService.getTabSessionState(index);
            String boundSessionId = savedState != null ? normalize(savedState.sessionId) : null;
            String bindingMode = savedState != null ? savedState.getEffectiveTitleBindingMode() : null;
            LOG.info("[HistoryTitleSync] Inspect tab. index=" + index
                    + ", displayName=" + content.getDisplayName()
                    + ", boundSessionId=" + boundSessionId
                    + ", bindingMode=" + bindingMode);
            if (savedState == null || !normalizedSessionId.equals(boundSessionId)) {
                continue;
            }
            if (!TabStateService.TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE.equals(bindingMode)) {
                LOG.info("[HistoryTitleSync] Skip tab because binding mode is not follow-session. index=" + index
                        + ", bindingMode=" + bindingMode);
                continue;
            }

            ClaudeChatWindow chatWindow = getChatWindowForContent(content);
            if (chatWindow != null) {
                LOG.info("[HistoryTitleSync] Sync tab via chat window. index=" + index
                        + ", oldTitle=" + content.getDisplayName()
                        + ", newTitle=" + normalizedTitle);
                chatWindow.syncTabTitleFromSessionTitle(normalizedTitle);
            } else {
                LOG.info("[HistoryTitleSync] Sync tab via content fallback. index=" + index
                        + ", oldTitle=" + content.getDisplayName()
                        + ", newTitle=" + normalizedTitle);
                content.setDisplayName(normalizedTitle);
                tabStateService.saveTabName(index, normalizedTitle);
                tabStateService.saveTabTitleBindingMode(index, TabStateService.TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE);
            }
        }
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    @Override
    public void init(@NotNull ToolWindow toolWindow) {
        toolWindow.setTitle(TOOL_WINDOW_DISPLAY_NAME);
        toolWindow.setStripeTitle(TOOL_WINDOW_DISPLAY_NAME);
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        registerShutdownHook();

        ContentFactory contentFactory = ContentFactory.getInstance();
        ContentManager contentManager = toolWindow.getContentManager();

        if (BridgePreloader.isBridgeReady()) {
            LOG.info("[ToolWindow] ai-bridge ready, creating chat window directly");
            createChatWindowContent(project, contentFactory, contentManager);
        } else {
            LOG.info("[ToolWindow] ai-bridge not ready, showing loading panel");
            JPanel loadingPanel = createLoadingPanel();
            Content loadingContent = contentFactory.createContent(loadingPanel, TAB_NAME_PREFIX + "1", false);
            contentManager.addContent(loadingContent);

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    BridgePreloader.getSharedResolver().findSdkDir();
                    CompletableFuture<Boolean> future = BridgePreloader.waitForBridgeAsync();
                    Boolean ready = future.get(60, TimeUnit.SECONDS);

                    if (project.isDisposed()) { return; }

                    ToolWindowManager.getInstance(project).invokeLater(() -> {
                        if (project.isDisposed()) { return; }

                        if (ready != null && ready) {
                            LOG.info("[ToolWindow] ai-bridge ready, replacing loading panel with chat window");
                            replaceLoadingPanelWithChatWindow(project, contentFactory, contentManager, loadingContent);
                        } else {
                            LOG.error("[ToolWindow] ai-bridge preparation failed");
                            updateLoadingPanelWithError(loadingPanel, "AI Bridge preparation failed. Please restart IDE.");
                        }
                    });
                } catch (TimeoutException e) {
                    LOG.error("[ToolWindow] ai-bridge preparation timeout");
                    ToolWindowManager.getInstance(project).invokeLater(() -> {
                        if (!project.isDisposed()) {
                            updateLoadingPanelWithError(loadingPanel, "AI Bridge preparation timeout. Please restart IDE.");
                        }
                    });
                } catch (Exception e) {
                    LOG.error("[ToolWindow] ai-bridge preparation error: " + e.getMessage());
                    ToolWindowManager.getInstance(project).invokeLater(() -> {
                        if (!project.isDisposed()) {
                            updateLoadingPanelWithError(loadingPanel, "Error: " + e.getMessage());
                        }
                    });
                }
            });
        }

        if (PlatformUtils.isPluginDevMode()) {
            AnAction devToolsAction =
                    ActionManager.getInstance()
                            .getAction("ClaudeCodeGUI.OpenDevToolsAction");
            if (devToolsAction != null) {
                toolWindow.setTitleActions(java.util.List.of(devToolsAction));
            }
        }

        AnAction renameTabAction =
                ActionManager.getInstance()
                        .getAction("ClaudeCodeGUI.RenameTabAction");
        AnAction detachTabAction =
                ActionManager.getInstance()
                        .getAction("ClaudeCodeGUI.DetachTabAction");
        AnAction forceRefreshTabAction =
                ActionManager.getInstance()
                        .getAction("ClaudeCodeGUI.ForceRefreshTabAction");
        com.intellij.openapi.actionSystem.AnAction saveAsTemplateAction =
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                        .getAction("ClaudeCodeGUI.SaveAsTemplateAction");
        com.intellij.openapi.actionSystem.AnAction createFromTemplateAction =
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                        .getAction("ClaudeCodeGUI.CreateFromTemplateAction");

        DefaultActionGroup gearActions = new DefaultActionGroup();
        if (forceRefreshTabAction != null) {
            gearActions.add(forceRefreshTabAction);
        }
        if (renameTabAction != null) {
            gearActions.add(renameTabAction);
        }
        if (detachTabAction != null) {
            gearActions.add(detachTabAction);
        }
        if (saveAsTemplateAction != null) {
            gearActions.addSeparator();
            gearActions.add(saveAsTemplateAction);
        }
        if (createFromTemplateAction != null) {
            gearActions.add(createFromTemplateAction);
        }
        toolWindow.setAdditionalGearActions(gearActions);
        installTabPopupMenu(toolWindow, gearActions);

        registerProjectCloseListener(project);

        contentManager.addContentManagerListener(new ContentManagerListener() {
            @Override
            public void contentAdded(@NotNull ContentManagerEvent event) {
                updateTabCloseableState(contentManager);
                TabStateService tabStateService = TabStateService.getInstance(project);
                tabStateService.saveTabCount(contentManager.getContentCount());
            }

            @Override
            public void selectionChanged(@NotNull ContentManagerEvent event) {
                // 历史恢复已统一收敛到前端 ready 后的 pending restore 主链，
                // 切换标签页时不应再补跑旧的 `loadFromServer()`，否则会在手动重绑后产生晚到快照。
            }

            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                updateTabCloseableState(contentManager);

                Content removedContent = event.getContent();
                if (isContentDetaching(removedContent)) {
                    LOG.info("[TabManager] Tab detaching to floating window, skipping dispose: "
                        + removedContent.getDisplayName());
                    return;
                }

                int removedIndex = event.getIndex();
                TabStateService tabStateService = TabStateService.getInstance(project);
                tabStateService.onTabRemoved(removedIndex);

                ClaudeChatWindow window = contentToWindowMap.get(removedContent);
                if (window != null) {
                    LOG.info("[TabManager] Disposing ClaudeChatWindow for removed tab: "
                        + removedContent.getDisplayName());
                    window.dispose();
                }
            }

            @Override
            public void contentRemoveQuery(@NotNull ContentManagerEvent event) {
                Content content = event.getContent();
                if (isContentDetaching(content)) {
                    return;
                }

                String tabName = content.getDisplayName();
                int result = com.intellij.openapi.ui.Messages.showYesNoDialog(
                    project,
                    ClaudeCodeGuiBundle.message("tab.close.confirm.message", tabName),
                    ClaudeCodeGuiBundle.message("tab.close.confirm.title"),
                    ClaudeCodeGuiBundle.message("tab.close.confirm.yes"),
                    ClaudeCodeGuiBundle.message("tab.close.confirm.no"),
                    com.intellij.openapi.ui.Messages.getQuestionIcon()
                );

                if (result != com.intellij.openapi.ui.Messages.YES) {
                    event.consume();
                }
            }
        });

        updateTabCloseableState(contentManager);
    }

    /**
     * 根据当前 Tab 数量更新可关闭状态。
     * 仅有多 Tab 场景允许关闭，避免单 Tab 时用户误关闭唯一窗口。
     *
     * @param contentManager Tab 内容管理器
     */
    private void updateTabCloseableState(ContentManager contentManager) {
        int tabCount = contentManager.getContentCount();
        boolean closeable = tabCount > 1;

        for (Content tab : contentManager.getContents()) {
            tab.setCloseable(closeable);
        }

        LOG.debug("[TabManager] Updated tab closeable state: count=" + tabCount + ", closeable=" + closeable);
    }

    /**
     * 为工具窗口 Tab 头部安装右键菜单。
     * 当前问题发生时，用户往往只能与页签区域交互，因此这里直接把“强制刷新窗口”等页签动作挂到头部右键，
     * 作为空白界面场景下的人工恢复入口。该实现依赖 IntelliJ 2024.3 的 ToolWindowContentUi 结构。
     *
     * @param toolWindow 当前工具窗口
     * @param popupActions 页签右键菜单动作组
     */
    private void installTabPopupMenu(@NotNull ToolWindow toolWindow, @NotNull ActionGroup popupActions) {
        if (!(toolWindow instanceof ToolWindowImpl toolWindowImpl)) {
            return;
        }

        ToolWindowContentUi contentUi = resolveContentUi(toolWindowImpl);
        if (contentUi == null) {
            LOG.warn("[TabPopup] ToolWindowContentUi not found, skipping tab popup installation");
            return;
        }

        JComponent tabComponent = contentUi.getTabComponent();
        if (Boolean.TRUE.equals(tabComponent.getClientProperty("ccg.tab.popup.installed"))) {
            return;
        }

        tabComponent.putClientProperty("ccg.tab.popup.installed", Boolean.TRUE);
        tabComponent.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                showPopupIfNeeded(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                showPopupIfNeeded(e);
            }

            /**
             * 在检测到平台弹出触发手势时显示页签右键菜单。
             * 先尝试将鼠标位置对应的页签设为选中项，保证后续动作始终作用于用户实际右键的页签。
             *
             * @param event 鼠标事件
             */
            private void showPopupIfNeeded(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }

                selectTabUnderCursor(toolWindow.getContentManager(), tabComponent, event);
                Content selectedContent = toolWindow.getContentManager().getSelectedContent();
                if (selectedContent == null) {
                    return;
                }

                contentUi.showContextMenu(tabComponent, event.getX(), event.getY(), popupActions, selectedContent);
                event.consume();
            }
        });
    }

    /**
     * 解析工具窗口内容 UI。
     * 该对象由 IntelliJ 平台维护，负责 Tab 头部组件与上下文菜单展示。
     *
     * @param toolWindowImpl 具体工具窗口实现
     * @return 内容 UI；若当前平台实现不可用则返回 null
     */
    private ToolWindowContentUi resolveContentUi(@NotNull ToolWindowImpl toolWindowImpl) {
        try {
            java.lang.reflect.Field field = ToolWindowImpl.class.getDeclaredField("contentUi");
            field.setAccessible(true);
            return (ToolWindowContentUi) field.get(toolWindowImpl);
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * 根据鼠标位置尽量切换到被右键命中的页签。
     * IntelliJ 页签标签组件本身持有 Content 引用，因此优先通过平台标签对象反查实际页签；
     * 如果当前鼠标位置不在具体标签上，则保留现有选中项。
     *
     * @param contentManager 内容管理器
     * @param tabComponent 页签容器
     * @param event 鼠标事件
     */
    private void selectTabUnderCursor(@NotNull ContentManager contentManager, @NotNull JComponent tabComponent, @NotNull MouseEvent event) {
        Component hitComponent = SwingUtilities.getDeepestComponentAt(tabComponent, event.getX(), event.getY());
        if (hitComponent == null) {
            return;
        }

        Component current = hitComponent;
        while (current != null && current != tabComponent) {
            if (current instanceof BaseLabel label) {
                Content content = label.getContent();
                if (content != null && contentManager.getIndexOfContent(content) >= 0) {
                    contentManager.setSelectedContent(content, true, true);
                }
                return;
            }
            current = current.getParent();
        }
    }

    private JPanel createLoadingPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(com.github.claudecodegui.util.ThemeConfigService.getBackgroundColor());

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        JLabel iconLabel = new JLabel("⚙");
        iconLabel.setFont(iconLabel.getFont().deriveFont(48f));
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(iconLabel);

        centerPanel.add(Box.createVerticalStrut(16));

        JLabel textLabel = new JLabel(ClaudeCodeGuiBundle.message("toolwindow.preparingBridge"));
        textLabel.setFont(textLabel.getFont().deriveFont(14f));
        textLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(textLabel);

        panel.add(centerPanel);
        return panel;
    }

    private void updateLoadingPanelWithError(JPanel loadingPanel, String errorMessage) {
        loadingPanel.removeAll();

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        JLabel iconLabel = new JLabel("⚠");
        iconLabel.setFont(iconLabel.getFont().deriveFont(48f));
        iconLabel.setForeground(JBColor.ORANGE);
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(iconLabel);

        centerPanel.add(Box.createVerticalStrut(16));

        JLabel textLabel = new JLabel(errorMessage);
        textLabel.setFont(textLabel.getFont().deriveFont(14f));
        textLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        centerPanel.add(textLabel);

        loadingPanel.add(centerPanel);
        loadingPanel.revalidate();
        loadingPanel.repaint();
    }

    /**
     * 将启动阶段的 loading 面板替换为真实聊天窗口，并按已保存的标签页状态恢复首个会话。
     * 这里的首个标签页已经被展示给用户，因此需要立即触发历史恢复，避免出现空白页或还原延迟。
     *
     * @param project 当前项目
     * @param contentFactory 内容工厂
     * @param contentManager ToolWindow 内容管理器
     * @param loadingContent 启动阶段占位的 loading content
     */
    private void replaceLoadingPanelWithChatWindow(
            @NotNull Project project,
            ContentFactory contentFactory,
            ContentManager contentManager,
            Content loadingContent
    ) {
        TabStateService tabStateService = TabStateService.getInstance(project);
        int savedTabCount = tabStateService.getTabCount();
        LOG.info("[TabManager] Restoring " + savedTabCount + " tabs from storage");

        TabStateService.TabSessionState firstSavedState = tabStateService.getTabSessionState(0);
        ClaudeChatWindow firstChatWindow = new ClaudeChatWindow(project, false);
        String firstTabName = resolveRestoredTabName(tabStateService, 0);

        loadingContent.setComponent(firstChatWindow.getContent());
        loadingContent.setDisplayName(firstTabName);
        firstChatWindow.setParentContent(loadingContent);
        firstChatWindow.setOriginalTabName(firstTabName);
        loadingContent.setDisposer(firstChatWindow::dispose);
        restoreTabSessionState(firstSavedState, 0, firstChatWindow, true);

        for (int i = 1; i < savedTabCount; i++) {
            TabStateService.TabSessionState savedState = tabStateService.getTabSessionState(i);
            ClaudeChatWindow chatWindow = new ClaudeChatWindow(project, true);
            String tabName = resolveRestoredTabName(tabStateService, i);

            Content content = contentFactory.createContent(chatWindow.getContent(), tabName, false);
            chatWindow.setParentContent(content);
            chatWindow.setOriginalTabName(tabName);
            content.setDisposer(chatWindow::dispose);
            contentManager.addContent(content);
            restoreTabSessionState(savedState, i, chatWindow, false);
        }

        updateTabCloseableState(contentManager);
    }

    /**
     * 按持久化状态创建聊天窗口内容。
     * 首个标签页在不经过 loading 面板时也要按“当前可见页”处理，因此保留是否立即恢复历史的显式参数。
     *
     * @param project 当前项目
     * @param contentFactory 内容工厂
     * @param contentManager ToolWindow 内容管理器
     */
    private void createChatWindowContent(
            @NotNull Project project,
            ContentFactory contentFactory,
            ContentManager contentManager
    ) {
        TabStateService tabStateService = TabStateService.getInstance(project);
        int savedTabCount = tabStateService.getTabCount();
        LOG.info("[TabManager] Restoring " + savedTabCount + " tabs from storage");

        for (int i = 0; i < savedTabCount; i++) {
            boolean isFirstTab = (i == 0);
            TabStateService.TabSessionState savedState = tabStateService.getTabSessionState(i);
            ClaudeChatWindow chatWindow = new ClaudeChatWindow(project, !isFirstTab);
            String tabName = resolveRestoredTabName(tabStateService, i);

            Content content = contentFactory.createContent(chatWindow.getContent(), tabName, false);
            chatWindow.setParentContent(content);
            chatWindow.setOriginalTabName(tabName);
            content.setDisposer(chatWindow::dispose);
            contentManager.addContent(content);
            restoreTabSessionState(savedState, i, chatWindow, isFirstTab);
        }

        updateTabCloseableState(contentManager);
    }

    /**
     * 恢复单个标签页的会话绑定关系。
     * 历史消息恢复已经统一收敛到前端 ready 后的 pending restore 主链，
     * 因此这里的 `loadImmediately` 仅保留给既有调用点做兼容透传，不再驱动旧的即时历史恢复。
     *
     * @param savedState 持久化的标签页状态
     * @param tabIndex 当前标签页索引，仅用于日志定位
     * @param chatWindow 目标聊天窗口
     * @param loadImmediately 兼容旧调用语义的标记，不再触发旧恢复链路
     */
    private void restoreTabSessionState(
            TabStateService.TabSessionState savedState,
            int tabIndex,
            ClaudeChatWindow chatWindow,
            boolean loadImmediately
    ) {
        if (savedState == null) {
            return;
        }
        chatWindow.restorePersistedTabSessionState(savedState, loadImmediately);
        LOG.info("[TabManager] Restored tab " + tabIndex + " session binding from storage");
    }

    private static synchronized void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("[ShutdownHook] IDEA is shutting down, cleaning up all Node.js processes...");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = executor.submit(() -> {
                    for (ClaudeChatWindow window : collectAllChatWindows()) {
                        if (window != null) {
                            cleanupWindowProcesses(window);
                        }
                    }
                });

                future.get(3, TimeUnit.SECONDS);
                LOG.info("[ShutdownHook] Node.js process cleanup completed");
            } catch (TimeoutException e) {
                LOG.warn("[ShutdownHook] Process cleanup timed out (3s), forcing exit");
            } catch (Exception e) {
                LOG.error("[ShutdownHook] Process cleanup failed: " + e.getMessage());
            } finally {
                executor.shutdownNow();
            }
        }, "Claude-Process-Cleanup-Hook"));

        LOG.info("[ShutdownHook] JVM Shutdown Hook registered");
    }

    private static final CodeSnippetManager codeSnippetManager = new CodeSnippetManager(instances, contentToWindowMap);

    public static void addSelectionFromExternal(Project project, String selectionInfo) {
        codeSnippetManager.addSelectionFromExternal(project, selectionInfo);
    }

    /**
     * Register project closing listener to dispose all chat windows for the project.
     * This ensures proper cleanup when a project is closed.
     *
     * @param project The project to listen to
     */
    private void registerProjectCloseListener(@NotNull Project project) {
        ToolWindowLifecycleDisposableService lifecycleDisposable = ToolWindowLifecycleDisposableService.getInstance(project);
        if (!lifecycleDisposable.markProjectCloseListenerRegistered()) {
            return;
        }
        project.getMessageBus().connect(lifecycleDisposable).subscribe(
                com.intellij.openapi.project.ProjectManager.TOPIC,
                new com.intellij.openapi.project.ProjectManagerListener() {
                    @Override
                    public void projectClosing(@NotNull Project closingProject) {
                        if (closingProject.equals(project)) {
                            LOG.info("[ToolWindow] Project closing, disposing chat windows for: " + project.getName());
                            disposeProjectChatWindows(project);
                        }
                    }
                }
        );
    }
}
