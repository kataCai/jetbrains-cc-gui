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

export function registerMessageCallbacks(
  options: UseWindowCallbacksOptions,
  resetTransientUiState: () => void,
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
  const clearContinuedSegmentTransitionCache = (): void => {
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
      clearContinuedSegmentTransitionCache();
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
      clearContinuedSegmentTransitionCache();
      return nextList;
    }
    const currentSessionId = currentSessionIdRef.current?.trim() || null;
    const hasPrefixCache = Array.isArray(window.__continuedSegmentHistoryPrefixMessages);
    const shouldMergeContinuedSegment = !!currentSessionId
      && window.__continuedSegmentHistoryPrefixSessionId?.trim() === currentSessionId
      && hasPrefixCache;
    if (shouldMergeContinuedSegment) {
      const mergedList = mergeContinuedSegmentPrefixIfNeeded(nextList);
      if (window.__continuedSegmentFirstSnapshotSessionId === currentSessionId) {
        emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued segment first snapshot applied', {
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
        window.__continuedSegmentPendingTailMessages = cloneContinuedTailMessages(selectedPendingTail);
        emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued pending tail cached', {
          pendingTailCount: window.__continuedSegmentPendingTailMessages?.length ?? null,
          nextMessageCount: nextList.length,
          pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
          pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
          awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
        });
      }
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued prefix merge skipped', {
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

  window.prepareHistoryRestoreSnapshot = (restoreKey, snapshotSignature, restoreKind) => {
    window.__preparedHistoryRestoreKey = restoreKey || null;
    window.__preparedHistoryRestoreSignature = snapshotSignature || null;
    window.__preparedHistoryRestoreKind = restoreKind || 'single_session';
    emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'prepared snapshot context', {
      restoreKey: window.__preparedHistoryRestoreKey,
      snapshotSignature: window.__preparedHistoryRestoreSignature,
      restoreKind: window.__preparedHistoryRestoreKind,
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

    const preparedHistoryRestore = !isStreamingRef.current
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
          messageCount: parsed.length,
          prefixCacheCleared,
        });
      }

      setMessages((prev) => {
        const restoreKind = preparedHistoryRestore?.restoreKind ?? null;
        const isAuthoritativeRestore = isAuthoritativeRestoreKind(restoreKind);

        if (isStreamingRef.current) {
          if (useBackendStreamingRenderRef.current) {
            let smartMerged = parsed.map((newMsg, i) => {
              if (i === parsed.length - 1) return newMsg;
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

          const lastAssistantIdx = findLastAssistantIndex(parsed);
          if (lastAssistantIdx < 0) {
            return finalizeMessageList(
              prev,
              preserveShrinkIfNeeded(
                prev,
                appendOptimisticMessageIfMissing(prev, parsed),
                restoreKind,
              ),
            );
          }
        }

        if (!isStreamingRef.current) {
          if (isAuthoritativeRestore) {
            clearContinuedSegmentTransitionCache();
            return parsed;
          }

          let smartMerged = parsed.map((newMsg, i) => {
            if (i < prev.length) {
              const oldMsg = prev[i];
              // 保留前端补写的耗时字段，避免非流式刷新或历史重载时丢失。
              if (typeof oldMsg.durationMs === 'number' && newMsg.type === 'assistant') {
                newMsg = { ...newMsg, durationMs: oldMsg.durationMs };
              }
              if (i < parsed.length - 1) {
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

        let patched = [...parsed];
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
    clearPreparedHistoryRestoreSnapshot();
    resetTransientUiState();
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
    setMessages((prev) => [...prev, message]);
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
    const userMessage: ClaudeMessage = {
      type: 'user',
      content: content || '',
      timestamp: new Date().toISOString(),
    };
    setMessages((prev) => {
      // If the last message is an optimistic message with matching content,
      // skip adding — the frontend already rendered the optimistic copy.
      // Otherwise addUserMessage + optimistic create a brief duplicate until
      // the next updateMessages deduplicates them.
      const lastMsg = prev[prev.length - 1];
      if (lastMsg?.isOptimistic && lastMsg.type === 'user' && lastMsg.content === content) {
        return prev;
      }
      return [...prev, userMessage];
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
