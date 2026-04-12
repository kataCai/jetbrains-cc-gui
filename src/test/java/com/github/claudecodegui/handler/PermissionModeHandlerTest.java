package com.github.claudecodegui.handler;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 验证 PermissionModeHandler 的模式归一化逻辑。
 * 这组测试重点覆盖默认值、非法值以及 Codex 对 plan 模式的降级行为。
 */
public class PermissionModeHandlerTest {

    @Test
    public void resolvesEmptyModeToDefault() {
        // 空字符串和 null 都不应继续往下游传递，统一折叠成 default。
        assertEquals("default", PermissionModeHandler.resolveEffectivePermissionMode("claude", " "));
        assertEquals("default", PermissionModeHandler.resolveEffectivePermissionMode("claude", null));
    }

    @Test
    public void resolvesInvalidModeToDefault() {
        // 非法模式必须被拒绝，避免把未知值持久化到 session 或设置中。
        assertEquals("default", PermissionModeHandler.resolveEffectivePermissionMode("claude", "dangerouslyAllowEverything"));
    }

    @Test
    public void downgradesCodexPlanToDefault() {
        // Codex 当前不支持 plan 执行模式，因此这里应强制降级。
        assertEquals("default", PermissionModeHandler.resolveEffectivePermissionMode("codex", "plan"));
    }

    @Test
    public void keepsPlanForClaudeProvider() {
        // Claude 仍然允许 plan 原样透传。
        assertEquals("plan", PermissionModeHandler.resolveEffectivePermissionMode("claude", "plan"));
    }
}
