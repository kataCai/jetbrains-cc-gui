package com.github.claudecodegui.util;

/**
 * 用户可见文本净化器。
 * 该类负责把发送给模型的完整请求文本还原为聊天窗口、历史恢复、标题提取和 continued carryover 中允许展示的真实用户文本。
 * 适用场景包括 Codex 历史回放、运行时切模型续接、前端快照恢复以及旧污染历史的动态清理。
 * 边界约束是只能删除高置信内部块，例如权限说明、AGENTS 规则、环境上下文、skills 元数据和 continuation carryover；
 * 对用户自己编写的普通 Markdown 标题必须保守保留，避免误删真实输入。
 * 该类不修改底层 Codex 原始历史文件，只在读取和展示链路上生成派生文本。
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
    private static final String[] MARKDOWN_INSTRUCTION_PREAMBLE_PREFIXES = {
            "# AGENTS.md instructions",
            "# Codex 全局通用规则"
    };
    private static final int MAX_SANITIZE_PASSES = 8;

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
     * 清理聊天窗口可见文本中的内部上下文。
     * 该方法兼容旧调用方的空串语义：底层净化结果为 null 时返回空字符串，避免前台渲染链路出现空指针。
     *
     * @param text 可能包含内部 prompt/context 注入块的原始文本
     * @return 适合前台展示的用户文本；清理后为空时返回空字符串
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

        String sanitized = text.replace("\r\n", "\n").replace("\r", "\n");
        for (int pass = 0; pass < MAX_SANITIZE_PASSES; pass++) {
            String previous = sanitized;
            sanitized = stripSystemTags(sanitized);
            sanitized = stripLeadingMarkdownInstructionPreambles(sanitized);
            sanitized = removeContinuationCarryoverBlocks(sanitized);
            sanitized = stripInternalSkillSections(sanitized);
            sanitized = stripAppendedContext(sanitized);
            if (previous.equals(sanitized)) {
                break;
            }
        }
        String trimmed = sanitized.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 判断一条用户侧文本是否完整命中了内部 continued carryover 结构。
     * 该入口供历史恢复与后续 carryover 构建链路复用，避免不同模块分别维护 summary / recent turns 两套识别规则。
     *
     * @param text 待检查的原始或可见用户文本
     * @return true 表示整条文本以高置信 continued carryover 块开头
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
     * 剥离发送层拼接在用户问题前面的 Markdown 规则前导段。
     * 该方法只处理位于文本开头且命中高置信前缀的段落，不扫描正文中间的同名标题；
     * 这样既能清理 AGENTS 和全局规则说明，也不会误删用户在普通 Markdown 文档里写的同名小节。
     *
     * @param text 已完成标签类系统块清理后的请求文本
     * @return 去除开头规则前导段后的文本；未命中前导段时返回原文本
     */
    private static String stripLeadingMarkdownInstructionPreambles(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;
        boolean changed;
        do {
            changed = false;
            String trimmedLeading = trimLeadingBlankLines(result);
            for (String prefix : MARKDOWN_INSTRUCTION_PREAMBLE_PREFIXES) {
                if (trimmedLeading.startsWith(prefix)) {
                    result = removeFirstParagraph(trimmedLeading);
                    changed = true;
                    break;
                }
            }
        } while (changed);
        return result;
    }

    /**
     * 删除文本开头的空白字符，供内部前导块识别在多轮净化过程中复用。
     * 标签或 skills 块被删除后，经常会在文本开头留下换行；如果不先规整这些换行，
     * 后续前导段匹配会因为首字符不是标题而漏判。
     *
     * @param text 待处理文本
     * @return 去除开头空白后的文本
     */
    private static String trimLeadingBlankLines(String text) {
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current != '\n' && current != ' ' && current != '\t') {
                break;
            }
            index++;
        }
        return text.substring(index);
    }

    /**
     * 删除文本中的第一个段落，用于移除已经确认是内部规则说明的 Markdown 前导。
     * 段落边界以空行识别；若没有空行，说明整条消息只有内部前导，返回空串让上层丢弃。
     *
     * @param text 以内部规则前导开头的文本
     * @return 删除首段后的剩余文本
     */
    private static String removeFirstParagraph(String text) {
        int separatorIndex = text.indexOf("\n\n");
        if (separatorIndex < 0) {
            return "";
        }
        return text.substring(separatorIndex + 2);
    }

    /**
     * 剥离文本任意位置的内部 skills/工具说明段，并保留说明块前后的真实用户文本。
     * 该方法只在 `## Skills` 等高置信起点之后继续看到 `SKILL.md`、`(file:)` 或 skill root 映射时才删除，
     * 以避免用户普通 Markdown 文档中恰好出现同名标题时被误删。
     *
     * @param text 已完成标签清理和 continuation 块剥离后的文本
     * @return 去掉内部 skills 说明段后的文本；未命中强证据时返回原文本
     */
    private static String stripInternalSkillSections(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String[] paragraphs = text.split("\\n\\s*\\n");
        if (paragraphs.length == 0) {
            return text;
        }

        ListSectionRemoval removal = new ListSectionRemoval(paragraphs.length);
        int index = 0;
        while (index < paragraphs.length) {
            String paragraph = paragraphs[index].trim();
            if (paragraph.isEmpty()) {
                index++;
                continue;
            }
            if (!isHighConfidenceInternalSkillHeading(paragraph)) {
                index++;
                continue;
            }

            int sectionEndIndex = index + 1;
            boolean foundStrongInternalSkillEvidence = looksLikeStrongInternalSkillEvidence(paragraph);
            while (sectionEndIndex < paragraphs.length) {
                String sectionParagraph = paragraphs[sectionEndIndex].trim();
                if (sectionParagraph.isEmpty()) {
                    sectionEndIndex++;
                    continue;
                }
                if (!looksLikeSkillInstructionParagraph(sectionParagraph)) {
                    break;
                }
                // 中文注释：只有在命中技能文件路径、skill root 映射等更强证据后，
                // 才允许把整段说明裁剪掉，避免用户普通 Markdown 标题被误删。
                if (looksLikeStrongInternalSkillEvidence(paragraph)) {
                    foundStrongInternalSkillEvidence = true;
                }
                if (looksLikeStrongInternalSkillEvidence(sectionParagraph)) {
                    foundStrongInternalSkillEvidence = true;
                }
                sectionEndIndex++;
            }

            // 中文注释：线上污染日志里存在“整段 skills 说明被压平成单段单行”的形态，
            // 这时起始段本身已经同时包含 heading、skill root 与 SKILL.md 证据，不能再强依赖“至少两个段落”。
            boolean isFlattenedSingleParagraphSection =
                    foundStrongInternalSkillEvidence && looksLikeFlattenedSingleParagraphInternalSkillSection(paragraph);
            if (foundStrongInternalSkillEvidence && (sectionEndIndex > index + 1 || isFlattenedSingleParagraphSection)) {
                removal.markRemoved(index, sectionEndIndex);
                index = sectionEndIndex;
            } else {
                index++;
            }
        }

        if (!removal.hasRemoval()) {
            return text;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < paragraphs.length; i++) {
            if (removal.isRemoved(i)) {
                continue;
            }
            String paragraph = paragraphs[i].trim();
            if (paragraph.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(paragraph);
        }
        return builder.toString();
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
        return isHighConfidenceInternalSkillHeading(paragraph)
                || paragraph.startsWith("- ")
                || paragraph.startsWith("* ")
                || paragraph.matches("^\\d+\\..*")
                || paragraph.contains("SKILL.md")
                || paragraph.contains("### Skill")
                || paragraph.contains("(file:")
                || paragraph.startsWith("Use when")
                || paragraph.startsWith("### Skill")
                || paragraph.startsWith("Discovery:")
                || paragraph.startsWith("Trigger rules:")
                || paragraph.startsWith("Coordination and sequencing:")
                || paragraph.startsWith("Context hygiene:")
                || paragraph.startsWith("Safety and fallback:");
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
     * 判断起始段是否属于被压平成“单段单行”的内部 skills 说明。
     * 该场景要求同一段里同时出现 skills heading、skill roots/available skills/how to use 等内联小节，
     * 用来精准覆盖日志中被发送层压平后的污染文本，避免把普通用户写的 `## Skills` 单段笔记误删。
     *
     * @param paragraph 起始段落文本
     * @return true 表示这是高置信单段压平 skills 说明
     */
    private static boolean looksLikeFlattenedSingleParagraphInternalSkillSection(String paragraph) {
        if (paragraph == null || paragraph.isEmpty()) {
            return false;
        }
        return paragraph.startsWith("## Skills")
                && paragraph.contains("### Skill roots")
                && paragraph.contains("### Available skills")
                && paragraph.contains("### How to use skills");
    }

    /**
     * 剥离 continued conversation 首条消息前拼接的内部 carryover 提示块。
     * 该规则要求命中一整组固定结构特征后才会裁剪，避免把用户手工输入的普通 Markdown 标题误删。
     */
    private static String removeContinuationCarryoverBlocks(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        String result = text;
        int searchIndex = 0;
        boolean changed = false;
        while (searchIndex < result.length()) {
            int start = result.indexOf(CONTINUATION_HEADING, searchIndex);
            if (start < 0) {
                break;
            }
            int blockEnd = findContinuationCarryoverBlockEnd(result, start);
            if (blockEnd < 0) {
                searchIndex = start + CONTINUATION_HEADING.length();
                continue;
            }
            String candidate = result.substring(start, blockEnd).trim();
            if (!looksLikeContinuationCarryoverBlock(candidate)) {
                searchIndex = start + CONTINUATION_HEADING.length();
                continue;
            }
            result = result.substring(0, start) + result.substring(blockEnd);
            changed = true;
            searchIndex = Math.max(0, start);
        }
        return changed ? collapseExcessBlankLines(result) : text;
    }

    /**
     * 定位 continued carryover 块的结束位置。
     * 真实用户输入会跟在固定意图行之后的空行后面，因此这里以固定意图行作为块尾锚点，
     * 再吞掉随后的空白行，避免剥离后留下多余空段。
     *
     * @param text 待扫描的完整文本
     * @param start continued 标题所在位置
     * @return 块结束下标；未找到完整块尾时返回 -1
     */
    private static int findContinuationCarryoverBlockEnd(String text, int start) {
        int intentIndex = text.indexOf(CONTINUATION_INTENT_LINE, start);
        if (intentIndex < 0) {
            return -1;
        }
        int end = intentIndex + CONTINUATION_INTENT_LINE.length();
        while (end < text.length()) {
            char current = text.charAt(end);
            if (current != '\n' && current != ' ' && current != '\t') {
                break;
            }
            end++;
        }
        return end;
    }

    /**
     * 收敛内部块删除后遗留的过多空行。
     * 只把连续三行以上空行压缩为一个段落分隔，避免中间块删除后产生空洞；
     * 普通两段用户文本之间的单个空行仍会保留。
     *
     * @param text 已删除内部块的文本
     * @return 空行规整后的文本
     */
    private static String collapseExcessBlankLines(String text) {
        return text.replaceAll("\\n{3,}", "\n\n").trim();
    }

    /**
     * 记录按段落切分后的内部说明块删除区间。
     * 使用独立小对象避免在主循环里同时维护多个布尔数组和状态变量，使 skills 块扫描逻辑更容易审查。
     */
    private static final class ListSectionRemoval {
        private final boolean[] removed;
        private boolean hasRemoval;

        /**
         * 创建删除标记表。
         *
         * @param size 段落总数
         */
        private ListSectionRemoval(int size) {
            this.removed = new boolean[size];
        }

        /**
         * 标记半开区间内的段落需要删除。
         *
         * @param startInclusive 起始段落下标，包含
         * @param endExclusive 结束段落下标，不包含
         */
        private void markRemoved(int startInclusive, int endExclusive) {
            for (int i = startInclusive; i < endExclusive && i < removed.length; i++) {
                removed[i] = true;
                hasRemoval = true;
            }
        }

        /**
         * 判断指定段落是否已被标记删除。
         *
         * @param index 段落下标
         * @return 已标记删除时返回 true
         */
        private boolean isRemoved(int index) {
            return index >= 0 && index < removed.length && removed[index];
        }

        /**
         * 判断本轮扫描是否实际命中过内部说明块。
         *
         * @return 至少存在一个删除区间时返回 true
         */
        private boolean hasRemoval() {
            return hasRemoval;
        }
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
