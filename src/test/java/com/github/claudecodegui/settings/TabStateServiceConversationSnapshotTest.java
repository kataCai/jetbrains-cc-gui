package com.github.claudecodegui.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/**
 * 验证 Tab 会话快照在新增逻辑会话字段后的复制与兼容行为。
 * 该测试用于保证 IDE 重启或标签页恢复时，新字段不会因为 copy/兼容回退缺失而丢失。
 */
public class TabStateServiceConversationSnapshotTest {

    /**
     * 验证 copy() 会完整保留逻辑会话和活动分段相关字段。
     * 该用例约束后续 Tab 持久化扩展不会只复制旧字段，导致恢复时无法定位当前逻辑会话主干。
     */
    @Test
    public void shouldCopyLogicalConversationFields() {
        TabStateService.TabSessionState state = new TabStateService.TabSessionState();
        state.provider = "codex";
        state.runtimeFamily = "codex";
        state.sessionId = "session-002";
        state.logicalConversationId = "logical-001";
        state.activeSegmentSessionId = "session-002";
        state.parentSegmentSessionId = "session-001";
        state.continuationPending = true;
        state.continuationSourceSessionId = "session-001";

        TabStateService.TabSessionState copy = state.copy();

        assertEquals("logical-001", copy.logicalConversationId);
        assertEquals("session-002", copy.activeSegmentSessionId);
        assertEquals("session-001", copy.parentSegmentSessionId);
        assertEquals("session-001", copy.continuationSourceSessionId);
        assertEquals(true, copy.continuationPending);
    }

    /**
     * 验证旧快照缺失新字段时，copy() 仍然保持兼容，不会抛异常或伪造脏值。
     * 这可以保证历史用户升级后读取旧 XML 持久化快照时仍能安全回退。
     */
    @Test
    public void shouldKeepNewConversationFieldsNullableForLegacySnapshot() {
        TabStateService.TabSessionState state = new TabStateService.TabSessionState();
        state.provider = "claude";
        state.sessionId = "legacy-session";

        TabStateService.TabSessionState copy = state.copy();

        assertNull(copy.logicalConversationId);
        assertNull(copy.activeSegmentSessionId);
        assertNull(copy.parentSegmentSessionId);
        assertNull(copy.continuationSourceSessionId);
        assertFalse(copy.continuationPending);
    }
}
