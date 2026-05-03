package com.github.claudecodegui.ui.toolwindow;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tab 恢复期快照保护测试。
 * 用于验证在持久化恢复进行中时，不会让空 sessionId 快照覆盖已保存的有效绑定。
 */
public class TabSessionRestoreStatePersistGuardTest {

    /**
     * 验证恢复进行中且已有旧绑定时，应拦截空 sessionId 覆盖。
     */
    @Test
    public void shouldBlockEmptySessionSnapshotWhilePersistedRestoreIsRunning() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.schedulePersistedRestore("persisted-session-1", "/workspace/demo");
        state.markRestoreStarted();

        assertTrue(state.shouldBlockEmptySessionSnapshotOverwrite("persisted-session-1", null));
        assertTrue(state.shouldBlockEmptySessionSnapshotOverwrite("persisted-session-1", "   "));
    }

    /**
     * 验证恢复完成后，空 sessionId 不再被恢复保护拦截。
     */
    @Test
    public void shouldAllowEmptySessionSnapshotAfterRestoreCompletes() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.schedulePersistedRestore("persisted-session-2", "/workspace/demo");
        state.markRestoreStarted();
        state.markRestoreFinished("persisted-session-2");

        assertFalse(state.shouldBlockEmptySessionSnapshotOverwrite("persisted-session-2", null));
    }

    /**
     * 验证没有旧绑定时，不应误拦截正常的新会话持久化。
     */
    @Test
    public void shouldNotBlockWhenNoPersistedSessionBindingExists() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.markRestoreStarted();

        assertFalse(state.shouldBlockEmptySessionSnapshotOverwrite(null, null));
        assertFalse(state.shouldBlockEmptySessionSnapshotOverwrite("   ", null));
    }

    /**
     * 验证仅根据已持久化绑定预热保护后，也应拦截启动早期的空快照覆盖。
     */
    @Test
    public void shouldBlockEmptySnapshotAfterProtectionPrimed() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.primePersistedRestoreProtection("persisted-session-3");

        assertTrue(state.shouldBlockEmptySessionSnapshotOverwrite("persisted-session-3", null));
    }
}
