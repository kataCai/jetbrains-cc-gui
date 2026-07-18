/**
 * messageCallbacks.ts
 *
 * Registers window bridge callbacks for message management:
 * updateMessages, updateStatus, showLoading, showThinkingStatus,
 * setHistoryData, clearMessages, addErrorMessage, addHistoryMessage,
 * historyLoadComplete, addUserMessage.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { ClaudeMessage, HistoryRestoreKind, MessageIdentity } from '../../../types';
import type { ContextUsageData } from '../../../components/ContextUsageDialog';
import { sendBridgeEvent } from '../../../utils/bridge';
import { debugError, emitFrontendDiagnosticLog } from '../../../utils/debug';
import { buildTranscriptDiagnosticMessageDump, FULL_TRANSCRIPT_SNAPSHOT_KIND } from '../../../utils/transcriptDiagnostics';
import { isHighConfidenceInternalVisibleResidue } from '../../../utils/contentBlockNormalize';
import {
  appendOptimisticMessageIfMissing,
  ensureStreamingAssistantInList,
  getRawUuid,
  preserveLastAssistantIdentity,
  preserveRecentlyEndedStreamingTurn,
  preserveLatestMessagesOnShrink,
  preserveStreamingAssistantContent,
  stripDuplicateTrailingToolMessages,
} from '../messageSync';
import { releaseSessionTransition } from '../sessionTransition';
import { parseSequence } from '../parseSequence';

const isTruthy = (v: unknown) => v === true || v === 'true';

const CONTINUED_PENDING_TAIL_INTERNAL_PREFIXES = [
  '# AGENTS.md instructions',
  '<agents-instructions>',
  '<INSTRUCTIONS>',
  '<environment_context>',
  '<permissions instructions>',
  'Filesystem sandboxing defines which files can be read or written.',
  '## Conversation Continuation',
  '## Skills',
  'A skill is a set of local instructions to follow that is stored in a `SKILL.md` file.',
];
const USER_VISIBLE_SYSTEM_TAG_NAMES = [
  'agents-instructions',
  'system-reminder',
  'system-prompt',
  'permissions instructions',
  'environment_context',
  'INSTRUCTIONS',
];
const USER_VISIBLE_MARKDOWN_INSTRUCTION_PREAMBLE_PREFIXES = [
  '# AGENTS.md instructions',
  '# Codex 全局通用规则',
];
const USER_VISIBLE_APPENDED_CONTEXT_MARKERS = [
  '\n\n## Agent Role and Instructions\n\n',
  '\n\n## Workspace Context\n\n',
  '\n\n## Project Modules\n\nThis project contains multiple modules:\n',
  '\n\n## Active Terminal Session\n\nThe user is working in the following terminal context:\n\n',
  '\n\n## Referenced Files\n\nThe following files were referenced by the user:\n\n',
  '\n\n## IDE Context\n\n',
  "\n\n## User's Current IDE Context\n\nThe user is viewing this file in their IDE.",
  "\n\n## User's Current IDE Context\n\nThe user is working in an IDE.",
  '\n\n### Multi-Project Workspace Structure\n\n',
  '\n\n### Project Module Structure\n\nThis project contains multiple modules:\n',
];
const USER_VISIBLE_CONTINUATION_HEADING = '## Conversation Continuation';
const USER_VISIBLE_CONTINUATION_PURPOSE_LINE = 'You are continuing an existing conversation in a new runtime segment.';
const USER_VISIBLE_CONTINUATION_LOGICAL_ID_PREFIX = 'Logical conversation id:';
const USER_VISIBLE_CONTINUATION_PREVIOUS_SESSION_PREFIX = 'Previous segment session id:';
const USER_VISIBLE_CONTINUATION_SUMMARY_PREFIX = 'Previous conversation summary:';
const USER_VISIBLE_CONTINUATION_RECENT_TURNS_PREFIX = 'Recent conversation turns:';
const USER_VISIBLE_CONTINUATION_INTENT_LINE =
  "Preserve the user's intent and continue from that context unless the latest request overrides it.";
const USER_VISIBLE_MAX_SANITIZE_PASSES = 8;

function normalizeUserVisibleText(text: string): string {
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
}

function removeTagBlocks(text: string, tagName: string): string {
  let result = text;
  const openTag = `<${tagName}>`;
  const closeTag = `</${tagName}>`;
  let startIndex = result.indexOf(openTag);
  while (startIndex >= 0) {
    const endIndex = result.indexOf(closeTag, startIndex);
    if (endIndex < 0) {
      break;
    }
    result = result.slice(0, startIndex) + result.slice(endIndex + closeTag.length);
    startIndex = result.indexOf(openTag);
  }
  return result;
}

function stripSystemTagsForUserVisibleText(text: string): string {
  return USER_VISIBLE_SYSTEM_TAG_NAMES.reduce(
    (result, tagName) => removeTagBlocks(result, tagName),
    text,
  );
}

function trimLeadingBlankLines(text: string): string {
  return text.replace(/^[\n\t ]+/, '');
}

function removeFirstParagraph(text: string): string {
  const separatorIndex = text.indexOf('\n\n');
  if (separatorIndex < 0) {
    return '';
  }
  return text.slice(separatorIndex + 2);
}

function stripLeadingMarkdownInstructionPreambles(text: string): string {
  let result = text;
  let changed = false;
  do {
    changed = false;
    const trimmedLeading = trimLeadingBlankLines(result);
    for (const prefix of USER_VISIBLE_MARKDOWN_INSTRUCTION_PREAMBLE_PREFIXES) {
      if (trimmedLeading.startsWith(prefix)) {
        result = removeFirstParagraph(trimmedLeading);
        changed = true;
        break;
      }
    }
  } while (changed);
  return result;
}

function looksLikeContinuationCarryoverBlock(text: string): boolean {
  if (!text.startsWith(USER_VISIBLE_CONTINUATION_HEADING)) {
    return false;
  }
  const hasAnchor = text.includes(USER_VISIBLE_CONTINUATION_LOGICAL_ID_PREFIX)
    && text.includes(USER_VISIBLE_CONTINUATION_PREVIOUS_SESSION_PREFIX);
  const hasBody = text.includes(USER_VISIBLE_CONTINUATION_SUMMARY_PREFIX)
    || text.includes(USER_VISIBLE_CONTINUATION_RECENT_TURNS_PREFIX);
  return text.includes(USER_VISIBLE_CONTINUATION_PURPOSE_LINE)
    && hasAnchor
    && hasBody
    && text.includes(USER_VISIBLE_CONTINUATION_INTENT_LINE);
}

function removeContinuationCarryoverBlocks(text: string): string {
  let result = text;
  let searchIndex = 0;
  let changed = false;
  while (searchIndex < result.length) {
    const startIndex = result.indexOf(USER_VISIBLE_CONTINUATION_HEADING, searchIndex);
    if (startIndex < 0) {
      break;
    }
    const blockEndIndex = findContinuationCarryoverBlockEnd(result, startIndex);
    if (blockEndIndex < 0) {
      searchIndex = startIndex + USER_VISIBLE_CONTINUATION_HEADING.length;
      continue;
    }
    const block = result.slice(startIndex, blockEndIndex).trim();
    if (!looksLikeContinuationCarryoverBlock(block)) {
      searchIndex = startIndex + USER_VISIBLE_CONTINUATION_HEADING.length;
      continue;
    }
    result = result.slice(0, startIndex) + result.slice(blockEndIndex);
    changed = true;
    searchIndex = Math.max(0, startIndex);
  }
  return changed ? collapseExcessBlankLines(result) : result;
}

/**
 * 以固定意图行为块尾锚点定位 continuation 提示块，避免被中间正文空行提前截断。
 */
function findContinuationCarryoverBlockEnd(text: string, startIndex: number): number {
  const intentIndex = text.indexOf(USER_VISIBLE_CONTINUATION_INTENT_LINE, startIndex);
  if (intentIndex < 0) {
    return -1;
  }
  let endIndex = intentIndex + USER_VISIBLE_CONTINUATION_INTENT_LINE.length;
  while (endIndex < text.length) {
    const current = text[endIndex];
    if (current !== '\n' && current !== ' ' && current !== '\t') {
      break;
    }
    endIndex += 1;
  }
  return endIndex;
}

/**
 * continuation / skills 等内部块剥离后可能留下过多空行，这里统一收敛为最多一个空段。
 */
function collapseExcessBlankLines(text: string): string {
  return text.replace(/\n{3,}/g, '\n\n').trim();
}

function isHighConfidenceInternalSkillHeading(paragraph: string): boolean {
  return paragraph.startsWith('## Skills')
    || paragraph.startsWith('### Skill roots')
    || paragraph.startsWith('### Available skills')
    || paragraph.startsWith('### How to use skills')
    || paragraph.startsWith('A skill is a set of local instructions to follow that is stored in a `SKILL.md` file.');
}

function looksLikeStrongInternalSkillEvidence(paragraph: string): boolean {
  return paragraph.includes('SKILL.md')
    || paragraph.includes('### Skill roots')
    || paragraph.includes('### Available skills')
    || paragraph.includes('### How to use skills')
    || paragraph.includes('The list above is the skills available in this session')
    || paragraph.includes('After deciding to use a skill')
    || paragraph.includes('(file: r0/')
    || paragraph.includes('(file: r1/')
    || paragraph.includes('(file: r2/');
}

function looksLikeSkillInstructionParagraph(paragraph: string): boolean {
  return isHighConfidenceInternalSkillHeading(paragraph)
    || looksLikeStrongInternalSkillEvidence(paragraph)
    || paragraph.startsWith('- `r')
    || paragraph.startsWith('- Discovery:')
    || paragraph.startsWith('- Trigger rules:')
    || paragraph.startsWith('- Missing/blocked:')
    || paragraph.startsWith('- How to use a skill')
    || paragraph.startsWith('- Coordination and sequencing:')
    || paragraph.startsWith('- Context hygiene:')
    || paragraph.startsWith('- Safety and fallback:')
    || paragraph.startsWith('1. After deciding to use a skill')
    || paragraph.startsWith('2. When `SKILL.md` references relative paths')
    || paragraph.startsWith('3. If `SKILL.md` points to extra folders')
    || paragraph.startsWith('4. If `scripts/` exist')
    || paragraph.startsWith('5. If `assets/` or templates exist')
    || paragraph.includes('skill roots')
    || paragraph.includes('Available skills')
    || paragraph.includes('How to use skills');
}

function looksLikeFlattenedSingleParagraphInternalSkillSection(paragraph: string): boolean {
  return paragraph.startsWith('## Skills')
    && paragraph.includes('SKILL.md')
    && paragraph.includes('### Available skills')
    && paragraph.includes('### How to use skills');
}

function stripInternalSkillSections(text: string): string {
  const paragraphs = text.split(/\n\s*\n/);
  if (paragraphs.length === 0) {
    return text;
  }

  const keptParagraphs: string[] = [];
  let index = 0;
  while (index < paragraphs.length) {
    const paragraph = paragraphs[index]?.trim() || '';
    if (!paragraph) {
      index += 1;
      continue;
    }
    if (!isHighConfidenceInternalSkillHeading(paragraph)) {
      keptParagraphs.push(paragraphs[index]);
      index += 1;
      continue;
    }

    let sectionEndIndex = index + 1;
    let foundStrongInternalSkillEvidence = looksLikeStrongInternalSkillEvidence(paragraph);
    while (sectionEndIndex < paragraphs.length) {
      const sectionParagraph = paragraphs[sectionEndIndex]?.trim() || '';
      if (!sectionParagraph) {
        sectionEndIndex += 1;
        continue;
      }
      if (!looksLikeSkillInstructionParagraph(sectionParagraph)) {
        break;
      }
      if (looksLikeStrongInternalSkillEvidence(sectionParagraph)) {
        foundStrongInternalSkillEvidence = true;
      }
      sectionEndIndex += 1;
    }

    const isFlattenedSingleParagraphSection = foundStrongInternalSkillEvidence
      && looksLikeFlattenedSingleParagraphInternalSkillSection(paragraph);
    if (foundStrongInternalSkillEvidence && (sectionEndIndex > index + 1 || isFlattenedSingleParagraphSection)) {
      index = sectionEndIndex;
      continue;
    }

    keptParagraphs.push(paragraphs[index]);
    index += 1;
  }

  return keptParagraphs.join('\n\n');
}

function stripAppendedContext(text: string): string {
  let cutIndex = -1;
  for (const marker of USER_VISIBLE_APPENDED_CONTEXT_MARKERS) {
    const markerIndex = text.indexOf(marker);
    if (markerIndex <= 0) {
      continue;
    }
    const prefix = text.slice(0, markerIndex).trim();
    if (!prefix) {
      continue;
    }
    if (cutIndex < 0 || markerIndex < cutIndex) {
      cutIndex = markerIndex;
    }
  }
  if (cutIndex < 0) {
    return text;
  }
  return text.slice(0, cutIndex);
}

function sanitizeUserVisibleText(text: string | null | undefined): string | null {
  if (!text) {
    return null;
  }
  let sanitized = normalizeUserVisibleText(text);
  for (let pass = 0; pass < USER_VISIBLE_MAX_SANITIZE_PASSES; pass += 1) {
    const previous = sanitized;
    sanitized = stripSystemTagsForUserVisibleText(sanitized);
    sanitized = stripLeadingMarkdownInstructionPreambles(sanitized);
    sanitized = removeContinuationCarryoverBlocks(sanitized);
    sanitized = stripInternalSkillSections(sanitized);
    sanitized = stripAppendedContext(sanitized);
    if (previous === sanitized) {
      break;
    }
  }
  const trimmed = sanitized.trim();
  return trimmed ? trimmed : null;
}

function toUserTextRawBlocks(text: string): Array<{ type: 'text'; text: string }> {
  return [{ type: 'text', text }];
}

/**
 * 统一提取 user raw 中的内容块，优先复用原始结构，避免不同入口各自解析 `raw.content`/`raw.message.content`。
 */
function extractUserRawContentBlocks(raw: ClaudeMessage['raw']): Array<Record<string, unknown>> {
  if (!raw || typeof raw === 'string') {
    return [];
  }
  const rawObject = raw as Record<string, unknown>;
  const directContent = rawObject.content;
  if (Array.isArray(directContent)) {
    return directContent.filter((block): block is Record<string, unknown> => !!block && typeof block === 'object');
  }
  const rawMessage = rawObject.message;
  if (rawMessage && typeof rawMessage === 'object' && !Array.isArray(rawMessage)) {
    const nestedContent = (rawMessage as Record<string, unknown>).content;
    if (Array.isArray(nestedContent)) {
      return nestedContent.filter((block): block is Record<string, unknown> => !!block && typeof block === 'object');
    }
  }
  return [];
}

/**
 * 判断净化后的 user raw 是否仍包含前端可见的非文本 block，避免纯图片/附件消息被误删。
 */
function containsVisibleNonTextUserBlocks(raw: ClaudeMessage['raw']): boolean {
  return extractUserRawContentBlocks(raw).some((block) => {
    const type = typeof block.type === 'string' ? block.type : '';
    return type !== '' && type !== 'text' && type !== 'thinking';
  });
}

/**
 * 只重写 user raw 中可见文本 block，保留图片、附件等非文本 block，避免净化文本时退化消息结构。
 */
function buildSanitizedUserRaw(raw: ClaudeMessage['raw'], text: string | null): ClaudeMessage['raw'] {
  if (raw == null) {
    return raw;
  }
  if (typeof raw === 'string') {
    return text ?? '';
  }
  const sanitizedBlocks: Array<Record<string, unknown>> = [];
  const originalBlocks = extractUserRawContentBlocks(raw);
  let insertedSanitizedText = false;
  for (const block of originalBlocks) {
    const type = typeof block.type === 'string' ? block.type : '';
    if (type === 'text') {
      if (!insertedSanitizedText && text) {
        sanitizedBlocks.push({ ...block, text });
        insertedSanitizedText = true;
      }
      continue;
    }
    sanitizedBlocks.push({ ...block });
  }
  if (!insertedSanitizedText && text) {
    sanitizedBlocks.push(...toUserTextRawBlocks(text));
  }

  const rawObject = { ...(raw as Record<string, unknown>) };
  rawObject.content = sanitizedBlocks;
  const rawMessage = rawObject.message;
  if (rawMessage && typeof rawMessage === 'object' && !Array.isArray(rawMessage)) {
    rawObject.message = {
      ...(rawMessage as Record<string, unknown>),
      content: sanitizedBlocks.map((block) => ({ ...block })),
    };
  }
  return rawObject;
}

function summarizeDiagnosticMessageContent(content: string | null | undefined): string | null {
  if (typeof content !== 'string') {
    return null;
  }
  const compact = content.replace(/\s+/g, ' ').trim();
  if (!compact) {
    return null;
  }
  return compact.slice(0, 96);
}

type SanitizedFrontendVisibleMessageResult = {
  message: ClaudeMessage | null;
  changed: boolean;
};

/**
 * 从前端消息对象中提取所有可能参与可见性判断的文本候选。
 * 这里会同时读取顶层 `content`、`raw.content` 与 `raw.message.content`，避免只清洗顶层文本时遗漏 raw 回退分支。
 */
function extractFrontendMessageTexts(message: ClaudeMessage): string[] {
  const texts: string[] = [];
  const appendText = (value: unknown) => {
    if (typeof value === 'string' && value.trim()) {
      texts.push(value);
    }
  };
  const appendTextBlocks = (value: unknown) => {
    if (!Array.isArray(value)) {
      return;
    }
    value.forEach((block) => {
      if (block && typeof block === 'object' && (block as { type?: string }).type === 'text') {
        appendText((block as { text?: unknown }).text);
      }
    });
  };

  appendText(message.content);
  const raw = message.raw;
  if (typeof raw === 'string') {
    appendText(raw);
    return texts;
  }
  if (!raw || typeof raw !== 'object') {
    return texts;
  }

  const rawObject = raw as Record<string, unknown>;
  appendText(rawObject.content);
  appendTextBlocks(rawObject.content);
  if (rawObject.message && typeof rawObject.message === 'object' && !Array.isArray(rawObject.message)) {
    const rawMessage = rawObject.message as Record<string, unknown>;
    appendText(rawMessage.content);
    appendTextBlocks(rawMessage.content);
  }
  return texts;
}

/**
 * 判断当前前端消息是否属于“非 user 但命中高置信内部残留”的污染消息。
 * 该判定专门服务 assistant/system/notification 兜底过滤，避免 permissions/skills/continuation 被直接渲染。
 */
function isHighConfidenceInternalResidueVisibleMessage(message: ClaudeMessage): boolean {
  if (message.type === 'user') {
    return false;
  }
  return extractFrontendMessageTexts(message).some((text) => isHighConfidenceInternalVisibleResidue(text));
}

/**
 * 统一净化前端可见消息。
 * `user` 分支保留“净化后继续展示真实问题文本”的策略；非 user 分支一旦命中高置信内部残留则整条丢弃。
 */
function sanitizeFrontendVisibleMessage(message: ClaudeMessage): SanitizedFrontendVisibleMessageResult {
  if (message.type !== 'user') {
    if (isHighConfidenceInternalResidueVisibleMessage(message)) {
      return { message: null, changed: true };
    }
    return { message, changed: false };
  }
  const originalContent = typeof message.content === 'string' ? message.content : '';
  const sanitizedContent = sanitizeUserVisibleText(originalContent);
  const hasVisibleNonTextBlocks = containsVisibleNonTextUserBlocks(message.raw);
  if (!sanitizedContent) {
    if (!hasVisibleNonTextBlocks) {
      return { message: null, changed: originalContent.trim().length > 0 || !!message.raw };
    }
    return {
      message: {
        ...message,
        content: '',
        raw: buildSanitizedUserRaw(message.raw, null),
      },
      changed: originalContent.trim().length > 0 || !!message.raw,
    };
  }
  const sanitizedRaw = buildSanitizedUserRaw(message.raw, sanitizedContent);
  if (sanitizedContent === originalContent && sanitizedRaw === message.raw) {
    return { message, changed: false };
  }
  return {
    message: {
      ...message,
      content: sanitizedContent,
      raw: sanitizedRaw,
    },
    changed: true,
  };
}

type SanitizedFrontendMessageListResult = {
  messages: ClaudeMessage[];
  sanitizedToEmptyCount: number;
  changedCount: number;
};

/**
 * 对一批待显示消息执行统一净化，并返回变更统计。
 * 该入口同时被 continued pending tail、authoritative snapshot 和历史追加链路复用，避免不同入口的过滤规则漂移。
 */
function sanitizeFrontendVisibleMessages(messages: ClaudeMessage[]): SanitizedFrontendMessageListResult {
  let sanitizedToEmptyCount = 0;
  let changedCount = 0;
  const sanitizedMessages: ClaudeMessage[] = [];
  for (const message of messages) {
    const sanitizedResult = sanitizeFrontendVisibleMessage(message);
    if (!sanitizedResult.message) {
      sanitizedToEmptyCount += 1;
      changedCount += 1;
      continue;
    }
    if (sanitizedResult.changed) {
      changedCount += 1;
    }
    sanitizedMessages.push(sanitizedResult.message);
  }
  return {
    messages: sanitizedMessages,
    sanitizedToEmptyCount,
    changedCount,
  };
}

/**
 * 为消息中的非文本 raw block 构建轻量签名。
 * 这样在 streaming 阶段可以低成本判断结构是否真的发生了变化，
 * 避免对大型 raw 结构做高频 `JSON.stringify`。
 *
 * @param message 待分析消息
 * @param extractRawBlocks raw block 提取函数
 * @return 非文本 block 的结构签名
 */
function getStructuralRawBlockSignature(
  message: ClaudeMessage,
  extractRawBlocks: (raw: ClaudeMessage['raw']) => Record<string, unknown>[],
): string {
  const blocks = extractRawBlocks(message.raw);
  if (!Array.isArray(blocks) || blocks.length === 0) {
    return '';
  }

  const parts: string[] = [];
  for (const raw of blocks) {
    if (!raw || typeof raw !== 'object') continue;
    const block = raw as Record<string, unknown>;
    const type = typeof block.type === 'string' ? block.type : '';
    if (type === 'text' || type === 'thinking') continue;

    if (type === 'tool_use') {
      parts.push(`tu:${block.id ?? ''}:${block.name ?? ''}`);
    } else if (type === 'tool_result') {
      parts.push(`tr:${block.tool_use_id ?? ''}:${block.is_error === true ? '1' : '0'}`);
    } else if (type === 'attachment') {
      parts.push(`at:${block.fileName ?? ''}:${block.mediaType ?? ''}`);
    } else if (type === 'image') {
      parts.push(`im:${block.src ?? ''}:${block.mediaType ?? ''}`);
    } else {
      parts.push(type);
    }
  }

  return parts.join('|');
}

/**
 * 判断 continued 过渡态里某条消息是否仍明显带着内部 prompt/skills 残留。
 * 这里只匹配高置信固定前缀与固定组合特征，专门服务“pending tail 缓存”兜底，
 * 避免普通历史恢复或真实用户 Markdown 被误删。
 *
 * @param message 待检查的前端消息
 * @return true 表示该消息不应进入 continued pending tail 缓存
 */
function isHighConfidenceInternalContinuedTailMessage(message: ClaudeMessage): boolean {
  const normalizedContent = typeof message?.content === 'string' ? message.content.trim() : '';
  if (!normalizedContent) {
    return false;
  }
  if (CONTINUED_PENDING_TAIL_INTERNAL_PREFIXES.some((prefix) => normalizedContent.startsWith(prefix))) {
    return true;
  }
  if (
    normalizedContent.includes('SKILL.md')
    && normalizedContent.includes('### Available skills')
    && normalizedContent.includes('### How to use skills')
  ) {
    return true;
  }
  return normalizedContent.includes('Logical conversation id:')
    && normalizedContent.includes('Previous segment session id:')
    && normalizedContent.includes("Preserve the user's intent and continue from that context unless the latest request overrides it.");
}

/**
 * 仅在 continued 首帧 sessionId 尚未绑定时，对待缓存 tail 做高置信内部消息过滤。
 * 该过滤不会影响当前帧展示，只用于避免旧的污染消息被缓存并在后续 prefix merge 中再次拼回界面。
 *
 * @param messages 候选 continued pending tail
 * @return 去除明显内部消息后的 tail
 */
function filterContinuedPendingTailMessages(messages: ClaudeMessage[]): ClaudeMessage[] {
  return messages.filter((message) => !isHighConfidenceInternalContinuedTailMessage(message));
}

/**
 * 统计消息列表中仍残留的相邻同内容 user 消息数量。
 * 该诊断只用于判断 authoritative snapshot 是否还带着明显重复，不参与任何渲染分支。
 *
 * @param messages 待检查的消息列表
 * @return 相邻重复 user 对的数量
 */
function countAdjacentDuplicateUserMessages(messages: ClaudeMessage[]): number {
  if (!Array.isArray(messages) || messages.length < 2) {
    return 0;
  }
  let duplicateCount = 0;
  for (let index = 1; index < messages.length; index += 1) {
    const previous = messages[index - 1];
    const current = messages[index];
    if (previous?.type !== 'user' || current?.type !== 'user') {
      continue;
    }
    const previousContent = typeof previous.content === 'string' ? previous.content.trim() : '';
    const currentContent = typeof current.content === 'string' ? current.content.trim() : '';
    if (previousContent && previousContent === currentContent) {
      duplicateCount += 1;
    }
  }
  return duplicateCount;
}

export function registerMessageCallbacks(
  options: UseWindowCallbacksOptions,
  resetTransientUiState: (runOptions?: {
    preserveContinuedPrefix?: boolean;
    preservePreparedHistoryRestore?: boolean;
  }) => void,
): void {
  const {
    addToast,
    setMessages,
    setStatus,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setHistoryData,
    userPausedRef,
    isUserAtBottomRef,
    messagesContainerRef,
    suppressNextStatusToastRef,
    streamingContentRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    findLastAssistantIndex,
    extractRawBlocks,
    patchAssistantForStreaming,
    updateContextUsageData,
    closeContextUsageDialog,
    currentSessionIdRef,
    continuationPendingRef,
  } = options;

  const ensureStreamingAssistantPreserved = (prevList: ClaudeMessage[], resultList: ClaudeMessage[]): ClaudeMessage[] => {
    const { list, streamingIndex } = ensureStreamingAssistantInList(
      prevList,
      resultList,
      isStreamingRef.current,
      streamingTurnIdRef.current,
    );
    if (streamingIndex >= 0) {
      streamingMessageIndexRef.current = streamingIndex;
    }
    return list;
  };

  /**
   * 判断当前快照是否仍然只是 continued segment 的“新分段尾部”，尚未自带完整逻辑会话前缀。
   * 这类快照如果继续套用“最后一个 assistant 身份复用”，就会把旧分段 assistant 的时间戳
   * 错误迁移到新分段 assistant 上，导致后续快照看起来像是旧消息被篡改。
   *
   * @param nextList 后端当前回传的消息快照
   * @return true 表示应跳过 assistant 身份复用
   */
  const shouldSkipAssistantIdentityPreservationForContinuedSegment = (nextList: ClaudeMessage[]): boolean => {
    const currentSessionId = currentSessionIdRef.current?.trim() || null;
    const preservedPrefix = window.__continuedSegmentHistoryPrefixMessages;
    const preservedPrefixSessionId = window.__continuedSegmentHistoryPrefixSessionId?.trim() || null;
    if (!currentSessionId || !Array.isArray(preservedPrefix) || preservedPrefix.length === 0) {
      return false;
    }
    if (preservedPrefixSessionId !== currentSessionId) {
      return false;
    }
    const nextAlreadyContainsPrefix = nextList.length >= preservedPrefix.length
      && preservedPrefix.every((message, index) => isSameMessageIdentity(message, nextList[index]));
    return !nextAlreadyContainsPrefix;
  };

  /**
   * 对普通快照沿用既有 assistant 身份稳定逻辑；但 continued segment 的局部尾段快照必须跳过，
   * 否则会把旧分段 assistant 的标识误复用到新分段 assistant 上。
   *
   * @param prevList 当前界面上一轮消息
   * @param nextList 本轮待应用消息
   * @return 按场景处理后的消息列表
   */
  const preserveLastAssistantIdentityIfSafe = (
    prevList: ClaudeMessage[],
    nextList: ClaudeMessage[],
    restoreKind?: HistoryRestoreKind | null,
  ): ClaudeMessage[] => {
    if (isAuthoritativeRestoreKind(restoreKind)) {
      return nextList;
    }
    if (shouldSkipAssistantIdentityPreservationForContinuedSegment(nextList)) {
      return nextList;
    }
    return preserveLastAssistantIdentity(prevList, nextList, findLastAssistantIndex);
  };

  const finalizeMessageList = (prevList: ClaudeMessage[], resultList: ClaudeMessage[]): ClaudeMessage[] => {
    const recentlyEndedPreserved = preserveRecentlyEndedStreamingTurn(
      prevList,
      resultList,
      findLastAssistantIndex,
    );
    const withoutDuplicateToolTail = stripDuplicateTrailingToolMessages(
      recentlyEndedPreserved,
      options.currentProviderRef.current,
    );
    return ensureStreamingAssistantPreserved(prevList, withoutDuplicateToolTail);
  };

  /**
   * 仅根据“类型 + 时间戳 + 文本内容”判断两条前端消息是否指向同一条逻辑消息。
   * 这里故意不比较完整 raw 结构，避免后端在补齐 thinking/tool block 时把“前缀已包含”误判成不同消息。
   *
   * @param left 左侧消息
   * @param right 右侧消息
   * @return 两条消息可视为同一条逻辑消息时返回 true
   */
  const isSameMessageIdentity = (left: ClaudeMessage, right: ClaudeMessage | undefined): boolean => (
    !!right
    && (
      areStableMessageIdentitiesEqual(left.messageIdentity, right.messageIdentity)
      || (
        left.type === right.type
        && left.timestamp === right.timestamp
        && (left.content || '') === (right.content || '')
      )
    )
  );

  /**
   * 优先使用后端显式下发的 `messageIdentity.key` 判断两条消息是否属于同一条逻辑消息。
   * 只有 identity 缺失时才回退到旧的 `type + timestamp + content` 比较，避免 continued 和 authoritative restore
   * 仍然被迫依赖时间戳完全相等这一脆弱条件。
   *
   * @param left 左侧消息 identity
   * @param right 右侧消息 identity
   * @return 两侧 identity key 一致时返回 true
   */
  const areStableMessageIdentitiesEqual = (
    left: MessageIdentity | undefined,
    right: MessageIdentity | undefined,
  ): boolean => !!left?.key && !!right?.key && left.key === right.key;

  /**
   * 判断当前历史恢复快照是否属于“后端已完成完整 logical conversation 聚合”的权威接管场景。
   * 一旦命中该场景，前端必须跳过 continued prefix merge、assistant identity 复用和 shrink preserve，
   * 直接以这份快照替换当前消息列表。
   *
   * @param restoreKind 本轮历史恢复快照类别
   * @return true 表示该快照应作为 authoritative snapshot 直接接管界面
   */
  const isAuthoritativeRestoreKind = (restoreKind: HistoryRestoreKind | null | undefined): boolean => (
    restoreKind === 'runtime_continue_authoritative'
    || restoreKind === 'logical_conversation'
  );

  /**
   * 统一清理 continued segment 过渡缓存。
   * 当后端快照已经回到完整逻辑会话形态时，旧前缀缓存和等待首个 sessionId 的元数据都必须一起释放；
   * 否则后续普通发送或普通 setSessionId 可能误读到 stale cache，继续走 continued 兜底分支。
   *
   * @return 无返回值
   */
  /**
   * 清理 continued 过渡缓存，并记录清理原因。
   * 这些缓存一旦跨越真实 session 绑定或 authoritative restore 继续残留，就会把旧前缀误拼回界面；
   * 因此这里统一收口清理日志，便于后续排查“是哪一步提前或滞后消费了 fallback cache”。
   *
   * @param cleanupReason 本次清理的明确原因
   * @return 无返回值
   */
  const clearContinuedSegmentTransitionCache = (cleanupReason: string): void => {
    const prefixCacheCount = Array.isArray(window.__continuedSegmentHistoryPrefixMessages)
      ? window.__continuedSegmentHistoryPrefixMessages.length
      : null;
    const pendingTailCount = Array.isArray(window.__continuedSegmentPendingTailMessages)
      ? window.__continuedSegmentPendingTailMessages.length
      : null;
    const hasAnyCache = prefixCacheCount !== null
      || pendingTailCount !== null
      || !!window.__continuedSegmentFirstSnapshotSessionId
      || !!window.__continuedSegmentPendingSourceSessionId
      || !!window.__continuedSegmentPendingLogicalConversationId
      || window.__continuedSegmentAwaitingFirstSessionId === true;
    if (hasAnyCache) {
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued transition cache cleared', {
        cleanupReason,
        prefixCacheCount,
        prefixSessionId: window.__continuedSegmentHistoryPrefixSessionId ?? null,
        pendingTailCount,
        firstSnapshotSessionId: window.__continuedSegmentFirstSnapshotSessionId ?? null,
        pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
        pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
        awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
      });
    }
    window.__continuedSegmentHistoryPrefixMessages = null;
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentFirstSnapshotSessionId = null;
    window.__continuedSegmentPendingTailMessages = null;
    window.__continuedSegmentPendingSourceSessionId = null;
    window.__continuedSegmentPendingLogicalConversationId = null;
    window.__continuedSegmentPendingCreatedAt = null;
    window.__continuedSegmentPendingReason = null;
    window.__continuedSegmentAwaitingFirstSessionId = false;
  };

  /**
   * 浅拷贝一份 continued 尾部快照，避免后续合并阶段直接复用同一数组引用，
   * 导致测试或诊断日志里很难判断“缓存尾部”与“当前实时快照”是否已经分叉。
   *
   * @param messages 待复制的消息列表
   * @return 复制后的消息列表；原值无效时返回 null
   */
  const cloneContinuedTailMessages = (messages: ClaudeMessage[] | null | undefined): ClaudeMessage[] | null => (
    Array.isArray(messages) ? messages.map((message) => ({ ...message })) : null
  );

  /**
   * 在“sessionId 绑定前先到的尾部快照”和“当前实时尾部快照”之间选出更完整的一份。
   * 优先保留能完全覆盖另一份前缀、且长度更长的尾部；若两者互不覆盖，则保守采用当前实时快照，
   * 避免过期缓存把同一 runtime segment 的更新倒退回去。
   *
   * @param currentTail 当前实时收到的尾部快照
   * @param pendingTail sessionId 未绑定前缓存下来的早到尾部快照
   * @return 更适合作为当前 continued 尾部的快照
   */
  const selectMoreCompleteContinuedTail = (
    currentTail: ClaudeMessage[],
    pendingTail: ClaudeMessage[] | null | undefined,
  ): ClaudeMessage[] => {
    if (!Array.isArray(pendingTail) || pendingTail.length === 0) {
      return currentTail;
    }
    if (!Array.isArray(currentTail) || currentTail.length === 0) {
      return pendingTail;
    }

    const currentExtendsPending = pendingTail.every((message, index) => isSameMessageIdentity(message, currentTail[index]));
    if (currentExtendsPending && currentTail.length >= pendingTail.length) {
      return currentTail;
    }

    const pendingExtendsCurrent = currentTail.every((message, index) => isSameMessageIdentity(message, pendingTail[index]));
    if (pendingExtendsCurrent && pendingTail.length > currentTail.length) {
      return pendingTail;
    }

    return currentTail;
  };

  /**
   * 把 continued segment 之前的逻辑会话前缀重新拼回当前 runtime snapshot。
   * 运行时切模型后的后端快照通常只包含“新物理 session”自己的消息；
   * 如果前端不显式保留旧前缀，当前窗口就会在每次 updateMessages 时只剩下新分段局部消息。
   *
   * @param nextList 当前物理分段回传的最新消息快照
   * @return 需要展示到窗口中的逻辑会话消息列表
   */
  /**
   * 计算 continued 前缀尾部与当前 tail 头部之间最大的重叠长度。
   * 这里专门处理“前缀最后一条 user 已经和新 tail 第一条 user 相同”的场景，
   * 避免绑定 sessionId 之后把同一条追问重复拼接两遍。
   *
   * @param preservedPrefix 续接前保留下来的逻辑会话前缀
   * @param mergedTail 当前物理分段对应的最新 tail 快照
   * @return 需要从 tail 头部跳过的重叠消息数量
   */
  const findContinuedPrefixTailOverlap = (
    preservedPrefix: ClaudeMessage[],
    mergedTail: ClaudeMessage[],
  ): number => {
    const maxOverlap = Math.min(preservedPrefix.length, mergedTail.length);
    for (let overlap = maxOverlap; overlap > 0; overlap -= 1) {
      const prefixStartIndex = preservedPrefix.length - overlap;
      const overlapMatched = mergedTail
        .slice(0, overlap)
        .every((message, index) => isSameMessageIdentity(message, preservedPrefix[prefixStartIndex + index]));
      if (overlapMatched) {
        return overlap;
      }
    }
    return 0;
  };

  const mergeContinuedSegmentPrefixIfNeeded = (nextList: ClaudeMessage[]): ClaudeMessage[] => {
    const currentSessionId = currentSessionIdRef.current?.trim() || null;
    const preservedPrefix = window.__continuedSegmentHistoryPrefixMessages;
    const preservedPrefixSessionId = window.__continuedSegmentHistoryPrefixSessionId?.trim() || null;
    if (!currentSessionId || !Array.isArray(preservedPrefix) || preservedPrefixSessionId !== currentSessionId) {
      return nextList;
    }
    if (preservedPrefix.length === 0) {
      return nextList;
    }
    const mergedTail = selectMoreCompleteContinuedTail(nextList, window.__continuedSegmentPendingTailMessages);
    // 中文注释：一旦真实 sessionId 已经绑定，说明“早到尾部缓存”已经有了稳定锚点，
    // 后续继续保留只会让旧缓存误参与下一轮比较，因此这里在正式合并前先消费掉。
    window.__continuedSegmentPendingTailMessages = null;

    const nextAlreadyContainsPrefix = mergedTail.length >= preservedPrefix.length
      && preservedPrefix.every((message, index) => isSameMessageIdentity(message, mergedTail[index]));
    if (nextAlreadyContainsPrefix) {
      // 中文注释：若后续某条快照已经升级成“整条逻辑会话完整快照”，说明前缀缓存已不再需要，
      // 否则继续强拼接会把旧历史重复叠加两遍。
      window.__continuedSegmentHistoryPrefixMessages = null;
      clearContinuedSegmentTransitionCache('prefix_cache_consumed_by_complete_snapshot');
      return mergedTail;
    }

    const overlapCount = findContinuedPrefixTailOverlap(preservedPrefix, mergedTail);
    return [...preservedPrefix, ...mergedTail.slice(overlapCount)];
  };

  /**
   * continued segment 的 runtime snapshot 需要优先走“旧前缀 + 新分段快照”合并，
   * 普通 shrink 保护只适合处理压缩/摘要导致的临时变短，不适合处理跨 runtime 分段后的局部快照。
   *
   * @param prevList 当前界面上次已展示的消息
   * @param nextList 后端本次回传的消息
   * @return 需要真正落到界面的消息列表
   */
  const preserveShrinkIfNeeded = (
    prevList: ClaudeMessage[],
    nextList: ClaudeMessage[],
    restoreKind?: HistoryRestoreKind | null,
  ): ClaudeMessage[] => {
    if (isAuthoritativeRestoreKind(restoreKind)) {
      clearContinuedSegmentTransitionCache('authoritative_restore');
      return nextList;
    }
    const currentSessionId = currentSessionIdRef.current?.trim() || null;
    const hasPrefixCache = Array.isArray(window.__continuedSegmentHistoryPrefixMessages);
    const shouldMergeContinuedSegment = !!currentSessionId
      && window.__continuedSegmentHistoryPrefixSessionId?.trim() === currentSessionId
      && hasPrefixCache;
    if (shouldMergeContinuedSegment) {
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued prefix cache hit', {
        currentSessionId,
        prefixSessionId: window.__continuedSegmentHistoryPrefixSessionId ?? null,
        prefixCacheCount: window.__continuedSegmentHistoryPrefixMessages?.length ?? null,
        pendingTailCount: window.__continuedSegmentPendingTailMessages?.length ?? null,
        firstSnapshotSessionId: window.__continuedSegmentFirstSnapshotSessionId ?? null,
        awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
      });
      const mergedList = mergeContinuedSegmentPrefixIfNeeded(nextList);
      if (window.__continuedSegmentFirstSnapshotSessionId === currentSessionId) {
        emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued segment first snapshot applied', {
          snapshotStage: 'transitional',
          prefixCacheHit: true,
          currentSessionId,
          continuationPending: continuationPendingRef.current,
          previousMessageCount: prevList.length,
          nextMessageCount: nextList.length,
          mergedMessageCount: mergedList.length,
          firstSnapshotSessionId: window.__continuedSegmentFirstSnapshotSessionId ?? null,
          pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
          pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
          awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
        });
        // 中文注释：这里只清理“首帧已消费”标记。
        // continued 生命周期是否完成，已经在 setSessionId 时显式收口，不能再依赖首帧快照时序。
        window.__continuedSegmentFirstSnapshotSessionId = null;
      }
      return mergedList;
    }
    if (hasPrefixCache) {
      const shouldCachePendingTail = !currentSessionId
        && !window.__continuedSegmentHistoryPrefixSessionId?.trim()
        && window.__continuedSegmentAwaitingFirstSessionId === true
        && nextList.length > 0;
      if (shouldCachePendingTail) {
        const selectedPendingTail = selectMoreCompleteContinuedTail(
          nextList,
          window.__continuedSegmentPendingTailMessages,
        );
        const highConfidenceFilteredPendingTail = filterContinuedPendingTailMessages(selectedPendingTail);
        const filteredInternalCount = selectedPendingTail.length - highConfidenceFilteredPendingTail.length;
        const sanitizedPendingTailResult = sanitizeFrontendVisibleMessages(highConfidenceFilteredPendingTail);
        window.__continuedSegmentPendingTailMessages = sanitizedPendingTailResult.messages.length > 0
          ? cloneContinuedTailMessages(sanitizedPendingTailResult.messages)
          : null;
        emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued pending tail cached', {
          snapshotStage: 'transitional',
          pendingTailCount: window.__continuedSegmentPendingTailMessages?.length ?? null,
          nextMessageCount: nextList.length,
          filteredInternalCount,
          sanitizedTailCount: sanitizedPendingTailResult.changedCount,
          sanitizedToEmptyCount: sanitizedPendingTailResult.sanitizedToEmptyCount,
          firstMessageType: selectedPendingTail[0]?.type ?? null,
          firstMessagePreview: summarizeDiagnosticMessageContent(selectedPendingTail[0]?.content),
          firstSanitizedPreview: summarizeDiagnosticMessageContent(
            window.__continuedSegmentPendingTailMessages?.[0]?.content,
          ),
          pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
          pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
          awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
        });
      }
      const skipReason = shouldCachePendingTail
        ? 'awaiting_first_session_id'
        : !currentSessionId
          ? 'missing_current_session_id'
          : 'prefix_session_id_mismatch';
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued prefix merge skipped', {
        snapshotStage: 'transitional',
        skipReason,
        currentSessionId,
        prefixSessionId: window.__continuedSegmentHistoryPrefixSessionId ?? null,
        prefixCacheCount: window.__continuedSegmentHistoryPrefixMessages?.length ?? null,
        pendingTailCount: window.__continuedSegmentPendingTailMessages?.length ?? null,
        firstSnapshotSessionId: window.__continuedSegmentFirstSnapshotSessionId ?? null,
        pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
        pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
        awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
        continuationPending: continuationPendingRef.current,
        previousMessageCount: prevList.length,
        nextMessageCount: nextList.length,
      });
    }
    return preserveLatestMessagesOnShrink(prevList, nextList, options.currentProviderRef.current);
  };

  if (window.__pendingUpdateRaf != null) {
    cancelAnimationFrame(window.__pendingUpdateRaf);
    window.__pendingUpdateRaf = null;
    window.__pendingUpdateJson = null;
    window.__pendingUpdateSequence = null;
  }
  let pendingUpdateJson: string | null = null;
  let pendingUpdateRaf: number | null = null;
  let pendingUpdateSequence: number | null = null;

  /**
   * 清理当前待消费的历史恢复快照上下文。
   * 该上下文只对下一次历史 `updateMessages` 生效，消费完成或恢复链路结束后都应立即清空，
   * 避免后续普通消息刷新误命中历史快照幂等判断。
   */
  const clearPreparedHistoryRestoreSnapshot = () => {
    window.__preparedHistoryRestoreKey = null;
    window.__preparedHistoryRestoreSignature = null;
    window.__preparedHistoryRestoreKind = null;
  };

  /**
   * 判断当前是否已经收到、但尚未被下一次 updateMessages 消费的 restore 上下文。
   * 这里专门给 `clearMessages` 使用，避免 authoritative restore 固定链路中把刚 prepare 的元数据提前清掉。
   *
   * @return true 表示下一次 updateMessages 仍应消费 restore 元数据
   */
  const hasPreparedHistoryRestoreSnapshot = (): boolean => (
    typeof window.__preparedHistoryRestoreKey === 'string'
    && window.__preparedHistoryRestoreKey.length > 0
    && typeof window.__preparedHistoryRestoreSignature === 'string'
    && window.__preparedHistoryRestoreSignature.length > 0
  );

  /**
   * 读取并消费后端刚刚准备好的历史恢复快照上下文。
   *
   * @return 若当前 `updateMessages` 属于历史恢复快照，则返回 restore key 与 snapshot signature
   */
  const consumePreparedHistoryRestoreSnapshot = (): {
    restoreKey: string;
    snapshotSignature: string;
    restoreKind: HistoryRestoreKind;
  } | null => {
    const restoreKey = window.__preparedHistoryRestoreKey;
    const snapshotSignature = window.__preparedHistoryRestoreSignature;
    const restoreKind = window.__preparedHistoryRestoreKind || 'single_session';
    clearPreparedHistoryRestoreSnapshot();
    if (!restoreKey || !snapshotSignature) {
      return null;
    }
    return { restoreKey, snapshotSignature, restoreKind };
  };

  /**
   * 判断当前 updateMessages 是否应该消费待恢复快照上下文。
   * 普通 streaming 快照仍保留 rAF 合并策略；但 authoritative restore 是后端完整逻辑会话快照，
   * 必须在 streaming 阶段也立即消费并覆盖旧缓存，避免 optimistic user 被重新拼回。
   *
   * @return true 表示本轮 updateMessages 应消费 prepared history restore 元数据
   */
  const shouldConsumePreparedHistoryRestoreSnapshot = (): boolean => (
    !isStreamingRef.current || isAuthoritativeRestoreKind(window.__preparedHistoryRestoreKind)
  );

  window.prepareHistoryRestoreSnapshot = (restoreKey, snapshotSignature, restoreKind) => {
    window.__preparedHistoryRestoreKey = restoreKey || null;
    window.__preparedHistoryRestoreSignature = snapshotSignature || null;
    window.__preparedHistoryRestoreKind = restoreKind || 'single_session';
    const snapshotStage = restoreKind === 'runtime_continue_authoritative'
      ? 'authoritative'
      : 'transitional';
    emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'prepared snapshot context', {
      restoreKey: window.__preparedHistoryRestoreKey,
      snapshotSignature: window.__preparedHistoryRestoreSignature,
      restoreKind: window.__preparedHistoryRestoreKind,
      snapshotStage,
    });
  };

  const cancelPendingUpdateMessages = () => {
    if (pendingUpdateRaf !== null) {
      cancelAnimationFrame(pendingUpdateRaf);
    }
    pendingUpdateRaf = null;
    pendingUpdateJson = null;
    pendingUpdateSequence = null;
    window.__pendingUpdateRaf = null;
    window.__pendingUpdateJson = null;
    window.__pendingUpdateSequence = null;
  };
  window.__cancelPendingUpdateMessages = cancelPendingUpdateMessages;

  const processUpdateMessages = (json: string, sequence: number | null = null) => {
    const minAcceptedSequence = window.__minAcceptedUpdateSequence ?? 0;
    if (sequence != null && sequence < minAcceptedSequence) {
      return;
    }

    const preparedHistoryRestore = shouldConsumePreparedHistoryRestoreSnapshot()
      ? consumePreparedHistoryRestoreSnapshot()
      : null;
    if (
      preparedHistoryRestore
      && window.__lastAppliedHistoryRestoreKey === preparedHistoryRestore.restoreKey
      && window.__lastAppliedHistoryRestoreSignature === preparedHistoryRestore.snapshotSignature
      && window.__lastAppliedHistoryRestoreKind === preparedHistoryRestore.restoreKind
    ) {
      emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'skip duplicate snapshot', preparedHistoryRestore);
      return;
    }

    try {
      const parsed = JSON.parse(json) as ClaudeMessage[];
      const sanitizedParsedResult = sanitizeFrontendVisibleMessages(parsed);
      const sanitizedParsed = sanitizedParsedResult.messages;
      if (sequence != null) {
        window.__minAcceptedUpdateSequence = Math.max(minAcceptedSequence, sequence);
      }
      if (preparedHistoryRestore) {
        const prefixCacheCleared = isAuthoritativeRestoreKind(preparedHistoryRestore.restoreKind)
          && (
            Array.isArray(window.__continuedSegmentHistoryPrefixMessages)
            || Array.isArray(window.__continuedSegmentPendingTailMessages)
            || !!window.__continuedSegmentFirstSnapshotSessionId
          );
        // 仅在成功解析并准备真正应用本次快照时，才记录“最后一次已落地的历史恢复快照”。
        window.__lastAppliedHistoryRestoreKey = preparedHistoryRestore.restoreKey;
        window.__lastAppliedHistoryRestoreSignature = preparedHistoryRestore.snapshotSignature;
        window.__lastAppliedHistoryRestoreKind = preparedHistoryRestore.restoreKind;
        emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'apply snapshot', {
          restoreKey: preparedHistoryRestore.restoreKey,
          snapshotSignature: preparedHistoryRestore.snapshotSignature,
          restoreKind: preparedHistoryRestore.restoreKind,
          messageCount: sanitizedParsed.length,
          adjacentDuplicateUserCount: countAdjacentDuplicateUserMessages(sanitizedParsed),
          prefixCacheCleared,
        });
        emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'applyHistoryRestoreSnapshot', {
          restoreRequestKey: preparedHistoryRestore.restoreKey,
          restoreKey: preparedHistoryRestore.restoreKey,
          snapshotSignature: preparedHistoryRestore.snapshotSignature,
          restoreKind: preparedHistoryRestore.restoreKind,
          messageCount: sanitizedParsed.length,
        });
        emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'updateMessagesForRestore', {
          restoreRequestKey: preparedHistoryRestore.restoreKey,
          restoreKey: preparedHistoryRestore.restoreKey,
          snapshotSignature: preparedHistoryRestore.snapshotSignature,
          restoreKind: preparedHistoryRestore.restoreKind,
          messageCount: sanitizedParsed.length,
          streaming: isStreamingRef.current,
        });
      }

      setMessages((prev) => {
        const restoreKind = preparedHistoryRestore?.restoreKind ?? null;
        const isAuthoritativeRestore = isAuthoritativeRestoreKind(restoreKind);

        if (isAuthoritativeRestore) {
          clearContinuedSegmentTransitionCache('authoritative_restore_replace');
          emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'authoritative snapshot replaced messages', {
            restoreKey: preparedHistoryRestore?.restoreKey ?? null,
            restoreKind,
            previousMessageCount: prev.length,
            nextMessageCount: sanitizedParsed.length,
            streaming: isStreamingRef.current,
          });
          emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'authoritativeSnapshotReplacedMessages', {
            restoreRequestKey: preparedHistoryRestore?.restoreKey ?? null,
            restoreKey: preparedHistoryRestore?.restoreKey ?? null,
            restoreKind,
            previousMessageCount: prev.length,
            nextMessageCount: sanitizedParsed.length,
            streaming: isStreamingRef.current,
          });
          // 中文注释：`scroll.log` 这类可视采样无法稳定反映真实 message array，
          // authoritative replace 一旦落地，就立刻补打一份轻量 dump，便于直接用 idea.log 对账真实顺序与 key。
          emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'authoritative snapshot message dump', {
            restoreRequestKey: preparedHistoryRestore?.restoreKey ?? null,
            restoreKey: preparedHistoryRestore?.restoreKey ?? null,
            restoreKind,
            snapshotKind: FULL_TRANSCRIPT_SNAPSHOT_KIND,
            transcriptSource: 'react_messages_state',
            messageCount: sanitizedParsed.length,
            messageDump: buildTranscriptDiagnosticMessageDump(sanitizedParsed),
          });
          emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'authoritativeSnapshotMessageDump', {
            restoreRequestKey: preparedHistoryRestore?.restoreKey ?? null,
            restoreKey: preparedHistoryRestore?.restoreKey ?? null,
            restoreKind,
            snapshotKind: FULL_TRANSCRIPT_SNAPSHOT_KIND,
            transcriptSource: 'react_messages_state',
            messageCount: sanitizedParsed.length,
            messageDump: buildTranscriptDiagnosticMessageDump(sanitizedParsed),
          });
          return sanitizedParsed;
        }

        if (isStreamingRef.current) {
          if (useBackendStreamingRenderRef.current) {
            let smartMerged = sanitizedParsed.map((newMsg, i) => {
              if (i === sanitizedParsed.length - 1) return newMsg;
              if (i < prev.length) {
                const oldMsg = prev[i];
                // 保留前端补写的耗时字段，避免被后端快照覆盖掉。
                if (typeof oldMsg.durationMs === 'number' && newMsg.type === 'assistant') {
                  newMsg = { ...newMsg, durationMs: oldMsg.durationMs };
                }
                if (
                  oldMsg.timestamp === newMsg.timestamp &&
                  oldMsg.type === newMsg.type &&
                  oldMsg.content === newMsg.content
                ) {
                  return oldMsg;
                }
              }
              return newMsg;
            });

            smartMerged = preserveLastAssistantIdentityIfSafe(prev, smartMerged, restoreKind);
            smartMerged = preserveStreamingAssistantContent(
              prev,
              smartMerged,
              isStreamingRef,
              streamingContentRef,
              findLastAssistantIndex,
              patchAssistantForStreaming,
            );
            const result = preserveShrinkIfNeeded(
              prev,
              appendOptimisticMessageIfMissing(prev, smartMerged),
              restoreKind,
            );

            let lastAssistantIdx = findLastAssistantIndex(result);
            if (
              lastAssistantIdx >= 0 &&
              streamingTurnIdRef.current > 0 &&
              result[lastAssistantIdx].__turnId !== streamingTurnIdRef.current
            ) {
              for (let i = result.length - 1; i >= 0; i--) {
                if (result[i].type === 'assistant' && result[i].__turnId === streamingTurnIdRef.current) {
                  lastAssistantIdx = i;
                  break;
                }
              }
            }
            if (lastAssistantIdx >= 0) {
              streamingMessageIndexRef.current = lastAssistantIdx;

              if (result[lastAssistantIdx]?.__turnId !== streamingTurnIdRef.current) {
                result[lastAssistantIdx] = {
                  ...result[lastAssistantIdx],
                  __turnId: streamingTurnIdRef.current,
                };
              }

              if (streamingContentRef.current && result[lastAssistantIdx]?.type === 'assistant') {
                const backendContent = result[lastAssistantIdx].content || '';
                if (streamingContentRef.current.length >= backendContent.length) {
                  result[lastAssistantIdx] = patchAssistantForStreaming({
                    ...result[lastAssistantIdx],
                    content: streamingContentRef.current,
                    isStreaming: true,
                  });
                } else {
                  streamingContentRef.current = backendContent;
                }
              }
            }

            return finalizeMessageList(prev, result);
          }

          const lastAssistantIdx = findLastAssistantIndex(sanitizedParsed);
          if (lastAssistantIdx < 0) {
            return finalizeMessageList(
              prev,
              preserveShrinkIfNeeded(
                prev,
                appendOptimisticMessageIfMissing(prev, sanitizedParsed),
                restoreKind,
              ),
            );
          }
        }

        if (!isStreamingRef.current) {
          let smartMerged = sanitizedParsed.map((newMsg, i) => {
            if (i < prev.length) {
              const oldMsg = prev[i];
              // 保留前端补写的耗时字段，避免非流式刷新或历史重载时丢失。
              if (typeof oldMsg.durationMs === 'number' && newMsg.type === 'assistant') {
                newMsg = { ...newMsg, durationMs: oldMsg.durationMs };
              }
              if (i < sanitizedParsed.length - 1) {
                if (
                  oldMsg.timestamp === newMsg.timestamp &&
                  oldMsg.type === newMsg.type &&
                  oldMsg.content === newMsg.content
                ) {
                  return oldMsg;
                }
              }
            }
            return newMsg;
          });

          smartMerged = preserveLastAssistantIdentityIfSafe(prev, smartMerged, restoreKind);
          smartMerged = preserveShrinkIfNeeded(prev, smartMerged, restoreKind);
          return finalizeMessageList(prev, appendOptimisticMessageIfMissing(prev, smartMerged));
        }

        let patched = [...sanitizedParsed];
        patched = appendOptimisticMessageIfMissing(prev, patched);
        patched = preserveLastAssistantIdentityIfSafe(prev, patched, restoreKind);
        patched = preserveStreamingAssistantContent(
          prev,
          patched,
          isStreamingRef,
          streamingContentRef,
          findLastAssistantIndex,
          patchAssistantForStreaming,
        );
        patched = preserveShrinkIfNeeded(prev, patched, restoreKind);

        const patchedAssistantIdx = findLastAssistantIndex(patched);
        if (patchedAssistantIdx >= 0 && patched[patchedAssistantIdx]?.type === 'assistant') {
          streamingMessageIndexRef.current = patchedAssistantIdx;
          patched[patchedAssistantIdx] = patchAssistantForStreaming({
            ...patched[patchedAssistantIdx],
            __turnId: streamingTurnIdRef.current,
          });
        }

        const hasStructuralChange = patched.length !== prev.length ||
          patched.some((msg, i) => {
            if (i >= prev.length) return true;
            const prevMsg = prev[i];
            if (msg.type !== prevMsg.type || msg.timestamp !== prevMsg.timestamp) {
              return true;
            }
            if (msg.type === 'assistant' && prevMsg.type === 'assistant') {
              const prevBlocks = extractRawBlocks(prevMsg.raw);
              const newBlocks = extractRawBlocks(msg.raw);
              const prevThinkingBlocks = prevBlocks.filter(
                (b): b is { type: 'thinking'; thinking?: string } => b?.type === 'thinking',
              );
              const newThinkingBlocks = newBlocks.filter(
                (b): b is { type: 'thinking'; thinking?: string } => b?.type === 'thinking',
              );
              if (prevThinkingBlocks.length !== newThinkingBlocks.length) return true;
              for (let j = 0; j < prevThinkingBlocks.length; j++) {
                const prevThinking = prevThinkingBlocks[j]?.thinking ?? '';
                const newThinking = newThinkingBlocks[j]?.thinking ?? '';
                if (prevThinking !== newThinking) return true;
              }
              if (prevBlocks.length !== newBlocks.length) return true;
            }
            return getStructuralRawBlockSignature(msg, extractRawBlocks) !==
              getStructuralRawBlockSignature(prevMsg, extractRawBlocks);
          });
        if (!hasStructuralChange) {
          return prev;
        }

        return finalizeMessageList(prev, patched);
      });
    } catch (error) {
      console.error('[Frontend] Failed to parse messages:', error);
    }
  };

  window.updateMessages = (json, sequenceArg) => {
    if (window.__sessionTransitioning) return;
    const sequence = parseSequence(sequenceArg);
    const minAcceptedSequence = window.__minAcceptedUpdateSequence ?? 0;
    if (sequence != null && sequence < minAcceptedSequence) {
      return;
    }

    if (isStreamingRef.current && window.__lastStreamActivityAt !== undefined) {
      window.__lastStreamActivityAt = Date.now();
    }

    if (isStreamingRef.current && isAuthoritativeRestoreKind(window.__preparedHistoryRestoreKind)) {
      cancelPendingUpdateMessages();
      processUpdateMessages(json, sequence);
      return;
    }

    if (isStreamingRef.current) {
      pendingUpdateJson = json;
      pendingUpdateSequence = sequence;
      window.__pendingUpdateJson = json;
      window.__pendingUpdateSequence = sequence;
      if (pendingUpdateRaf === null) {
        const timerId = requestAnimationFrame(() => {
          pendingUpdateRaf = null;
          window.__pendingUpdateRaf = null;
          const latestJson = pendingUpdateJson;
          const latestSequence = pendingUpdateSequence;
          pendingUpdateJson = null;
          pendingUpdateSequence = null;
          window.__pendingUpdateJson = null;
          window.__pendingUpdateSequence = null;
          if (latestJson) {
            processUpdateMessages(latestJson, latestSequence);
          }
        });
        pendingUpdateRaf = timerId as unknown as number;
        window.__pendingUpdateRaf = timerId as unknown as number;
      }
      return;
    }

    processUpdateMessages(json, sequence);
  };

  const pendingMessages = (window as unknown as Record<string, unknown>).__pendingUpdateMessages;
  if (typeof pendingMessages === 'string' && pendingMessages.length > 0) {
    delete (window as unknown as Record<string, unknown>).__pendingUpdateMessages;
    window.updateMessages(pendingMessages);
  } else if (
    pendingMessages &&
    typeof pendingMessages === 'object' &&
    typeof (pendingMessages as { json?: unknown }).json === 'string'
  ) {
    delete (window as unknown as Record<string, unknown>).__pendingUpdateMessages;
    const payload = pendingMessages as { json: string; sequence?: number | null };
    window.updateMessages(payload.json, payload.sequence ?? undefined);
  }

  window.updateStatus = (text) => {
    setStatus(text);
    if (suppressNextStatusToastRef.current) {
      suppressNextStatusToastRef.current = false;
      return;
    }
    addToast(text);
  };

  window.showLoading = (value) => {
    const isLoading = isTruthy(value);

    if (!isLoading && isStreamingRef.current) {
      return;
    }

    sendBridgeEvent('tab_loading_changed', JSON.stringify({ loading: isLoading }));

    setLoading((prevLoading) => {
      if (isLoading) {
        if (!prevLoading) {
          setLoadingStartTime(Date.now());
        }
      } else {
        // 非 streaming 分支在 loading 结束时补一次耗时，避免只依赖 onStreamEnd。
        // 如果 onStreamEnd 已经落过 durationMs，则这里直接跳过，避免重复写入。
        setLoadingStartTime((prevStartTime) => {
          if (prevStartTime != null) {
            const durationMs = Date.now() - prevStartTime;
            setMessages((prev) => {
              for (let i = prev.length - 1; i >= 0; i--) {
                if (prev[i].type === 'assistant') {
                  if (typeof prev[i].durationMs === 'number') {
                    return prev;
                  }
                  const next = [...prev];
                  next[i] = { ...next[i], durationMs };
                  return next;
                }
              }
              return prev;
            });
          }
          return null;
        });
      }
      return isLoading;
    });
  };

  window.showThinkingStatus = (value) => setIsThinking(isTruthy(value));
  window.showSummary = (summary) => {
    if (!summary || !summary.trim()) return;
    setStatus(summary);
  };
  window.setHistoryData = (data) => setHistoryData(data);

  const pendingStatus = (window as unknown as Record<string, unknown>).__pendingStatusText;
  if (typeof pendingStatus === 'string' && pendingStatus.length > 0) {
    delete (window as unknown as Record<string, unknown>).__pendingStatusText;
    window.updateStatus?.(pendingStatus);
  }

  const pendingLoading = window.__pendingLoadingState;
  if (typeof pendingLoading === 'boolean') {
    delete window.__pendingLoadingState;
    window.showLoading?.(pendingLoading);
  }

  const pendingUserMessage = window.__pendingUserMessage;
  if (typeof pendingUserMessage === 'string' && pendingUserMessage.length > 0) {
    delete window.__pendingUserMessage;
    window.addUserMessage?.(pendingUserMessage);
  }

  const pendingSummary = (window as unknown as Record<string, unknown>).__pendingSummaryText;
  if (typeof pendingSummary === 'string' && pendingSummary.length > 0) {
    delete (window as unknown as Record<string, unknown>).__pendingSummaryText;
    window.showSummary?.(pendingSummary);
  }

  window.patchMessageUuid = (content, uuid) => {
    if (window.__sessionTransitioning) return;
    if (!content || !uuid) return;

    setMessages((prev) => {
      for (let i = prev.length - 1; i >= 0; i -= 1) {
        const message = prev[i];
        if (message.type !== 'user') continue;
        if (getRawUuid(message)) continue;

        const rawText = extractRawBlocks(message.raw)
          .filter((block) => block?.type === 'text' && typeof block.text === 'string')
          .map((block) => String(block.text))
          .join('\n');
        if ((message.content || '') !== content && rawText !== content) continue;

        const raw: ClaudeMessage['raw'] =
          typeof message.raw === 'object' && message.raw
            ? { ...message.raw, uuid }
            : {
                uuid,
                message: {
                  content: [{ type: 'text' as const, text: message.content || content }],
                },
              };

        const next = [...prev];
        next[i] = {
          ...message,
          raw,
        };
        return next;
      }

      console.debug('[patchMessageUuid] no matching unresolved user message found for content:', content);
      return prev;
    });
  };

  window.clearMessages = () => {
    const preservePreparedHistoryRestore = hasPreparedHistoryRestoreSnapshot();
    const preserveContinuedPrefixForAuthoritativeRestore = preservePreparedHistoryRestore
      && isAuthoritativeRestoreKind(window.__preparedHistoryRestoreKind);
    if (pendingUpdateRaf !== null) {
      cancelAnimationFrame(pendingUpdateRaf);
      pendingUpdateRaf = null;
      pendingUpdateJson = null;
      pendingUpdateSequence = null;
      window.__pendingUpdateRaf = null;
      window.__pendingUpdateJson = null;
      window.__pendingUpdateSequence = null;
    }
    window.__deniedToolIds?.clear();
    if (preservePreparedHistoryRestore) {
      emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'clearMessagesBeforeRestore', {
        restoreRequestKey: window.__preparedHistoryRestoreKey,
        restoreKey: window.__preparedHistoryRestoreKey,
        snapshotSignature: window.__preparedHistoryRestoreSignature,
        restoreKind: window.__preparedHistoryRestoreKind,
      });
    } else {
      clearPreparedHistoryRestoreSnapshot();
    }
    // 中文注释：authoritative restore 的真实消费点在下一次 updateMessages 的 replace 分支；
    // 这里若提前清掉 continued 过渡缓存，就拿不到“authoritative_restore_replace”清理原因日志，
    // 也无法在 apply snapshot 阶段感知旧前缀确实仍待被权威快照接管。
    resetTransientUiState({
      preservePreparedHistoryRestore,
      preserveContinuedPrefix: preserveContinuedPrefixForAuthoritativeRestore,
    });
    closeContextUsageDialog();
    setMessages([]);
  };

  window.addErrorMessage = (message) => {
    addToast(message, 'error');
  };

  window.showContextUsageDialog = (json: string) => {
    try {
      const result = JSON.parse(json);
      const requestId = typeof result.requestId === 'string' ? result.requestId : null;
      const data: ContextUsageData = result.data || result;
      if (result.success === false) {
        if (closeContextUsageDialog(requestId)) {
          addToast(result.error || 'Failed to get context usage', 'error');
        }
        return;
      }
      updateContextUsageData(requestId, data);
    } catch (e) {
      debugError('[ContextUsage] Failed to parse context usage result:', e);
      closeContextUsageDialog();
      addToast('Failed to parse context usage data', 'error');
    }
  };

  window.onContextUsageError = (message: string, requestId?: string) => {
    if (closeContextUsageDialog(requestId)) {
      addToast(message, 'error');
    }
  };

  window.addHistoryMessage = (message: ClaudeMessage) => {
    if (window.__sessionTransitioning) return;
    const sanitizedMessage = sanitizeFrontendVisibleMessage(message).message;
    if (!sanitizedMessage) {
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'drop addHistoryMessage after sanitization', {
        messageType: message.type,
        messagePreview: summarizeDiagnosticMessageContent(message.content),
      });
      return;
    }
    setMessages((prev) => [...prev, sanitizedMessage]);
  };

  window.historyLoadComplete = () => {
    releaseSessionTransition();
    window.__pendingHistoryLoadComplete = false;
    const pendingToast = window.__pendingSessionTransitionToast;
    if (pendingToast) {
      window.__pendingSessionTransitionToast = undefined;
      addToast(pendingToast.message, pendingToast.type);
    }
    window.__lastStreamEndedTurnId = undefined;
    window.__lastStreamEndedAt = undefined;
  };

  if (window.__pendingHistoryLoadComplete) {
    window.historyLoadComplete();
  }

  window.addUserMessage = (content: string) => {
    if (window.__sessionTransitioning) return;
    const candidateUserMessage: ClaudeMessage = {
      type: 'user',
      content: content || '',
      timestamp: new Date().toISOString(),
      raw: {
        role: 'user',
        content: toUserTextRawBlocks(content || ''),
      },
    };
    const sanitizedUserMessage = sanitizeFrontendVisibleMessage(candidateUserMessage).message;
    if (!sanitizedUserMessage) {
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'drop addUserMessage after sanitization', {
        messagePreview: summarizeDiagnosticMessageContent(content),
      });
      return;
    }
    setMessages((prev) => {
      // If the last message is an optimistic message with matching content,
      // skip adding — the frontend already rendered the optimistic copy.
      // Otherwise addUserMessage + optimistic create a brief duplicate until
      // the next updateMessages deduplicates them.
      const lastMsg = prev[prev.length - 1];
      if (lastMsg?.isOptimistic && lastMsg.type === 'user' && lastMsg.content === sanitizedUserMessage.content) {
        return prev;
      }
      return [...prev, sanitizedUserMessage];
    });
    userPausedRef.current = false;
    isUserAtBottomRef.current = true;
    requestAnimationFrame(() => {
      if (messagesContainerRef.current) {
        messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
      }
    });
  };
}
