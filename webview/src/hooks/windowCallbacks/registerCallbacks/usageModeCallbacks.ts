import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode, ReasoningEffort } from '../../../components/ChatInputBox/types';
import { isValidPermissionMode, normalizeClaudeModelId } from '../../../components/ChatInputBox/types';
import { buildCodexSelectedModelKey } from '../../../types/provider';
import { buildRuntimeSelectionState } from '../../../types/runtimeSelection';
import { clampPermissionDialogTimeoutSeconds } from '../../../utils/permissionDialogTimeout';
import { debugLog } from '../../../utils/debug';
import { updateFrontendDebugRuntimeConfig } from '../../../utils/debug';
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';

/**
 * 注册使用量、权限模式、模型与基础设置相关的 window bridge 回调。
 *
 * 该入口负责把后端运行态快照回写到前端，并同步维护 provider/model 选择状态、
 * continued segment 运行态以及若干基础设置项，避免聊天页与设置页状态分叉。
 *
 * @param options 回调注册所需的状态 setter、引用对象与同步工具
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
    setActiveSessionRuntimeSnapshot,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled,
    setStreamingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setRightClickOpenDevToolsEnabled,
    setFrontendDebugPanelEnabled,
    setFrontendDiagnosticArchiveEnabled,
    setPermissionDialogTimeoutSeconds,
    currentProviderRef,
    activeCodexProviderIdRef,
    shouldAdoptCodexDefaultModelRef,
    shouldAdoptCodexDefaultReasoningEffortRef,
    shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef,
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
   * Codex 不支持 plan 模式，因此后端误传 plan 时，前端需要降级到 default。
   *
   * @param mode 后端同步的权限模式
   * @param providerOverride 可选的 provider 覆盖值，用于处理 provider 切换过程中的回调
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

  /**
   * 根据后端回推构建并保存“当前活动分段真实运行态”快照。
   * 该快照只描述当前物理 session 的真实 provider/model/reasoning/providerId，
   * 不直接代表聊天区选择器当前想要发送到哪个运行时。
   *
   * @param data 后端回推的运行态字段
   * @return 无返回值
   */
  const updateActiveRuntimeSnapshot = (data: {
    provider?: string;
    model?: string;
    reasoningEffort?: ReasoningEffort;
    codexProviderId?: string;
  }) => {
    setActiveSessionRuntimeSnapshot(buildRuntimeSelectionState({
      provider: data.provider === 'codex' ? 'codex' : 'claude',
      model: typeof data.model === 'string' ? data.model.trim() : '',
      reasoningEffort:
        data.reasoningEffort === 'low'
        || data.reasoningEffort === 'medium'
        || data.reasoningEffort === 'high'
        || data.reasoningEffort === 'xhigh'
          ? data.reasoningEffort
          : undefined,
      codexProviderId: typeof data.codexProviderId === 'string' ? data.codexProviderId.trim() : '',
    }));
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
      updateActiveRuntimeSnapshot(data);
      if (shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef.current) {
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
      }

      // 中文注释：Tab 恢复阶段需要把逻辑会话与 continued segment 运行态一起恢复，
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
      const restoredContinuationSourceSessionId =
        typeof data.continuationSourceSessionId === 'string' && data.continuationSourceSessionId.trim().length > 0
          ? data.continuationSourceSessionId.trim()
          : null;
      // 中文注释：旧 Tab 快照可能只保留逻辑会话主键而缺失活动分段，后端补发 latestSessionId 时这里兜底到最新分段，
      // 避免恢复后继续发送又落回旧物理分段或空分段。
      setActiveSegmentSessionId(restoredActiveSegmentSessionId);
      setParentSegmentSessionId(
        typeof data.parentSegmentSessionId === 'string' && data.parentSegmentSessionId.trim().length > 0
          ? data.parentSegmentSessionId.trim()
          : null,
      );
      // 中文注释：如果恢复时已经存在稳定的 active segment，但没有 continuation source 锚点，
      // 说明 continuationPending 只是旧快照残留，继续恢复为 true 只会错误阻塞首条发送。
      const shouldNormalizeStaleContinuationPending =
        data.continuationPending === true
        && restoredActiveSegmentSessionId !== null
        && restoredContinuationSourceSessionId === null;
      setContinuationPending(shouldNormalizeStaleContinuationPending ? false : data.continuationPending === true);
      setContinuationSourceSessionId(
        shouldNormalizeStaleContinuationPending ? null : restoredContinuationSourceSessionId,
      );

      if (shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef.current) {
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
      updateActiveRuntimeSnapshot(data);
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
        data.reasoningEffort === 'low'
        || data.reasoningEffort === 'medium'
        || data.reasoningEffort === 'high'
        || data.reasoningEffort === 'xhigh'
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

  /**
   * 回写“右键打开调试面板”全局开关。
   * 该值会同时驱动设置页和聊天页的右键菜单入口，因此需要与其他基础行为开关保持同类回写协议。
   */
  window.updateRightClickOpenDevToolsEnabled = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      // 聊天页不一定提供这个 setter，因此这里需要保持可选调用。
      setRightClickOpenDevToolsEnabled?.(data.rightClickOpenDevToolsEnabled ?? false);
    } catch (error) {
      console.error('[Frontend] Failed to parse right click devtools enabled:', error);
    }
  };

  /**
   * 回写前端诊断日志运行时配置。
   * 除了驱动 React 状态，还需要同步更新模块级 runtime config，保证聊天页未重新挂载时也能立即生效。
   */
  window.updateFrontendDebugConfig = (jsonStr: string) => {
    try {
      const data = JSON.parse(jsonStr);
      const panelEnabled = data.panelEnabled === true;
      const archiveEnabled = data.archiveEnabled === true;
      updateFrontendDebugRuntimeConfig({
        panelEnabled,
        archiveEnabled,
        panelConfigured: data.panelConfigured === true,
        archiveConfigured: data.archiveConfigured === true,
      });
      setFrontendDebugPanelEnabled?.(panelEnabled);
      setFrontendDiagnosticArchiveEnabled?.(archiveEnabled);
    } catch (error) {
      console.error('[Frontend] Failed to parse frontend debug config:', error);
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
