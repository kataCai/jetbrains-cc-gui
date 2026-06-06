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

    /**
     * 验证 Tab 快照复制时会保留 Codex 会话绑定相关字段。
     * 这是历史会话在 Tab 级恢复 provider/model 绑定的前提，避免只复制标题却丢失运行时来源信息。
     */
    @Test
    public void shouldCopyCodexBindingFieldsWhenDuplicatingSessionState() {
        TabStateService.TabSessionState state = new TabStateService.TabSessionState();
        state.codexProviderId = "minimax-provider";
        state.codexRequestMode = "codex_sdk";
        state.codexBaseUrlSource = "provider";
        state.codexEffectiveConfigSource = "managed_provider";

        TabStateService.TabSessionState copied = state.copy();

        assertEquals("minimax-provider", copied.codexProviderId);
        assertEquals("codex_sdk", copied.codexRequestMode);
        assertEquals("provider", copied.codexBaseUrlSource);
        assertEquals("managed_provider", copied.codexEffectiveConfigSource);
    }
}
