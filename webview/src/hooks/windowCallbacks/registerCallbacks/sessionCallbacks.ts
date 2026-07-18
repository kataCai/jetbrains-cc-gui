/**
 * sessionCallbacks.ts
 *
 * Registers window bridge callbacks for session management, SDK dependency status,
 * and rewind result: setSessionId, addToast, onExportSessionData,
 * updateDependencyStatus, onRewindResult.
 */

import type { MutableRefObject } from 'react';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { downloadJSON } from '../../../utils/exportMarkdown';
import { emitFrontendDiagnosticLog } from '../../../utils/debug';
import {
  buildFrontendTranscriptDiagnosticSnapshot,
  buildTranscriptDiagnosticMessageDump,
  FULL_TRANSCRIPT_SNAPSHOT_KIND,
} from '../../../utils/transcriptDiagnostics';
import { releaseSessionTransition } from '../sessionTransition';
import { drainAndRequestDependencyStatus } from '../settingsBootstrap';

// Matches session-titles-service.cjs#updateTitle, which rejects longer titles.
const CUSTOM_TITLE_MAX_LENGTH = 50;

/**
 * 注册会话相关与 SDK 状态相关的前端 bridge 回调。
 * 这里同时承接当前主线的历史标题/Tab 标题修复，以及上游新增的 AI 长标题本地兜底逻辑，
 * 目标是在历史回放、新建会话、AI 自动标题和 SDK 状态刷新之间维持同一套前端契约。
 *
 * @param options useWindowCallbacks 传入的完整状态与回调集合
 * @param tRef 国际化函数引用，避免闭包拿到旧值
 * @return 无返回值
 */
export function registerSessionAndSdkCallbacks(
  options: UseWindowCallbacksOptions,
  tRef: MutableRefObject<UseWindowCallbacksOptions['t']>,
): void {
  const {
    addToast,
    setCurrentSessionId,
    setCustomSessionTitle,
    setLogicalConversationId,
    setActiveSegmentSessionId,
    setParentSegmentSessionId,
    setContinuationPending,
    setContinuationSourceSessionId,
    setSdkStatus,
    setSdkStatusLoaded,
    setIsRewinding,
    setRewindDialogOpen,
    setCurrentRewindRequest,
    customSessionTitleRef,
    currentSessionIdRef,
    updateHistoryTitle,
    applyHistoryTitleLocal,
  } = options;

  /**
   * 导出当前前端完整 transcript 诊断快照。
   * 该入口只服务于问题排查：直接读取 React 当前 message array，并明确标记为完整 transcript，
   * 用来和滚动文本采样日志区分，避免把折叠窗口状态误判成真实消息数组异常。
   *
   * @param payload 可选 JSON 字符串；当前仅用于附带触发原因，非法 JSON 会自动忽略并回退默认行为
   * @return 无返回值
   */
  const exportFrontendTranscriptDiagnosticSnapshot = (payload?: string) => {
    let exportReason: string | null = null;
    if (typeof payload === 'string' && payload.trim().length > 0) {
      try {
        const parsed = JSON.parse(payload) as Record<string, unknown>;
        exportReason = typeof parsed.reason === 'string' && parsed.reason.trim().length > 0
          ? parsed.reason.trim()
          : null;
      } catch {
        exportReason = payload.trim();
      }
    }

    const exportedAt = new Date().toISOString();
    const snapshot = buildFrontendTranscriptDiagnosticSnapshot({
      messages: options.messagesRef.current,
      exportedAt,
      provider: options.currentProviderRef.current || null,
      sessionId: currentSessionIdRef.current,
      logicalConversationId: options.logicalConversationIdRef.current,
      activeSegmentSessionId: options.activeSegmentSessionIdRef.current,
    });
    const filenameSessionPart = (snapshot.logicalConversationId || snapshot.sessionId || 'unsaved-session')
      .replace(/[^a-zA-Z0-9_-]+/g, '-')
      .slice(0, 48) || 'unsaved-session';
    const filename = `frontend-transcript-${filenameSessionPart}-${exportedAt
      .replace(/[:.]/g, '-')
      .replace('T', '_')
      .replace('Z', '_utc')}.json`;

    downloadJSON(JSON.stringify(snapshot, null, 2), filename);
    emitFrontendDiagnosticLog('TranscriptDiagnostics.Frontend', 'export full transcript snapshot', {
      reason: exportReason,
      snapshotKind: FULL_TRANSCRIPT_SNAPSHOT_KIND,
      transcriptSource: snapshot.transcriptSource,
      provider: snapshot.provider,
      sessionId: snapshot.sessionId,
      logicalConversationId: snapshot.logicalConversationId,
      activeSegmentSessionId: snapshot.activeSegmentSessionId,
      messageCount: snapshot.messageCount,
      messageDump: buildTranscriptDiagnosticMessageDump(snapshot.messages),
    });
  };

  /**
   * 同步当前会话 ID 到 React state 与即时读取 ref。
   * `updateMessages` 与发送链路会直接读取 ref，因此这里必须与 state 一起更新。
   *
   * @param sessionId 当前真实 sessionId；为空表示尚未绑定
   */
  const applyCurrentSessionId = (sessionId: string | null) => {
    setCurrentSessionId(sessionId);
    currentSessionIdRef.current = sessionId;
  };

  /**
   * 同步逻辑会话 ID 到 React state 与即时读取 ref。
   * continued completion 可能早于下一轮 React render 到达，因此这里必须直接写 ref，
   * 避免下一次 createContinuedSegment 仍读取到旧的空 logical id。
   *
   * @param logicalConversationId 后端确认的逻辑会话 ID；为空表示不更新
   * @return 无返回值
   */
  const applyLogicalConversationId = (logicalConversationId: string | null) => {
    if (!logicalConversationId) {
      return;
    }
    setLogicalConversationId(logicalConversationId);
    options.logicalConversationIdRef.current = logicalConversationId;
    window.__continuedSegmentPendingLogicalConversationId = logicalConversationId;
  };

  /**
   * 同步当前活动物理分段 ID 到 React state 与 ref。
   * 该值是下一次 continued 切段的 source anchor 之一，不能只依赖异步 state 更新。
   *
   * @param activeSegmentSessionId 后端确认的活动分段 ID；为空表示不更新
   * @return 无返回值
   */
  const applyActiveSegmentSessionId = (activeSegmentSessionId: string | null) => {
    if (!activeSegmentSessionId) {
      return;
    }
    setActiveSegmentSessionId(activeSegmentSessionId);
    options.activeSegmentSessionIdRef.current = activeSegmentSessionId;
  };

  /**
   * 同步 continued pending 状态到 React state 与 ref。
   * 这样后端在 `setSessionId -> updateMessages` 同一轮回推里，首帧即可读到最新状态。
   *
   * @param pending 是否仍处于 continued 过渡态
   */
  const applyContinuationPending = (pending: boolean) => {
    setContinuationPending(pending);
    options.continuationPendingRef.current = pending;
  };

  /**
   * 清理 continued 过渡期间的缓存。
   * 失败回滚后如果保留这些缓存，后续发送仍可能被误判成“continued 尚未完成”。
   */
  const clearContinuedTransitionCaches = () => {
    window.__continuedSegmentFirstSnapshotSessionId = null;
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentHistoryPrefixMessages = null;
    window.__continuedSegmentPendingTailMessages = null;
    window.__continuedSegmentPendingSourceSessionId = null;
    window.__continuedSegmentPendingLogicalConversationId = null;
    window.__continuedSegmentPendingCreatedAt = null;
    window.__continuedSegmentPendingReason = null;
    window.__continuedSegmentAwaitingFirstSessionId = false;
  };

  type ContinuedCompletionPayload = {
    sessionId: string | null;
    logicalConversationId: string | null;
    activeSegmentSessionId: string | null;
    sourceSessionId: string | null;
    parentSegmentSessionId: string | null;
  };

  /**
   * 解析 continued completion 载荷。
   * 后端新版会传 JSON 字符串，旧版仍可能只传 sessionId；这里保持向后兼容，
   * 并把空白字符串统一归一化为 null，避免后续分支重复做 trim 判定。
   *
   * @param payload 后端 bridge 传入的 completion 参数
   * @return 归一化后的 continued completion 元数据
   */
  const parseContinuedCompletionPayload = (payload?: string): ContinuedCompletionPayload => {
    const normalize = (value: unknown): string | null => (
      typeof value === 'string' && value.trim().length > 0 ? value.trim() : null
    );
    const fallbackSessionId = normalize(payload);
    if (!fallbackSessionId) {
      return {
        sessionId: null,
        logicalConversationId: null,
        activeSegmentSessionId: null,
        sourceSessionId: null,
        parentSegmentSessionId: null,
      };
    }

    try {
      const parsed = JSON.parse(fallbackSessionId) as Record<string, unknown>;
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return {
          sessionId: fallbackSessionId,
          logicalConversationId: null,
          activeSegmentSessionId: null,
          sourceSessionId: null,
          parentSegmentSessionId: null,
        };
      }
      const sessionId = normalize(parsed.sessionId);
      const sourceSessionId = normalize(parsed.sourceSessionId);
      return {
        sessionId,
        logicalConversationId: normalize(parsed.logicalConversationId),
        activeSegmentSessionId: normalize(parsed.activeSegmentSessionId) || sessionId,
        sourceSessionId,
        parentSegmentSessionId: normalize(parsed.parentSegmentSessionId) || sourceSessionId,
      };
    } catch {
      return {
        sessionId: fallbackSessionId,
        logicalConversationId: null,
        activeSegmentSessionId: null,
        sourceSessionId: null,
        parentSegmentSessionId: null,
      };
    }
  };

  /**
   * 显式收口 continued segment 的前端运行态。
   * 这里既支持后端在 setSessionId 之后追加生命周期完成信号，
   * 也兼容信号早于 React 挂载时先落到 placeholder 的场景。
   *
   * @param sessionId 若已知真实 sessionId，则同步刷新当前 session 与活动分段锚点
   */
  const completeContinuedSegmentTransition = (payload?: string) => {
    const completion = parseContinuedCompletionPayload(payload);
    const normalizedSessionId = completion.sessionId;
    releaseSessionTransition();
    applyLogicalConversationId(completion.logicalConversationId);
    if (completion.parentSegmentSessionId) {
      setParentSegmentSessionId(completion.parentSegmentSessionId);
    }
    if (normalizedSessionId) {
      applyCurrentSessionId(normalizedSessionId);
      applyActiveSegmentSessionId(completion.activeSegmentSessionId || normalizedSessionId);
      window.__continuedSegmentFirstSnapshotSessionId = normalizedSessionId;
      window.__continuedSegmentHistoryPrefixSessionId = normalizedSessionId;
      window.__continuedSegmentAwaitingFirstSessionId = false;
      if (!Array.isArray(window.__continuedSegmentHistoryPrefixMessages)
        && (window.__continuedSegmentPendingSourceSessionId || window.__continuedSegmentPendingLogicalConversationId)) {
        emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'window.completeContinuedSegmentTransition missing prefix cache', {
          sessionId: normalizedSessionId,
          pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
          pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
          awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
        });
      }
    }
    // 中文注释：continued 的 pending/source 只应该表示“当前仍在等待真实分段落地”。
    // 后端一旦显式确认收口完成，这里必须幂等清理，避免首帧快照时序再次污染发送门禁。
    applyContinuationPending(false);
    setContinuationSourceSessionId(null);
    emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'window.completeContinuedSegmentTransition applied', {
      sessionId: normalizedSessionId,
      activeSegmentSessionId: completion.activeSegmentSessionId,
      sourceSessionId: completion.sourceSessionId,
      logicalConversationId: options.logicalConversationIdRef.current,
      transitionToken: window.__sessionTransitionToken ?? null,
      hasPrefixCache: Array.isArray(window.__continuedSegmentHistoryPrefixMessages),
      prefixCacheCount: window.__continuedSegmentHistoryPrefixMessages?.length ?? null,
      pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
      pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
      awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
    });
  };

  /**
   * 在 continued 创建失败时回滚前端运行态到旧会话。
   * 这里必须同步恢复 session 锚点并清理过渡缓存，避免后续发送继续命中“continued 尚未就绪”。
   *
   * @param sessionId 失败后需要恢复的旧 sessionId；为空时仅清理 continued 状态
   */
  const abortContinuedSegmentTransition = (sessionId?: string) => {
    const normalizedSessionId = typeof sessionId === 'string' && sessionId.trim().length > 0
      ? sessionId.trim()
      : null;
    releaseSessionTransition();
    if (normalizedSessionId) {
      applyCurrentSessionId(normalizedSessionId);
      setActiveSegmentSessionId(normalizedSessionId);
    }
    applyContinuationPending(false);
    setContinuationSourceSessionId(null);
    clearContinuedTransitionCaches();
    emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'window.abortContinuedSegmentTransition applied', {
      snapshotStage: 'degraded',
      restoredSessionId: normalizedSessionId,
      logicalConversationId: options.logicalConversationIdRef.current,
      transitionToken: window.__sessionTransitionToken ?? null,
    });
  };

  /**
   * 判断当前是否存在 createContinuedSegment 写入的稳定 transition cache。
   * 该 cache 是 React refs 丢失时的兜底依据；只要它存在且 prefix cache 非空，
   * setSessionId 回推就不能被当成普通会话处理。
   *
   * @return true 表示存在仍待首个真实 sessionId 收口的 continued transition 元数据
   */
  const hasContinuedTransitionCache = (): boolean => !!(
    window.__continuedSegmentAwaitingFirstSessionId
    || window.__continuedSegmentPendingSourceSessionId?.trim()
  );

  /**
   * 判断当前 continued prefix cache 是否可以绑定到本次回推的 sessionId。
   *
   * @param sessionId 后端回推的真实 sessionId
   * @return true 表示 prefix cache 未绑定或已经绑定到同一 sessionId
   */
  const canBindPrefixCacheToSession = (sessionId: string): boolean => {
    const prefixSessionId = window.__continuedSegmentHistoryPrefixSessionId?.trim() || null;
    return prefixSessionId === null || prefixSessionId === sessionId;
  };

  /**
   * 判断 `setSessionId` 是否应按 continued segment 首帧处理。
   * 主路径优先使用 `continuationPendingRef`；若该 ref 因时序问题提前回落，
   * 则在“旧 session 为空、存在 continued prefix cache、存在 transition cache 元数据”时兜底进入 continued 分支。
   *
   * @param sessionId 后端回推的真实 sessionId
   * @param oldSessionId setSessionId 前端当前持有的 sessionId
   * @return true 表示当前 sessionId 回推属于 continued segment 新物理分段
   */
  const shouldHandleAsContinuedSegment = (sessionId: string, oldSessionId: string | null): boolean => {
    if (options.continuationPendingRef.current) {
      return true;
    }
    const hasPrefixCache = Array.isArray(window.__continuedSegmentHistoryPrefixMessages)
      && window.__continuedSegmentHistoryPrefixMessages.length > 0;
    const hasTransitionCache = hasContinuedTransitionCache();
    const hasSourceAnchor = !!(
      window.__continuedSegmentPendingSourceSessionId?.trim()
      || options.activeSegmentSessionIdRef.current?.trim()
    );
    if (hasTransitionCache) {
      return oldSessionId === null && hasPrefixCache && hasSourceAnchor && canBindPrefixCacheToSession(sessionId);
    }
    const hasContinuationContext = !!(
      options.logicalConversationIdRef.current?.trim()
      || options.activeSegmentSessionIdRef.current?.trim()
    );
    return oldSessionId === null && hasPrefixCache && hasContinuationContext && canBindPrefixCacheToSession(sessionId);
  };

  window.setSessionId = (sessionId: string) => {
    const oldId = currentSessionIdRef.current;
    const continuationPendingBeforeApply = options.continuationPendingRef.current;
    const handleAsContinuedSegment = shouldHandleAsContinuedSegment(sessionId, oldId);
    const hasTransitionCache = hasContinuedTransitionCache();
    const hasPrefixCache = Array.isArray(window.__continuedSegmentHistoryPrefixMessages);
    const prefixCacheCount = window.__continuedSegmentHistoryPrefixMessages?.length ?? null;
    const hasSourceAnchor = !!(
      window.__continuedSegmentPendingSourceSessionId?.trim()
      || options.activeSegmentSessionIdRef.current?.trim()
    );
    const hasSuspiciousContinuedPrefixCache = hasPrefixCache
      && (prefixCacheCount ?? 0) > 0
      && hasTransitionCache
      && hasSourceAnchor
      && canBindPrefixCacheToSession(sessionId);
    emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'window.setSessionId received', {
      sessionId,
      oldSessionId: oldId,
      continuationPending: continuationPendingBeforeApply,
      continuedFallbackMatched: handleAsContinuedSegment && !continuationPendingBeforeApply,
      hasPrefixCache,
      prefixCacheCount,
      pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
      pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
      awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
      activeSegmentSessionId: options.activeSegmentSessionIdRef.current,
      logicalConversationId: options.logicalConversationIdRef.current,
      transitionToken: window.__sessionTransitionToken ?? null,
    });
    releaseSessionTransition();
    applyCurrentSessionId(sessionId);
    if (handleAsContinuedSegment) {
      setActiveSegmentSessionId(sessionId);
      // 中文注释：真实 sessionId 一旦回传，continued segment 的前端门禁就不应继续停留在 pending，
      // 否则用户切完模型后第一次发送仍会被误判成“切段未完成”，只能靠重复点击才能继续会话。
      applyContinuationPending(false);
      setContinuationSourceSessionId(null);
      window.__continuedSegmentFirstSnapshotSessionId = sessionId;
      window.__continuedSegmentHistoryPrefixSessionId = sessionId;
      window.__continuedSegmentAwaitingFirstSessionId = false;
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'window.setSessionId marked continued segment first snapshot', {
        sessionId,
        fallbackMatched: !continuationPendingBeforeApply,
        fallbackBindingSource: continuationPendingBeforeApply
          ? 'continuation_pending'
          : hasTransitionCache
            ? 'transition_cache'
            : 'prefix_cache_context',
        logicalConversationId: options.logicalConversationIdRef.current,
        transitionToken: window.__sessionTransitionToken ?? null,
        pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
        pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
        prefixCacheCount: window.__continuedSegmentHistoryPrefixMessages?.length ?? null,
      });
    } else {
      emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'window.setSessionId used ordinary session branch', {
        sessionId,
        oldSessionId: oldId,
        continuationPending: continuationPendingBeforeApply,
        hasPrefixCache,
        prefixCacheCount,
        preservedSuspiciousContinuedPrefixCache: hasSuspiciousContinuedPrefixCache,
        pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
        pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
        awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
      });
      if (!hasSuspiciousContinuedPrefixCache) {
        emitFrontendDiagnosticLog('CodexRuntime.Frontend', 'continued transition cache cleared', {
          cleanupReason: 'ordinary_session_branch_without_continued_context',
          prefixCacheCount,
          prefixSessionId: window.__continuedSegmentHistoryPrefixSessionId ?? null,
          pendingTailCount: Array.isArray(window.__continuedSegmentPendingTailMessages)
            ? window.__continuedSegmentPendingTailMessages.length
            : null,
          firstSnapshotSessionId: window.__continuedSegmentFirstSnapshotSessionId ?? null,
          pendingSourceSessionId: window.__continuedSegmentPendingSourceSessionId ?? null,
          pendingLogicalConversationId: window.__continuedSegmentPendingLogicalConversationId ?? null,
          awaitingFirstSessionId: window.__continuedSegmentAwaitingFirstSessionId ?? false,
        });
        window.__continuedSegmentFirstSnapshotSessionId = null;
        window.__continuedSegmentHistoryPrefixSessionId = null;
        window.__continuedSegmentHistoryPrefixMessages = null;
        window.__continuedSegmentPendingTailMessages = null;
        window.__continuedSegmentPendingSourceSessionId = null;
        window.__continuedSegmentPendingLogicalConversationId = null;
        window.__continuedSegmentPendingCreatedAt = null;
        window.__continuedSegmentPendingReason = null;
        window.__continuedSegmentAwaitingFirstSessionId = false;
      }
    }

    // B-011 + B-014: Persist custom title under the real SDK session ID.
    // NOTE: We intentionally do NOT delete the old ID's title to prevent
    // data loss when Codex creates new threads for continued conversations.
    // Orphaned title entries are harmless and cleaned up on session deletion.
    const title = customSessionTitleRef.current;
    if (title && oldId !== sessionId) {
      // AI-generated titles can exceed the backend limit. Fall back to
      // local-only update so the UI keeps the title visible without a
      // silent backend write failure.
      if (title.length <= CUSTOM_TITLE_MAX_LENGTH) {
        updateHistoryTitle(sessionId, title);
      } else {
        applyHistoryTitleLocal(sessionId, title);
      }
    }
  };

  window.completeContinuedSegmentTransition = (payload?: string) => {
    completeContinuedSegmentTransition(payload);
  };

  window.abortContinuedSegmentTransition = (sessionId?: string) => {
    abortContinuedSegmentTransition(sessionId);
  };

  const pendingContinuedTransitionPayload = window.__pendingCompleteContinuedSegmentTransitionPayload
    ?? window.__pendingCompleteContinuedSegmentTransitionSessionId;
  if (typeof pendingContinuedTransitionPayload === 'string') {
    delete window.__pendingCompleteContinuedSegmentTransitionPayload;
    delete window.__pendingCompleteContinuedSegmentTransitionSessionId;
    window.completeContinuedSegmentTransition(pendingContinuedTransitionPayload || undefined);
  }

  const pendingAbortContinuedTransitionSessionId = window.__pendingAbortContinuedSegmentTransitionSessionId;
  if (typeof pendingAbortContinuedTransitionSessionId === 'string') {
    delete window.__pendingAbortContinuedSegmentTransitionSessionId;
    window.abortContinuedSegmentTransition(pendingAbortContinuedTransitionSessionId || undefined);
  }

  window.addToast = (message, type) => {
    addToast(message, type as 'info' | 'success' | 'warning' | 'error' | undefined);
  };

  window.onExportSessionData = (json) => {
    try {
      const data = JSON.parse(json);
      if (data.sessionId && data.messages) {
        const exportContent = JSON.stringify(data, null, 2);
        const sanitizedTitle = (data.title || 'session')
          .replace(/[<>:"/\\|?*]/g, '_')
          .replace(/\s+/g, '_')
          .substring(0, 50);
        const filename = `${sanitizedTitle}_${data.sessionId.substring(0, 8)}.json`;
        downloadJSON(exportContent, filename);
      } else if (data.error) {
        addToast(data.error, 'error');
      } else {
        addToast(tRef.current('history.exportFailed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to process export data:', error);
      addToast(tRef.current('history.exportFailed'), 'error');
    }
  };

  window.exportFrontendTranscriptDiagnosticSnapshot = (json?: string) => {
    exportFrontendTranscriptDiagnosticSnapshot(json);
  };

  // =========================================================================
  // SDK Status Callbacks
  // =========================================================================

  const originalUpdateDependencyStatus = window.updateDependencyStatus;
  window.updateDependencyStatus = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setSdkStatus(data);
      setSdkStatusLoaded(true);
    } catch (error) {
      console.error('[Frontend] Failed to parse dependency status:', error);
    }
    if (
      originalUpdateDependencyStatus &&
      originalUpdateDependencyStatus !== window.updateDependencyStatus
    ) {
      originalUpdateDependencyStatus(jsonStr);
    }
  };
  (window as unknown as Record<string, unknown>)._appUpdateDependencyStatus =
    window.updateDependencyStatus;

  drainAndRequestDependencyStatus();

  // =========================================================================
  // Rewind Result Callback
  // =========================================================================

  window.onRewindResult = (json: string) => {
    try {
      const result = JSON.parse(json);
      setIsRewinding(false);
      if (result.success) {
        setRewindDialogOpen(false);
        setCurrentRewindRequest(null);
        window.addToast?.(tRef.current('rewind.success'), 'success');
      } else {
        window.addToast?.(result.message || tRef.current('rewind.failed'), 'error');
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse rewind result:', error);
      setIsRewinding(false);
      setRewindDialogOpen(false);
      setCurrentRewindRequest(null);
      window.addToast?.(tRef.current('rewind.parseError'), 'error');
    }
  };

  // =========================================================================
  // AI Title Callback
  // =========================================================================

  /**
   * 统一兼容历史标题回放的两种前端调用签名。
   * 1. 旧链路：`updateSessionTitle(title)`，仅恢复当前前端标题状态，不写历史列表。
   * 2. 新链路：`updateSessionTitle(sessionId, title)`，要求 sessionId 与当前会话匹配后，
   *    再同步会话标题与历史列表；若标题过长，则走本地历史列表兜底，避免后端拒绝写入。
   *
   * @param sessionIdOrTitle 旧签名中的标题，或新签名中的 sessionId
   * @param maybeTitle 新签名中的标题；旧签名场景下为空
   * @return 无返回值
   */
  window.updateSessionTitle = (sessionIdOrTitle: string, maybeTitle?: string) => {
    const hasExplicitSessionId = typeof maybeTitle === 'string';
    const normalizedTitle = (hasExplicitSessionId ? maybeTitle : sessionIdOrTitle)?.trim();

    if (!normalizedTitle) {
      return;
    }

    if (!hasExplicitSessionId) {
      // 兼容旧的一参回放链路：仅恢复当前前端标题状态，不触发历史标题持久化写回。
      setCustomSessionTitle(normalizedTitle);
      return;
    }

    const normalizedSessionId = sessionIdOrTitle?.trim();
    if (!normalizedSessionId) {
      return;
    }
    if (currentSessionIdRef.current !== normalizedSessionId) {
      return;
    }

    setCustomSessionTitle(normalizedTitle);
    if (normalizedTitle.length <= CUSTOM_TITLE_MAX_LENGTH) {
      updateHistoryTitle(normalizedSessionId, normalizedTitle);
    } else {
      applyHistoryTitleLocal(normalizedSessionId, normalizedTitle);
    }
  };
}
