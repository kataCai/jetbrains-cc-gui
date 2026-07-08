package com.github.claudecodegui.util;

/**
 * Strips internal prompt/context additions from user-facing transcript text.
 * These sections are useful when sending to providers, but should not be
 * rendered back to users in history replay or tab restore flows.
 */
public final class UserMessageSanitizer {

    private static final String[] SYSTEM_TAG_NAMES = {
            "agents-instructions",
            "system-reminder",
            "system-prompt",
            "permissions instructions",
            "environment_context",
            "INSTRUCTIONS"
    };
    private static final String CONTINUATION_HEADING = "## Conversation Continuation";
    private static final String CONTINUATION_PURPOSE_LINE =
            "You are continuing an existing conversation in a new runtime segment.";
    private static final String CONTINUATION_LOGICAL_ID_PREFIX = "Logical conversation id:";
    private static final String CONTINUATION_PREVIOUS_SESSION_PREFIX = "Previous segment session id:";
    private static final String CONTINUATION_SUMMARY_PREFIX = "Previous conversation summary:";
    private static final String CONTINUATION_RECENT_TURNS_PREFIX = "Recent conversation turns:";
    private static final String CONTINUATION_INTENT_LINE =
            "Preserve the user's intent and continue from that context unless the latest request overrides it.";
    private static final String[] INTERNAL_SKILL_SECTION_HEADINGS = {
            "## Skills",
            "### Skill roots",
            "### Available skills",
            "### How to use skills"
    };

    private static final String[] APPENDED_CONTEXT_MARKERS = {
        "\n\n## Agent Role and Instructions\n\n",
        "\n\n## Workspace Context\n\n",
        "\n\n## Project Modules\n\nThis project contains multiple modules:\n",
        "\n\n## Active Terminal Session\n\nThe user is working in the following terminal context:\n\n",
        "\n\n## Referenced Files\n\nThe following files were referenced by the user:\n\n",
        "\n\n## IDE Context\n\n",
        "\n\n## User's Current IDE Context\n\nThe user is viewing this file in their IDE.",
        "\n\n## User's Current IDE Context\n\nThe user is working in an IDE.",
        "\n\n### Multi-Project Workspace Structure\n\n",
        "\n\n### Project Module Structure\n\nThis project contains multiple modules:\n"
    };

    private UserMessageSanitizer() {
    }

    /**
     * Removes system-only tags and appended prompt context from transcript text.
     */
    public static String sanitizeUserFacingText(String text) {
        String sanitized = sanitizeInjectedRequestTextToUserVisibleText(text);
        return sanitized == null ? "" : sanitized;
    }

    /**
     * 将“发送给模型的完整请求文本”收敛为“前台允许展示给用户的真实文本”。
     * 该入口统一服务于历史恢复、continued carryover 构建以及其他需要把注入式上下文回译为用户可见文本的链路。
     *
     * @param text 可能混入 continuation、权限说明、skills 说明和 IDE/环境上下文的原始请求文本
     * @return 仅保留用户真实输入后的文本；若清洗后为空则返回 null，供上层直接丢弃该消息
     */
    public static String sanitizeInjectedRequestTextToUserVisibleText(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        String strippedTags = stripSystemTags(normalized);
        String strippedContinuationCarryover = removeContinuationCarryoverPrefix(strippedTags);
        String strippedSkillSections = stripLeadingSkillSections(strippedContinuationCarryover);
        String strippedContext = stripAppendedContext(strippedSkillSections);
        String trimmed = strippedContext.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 判断一条用户侧可见文本是否完整命中了内部 continued carryover 结构。
     * 该入口供历史恢复与后续 carryover 构建链路复用，避免不同模块分别维护 summary / recent turns 两套识别规则。
     */
    public static boolean isSyntheticContinuationCarryoverMessage(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith(CONTINUATION_HEADING)) {
            return false;
        }
        int bodySeparatorIndex = normalized.indexOf("\n\n");
        String continuationBlock = bodySeparatorIndex >= 0 ? normalized.substring(0, bodySeparatorIndex) : normalized;
        return looksLikeContinuationCarryoverBlock(continuationBlock);
    }

    private static String stripSystemTags(String text) {
        String result = text;
        for (String tag : SYSTEM_TAG_NAMES) {
            result = removeTagBlocks(result, tag);
        }
        return result;
    }

    private static String removeTagBlocks(String text, String tagName) {
        String result = text;
        String openTag = "<" + tagName + ">";
        String closeTag = "</" + tagName + ">";
        int start = result.indexOf(openTag);
        while (start >= 0) {
            int end = result.indexOf(closeTag, start);
            if (end < 0) {
                break;
            }
            result = result.substring(0, start) + result.substring(end + closeTag.length());
            start = result.indexOf(openTag);
        }
        return result;
    }

    private static String stripAppendedContext(String text) {
        int cutIndex = -1;
        for (String marker : APPENDED_CONTEXT_MARKERS) {
            int idx = text.indexOf(marker);
            if (idx <= 0) {
                continue;
            }
            String prefix = text.substring(0, idx).trim();
            if (prefix.isEmpty()) {
                continue;
            }
            if (cutIndex == -1 || idx < cutIndex) {
                cutIndex = idx;
            }
        }
        if (cutIndex < 0) {
            return text;
        }
        return text.substring(0, cutIndex);
    }

    /**
     * 剥离位于文本前部的 skills/工具说明段，只保留其后真正的用户输入。
     * 这里仅在命中高置信内部 heading 时才启动，避免误删用户正常编写的 Markdown。
     *
     * @param text 已完成标签清理和 continuation 前缀剥离后的文本
     * @return 去掉前导内部 skills 说明段后的文本
     */
    private static String stripLeadingSkillSections(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String[] paragraphs = text.split("\\n\\s*\\n");
        if (paragraphs.length == 0) {
            return text;
        }

        int firstUserParagraphIndex = 0;
        boolean foundInternalSkillsHeading = false;
        boolean foundStrongInternalSkillEvidence = false;
        while (firstUserParagraphIndex < paragraphs.length) {
            String paragraph = paragraphs[firstUserParagraphIndex].trim();
            if (paragraph.isEmpty()) {
                firstUserParagraphIndex++;
                continue;
            }
            if (isHighConfidenceInternalSkillHeading(paragraph)) {
                foundInternalSkillsHeading = true;
                firstUserParagraphIndex++;
                continue;
            }
            if (foundInternalSkillsHeading && looksLikeSkillInstructionParagraph(paragraph)) {
                // 中文注释：只有在命中技能文件路径、skill root 映射等更强证据后，
                // 才允许把整段前导当成内部 skills 说明裁剪掉，避免用户普通 Markdown 标题被误删。
                if (looksLikeStrongInternalSkillEvidence(paragraph)) {
                    foundStrongInternalSkillEvidence = true;
                }
                firstUserParagraphIndex++;
                continue;
            }
            break;
        }

        if (!foundInternalSkillsHeading
                || !foundStrongInternalSkillEvidence
                || firstUserParagraphIndex <= 0
                || firstUserParagraphIndex >= paragraphs.length) {
            return text;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = firstUserParagraphIndex; i < paragraphs.length; i++) {
            String paragraph = paragraphs[i].trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(paragraph);
        }
        return builder.length() == 0 ? text : builder.toString();
    }

    /**
     * 判断某个段落是否是 skills 说明的高置信起始 heading。
     *
     * @param paragraph 按空行切分后的段落文本
     * @return true 表示该段落应视为内部 skills 说明块起点
     */
    private static boolean isHighConfidenceInternalSkillHeading(String paragraph) {
        for (String heading : INTERNAL_SKILL_SECTION_HEADINGS) {
            if (paragraph.startsWith(heading)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断段落是否仍属于内部 skills 说明块。
     * 该判断在已经命中高置信内部 heading 后才会使用，用来连续跳过技能列表、规则列表和说明性小节。
     *
     * @param paragraph 按空行切分后的段落文本
     * @return true 表示该段落仍应视为内部 skills 说明的一部分
     */
    private static boolean looksLikeSkillInstructionParagraph(String paragraph) {
        if (paragraph.isEmpty()) {
            return false;
        }
        return paragraph.startsWith("#")
                || paragraph.startsWith("- ")
                || paragraph.startsWith("* ")
                || paragraph.matches("^\\d+\\..*")
                || paragraph.contains("SKILL.md")
                || paragraph.contains("### Skill")
                || paragraph.contains("(file:")
                || paragraph.startsWith("Use when")
                || paragraph.startsWith("###")
                || paragraph.startsWith("##");
    }

    /**
     * 判断段落中是否出现足以证明“这确实是内部 skills 说明”的强特征。
     * 仅靠 `## Skills` 之类通用标题不足以下结论；必须再看到 skill 文件路径、
     * `(file: ...)` 元信息或 skill root 映射，才允许触发整段剥离。
     *
     * @param paragraph 按空行切分后的段落文本
     * @return true 表示该段落提供了足够强的内部 skills 说明证据
     */
    private static boolean looksLikeStrongInternalSkillEvidence(String paragraph) {
        if (paragraph == null || paragraph.isEmpty()) {
            return false;
        }
        return paragraph.contains("SKILL.md")
                || paragraph.contains("(file:")
                || paragraph.matches("(?s).*[`']r\\d+[`']\\s*=\\s*[`'].+");
    }

    /**
     * 剥离 continued conversation 首条消息前拼接的内部 carryover 提示块。
     * 该规则要求命中一整组固定结构特征后才会裁剪，避免把用户手工输入的普通 Markdown 标题误删。
     */
    private static String removeContinuationCarryoverPrefix(String text) {
        if (!isSyntheticContinuationCarryoverMessage(text)) {
            return text;
        }

        int bodySeparatorIndex = text.indexOf("\n\n");
        if (bodySeparatorIndex < 0) {
            return "";
        }
        return text.substring(bodySeparatorIndex + 2);
    }

    /**
     * 判断文本头部是否完整符合 continued carryover 的固定结构。
     * 只有在标题、用途说明、逻辑会话 id、来源分段、摘要与意图约束全部同时出现时，才允许整段剥离。
     */
    private static boolean looksLikeContinuationCarryoverBlock(String text) {
        return text.contains(CONTINUATION_PURPOSE_LINE)
                && text.contains(CONTINUATION_LOGICAL_ID_PREFIX)
                && text.contains(CONTINUATION_PREVIOUS_SESSION_PREFIX)
                && containsContinuationPayloadMarker(text)
                && text.contains(CONTINUATION_INTENT_LINE);
    }

    /**
     * continued carryover 的正文区域既可能是旧版摘要，也可能是新版 recent turns 快照。
     * 这里统一兼容两种固定标记，避免发送层前缀升级后历史恢复与二次 carryover 过滤规则脱节。
     */
    private static boolean containsContinuationPayloadMarker(String text) {
        return text.contains(CONTINUATION_SUMMARY_PREFIX) || text.contains(CONTINUATION_RECENT_TURNS_PREFIX);
    }
}
