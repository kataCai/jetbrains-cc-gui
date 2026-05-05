import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { TFunction } from 'i18next';
import { sendBridgeEvent } from '../utils/bridge';
import { CLAUDE_MODELS, CODEX_MODELS, createRuntimeModelInfo, isValidPermissionMode } from '../components/ChatInputBox/types';
import type { PermissionMode, ReasoningEffort, SelectedAgent } from '../components/ChatInputBox/types';
import type { ProviderConfig } from '../types/provider';
import { isSpecialProviderId } from '../types/provider';
import { writeClaudeModelMapping } from '../utils/claudeModelMapping';

export type ViewMode = 'chat' | 'history' | 'settings';

const getCustomModels = (key: string): { id: string }[] => {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : [];
  } catch { return []; }
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
 * Manages all provider/model/permission state, initialization from localStorage,
 * sync to backend, and handler callbacks for mode/model/provider selection.
 */
export function useModelProviderState({ addToast, t }: UseModelProviderStateOptions) {
  // ChatInputBox related state
  const [currentProvider, setCurrentProvider] = useState('claude');
  const [selectedClaudeModel, setSelectedClaudeModel] = useState(CLAUDE_MODELS[0].id);
  const [selectedCodexModel, setSelectedCodexModel] = useState(CODEX_MODELS[0].id);
  const [defaultCodexModelFromConfig, setDefaultCodexModelFromConfig] = useState<string | null>(null);
  const [codexBaseUrl, setCodexBaseUrl] = useState<string | null>(null);
  const [codexUsesCustomBaseUrl, setCodexUsesCustomBaseUrl] = useState(false);
  const [claudePermissionMode, setClaudePermissionMode] = useState<PermissionMode>('bypassPermissions');
  const [codexPermissionMode, setCodexPermissionMode] = useState<PermissionMode>('default');
  const [permissionMode, setPermissionMode] = useState<PermissionMode>('bypassPermissions');
  // Codex reasoning effort (thinking depth)
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('medium');
  const [usagePercentage, setUsagePercentage] = useState(0);
  const [usageUsedTokens, setUsageUsedTokens] = useState<number | undefined>(undefined);
  const [usageMaxTokens, setUsageMaxTokens] = useState<number | undefined>(undefined);
  const [, setProviderConfigVersion] = useState(0);
  const [activeProviderConfig, setActiveProviderConfig] = useState<ProviderConfig | null>(null);
  const [claudeSettingsAlwaysThinkingEnabled, setClaudeSettingsAlwaysThinkingEnabled] = useState(true);
  const [selectedAgent, setSelectedAgent] = useState<SelectedAgent | null>(null);
  // Streaming toggle state (synced with settings page)
  const [streamingEnabledSetting, setStreamingEnabledSetting] = useState(true);
  // Send shortcut setting
  const [sendShortcut, setSendShortcut] = useState<'enter' | 'cmdEnter'>('enter');
  // Auto-open file setting
  const [autoOpenFileEnabled, setAutoOpenFileEnabled] = useState(false);

  // SDK installation status
  const [sdkStatus, setSdkStatus] = useState<Record<string, { installed?: boolean; status?: string }>>({});
  const [sdkStatusLoaded, setSdkStatusLoaded] = useState(false);

  // Refs for stale closure prevention
  const currentProviderRef = useRef(currentProvider);
  const activeProviderConfigRef = useRef(activeProviderConfig);
  const shouldAdoptCodexDefaultModelRef = useRef(true);
  useEffect(() => { currentProviderRef.current = currentProvider; }, [currentProvider]);
  useEffect(() => { activeProviderConfigRef.current = activeProviderConfig; }, [activeProviderConfig]);

  const notifyCodexPlanDowngrade = useCallback(() => {
    addToast(
      t('chat.planDowngradedForCodex', {
        defaultValue: 'Codex does not support Plan mode. Switched back to Chat/default.',
      }),
      'warning',
    );
  }, [addToast, t]);

  // Select the displayed model based on the current provider
  const selectedModel = currentProvider === 'codex' ? selectedCodexModel : selectedClaudeModel;

  // Determine whether the SDK for the current provider is installed
  const currentSdkInstalled = useMemo(() => {
    if (!sdkStatusLoaded) return false;
    const providerToSdk: Record<string, string> = {
      claude: 'claude-sdk',
      anthropic: 'claude-sdk',
      bedrock: 'claude-sdk',
      codex: 'codex-sdk',
      openai: 'codex-sdk',
    };
    const sdkId = providerToSdk[currentProvider] || 'claude-sdk';
    const status = sdkStatus[sdkId];
    return status?.status === 'installed' || status?.installed === true;
  }, [sdkStatusLoaded, currentProvider, sdkStatus]);

  const syncActiveProviderModelMapping = useCallback((provider?: ProviderConfig | null) => {
    if (!provider || !provider.settingsConfig || !provider.settingsConfig.env) {
      writeClaudeModelMapping({});
      return;
    }
    const env = provider.settingsConfig.env as Record<string, any>;
    const mapping = {
      main: env.ANTHROPIC_MODEL ?? '',
      haiku: env.ANTHROPIC_SMALL_FAST_MODEL ?? env.ANTHROPIC_DEFAULT_HAIKU_MODEL ?? '',
      sonnet: env.ANTHROPIC_DEFAULT_SONNET_MODEL ?? '',
      opus: env.ANTHROPIC_DEFAULT_OPUS_MODEL ?? '',
    };
    writeClaudeModelMapping(mapping);
  }, []);

  // Load model selection state from LocalStorage and sync to backend
  useEffect(() => {
    try {
      const saved = localStorage.getItem('model-selection-state');
      let restoredProvider = 'claude';
      let restoredClaudeModel = CLAUDE_MODELS[0].id;
      let restoredCodexModel = CODEX_MODELS[0].id;
      let restoredClaudePermissionMode: PermissionMode = 'bypassPermissions';
      let restoredCodexPermissionMode: PermissionMode = 'default';
      let initialPermissionMode: PermissionMode = 'bypassPermissions';

      if (saved) {
        const state = JSON.parse(saved);

        if (['claude', 'codex'].includes(state.provider)) {
          restoredProvider = state.provider;
          setCurrentProvider(state.provider);
        }

        if (isValidPermissionMode(state.claudePermissionMode)) {
          restoredClaudePermissionMode = state.claudePermissionMode;
        }
        if (isValidPermissionMode(state.codexPermissionMode)) {
          restoredCodexPermissionMode = state.codexPermissionMode === 'plan'
            ? 'default'
            : state.codexPermissionMode;
        }

        const savedClaudeCustomModels = getCustomModels('claude-custom-models');
        if (
          CLAUDE_MODELS.find(m => m.id === state.claudeModel) ||
          savedClaudeCustomModels.find((m: { id: string }) => m.id === state.claudeModel)
        ) {
          restoredClaudeModel = state.claudeModel;
          setSelectedClaudeModel(state.claudeModel);
        }

        const savedCodexCustomModels = getCustomModels('codex-custom-models');
        const restoredCodexModelId = resolveRestorableModelId(
          state.codexModel,
          CODEX_MODELS,
          savedCodexCustomModels,
        );
        if (restoredCodexModelId) {
          shouldAdoptCodexDefaultModelRef.current = false;
          restoredCodexModel = restoredCodexModelId;
          setSelectedCodexModel(restoredCodexModelId);
        }
      }

      initialPermissionMode = restoredProvider === 'codex'
        ? restoredCodexPermissionMode
        : restoredClaudePermissionMode;
      setClaudePermissionMode(restoredClaudePermissionMode);
      setCodexPermissionMode(restoredCodexPermissionMode);
      setPermissionMode(initialPermissionMode);

      let syncRetryCount = 0;
      const MAX_SYNC_RETRIES = 30;

      const syncToBackend = () => {
        if (window.sendToJava) {
          sendBridgeEvent('set_provider', restoredProvider);
          const modelToSync = restoredProvider === 'codex' ? restoredCodexModel : restoredClaudeModel;
          sendBridgeEvent('set_model', modelToSync);
          sendBridgeEvent('set_mode', initialPermissionMode);
        } else {
          syncRetryCount++;
          if (syncRetryCount < MAX_SYNC_RETRIES) {
            setTimeout(syncToBackend, 100);
          }
        }
      };
      setTimeout(syncToBackend, 200);
    } catch {
      // Failed to load model selection state
    }
  }, []);

  useEffect(() => {
    let retryCount = 0;
    const MAX_RETRIES = 10;
    let timeoutId: number | undefined;

      const requestCodexModelState = () => {
        if (window.sendToJava) {
          sendBridgeEvent('get_codex_model_state');
      } else {
        retryCount++;
        if (retryCount < MAX_RETRIES) {
          timeoutId = window.setTimeout(requestCodexModelState, 100);
        }
      }
    };

    timeoutId = window.setTimeout(requestCodexModelState, 200);

    return () => {
      if (timeoutId !== undefined) {
        clearTimeout(timeoutId);
      }
    };
  }, []);

  // Save model selection state to LocalStorage
  useEffect(() => {
    try {
      localStorage.setItem('model-selection-state', JSON.stringify({
        provider: currentProvider,
        claudeModel: selectedClaudeModel,
        codexModel: selectedCodexModel,
        claudePermissionMode,
        codexPermissionMode,
      }));
    } catch {
      // Failed to save model selection state
    }
  }, [currentProvider, selectedClaudeModel, selectedCodexModel, claudePermissionMode, codexPermissionMode]);

  // Load selected agent
  useEffect(() => {
    let retryCount = 0;
    const MAX_RETRIES = 10;
    let timeoutId: number | undefined;

    const loadSelectedAgent = () => {
      if (window.sendToJava) {
        sendBridgeEvent('get_selected_agent');
      } else {
        retryCount++;
        if (retryCount < MAX_RETRIES) {
          timeoutId = window.setTimeout(loadSelectedAgent, 100);
        }
      }
    };

    timeoutId = window.setTimeout(loadSelectedAgent, 200);

    return () => {
      if (timeoutId !== undefined) {
        clearTimeout(timeoutId);
      }
    };
  }, []);

  // Handler callbacks
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    if (currentProviderRef.current === 'codex') {
      const codexMode: PermissionMode = mode === 'plan' ? 'default' : mode;
      setPermissionMode(codexMode);
      setCodexPermissionMode(codexMode);
      sendBridgeEvent('set_mode', codexMode);
      return;
    }
    setPermissionMode(mode);
    setClaudePermissionMode(mode);
    sendBridgeEvent('set_mode', mode);
  }, []);

  const handleModelSelect = useCallback((modelId: string) => {
    if (currentProviderRef.current === 'claude') {
      setSelectedClaudeModel(modelId);
    } else if (currentProviderRef.current === 'codex') {
      shouldAdoptCodexDefaultModelRef.current = false;
      setSelectedCodexModel(modelId);
    }
    sendBridgeEvent('set_model', modelId);
  }, []);

  const handleProviderSelect = useCallback((providerId: string) => {
    const shouldNotifyCodexPlanDowngrade = providerId === 'codex' && permissionMode === 'plan';
    setCurrentProvider(providerId);
    sendBridgeEvent('set_provider', providerId);
    const modeToSet: PermissionMode = providerId === 'codex'
      ? (codexPermissionMode === 'plan' ? 'default' : codexPermissionMode)
      : claudePermissionMode;
    setPermissionMode(modeToSet);
    sendBridgeEvent('set_mode', modeToSet);

    const newModel = providerId === 'codex' ? selectedCodexModel : selectedClaudeModel;
    sendBridgeEvent('set_model', newModel);
    if (shouldNotifyCodexPlanDowngrade) {
      notifyCodexPlanDowngrade();
    }
  }, [claudePermissionMode, codexPermissionMode, notifyCodexPlanDowngrade, permissionMode, selectedCodexModel, selectedClaudeModel]);

  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    setReasoningEffort(effort);
    sendBridgeEvent('set_reasoning_effort', effort);
  }, []);

  const handleAgentSelect = useCallback((agent: SelectedAgent | null) => {
    setSelectedAgent(agent);
    if (agent) {
      sendBridgeEvent('set_selected_agent', JSON.stringify({
        id: agent.id,
        name: agent.name,
        prompt: agent.prompt,
      }));
    } else {
      sendBridgeEvent('set_selected_agent', '');
    }
  }, []);

  const handleToggleThinking = useCallback((enabled: boolean) => {
    const config = activeProviderConfigRef.current;
    const isSpecialProvider = isSpecialProviderId(config?.id || '');

    setClaudeSettingsAlwaysThinkingEnabled(enabled);

    if (!config || isSpecialProvider) {
      setActiveProviderConfig(prev => prev ? {
        ...prev,
        settingsConfig: {
          ...prev.settingsConfig,
          alwaysThinkingEnabled: enabled
        }
      } : prev);
      sendBridgeEvent('set_thinking_enabled', JSON.stringify({ enabled }));
      addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
      return;
    }

    setActiveProviderConfig(prev => prev ? {
      ...prev,
      settingsConfig: {
        ...prev.settingsConfig,
        alwaysThinkingEnabled: enabled
      }
    } : null);

    const payload = JSON.stringify({
      id: config.id,
      updates: {
        settingsConfig: {
          ...(config.settingsConfig || {}),
          alwaysThinkingEnabled: enabled
        }
      }
    });
    sendBridgeEvent('update_provider', payload);
    addToast(enabled ? t('toast.thinkingEnabled') : t('toast.thinkingDisabled'), 'success');
  }, [addToast, t]);

  const handleStreamingEnabledChange = useCallback((enabled: boolean) => {
    setStreamingEnabledSetting(enabled);
    const payload = { streamingEnabled: enabled };
    sendBridgeEvent('set_streaming_enabled', JSON.stringify(payload));
    addToast(enabled ? t('settings.basic.streaming.enabled') : t('settings.basic.streaming.disabled'), 'success');
  }, [t, addToast]);

  const handleSendShortcutChange = useCallback((shortcut: 'enter' | 'cmdEnter') => {
    setSendShortcut(shortcut);
    const payload = { sendShortcut: shortcut };
    sendBridgeEvent('set_send_shortcut', JSON.stringify(payload));
  }, []);

  const handleAutoOpenFileEnabledChange = useCallback((enabled: boolean) => {
    setAutoOpenFileEnabled(enabled);
    const payload = { autoOpenFileEnabled: enabled };
    sendBridgeEvent('set_auto_open_file_enabled', JSON.stringify(payload));
    addToast(enabled ? t('settings.basic.autoOpenFile.enabled') : t('settings.basic.autoOpenFile.disabled'), 'success');
  }, [t, addToast]);

  return {
    // States
    currentProvider, setCurrentProvider,
    selectedClaudeModel, setSelectedClaudeModel,
    selectedCodexModel, setSelectedCodexModel,
    defaultCodexModelFromConfig, setDefaultCodexModelFromConfig,
    codexBaseUrl, setCodexBaseUrl,
    codexUsesCustomBaseUrl, setCodexUsesCustomBaseUrl,
    claudePermissionMode, setClaudePermissionMode,
    codexPermissionMode, setCodexPermissionMode,
    permissionMode, setPermissionMode,
    reasoningEffort, setReasoningEffort,
    usagePercentage, setUsagePercentage,
    usageUsedTokens, setUsageUsedTokens,
    usageMaxTokens, setUsageMaxTokens,
    setProviderConfigVersion,
    activeProviderConfig, setActiveProviderConfig,
    claudeSettingsAlwaysThinkingEnabled, setClaudeSettingsAlwaysThinkingEnabled,
    selectedAgent, setSelectedAgent,
    streamingEnabledSetting, setStreamingEnabledSetting,
    sendShortcut, setSendShortcut,
    autoOpenFileEnabled, setAutoOpenFileEnabled,
    sdkStatus, setSdkStatus,
    sdkStatusLoaded, setSdkStatusLoaded,
    // Computed
    selectedModel,
    currentSdkInstalled,
    // Refs
    currentProviderRef,
    activeProviderConfigRef,
    shouldAdoptCodexDefaultModelRef,
    // Functions
    syncActiveProviderModelMapping,
    // Handlers
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleReasoningChange,
    handleAgentSelect,
    handleToggleThinking,
    handleStreamingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
  };
}
