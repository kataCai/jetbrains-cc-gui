import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { sendBridgeEvent } from '../utils/bridge';
import {
  apply1MContextSuffix,
  createRuntimeModelInfo,
  normalizeClaudeModelId,
  strip1MContextSuffix,
} from '../components/ChatInputBox/types';
import type { PermissionMode } from '../components/ChatInputBox/types';
import {
  buildCodexSelectedModelKey,
  isSpecialProviderId,
  parseCodexModelCatalogKey,
} from '../types/provider';
import { useClaudeProvider } from './providers/useClaudeProvider';
import { useCodexProvider } from './providers/useCodexProvider';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';
import { useModelStatePersistence } from './providers/useModelStatePersistence';
import { debugLog } from '../utils/debug';

export type ViewMode = 'chat' | 'history' | 'settings';

/**
 * 读取本地缓存的自定义模型列表。
 * 这里只用于前端恢复阶段的兜底校验，读取失败时统一返回空数组，
 * 避免 localStorage 中的脏数据导致整个模型状态初始化失败。
 *
 * @param key localStorage 键名
 * @return 自定义模型数组；异常时返回空数组
 */
const getCustomModels = (key: string): { id: string }[] => {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
};

/**
 * 判断给定模型是否存在于内置或自定义列表中。
 * Codex 当前模型可能来自本地 CLI 配置，不一定已经进入前端静态列表，
 * 因此前端恢复时需要允许保留未知但非空的模型 ID。
 *
 * @param modelId 待校验模型 ID
 * @param builtInModels 内置模型列表
 * @param customModels 自定义模型列表
 * @return 是否可视为已知模型
 */
function isKnownModel(
  modelId: string | null | undefined,
  builtInModels: { id: string }[],
  customModels: { id: string }[],
): boolean {
  const normalizedModelId = typeof modelId === 'string' ? modelId.trim() : '';
  if (!normalizedModelId) {
    return false;
  }
  return builtInModels.some((model) => model.id === normalizedModelId)
    || customModels.some((model) => model.id === normalizedModelId);
}

/**
 * 解析可用于前端恢复的模型 ID。
 * 若模型已经存在于已知列表则原样返回；否则只要非空也尝试保留，
 * 以兼容后端同步过来的新模型或用户本地 CLI 配置。
 *
 * @param modelId 原始模型 ID
 * @param builtInModels 内置模型列表
 * @param customModels 自定义模型列表
 * @return 可恢复的模型 ID；无效时返回 null
 */
function resolveRestorableModelId(
  modelId: string | null | undefined,
  builtInModels: { id: string }[],
  customModels: { id: string }[],
): string | null {
  const normalizedModelId = typeof modelId === 'string' ? modelId.trim() : '';
  if (!normalizedModelId) {
    return null;
  }
  if (isKnownModel(normalizedModelId, builtInModels, customModels)) {
    return normalizedModelId;
  }
  return createRuntimeModelInfo(normalizedModelId)?.id ?? null;
}

export interface UseModelProviderStateOptions {
  addToast: (message: string, type?: 'info' | 'success' | 'warning' | 'error') => void;
  t: TFunction;
  onCodexConversationConfigChanged?: (reason: 'provider' | 'model' | 'activeProvider') => void;
}

/**
 * 管理聊天页中与 provider / model / mode 相关的运行态。
 * 这里刻意把“当前标签运行态”和“设置页全局默认态”分离，
 * 避免多个标签页共用一份 Codex provider/model 选择而互相污染。
 *
 * @param addToast 页面 toast 回调
 * @param t i18n 翻译函数
 * @return 聊天页所需的扁平状态、ref 与处理函数集合
 */
export function useModelProviderState({
  addToast,
  t,
  onCodexConversationConfigChanged,
}: UseModelProviderStateOptions) {
  const traceCodexRuntime = useCallback((event: string, payload: Record<string, unknown>) => {
    debugLog(`[CODEX_RUNTIME_TRACE][Webview] ${event}`, payload);
  }, []);
  const [currentProvider, setCurrentProvider] = useState('claude');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>('bypassPermissions');
  const [defaultCodexModelFromConfig, setDefaultCodexModelFromConfig] = useState<string | null>(null);
  const [codexBaseUrl, setCodexBaseUrl] = useState<string | null>(null);
  const [codexUsesCustomBaseUrl, setCodexUsesCustomBaseUrl] = useState(false);
  const [activeCodexProviderId, setActiveCodexProviderId] = useState('');

  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;
  const activeCodexProviderIdRef = useRef(activeCodexProviderId);
  useEffect(() => {
    activeCodexProviderIdRef.current = activeCodexProviderId;
  }, [activeCodexProviderId]);

  /**
   * 当标签页已经从持久化状态或后端恢复过 Codex 模型后，
   * 后续 CLI 默认模型回推只用于展示，不再覆盖当前标签显式选择。
   */
  const shouldAdoptCodexDefaultModelRef = useRef(true);
  const shouldAdoptCodexDefaultReasoningEffortRef = useRef(true);

  const claude = useClaudeProvider();
  const codex = useCodexProvider();
  const { isSdkInstalled, ...usage } = useUsageTracking();
  const settings = useProviderSettings({ addToast, t });

  const {
    selectedClaudeModel,
    setSelectedClaudeModel,
    claudePermissionMode,
    setClaudePermissionMode,
    longContextEnabled,
    setLongContextEnabled,
    claudeSettingsAlwaysThinkingEnabled,
    setClaudeSettingsAlwaysThinkingEnabled,
  } = claude;
  const {
    selectedCodexModel,
    setSelectedCodexModel,
    selectedCodexSelectionKey,
    setSelectedCodexSelectionKey,
    codexPermissionMode,
    setCodexPermissionMode,
    reasoningEffort,
    setReasoningEffort,
    handleReasoningChange: handleReasoningChangeInternal,
  } = codex;
  const {
    activeProviderConfig,
    setActiveProviderConfig,
    setProviderConfigVersion,
    selectedAgent,
    setSelectedAgent,
    streamingEnabledSetting,
    setStreamingEnabledSetting,
    sendShortcut,
    setSendShortcut,
    autoOpenFileEnabled,
    setAutoOpenFileEnabled,
    rightClickOpenDevToolsEnabled,
    setRightClickOpenDevToolsEnabled,
    syncActiveProviderModelMapping,
    handleAgentSelect,
    handleStreamingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
    handleRightClickOpenDevToolsEnabledChange,
  } = settings;
  const {
    usagePercentage,
    setUsagePercentage,
    usageUsedTokens,
    setUsageUsedTokens,
    usageMaxTokens,
    setUsageMaxTokens,
    sdkStatus,
    setSdkStatus,
    sdkStatusLoaded,
    setSdkStatusLoaded,
  } = usage;

  const activeProviderConfigRef = useRef(activeProviderConfig);
  useEffect(() => {
    activeProviderConfigRef.current = activeProviderConfig;
  }, [activeProviderConfig]);

  const notifyCodexPlanDowngrade = useCallback(() => {
    addToast(
      t('chat.planDowngradedForCodex', {
        defaultValue: 'Codex does not support Plan mode. Switched back to Chat/default.',
      }),
      'warning',
    );
  }, [addToast, t]);

  useModelStatePersistence({
    setCurrentProvider,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    setReasoningEffort,
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    claudePermissionMode,
    codexPermissionMode,
    longContextEnabled,
    reasoningEffort,
    onCodexModelHydrated: () => {
      shouldAdoptCodexDefaultModelRef.current = false;
    },
  });

  /**
   * 主动向后端拉取一次 Codex 当前运行态配置。
   * localStorage 只保留 Claude 侧显示偏好，Codex 真实运行态由后端/标签页快照驱动。
   */
  useEffect(() => {
    let retryCount = 0;
    const maxRetries = 10;
    let timeoutId: number | undefined;

    const requestCodexModelState = () => {
      if (window.sendToJava) {
        sendBridgeEvent('get_codex_model_state');
        return;
      }
      retryCount++;
      if (retryCount < maxRetries) {
        timeoutId = window.setTimeout(requestCodexModelState, 100);
      }
    };

    timeoutId = window.setTimeout(requestCodexModelState, 200);
    return () => {
      if (timeoutId !== undefined) {
        clearTimeout(timeoutId);
      }
    };
  }, []);

  const selectedModel = currentProvider === 'codex' ? selectedCodexModel : selectedClaudeModel;
  const currentSdkInstalled = useMemo(
    () => isSdkInstalled(currentProvider),
    [currentProvider, isSdkInstalled],
  );

  /**
   * 处理权限模式切换。
   * Codex 不支持 plan，因此进入 Codex 时需要自动降级为 default。
   *
   * @param mode 目标权限模式
   */
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    if (currentProvider === 'codex') {
      const codexMode: PermissionMode = mode === 'plan' ? 'default' : mode;
      setPermissionMode(codexMode);
      setCodexPermissionMode(codexMode);
      sendBridgeEvent('set_mode', codexMode);
      return;
    }

    setPermissionMode(mode);
    setClaudePermissionMode(mode);
    sendBridgeEvent('set_mode', mode);
  }, [currentProvider, setClaudePermissionMode, setCodexPermissionMode]);

  /**
   * 处理模型切换。
   * Claude 需要根据 long-context 开关拼装最终模型 ID；
   * Codex 则把选择绑定到当前标签的 provider + model 组合。
   *
   * @param modelId 用户选择的模型 ID
   */
  const handleModelSelect = useCallback((modelId: string) => {
    if (currentProvider === 'claude') {
      const strippedModelId = strip1MContextSuffix(modelId);
      const normalizedModelId = normalizeClaudeModelId(strippedModelId);
      setSelectedClaudeModel(normalizedModelId);
      sendBridgeEvent('set_model', apply1MContextSuffix(normalizedModelId, longContextEnabled));
      return;
    }

    if (currentProvider === 'codex') {
      const parsedCatalogSelection = parseCodexModelCatalogKey(modelId);
      const targetProviderId = parsedCatalogSelection?.providerId ?? activeCodexProviderId;
      const targetModelId = parsedCatalogSelection?.modelId ?? modelId;
      const savedCodexCustomModels = getCustomModels('codex-custom-models');
      const resolvedCodexModelId = resolveRestorableModelId(
        targetModelId,
        [{ id: selectedCodexModel }, { id: defaultCodexModelFromConfig ?? '' }],
        savedCodexCustomModels,
      ) ?? targetModelId;

      traceCodexRuntime('codexModelSelect', {
        currentProvider,
        currentTabProviderId: activeCodexProviderId,
        targetProviderId,
        targetModelId,
        resolvedCodexModelId,
      });
      shouldAdoptCodexDefaultModelRef.current = false;
      // 聊天区选中态必须保留 provider 维度，否则同名模型会在下拉中被同时勾选。
      setSelectedCodexSelectionKey(buildCodexSelectedModelKey(targetProviderId, resolvedCodexModelId));
      setSelectedCodexModel(resolvedCodexModelId);
      sendBridgeEvent('set_model', resolvedCodexModelId);
      sendBridgeEvent('select_codex_model', JSON.stringify({
        providerId: targetProviderId,
        modelId: resolvedCodexModelId,
      }));
      // Codex 运行时模型改变后必须丢弃当前 threadId，避免继续复用旧会话。
      onCodexConversationConfigChanged?.('model');
    }
  }, [
    activeCodexProviderId,
    currentProvider,
    defaultCodexModelFromConfig,
    longContextEnabled,
    onCodexConversationConfigChanged,
    selectedCodexModel,
    setSelectedCodexSelectionKey,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    traceCodexRuntime,
  ]);

  /**
   * 处理聊天页 provider 切换。
   * 这里是“当前标签”的 provider，不等同于设置页里的全局默认 provider。
   *
   * @param providerId 目标 provider
   */
  const handleProviderSelect = useCallback((providerId: string) => {
    const shouldNotifyPlanDowngrade = providerId === 'codex' && permissionMode === 'plan';
    traceCodexRuntime('providerSelect', {
      previousProvider: currentProvider,
      nextProvider: providerId,
      permissionMode,
      claudePermissionMode,
      codexPermissionMode,
      selectedClaudeModel,
      selectedCodexModel,
    });

    setCurrentProvider(providerId);
    sendBridgeEvent('set_provider', providerId);

    const modeToSet: PermissionMode = providerId === 'codex'
      ? (codexPermissionMode === 'plan' ? 'default' : codexPermissionMode)
      : claudePermissionMode;
    setPermissionMode(modeToSet);
    sendBridgeEvent('set_mode', modeToSet);

    const newModel = providerId === 'codex'
      ? selectedCodexModel
      : apply1MContextSuffix(selectedClaudeModel, longContextEnabled);
    sendBridgeEvent('set_model', newModel);

    if (providerId === 'codex' || currentProvider === 'codex') {
      onCodexConversationConfigChanged?.('provider');
    }

    if (shouldNotifyPlanDowngrade) {
      notifyCodexPlanDowngrade();
    }
  }, [
    claudePermissionMode,
    codexPermissionMode,
    currentProvider,
    longContextEnabled,
    notifyCodexPlanDowngrade,
    onCodexConversationConfigChanged,
    permissionMode,
    selectedClaudeModel,
    selectedCodexModel,
    traceCodexRuntime,
  ]);

  /**
   * 切换 Claude long context。
   * 这里只影响 Claude 侧模型，不改 Codex 标签运行态。
   *
   * @param enabled 是否启用 1M context
   */
  const handleLongContextChange = useCallback((enabled: boolean) => {
    setLongContextEnabled(enabled);
    if (currentProvider === 'claude') {
      sendBridgeEvent('set_model', apply1MContextSuffix(selectedClaudeModel, enabled));
    }
  }, [currentProvider, selectedClaudeModel, setLongContextEnabled]);

  const handleReasoningChange = useCallback((effort: typeof reasoningEffort) => {
    shouldAdoptCodexDefaultReasoningEffortRef.current = false;
    handleReasoningChangeInternal(effort);
  }, [handleReasoningChangeInternal, reasoningEffort]);

  /**
   * 切换 Claude thinking。
   * 特殊 provider 仍走本地状态 + 简化 bridge 事件；普通 provider 回写 provider 配置。
   *
   * @param enabled 是否启用 thinking
   */
  const handleToggleThinking = useCallback((enabled: boolean) => {
    const config = activeProviderConfigRef.current;
    const isSpecialProvider = isSpecialProviderId(config?.id || '');

    setClaudeSettingsAlwaysThinkingEnabled(enabled);

    if (!config || isSpecialProvider) {
      setActiveProviderConfig((prev) => prev ? {
        ...prev,
        settingsConfig: {
          ...prev.settingsConfig,
          alwaysThinkingEnabled: enabled,
        },
      } : prev);
      sendBridgeEvent('set_thinking_enabled', JSON.stringify({ enabled }));
      addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
      return;
    }

    setActiveProviderConfig((prev) => prev ? {
      ...prev,
      settingsConfig: {
        ...prev.settingsConfig,
        alwaysThinkingEnabled: enabled,
      },
    } : null);

    sendBridgeEvent('update_provider', JSON.stringify({
      id: config.id,
      updates: {
        settingsConfig: {
          ...(config.settingsConfig || {}),
          alwaysThinkingEnabled: enabled,
        },
      },
    }));
    addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
  }, [addToast, setActiveProviderConfig, setClaudeSettingsAlwaysThinkingEnabled, t]);

  return {
    ...claude,
    ...codex,
    ...usage,
    ...settings,
    currentProvider,
    setCurrentProvider,
    permissionMode,
    setPermissionMode,
    selectedClaudeModel,
    setSelectedClaudeModel,
    selectedCodexModel,
    setSelectedCodexModel,
    selectedCodexSelectionKey,
    setSelectedCodexSelectionKey,
    defaultCodexModelFromConfig,
    setDefaultCodexModelFromConfig,
    codexBaseUrl,
    setCodexBaseUrl,
    codexUsesCustomBaseUrl,
    setCodexUsesCustomBaseUrl,
    claudePermissionMode,
    setClaudePermissionMode,
    codexPermissionMode,
    setCodexPermissionMode,
    reasoningEffort,
    setReasoningEffort,
    usagePercentage,
    setUsagePercentage,
    usageUsedTokens,
    setUsageUsedTokens,
    usageMaxTokens,
    setUsageMaxTokens,
    setProviderConfigVersion,
    activeProviderConfig,
    setActiveProviderConfig,
    claudeSettingsAlwaysThinkingEnabled,
    setClaudeSettingsAlwaysThinkingEnabled,
    selectedAgent,
    setSelectedAgent,
    streamingEnabledSetting,
    setStreamingEnabledSetting,
    sendShortcut,
    setSendShortcut,
    autoOpenFileEnabled,
    setAutoOpenFileEnabled,
    rightClickOpenDevToolsEnabled,
    setRightClickOpenDevToolsEnabled,
    sdkStatus,
    setSdkStatus,
    sdkStatusLoaded,
    setSdkStatusLoaded,
    selectedModel,
    currentSdkInstalled,
    currentProviderRef,
    activeCodexProviderId,
    setActiveCodexProviderId,
    activeCodexProviderIdRef,
    activeProviderConfigRef,
    shouldAdoptCodexDefaultModelRef,
    shouldAdoptCodexDefaultReasoningEffortRef,
    syncActiveProviderModelMapping,
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleReasoningChange,
    handleAgentSelect,
    handleToggleThinking,
    handleStreamingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
    handleRightClickOpenDevToolsEnabledChange,
    handleLongContextChange,
  };
}
