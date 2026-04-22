package com.github.claudecodegui.startup;

import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.ui.toolwindow.ToolWindowLifecycleDisposableService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 在项目启动后初始化远程协作通道，并在最后一个项目关闭时安全回收 polling 资源。
 */
public class RemoteCollabStartupActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(RemoteCollabStartupActivity.class);

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        registerProjectCloseListener(project);
        ApplicationManager.getApplication().executeOnPooledThread(() -> initializeRemoteCollab(project));
        return Unit.INSTANCE;
    }

    private void initializeRemoteCollab(@NotNull Project project) {
        try {
            CodemossSettingsService settingsService = new CodemossSettingsService();
            RemoteCollabService remoteCollabService = RemoteCollabService.getInstance();
            remoteCollabService.initializeIfEnabled(settingsService);
            ClaudeNotifier.updateRemoteCollabStatus(
                project,
                remoteCollabService.getConnectionStatus(),
                remoteCollabService.isCurrentInstanceReceivingUpdates()
            );
        } catch (Exception e) {
            LOG.warn("[RemoteCollabStartup] Failed to initialize remote collaboration: " + e.getMessage(), e);
        }
    }

    private void registerProjectCloseListener(@NotNull Project project) {
        ToolWindowLifecycleDisposableService lifecycleDisposable = ToolWindowLifecycleDisposableService.getInstance(project);
        if (!lifecycleDisposable.markRemoteCollabCloseListenerRegistered()) {
            return;
        }
        project.getMessageBus().connect(lifecycleDisposable).subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
            @Override
            public void projectClosing(@NotNull Project closingProject) {
                if (!closingProject.equals(project)) {
                    return;
                }
                if (ProjectManager.getInstance().getOpenProjects().length <= 1) {
                    RemoteCollabService.getInstance().shutdown();
                    ClaudeNotifier.updateRemoteCollabStatus(project, "disabled", false);
                }
            }
        });
    }
}
