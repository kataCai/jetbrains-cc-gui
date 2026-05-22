/**
 * messageCallbacks.ts
 *
 * Registers window bridge callbacks for message management:
 * updateMessages, updateStatus, showLoading, showThinkingStatus,
 * setHistoryData, clearMessages, addErrorMessage, addHistoryMessage,
 * historyLoadComplete, addUserMessage.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { ClaudeMessage } from '../../../types';
import { sendBridgeEvent } from '../../../utils/bridge';
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

  if (window.__pendingUpdateRaf != null) {
    cancelAnimationFrame(window.__pendingUpdateRaf);
    window.__pendingUpdateRaf = null;
    window.__pendingUpdateJson = null;
    window.__pendingUpdateSequence = null;
  }
  let pendingUpdateJson: string | null = null;
  let pendingUpdateRaf: number | null = null;
  let pendingUpdateSequence: number | null = null;

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

    try {
      const parsed = JSON.parse(json) as ClaudeMessage[];
      if (sequence != null) {
        window.__minAcceptedUpdateSequence = Math.max(minAcceptedSequence, sequence);
      }

      setMessages((prev) => {
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

            smartMerged = preserveLastAssistantIdentity(prev, smartMerged, findLastAssistantIndex);
            smartMerged = preserveStreamingAssistantContent(
              prev,
              smartMerged,
              isStreamingRef,
              streamingContentRef,
              findLastAssistantIndex,
              patchAssistantForStreaming,
            );
            const result = preserveLatestMessagesOnShrink(
              prev,
              appendOptimisticMessageIfMissing(prev, smartMerged),
              options.currentProviderRef.current,
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
              preserveLatestMessagesOnShrink(
                prev,
                appendOptimisticMessageIfMissing(prev, parsed),
                options.currentProviderRef.current,
              ),
            );
          }
        }

        if (!isStreamingRef.current) {
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

          smartMerged = preserveLastAssistantIdentity(prev, smartMerged, findLastAssistantIndex);
          smartMerged = preserveLatestMessagesOnShrink(prev, smartMerged, options.currentProviderRef.current);
          return finalizeMessageList(prev, appendOptimisticMessageIfMissing(prev, smartMerged));
        }

        let patched = [...parsed];
        patched = appendOptimisticMessageIfMissing(prev, patched);
        patched = preserveLastAssistantIdentity(prev, patched, findLastAssistantIndex);
        patched = preserveStreamingAssistantContent(
          prev,
          patched,
          isStreamingRef,
          streamingContentRef,
          findLastAssistantIndex,
          patchAssistantForStreaming,
        );
        patched = preserveLatestMessagesOnShrink(prev, patched, options.currentProviderRef.current);

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
        const rafId = requestAnimationFrame(() => {
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
        pendingUpdateRaf = rafId;
        window.__pendingUpdateRaf = rafId;
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
    resetTransientUiState();
    setMessages([]);
  };

  window.addErrorMessage = (message) => {
    addToast(message, 'error');
  };

  window.addHistoryMessage = (message: ClaudeMessage) => {
    if (window.__sessionTransitioning) return;
    setMessages((prev) => [...prev, message]);
  };

  window.historyLoadComplete = () => {
    releaseSessionTransition();
    const pendingToast = window.__pendingSessionTransitionToast;
    if (pendingToast) {
      window.__pendingSessionTransitionToast = undefined;
      addToast(pendingToast.message, pendingToast.type);
    }
    window.__lastStreamEndedTurnId = undefined;
    window.__lastStreamEndedAt = undefined;
    setMessages((prev) => {
      if (prev.length === 0) return prev;
      return prev.map((m) => ({ ...m }));
    });
  };

  window.addUserMessage = (content: string) => {
    if (window.__sessionTransitioning) return;
    const userMessage: ClaudeMessage = {
      type: 'user',
      content: content || '',
      timestamp: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, userMessage]);
    userPausedRef.current = false;
    isUserAtBottomRef.current = true;
    requestAnimationFrame(() => {
      if (messagesContainerRef.current) {
        messagesContainerRef.current.scrollTop = messagesContainerRef.current.scrollHeight;
      }
    });
  };
}
