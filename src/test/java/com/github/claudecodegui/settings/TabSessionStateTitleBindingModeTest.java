package com.github.claudecodegui.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * TabSessionState 标题绑定模式测试。
 * 用于验证标题来源标记在复制快照时能够被保留，
 * 避免后续会话标题同步时丢失“跟随会话标题/手动自定义”的判断依据。
 */
public class TabSessionStateTitleBindingModeTest {

    /**
     * 验证未显式设置时默认采用跟随会话标题模式。
     */
    @Test
    public void shouldDefaultToFollowSessionTitleWhenModeIsMissing() {
        TabStateService.TabSessionState state = new TabStateService.TabSessionState();

        assertEquals("FOLLOW_SESSION_TITLE", state.getEffectiveTitleBindingMode());
    }

    /**
     * 验证复制快照时会保留标题绑定模式。
     */
    @Test
    public void shouldCopyTitleBindingModeWhenDuplicatingSessionState() {
        TabStateService.TabSessionState state = new TabStateService.TabSessionState();
        state.titleBindingMode = "MANUAL_CUSTOM";

        TabStateService.TabSessionState copied = state.copy();

        assertEquals("MANUAL_CUSTOM", copied.getEffectiveTitleBindingMode());
    }
}
