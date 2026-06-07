import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  AVAILABLE_MODELS,
  modelSupports1MContext,
  normalizeClaudeModelId,
  strip1MContextSuffix,
} from '../types';
import type { ModelInfo } from '../types';
import { parseCodexModelCatalogKey } from '../../../types/provider';
import { readClaudeModelMapping } from '../../../utils/claudeModelMapping';
import { ProviderModelIcon } from '../../shared/ProviderModelIcon';
import Switch from 'antd/es/switch';

const RELATIVE_INLINE_BLOCK_STYLE: React.CSSProperties = { position: 'relative', display: 'inline-block' };
const CHEVRON_ICON_STYLE: React.CSSProperties = { fontSize: '10px', marginLeft: '2px' };
const DROPDOWN_STYLE: React.CSSProperties = {
  position: 'absolute',
  bottom: '100%',
  left: 0,
  marginBottom: '4px',
  zIndex: 10000,
};
const MODEL_OPTION_INFO_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', flex: 1 };
const LONG_CONTEXT_OPTION_STYLE: React.CSSProperties = { justifyContent: 'space-between', cursor: 'default' };
const LONG_CONTEXT_LABEL_STYLE: React.CSSProperties = { fontSize: '12px' };
const PROVIDER_TAG_STYLE: React.CSSProperties = {
  marginLeft: '6px',
  opacity: 0.72,
  fontSize: '11px',
};
const EMPTY_MODEL_FALLBACK: ModelInfo = {
  id: '__empty_model__',
  label: 'No model configured',
};

interface SelectorModelInfo extends ModelInfo {
  providerId?: string;
  providerLabel?: string;
  rawModelId?: string;
  runnable?: boolean;
}

interface ModelSelectProps {
  value: string;
  onChange: (modelId: string) => void;
  models?: SelectorModelInfo[];
  currentProvider?: string;
  onAddModel?: () => void;
  onOpenCodexProviderSettings?: () => void;
  onOpenCodexProviderModelManagement?: () => void;
  onOpenCodexModelAliasSettings?: () => void;
  defaultCodexModelFromConfig?: string | null;
  codexBaseUrl?: string | null;
  codexUsesCustomBaseUrl?: boolean;
  longContextEnabled?: boolean;
  onLongContextChange?: (enabled: boolean) => void;
}

const DEFAULT_MODEL_MAP: Record<string, ModelInfo> = AVAILABLE_MODELS.reduce(
  (acc, model) => {
    acc[model.id] = model;
    return acc;
  },
  {} as Record<string, ModelInfo>,
);

const MODEL_LABEL_KEYS: Record<string, string> = {
  'claude-sonnet-4-6': 'models.claude.sonnet46.label',
  'claude-opus-4-7': 'models.claude.opus46.label',
  'claude-opus-4-6': 'models.claude.opus46_1m.label',
  'claude-opus-4-6[1m]': 'models.claude.opus46_1m.label',
  'claude-haiku-4-5': 'models.claude.haiku45.label',
  'gpt-5.5': 'models.codex.gpt55.label',
  'gpt-5.4': 'models.codex.gpt54.label',
  'gpt-5.2-codex': 'models.codex.gpt52codex.label',
  'gpt-5.1-codex-max': 'models.codex.gpt51codexMax.label',
  'gpt-5.4-mini': 'models.codex.gpt54mini.label',
  'gpt-5.3-codex': 'models.codex.gpt53codex.label',
  'gpt-5.3-codex-spark': 'models.codex.gpt53codexSpark.label',
  'gpt-5.2': 'models.codex.gpt52.label',
  'gpt-5.1-codex-mini': 'models.codex.gpt51codexMini.label',
};

const MODEL_DESCRIPTION_KEYS: Record<string, string> = {
  'claude-sonnet-4-6': 'models.claude.sonnet46.description',
  'claude-opus-4-7': 'models.claude.opus46.description',
  'claude-opus-4-6': 'models.claude.opus46_1m.description',
  'claude-opus-4-6[1m]': 'models.claude.opus46_1m.description',
  'claude-haiku-4-5': 'models.claude.haiku45.description',
  'gpt-5.5': 'models.codex.gpt55.description',
  'gpt-5.4': 'models.codex.gpt54.description',
  'gpt-5.2-codex': 'models.codex.gpt52codex.description',
  'gpt-5.1-codex-max': 'models.codex.gpt51codexMax.description',
  'gpt-5.4-mini': 'models.codex.gpt54mini.description',
  'gpt-5.3-codex': 'models.codex.gpt53codex.description',
  'gpt-5.3-codex-spark': 'models.codex.gpt53codexSpark.description',
  'gpt-5.2': 'models.codex.gpt52.description',
  'gpt-5.1-codex-mini': 'models.codex.gpt51codexMini.description',
};

/**
 * 将 Claude 模型 ID 映射到本地 model mapping 的键名。
 * 旧版 Opus 4.6 与 1M 变体共用同一映射桶，这样可以兼容上游新命名和当前主线的历史缓存。
 */
const MODEL_ID_TO_MAPPING_KEY: Record<string, string> = {
  'claude-sonnet-4-6': 'sonnet',
  'claude-opus-4-7': 'opus',
  'claude-opus-4-6': 'opus',
  'claude-opus-4-6[1m]': 'opus',
  'claude-haiku-4-5': 'haiku',
};

/**
 * 从本地 Claude provider 配置映射中解析真实模型名。
 *
 * @param mappingKey 目标映射键
 * @param modelMapping 本地映射表
 * @return 可展示的真实模型名；没有命中时返回 undefined
 */
const resolveMappedModelName = (
  mappingKey: string | undefined,
  modelMapping: Record<string, string | undefined>,
): string | undefined => {
  if (!mappingKey) {
    return modelMapping.main?.trim() || undefined;
  }

  const mapped = modelMapping[mappingKey]
    || (mappingKey === 'opus_1m' ? modelMapping.opus : undefined)
    || modelMapping.main;

  return mapped?.trim() || undefined;
};

/**
 * 解析供图标匹配使用的模型名。
 * Claude 映射模型优先使用 provider 配置中的真实模型名，其他 provider 直接回退到原始 ID。
 *
 * @param modelId 当前模型 ID
 * @param modelMapping 本地映射表
 * @param mappingKeyMap 模型与映射键的对照表
 * @return 供图标组件使用的模型名
 */
const resolveModelIdForIcon = (
  modelId: string,
  modelMapping: Record<string, string | undefined>,
  mappingKeyMap: Record<string, string>,
): string => {
  const mappingKey = mappingKeyMap[modelId];
  if (!mappingKey) {
    return modelId;
  }
  const mapped = resolveMappedModelName(mappingKey, modelMapping);
  return mapped || modelId;
};

/**
 * 模型选择器。
 * 该组件同时承载两类并轨能力：
 * 1. upstream 的 Claude long-context 切换与更完整的 Codex 模型清单；
 * 2. 当前主线的 Codex CLI 默认模型提示与 custom base_url 警告。
 *
 * @param value 当前模型 ID
 * @param onChange 模型切换回调
 * @param models 可选模型列表
 * @param currentProvider 当前 provider
 * @param onAddModel 打开模型管理入口
 * @param defaultCodexModelFromConfig CLI 默认模型，仅展示
 * @param codexBaseUrl CLI base_url，仅展示
 * @param codexUsesCustomBaseUrl 是否使用非官方 base_url
 * @param longContextEnabled Claude 长上下文开关
 * @param onLongContextChange 切换 Claude 长上下文
 */
export const ModelSelect = ({
  value,
  onChange,
  models = AVAILABLE_MODELS,
  currentProvider = 'claude',
  onAddModel,
  onOpenCodexProviderSettings,
  onOpenCodexProviderModelManagement,
  onOpenCodexModelAliasSettings,
  defaultCodexModelFromConfig = null,
  codexBaseUrl = null,
  codexUsesCustomBaseUrl = false,
  longContextEnabled = true,
  onLongContextChange,
}: ModelSelectProps) => {
  const { t } = useTranslation();
  const [isOpen, setIsOpen] = useState(false);
  const [showCodexManagementActions, setShowCodexManagementActions] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);

  const parsedCompositeValue = parseCodexModelCatalogKey(value);
  const strippedValue = strip1MContextSuffix(parsedCompositeValue?.modelId ?? value);
  const normalizedValue = currentProvider === 'claude' ? normalizeClaudeModelId(strippedValue) : strippedValue;
  const currentModel = models.find(m => m.id === value)
    || models.find(m => m.rawModelId === normalizedValue)
    || models.find(m => m.id === normalizedValue)
    || models.find(m => m.id === strippedValue)
    || models[0]
    || EMPTY_MODEL_FALLBACK;
  const modelMapping = readClaudeModelMapping();
  const normalizedDefaultCodexModel = typeof defaultCodexModelFromConfig === 'string'
    ? defaultCodexModelFromConfig.trim()
    : '';
  const normalizedCodexBaseUrl = typeof codexBaseUrl === 'string'
    ? codexBaseUrl.trim()
    : '';
  const shouldShowCodexDefaultHint = currentProvider === 'codex' && normalizedDefaultCodexModel.length > 0;
  const shouldShowCodexBaseUrlWarning = currentProvider === 'codex'
    && codexUsesCustomBaseUrl
    && normalizedCodexBaseUrl.length > 0;

  const isSelectedModel = (modelId: string): boolean => {
    if (currentProvider !== 'claude') {
      const parsedOption = parseCodexModelCatalogKey(modelId);
      const parsedCurrent = parseCodexModelCatalogKey(value);
      if (parsedOption && parsedCurrent) {
        return parsedOption.providerId === parsedCurrent.providerId
          && parsedOption.modelId === parsedCurrent.modelId;
      }
      if (parsedOption) {
        return parsedOption.modelId === strippedValue;
      }
      return modelId === value || modelId === strippedValue;
    }
    return normalizeClaudeModelId(modelId) === normalizedValue;
  };

  /**
   * 解析模型项的原始 modelId。
   * 对 Codex catalog 场景，按钮 value 使用复合 key，但图标和描述翻译仍然应基于真实 modelId。
   *
   * @param model 当前模型项
   * @return 真实模型 ID
   */
  const getRawModelId = (model: SelectorModelInfo): string => {
    return model.rawModelId || parseCodexModelCatalogKey(model.id)?.modelId || model.id;
  };

  /**
   * 判断当前模型项是否允许用户直接选择。
   * 用于兼容 CLI 未授权等“可见但不可运行”的 catalog 项。
   *
   * @param model 当前模型项
   * @return true 表示可选；false 表示应禁用
   */
  const isRunnableModel = (model: SelectorModelInfo): boolean => {
    return model.runnable !== false;
  };

  /**
   * 根据 provider 和长上下文状态生成模型标签。
   *
   * @param model 模型定义
   * @param show1MContext 是否追加 1M context 提示
   * @return 供 UI 展示的模型标签
   */
  const getModelLabel = (model: SelectorModelInfo, show1MContext = false): string => {
    const rawModelId = getRawModelId(model);
    const mappingKey = MODEL_ID_TO_MAPPING_KEY[rawModelId];
    if (mappingKey) {
      const mappedName = resolveMappedModelName(mappingKey, modelMapping);
      if (mappedName) {
        return append1MContextSuffix(mappedName, rawModelId, show1MContext);
      }
    }

    const defaultModel = DEFAULT_MODEL_MAP[rawModelId];
    const labelKey = MODEL_LABEL_KEYS[rawModelId];
    const hasCustomLabel = defaultModel && model.label && model.label !== defaultModel.label;

    if (hasCustomLabel) {
      return append1MContextSuffix(model.label ?? '', rawModelId, show1MContext);
    }

    if (labelKey) {
      return append1MContextSuffix(t(labelKey), rawModelId, show1MContext);
    }

    return append1MContextSuffix(model.label ?? '', rawModelId, show1MContext);
  };

  /**
   * 为支持 1M context 的 Claude 模型附加短标签。
   *
   * @param label 原始标签
   * @param modelId 模型 ID
   * @param show1MContext 是否显示 1M 标签
   * @return 最终展示标签
   */
  const append1MContextSuffix = (label: string, modelId: string, show1MContext: boolean): string => {
    if (show1MContext && modelSupports1MContext(modelId) && longContextEnabled) {
      return `${label} (${t('models.longContext.shortLabel')})`;
    }
    return label;
  };

  /**
   * 解析模型描述文案。
   *
   * @param model 模型定义
   * @return 描述文案；没有时返回 undefined
   */
  const getModelDescription = (model: SelectorModelInfo): string | undefined => {
    const descriptionKey = MODEL_DESCRIPTION_KEYS[getRawModelId(model)];
    return descriptionKey ? t(descriptionKey) : model.description;
  };

  const handleToggle = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    setIsOpen(!isOpen);
    if (isOpen) {
      setShowCodexManagementActions(false);
    }
  }, [isOpen]);

  const handleSelect = useCallback((modelId: string) => {
    onChange(modelId);
    setIsOpen(false);
    setShowCodexManagementActions(false);
  }, [onChange]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const handleClickOutside = (e: MouseEvent) => {
      if (
        dropdownRef.current &&
        !dropdownRef.current.contains(e.target as Node) &&
        buttonRef.current &&
        !buttonRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
        setShowCodexManagementActions(false);
      }
    };

    const timer = setTimeout(() => {
      document.addEventListener('mousedown', handleClickOutside);
    }, 0);

    return () => {
      clearTimeout(timer);
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  /**
   * Codex 的“添加模型”入口需要先显式区分动作语义。
   * 这里不再直接触发旧的别名弹窗，而是先展示 provider 创建/管理/别名三个独立动作。
   */
  const handleOpenAddModelActions = useCallback(() => {
    if (currentProvider === 'codex') {
      setShowCodexManagementActions(true);
      return;
    }
    onAddModel?.();
    setIsOpen(false);
  }, [currentProvider, onAddModel]);

  const runCodexManagementAction = useCallback((action?: () => void) => {
    action?.();
    setShowCodexManagementActions(false);
    setIsOpen(false);
  }, []);

  return (
    <div style={RELATIVE_INLINE_BLOCK_STYLE}>
      <button
        ref={buttonRef}
        className="selector-button"
        onClick={handleToggle}
        title={shouldShowCodexDefaultHint
          ? t('chat.currentModelWithCliDefault', {
            model: getModelLabel(currentModel, true),
            defaultModel: normalizedDefaultCodexModel,
            defaultValue: `Current model: ${getModelLabel(currentModel, true)} | CLI default: ${normalizedDefaultCodexModel}`,
          })
          : t('chat.currentModel', { model: getModelLabel(currentModel, true) })}
      >
        <ProviderModelIcon
          providerId={currentProvider}
          modelId={resolveModelIdForIcon(getRawModelId(currentModel), modelMapping, MODEL_ID_TO_MAPPING_KEY)}
          size={12}
          colored
        />
        <span className="selector-button-text">{getModelLabel(currentModel, true)}</span>
        {currentProvider === 'codex' && currentModel.providerLabel && (
          <span className="selector-button-meta" style={PROVIDER_TAG_STYLE}>
            {currentModel.providerLabel}
          </span>
        )}
        {shouldShowCodexDefaultHint && normalizedDefaultCodexModel !== currentModel.id && (
          <span
            className="selector-button-meta"
            style={{ marginLeft: '6px', opacity: 0.7, fontSize: '11px' }}
            title={t('chat.codexCliDefaultModelHint', {
              defaultModel: normalizedDefaultCodexModel,
              defaultValue: `CLI default: ${normalizedDefaultCodexModel}`,
            })}
          >
            {t('chat.codexCliDefaultModelBadge', {
              defaultModel: normalizedDefaultCodexModel,
              defaultValue: `CLI ${normalizedDefaultCodexModel}`,
            })}
          </span>
        )}
        <span className={`codicon codicon-chevron-${isOpen ? 'up' : 'down'}`} style={CHEVRON_ICON_STYLE} />
      </button>

      {isOpen && (
        <div
          ref={dropdownRef}
          className="selector-dropdown"
          style={DROPDOWN_STYLE}
        >
          {shouldShowCodexBaseUrlWarning && (
            <div
              className="selector-option"
              style={{
                cursor: 'default',
                borderBottom: '1px solid var(--vscode-widget-border, rgba(255,255,255,0.08))',
                background: 'var(--vscode-inputValidation-warningBackground, rgba(191, 140, 0, 0.12))',
                color: 'var(--vscode-inputValidation-warningForeground, inherit)',
              }}
              title={t('chat.codexCustomBaseUrlWarning', {
                baseUrl: normalizedCodexBaseUrl,
                defaultValue: `Custom OpenAI base URL: ${normalizedCodexBaseUrl}. Model selection may not be fully supported by the upstream service.`,
              })}
            >
              <span className="codicon codicon-warning" style={{ color: 'var(--vscode-editorWarning-foreground, #d7ba7d)' }} />
              <div style={{ display: 'flex', flexDirection: 'column', flex: 1 }}>
                <span>{t('chat.codexCustomBaseUrlWarningTitle', { defaultValue: 'Custom OpenAI base URL' })}</span>
                <span className="model-description">
                  {t('chat.codexCustomBaseUrlWarningDescription', {
                    baseUrl: normalizedCodexBaseUrl,
                    defaultValue: `${normalizedCodexBaseUrl} may not support all model selections.`,
                  })}
                </span>
              </div>
            </div>
          )}
          {!showCodexManagementActions && models.map((model) => (
            <div
              key={model.id}
              className={`selector-option ${isSelectedModel(model.id) ? 'selected' : ''}`}
              onClick={() => {
                if (!isRunnableModel(model)) {
                  return;
                }
                handleSelect(model.id);
              }}
              aria-disabled={!isRunnableModel(model)}
              style={!isRunnableModel(model) ? { opacity: 0.55, cursor: 'not-allowed' } : undefined}
            >
              <ProviderModelIcon
                providerId={currentProvider}
                modelId={resolveModelIdForIcon(getRawModelId(model), modelMapping, MODEL_ID_TO_MAPPING_KEY)}
                size={16}
                colored
              />
              <div style={MODEL_OPTION_INFO_STYLE}>
                <span>
                  {getModelLabel(model, false)}
                  {currentProvider === 'codex' && model.providerLabel && (
                    <span style={PROVIDER_TAG_STYLE}>{model.providerLabel}</span>
                  )}
                </span>
                {getModelDescription(model) && (
                  <span className="model-description">{getModelDescription(model)}</span>
                )}
                {currentProvider === 'codex' && !isRunnableModel(model) && (
                  <span className="model-description">
                    {t('chat.codexModelUnavailable', { defaultValue: 'Unavailable until provider authorization is complete.' })}
                  </span>
                )}
                {shouldShowCodexDefaultHint && getRawModelId(model) === normalizedDefaultCodexModel && (
                  <span className="model-description">
                    {t('chat.codexCliDefaultModelOption', {
                      defaultValue: 'CLI default model',
                    })}
                  </span>
                )}
              </div>
              {isSelectedModel(model.id) && (
                <span className="codicon codicon-check check-mark" />
              )}
            </div>
          ))}
          {showCodexManagementActions && currentProvider === 'codex' && (
            <>
              <div
                className="selector-option selector-option-add"
                onClick={() => runCodexManagementAction(onOpenCodexProviderSettings)}
              >
                <span className="codicon codicon-cloud-upload selector-add-icon" />
                <span>{t('chat.addCodexProviderAction')}</span>
              </div>
              <div
                className="selector-option selector-option-add"
                onClick={() => runCodexManagementAction(onOpenCodexProviderModelManagement)}
              >
                <span className="codicon codicon-settings-gear selector-add-icon" />
                <span>{t('chat.manageCurrentCodexProviderModelsAction')}</span>
              </div>
              <div
                className="selector-option selector-option-add"
                onClick={() => runCodexManagementAction(onOpenCodexModelAliasSettings)}
              >
                <span className="codicon codicon-symbol-misc selector-add-icon" />
                <span>{t('chat.addCodexModelAliasAction')}</span>
              </div>
            </>
          )}
          {currentProvider === 'claude' && onLongContextChange && (
            <>
              <div className="selector-divider" />
              <div
                className="selector-option"
                style={LONG_CONTEXT_OPTION_STYLE}
                onClick={(e) => e.stopPropagation()}
              >
                <span style={LONG_CONTEXT_LABEL_STYLE}>{t('models.longContext.shortLabel')}</span>
                <Switch
                  size="small"
                  checked={modelSupports1MContext(value) ? longContextEnabled : false}
                  disabled={!modelSupports1MContext(value)}
                  onChange={onLongContextChange}
                />
              </div>
            </>
          )}
          {onAddModel && (
            <>
              <div className="selector-divider" />
              <div
                className="selector-option selector-option-add"
                onClick={handleOpenAddModelActions}
              >
                <span className="codicon codicon-add selector-add-icon" />
                <span>{t('models.addModel')}</span>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  );
};

export default ModelSelect;
