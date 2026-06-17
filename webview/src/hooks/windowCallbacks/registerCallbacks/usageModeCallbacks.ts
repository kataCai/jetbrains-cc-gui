import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode, ReasoningEffort } from '../../../components/ChatInputBox/types';
import { isValidPermissionMode, normalizeClaudeModelId } from '../../../components/ChatInputBox/types';
import { clampPermissionDialogTimeoutSeconds } from '../../../utils/permissionDialogTimeout';
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
    setCurrentProvider,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
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
    shouldAdoptCodexDefaultModelRef,
    syncActiveProviderModelMapping,
  } = options;

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
    }
  };

  window.onModelConfirmed = (modelId, provider) => {
    if (provider === 'claude') {
      setSelectedClaudeModel(normalizeClaudeModelId(modelId));
    } else if (provider === 'codex') {
      setSelectedCodexModel(modelId);
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
      };

      const nextProvider = data.provider === 'codex' ? 'codex' : 'claude';
      setCurrentProvider(nextProvider);
      currentProviderRef.current = nextProvider;

      if (nextProvider === 'claude' && typeof data.model === 'string' && data.model.trim().length > 0) {
        setSelectedClaudeModel(normalizeClaudeModelId(data.model));
      }

      if (nextProvider === 'codex' && typeof data.model === 'string' && data.model.trim().length > 0) {
        const normalizedModel = data.model.trim();
        setSelectedCodexModel(normalizedModel);
        shouldAdoptCodexDefaultModelRef.current = false;
      }

      if (typeof data.codexProviderId === 'string') {
        setActiveCodexProviderId(data.codexProviderId.trim());
      }

      updateMode(data.permissionMode, nextProvider);

      if (
        data.reasoningEffort === 'low'
        || data.reasoningEffort === 'medium'
        || data.reasoningEffort === 'high'
        || data.reasoningEffort === 'xhigh'
      ) {
        setReasoningEffort(data.reasoningEffort);
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
        }
      }

      if (
        data.reasoningEffort === 'low' ||
        data.reasoningEffort === 'medium' ||
        data.reasoningEffort === 'high' ||
        data.reasoningEffort === 'xhigh'
      ) {
        setReasoningEffort(data.reasoningEffort);
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
