import { useCallback, useRef, useState, type MutableRefObject } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, HistoryData, HistorySessionSummary } from '../types';
import { sendBridgeEvent } from '../utils/bridge';
import { debugLog, emitFrontendDiagnosticLog } from '../utils/debug';

type ViewMode = 'chat' | 'history' | 'settings';

type ToastType = 'info' | 'success' | 'warning' | 'error';

const createSessionTransitionToken = () =>
  `transition-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

/**
 * 统一计算历史列表项在前端侧的“会话操作键”。
 * 对已聚合的 Codex continued conversation 优先使用 logicalConversationId，
 * 老数据和 Claude 会话则继续回退到物理 sessionId。
 *
 * @param session 历史会话摘要
 * @return 可用于加载/删除/收藏等操作的稳定键
 */
const getConversationKey = (session: HistorySessionSummary): string =>
  session.logicalConversationId?.trim() || session.sessionId;

interface ResolvedHistoryTarget {
  summary: HistorySessionSummary | null;
  logicalConversationId: string | null;
  representativeSessionId: string | null;
  relatedSessionIds: string[];
}

/**
 * 统一解析前端历史列表里某个操作键对应的真实会话目标。
 * 该解析兼容三种数据形态：
 * 1. 老数据：只有物理 sessionId；
 * 2. 前端本地聚合：historyData 里仍保留多条物理分段；
 * 3. 后端已聚合：historyData 里直接返回带 logicalConversationId 的单条摘要。
 *
 * @param historyData 当前历史数据快照
 * @param conversationKey 历史列表点击或批量操作传入的键
 * @return 包含逻辑会话 id、代表分段 id 与关联物理分段 id 集合的解析结果
 */
function resolveHistoryTarget(
  historyData: HistoryData | null,
  conversationKey: string,
): ResolvedHistoryTarget {
  const normalizedKey = (conversationKey || '').trim();
  if (!normalizedKey || !historyData?.sessions?.length) {
    return {
      summary: null,
      logicalConversationId: null,
      representativeSessionId: normalizedKey || null,
      relatedSessionIds: normalizedKey ? [normalizedKey] : [],
    };
  }

  const matches = historyData.sessions.filter((session) => {
    const logicalConversationId = session.logicalConversationId?.trim();
    return session.sessionId === normalizedKey || logicalConversationId === normalizedKey;
  });

  if (matches.length === 0) {
    return {
      summary: null,
      logicalConversationId: null,
      representativeSessionId: normalizedKey,
      relatedSessionIds: [normalizedKey],
    };
  }

  const representative = matches.reduce<HistorySessionSummary>((best, current) => {
    if (current.activeSegmentSessionId && current.sessionId === current.activeSegmentSessionId) {
      return current;
    }
    if (best.activeSegmentSessionId && best.sessionId === best.activeSegmentSessionId) {
      return best;
    }
    const bestTs = best.lastTimestamp ? new Date(best.lastTimestamp).getTime() : 0;
    const currentTs = current.lastTimestamp ? new Date(current.lastTimestamp).getTime() : 0;
    return currentTs >= bestTs ? current : best;
  }, matches[0]);

  const logicalConversationId = representative.logicalConversationId?.trim() || null;
  const representativeSessionId = representative.activeSegmentSessionId?.trim()
    || representative.sessionId
    || normalizedKey;

  return {
    summary: representative,
    logicalConversationId,
    representativeSessionId,
    relatedSessionIds: Array.from(new Set(matches.map((session) => session.sessionId).filter(Boolean))),
  };
}

interface UseSessionManagementOptions {
  messages: ClaudeMessage[];
  loading: boolean;
  historyData: HistoryData | null;
  currentSessionId: string | null;
  logicalConversationId?: string | null;
  currentSessionIdRef?: MutableRefObject<string | null>;
  continuationPendingRef?: MutableRefObject<boolean>;
  setContinuationPending: (pending: boolean) => void;
  setContinuationSourceSessionId: (sessionId: string | null) => void;
  setHistoryData: React.Dispatch<React.SetStateAction<HistoryData | null>>;
  setMessages: React.Dispatch<React.SetStateAction<ClaudeMessage[]>>;
  setCurrentView: (view: ViewMode) => void;
  setCurrentSessionId: (id: string | null) => void;
  setCustomSessionTitle: (title: string | null) => void;
  setUsagePercentage: (percent: number) => void;
  setUsageUsedTokens: (tokens: number | undefined) => void;
  setUsageMaxTokens: (tokens: number | undefined) => void;
  setStatus: (status: string) => void;
  setLoading: (loading: boolean) => void;
  setIsThinking: (thinking: boolean) => void;
  setStreamingActive: (active: boolean) => void;
  clearToasts: () => void;
  addToast: (message: string, type?: ToastType) => void;
  t: TFunction;
}

export interface ContinuedSegmentRequest {
  switchReason: 'provider' | 'model' | 'activeProvider';
  targetProvider: string;
  targetRuntimeFamily: 'claude' | 'codex';
  targetModel: string;
  targetReasoningEffort?: string;
  targetCodexProviderId?: string;
  logicalConversationId?: string;
  sourceSessionId?: string | null;
  activeSegmentSessionId?: string | null;
  continuationSourceSessionId?: string | null;
}

interface UseSessionManagementReturn {
  showNewSessionConfirm: boolean;
  showInterruptConfirm: boolean;
  suppressNextStatusToastRef: React.MutableRefObject<boolean>;
  createNewSession: () => void;
  forceCreateNewSession: () => void;
  forceCreateNewSessionWithProvider: (providerId: string) => void;
  createContinuedSegment: (request: ContinuedSegmentRequest) => void;
  handleConfirmNewSession: () => void;
  handleCancelNewSession: () => void;
  handleConfirmInterrupt: () => void;
  handleCancelInterrupt: () => void;
  loadHistorySession: (sessionId: string, provider?: string) => void;
  deleteHistorySession: (sessionId: string) => void;
  deleteHistorySessions: (sessionIds: string[]) => void;
  exportHistorySession: (sessionId: string, title: string) => void;
  toggleFavoriteSession: (sessionId: string) => void;
  updateHistoryTitle: (sessionId: string, newTitle: string) => void;
  syncCurrentTabTitle: (newTitle: string) => void;
  applyHistoryTitleLocal: (sessionId: string, newTitle: string) => void;
}

/**
 * 负责会话创建、切换、删除、导出与标题同步等会话管理动作。
 * 该 Hook 同时承接当前主线“会话切换保护”和上游新增的“本地历史标题兜底”能力，
 * 以避免历史标题回放、AI 自动标题和窗口 Tab 标题在并轨后再次分叉。
 *
 * @param options 会话管理所需的状态、setter 与国际化能力
 * @return 提供会话管理动作、确认框状态以及标题同步辅助方法
 */
export function useSessionManagement({
  messages,
  loading,
  historyData,
  currentSessionId,
  logicalConversationId = null,
  currentSessionIdRef,
  continuationPendingRef,
  setContinuationPending,
  setContinuationSourceSessionId,
  setHistoryData,
  setMessages,
  setCurrentView,
  setCurrentSessionId,
  setCustomSessionTitle,
  setUsagePercentage,
  setUsageUsedTokens,
  setUsageMaxTokens,
  setStatus,
  setLoading: setLoadingState,
  setIsThinking,
  setStreamingActive,
  clearToasts,
  addToast,
  t,
}: UseSessionManagementOptions): UseSessionManagementReturn {
  const traceCodexRuntime = useCallback((event: string, payload: Record<string, unknown>) => {
    debugLog(`[CODEX_RUNTIME_TRACE][Webview] ${event}`, payload);
  }, []);
  const [showNewSessionConfirm, setShowNewSessionConfirm] = useState(false);
  const [showInterruptConfirm, setShowInterruptConfirm] = useState(false);
  const pendingActionRef = useRef<'newSession' | null>(null);
  const suppressNextStatusToastRef = useRef(false);
  const transitionTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const historyDataRef = useRef(historyData);
  historyDataRef.current = historyData;

  /**
   * 清理前端本地的 continued segment 过渡态与历史前缀缓存。
   * 该方法只用于“独立新会话 / 历史切换 / 删除后重建空会话”这类明确不应继续沿用旧逻辑会话的路径，
   * 目的是避免 stale `continuationPending` 让首条新消息被误拦截为“continued segment 尚未 ready”。
   *
   * @return 无返回值
   */
  const resetContinuedSegmentRuntimeState = useCallback(() => {
    if (continuationPendingRef) {
      continuationPendingRef.current = false;
    }
    setContinuationPending(false);
    setContinuationSourceSessionId(null);
    window.__continuedSegmentFirstSnapshotSessionId = null;
    window.__continuedSegmentHistoryPrefixMessages = null;
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingTailMessages = null;
    window.__continuedSegmentPendingSourceSessionId = null;
    window.__continuedSegmentPendingLogicalConversationId = null;
    window.__continuedSegmentPendingCreatedAt = null;
    window.__continuedSegmentPendingReason = null;
    window.__continuedSegmentAwaitingFirstSessionId = false;
  }, [
    continuationPendingRef,
    setContinuationPending,
    setContinuationSourceSessionId,
  ]);

  /**
   * 统一控制“删除会话成功”提示的立即显示或延迟显示。
   * 当删除的是当前会话时，会话切换会立即发生；此时先挂到 window 上，等切换完成后再展示，
   * 避免 toast 在新旧会话切换过程中被清掉。
   *
   * @param afterSessionTransition 是否在会话切换完成后再展示提示
   * @return 无返回值
   */
  const showSessionDeletedToast = useCallback((afterSessionTransition = false) => {
    const toast = { message: t('history.sessionDeleted'), type: 'success' as const };
    if (afterSessionTransition) {
      window.__pendingSessionTransitionToast = toast;
      return;
    }
    addToast(toast.message, toast.type);
  }, [addToast, t]);

  /**
   * 进入会话切换保护态，统一清理瞬时 UI 状态并设置超时释放。
   * 该保护态用于避免历史回放、新建会话或切 provider 时，旧会话的流式回调继续污染新界面。
   *
   * @param nextSessionId 即将进入的新会话 ID；新建会话场景下可为空
   * @param nextTitle 即将展示的标题；未知时可为空
   * @return 无返回值
   */
  const beginSessionTransition = useCallback((nextSessionId: string | null, nextTitle: string | null) => {
    traceCodexRuntime('beginSessionTransition', {
      previousSessionId: currentSessionId,
      nextSessionId,
      nextTitle,
      loading,
      messageCount: messages.length,
    });
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = createSessionTransitionToken();
    if (typeof window.__resetTransientUiState === 'function') {
      window.__resetTransientUiState();
    } else {
      clearToasts();
      setStatus('');
      setLoadingState(false);
      setIsThinking(false);
      setStreamingActive(false);
    }
    // 中文注释：独立新会话、历史恢复与删除后重建都不应该继承 continued segment 的 pending/source 状态，
    // 否则前端会把当前空白新会话误判成“切段未完成”，导致第一次发送直接弹出 not ready 提示。
    resetContinuedSegmentRuntimeState();
    setMessages([]);
    setCurrentSessionId(nextSessionId);
    if (currentSessionIdRef) {
      currentSessionIdRef.current = nextSessionId;
    }
    setCustomSessionTitle(nextTitle);
    setUsagePercentage(0);
    setUsageUsedTokens(undefined);
    setUsageMaxTokens(undefined);

    if (transitionTimeoutRef.current !== null) {
      clearTimeout(transitionTimeoutRef.current);
    }
    const token = window.__sessionTransitionToken;
    transitionTimeoutRef.current = setTimeout(() => {
      transitionTimeoutRef.current = null;
      if (window.__sessionTransitioning && window.__sessionTransitionToken === token) {
        console.warn('[SessionManagement] Transition guard timed out - auto-releasing');
        window.__sessionTransitioning = false;
        window.__sessionTransitionToken = null;
      }
    }, 15_000);
  }, [
    clearToasts,
    setStatus,
    setLoadingState,
    setIsThinking,
    setStreamingActive,
    setMessages,
    setCurrentSessionId,
    setCustomSessionTitle,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    currentSessionId,
    currentSessionIdRef,
    loading,
    messages.length,
    continuationPendingRef,
    resetContinuedSegmentRuntimeState,
    traceCodexRuntime,
  ]);

  const createNewSession = useCallback(() => {
    if (loading) {
      pendingActionRef.current = 'newSession';
      setShowInterruptConfirm(true);
    } else if (messages.length > 0) {
      pendingActionRef.current = 'newSession';
      setShowNewSessionConfirm(true);
    } else {
      beginSessionTransition(null, null);
      sendBridgeEvent('create_new_session');
    }
  }, [beginSessionTransition, messages.length, loading]);

  const forceCreateNewSession = useCallback(() => {
    traceCodexRuntime('forceCreateNewSession', {
      currentSessionId,
      loading,
      messageCount: messages.length,
    });
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }
    beginSessionTransition(null, null);
    sendBridgeEvent('create_new_session');
  }, [beginSessionTransition, currentSessionId, loading, messages.length, traceCodexRuntime]);

  const forceCreateNewSessionWithProvider = useCallback((providerId: string) => {
    traceCodexRuntime('forceCreateNewSessionWithProvider', {
      currentSessionId,
      loading,
      providerId,
      messageCount: messages.length,
    });
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }
    beginSessionTransition(null, null);
    sendBridgeEvent('set_provider', providerId);
    sendBridgeEvent('create_new_session');
  }, [beginSessionTransition, currentSessionId, loading, messages.length, traceCodexRuntime]);


  /**
   * 初始化显式/静默 continued 共用的 transition cache。
   * 只写入 prefix 与 pending 元数据，不执行 setCurrentSessionId(null)、不清空可见消息列表、
   * 不弹 toast；调用方如需显式切段 UI 过渡，应在本方法之外单独处理。
   *
   * @param params.sourceSessionId 稳定 source 锚点
   * @param params.logicalConversationId 逻辑会话 id
   * @param params.switchReason 切换原因
   * @param params.prefixMessages 需要缓存的旧历史前缀
   * @return 无返回值
   */
  const initializeExplicitContinuedTransitionCache = (params: {
    sourceSessionId: string | null;
    logicalConversationId: string | null;
    switchReason?: string | null;
    prefixMessages: ClaudeMessage[];
  }) => {
    window.__continuedSegmentFirstSnapshotSessionId = null;
    window.__continuedSegmentHistoryPrefixMessages = params.prefixMessages.slice();
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingTailMessages = null;
    window.__continuedSegmentPendingSourceSessionId = params.sourceSessionId?.trim() || null;
    window.__continuedSegmentPendingLogicalConversationId = params.logicalConversationId?.trim() || null;
    window.__continuedSegmentPendingCreatedAt = Date.now();
    window.__continuedSegmentPendingReason = params.switchReason ?? null;
    window.__continuedSegmentAwaitingFirstSessionId = true;
  };

  /**
   * 在当前逻辑会话内创建一个新的继续分段。
   * 该入口用于切模型或切供应商时保留当前消息上下文，只切换后端运行段，
   * 避免继续沿用旧 thread，也避免直接清空前端会话记录。
   *
   * @param request 继续分段所需的目标运行时信息
   * @return 无返回值
   */
  const createContinuedSegment = useCallback((request: ContinuedSegmentRequest) => {
    const resolvedSourceSessionId = request.activeSegmentSessionId?.trim()
      || request.continuationSourceSessionId?.trim()
      || request.sourceSessionId?.trim()
      || currentSessionId;
    const payload = {
      sourceSessionId: resolvedSourceSessionId,
      logicalConversationId: request.logicalConversationId?.trim() || undefined,
      targetProvider: request.targetProvider,
      targetRuntimeFamily: request.targetRuntimeFamily,
      targetModel: request.targetModel,
      targetReasoningEffort: request.targetReasoningEffort,
      targetCodexProviderId: request.targetCodexProviderId,
      switchReason: request.switchReason,
    };

    traceCodexRuntime('createContinuedSegment', payload);
    emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'createContinuedSegment request', {
      ...payload,
      requestSourceSessionId: request.sourceSessionId?.trim() || null,
      activeSegmentSessionId: request.activeSegmentSessionId?.trim() || null,
      currentSessionId,
      continuationSourceSessionId: request.continuationSourceSessionId?.trim() || null,
      resolvedSourceSessionId: resolvedSourceSessionId?.trim() || null,
      transitionToken: window.__sessionTransitionToken ?? null,
    });

    if (!resolvedSourceSessionId?.trim()) {
      // 中文注释：没有稳定 source anchor 时，continued 只会把旧前缀误绑到错误分段。
      // 这里必须在进入任何过渡缓存与 pending 状态之前直接拒绝请求，保留当前会话不变。
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'createContinuedSegment aborted missing source anchor', {
        requestSourceSessionId: request.sourceSessionId?.trim() || null,
        activeSegmentSessionId: request.activeSegmentSessionId?.trim() || null,
        currentSessionId,
        continuationSourceSessionId: request.continuationSourceSessionId?.trim() || null,
        logicalConversationId: request.logicalConversationId?.trim() || logicalConversationId?.trim() || null,
      });
      addToast(t('chat.continuedSegmentAnchorMissing', {
        defaultValue: 'chat.continuedSegmentAnchorMissing',
      }), 'warning');
      return;
    }

    if (loading) {
      sendBridgeEvent('interrupt_session');
    }

    // 中文注释：继续分段只需要重置瞬时运行态，不应像“新建空会话”那样清空历史消息，
    // 否则用户在切模型/切供应商后会立刻丢失当前上下文展示。
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = createSessionTransitionToken();
    emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'createContinuedSegment before transient reset', {
      messageCount: messages.length,
      continuationPending: continuationPendingRef?.current ?? null,
      currentSessionId: currentSessionIdRef?.current ?? currentSessionId,
      hasPrefixCache: Array.isArray(window.__continuedSegmentHistoryPrefixMessages),
      prefixCacheCount: window.__continuedSegmentHistoryPrefixMessages?.length ?? null,
    });
    if (typeof window.__resetTransientUiState === 'function') {
      window.__resetTransientUiState({ preserveContinuedPrefix: true });
    } else {
      clearToasts();
      setStatus('');
      setLoadingState(false);
      setIsThinking(false);
      setStreamingActive(false);
    }
    // 中文注释：continued segment 切换必须在 reset 之后写入前缀缓存。
    // 这里复用与 silent switch 相同的 transition cache 初始化语义：
    // 只缓存 prefix / pending 元数据，不在 helper 内清空可见列表或置空 currentSessionId。
    // 显式切段的 setCurrentSessionId(null) / toast 等待等 UI 动作仍由本方法后续步骤单独处理。
    // 2026-07-16 复盘结论：
    // 1. `__continuedSegmentHistoryPrefixMessages` 仍用于保留旧逻辑会话前缀，等待新分段首帧补齐；
    // 2. `__continuedSegmentPendingTailMessages` 仍用于承接“尾部消息先到、真实 sessionId 后到”的显式 continued 竞态；
    // 3. `__continuedSegmentAwaitingFirstSessionId` 仍用于界定首发放行窗口与 pending tail 采集窗口。
    initializeExplicitContinuedTransitionCache({
      sourceSessionId: resolvedSourceSessionId?.trim() || null,
      logicalConversationId: request.logicalConversationId?.trim() || logicalConversationId?.trim() || null,
      switchReason: request.switchReason,
      prefixMessages: messages.slice(),
    });
    emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'createContinuedSegment after transient reset', {
      messageCount: messages.length,
      continuationPending: continuationPendingRef?.current ?? null,
      currentSessionId: currentSessionIdRef?.current ?? currentSessionId,
      hasPrefixCache: Array.isArray(window.__continuedSegmentHistoryPrefixMessages),
      prefixCacheCount: window.__continuedSegmentHistoryPrefixMessages?.length ?? null,
      pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId,
      pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId,
      awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId,
    });
    setCurrentSessionId(null);
    if (currentSessionIdRef) {
      currentSessionIdRef.current = null;
    }
    // 中文注释：continued segment 在真实 sessionId 回传前必须先在前端本地进入 pending 状态，
    // 否则 transition guard 超时后，消息发送和首帧 shrink 保护都会误判为普通会话。
    setContinuationPending(true);
    if (continuationPendingRef) {
      // 中文注释：同步更新 ref，避免发送回调在下一次 rerender 之前继续读到旧的 pending 状态。
      continuationPendingRef.current = true;
    }
    setContinuationSourceSessionId(resolvedSourceSessionId?.trim() || null);
    setUsagePercentage(0);
    setUsageUsedTokens(undefined);
    setUsageMaxTokens(undefined);

    if (transitionTimeoutRef.current !== null) {
      clearTimeout(transitionTimeoutRef.current);
    }
    const token = window.__sessionTransitionToken;
    transitionTimeoutRef.current = setTimeout(() => {
      transitionTimeoutRef.current = null;
      if (window.__sessionTransitioning && window.__sessionTransitionToken === token) {
        console.warn('[SessionManagement] Continued segment transition guard timed out - auto-releasing');
        window.__sessionTransitioning = false;
        window.__sessionTransitionToken = null;
      }
    }, 15_000);

    sendBridgeEvent('create_continued_segment', JSON.stringify(payload));
  }, [
    clearToasts,
    currentSessionId,
    logicalConversationId,
    loading,
    messages,
    setCurrentSessionId,
    setIsThinking,
    setLoadingState,
    setStatus,
    setStreamingActive,
    setContinuationPending,
    setContinuationSourceSessionId,
    setUsageMaxTokens,
    setUsagePercentage,
    setUsageUsedTokens,
    currentSessionIdRef,
    continuationPendingRef,
    traceCodexRuntime,
  ]);

  const handleConfirmNewSession = useCallback(() => {
    setShowNewSessionConfirm(false);
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }
    beginSessionTransition(null, null);
    sendBridgeEvent('create_new_session');
    pendingActionRef.current = null;
  }, [beginSessionTransition, loading]);

  const handleCancelNewSession = useCallback(() => {
    setShowNewSessionConfirm(false);
    pendingActionRef.current = null;
  }, []);

  const handleConfirmInterrupt = useCallback(() => {
    setShowInterruptConfirm(false);
    sendBridgeEvent('interrupt_session');
    beginSessionTransition(null, null);
    sendBridgeEvent('create_new_session');
    pendingActionRef.current = null;
  }, [beginSessionTransition]);

  const handleCancelInterrupt = useCallback(() => {
    setShowInterruptConfirm(false);
    pendingActionRef.current = null;
  }, []);

  const loadHistorySession = useCallback((sessionId: string, provider?: string) => {
    if (loading) {
      sendBridgeEvent('interrupt_session');
    }

    const target = resolveHistoryTarget(historyDataRef.current, sessionId);
    const session = target.summary;
    const resolvedSessionId = target.representativeSessionId || sessionId;
    beginSessionTransition(resolvedSessionId, session?.title ?? null);
    const transitionToken = window.__sessionTransitionToken ?? null;
    traceCodexRuntime('loadHistorySession', {
      requestedConversationKey: sessionId,
      resolvedSessionId,
      logicalConversationId: target.logicalConversationId,
      activeSegmentSessionId: session?.activeSegmentSessionId || resolvedSessionId,
      provider: provider || session?.provider || 'claude',
      runtimeFamily: session?.runtimeFamily || null,
      restoreSource: 'history_switch',
      transitionToken,
      relatedSessionIds: target.relatedSessionIds,
    });
    sendBridgeEvent(target.logicalConversationId ? 'load_conversation' : 'load_session', JSON.stringify({
      sessionId: resolvedSessionId,
      logicalConversationId: target.logicalConversationId,
      activeSegmentSessionId: session?.activeSegmentSessionId || resolvedSessionId,
      provider: provider || session?.provider || 'claude',
      runtimeFamily: session?.runtimeFamily,
      restoreSource: 'history_switch',
      transitionToken,
    }));
    setCurrentView('chat');
  }, [beginSessionTransition, loading, setCurrentView]);

  const deleteHistorySession = useCallback((sessionId: string) => {
    const target = resolveHistoryTarget(historyDataRef.current, sessionId);
    sendBridgeEvent('delete_session', JSON.stringify({
      sessionId: target.representativeSessionId || sessionId,
      logicalConversationId: target.logicalConversationId,
    }));
    let startedSessionTransition = false;

    if (historyData && historyData.sessions) {
      const deletedConversationKey = sessionId;
      const deletedSessionIds = new Set(target.relatedSessionIds);
      setHistoryData((prevHistoryData) => {
        if (!prevHistoryData?.sessions) {
          return prevHistoryData;
        }

        const deletedSessions = prevHistoryData.sessions.filter((session) => (
          getConversationKey(session) === deletedConversationKey || deletedSessionIds.has(session.sessionId)
        ));
        const deletedMessageCount = deletedSessions.reduce((sum, session) => sum + (session.messageCount || 0), 0);
        return {
          ...prevHistoryData,
          sessions: prevHistoryData.sessions.filter((session) => (
            getConversationKey(session) !== deletedConversationKey && !deletedSessionIds.has(session.sessionId)
          )),
          total: Math.max(0, (prevHistoryData.total || 0) - deletedMessageCount),
        };
      });

      if (currentSessionId && deletedSessionIds.has(currentSessionId)) {
        if (loading) {
          sendBridgeEvent('interrupt_session');
        }
        beginSessionTransition(null, null);
        startedSessionTransition = true;
        suppressNextStatusToastRef.current = true;
        sendBridgeEvent('create_new_session');
      }
    }

    showSessionDeletedToast(startedSessionTransition);
  }, [historyData, currentSessionId, loading, setHistoryData, beginSessionTransition, showSessionDeletedToast]);

  const deleteHistorySessions = useCallback((sessionIds: string[]) => {
    const uniqueSessionIds = Array.from(new Set(sessionIds.filter(Boolean)));
    if (uniqueSessionIds.length === 0) {
      return;
    }

    const targets = uniqueSessionIds.map((sessionId) => resolveHistoryTarget(historyDataRef.current, sessionId));
    sendBridgeEvent('delete_sessions', JSON.stringify(targets.map((target, index) => ({
      sessionId: target.representativeSessionId || uniqueSessionIds[index],
      logicalConversationId: target.logicalConversationId,
    }))));
    let startedSessionTransition = false;

    if (historyData && historyData.sessions) {
      const deletedConversationKeys = new Set(uniqueSessionIds);
      const deletedSessionIds = new Set(targets.flatMap((target) => target.relatedSessionIds));
      setHistoryData((prevHistoryData) => {
        if (!prevHistoryData?.sessions) {
          return prevHistoryData;
        }

        const deletedMessageCount = prevHistoryData.sessions.reduce((sum, session) => (
          deletedConversationKeys.has(getConversationKey(session)) || deletedSessionIds.has(session.sessionId)
            ? sum + (session.messageCount || 0)
            : sum
        ), 0);

        return {
          ...prevHistoryData,
          sessions: prevHistoryData.sessions.filter((session) => (
            !deletedConversationKeys.has(getConversationKey(session)) && !deletedSessionIds.has(session.sessionId)
          )),
          total: Math.max(0, (prevHistoryData.total || 0) - deletedMessageCount),
        };
      });

      if (currentSessionId && deletedSessionIds.has(currentSessionId)) {
        if (loading) {
          sendBridgeEvent('interrupt_session');
        }
        beginSessionTransition(null, null);
        startedSessionTransition = true;
        suppressNextStatusToastRef.current = true;
        sendBridgeEvent('create_new_session');
      }
    }

    showSessionDeletedToast(startedSessionTransition);
  }, [historyData, currentSessionId, loading, setHistoryData, beginSessionTransition, showSessionDeletedToast]);

  const exportHistorySession = useCallback((sessionId: string, title: string) => {
    const target = resolveHistoryTarget(historyDataRef.current, sessionId);
    const exportData = JSON.stringify({
      sessionId: target.representativeSessionId || sessionId,
      logicalConversationId: target.logicalConversationId,
      title,
    });
    sendBridgeEvent('export_session', exportData);
  }, [historyDataRef]);

  const toggleFavoriteSession = useCallback((sessionId: string) => {
    const target = resolveHistoryTarget(historyDataRef.current, sessionId);
    sendBridgeEvent('toggle_favorite', JSON.stringify({
      sessionId: target.representativeSessionId || sessionId,
      logicalConversationId: target.logicalConversationId,
    }));

    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map((session) => {
        if (getConversationKey(session) === sessionId || target.relatedSessionIds.includes(session.sessionId)) {
          const isFavorited = !session.isFavorited;
          return {
            ...session,
            isFavorited,
            favoritedAt: isFavorited ? Date.now() : undefined,
          };
        }
        return session;
      });

      setHistoryData({
        ...historyData,
        sessions: updatedSessions,
      });

      const session = historyData.sessions.find((item) =>
        getConversationKey(item) === sessionId || target.relatedSessionIds.includes(item.sessionId));
      if (session?.isFavorited) {
        addToast(t('history.unfavorited'), 'success');
      } else {
        addToast(t('history.favorited'), 'success');
      }
    }
  }, [historyData, setHistoryData, addToast, t]);

  /**
   * 走后端标题更新接口，同时立即刷新前端历史列表。
   * 该路径适用于用户显式改名或长度受控的标题更新场景。
   *
   * @param sessionId 目标会话 ID
   * @param newTitle 新标题
   * @return 无返回值
   */
  const updateHistoryTitle = useCallback((sessionId: string, newTitle: string) => {
    const target = resolveHistoryTarget(historyDataRef.current, sessionId);
    const updateData = JSON.stringify({
      sessionId: target.representativeSessionId || sessionId,
      logicalConversationId: target.logicalConversationId
        || (currentSessionId === sessionId ? logicalConversationId : null),
      customTitle: newTitle,
    });
    console.warn('[HistoryTitleSync][Frontend] send update_title', updateData);
    sendBridgeEvent('update_title', updateData);

    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map((session) => {
        if (getConversationKey(session) === sessionId || target.relatedSessionIds.includes(session.sessionId)) {
          return {
            ...session,
            title: newTitle,
          };
        }
        return session;
      });

      setHistoryData({
        ...historyData,
        sessions: updatedSessions,
      });

      addToast(t('history.titleUpdated'), 'success');
    }
  }, [historyData, setHistoryData, addToast, t]);

  /**
   * 在当前会话尚未分配真实 sessionId 时，仅同步当前 IDE Tab 标题。
   * 该入口不更新历史列表，也不回写后端标题持久化，只用于“新会话/临时会话”
   * 的标题编辑场景，保证聊天头标题与当前窗口 Tab 标题一致。
   *
   * @param newTitle 需要同步到当前窗口 Tab 的标题
   * @return 无返回值
   */
  const syncCurrentTabTitle = useCallback((newTitle: string) => {
    const payload = JSON.stringify({ title: newTitle });
    sendBridgeEvent('sync_current_tab_title', payload);
  }, []);

  /**
   * 仅在前端本地更新历史列表标题，不走后端 `customTitle` 写回链路。
   * 该入口用于 AI 自动生成标题等可能超过后端长度限制的场景，避免后端拒绝写入后
   * 历史列表显示回退成旧标题。
   *
   * @param sessionId 目标历史会话 ID
   * @param newTitle 需要立即反映到历史列表的标题
   * @return 无返回值
   */
  const applyHistoryTitleLocal = useCallback((sessionId: string, newTitle: string) => {
    if (historyData && historyData.sessions) {
      const target = resolveHistoryTarget(historyData, sessionId);
      const updatedSessions = historyData.sessions.map((session) => (
        getConversationKey(session) === sessionId || target.relatedSessionIds.includes(session.sessionId)
          ? { ...session, title: newTitle }
          : session
      ));
      setHistoryData({
        ...historyData,
        sessions: updatedSessions,
      });
    }
  }, [historyData, setHistoryData]);

  return {
    showNewSessionConfirm,
    showInterruptConfirm,
    suppressNextStatusToastRef,
    createNewSession,
    forceCreateNewSession,
    forceCreateNewSessionWithProvider,
    createContinuedSegment,
    handleConfirmNewSession,
    handleCancelNewSession,
    handleConfirmInterrupt,
    handleCancelInterrupt,
    loadHistorySession,
    deleteHistorySession,
    deleteHistorySessions,
    exportHistorySession,
    toggleFavoriteSession,
    updateHistoryTitle,
    syncCurrentTabTitle,
    applyHistoryTitleLocal,
  };
}
