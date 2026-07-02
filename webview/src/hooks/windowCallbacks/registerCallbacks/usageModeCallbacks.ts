import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode, ReasoningEffort } from '../../../components/ChatInputBox/types';
import { isValidPermissionMode, normalizeClaudeModelId } from '../../../components/ChatInputBox/types';
import { buildCodexSelectedModelKey } from '../../../types/provider';
import { clampPermissionDialogTimeoutSeconds } from '../../../utils/permissionDialogTimeout';
import { debugLog } from '../../../utils/debug';
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';

/**
 * 注册使用量、权限模式、模型与基础设置相关的 window bridge 回调。
 *
 * 该函数是后端向 WebView 同步运行态设置的入口。并轨时必须同时保留
 * 当前主线的 Codex 模型状态同步、provider 映射同步，以及上游新增的
 * 权限弹窗超时时间同步，避免设置页和运行态出现不一致。
 *
 * @param options 注册回调所需的状态 setter、当前 provider 引用和模型同步工具。
 */
export function registerUsageModeCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setLogicalConversationId,
    setActiveSegmentSessionId,
    setParentSegmentSessionId,
    setContinuationPending,
    setContinuationSourceSessionId,
    setCurrentProvider,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedCodexSelectionKey,
    setActiveCodexProviderId,
    setDefaultCodexModelFromConfig,
    setCodexBaseUrl,
    setCodexUsesCustomBaseUrl,
    setReasoningEffort,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled,
    setStreamingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setPermissionDialogTimeoutSeconds,
    currentProviderRef,
    activeCodexProviderIdRef,
    shouldAdoptCodexDefaultModelRef,
    shouldAdoptCodexDefaultReasoningEffortRef,
    syncActiveProviderModelMapping,
  } = options;
  const traceCodexRuntime = (event: string, payload: Record<string, unknown>) => {
    debugLog(`[CODEX_RUNTIME_TRACE][Webview] ${event}`, payload);
  };

  window.onUsageUpdate = (json) => {
    try {
      const data = JSON.parse(json);
      if (typeof data.percentage !== 'number') {
        return;
      }

      const used =
        typeof data.usedTokens === 'number'
          ? data.usedTokens
          : typeof data.totalTokens === 'number'
            ? data.totalTokens
            : undefined;
      const max =
        typeof data.maxTokens === 'number'
          ? data.maxTokens
          : typeof data.limit === 'number'
            ? data.limit
            : undefined;

      if (used !== undefined && max !== undefined && used > max * 2) {
        console.warn('[Frontend] Usage data may be incorrect: used=' + used + ', max=' + max);
      }

      setUsagePercentage(Math.max(0, Math.min(100, data.percentage)));
      setUsageUsedTokens(used);
      setUsageMaxTokens(max);
    } catch (error) {
      console.error('[Frontend] Failed to parse usage update:', error);
    }
  };

  /**
   * 同步 provider 维度的权限模式。
   *
   * Codex 不支持 plan 模式，因此后端误传 plan 时在前端降级为 default。
   *
   * @param mode 后端同步的权限模式。
   * @param providerOverride 可选的 provider 覆盖值，用于处理 provider 切换期间的回调。
   */
  const updateMode = (mode?: PermissionMode, providerOverride?: string) => {
    const activeProvider = providerOverride || currentProviderRef.current;
    if (!isValidPermissionMode(mode)) {
      return;
    }

    const nextMode: PermissionMode = activeProvider === 'codex' && mode === 'plan' ? 'default' : mode;
    setPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
    if (activeProvider === 'codex') {
      setCodexPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
    } else {
      setClaudePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
    }
  };

  window.onModeChanged = (mode) => updateMode(mode as PermissionMode);
  window.onModeReceived = (mode) => updateMode(mode as PermissionMode);

  window.onModelChanged = (modelId) => {
    const provider = currentProviderRef.current;
    if (provider === 'claude') {
      setSelectedClaudeModel(normalizeClaudeModelId(modelId));
    } else if (provider === 'codex') {
      setSelectedCodexModel(modelId);
      setSelectedCodexSelectionKey(buildCodexSelectedModelKey(activeCodexProviderIdRef.current, modelId));
    }
  };

  window.onModelConfirmed = (modelId, provider) => {
    if (provider === 'claude') {
      setSelectedClaudeModel(normalizeClaudeModelId(modelId));
    } else if (provider === 'codex') {
      setSelectedCodexModel(modelId);
      setSelectedCodexSelectionKey(buildCodexSelectedModelKey(activeCodexProviderIdRef.current, modelId));
    }
  };

  window.restoreTabRuntimeState = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr) as {
        provider?: string;
        model?: string;
        permissionMode?: PermissionMode;
        reasoningEffort?: ReasoningEffort;
        codexProviderId?: string;
        logicalConversationId?: string;
        latestSessionId?: string;
        activeSegmentSessionId?: string;
        parentSegmentSessionId?: string;
        continuationPending?: boolean;
        continuationSourceSessionId?: string;
      };
      traceCodexRuntime('restoreTabRuntimeState', {
        provider: data.provider ?? null,
        model: data.model ?? null,
        permissionMode: data.permissionMode ?? null,
        reasoningEffort: data.reasoningEffort ?? null,
        codexProviderId: data.codexProviderId ?? null,
        logicalConversationId: data.logicalConversationId ?? null,
        activeSegmentSessionId: data.activeSegmentSessionId ?? data.latestSessionId ?? null,
        parentSegmentSessionId: data.parentSegmentSessionId ?? null,
        continuationPending: data.continuationPending === true,
        continuationSourceSessionId: data.continuationSourceSessionId ?? null,
      });

      const nextProvider = data.provider === 'codex' ? 'codex' : 'claude';
      setCurrentProvider(nextProvider);
      currentProviderRef.current = nextProvider;

      if (nextProvider === 'claude' && typeof data.model === 'string' && data.model.trim().length > 0) {
        setSelectedClaudeModel(normalizeClaudeModelId(data.model));
      }

      if (nextProvider === 'codex' && typeof data.model === 'string' && data.model.trim().length > 0) {
        const normalizedModel = data.model.trim();
        setSelectedCodexModel(normalizedModel);
        // 恢复标签页快照时同步重建复合 key，避免前端退化成仅凭 modelId 判断勾选状态。
        setSelectedCodexSelectionKey(buildCodexSelectedModelKey(data.codexProviderId, normalizedModel));
        shouldAdoptCodexDefaultModelRef.current = false;
      }

      if (typeof data.codexProviderId === 'string') {
        const normalizedProviderId = data.codexProviderId.trim();
        activeCodexProviderIdRef.current = normalizedProviderId;
        setActiveCodexProviderId(normalizedProviderId);
      }

      // 中文注释：Tab 恢复阶段需要把逻辑会话与 continued segment 运行态一并恢复，
      // 否则切换模型/供应商后的“继续会话”信息只停留在后端快照里，前端后续动作无法感知。
      setLogicalConversationId(
        typeof data.logicalConversationId === 'string' && data.logicalConversationId.trim().length > 0
          ? data.logicalConversationId.trim()
          : null,
      );
      const restoredActiveSegmentSessionId =
        typeof data.activeSegmentSessionId === 'string' && data.activeSegmentSessionId.trim().length > 0
          ? data.activeSegmentSessionId.trim()
          : typeof data.latestSessionId === 'string' && data.latestSessionId.trim().length > 0
            ? data.latestSessionId.trim()
            : null;
      // 中文注释：旧 Tab 快照可能只保留逻辑会话主键而缺失活动分段，后端补发 latestSessionId 时这里兜底到最新分段，
      // 避免恢复后继续发送又落回旧物理分段或空分段。
      setActiveSegmentSessionId(restoredActiveSegmentSessionId);
      setParentSegmentSessionId(
        typeof data.parentSegmentSessionId === 'string' && data.parentSegmentSessionId.trim().length > 0
          ? data.parentSegmentSessionId.trim()
          : null,
      );
      setContinuationPending(data.continuationPending === true);
      setContinuationSourceSessionId(
        typeof data.continuationSourceSessionId === 'string' && data.continuationSourceSessionId.trim().length > 0
          ? data.continuationSourceSessionId.trim()
          : null,
      );

      updateMode(data.permissionMode, nextProvider);

      if (
        data.reasoningEffort === 'low'
        || data.reasoningEffort === 'medium'
        || data.reasoningEffort === 'high'
        || data.reasoningEffort === 'xhigh'
      ) {
        setReasoningEffort(data.reasoningEffort);
        shouldAdoptCodexDefaultReasoningEffortRef.current = false;
      }
    } catch (error) {
      console.error('[Frontend] Failed to restore tab runtime state:', error);
    }
  };

  if (window.__pendingTabRuntimeState) {
    const pending = window.__pendingTabRuntimeState;
    delete window.__pendingTabRuntimeState;
    window.restoreTabRuntimeState(pending);
  }

  window.applyNewTabDefaults = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr) as {
        provider?: string;
        model?: string;
        permissionMode?: PermissionMode;
        reasoningEffort?: ReasoningEffort;
        codexProviderId?: string;
        modelSource?: string;
        reasoningSource?: string;
      };
      traceCodexRuntime('applyNewTabDefaults', {
        provider: data.provider ?? null,
        model: data.model ?? null,
        permissionMode: data.permissionMode ?? null,
        reasoningEffort: data.reasoningEffort ?? null,
        codexProviderId: data.codexProviderId ?? null,
        modelSource: data.modelSource ?? null,
        reasoningSource: data.reasoningSource ?? null,
      });

      const nextProvider = data.provider === 'codex' ? 'codex' : 'claude';
      setCurrentProvider(nextProvider);
      currentProviderRef.current = nextProvider;

      if (nextProvider === 'codex' && typeof data.model === 'string' && data.model.trim().length > 0) {
        const normalizedModel = data.model.trim();
        setSelectedCodexModel(normalizedModel);
        setSelectedCodexSelectionKey(buildCodexSelectedModelKey(data.codexProviderId, normalizedModel));
        if (data.modelSource === 'remembered_model') {
          shouldAdoptCodexDefaultModelRef.current = false;
        }
      }

      if (typeof data.codexProviderId === 'string') {
        const normalizedProviderId = data.codexProviderId.trim();
        activeCodexProviderIdRef.current = normalizedProviderId;
        setActiveCodexProviderId(normalizedProviderId);
      }

      updateMode(data.permissionMode, nextProvider);

      if (
        data.reasoningEffort === 'low'
        || data.reasoningEffort === 'medium'
        || data.reasoningEffort === 'high'
        || data.reasoningEffort === 'xhigh'
      ) {
        setReasoningEffort(data.reasoningEffort);
        if (data.reasoningSource === 'remembered_reasoning') {
          shouldAdoptCodexDefaultReasoningEffortRef.current = false;
        }
      }
    } catch (error) {
      console.error('[Frontend] Failed to apply fresh new tab defaults:', error);
    }
  };

  if (window.__pendingNewTabDefaults) {
    const pending = window.__pendingNewTabDefaults;
    delete window.__pendingNewTabDefaults;
    window.applyNewTabDefaults(pending);
  }

  window.updateCodexModelState = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr) as {
        model?: string;
        reasoningEffort?: ReasoningEffort;
        baseUrl?: string;
        usesCustomBaseUrl?: boolean;
      };

      if (typeof data.model === 'string' && data.model.trim().length > 0) {
        const normalizedModel = data.model.trim();
        setDefaultCodexModelFromConfig(normalizedModel);
        if (shouldAdoptCodexDefaultModelRef.current) {
          setSelectedCodexModel(normalizedModel);
          setSelectedCodexSelectionKey(buildCodexSelectedModelKey(activeCodexProviderIdRef.current, normalizedModel));
        }
      }

      if (
        data.reasoningEffort === 'low' ||
        data.reasoningEffort === 'medium' ||
        data.reasoningEffort === 'high' ||
        data.reasoningEffort === 'xhigh'
      ) {
        if (shouldAdoptCodexDefaultReasoningEffortRef.current) {
          setReasoningEffort(data.reasoningEffort);
        }
      }

      setCodexBaseUrl(
        typeof data.baseUrl === 'string' && data.baseUrl.trim().length > 0
          ? data.baseUrl.trim()
          : null,
      );
      setCodexUsesCustomBaseUrl(data.usesCustomBaseUrl === true);
    } catch (error) {
      console.error('[Frontend] Failed to parse Codex model state:', error);
    }
  };

  window.updateActiveProvider = (jsonStr: string) => {
    try {
      const provider = JSON.parse(jsonStr);
      syncActiveProviderModelMapping(provider);
      setProviderConfigVersion((prev) => prev + 1);
      setActiveProviderConfig(provider);
    } catch (error) {
      console.error('[Frontend] Failed to parse active provider in App:', error);
    }
  };

  window.updateThinkingEnabled = (jsonStr: string) => {
    const trimmed = (jsonStr || '').trim();
    try {
      const data = JSON.parse(trimmed);
      if (typeof data === 'boolean') {
        setClaudeSettingsAlwaysThinkingEnabled(data);
        return;
      }
      if (data && typeof data.enabled === 'boolean') {
        setClaudeSettingsAlwaysThinkingEnabled(data.enabled);
        return;
      }
    } catch {
      if (trimmed === 'true' || trimmed === 'false') {
        setClaudeSettingsAlwaysThinkingEnabled(trimmed === 'true');
      }
    }
  };

  window.updateStreamingEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setStreamingEnabledSetting(data.streamingEnabled ?? true);
    } catch (error) {
      console.error('[Frontend] Failed to parse streaming enabled:', error);
    }
  };

  window.updateSendShortcut = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      if (data.sendShortcut === 'enter' || data.sendShortcut === 'cmdEnter') {
        setSendShortcut(data.sendShortcut);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse send shortcut:', error);
    }
  };

  window.updateAutoOpenFileEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setAutoOpenFileEnabled(data.autoOpenFileEnabled ?? false);
    } catch (error) {
      console.error('[Frontend] Failed to parse auto open file enabled:', error);
    }
  };

  window.updatePermissionDialogTimeout = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      setPermissionDialogTimeoutSeconds(
        clampPermissionDialogTimeoutSeconds(data.permissionDialogTimeoutSeconds),
      );
    } catch (error) {
      const errorName = error instanceof Error ? error.name : 'UnknownError';
      console.error(`[Frontend] Failed to parse permission dialog timeout payload: ${errorName}`);
    }
  };

  drainPendingSettings();
  startInitialSettingsRequest();
}
