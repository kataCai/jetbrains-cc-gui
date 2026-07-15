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
    /**
     * 验证 AGENTS 说明和环境上下文作为发送层前导块出现时会被整体剥离。
     * 该场景覆盖插件把仓库规则、运行环境和用户真实输入拼成同一条 Codex user message 的情况，
     * 断言只保留最后的真实用户问题，避免历史恢复或 continued carryover 再次暴露内部规则。
     */
    @Test
    public void sanitizeInjectedRequestTextToUserVisibleTextShouldStripAgentsInstructionsAndEnvironmentPrelude() {
        String original = "# AGENTS.md instructions for E:/workspace/demo\n\n"
                + "<INSTRUCTIONS>\n"
                + "# Codex 全局通用规则\n\n"
                + "- 默认使用中文回复。\n"
                + "</INSTRUCTIONS>\n\n"
                + "<environment_context>\n"
                + "  <cwd>E:/workspace/demo</cwd>\n"
                + "  <shell>powershell</shell>\n"
                + "</environment_context>\n\n"
                + "继续分析当前问题";

        String sanitized = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(original);

        assertEquals("继续分析当前问题", sanitized);
    }

    /**
     * 验证 continued carryover 块即使出现在真实用户文本中间，也会被作为完整内部块剥离。
     * 该场景覆盖真实历史恢复中“用户输入 + continuation 注入块 + 最新用户输入”被拼成同一条 user message 的污染形态，
     * 断言清洗后仍保留内部块前后的真实用户文本。
     */
    @Test
    public void sanitizeInjectedRequestTextToUserVisibleTextShouldStripContinuationBlockInMiddle() {
        String original = "前面是真实用户补充\n\n"
                + "## Conversation Continuation\n"
                + "You are continuing an existing conversation in a new runtime segment.\n"
                + "Logical conversation id: logical-001\n"
                + "Previous segment session id: segment-001\n"
                + "Recent conversation turns:\n"
                + "User: 1+1=?\n"
                + "Assistant: 2\n"
                + "Preserve the user's intent and continue from that context unless the latest request overrides it.\n\n"
                + "再+1=?";

        String sanitized = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(original);

        assertEquals("前面是真实用户补充\n\n再+1=?", sanitized);
    }

    /**
     * 验证 skills 说明块即使不在整条消息开头，只要命中 SKILL.md、skill roots 等强内部特征，也会被完整剥离。
     * 该测试防止前面残留其他真实文本时，`## Skills` 块因为不是 leading section 而继续泄漏到聊天窗口或 carryover。
     */
    @Test
    public void sanitizeInjectedRequestTextToUserVisibleTextShouldStripInternalSkillsBlockInMiddle() {
        String original = "先保留这句真实用户输入\n\n"
                + "## Skills\n\n"
                + "### Skill roots\n\n"
                + "- `r0` = `D:/Users/example/.agents/skills`\n\n"
                + "### Available skills\n\n"
                + "- `firecrawl-search`: Search the web. (file: r0/firecrawl-search/SKILL.md)\n\n"
                + "### How to use skills\n\n"
                + "1. Read the skill before doing work.\n\n"
                + "继续执行计划";

        String sanitized = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(original);

        assertEquals("先保留这句真实用户输入\n\n继续执行计划", sanitized);
    }
    /**
     * 验证被压平成单段单行的 skills 内部说明也会被完整剥离。
     * 当前线上污染日志就是这种形态：`## Skills`、`(file: ...SKILL.md)` 和 `### How to use skills`
     * 全部挤在同一段里；如果净化逻辑仍要求“至少两个段落”，就会把整段内部说明直接漏到前台。
     */
    @Test
    public void sanitizeInjectedRequestTextToUserVisibleTextShouldStripFlattenedSingleParagraphSkillsPrelude() {
        String original = "## Skills A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. "
                + "Below is the list of skills that can be used. "
                + "### Skill roots - `r0` = `D:/Users/example/.agents/skills` - `r1` = `D:/Users/example/.codex/skills/.system` "
                + "### Available skills - demo: helper (file: r0/demo/SKILL.md) "
                + "### How to use skills - Discovery: read skill first. Trigger rules: use when matched.\n\n"
                + "继续分析当前问题";

        String sanitized = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(original);

        assertEquals("继续分析当前问题", sanitized);
    }
    /**
     * 验证真实用户问题后面如果继续拼接 permissions 与 skills 内部尾巴，
     * 清洗器仍会只保留前面的真实用户问题，避免运行态 mixed message 泄漏到前端。
     */
    @Test
    public void sanitizeInjectedRequestTextToUserVisibleTextShouldKeepVisibleQuestionWhenInternalTailIsAppended() {
        String original = "再+1=?\n\n"
                + "<permissions instructions>\n"
                + "Filesystem sandboxing defines which files can be read or written.\n"
                + "</permissions instructions>\n\n"
                + "## Skills A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. "
                + "### Skill roots - `r0` = `D:/Users/example/.agents/skills` "
                + "### Available skills - demo: helper (file: r0/demo/SKILL.md) "
                + "### How to use skills - read the skill first.";

        String sanitized = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(original);

        assertEquals("再+1=?", sanitized);
    }
}
