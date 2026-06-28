import { useCallback, useMemo, useState, useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import type { ButtonAreaProps, ModelInfo, PermissionMode, ReasoningEffort } from './types';
import { ConfigSelect, ModelSelect, ModeSelect, ProviderSelect, ReasoningSelect } from './selectors';
import { CLAUDE_MODELS, CODEX_MODELS, createRuntimeModelInfo } from './types';
import {
  buildCodexSelectedModelKey,
  buildCodexModelCatalogKey,
  parseCodexModelCatalogKey,
  STORAGE_KEYS,
  validateCodexCustomModels,
} from '../../types/provider';
import type { CodexCustomModel, CodexModelCatalogItem, CodexProviderConfig } from '../../types/provider';
import { readClaudeModelMapping } from '../../utils/claudeModelMapping';
import { sendBridgeEvent } from '../../utils/bridge';
import {
  subscribeActiveCodexProvider,
  subscribeCodexModelCatalog,
} from '../../utils/runtimeProviderCapabilities';
import {
  getChatExecutionMode,
  getComposerUsageMode,
  resolvePermissionModeFromComposer,
  type ChatExecutionMode,
  type ComposerUsageMode,
} from './modeViewModel';

/**
 * Get custom Codex model list from localStorage
 * Uses runtime type validation for data safety
 */
function getCustomCodexModels(): ModelInfo[] {
  if (typeof window === 'undefined' || !window.localStorage) {
    return [];
  }
  try {
    const stored = window.localStorage.getItem(STORAGE_KEYS.CODEX_CUSTOM_MODELS);
    if (!stored) {
      return [];
    }
    const parsed = JSON.parse(stored);
    // Use runtime type validation
    const validModels = validateCodexCustomModels(parsed);
    return validModels.map(m => ({
      id: m.id,
      label: m.label || m.id,
      description: m.description,
    }));
  } catch {
    return [];
  }
}

/**
 * Get custom Claude model list from localStorage
 * Uses runtime type validation for data safety
 */
function getCustomClaudeModels(): ModelInfo[] {
  if (typeof window === 'undefined' || !window.localStorage) {
    return [];
  }
  try {
    const stored = window.localStorage.getItem(STORAGE_KEYS.CLAUDE_CUSTOM_MODELS);
    if (!stored) {
      return [];
    }
    const parsed = JSON.parse(stored) as CodexCustomModel[];
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed
      .filter((m): m is CodexCustomModel => !!m && typeof m === 'object' && typeof m.id === 'string' && m.id.trim().length > 0)
      .map(m => ({
        id: m.id,
        label: m.label || m.id,
        description: m.description,
      }));
  } catch {
    return [];
  }
}

/**
 * 将当前已选模型注入到模型列表顶部。
 * 这样即便当前模型来自本地 Codex 配置且尚未进入内置/自定义列表，
 * 下拉框和按钮也仍能稳定展示当前值，而不是被旧默认值顶掉。
 *
 * @param models 原始模型列表
 * @param selectedModel 当前已选模型 ID
 * @return 包含当前模型的展示列表
 */
function ensureSelectedModelVisible(models: ModelInfo[], selectedModel: string): ModelInfo[] {
  if (models.some(model => model.id === selectedModel)) {
    return models;
  }
  const runtimeModel = createRuntimeModelInfo(selectedModel);
  if (!runtimeModel) {
    return models;
  }
  return [runtimeModel, ...models];
}

function getProviderCodexModels(provider: CodexProviderConfig | null): ModelInfo[] {
  const providerModels = provider?.models && provider.models.length > 0
    ? provider.models
    : provider?.customModels;
  if (!providerModels || providerModels.length === 0) {
    return [];
  }
  return providerModels
    .filter((model): model is CodexCustomModel => !!model && typeof model.id === 'string' && model.id.trim().length > 0)
    .map((model) => ({
      id: model.id,
      label: model.label || model.id,
      description: model.description,
    }));
}

interface CodexSelectorModelInfo extends ModelInfo {
  providerId?: string;
  providerLabel?: string;
  rawModelId?: string;
  runnable?: boolean;
}

/**
 * 合并插件级自定义模型与当前 provider 模型。
 * 自定义模型代表用户在插件层显式补充的能力，应始终优先展示；若与 provider 模型同 id，
 * 则保留自定义模型，避免聊天区下拉把设置页刚保存的配置覆盖掉。
 *
 * @param customModels 插件级自定义模型
 * @param providerModels 当前 provider 返回的模型列表
 * @return 去重后的模型列表，自定义模型排在前面
 */
function mergeCustomAndProviderCodexModels(
  customModels: ModelInfo[],
  providerModels: ModelInfo[],
): ModelInfo[] {
  if (customModels.length === 0) {
    return providerModels;
  }
  const customIds = new Set(customModels.map(model => model.id));
  const filteredProviderModels = providerModels.filter(model => !customIds.has(model.id));
  return [...customModels, ...filteredProviderModels];
}

function shouldShowCodexModelConfigHint(provider: CodexProviderConfig | null): boolean {
  if (!provider || provider.id === '__codex_cli_login__') {
    return false;
  }
  return getProviderCodexModels(provider).length === 0;
}

/**
 * ButtonArea - Bottom toolbar component
 * Contains mode selector, model selector, attachment button, prompt enhancer button, send/stop button
 */
export const ButtonArea = ({
  disabled = false,
  hasInputContent = false,
  isLoading = false,
  isEnhancing = false,
  selectedModel = 'claude-sonnet-4-6',
  selectedCodexSelectionKey = '',
  defaultCodexModelFromConfig = null,
  codexBaseUrl = null,
  codexUsesCustomBaseUrl = false,
  permissionMode = 'bypassPermissions',
  currentProvider = 'claude',
  reasoningEffort = 'high',
  onSubmit,
  onStop,
  onModeSelect,
  onModelSelect,
  onProviderSelect,
  onReasoningChange,
  onEnhancePrompt,
  alwaysThinkingEnabled = false,
  onToggleThinking,
  streamingEnabled = true,
  onStreamingEnabledChange,
  selectedAgent,
  onAgentSelect,
  onOpenAgentSettings,
  onAddModel,
  onOpenCodexProviderSettings,
  onOpenCodexProviderModelManagement,
  onOpenCodexModelAliasSettings,
  longContextEnabled = true,
  onLongContextChange,
}: ButtonAreaProps) => {
  const { t } = useTranslation();
  // const fileInputRef = useRef<HTMLInputElement>(null);

  // Track changes to custom models in localStorage
  // When localStorage changes, updating this version number triggers useMemo recalculation
  const [customModelsVersion, setCustomModelsVersion] = useState(0);
  const [activeCodexProvider, setActiveCodexProvider] = useState<CodexProviderConfig | null>(null);
  const [codexModelCatalog, setCodexModelCatalog] = useState<CodexModelCatalogItem[]>([]);
  // Plan 是产品层 usage mode，不是底层具体执行模式。
  // 切到 Plan 时仍要记住用户上一次真正选择的 chat execution mode，
  // 这样再切回 Chat 时可以恢复原选择，而不是粗暴丢回 default。
  const lastNonPlanChatExecutionModeRef = useRef<ChatExecutionMode>(getChatExecutionMode(permissionMode));

  // Listen for localStorage changes (cross-tab sync + same-tab custom events)
  useEffect(() => {
    const handleStorageChange = (e: StorageEvent) => {
      if (e.key === STORAGE_KEYS.CODEX_CUSTOM_MODELS || e.key === STORAGE_KEYS.CLAUDE_MODEL_MAPPING || e.key === STORAGE_KEYS.CLAUDE_CUSTOM_MODELS) {
        setCustomModelsVersion(v => v + 1);
      }
    };

    // Listen for custom events (localStorage changes within the same tab)
    const handleCustomStorageChange = (e: CustomEvent<{ key: string }>) => {
      if (e.detail.key === STORAGE_KEYS.CODEX_CUSTOM_MODELS || e.detail.key === STORAGE_KEYS.CLAUDE_MODEL_MAPPING || e.detail.key === STORAGE_KEYS.CLAUDE_CUSTOM_MODELS) {
        setCustomModelsVersion(v => v + 1);
      }
    };

    window.addEventListener('storage', handleStorageChange);
    window.addEventListener('localStorageChange', handleCustomStorageChange as EventListener);

    return () => {
      window.removeEventListener('storage', handleStorageChange);
      window.removeEventListener('localStorageChange', handleCustomStorageChange as EventListener);
    };
  }, []);

  useEffect(() => {
    if (permissionMode !== 'plan') {
      // 只有真正处于执行模式时才刷新记忆值，避免 plan 状态把“上一次聊天执行模式”覆盖掉。
      lastNonPlanChatExecutionModeRef.current = permissionMode;
    }
  }, [permissionMode]);

  useEffect(() => {
    const unsubscribe = subscribeActiveCodexProvider((json) => {
      try {
        const provider = JSON.parse(json) as CodexProviderConfig;
        setActiveCodexProvider(provider?.id ? provider : null);
      } catch (error) {
        console.error('[ButtonArea] Failed to parse active Codex provider:', error);
      }
    });
    return unsubscribe;
  }, []);

  useEffect(() => {
    const unsubscribe = subscribeCodexModelCatalog((json) => {
      try {
        const payload = JSON.parse(json) as unknown;
        if (!Array.isArray(payload)) {
          setCodexModelCatalog([]);
          return;
        }
        setCodexModelCatalog(payload.filter((item): item is CodexModelCatalogItem => {
          if (!item || typeof item !== 'object') {
            return false;
          }
          const candidate = item as Record<string, unknown>;
          return typeof candidate.key === 'string'
            && typeof candidate.providerId === 'string'
            && typeof candidate.providerName === 'string'
            && typeof candidate.modelId === 'string'
            && typeof candidate.label === 'string'
            && typeof candidate.visible === 'boolean'
            && typeof candidate.runnable === 'boolean';
        }));
      } catch (error) {
        console.error('[ButtonArea] Failed to parse Codex model catalog:', error);
        setCodexModelCatalog([]);
      }
    });
    return unsubscribe;
  }, []);

  useEffect(() => {
    if (currentProvider === 'codex') {
      // Codex 聊天区优先消费统一 catalog，同时保留旧 active provider 回调作兜底。
      sendBridgeEvent('get_codex_model_catalog');
      sendBridgeEvent('get_active_codex_provider');
    }
  }, [currentProvider]);

  /**
   * 将后端拍平的 catalog 转成聊天区可直接消费的下拉项。
   * 这里直接把 option id 设为复合 key，确保用户选择后能把 provider + model 一次性回传给 hook。
   *
   * @param catalog 后端统一模型目录
   * @return 过滤可见项后的聊天区下拉模型列表
   */
  const buildCatalogModels = useCallback((catalog: CodexModelCatalogItem[]): CodexSelectorModelInfo[] => {
    return catalog
      .filter(item => item.visible)
      .map(item => ({
        id: item.key || buildCodexModelCatalogKey(item.providerId, item.modelId),
        label: item.label || item.modelId,
        description: item.description,
        providerId: item.providerId,
        providerLabel: item.providerName,
        rawModelId: item.modelId,
        runnable: item.runnable,
      }));
  }, []);

  /**
   * 当 catalog 尚未覆盖当前选中模型时，在列表顶部注入一个运行时兜底项。
   * 这样即使后端 catalog 尚未返回、或当前会话模型来自旧配置，也不会导致下拉按钮丢失当前值。
   *
   * @param models 当前候选模型列表
   * @param selectedCodexModelId 当前选中模型 ID
   * @param provider 当前激活 provider
   * @return 保证包含当前选中值的列表
   */
  const ensureSelectedCodexCatalogModelVisible = useCallback((
    models: CodexSelectorModelInfo[],
    selectedCodexSelectionValue: string,
    selectedCodexModelId: string,
    provider: CodexProviderConfig | null,
  ): CodexSelectorModelInfo[] => {
    const normalizedSelectionValue = selectedCodexSelectionValue.trim();
    const normalizedModelId = selectedCodexModelId.trim();
    const parsedSelection = parseCodexModelCatalogKey(normalizedSelectionValue);
    if (!normalizedSelectionValue && !normalizedModelId) {
      return models;
    }
    if (normalizedSelectionValue && models.some(model => model.id === normalizedSelectionValue)) {
      return models;
    }
    if (!normalizedSelectionValue && models.some(model => model.rawModelId === normalizedModelId || model.id === normalizedModelId)) {
      return models;
    }
    const runtimeModel = createRuntimeModelInfo(normalizedModelId || normalizedSelectionValue);
    if (!runtimeModel) {
      return models;
    }
    return [{
      ...runtimeModel,
      id: normalizedSelectionValue || buildCodexSelectedModelKey(provider?.id, normalizedModelId),
      providerId: parsedSelection?.providerId || provider?.id,
      providerLabel: provider?.name,
      rawModelId: normalizedModelId || runtimeModel.id,
      runnable: true,
    }, ...models];
  }, []);

  /**
   * Apply model name mapping
   * Maps base model IDs to actual model names (e.g., versions with capacity suffixes)
   */
  const applyModelMapping = useCallback((model: ModelInfo, mapping: { main?: string; haiku?: string; sonnet?: string; opus?: string }): ModelInfo => {
    const modelKeyMap: Record<string, keyof typeof mapping> = {
      'claude-sonnet-4-6': 'sonnet',
      'claude-opus-4-7': 'opus',
      'claude-haiku-4-5': 'haiku',
    };

    const key = modelKeyMap[model.id];
    const resolvedMapping = (key ? mapping[key] : undefined) || mapping.main;
    if (resolvedMapping) {
      const actualModel = String(resolvedMapping).trim();
      if (actualModel.length > 0) {
        // Keep the original id as unique identifier, only modify label to custom name
        // This ensures id remains unique even if multiple models share the same displayName
        return { ...model, label: actualModel };
      }
    }
    return model;
  }, []);

  // Select model list based on current provider
  // customModelsVersion triggers recalculation when localStorage changes
  const availableModels = useMemo(() => {
    if (currentProvider === 'codex') {
      const catalogModels = buildCatalogModels(codexModelCatalog);
      if (catalogModels.length > 0) {
        return ensureSelectedCodexCatalogModelVisible(
          catalogModels,
          selectedCodexSelectionKey,
          selectedModel,
          activeCodexProvider,
        );
      }
      const providerModels = getProviderCodexModels(activeCodexProvider);
      if (providerModels.length > 0) {
        const customModels = getCustomCodexModels();
        // 插件级自定义模型不应因当前激活了托管 provider 就从模型下拉中消失。
        return ensureSelectedModelVisible(
          mergeCustomAndProviderCodexModels(customModels, providerModels),
          selectedModel,
        );
      }
      if (activeCodexProvider?.id) {
        // 修改原因：managed provider 空模型时展示明确引导，避免回退内置列表掩盖配置缺口。
        return [];
      }
      const customModels = getCustomCodexModels();
      if (customModels.length === 0) {
        return ensureSelectedModelVisible(CODEX_MODELS, selectedModel);
      }
      // Custom models first, built-in models after
      // Filter out built-in models that duplicate custom models
      const customIds = new Set(customModels.map(m => m.id));
      const filteredBuiltIn = CODEX_MODELS.filter(m => !customIds.has(m.id));
      return ensureSelectedModelVisible([...customModels, ...filteredBuiltIn], selectedModel);
    }
    if (typeof window === 'undefined' || !window.localStorage) {
      return CLAUDE_MODELS;
    }

    // Apply model mapping to built-in models
    let builtInModels = CLAUDE_MODELS;
    try {
      const mapping = readClaudeModelMapping();
      if (Object.keys(mapping).length > 0) {
        builtInModels = CLAUDE_MODELS.map((m) => applyModelMapping(m, mapping));
      }
    } catch {
      // ignore
    }

    // Merge custom models (displayed before built-in models)
    const customModels = getCustomClaudeModels();
    if (customModels.length === 0) {
      return builtInModels;
    }
    // Filter out built-in models that duplicate custom models
    const customIds = new Set(customModels.map(m => m.id));
    const filteredBuiltIn = builtInModels.filter(m => !customIds.has(m.id));
    return [...customModels, ...filteredBuiltIn];
  }, [
    activeCodexProvider,
    applyModelMapping,
    buildCatalogModels,
    codexModelCatalog,
    currentProvider,
    customModelsVersion,
    ensureSelectedCodexCatalogModelVisible,
    selectedCodexSelectionKey,
    selectedModel,
  ]);

  const codexModelConfigHintVisible = useMemo(
    () => currentProvider === 'codex' && shouldShowCodexModelConfigHint(activeCodexProvider),
    [activeCodexProvider, currentProvider]
  );

  /**
   * Handle submit button click
   */
  const handleSubmitClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onSubmit?.();
  }, [onSubmit]);

  /**
   * Handle stop button click
   */
  const handleStopClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onStop?.();
  }, [onStop]);

  /**
   * Handle mode selection
   */
  const handleModeSelect = useCallback((mode: PermissionMode) => {
    const selectedChatMode = mode as ChatExecutionMode;
    lastNonPlanChatExecutionModeRef.current = selectedChatMode;
    // 执行模式下拉只负责切换 default / acceptEdits / bypassPermissions，
    // 最终仍要和当前 usage mode 重新组合成统一的 permissionMode。
    const nextMode = resolvePermissionModeFromComposer(getComposerUsageMode(permissionMode), selectedChatMode);
    onModeSelect?.(nextMode);
  }, [onModeSelect, permissionMode]);

  const usageMode = getComposerUsageMode(permissionMode);
  const chatExecutionMode = getChatExecutionMode(permissionMode, lastNonPlanChatExecutionModeRef.current);
  const showComposerModeToggle = currentProvider !== 'codex';

  const handleUsageModeSelect = useCallback((mode: ComposerUsageMode) => {
    // 顶层 Chat/Plan 切换不直接修改“上一次 chat 模式记忆”，
    // 只把 usage mode 与当前执行模式重新组合，避免用户切换计划模式后丢失偏好。
    const nextMode = resolvePermissionModeFromComposer(mode, chatExecutionMode);
    onModeSelect?.(nextMode);
  }, [chatExecutionMode, onModeSelect]);

  /**
   * Handle model selection
   */
  const handleModelSelect = useCallback((modelId: string) => {
    onModelSelect?.(modelId);
  }, [onModelSelect]);

  /**
   * Handle provider selection
   */
  const handleProviderSelect = useCallback((providerId: string) => {
    onProviderSelect?.(providerId);
  }, [onProviderSelect]);

  /**
   * Handle reasoning depth selection
   */
  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    onReasoningChange?.(effort);
  }, [onReasoningChange]);

  /**
   * Handle enhance prompt button click
   */
  const handleEnhanceClick = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    onEnhancePrompt?.();
  }, [onEnhancePrompt]);

  return (
    <div className="button-area" data-provider={currentProvider}>
      {/* Left side: selectors */}
      <div className="button-area-left">
        <ConfigSelect
          alwaysThinkingEnabled={alwaysThinkingEnabled}
          onToggleThinking={onToggleThinking}
          streamingEnabled={streamingEnabled}
          onStreamingEnabledChange={onStreamingEnabledChange}
          selectedAgent={selectedAgent}
          onAgentSelect={onAgentSelect}
          onOpenAgentSettings={onOpenAgentSettings}
        />
        <ProviderSelect
          value={currentProvider}
          onChange={handleProviderSelect}
          compact
        />
        {showComposerModeToggle && (
          <div className="composer-mode-toggle" role="tablist" aria-label={t('chat.composerMode', { defaultValue: 'Composer mode' })}>
            <button
              type="button"
              aria-pressed={usageMode === 'chat'}
              onClick={() => handleUsageModeSelect('chat')}
            >
              {t('chat.chatMode', { defaultValue: 'Chat' })}
            </button>
            <button
              type="button"
              aria-pressed={usageMode === 'plan'}
              onClick={() => handleUsageModeSelect('plan')}
              title={t('chat.planMode', { defaultValue: 'Plan mode' })}
            >
              {t('chat.planModeLabel', { defaultValue: 'Plan' })}
            </button>
          </div>
        )}
        <ModeSelect value={chatExecutionMode} onChange={handleModeSelect} provider={currentProvider} />
        <ModelSelect
          value={currentProvider === 'codex' ? (selectedCodexSelectionKey || selectedModel) : selectedModel}
          selectedCodexSelectionKey={currentProvider === 'codex' ? selectedCodexSelectionKey : undefined}
          onChange={handleModelSelect}
          models={availableModels}
          currentProvider={currentProvider}
          onAddModel={onAddModel}
          onOpenCodexProviderSettings={onOpenCodexProviderSettings}
          onOpenCodexProviderModelManagement={onOpenCodexProviderModelManagement}
          onOpenCodexModelAliasSettings={onOpenCodexModelAliasSettings}
          defaultCodexModelFromConfig={currentProvider === 'codex' ? defaultCodexModelFromConfig : null}
          codexBaseUrl={currentProvider === 'codex' ? codexBaseUrl : null}
          codexUsesCustomBaseUrl={currentProvider === 'codex' ? codexUsesCustomBaseUrl : false}
          longContextEnabled={currentProvider === 'claude' ? longContextEnabled : false}
          onLongContextChange={currentProvider === 'claude' ? onLongContextChange : undefined}
        />
        {codexModelConfigHintVisible && (
          <div className="toolbar-inline-hint" role="status">
            {t('chat.codexModelConfigRequired')}
          </div>
        )}
        {currentProvider === 'codex' && (
          <ReasoningSelect
            value={reasoningEffort}
            onChange={handleReasoningChange}
            selectedModel={selectedModel}
            currentProvider={currentProvider}
          />
        )}
      </div>

      {/* Right side: tool buttons */}
      <div className="button-area-right">
        <div className="button-divider" />

        {/* Enhance prompt button */}
        <button
          className="enhance-prompt-button has-tooltip"
          onClick={handleEnhanceClick}
          disabled={disabled || !hasInputContent || isLoading || isEnhancing}
          data-tooltip={`${t('promptEnhancer.tooltip')} (${t('promptEnhancer.shortcut')})`}
        >
          <span className={`codicon ${isEnhancing ? 'codicon-loading codicon-modifier-spin' : 'codicon-sparkle'}`} />
        </button>

        {/* Send/Stop button */}
        {isLoading ? (
          <button
            className="submit-button stop-button"
            onClick={handleStopClick}
            title={t('chat.stopGeneration')}
          >
            <span className="codicon codicon-debug-stop" />
          </button>
        ) : (
          <button
            className="submit-button"
            onClick={handleSubmitClick}
            disabled={disabled || !hasInputContent}
            title={t('chat.sendMessageEnter')}
          >
            <span className="codicon codicon-send" />
          </button>
        )}
      </div>
    </div>
  );
};

export default ButtonArea;
