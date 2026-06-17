package com.github.claudecodegui.ui.toolwindow;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tab 会话恢复状态测试。
 * 用于验证自动恢复与手动强制刷新复用的恢复请求调度逻辑，避免前端重复 ready 时反复加载历史，
 * 同时保证窗口内存消息为空时仍然可以补触发一次会话恢复。
 */
public class TabSessionRestoreStateTest {

    /**
     * 验证空 sessionId 不应生成恢复请求。
     */
    @Test
    public void shouldIgnoreRestoreWhenSessionIdIsEmpty() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.schedulePersistedRestore(null, "/workspace/demo");
        state.schedulePersistedRestore("   ", "/workspace/demo");

        assertFalse(state.hasPendingRestoreRequest());
        assertNull(state.consumePendingRestoreRequest());
    }

    /**
     * 验证持久化恢复请求只能被消费一次，直到重新调度。
     */
    @Test
    public void shouldConsumePersistedRestoreOnlyOnceUntilRescheduled() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.schedulePersistedRestore("session-1", "/workspace/demo");

        TabSessionRestoreState.RestoreRequest firstRequest = state.consumePendingRestoreRequest();
        TabSessionRestoreState.RestoreRequest secondRequest = state.consumePendingRestoreRequest();

        assertNotNull(firstRequest);
        assertEquals("session-1", firstRequest.getSessionId());
        assertEquals("/workspace/demo", firstRequest.getProjectPath());
        assertEquals(TabSessionRestoreState.RESTORE_SOURCE_STARTUP, firstRequest.getRestoreSource());
        assertEquals(TabSessionRestoreState.RestoreLifecycleStatus.PENDING, state.getRestoreLifecycleStatus());
        assertFalse(firstRequest.isManualRefreshTriggered());
        assertNull(secondRequest);
    }

    /**
     * 验证当窗口内已经有消息时，手动强制刷新不需要额外触发历史恢复。
     */
    @Test
    public void shouldSkipManualRestoreWhenMessagesAlreadyExist() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.scheduleManualRestoreIfNeeded("session-2", "/workspace/demo", true);

        assertFalse(state.hasPendingRestoreRequest());
        assertNull(state.consumePendingRestoreRequest());
    }

    /**
     * 验证当窗口内没有消息时，手动强制刷新会补调一次恢复请求。
     */
    @Test
    public void shouldScheduleManualRestoreWhenMessagesAreMissing() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.scheduleManualRestoreIfNeeded("session-3", "/workspace/demo", false);

        TabSessionRestoreState.RestoreRequest request = state.consumePendingRestoreRequest();

        assertNotNull(request);
        assertEquals("session-3", request.getSessionId());
        assertEquals("/workspace/demo", request.getProjectPath());
        assertEquals(TabSessionRestoreState.RESTORE_SOURCE_MANUAL_REFRESH, request.getRestoreSource());
        assertEquals(TabSessionRestoreState.RestoreLifecycleStatus.PENDING, state.getRestoreLifecycleStatus());
        assertTrue(request.isManualRefreshTriggered());
    }

    /**
     * 验证恢复启动、完成与失败的显式状态机会被正确更新。
     * 该测试用于覆盖本轮新增的恢复生命周期语义，确保后端日志与前端 ready 收口可以读取稳定状态，
     * 不会长期停留在 restoring 或丢失失败态。
     */
    @Test
    public void shouldTrackRestoreLifecycleStatusTransitions() {
        TabSessionRestoreState state = new TabSessionRestoreState();

        state.schedulePersistedRestore("session-4", "/workspace/demo", "codex", "codex");
        assertEquals(TabSessionRestoreState.RestoreLifecycleStatus.PENDING, state.getRestoreLifecycleStatus());
        assertEquals("codex", state.getLastRestoreRuntimeFamily());
        assertEquals("startup", state.getLastRestoreSource());

        state.markRestoreStarted();
        assertEquals(TabSessionRestoreState.RestoreLifecycleStatus.RESTORING, state.getRestoreLifecycleStatus());

        state.markRestoreFinished("session-4");
        assertEquals(TabSessionRestoreState.RestoreLifecycleStatus.RESTORED, state.getRestoreLifecycleStatus());
        assertEquals("session-4", state.getLastRestoreSessionId());

        state.markRestoreFailed();
        assertEquals(TabSessionRestoreState.RestoreLifecycleStatus.FAILED, state.getRestoreLifecycleStatus());
    }
}
