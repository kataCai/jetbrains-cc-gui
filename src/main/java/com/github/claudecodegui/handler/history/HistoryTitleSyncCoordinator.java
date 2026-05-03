package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.settings.TabStateService;
import org.jetbrains.annotations.NotNull;

/**
 * 历史会话标题同步协调器。
 * 负责在后端确认会话标题更新成功后，按 sessionId 将新的会话标题同步到
 * 所有关联窗口，并基于标题绑定模式决定哪些窗口允许被自动覆盖。
 */
final class HistoryTitleSyncCoordinator {

    private final TitleSyncDispatcher dispatcher;

    HistoryTitleSyncCoordinator(@NotNull TitleSyncDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 同步指定会话的标题。
     *
     * @param sessionId 会话 ID
     * @param newTitle 新标题
     */
    void syncTitles(@NotNull String sessionId, @NotNull String newTitle) {
        if (sessionId.trim().isEmpty() || newTitle.trim().isEmpty()) {
            return;
        }
        dispatcher.dispatch(sessionId.trim(), newTitle.trim(), new TitleUpdater());
    }

    interface TitleSyncDispatcher {
        void dispatch(String sessionId, String newTitle, TitleUpdater updater);
    }

    interface TitleSyncTarget {
        String getTitleBindingMode();
        void updateTitle(String newTitle);
    }

    static final class TitleUpdater {
        /**
         * 仅在目标仍处于“跟随会话标题”模式时更新其标题。
         *
         * @param target 目标窗口
         * @param newTitle 新标题
         */
        void tryUpdate(@NotNull TitleSyncTarget target, @NotNull String newTitle) {
            if (TabStateService.TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE.equals(target.getTitleBindingMode())) {
                target.updateTitle(newTitle);
            }
        }
    }
}
