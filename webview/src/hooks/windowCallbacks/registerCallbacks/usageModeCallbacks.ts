/**
 * usageModeCallbacks.ts
 *
 * Registers window bridge callbacks for usage statistics, permission modes, and
 * model/provider updates: onUsageUpdate, onModeChanged, onModeReceived,
 * onModelChanged, onModelConfirmed, updateActiveProvider, updateThinkingEnabled,
 * updateStreamingEnabled, updateSendShortcut, updateAutoOpenFileEnabled.
 */

import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import type { PermissionMode, ReasoningEffort } from '../../../components/ChatInputBox/types';
import { isValidPermissionMode, normalizeClaudeModelId } from '../../../components/ChatInputBox/types';
import { drainPendingSettings, startInitialSettingsRequest } from '../settingsBootstrap';

export function registerUsageModeCallbacks(options: UseWindowCallbacksOptions): void {
  const {
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
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
    currentProviderRef,
    shouldAdoptCodexDefaultModelRef,
    syncActiveProviderModelMapping,
  } = options;

  window.onUsageUpdate = (json) => {
    try {
      const data = JSON.parse(json);
      if (typeof data.percentage === 'number') {
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
          console.warn(
            '[Frontend] Usage data may be incorrect: used=' + used + ', max=' + max,
          );
        }

        const safePercentage = Math.max(0, Math.min(100, data.percentage));
        setUsagePercentage(safePercentage);
        setUsageUsedTokens(used);
        setUsageMaxTokens(max);
      }
    } catch (error) {
      console.error('[Frontend] Failed to parse usage update:', error);
    }
  };

  const updateMode = (mode?: PermissionMode, providerOverride?: string) => {
    const activeProvider = providerOverride || currentProviderRef.current;
    if (isValidPermissionMode(mode)) {
      const nextMode: PermissionMode =
        activeProvider === 'codex' && mode === 'plan' ? 'default' : mode;
      setPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      if (activeProvider === 'codex') {
        setCodexPermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      } else {
        setClaudePermissionMode((prev) => (prev === nextMode ? prev : nextMode));
      }
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

  /**
   * 接收后端同步的 Codex 当前模型状态。
   * 该回调会覆盖前端缓存中的默认模型、reasoning effort 以及 custom base URL 状态，
   * 确保 GUI 展示与本地 `~/.codex/config.toml` 的真实运行态一致。
   *
   * @param jsonStr 后端序列化后的当前模型状态
   */
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

  drainPendingSettings();
  startInitialSettingsRequest();
}
