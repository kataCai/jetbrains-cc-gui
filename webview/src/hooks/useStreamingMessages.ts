import { useRef } from 'react';
import type { ClaudeMessage } from '../types';

/** `raw.message.content` 中的单个内容块。 */
interface ContentBlock {
  type: string;
  text?: string;
  thinking?: string;
  [key: string]: unknown;
}

// 与后端 StreamDeltaThrottler 的 33ms 节流保持一致，避免前后端节奏失配。
export const THROTTLE_INTERVAL = 33;

interface UseStreamingMessagesReturn {
  // Content refs
  streamingContentRef: React.MutableRefObject<string>;
  streamingThinkingRef: React.MutableRefObject<string>;
  isStreamingRef: React.MutableRefObject<boolean>;
  useBackendStreamingRenderRef: React.MutableRefObject<boolean>;
  streamingMessageIndexRef: React.MutableRefObject<number>;

  // Throttle control refs
  contentUpdateTimeoutRef: React.MutableRefObject<number | null>;
  thinkingUpdateTimeoutRef: React.MutableRefObject<number | null>;
  lastContentUpdateRef: React.MutableRefObject<number>;
  lastThinkingUpdateRef: React.MutableRefObject<number>;

  // Auto-expanded thinking keys
  autoExpandedThinkingKeysRef: React.MutableRefObject<Set<string>>;

  // Turn tracking
  streamingTurnIdRef: React.MutableRefObject<number>;
  turnIdCounterRef: React.MutableRefObject<number>;

  // Helper functions
  findLastAssistantIndex: (list: ClaudeMessage[]) => number;
  extractRawBlocks: (raw: unknown) => ContentBlock[];
  getOrCreateStreamingAssistantIndex: (list: ClaudeMessage[]) => number;
  patchAssistantForStreaming: (assistant: ClaudeMessage) => ClaudeMessage;

  // Reset function
  resetStreamingState: () => void;
}

const normalizeMultilineText = (value: string): string => value.replace(/\r\n?/g, '\n');

const getOverlapLength = (left: string, right: string): number => {
  const max = Math.min(left.length, right.length);
  for (let size = max; size > 0; size -= 1) {
    if (left.slice(-size) === right.slice(0, size)) {
      return size;
    }
  }
  return 0;
};

/**
 * 合并 snapshot 与本地 streaming 文本时，优先保留本地段内容，
 * 同时吸收明显的 suffix/prefix 重叠，避免 markdown fence 等尾部重复。
 *
 * @param existingText backend 已有文本
 * @param streamingText 前端累计中的 streaming 文本
 * @return 合并后的文本
 */
const mergeTextWithOverlap = (existingText: string, streamingText: string): string => {
  const normalizedExisting = normalizeMultilineText(existingText);
  const normalizedStreaming = normalizeMultilineText(streamingText);
  if (!normalizedExisting) return normalizedStreaming;
  if (!normalizedStreaming) return normalizedExisting;
  if (normalizedExisting === normalizedStreaming) return normalizedStreaming;
  if (normalizedStreaming.includes(normalizedExisting)) return normalizedStreaming;
  if (normalizedExisting.includes(normalizedStreaming)) return normalizedStreaming;

  const overlap = getOverlapLength(normalizedExisting, normalizedStreaming);
  if (overlap > 0) {
    return normalizedExisting + normalizedStreaming.slice(overlap);
  }

  const reverseOverlap = getOverlapLength(normalizedStreaming, normalizedExisting);
  if (reverseOverlap > 0) {
    return normalizedStreaming + normalizedExisting.slice(reverseOverlap);
  }

  return normalizedStreaming;
};

/**
 * 判断是否为文本块。
 *
 * @param block 待判断内容块
 * @return 是否为文本块
 */
const isTextBlock = (block: unknown): block is { type: 'text'; text: string } =>
  Boolean(block) &&
  typeof block === 'object' &&
  (block as { type?: string }).type === 'text' &&
  typeof (block as { text?: unknown }).text === 'string';

/**
 * 判断是否为思考块。
 *
 * @param block 待判断内容块
 * @return 是否为思考块
 */
const isThinkingBlock = (block: unknown): block is { type: 'thinking'; thinking: string } =>
  Boolean(block) &&
  typeof block === 'object' &&
  (block as { type?: string }).type === 'thinking' &&
  typeof (block as { thinking?: unknown }).thinking === 'string';

/**
 * 判断两个 streaming 文本/思考块是否语义等价。
 *
 * @param left 左侧块
 * @param right 右侧块
 * @return 两块内容归一化后是否等价
 */
const areStreamingBlocksEquivalent = (left: unknown, right: unknown): boolean => {
  if (isTextBlock(left) && isTextBlock(right)) {
    return normalizeMultilineText(left.text) === normalizeMultilineText(right.text);
  }
  if (isThinkingBlock(left) && isThinkingBlock(right)) {
    return normalizeMultilineText(left.thinking) === normalizeMultilineText(right.thinking);
  }
  return false;
};

/**
 * 读取 thinking 块中的可比对文本。
 *
 * @param block 内容块
 * @return thinking 文本；若结构兼容旧字段，也回退到 `text`
 */
const getThinkingText = (block: ContentBlock | undefined): string => {
  if (!block) return '';
  if (typeof block.thinking === 'string') return block.thinking;
  if (typeof block.text === 'string') return block.text;
  return '';
};

/**
 * 压缩连续重复的 thinking/text phase，避免 replay 时重复渲染。
 *
 * @param blocks 待压缩的 raw block 列表
 * @return 压缩后的 block 列表
 */
const collapseDuplicateStreamingPhases = (blocks: ContentBlock[]): ContentBlock[] => {
  const output: ContentBlock[] = [];
  for (let i = 0; i < blocks.length; i += 1) {
    const current = blocks[i];
    const next = blocks[i + 1];
    const hasPhasePair =
      (isThinkingBlock(current) || isTextBlock(current)) &&
      (isThinkingBlock(next) || isTextBlock(next));

    if (hasPhasePair) {
      const pair = [current, next];
      const previousPair = output.slice(-2);
      if (
        previousPair.length === 2 &&
        areStreamingBlocksEquivalent(previousPair[0], pair[0]) &&
        areStreamingBlocksEquivalent(previousPair[1], pair[1])
      ) {
        i += 1;
        continue;
      }
    }

    const previous = output[output.length - 1];
    if (previous && areStreamingBlocksEquivalent(previous, current)) {
      continue;
    }

    output.push(current);
  }

  return output;
};

/**
 * Hook for managing streaming message state and helper functions.
 */
export function useStreamingMessages(): UseStreamingMessagesReturn {
  const streamingContentRef = useRef('');
  const streamingThinkingRef = useRef('');
  const isStreamingRef = useRef(false);
  const useBackendStreamingRenderRef = useRef(false);
  const streamingMessageIndexRef = useRef<number>(-1);

  const contentUpdateTimeoutRef = useRef<number | null>(null);
  const thinkingUpdateTimeoutRef = useRef<number | null>(null);
  const lastContentUpdateRef = useRef(0);
  const lastThinkingUpdateRef = useRef(0);

  const autoExpandedThinkingKeysRef = useRef<Set<string>>(new Set());

  // 工具块第一次出现在 raw 尾部时，记录当时文本长度。
  // 后续若 streaming 文本继续增长，则新增文本属于“工具块之后”的后缀文本，
  // 需要追加到工具块后面，而不是继续扩写工具块前的最后一个 text block。
  const trailingStructuralTextBoundaryRef = useRef<{ signature: string; textLength: number } | null>(null);

  const streamingTurnIdRef = useRef(-1);
  const turnIdCounterRef = useRef(0);

  /**
   * 查找最后一个 assistant 消息索引。
   *
   * @param list 消息列表
   * @return 最后一个 assistant 的索引；不存在时返回 -1
   */
  const findLastAssistantIndex = (list: ClaudeMessage[]): number => {
    for (let i = list.length - 1; i >= 0; i -= 1) {
      if (list[i]?.type === 'assistant') return i;
    }
    return -1;
  };

  /**
   * 从 raw 结构中提取内容块数组。
   *
   * @param raw Claude raw message 或兼容对象
   * @return 内容块数组；不存在时返回空数组
   */
  const extractRawBlocks = (raw: unknown): ContentBlock[] => {
    if (!raw || typeof raw !== 'object') return [];
    const rawObj = raw as Record<string, unknown>;
    const msg = rawObj.message as Record<string, unknown> | undefined;
    const blocks = rawObj.content ?? msg?.content;
    return Array.isArray(blocks) ? (blocks as ContentBlock[]) : [];
  };

  /**
   * 为结构性 block 生成轻量签名，用于检测工具块边界变化。
   *
   * @param block raw block
   * @return 结构签名
   */
  const getStructuralBlockSignature = (block: ContentBlock): string => {
    if (block.type === 'tool_use') {
      return `tool_use:${block.id ?? ''}:${block.name ?? ''}`;
    }
    if (block.type === 'tool_result') {
      return `tool_result:${block.tool_use_id ?? ''}:${block.is_error === true ? '1' : '0'}`;
    }
    return String(block.type ?? '');
  };

  /**
   * 用累计文本同步 raw 中的 text block。
   * 关键约束：
   * 1. backend raw 结构仍然是结构真源；
   * 2. text 内容以当前更完整的一侧为准；
   * 3. 当工具块已出现在末尾且后续文本继续增长时，要把新增文本拆到工具块后；
   * 4. 对纯文本单块场景，允许用 overlap 合并，避免尾部重复。
   *
   * @param blocks backend/raw 当前 block 列表
   * @param content 当前最优 streaming 文本
   * @return 对齐后的 block 列表
   */
  const syncTextBlocksWithContent = (blocks: ContentBlock[], content: string): ContentBlock[] => {
    if (!content) return blocks;

    const textIndices = blocks
      .map((block, index) => (block?.type === 'text' ? index : -1))
      .filter((index) => index >= 0);

    if (textIndices.length === 0) {
      return [...blocks, { type: 'text', text: content }];
    }

    const lastTextIdx = textIndices[textIndices.length - 1];
    const prefixText = textIndices
      .slice(0, -1)
      .map((index) => (typeof blocks[index]?.text === 'string' ? String(blocks[index].text) : ''))
      .join('');
    const allText = textIndices
      .map((index) => (typeof blocks[index]?.text === 'string' ? String(blocks[index].text) : ''))
      .join('');
    const trailingStructuralBlocks = blocks
      .slice(lastTextIdx + 1)
      .filter((block) => block?.type !== 'text' && block?.type !== 'thinking');
    const trailingStructuralSignature = trailingStructuralBlocks
      .map(getStructuralBlockSignature)
      .join('|');

    if (trailingStructuralSignature && allText && content.startsWith(allText)) {
      const previousBoundary = trailingStructuralTextBoundaryRef.current;
      const canReuseBoundary =
        previousBoundary &&
        (trailingStructuralSignature === previousBoundary.signature ||
          trailingStructuralSignature.startsWith(`${previousBoundary.signature}|`));

      if (!canReuseBoundary) {
        trailingStructuralTextBoundaryRef.current = {
          signature: trailingStructuralSignature,
          textLength: allText.length,
        };
      }

      const boundary = trailingStructuralTextBoundaryRef.current;
      if (boundary && content.length > boundary.textLength) {
        const textBeforeStructuralBlocks = content.slice(0, boundary.textLength);
        const textAfterStructuralBlocks = content.slice(boundary.textLength);
        const desiredLastPreToolText = textBeforeStructuralBlocks.startsWith(prefixText)
          ? textBeforeStructuralBlocks.slice(prefixText.length)
          : textBeforeStructuralBlocks;
        const nextBlocks = [...blocks];
        nextBlocks[lastTextIdx] = { ...nextBlocks[lastTextIdx], text: desiredLastPreToolText };
        if (trailingStructuralSignature !== boundary.signature) {
          trailingStructuralTextBoundaryRef.current = {
            signature: trailingStructuralSignature,
            textLength: boundary.textLength,
          };
        }
        return collapseDuplicateStreamingPhases([
          ...nextBlocks,
          { type: 'text', text: textAfterStructuralBlocks },
        ]);
      }
    } else if (!trailingStructuralSignature) {
      trailingStructuralTextBoundaryRef.current = null;
    }

    if (!content.startsWith(prefixText)) {
      if (textIndices.length !== 1) {
        const firstTextIdx = textIndices[0];
        const hasStructuralBoundaryBetweenTexts = blocks
          .slice(firstTextIdx + 1, lastTextIdx)
          .some((block) => block?.type !== 'text' && block?.type !== 'thinking');
        // 当前 streaming 只覆盖了前导文本区间，但 backend raw 已经带有工具后的尾段文本。
        // 这时只更新最前面的 text block，并保留后续 backend-only trailing text。
        if (hasStructuralBoundaryBetweenTexts) {
          const currentFirstText =
            typeof blocks[firstTextIdx]?.text === 'string' ? String(blocks[firstTextIdx].text) : '';
          const mergedFirstText = mergeTextWithOverlap(currentFirstText, content);
          if (currentFirstText === mergedFirstText) {
            return collapseDuplicateStreamingPhases(blocks);
          }
          const nextBlocks = [...blocks];
          nextBlocks[firstTextIdx] = { ...nextBlocks[firstTextIdx], text: mergedFirstText };
          return collapseDuplicateStreamingPhases(nextBlocks);
        }
        return collapseDuplicateStreamingPhases(blocks);
      }
      const currentText = typeof blocks[lastTextIdx]?.text === 'string' ? String(blocks[lastTextIdx].text) : '';
      const mergedSingleText = mergeTextWithOverlap(currentText, content);
      if (currentText === mergedSingleText) {
        return collapseDuplicateStreamingPhases(blocks);
      }
      const nextBlocks = [...blocks];
      nextBlocks[lastTextIdx] = { ...nextBlocks[lastTextIdx], text: mergedSingleText };
      return collapseDuplicateStreamingPhases(nextBlocks);
    }

    const desiredLastText = content.slice(prefixText.length);
    if (!desiredLastText) {
      return collapseDuplicateStreamingPhases(blocks);
    }

    const currentLastText = typeof blocks[lastTextIdx]?.text === 'string' ? String(blocks[lastTextIdx].text) : '';
    const mergedLastText = mergeTextWithOverlap(currentLastText, desiredLastText);
    if (currentLastText === mergedLastText) {
      return collapseDuplicateStreamingPhases(blocks);
    }

    const nextBlocks = [...blocks];
    nextBlocks[lastTextIdx] = { ...nextBlocks[lastTextIdx], text: mergedLastText };
    return collapseDuplicateStreamingPhases(nextBlocks);
  };

  /**
   * 用累计 thinking 内容同步 raw 中的 thinking block。
   * 当一轮 thinking 被 tool_use 分段时，只把剩余 suffix 分配给最后一个 thinking block，
   * 避免把前面已经出现的思考内容再次拼接到最后一个块里。
   *
   * @param blocks backend/raw 当前 block 列表
   * @param thinking 当前累计 thinking 文本
   * @return 对齐后的 block 列表
   */
  const syncThinkingBlocksWithContent = (blocks: ContentBlock[], thinking: string): ContentBlock[] => {
    if (!thinking) return blocks;

    const thinkingIndices = blocks
      .map((block, index) => (block?.type === 'thinking' ? index : -1))
      .filter((index) => index >= 0);

    if (thinkingIndices.length === 0) {
      return collapseDuplicateStreamingPhases([{ type: 'thinking', thinking, text: thinking }, ...blocks]);
    }

    const lastThinkingIdx = thinkingIndices[thinkingIndices.length - 1];
    const prefixThinking = thinkingIndices
      .slice(0, -1)
      .map((index) => getThinkingText(blocks[index]))
      .join('');

    if (!thinking.startsWith(prefixThinking)) {
      if (thinkingIndices.length !== 1) {
        return collapseDuplicateStreamingPhases(blocks);
      }
      const currentThinking = getThinkingText(blocks[lastThinkingIdx]);
      const mergedThinking = mergeTextWithOverlap(currentThinking, thinking);
      if (currentThinking === mergedThinking) {
        return collapseDuplicateStreamingPhases(blocks);
      }
      const nextBlocks = [...blocks];
      nextBlocks[lastThinkingIdx] = {
        ...nextBlocks[lastThinkingIdx],
        thinking: mergedThinking,
        text: mergedThinking,
      };
      return collapseDuplicateStreamingPhases(nextBlocks);
    }

    const desiredLastThinking = thinking.slice(prefixThinking.length);
    if (!desiredLastThinking) {
      return collapseDuplicateStreamingPhases(blocks);
    }

    const currentLastThinking = getThinkingText(blocks[lastThinkingIdx]);
    const mergedThinking = mergeTextWithOverlap(currentLastThinking, desiredLastThinking);
    if (currentLastThinking === mergedThinking) {
      return collapseDuplicateStreamingPhases(blocks);
    }

    const nextBlocks = [...blocks];
    nextBlocks[lastThinkingIdx] = {
      ...nextBlocks[lastThinkingIdx],
      thinking: mergedThinking,
      text: mergedThinking,
    };
    return collapseDuplicateStreamingPhases(nextBlocks);
  };

  /**
   * 获取或创建当前 streaming assistant 的消息索引。
   * 该方法会在必要时直接向传入列表尾部追加 assistant 占位消息，
   * 因此调用方必须保证传入的是可变副本，而不是 React state 原数组。
   *
   * @param list 可变消息列表副本
   * @return assistant 消息索引
   */
  const getOrCreateStreamingAssistantIndex = (list: ClaudeMessage[]): number => {
    const currentIdx = streamingMessageIndexRef.current;
    if (currentIdx >= 0 && currentIdx < list.length && list[currentIdx]?.type === 'assistant') {
      return currentIdx;
    }
    const lastAssistantIdx = findLastAssistantIndex(list);
    if (lastAssistantIdx >= 0) {
      streamingMessageIndexRef.current = lastAssistantIdx;
      return lastAssistantIdx;
    }
    streamingMessageIndexRef.current = list.length;
    list.push({
      type: 'assistant',
      content: '',
      isStreaming: true,
      timestamp: new Date().toISOString(),
      raw: { message: { content: [] } } as ClaudeMessage['raw'],
    });
    return streamingMessageIndexRef.current;
  };

  /**
   * 按当前 streaming 缓冲修补 assistant 消息。
   * 约束：
   * 1. `.content` 取 delta 累计值与 backend snapshot 中更完整的一侧，避免文本回退；
   * 2. raw 结构以 backend 为准，但 text/thinking 块内容要与前端累计值保持一致；
   * 3. thinking 在首个 snapshot 前也要可见；
   * 4. 工具块之后的 streaming 尾文本不能错误并回工具块之前的 text block。
   *
   * @param assistant 当前 assistant 消息
   * @return 修补后的 assistant 消息
   */
  const patchAssistantForStreaming = (assistant: ClaudeMessage): ClaudeMessage => {
    const deltaContent = streamingContentRef.current || '';
    const backendContent = assistant.content || '';
    const bestContent = deltaContent.length >= backendContent.length ? deltaContent : backendContent;
    const deltaThinking = streamingThinkingRef.current || '';

    let patchedRaw = assistant.raw;
    if (patchedRaw && typeof patchedRaw === 'object') {
      const rawObj = patchedRaw as Record<string, unknown>;
      const msg = rawObj.message as Record<string, unknown> | undefined;
      const rawContent = Array.isArray(rawObj.content)
        ? (rawObj.content as ContentBlock[])
        : Array.isArray(msg?.content) ? (msg.content as ContentBlock[]) : [];

      let blocks = [...rawContent];
      blocks = syncThinkingBlocksWithContent(blocks, deltaThinking);
      blocks = syncTextBlocksWithContent(blocks, bestContent);

      patchedRaw = (msg
        ? { ...rawObj, message: { ...msg, content: blocks } }
        : { ...rawObj, content: blocks }) as ClaudeMessage['raw'];
    } else if (deltaThinking) {
      let blocks: ContentBlock[] = [];
      blocks = syncThinkingBlocksWithContent(blocks, deltaThinking);
      blocks = syncTextBlocksWithContent(blocks, bestContent);
      patchedRaw = { message: { content: blocks } } as ClaudeMessage['raw'];
    }

    return {
      ...assistant,
      content: bestContent,
      raw: patchedRaw,
      isStreaming: true,
    } as ClaudeMessage;
  };

  /**
   * 重置全部 streaming 临时状态。
   * 只清理当前回合运行态，不触碰已持久化到消息数组中的内容。
   */
  const resetStreamingState = () => {
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    streamingMessageIndexRef.current = -1;
    lastContentUpdateRef.current = 0;
    lastThinkingUpdateRef.current = 0;
    autoExpandedThinkingKeysRef.current.clear();
    trailingStructuralTextBoundaryRef.current = null;
    streamingTurnIdRef.current = -1;

    if (contentUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(contentUpdateTimeoutRef.current);
      contentUpdateTimeoutRef.current = null;
    }
    if (thinkingUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(thinkingUpdateTimeoutRef.current);
      thinkingUpdateTimeoutRef.current = null;
    }
  };

  return {
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    streamingMessageIndexRef,
    contentUpdateTimeoutRef,
    thinkingUpdateTimeoutRef,
    lastContentUpdateRef,
    lastThinkingUpdateRef,
    autoExpandedThinkingKeysRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    findLastAssistantIndex,
    extractRawBlocks,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,
    resetStreamingState,
  };
}
