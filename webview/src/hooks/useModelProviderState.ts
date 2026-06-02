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
import { isSpecialProviderId } from '../types/provider';
import { useClaudeProvider } from './providers/useClaudeProvider';
import { useCodexProvider } from './providers/useCodexProvider';
import { useUsageTracking } from './providers/useUsageTracking';
import { useProviderSettings } from './providers/useProviderSettings';
import { useModelStatePersistence } from './providers/useModelStatePersistence';
import { subscribeActiveCodexProvider } from '../utils/runtimeProviderCapabilities';

export type ViewMode = 'chat' | 'history' | 'settings';

/**
 * 读取本地缓存的自定义模型列表。
 * 该方法只用于恢复阶段的兜底校验，读取失败时统一回退为空数组，
 * 避免因为 localStorage 内容损坏导致整个模型状态初始化失败。
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
 * Codex 当前模型可能来自本地 CLI 配置，不一定已经写入前端静态列表，
 * 因此前端恢复状态时需要允许保留未知但非空的模型 ID。
 *
 * @param modelId 待校验模型 ID
 * @param builtInModels 内置模型列表
 * @param customModels 本地自定义模型列表
 * @return 是否可直接视为已注册模型
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
  return builtInModels.some(model => model.id === normalizedModelId)
    || customModels.some(model => model.id === normalizedModelId);
}

/**
 * 解析可用于前端状态恢复的模型 ID。
 * 若模型出现在已知列表中则原样返回；否则只要是非空字符串也允许保留，
 * 用于兼容后端同步过来的新模型或用户 CLI 本地配置中的未知模型。
 *
 * @param modelId 原始模型 ID
 * @param builtInModels 内置模型列表
 * @param customModels 自定义模型列表
 * @return 可恢复模型 ID；无效时返回 null
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
}

/**
 * 编排聊天页中与 provider / model / mode 相关的跨模块状态。
 * 该 hook 以 upstream 拆分出的 provider 子 hook 为骨架，同时保留当前主线
 * 对 Codex CLI 默认模型、custom base URL、未知模型恢复和窗口回调 ref 的扩展语义。
 *
 * 这里的返回值故意维持扁平结构，因为 App、ChatScreen、window callbacks
 * 和消息发送链路都已经直接解构这些字段；如果在并轨阶段擅自改成嵌套结构，
 * 会把大量既有调用点一并打断。
 *
 * @param addToast 页面 toast 回调，用于 provider 切换或 thinking 状态提示
 * @param t i18n 翻译函数
 * @return 扁平的状态、ref 与处理函数集合
 */
export function useModelProviderState({ addToast, t }: UseModelProviderStateOptions) {
  const [currentProvider, setCurrentProvider] = useState('claude');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>('bypassPermissions');
  const [defaultCodexModelFromConfig, setDefaultCodexModelFromConfig] = useState<string | null>(null);
  const [codexBaseUrl, setCodexBaseUrl] = useState<string | null>(null);
  const [codexUsesCustomBaseUrl, setCodexUsesCustomBaseUrl] = useState(false);
  const [activeCodexProviderId, setActiveCodexProviderId] = useState('');

  const currentProviderRef = useRef(currentProvider);
  currentProviderRef.current = currentProvider;

  // 当本地已恢复过 Codex 模型时，后续 CLI 默认模型回推只用于展示，不再覆盖用户手选值。
  const shouldAdoptCodexDefaultModelRef = useRef(true);

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
    codexPermissionMode,
    setCodexPermissionMode,
    reasoningEffort,
    setReasoningEffort,
    handleReasoningChange,
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
    syncActiveProviderModelMapping,
    handleAgentSelect,
    handleStreamingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
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

  useEffect(() => {
    /**
     * 跟踪当前激活的 Codex provider id。
     * 持久化 selectedCodexModel 时需要把模型绑定到具体 provider，避免切换 provider 后恢复串味。
     */
    const unsubscribe = subscribeActiveCodexProvider((jsonStr: string) => {
      try {
        const provider = JSON.parse(jsonStr) as { id?: string | null };
        setActiveCodexProviderId(typeof provider?.id === 'string' ? provider.id.trim() : '');
      } catch {
        setActiveCodexProviderId('');
      }
    });
    return unsubscribe;
  }, []);

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
   * 主动请求一次 Codex 当前运行态配置。
   * 本地持久化状态只能恢复最近一次前端选择，无法包含 CLI 动态解析出来的 default model、
   * reasoning effort 与 custom base_url，因此这里仍需额外向后端拉取一份运行态快照。
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
   * 处理模式切换。
   * Codex 当前不支持 plan，因此进入 Codex 时必须把 plan 降级为 default，
   * 同时保证前端显示状态和后端会话状态一致。
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
   * Claude 需要先去掉 `[1m]` 后缀并按 long-context 开关重新拼装后发给后端；
   * Codex 则保留完整模型 ID，同时关闭“采用 CLI 默认模型”的一次性兜底逻辑。
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
      const savedCodexCustomModels = getCustomModels('codex-custom-models');
      const resolvedCodexModelId = resolveRestorableModelId(
        modelId,
        [{ id: selectedCodexModel }, { id: defaultCodexModelFromConfig ?? '' }],
        savedCodexCustomModels,
      ) ?? modelId;
      shouldAdoptCodexDefaultModelRef.current = false;
      setSelectedCodexModel(resolvedCodexModelId);
      sendBridgeEvent('set_model', resolvedCodexModelId);
      sendBridgeEvent('set_selected_codex_model', JSON.stringify({
        providerId: activeCodexProviderId,
        modelId: resolvedCodexModelId,
      }));
    }
  }, [
    activeCodexProviderId,
    currentProvider,
    defaultCodexModelFromConfig,
    longContextEnabled,
    selectedCodexModel,
    setSelectedClaudeModel,
    setSelectedCodexModel,
  ]);

  /**
   * 处理 provider 切换。
   * 切换时必须一起同步 provider、mode 和 model，避免前端已经切到新 provider，
   * 但后端仍沿用旧 provider 的 mode/model 组合。
   *
   * @param providerId 目标 provider 标识
   */
  const handleProviderSelect = useCallback((providerId: string) => {
    const shouldNotifyCodexPlanDowngrade = providerId === 'codex' && permissionMode === 'plan';

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

    if (shouldNotifyCodexPlanDowngrade) {
      notifyCodexPlanDowngrade();
    }
  }, [
    claudePermissionMode,
    codexPermissionMode,
    longContextEnabled,
    notifyCodexPlanDowngrade,
    permissionMode,
    selectedClaudeModel,
    selectedCodexModel,
  ]);

  /**
   * 切换 Claude long context 开关。
   * 这里只更新 Claude 侧的拼装模型 ID，不影响 Codex 状态。
   *
   * @param enabled 是否启用 1M context
   */
  const handleLongContextChange = useCallback((enabled: boolean) => {
    setLongContextEnabled(enabled);
    if (currentProvider === 'claude') {
      sendBridgeEvent('set_model', apply1MContextSuffix(selectedClaudeModel, enabled));
    }
  }, [currentProvider, selectedClaudeModel, setLongContextEnabled]);

  /**
   * 切换 Claude thinking 开关。
   * 特殊 provider 仍走本地状态 + 简化 bridge 事件；普通 provider 则回写 provider 配置，
   * 以便设置页、聊天页和后端会话状态统一。
   *
   * @param enabled 是否启用 thinking
   */
  const handleToggleThinking = useCallback((enabled: boolean) => {
    const config = activeProviderConfigRef.current;
    const isSpecialProvider = isSpecialProviderId(config?.id || '');

    setClaudeSettingsAlwaysThinkingEnabled(enabled);

    if (!config || isSpecialProvider) {
      setActiveProviderConfig(prev => prev ? {
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

    setActiveProviderConfig(prev => prev ? {
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
    sdkStatus,
    setSdkStatus,
    sdkStatusLoaded,
    setSdkStatusLoaded,
    selectedModel,
    currentSdkInstalled,
    currentProviderRef,
    activeProviderConfigRef,
    shouldAdoptCodexDefaultModelRef,
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
    handleLongContextChange,
  };
}
