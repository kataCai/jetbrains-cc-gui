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

  window.setSessionId = (sessionId: string) => {
    const oldId = currentSessionIdRef.current;
    releaseSessionTransition();
    setCurrentSessionId(sessionId);

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
