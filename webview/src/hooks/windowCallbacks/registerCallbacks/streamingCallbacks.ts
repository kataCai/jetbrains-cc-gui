/**
 * streamingCallbacks.ts
 *
 * Registers window bridge callbacks for streaming:
 * onStreamStart, onContentDelta, onThinkingDelta, onStreamEnd, onPermissionDenied.
 */

import { startTransition } from 'react';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { ClaudeRawMessage } from '../../../types';
import { sendBridgeEvent } from '../../../utils/bridge';
import { THROTTLE_INTERVAL } from '../../useStreamingMessages';
import { parseSequence } from '../parseSequence';
import { getStreamEndHandlingMode } from '../messageSync';

/**
 * 检测 streaming 卡死的超时时间。
 * 如果在该时长内既没有 delta，也没有 streaming heartbeat/updateMessages，
 * 则认为后端的 onStreamEnd 可能丢失，前端只做一次本地恢复性收口。
 * 这里不能再把 watchdog 超时升级成 completed 语义，否则会再次制造误报。
 */
const STREAM_STALL_TIMEOUT_MS = 60_000;
const STREAM_STALL_CHECK_INTERVAL_MS = 5_000;
const TASK_COMPLETED_DEFER_FRAME_LIMIT = 8;

export function registerStreamingCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setMessages,
    setStreamingActive,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setExpandedThinking,
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    lastContentUpdateRef,
    contentUpdateTimeoutRef,
    lastThinkingUpdateRef,
    thinkingUpdateTimeoutRef,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,
  } = options;

  /**
   * 在聊天区最终消息快照尚未落地时，延后发送 Tab completed 事件。
   * 这里显式等待 pending updateMessages 清空后，再用下一帧上报 completed，
   * 尽量保证“聊天区已出现完成总结”早于“Tab 显示已完成”。
   * 同时保留有限帧兜底，避免极端情况下因为某个挂起标记未清掉而永久不完成。
   *
   * @param frameAttempt 当前已重试的帧次数
   */
  const flushDeferredTaskCompleted = (frameAttempt = 0): void => {
    if (!window.__pendingTaskCompleted) {
      return;
    }

    const hasPendingMessageFlush =
      window.__pendingUpdateRaf != null ||
      (typeof window.__pendingUpdateJson === 'string' && window.__pendingUpdateJson.length > 0);

    if (hasPendingMessageFlush && frameAttempt < TASK_COMPLETED_DEFER_FRAME_LIMIT) {
      window.__pendingTaskCompletedRaf = requestAnimationFrame(() => {
        flushDeferredTaskCompleted(frameAttempt + 1);
      });
      return;
    }

    window.__pendingTaskCompleted = false;
    window.__pendingTaskCompletedRaf = null;
    sendBridgeEvent('tab_status_changed', JSON.stringify({ status: 'completed' }));
  };

  if (window.__stallWatchdogInterval != null) {
    clearInterval(window.__stallWatchdogInterval);
    window.__stallWatchdogInterval = null;
  }
  window.__lastStreamActivityAt = 0;

  const clearStallWatchdog = () => {
    if (window.__stallWatchdogInterval != null) {
      clearInterval(window.__stallWatchdogInterval);
      window.__stallWatchdogInterval = null;
    }
  };

  const startStallWatchdog = () => {
    clearStallWatchdog();
    window.__lastStreamActivityAt = Date.now();
    window.__stallWatchdogInterval = setInterval(() => {
      if (!isStreamingRef.current) {
        clearStallWatchdog();
        return;
      }
      const elapsed = Date.now() - (window.__lastStreamActivityAt ?? 0);
      if (elapsed >= STREAM_STALL_TIMEOUT_MS) {
        console.warn(
          `[StreamWatchdog] Stream stalled for ${elapsed}ms — performing local recovery only`,
        );
        clearStallWatchdog();
        // watchdog 只负责恢复前端本地 streaming/loading/thinking 状态，
        // 不能再复用 onStreamEnd 去间接制造 completed 语义。
        isStreamingRef.current = false;
        useBackendStreamingRenderRef.current = false;
        streamingMessageIndexRef.current = -1;
        streamingTurnIdRef.current = -1;
        streamingContentRef.current = '';
        streamingThinkingRef.current = '';
        autoExpandedThinkingKeysRef.current.clear();
        setStreamingActive(false);
        setLoading(false);
        setLoadingStartTime(null);
        setIsThinking(false);
      }
    }, STREAM_STALL_CHECK_INTERVAL_MS);
  };

  window.onStreamStart = () => {
    if (window.__sessionTransitioning) return;

    // 新 turn 开始前先清理上一轮残留的延迟 updateMessages，
    // 避免旧 snapshot 在新一轮 streaming 中被误回放。
    if (typeof window.__cancelPendingUpdateMessages === 'function') {
      window.__cancelPendingUpdateMessages();
    }
    window.__pendingUpdateJson = null;
    window.__lastStreamEndedTurnId = undefined;
    window.__lastStreamEndedAt = undefined;
    window.__streamEndProcessedTurnId = undefined;
    window.__turnStartedAt = Date.now();

    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    isStreamingRef.current = true;
    startStallWatchdog();
    useBackendStreamingRenderRef.current = false;
    autoExpandedThinkingKeysRef.current.clear();
    setStreamingActive(true);

    streamingMessageIndexRef.current = -1;
    turnIdCounterRef.current += 1;
    streamingTurnIdRef.current = turnIdCounterRef.current;
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      if (last?.type === 'assistant') {
        streamingMessageIndexRef.current = prev.length - 1;
        const updated = [...prev];
        updated[prev.length - 1] = {
          ...updated[prev.length - 1],
          isStreaming: true,
          __turnId: streamingTurnIdRef.current,
        };
        return updated;
      }
      streamingMessageIndexRef.current = prev.length;
      return [
        ...prev,
        {
          type: 'assistant',
          content: '',
          isStreaming: true,
          timestamp: new Date().toISOString(),
          __turnId: streamingTurnIdRef.current,
        },
      ];
    });
  };

  /**
   * 生成基于 rAF 的 streaming 更新调度器。
   * 该调度器只负责触发 patch，不自己持有文本内容；
   * 真正的 streaming 文本来源仍然读取 hook 中的 ref。
   *
   * @param timeoutRef 当前调度器的 rAF handle 引用
   * @param lastUpdateRef 最近一次实际刷新的时间戳
   * @return 可复用的调度函数
   */
  const createStreamingRafScheduler = (
    timeoutRef: React.MutableRefObject<number | null>,
    lastUpdateRef: React.MutableRefObject<number>,
  ) => {
    const scheduleRaf = (): void => {
      if (timeoutRef.current != null) return;
      timeoutRef.current = requestAnimationFrame(() => {
        timeoutRef.current = null;
        const now = Date.now();
        const elapsed = now - lastUpdateRef.current;
        if (elapsed < THROTTLE_INTERVAL) {
          scheduleRaf();
          return;
        }
        lastUpdateRef.current = now;
        startTransition(() => {
          setMessages((prev) => {
            const newMessages = [...prev];
            let idx: number;
            if (useBackendStreamingRenderRef.current) {
              idx = streamingMessageIndexRef.current;
              if (idx < 0) return prev;
            } else {
              idx = getOrCreateStreamingAssistantIndex(newMessages);
            }
            if (idx >= 0 && newMessages[idx]?.type === 'assistant') {
              newMessages[idx] = patchAssistantForStreaming({
                ...newMessages[idx],
                isStreaming: true,
              });
            }
            return newMessages;
          });
        });
      });
    };
    return scheduleRaf;
  };

  const scheduleContentRaf = createStreamingRafScheduler(contentUpdateTimeoutRef, lastContentUpdateRef);
  const scheduleThinkingRaf = createStreamingRafScheduler(thinkingUpdateTimeoutRef, lastThinkingUpdateRef);

  window.onContentDelta = (delta: string) => {
    if (window.__sessionTransitioning) return;
    if (!isStreamingRef.current) return;
    window.__lastStreamActivityAt = Date.now();
    streamingContentRef.current += delta;
    scheduleContentRaf();
  };

  window.onThinkingDelta = (delta: string) => {
    if (window.__sessionTransitioning) return;
    if (!isStreamingRef.current) return;
    window.__lastStreamActivityAt = Date.now();
    streamingThinkingRef.current += delta;
    scheduleThinkingRaf();
  };

  window.onStreamEnd = (sequence?: string | number) => {
    if (window.__sessionTransitioning) return;

    const currentTurnId = streamingTurnIdRef.current;
    const handlingMode = getStreamEndHandlingMode(
      options.currentProviderRef.current,
      isStreamingRef.current,
      currentTurnId,
    );
    if (currentTurnId > 0 && window.__streamEndProcessedTurnId === currentTurnId) {
      return;
    }
    if (handlingMode === 'skip') {
      return;
    }

    clearStallWatchdog();
    const parsedSequence = parseSequence(sequence);
    console.info(
      '[TaskLifecycle] eventType=stream_end_ui'
        + ` sequence=${parsedSequence ?? -1}`
        + ` turnId=${currentTurnId}`
        + ` handlingMode=${handlingMode}`
        + ` isStreaming=${isStreamingRef.current}`,
    );
    if (parsedSequence != null && parsedSequence >= 0) {
      window.__minAcceptedUpdateSequence = Math.max(window.__minAcceptedUpdateSequence ?? 0, parsedSequence);
    }

    if (handlingMode === 'minimal') {
      if (typeof window.__cancelPendingUpdateMessages === 'function') {
        window.__cancelPendingUpdateMessages();
      }
      setStreamingActive(false);
      setLoading(false);
      setLoadingStartTime(null);
      setIsThinking(false);
      window.__streamEndProcessedTurnId = currentTurnId > 0 ? currentTurnId : undefined;
      return;
    }

    let backendSnapshotContent: string | undefined;
    let backendSnapshotRaw: ClaudeRawMessage | string | undefined;
    if (typeof window.__pendingUpdateJson === 'string' && window.__pendingUpdateJson.length > 0) {
      try {
        const parsed = JSON.parse(window.__pendingUpdateJson) as Array<Record<string, unknown>>;
        for (let i = parsed.length - 1; i >= 0; i -= 1) {
          if (parsed[i]?.type !== 'assistant') continue;
          const rawContent = parsed[i].content;
          const content = typeof rawContent === 'string' ? rawContent : '';
          if (content) {
            backendSnapshotContent = content;
            const rawVal = parsed[i].raw;
            if (rawVal != null && (typeof rawVal === 'object' || typeof rawVal === 'string')) {
              backendSnapshotRaw = rawVal as ClaudeRawMessage | string;
            }
          }
          break;
        }
      } catch (error) {
        console.warn('[Frontend] Failed to parse __pendingUpdateJson on stream end:', error);
      }
    }

    if (typeof window.__cancelPendingUpdateMessages === 'function') {
      window.__cancelPendingUpdateMessages();
    }

    if (contentUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(contentUpdateTimeoutRef.current);
      contentUpdateTimeoutRef.current = null;
    }
    if (thinkingUpdateTimeoutRef.current != null) {
      cancelAnimationFrame(thinkingUpdateTimeoutRef.current);
      thinkingUpdateTimeoutRef.current = null;
    }

    const keysToCollapse = new Set(autoExpandedThinkingKeysRef.current);
    const turnStartedAt = window.__turnStartedAt;
    window.__turnStartedAt = undefined;
    const endedStreamingTurnId = streamingTurnIdRef.current;
    const endedStreamingMessageIndex = streamingMessageIndexRef.current;
    const endedStreamingContent =
      backendSnapshotContent && backendSnapshotContent.length > streamingContentRef.current.length
        ? backendSnapshotContent
        : streamingContentRef.current;
    const endedBackendRaw = backendSnapshotRaw;

    type TextBlock = { type: 'text'; text: string };
    const hasTextBlocks = (value: unknown): value is { message: { content: TextBlock[] } } => {
      if (!value || typeof value !== 'object') return false;
      const msg = (value as { message?: unknown }).message;
      if (!msg || typeof msg !== 'object') return false;
      const content = (msg as { content?: unknown }).content;
      return Array.isArray(content);
    };
    const getTextLenFromRaw = (raw: unknown): number => {
      let parsedRaw: unknown = raw;
      if (typeof raw === 'string') {
        try {
          parsedRaw = JSON.parse(raw);
        } catch (error) {
          console.warn('[Frontend] Failed to parse raw JSON for length comparison:', error);
          return 0;
        }
      }
      if (!hasTextBlocks(parsedRaw)) return 0;
      return parsedRaw.message.content
        .filter((b): b is TextBlock => b?.type === 'text' && typeof b.text === 'string')
        .reduce((sum, b) => sum + b.text.length, 0);
    };

    // 在进入 updater 之前先清空 refs，避免 deferred updateMessages 与旧 streaming refs 竞态。
    isStreamingRef.current = false;
    useBackendStreamingRenderRef.current = false;
    streamingMessageIndexRef.current = -1;
    streamingTurnIdRef.current = -1;
    streamingContentRef.current = '';
    streamingThinkingRef.current = '';
    autoExpandedThinkingKeysRef.current.clear();

    window.__lastStreamEndedTurnId = endedStreamingTurnId > 0 ? endedStreamingTurnId : undefined;
    window.__lastStreamEndedAt = Date.now();

    setMessages((prev) => {
      let newMessages = prev;
      const idx = endedStreamingMessageIndex;
      if (prev.length > 0 && idx >= 0 && idx < prev.length && prev[idx]?.type === 'assistant') {
        newMessages = [...prev];
        const finalContent = endedStreamingContent || newMessages[idx].content || '';
        const durationMs =
          typeof turnStartedAt === 'number' && turnStartedAt > 0
            ? Date.now() - turnStartedAt
            : undefined;
        let finalRaw = newMessages[idx].raw;
        if (endedBackendRaw != null && getTextLenFromRaw(endedBackendRaw) >= getTextLenFromRaw(finalRaw)) {
          finalRaw = endedBackendRaw;
        }
        newMessages[idx] = {
          ...newMessages[idx],
          content: finalContent,
          raw: finalRaw,
          isStreaming: false,
          __turnId: endedStreamingTurnId,
          ...(durationMs != null ? { durationMs } : {}),
        };
      }
      return newMessages;
    });

    if (setExpandedThinking && keysToCollapse.size > 0) {
      setExpandedThinking((prev) => {
        const next = { ...prev };
        keysToCollapse.forEach((key) => {
          next[key] = false;
        });
        return next;
      });
    }

    setStreamingActive(false);
    setLoading(false);
    setLoadingStartTime(null);
    setIsThinking(false);
    window.__streamEndProcessedTurnId = endedStreamingTurnId > 0 ? endedStreamingTurnId : undefined;
  };

  /**
   * 任务真正完成时由后端显式调用。
   * 这里单独上报 completed，避免继续复用 onStreamEnd 导致 turn 级结束误报为任务完成。
   */
  window.onTaskCompleted = () => {
    if (window.__sessionTransitioning) return;
    console.info('[TaskLifecycle] eventType=task_completed_ui');
    if (window.__pendingTaskCompletedRaf != null) {
      cancelAnimationFrame(window.__pendingTaskCompletedRaf);
      window.__pendingTaskCompletedRaf = null;
    }
    window.__pendingTaskCompleted = true;
    flushDeferredTaskCompleted();
  };

  window.onStreamingHeartbeat = () => {
    if (isStreamingRef.current && window.__lastStreamActivityAt !== undefined) {
      window.__lastStreamActivityAt = Date.now();
    }
  };

  window.onPermissionDenied = () => {
    if (!window.__deniedToolIds) {
      window.__deniedToolIds = new Set<string>();
    }

    const idsToAdd: string[] = [];

    setMessages((currentMessages) => {
      try {
        for (let i = currentMessages.length - 1; i >= 0; i--) {
          const msg = currentMessages[i];
          if (msg.type === 'assistant' && msg.raw) {
            const rawObj = typeof msg.raw === 'string' ? JSON.parse(msg.raw) : msg.raw;
            const content = rawObj.content || rawObj.message?.content;

            if (Array.isArray(content)) {
              const toolUses = content.filter(
                (block: { type?: string; id?: string }) =>
                  block.type === 'tool_use' && block.id,
              ) as Array<{ type: string; id: string; name?: string }>;

              if (toolUses.length > 0) {
                const nextMsg = currentMessages[i + 1];
                const existingResultIds = new Set<string>();

                if (nextMsg?.type === 'user' && nextMsg.raw) {
                  const nextRaw =
                    typeof nextMsg.raw === 'string' ? JSON.parse(nextMsg.raw) : nextMsg.raw;
                  const nextContent = nextRaw.content || nextRaw.message?.content;
                  if (Array.isArray(nextContent)) {
                    nextContent.forEach((block: { type?: string; tool_use_id?: string }) => {
                      if (block.type === 'tool_result' && block.tool_use_id) {
                        existingResultIds.add(block.tool_use_id);
                      }
                    });
                  }
                }

                for (const tu of toolUses) {
                  if (!existingResultIds.has(tu.id)) {
                    idsToAdd.push(tu.id);
                  }
                }

                break;
              }
            }
          }
        }
      } catch (e) {
        console.error('[Frontend] Error in onPermissionDenied:', e);
      }

      return [...currentMessages];
    });

    for (const id of idsToAdd) {
      window.__deniedToolIds!.add(id);
    }
  };
}
