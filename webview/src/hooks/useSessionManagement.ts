import { useCallback, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeMessage, HistoryData } from '../types';
import { sendBridgeEvent } from '../utils/bridge';
import { debugLog } from '../utils/debug';

type ViewMode = 'chat' | 'history' | 'settings';

type ToastType = 'info' | 'success' | 'warning' | 'error';

const createSessionTransitionToken = () =>
  `transition-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;

interface UseSessionManagementOptions {
  messages: ClaudeMessage[];
  loading: boolean;
  historyData: HistoryData | null;
  currentSessionId: string | null;
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

interface UseSessionManagementReturn {
  showNewSessionConfirm: boolean;
  showInterruptConfirm: boolean;
  suppressNextStatusToastRef: React.MutableRefObject<boolean>;
  createNewSession: () => void;
  forceCreateNewSession: () => void;
  forceCreateNewSessionWithProvider: (providerId: string) => void;
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
    setMessages([]);
    setCurrentSessionId(nextSessionId);
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
    loading,
    messages.length,
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

    const session = historyDataRef.current?.sessions?.find((item) => item.sessionId === sessionId);
    beginSessionTransition(sessionId, session?.title ?? null);
    sendBridgeEvent('load_session', JSON.stringify({
      sessionId,
      provider: provider || session?.provider || 'claude',
    }));
    setCurrentView('chat');
  }, [beginSessionTransition, loading, setCurrentView]);

  const deleteHistorySession = useCallback((sessionId: string) => {
    sendBridgeEvent('delete_session', sessionId);
    let startedSessionTransition = false;

    if (historyData && historyData.sessions) {
      setHistoryData((prevHistoryData) => {
        if (!prevHistoryData?.sessions) {
          return prevHistoryData;
        }

        const deletedSession = prevHistoryData.sessions.find((session) => session.sessionId === sessionId);
        return {
          ...prevHistoryData,
          sessions: prevHistoryData.sessions.filter((session) => session.sessionId !== sessionId),
          total: Math.max(0, (prevHistoryData.total || 0) - (deletedSession?.messageCount || 0)),
        };
      });

      if (sessionId === currentSessionId) {
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

    sendBridgeEvent('delete_sessions', JSON.stringify(uniqueSessionIds));
    let startedSessionTransition = false;

    if (historyData && historyData.sessions) {
      const deletedSessionIds = new Set(uniqueSessionIds);
      setHistoryData((prevHistoryData) => {
        if (!prevHistoryData?.sessions) {
          return prevHistoryData;
        }

        const deletedMessageCount = prevHistoryData.sessions.reduce((sum, session) => (
          deletedSessionIds.has(session.sessionId) ? sum + (session.messageCount || 0) : sum
        ), 0);

        return {
          ...prevHistoryData,
          sessions: prevHistoryData.sessions.filter((session) => !deletedSessionIds.has(session.sessionId)),
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
    const exportData = JSON.stringify({ sessionId, title });
    sendBridgeEvent('export_session', exportData);
  }, []);

  const toggleFavoriteSession = useCallback((sessionId: string) => {
    sendBridgeEvent('toggle_favorite', sessionId);

    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map((session) => {
        if (session.sessionId === sessionId) {
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

      const session = historyData.sessions.find((item) => item.sessionId === sessionId);
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
    const updateData = JSON.stringify({ sessionId, customTitle: newTitle });
    console.warn('[HistoryTitleSync][Frontend] send update_title', updateData);
    sendBridgeEvent('update_title', updateData);

    if (historyData && historyData.sessions) {
      const updatedSessions = historyData.sessions.map((session) => {
        if (session.sessionId === sessionId) {
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
      const updatedSessions = historyData.sessions.map((session) => (
        session.sessionId === sessionId ? { ...session, title: newTitle } : session
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
