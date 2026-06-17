package com.github.claudecodegui.session;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * {@link SessionRuntimeFamily} 解析规则测试。
 * 该测试用于固定本轮会话恢复改造中最关键的 provider 语义约束：
 * 1. 显式 runtimeFamily 永远优先于展示 provider。
 * 2. Minimax 在当前阶段默认复用 Codex 恢复主干。
 * 3. 普通 Claude 会话在无显式覆盖、无 Codex binding 时仍保持 Claude 主干。
 */
public class SessionRuntimeFamilyTest {

    /**
     * 验证显式 runtimeFamily 必须优先于展示 provider。
     * 即使展示 provider 为 minimax，只要未来历史记录已经显式标注为 claude，
     * 恢复链路也必须尊重该显式记录，避免短期兼容规则覆盖长期正确语义。
     */
    @Test
    public void shouldPreferExplicitRuntimeFamilyOverDisplayProvider() {
        String runtimeFamily = SessionRuntimeFamily.resolve(
                "minimax",
                "claude",
                new CodexSessionBinding("minimax-provider", "MiniMax-M1", "codex_sdk", "provider", "managed_provider")
        );

        assertEquals(SessionRuntimeFamily.CLAUDE, runtimeFamily);
    }

    /**
     * 验证 Minimax 在当前阶段默认复用 Codex 恢复主干。
     * 当旧数据尚未显式写入 runtimeFamily 时，只要历史项展示 provider 为 minimax，
     * 就应按当前产品约束优先回到 Codex 家族，避免新建标签页绑定 MiniMax 历史后误走 Claude 分支。
     */
    @Test
    public void shouldResolveMinimaxDisplayProviderToCodexByDefault() {
        String runtimeFamily = SessionRuntimeFamily.resolve("minimax", null, null);

        assertEquals(SessionRuntimeFamily.CODEX, runtimeFamily);
    }

    /**
     * 验证普通 Claude 展示 provider 在无显式覆盖、无 Codex binding 时保持 Claude 主干。
     * 该断言用于约束本轮兼容规则不会意外扩大作用范围，避免把非 MiniMax、非 Codex 会话错判为 Codex。
     */
    @Test
    public void shouldKeepClaudeDisplayProviderOnClaudeRuntimeFamilyWithoutBinding() {
        String runtimeFamily = SessionRuntimeFamily.resolve("claude", null, null);

        assertEquals(SessionRuntimeFamily.CLAUDE, runtimeFamily);
    }
}
