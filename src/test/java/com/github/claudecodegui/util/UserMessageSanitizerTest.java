package com.github.claudecodegui.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 验证用户可见消息清洗器对内部提示词、continued carryover 和运行环境说明的剥离行为。
 * 这些测试直接约束“发送给模型的内部上下文不能在历史恢复或前台回显时再次暴露给用户”这一核心语义。
 */
public class UserMessageSanitizerTest {

    /**
     * 验证当消息正文由 continued carryover 前缀加真实用户问题组成时，
     * 清洗结果只保留真实用户问题，不回显任何内部续接提示。
     */
    @Test
    public void sanitizeUserFacingTextShouldStripContinuationCarryoverPrefixAndKeepRealQuestion() {
        String original = "## Conversation Continuation\n"
                + "You are continuing an existing conversation in a new runtime segment.\n"
                + "Logical conversation id: logical-001\n"
                + "Previous segment session id: segment-001\n"
                + "Previous conversation summary: continue from summary\n"
                + "Preserve the user's intent and continue from that context unless the latest request overrides it.\n\n"
                + "现在中东局势怎么样了？";

        String sanitized = UserMessageSanitizer.sanitizeUserFacingText(original);

        assertEquals("现在中东局势怎么样了？", sanitized);
    }

    /**
     * 验证只有在命中完整 continued carryover 结构特征时才执行整段剥离，
     * 避免误删用户手写的普通 Markdown 标题。
     */
    @Test
    public void sanitizeUserFacingTextShouldNotStripOrdinaryMarkdownHeadingThatOnlyLooksSimilar() {
        String original = "## Conversation Continuation\n\n"
                + "这是用户自己写的排障笔记，不是系统注入的 continued carryover。";

        String sanitized = UserMessageSanitizer.sanitizeUserFacingText(original);

        assertEquals(original, sanitized);
    }

    /**
     * 验证 continued 前缀升级为 Recent conversation turns 结构后，清洗器仍能正确剥离内部 carryover 块。
     */
    @Test
    public void sanitizeUserFacingTextShouldStripRecentTurnsContinuationCarryoverPrefix() {
        String original = "## Conversation Continuation\n"
                + "You are continuing an existing conversation in a new runtime segment.\n"
                + "Logical conversation id: logical-001\n"
                + "Previous segment session id: segment-001\n"
                + "Recent conversation turns:\n"
                + "User: 1+1=?\n"
                + "Assistant: 2\n"
                + "User: 再+1=?\n"
                + "Assistant: 3\n"
                + "Preserve the user's intent and continue from that context unless the latest request overrides it.\n\n"
                + "再+1=?";

        String sanitized = UserMessageSanitizer.sanitizeUserFacingText(original);

        assertEquals("再+1=?", sanitized);
    }

    /**
     * 验证当前台污染文本前部混入 permissions instructions 与 skills 说明块时，
     * 统一清洗入口会保留后部真实用户输入，而不会把整段内部上下文重新暴露给前台。
     */
    @Test
    public void sanitizeInjectedRequestTextToUserVisibleTextShouldStripLeadingPermissionsAndSkillsPrelude() {
        String original = "<permissions instructions>\n"
                + "Filesystem sandboxing defines which files can be read or written.\n"
                + "</permissions instructions>\n\n"
                + "## Skills\n\n"
                + "### Skill roots\n\n"
                + "- `r0` = `D:/Users/example/.agents/skills`\n\n"
                + "### Available skills\n\n"
                + "- `firecrawl-search`: Search the web. (file: r0/firecrawl-search/SKILL.md)\n\n"
                + "### How to use skills\n\n"
                + "1. Read the skill before doing work.\n\n"
                + "按照计划继续改造当前工作区";

        String sanitized = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(original);

        assertEquals("按照计划继续改造当前工作区", sanitized);
    }
    /**
     * 验证普通用户自己编写的 Markdown 文档即使以 `## Skills` 开头，也不会被误识别成内部 skills 前导而整段裁剪。
     * 只有命中更强的内部说明特征时才允许剥离，不能仅凭标题文字相同就破坏真实用户内容。
     */
    @Test
    public void sanitizeInjectedRequestTextToUserVisibleTextShouldKeepOrdinarySkillsMarkdownWrittenByUser() {
        String original = "## Skills\n\n"
                + "### Skill roots\n\n"
                + "这里是用户自己整理的技能地图，不是系统注入说明。\n\n"
                + "1. Java\n"
                + "2. Kotlin\n";

        String sanitized = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(original);

        assertEquals(original.trim(), sanitized);
    }
}
