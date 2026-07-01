package com.github.claudecodegui.handler.history;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 历史标题同步协调器测试。
 * 用于验证只有跟随会话标题模式的窗口会被自动同步，
 * 手动自定义标题的窗口不会被历史标题覆盖。
 */
public class HistoryTitleSyncCoordinatorTest {

    /**
     * 验证仅同步跟随会话标题模式的窗口。
     */
    @Test
    public void shouldSyncOnlyTabsThatFollowSessionTitle() {
        List<String> updatedWindows = new ArrayList<>();

        HistoryTitleSyncCoordinator coordinator = new HistoryTitleSyncCoordinator((sessionId, newTitle, updater) -> {
            updater.tryUpdate(new FakeTitleSyncTarget("FOLLOW_SESSION_TITLE", updatedWindows, "window-1"), newTitle);
            updater.tryUpdate(new FakeTitleSyncTarget("MANUAL_CUSTOM", updatedWindows, "window-2"), newTitle);
        });

        coordinator.syncTitles("session-1", "新标题");

        assertEquals(1, updatedWindows.size());
        assertEquals("window-1:新标题", updatedWindows.get(0));
    }

    private static final class FakeTitleSyncTarget implements HistoryTitleSyncCoordinator.TitleSyncTarget {
        private final String titleBindingMode;
        private final List<String> updatedWindows;
        private final String name;

        private FakeTitleSyncTarget(String titleBindingMode, List<String> updatedWindows, String name) {
            this.titleBindingMode = titleBindingMode;
            this.updatedWindows = updatedWindows;
            this.name = name;
        }

        @Override
        public String getTitleBindingMode() {
            return titleBindingMode;
        }

        @Override
        public void updateTitle(String newTitle) {
            updatedWindows.add(name + ":" + newTitle);
        }
    }
}
