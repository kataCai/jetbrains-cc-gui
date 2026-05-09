import { useRef } from 'react';
import type { ClaudeContentOrResultBlock, ClaudeMessage } from '../types';

export const THROTTLE_INTERVAL = 50; // 50ms throttle interval

interface UseStreamingMessagesReturn {
  // Content refs
  streamingContentRef: React.MutableRefObject<string>;
  isStreamingRef: React.MutableRefObject<boolean>;
  useBackendStreamingRenderRef: React.MutableRefObject<boolean>;
  streamingMessageIndexRef: React.MutableRefObject<number>;

  // Text segment refs
  streamingTextSegmentsRef: React.MutableRefObject<string[]>;
  activeTextSegmentIndexRef: React.MutableRefObject<number>;

  // Thinking segment refs
  streamingThinkingSegmentsRef: React.MutableRefObject<string[]>;
  activeThinkingSegmentIndexRef: React.MutableRefObject<number>;

  // Tool use tracking
  seenToolUseCountRef: React.MutableRefObject<number>;

  // Throttle control refs
  contentUpdateTimeoutRef: React.MutableRefObject<ReturnType<typeof setTimeout> | null>;
  thinkingUpdateTimeoutRef: React.MutableRefObject<ReturnType<typeof setTimeout> | null>;
  lastContentUpdateRef: React.MutableRefObject<number>;
  lastThinkingUpdateRef: React.MutableRefObject<number>;

  // Auto-expanded thinking keys
  autoExpandedThinkingKeysRef: React.MutableRefObject<Set<string>>;

  // Turn tracking
  streamingTurnIdRef: React.MutableRefObject<number>;
  turnIdCounterRef: React.MutableRefObject<number>;

  // Helper functions
  findLastAssistantIndex: (list: ClaudeMessage[]) => number;
  extractRawBlocks: (raw: unknown) => any[];
  buildStreamingBlocks: (existingBlocks: any[]) => any[];
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
 * 但会吸收明显的 suffix/prefix 重叠，避免 markdown fence 等尾部重复。
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

const isTextBlock = (block: unknown): block is { type: 'text'; text: string } =>
  Boolean(block) &&
  typeof block === 'object' &&
  (block as { type?: string }).type === 'text' &&
  typeof (block as { text?: unknown }).text === 'string';

const isThinkingBlock = (block: unknown): block is { type: 'thinking'; thinking: string } =>
  Boolean(block) &&
  typeof block === 'object' &&
  (block as { type?: string }).type === 'thinking' &&
  typeof (block as { thinking?: unknown }).thinking === 'string';

const areStreamingBlocksEquivalent = (left: unknown, right: unknown): boolean => {
  if (isTextBlock(left) && isTextBlock(right)) {
    return normalizeMultilineText(left.text) === normalizeMultilineText(right.text);
  }
  if (isThinkingBlock(left) && isThinkingBlock(right)) {
    return normalizeMultilineText(left.thinking) === normalizeMultilineText(right.thinking);
  }
  return false;
};

const isStreamingReplaceableBlock = (block: unknown): block is { type: 'text' | 'thinking' } =>
  Boolean(block) &&
  typeof block === 'object' &&
  (((block as { type?: string }).type === 'text') || ((block as { type?: string }).type === 'thinking'));

/**
 * 当同一轮 phase replay 被重复回放时，压缩连续重复的 thinking/text 片段，
 * 避免本地临时 streaming block 在没有结构性边界时重复渲染。
 */
const collapseDuplicateStreamingPhases = (blocks: any[]): any[] => {
  const output: any[] = [];
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
    if (
      previous &&
      areStreamingBlocksEquivalent(previous, current)
    ) {
      continue;
    }

    output.push(current);
  }

  return output;
};

/**
 * 仅替换当前 streaming 已覆盖到的 text/thinking block。
 * 超出当前 phase 范围、但来自 backend snapshot 的 trailing block 应保留，
 * 否则本地 patch 会把尚未收口的结构性快照误删。
 */
const mergeStreamingBlocksIntoSnapshot = (
  existingBlocks: ClaudeContentOrResultBlock[],
  streamingBlocks: ClaudeContentOrResultBlock[],
): ClaudeContentOrResultBlock[] => {
  const output: ClaudeContentOrResultBlock[] = [];
  const existingReplaceableCount = existingBlocks.filter(isStreamingReplaceableBlock).length;
  let streamingReplaceableIndex = 0;
  let streamingBlockIndex = 0;

  for (const block of existingBlocks) {
    if (!isStreamingReplaceableBlock(block)) {
      output.push(block);
      while (
        streamingBlockIndex < streamingBlocks.length &&
        !isStreamingReplaceableBlock(streamingBlocks[streamingBlockIndex])
      ) {
        streamingBlockIndex += 1;
      }
      continue;
    }

    const replacement = streamingBlocks[streamingBlockIndex];
    if (replacement && isStreamingReplaceableBlock(replacement)) {
      output.push(replacement);
      streamingReplaceableIndex += 1;
      streamingBlockIndex += 1;
      continue;
    }

    output.push(block);
  }

  if (streamingReplaceableIndex >= existingReplaceableCount) {
    while (streamingBlockIndex < streamingBlocks.length) {
      const trailing = streamingBlocks[streamingBlockIndex];
      if (isStreamingReplaceableBlock(trailing)) {
        output.push(trailing);
      }
      streamingBlockIndex += 1;
    }
  }

  return output;
};

/**
 * Hook for managing streaming message state and helper functions
 */
export function useStreamingMessages(): UseStreamingMessagesReturn {
  // Content refs
  const streamingContentRef = useRef('');
  const isStreamingRef = useRef(false);
  const useBackendStreamingRenderRef = useRef(false);
  const streamingMessageIndexRef = useRef<number>(-1);

  // Text segment refs
  const streamingTextSegmentsRef = useRef<string[]>([]);
  const activeTextSegmentIndexRef = useRef<number>(-1);

  // Thinking segment refs
  const streamingThinkingSegmentsRef = useRef<string[]>([]);
  const activeThinkingSegmentIndexRef = useRef<number>(-1);

  // Tool use tracking
  const seenToolUseCountRef = useRef(0);

  // Throttle control refs
  const contentUpdateTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const thinkingUpdateTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const lastContentUpdateRef = useRef(0);
  const lastThinkingUpdateRef = useRef(0);

  // Auto-expanded thinking keys
  const autoExpandedThinkingKeysRef = useRef<Set<string>>(new Set());

  // Turn tracking
  const streamingTurnIdRef = useRef(-1);
  const turnIdCounterRef = useRef(0);

  // Helper: Find last assistant message index
  const findLastAssistantIndex = (list: ClaudeMessage[]): number => {
    for (let i = list.length - 1; i >= 0; i -= 1) {
      if (list[i]?.type === 'assistant') return i;
    }
    return -1;
  };

  // Helper: Extract raw blocks from message
  const extractRawBlocks = (raw: unknown): any[] => {
    if (!raw || typeof raw !== 'object') return [];
    const rawObj: any = raw;
    const blocks = rawObj.content ?? rawObj.message?.content;
    return Array.isArray(blocks) ? blocks : [];
  };

  const normalizeThinking = (thinking: string): string => {
    return thinking
      .replace(/\r\n?/g, '\n')
      .replace(/\n[ \t]*\n+/g, '\n')
      .replace(/^\n+/, '')
      .replace(/\n+$/, '');
  };

  // Helper: Build streaming blocks from segments
  const buildStreamingBlocks = (existingBlocks: any[]): any[] => {
    const textSegments = streamingTextSegmentsRef.current;
    const thinkingSegments = streamingThinkingSegmentsRef.current;

    const output: any[] = [];
    let thinkingIdx = 0;
    let textIdx = 0;

    for (const block of existingBlocks) {
      if (!block || typeof block !== 'object') {
        continue;
      }
      if (block.type === 'thinking') {
        const thinking = thinkingSegments[thinkingIdx];
        thinkingIdx += 1;
        if (typeof thinking === 'string' && thinking.length > 0) {
          const normalized = normalizeThinking(thinking);
          if (normalized.length > 0) {
            output.push({ type: 'thinking', thinking: normalized });
          }
        }
        continue;
      }
      if (block.type === 'text') {
        const text = textSegments[textIdx];
        const existingText = typeof block.text === 'string' ? block.text : '';
        textIdx += 1;
        if (typeof text === 'string' && text.length > 0) {
          output.push({ type: 'text', text: mergeTextWithOverlap(existingText, text) });
        }
        continue;
      }

      output.push(block);
    }

    const phasesCount = Math.max(textSegments.length, thinkingSegments.length);
    const appendFromPhase = Math.max(textIdx, thinkingIdx);
    for (let phase = appendFromPhase; phase < phasesCount; phase += 1) {
      const thinking = thinkingSegments[phase];
      if (typeof thinking === 'string' && thinking.length > 0) {
        const normalized = normalizeThinking(thinking);
        if (normalized.length > 0) {
          output.push({ type: 'thinking', thinking: normalized });
        }
      }
        const text = textSegments[phase];
        if (typeof text === 'string' && text.length > 0) {
          output.push({ type: 'text', text });
        }
      }

    return collapseDuplicateStreamingPhases(output);
  };

  /**
   * Get or create streaming assistant message index.
   * NOTE: This function MUTATES the passed list array by pushing a new message
   * if no assistant message exists. Call this only with a copied array (e.g., [...prev]).
   * @param list - Mutable message array (should be a copy, not the original state)
   * @returns The index of the assistant message
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
    // No assistant: append a placeholder (mutates the list)
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

  // Helper: Patch assistant message for streaming
  const patchAssistantForStreaming = (assistant: ClaudeMessage): ClaudeMessage => {
    const existingRaw = (assistant.raw && typeof assistant.raw === 'object') ? (assistant.raw as any) : { message: { content: [] } };
    const existingBlocks = extractRawBlocks(existingRaw);
    const streamingBlocks = buildStreamingBlocks(existingBlocks);
    const newBlocks = mergeStreamingBlocksIntoSnapshot(existingBlocks, streamingBlocks);

    const rawPatched = existingRaw.message
      ? { ...existingRaw, message: { ...(existingRaw.message || {}), content: newBlocks } }
      : { ...existingRaw, content: newBlocks };

    return {
      ...assistant,
      content: streamingContentRef.current,
      raw: rawPatched,
      isStreaming: true,
    } as ClaudeMessage;
  };

  // Reset all streaming state
  const resetStreamingState = () => {
    streamingContentRef.current = '';
    streamingTextSegmentsRef.current = [];
    streamingThinkingSegmentsRef.current = [];
    streamingMessageIndexRef.current = -1;
    activeTextSegmentIndexRef.current = -1;
    activeThinkingSegmentIndexRef.current = -1;
    seenToolUseCountRef.current = 0;
    lastContentUpdateRef.current = 0;
    lastThinkingUpdateRef.current = 0;
    autoExpandedThinkingKeysRef.current.clear();
    streamingTurnIdRef.current = -1;

    if (contentUpdateTimeoutRef.current) {
      clearTimeout(contentUpdateTimeoutRef.current);
      contentUpdateTimeoutRef.current = null;
    }
    if (thinkingUpdateTimeoutRef.current) {
      clearTimeout(thinkingUpdateTimeoutRef.current);
      thinkingUpdateTimeoutRef.current = null;
    }
  };

  return {
    // Content refs
    streamingContentRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    streamingMessageIndexRef,

    // Text segment refs
    streamingTextSegmentsRef,
    activeTextSegmentIndexRef,

    // Thinking segment refs
    streamingThinkingSegmentsRef,
    activeThinkingSegmentIndexRef,

    // Tool use tracking
    seenToolUseCountRef,

    // Throttle control refs
    contentUpdateTimeoutRef,
    thinkingUpdateTimeoutRef,
    lastContentUpdateRef,
    lastThinkingUpdateRef,

    // Auto-expanded thinking keys
    autoExpandedThinkingKeysRef,

    // Turn tracking
    streamingTurnIdRef,
    turnIdCounterRef,

    // Helper functions
    findLastAssistantIndex,
    extractRawBlocks,
    buildStreamingBlocks,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,

    // Reset function
    resetStreamingState,
  };
}
