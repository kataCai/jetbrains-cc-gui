/**
 * sessionTransition.ts
 *
 * Helpers for session transition guard management and transient UI state reset.
 * These functions encapsulate the logic that coordinates the React state setters
 * and streaming refs when a new session is initiated.
 */

import type { MutableRefObject } from 'react';

export interface ResetTransientUiStateOptions {
  clearToasts: () => void;
  setStatus: React.Dispatch<React.SetStateAction<string>>;
  setLoading: React.Dispatch<React.SetStateAction<boolean>>;
  setLoadingStartTime: React.Dispatch<React.SetStateAction<number | null>>;
  setIsThinking: React.Dispatch<React.SetStateAction<boolean>>;
  setStreamingActive: React.Dispatch<React.SetStateAction<boolean>>;

  // Streaming refs
  isStreamingRef: MutableRefObject<boolean>;
  useBackendStreamingRenderRef: MutableRefObject<boolean>;
  streamingMessageIndexRef: MutableRefObject<number>;
  streamingContentRef: MutableRefObject<string>;
  streamingThinkingRef: MutableRefObject<string>;
  autoExpandedThinkingKeysRef: MutableRefObject<Set<string>>;
  contentUpdateTimeoutRef: MutableRefObject<number | null>;
  thinkingUpdateTimeoutRef: MutableRefObject<number | null>;

  // Turn tracking ref (for streaming assistant isolation)
  streamingTurnIdRef: MutableRefObject<number>;
}

/**
 * 单次 transient reset 的运行选项。
 * 目前仅 continued 切段会要求保留前缀缓存；普通新建、历史恢复和删除重建仍应清理缓存，
 * 避免旧逻辑会话前缀跨会话误拼接。
 */
export interface ResetTransientUiStateRunOptions {
  preserveContinuedPrefix?: boolean;
}

/**
 * 清理瞬时 UI 状态与流式渲染引用。
 * 调用方可在 continued 切段场景通过 `preserveContinuedPrefix` 保留前缀缓存；
 * 其他会话切换路径默认清理 continued 缓存，避免 stale prefix 污染独立会话。
 *
 * @param opts React setter 与流式状态引用
 * @return 可挂载到 window 上的同步 reset 函数
 */
export const buildResetTransientUiState = (opts: ResetTransientUiStateOptions) => {
  return (runOptions: ResetTransientUiStateRunOptions = {}) => {
    opts.clearToasts();
    opts.setStatus('');
    opts.setLoading(false);
    opts.setLoadingStartTime(null);
    opts.setIsThinking(false);
    opts.setStreamingActive(false);
    opts.isStreamingRef.current = false;
    opts.useBackendStreamingRenderRef.current = false;
    opts.streamingMessageIndexRef.current = -1;
    opts.streamingContentRef.current = '';
    opts.streamingThinkingRef.current = '';
    opts.autoExpandedThinkingKeysRef.current.clear();
    // Reset active turn ID to prevent stale streaming assistant recovery.
    // NOTE: turnIdCounterRef is intentionally NOT reset — it must stay monotonically
    // increasing across sessions so that stale messages from an old session can never
    // collide with a new session's turn IDs (and React keys like "turn-N" stay unique).
    opts.streamingTurnIdRef.current = -1;
    // Clear stream-end idempotency guard to avoid stale state across sessions.
    window.__streamEndProcessedTurnId = undefined;
    // 清理尚未消费的历史恢复快照上下文，避免跨会话误复用。
    window.__preparedHistoryRestoreKey = null;
    window.__preparedHistoryRestoreSignature = null;
    if (!runOptions.preserveContinuedPrefix) {
      // 中文注释：普通 session transition 复位时必须同时清掉 continued segment 的前缀缓存，
      // 否则旧分段消息可能在后续独立会话或历史恢复里被错误拼回界面。
      window.__continuedSegmentFirstSnapshotSessionId = null;
      window.__continuedSegmentHistoryPrefixMessages = null;
      window.__continuedSegmentHistoryPrefixSessionId = null;
      window.__continuedSegmentPendingTailMessages = null;
      window.__continuedSegmentPendingSourceSessionId = null;
      window.__continuedSegmentPendingLogicalConversationId = null;
      window.__continuedSegmentPendingCreatedAt = null;
      window.__continuedSegmentPendingReason = null;
      window.__continuedSegmentAwaitingFirstSessionId = false;
    }
    if (opts.contentUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(opts.contentUpdateTimeoutRef.current);
      opts.contentUpdateTimeoutRef.current = null;
    }
    if (opts.thinkingUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(opts.thinkingUpdateTimeoutRef.current);
      opts.thinkingUpdateTimeoutRef.current = null;
    }
  };
};

/**
 * Release the session transition guard flags set by beginSessionTransition
 * (useSessionManagement).
 */
export const releaseSessionTransition = (): void => {
  if (window.__sessionTransitioning) {
    window.__sessionTransitioning = false;
  }
  window.__sessionTransitionToken = null;
  // 历史恢复链路在结束时清理待消费上下文，避免后续普通快照误命中历史 restore 逻辑。
  window.__preparedHistoryRestoreKey = null;
  window.__preparedHistoryRestoreSignature = null;
};
