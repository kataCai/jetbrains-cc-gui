import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CodexAuthMode,
  CodexCustomModel,
  CodexProviderConfig,
  CodexRequestMode,
} from '../types/provider';
import { validateCodexCustomModels } from '../types/provider';

const GRID_STYLE: React.CSSProperties = { display: 'grid', gap: '12px' };
const FOOTER_ACTIONS_STYLE: React.CSSProperties = { marginLeft: 'auto', display: 'flex', gap: '8px' };
const INLINE_ACTION_STYLE: React.CSSProperties = { display: 'flex', gap: '8px', alignItems: 'center' };
const MODEL_ROW_STYLE: React.CSSProperties = {
  display: 'grid',
  gap: '8px',
  gridTemplateColumns: 'minmax(0, 1.2fr) minmax(0, 1fr) minmax(0, 1.2fr) auto',
  alignItems: 'start',
};
const MODEL_HEADER_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: '6px' };
const FORM_LINK_STYLE: React.CSSProperties = { fontSize: '12px', color: 'var(--button-primary-background, #0078d4)' };

const AUTH_MODE_OPTIONS: CodexAuthMode[] = ['api_key', 'api_key_env', 'codex_cli_login', 'proxy', 'oauth'];
const REQUEST_MODE_OPTIONS: CodexRequestMode[] = ['codex_sdk', 'cc_switch_proxy', 'custom_adapter'];

interface CodexProviderPreset {
  id: string;
  providerType: string;
  providerName: string;
  websiteUrl: string;
  apiKeyApplyUrl: string;
  baseUrl: string;
  requestMode: CodexRequestMode;
  authMode: CodexAuthMode;
  models: CodexCustomModel[];
}

const CODEX_PROVIDER_PRESETS: CodexProviderPreset[] = [
  {
    id: 'custom_gateway',
    providerType: 'custom_gateway',
    providerName: '',
    websiteUrl: '',
    apiKeyApplyUrl: '',
    baseUrl: '',
    requestMode: 'codex_sdk',
    authMode: 'api_key',
    models: [],
  },
  {
    id: 'minimax',
    providerType: 'minimax',
    providerName: 'MiniMax',
    websiteUrl: 'https://platform.minimaxi.com',
    apiKeyApplyUrl: 'https://platform.minimaxi.com/user-center/basic-information/interface-key',
    baseUrl: 'https://api.minimaxi.com/v1',
    requestMode: 'codex_sdk',
    authMode: 'api_key',
    models: [
      {
        id: 'MiniMax-M2.5',
        label: 'MiniMax-M2.5',
      },
    ],
  },
];

function createEmptyModelRow(): CodexCustomModel {
  return {
    id: '',
    label: '',
    description: '',
  };
}

/**
 * 将运行时模型定义转换成适合表单编辑的行数据。
 * 当 label 与 id 相同时时，编辑态不重复回填显示名称，避免形成两个完全相同的输入值。
 */
function createEditableModelRow(model: CodexCustomModel): CodexCustomModel {
  return {
    id: model.id || '',
    label: model.label && model.label !== model.id ? model.label : '',
    description: model.description || '',
    reasoningEffort: model.reasoningEffort,
  };
}

/**
 * 判断模型行是否仍然是“纯空白占位行”。
 * 该判断用于阻止用户在尚未填写当前空行时继续无限追加同类空行。
 */
function isEmptyModelRow(model: CodexCustomModel): boolean {
  return !model.id?.trim() && !model.label?.trim() && !model.description?.trim();
}

function maskApiKey(value?: string): string {
  const trimmedValue = value?.trim() || '';
  if (trimmedValue.length <= 8) {
    return trimmedValue ? '******' : '';
  }
  return `${trimmedValue.slice(0, 4)}******${trimmedValue.slice(-4)}`;
}

interface CodexProviderDialogProps {
  isOpen: boolean;
  provider?: CodexProviderConfig | null;
  initialProviderData?: Partial<CodexProviderConfig> | null;
  onClose: () => void;
  onSave: (provider: CodexProviderConfig) => void;
  addToast: (message: string, type: 'success' | 'error' | 'info') => void;
}

/**
 * 将当前结构化模型列表序列化为高级 JSON 文本。
 * 这里只同步真正有效的模型条目，避免把空占位行也写入 JSON 编辑器。
 *
 * @param models 当前结构化模型列表
 * @return 供高级 JSON 编辑器展示的格式化文本
 */
function serializeModelsJson(models: CodexCustomModel[]): string {
  return JSON.stringify(
    models
      .map((model) => ({
        ...model,
        id: model.id.trim(),
        label: (model.label || model.id).trim(),
        description: model.description?.trim() || undefined,
      }))
      .filter((model) => model.id.length > 0),
    null,
    2,
  );
}

/**
 * Codex provider 结构化配置弹窗。
 * 该弹窗负责创建和编辑“可运行的 provider 配置”，显式维护 provider/preset/baseUrl/apiKey/models 这一整组运行参数，
 * 不再把模型列表退化成单纯 JSON 文本框，避免用户误以为“添加模型别名”就等于“接入一个新模型供应商”。
 */
export default function CodexProviderDialog({
  isOpen,
  provider,
  initialProviderData,
  onClose,
  onSave,
  addToast,
}: CodexProviderDialogProps) {
  const { t } = useTranslation();
  const isAdding = !provider;
  const [providerPreset, setProviderPreset] = useState('custom_gateway');
  const [providerName, setProviderName] = useState('');
  const [remark, setRemark] = useState('');
  const [websiteUrl, setWebsiteUrl] = useState('');
  const [apiKeyApplyUrl, setApiKeyApplyUrl] = useState('');
  const [authMode, setAuthMode] = useState<CodexAuthMode>('api_key');
  const [requestMode, setRequestMode] = useState<CodexRequestMode>('codex_sdk');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [apiKeyEnv, setApiKeyEnv] = useState('');
  const [models, setModels] = useState<CodexCustomModel[]>([createEmptyModelRow()]);
  const [showApiKey, setShowApiKey] = useState(false);
  const [showAdvancedJsonEditor, setShowAdvancedJsonEditor] = useState(false);
  const [modelsJsonText, setModelsJsonText] = useState('[]');

  const selectedPreset = useMemo(
    () => CODEX_PROVIDER_PRESETS.find((preset) => preset.id === providerPreset),
    [providerPreset],
  );
  const maskedApiKey = useMemo(() => maskApiKey(apiKey), [apiKey]);

  /**
   * 将 provider 数据投影到结构化表单。
   * 新增场景默认落到 custom gateway；编辑场景优先回显已有 preset/providerType。
   */
  useEffect(() => {
    if (!isOpen) {
      return;
    }
    if (provider) {
      setProviderPreset(provider.presetId || provider.providerType || 'custom_gateway');
      setProviderName(provider.name || '');
      setRemark(provider.remark || '');
      setWebsiteUrl(provider.websiteUrl || '');
      setApiKeyApplyUrl(provider.apiKeyApplyUrl || '');
      setAuthMode(provider.authMode || 'api_key');
      setRequestMode(provider.requestMode || 'codex_sdk');
      setBaseUrl(provider.baseUrl || '');
      setApiKey(provider.apiKey || '');
      setApiKeyEnv(provider.apiKeyEnv || '');
      setModels(
        provider.models && provider.models.length > 0
          ? provider.models.map(createEditableModelRow)
          : [createEmptyModelRow()],
      );
      setShowApiKey(false);
      setShowAdvancedJsonEditor(false);
      return;
    }
    const nextPreset = initialProviderData?.presetId || initialProviderData?.providerType || 'custom_gateway';
    setProviderPreset(nextPreset);
    setProviderName(initialProviderData?.name || '');
    setRemark(initialProviderData?.remark || '');
    setWebsiteUrl(initialProviderData?.websiteUrl || '');
    setApiKeyApplyUrl(initialProviderData?.apiKeyApplyUrl || '');
    setAuthMode(initialProviderData?.authMode || 'api_key');
    setRequestMode(initialProviderData?.requestMode || 'codex_sdk');
    setBaseUrl(initialProviderData?.baseUrl || '');
    setApiKey(initialProviderData?.apiKey || '');
    setApiKeyEnv(initialProviderData?.apiKeyEnv || '');
    setModels(
      initialProviderData?.models && initialProviderData.models.length > 0
        ? initialProviderData.models.map(createEditableModelRow)
        : [createEmptyModelRow()],
    );
    setShowApiKey(false);
    setShowAdvancedJsonEditor(false);
  }, [initialProviderData, isOpen, provider]);

  useEffect(() => {
    setModelsJsonText(serializeModelsJson(models));
  }, [models]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [isOpen, onClose]);

  /**
   * 选择 preset 时自动填充 provider 基础字段。
   * 编辑态不强制覆盖用户已保存的值；新增态则直接按模板初始化，贴近 cc-switch 的 provider-centric 创建体验。
   */
  const handlePresetChange = (presetId: string) => {
    setProviderPreset(presetId);
    const nextPreset = CODEX_PROVIDER_PRESETS.find((preset) => preset.id === presetId);
    if (!nextPreset) {
      return;
    }
    setAuthMode(nextPreset.authMode);
    setRequestMode(nextPreset.requestMode);
    if (provider) {
      return;
    }
    setProviderName(nextPreset.providerName);
    setWebsiteUrl(nextPreset.websiteUrl);
    setApiKeyApplyUrl(nextPreset.apiKeyApplyUrl);
    setBaseUrl(nextPreset.baseUrl);
    setModels(
      nextPreset.models.length > 0
        ? nextPreset.models.map(createEditableModelRow)
        : [createEmptyModelRow()],
    );
  };

  /**
   * 更新指定模型行。
   * 这里保留行级结构而不是 JSON 编辑，便于后续继续扩展 reasoning effort、默认模型等配置。
   */
  const handleModelFieldChange = (index: number, field: keyof CodexCustomModel, value: string) => {
    setModels((prev) => prev.map((model, currentIndex) => (
      currentIndex === index
        ? { ...model, [field]: value }
        : model
    )));
  };

  /**
   * 将高级 JSON 编辑器中的模型数组同步回结构化模型列表。
   * 这里只接受合法的 CodexCustomModel 数组，避免把非法配置写入 provider payload。
   */
  const handleApplyModelsJson = () => {
    try {
      const parsedModels = JSON.parse(modelsJsonText);
      if (!Array.isArray(parsedModels)) {
        addToast(t('settings.codexProvider.dialog.modelsJsonInvalid'), 'error');
        return;
      }
      const validModels = validateCodexCustomModels(parsedModels);
      if (validModels.length !== parsedModels.length) {
        addToast(t('settings.codexProvider.dialog.modelsJsonInvalid'), 'error');
        return;
      }
      setModels(validModels.length > 0 ? validModels.map(createEditableModelRow) : [createEmptyModelRow()]);
    } catch {
      addToast(t('settings.codexProvider.dialog.modelsJsonInvalid'), 'error');
    }
  };

  /**
   * 过滤空模型行，保证提交 payload 只包含真正可运行的模型定义。
   */
  const normalizedModels = useMemo(
    () => models
      .map((model) => ({
        ...model,
        id: model.id.trim(),
        label: (model.label || model.id).trim(),
        description: model.description?.trim() || undefined,
      }))
      .filter((model) => model.id.length > 0),
    [models],
  );

  /**
   * 执行保存并可选激活。
   *
   * @param autoActivate 是否在保存后立即切换为当前 provider
   */
  const handleSave = (autoActivate: boolean) => {
    if (!providerName.trim()) {
      addToast(t('settings.codexProvider.dialog.nameRequired'), 'error');
      return;
    }
    if (!baseUrl.trim()) {
      addToast(t('settings.codexProvider.dialog.baseUrlRequired'), 'error');
      return;
    }
    if ((authMode === 'api_key' || authMode === 'api_key_env' || authMode === 'proxy') && !apiKey.trim() && !apiKeyEnv.trim()) {
      addToast(t('settings.codexProvider.dialog.apiKeyOrEnvRequired'), 'error');
      return;
    }
    if (normalizedModels.length === 0 && authMode !== 'codex_cli_login') {
      addToast(t('settings.codexProvider.dialog.modelsRequired'), 'error');
      return;
    }

    const providerData: CodexProviderConfig = {
      id: provider?.id || (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString()),
      name: providerName.trim(),
      providerType: selectedPreset?.providerType || providerPreset,
      presetId: providerPreset,
      remark: remark.trim() || undefined,
      websiteUrl: websiteUrl.trim() || undefined,
      apiKeyApplyUrl: apiKeyApplyUrl.trim() || undefined,
      createdAt: provider?.createdAt,
      authMode,
      requestMode,
      baseUrl: baseUrl.trim() || undefined,
      apiKey: apiKey.trim() || undefined,
      apiKeyEnv: apiKeyEnv.trim() || undefined,
      models: normalizedModels,
      autoActivate,
    };

    onSave(providerData);
    onClose();
  };

  if (!isOpen) {
    return null;
  }

  return (
    <div className="dialog-overlay">
      <div className="dialog provider-dialog codex-provider-dialog">
        <div className="dialog-header">
          <h3>
            {isAdding
              ? t('settings.codexProvider.dialog.addTitle')
              : t('settings.codexProvider.dialog.editTitle', { name: provider?.name })}
          </h3>
          <button className="close-btn" onClick={onClose}>
            <span className="codicon codicon-close" />
          </button>
        </div>

        <div className="dialog-body">
          <p className="dialog-desc">
            {isAdding
              ? t('settings.codexProvider.dialog.addDescription')
              : t('settings.codexProvider.dialog.editDescription')}
          </p>

          <div style={GRID_STYLE}>
            <div className="form-group">
              <label htmlFor="providerPreset">{t('settings.codexProvider.dialog.providerPreset')}</label>
              <select
                id="providerPreset"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.providerPreset')}
                value={providerPreset}
                onChange={(e) => handlePresetChange(e.target.value)}
              >
                {CODEX_PROVIDER_PRESETS.map((preset) => (
                  <option key={preset.id} value={preset.id}>
                    {preset.id}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="providerName">
                <span>{t('settings.codexProvider.dialog.providerName')}</span>
                <span className="required" aria-hidden="true">{t('settings.provider.dialog.required')}</span>
              </label>
              <input
                id="providerName"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.providerName')}
                placeholder={t('settings.codexProvider.dialog.providerNamePlaceholder')}
                value={providerName}
                onChange={(e) => setProviderName(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="websiteUrl">{t('settings.codexProvider.dialog.websiteUrl')}</label>
              <input
                id="websiteUrl"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.websiteUrl')}
                placeholder={t('settings.codexProvider.dialog.websiteUrlPlaceholder')}
                value={websiteUrl}
                onChange={(e) => setWebsiteUrl(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="apiKeyApplyUrl">{t('settings.codexProvider.dialog.apiKeyApplyUrl')}</label>
              <input
                id="apiKeyApplyUrl"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.apiKeyApplyUrl')}
                placeholder={t('settings.codexProvider.dialog.apiKeyApplyUrlPlaceholder')}
                value={apiKeyApplyUrl}
                onChange={(e) => setApiKeyApplyUrl(e.target.value)}
              />
              {apiKeyApplyUrl.trim() && (
                <a href={apiKeyApplyUrl.trim()} target="_blank" rel="noreferrer" style={FORM_LINK_STYLE}>
                  {apiKeyApplyUrl.trim()}
                </a>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="providerRemark">{t('settings.provider.dialog.remark')}</label>
              <input
                id="providerRemark"
                type="text"
                className="form-input"
                aria-label={t('settings.provider.dialog.remark')}
                placeholder={t('settings.provider.dialog.remarkPlaceholder')}
                value={remark}
                onChange={(e) => setRemark(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="authMode">{t('settings.codexProvider.dialog.authMode')}</label>
              <select
                id="authMode"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.authMode')}
                value={authMode}
                onChange={(e) => setAuthMode(e.target.value as CodexAuthMode)}
              >
                {AUTH_MODE_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {t(`settings.codexProvider.dialog.authModeOptions.${option}`)}
                  </option>
                ))}
              </select>
              <small className="form-hint">{t('settings.codexProvider.dialog.authModeHint')}</small>
            </div>

            <div className="form-group">
              <label htmlFor="requestMode">{t('settings.codexProvider.dialog.requestMode')}</label>
              <select
                id="requestMode"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.requestMode')}
                value={requestMode}
                onChange={(e) => setRequestMode(e.target.value as CodexRequestMode)}
              >
                {REQUEST_MODE_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {t(`settings.codexProvider.dialog.requestModeOptions.${option}`)}
                  </option>
                ))}
              </select>
              <small className="form-hint">{t('settings.codexProvider.dialog.requestModeHint')}</small>
            </div>

            <div className="form-group">
              <label htmlFor="baseUrl">{t('settings.codexProvider.dialog.baseUrl')}</label>
              <input
                id="baseUrl"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.baseUrl')}
                placeholder={t('settings.codexProvider.dialog.baseUrlPlaceholder')}
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
              />
              <small className="form-hint">{t('settings.codexProvider.dialog.baseUrlHint')}</small>
            </div>

            <div className="form-group">
              <label htmlFor="apiKey">{t('settings.codexProvider.dialog.apiKey')}</label>
              <div style={INLINE_ACTION_STYLE}>
                <input
                  id="apiKey"
                  type={showApiKey ? 'text' : 'password'}
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.apiKey')}
                  placeholder={t('settings.codexProvider.dialog.apiKeyPlaceholder')}
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                />
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setShowApiKey((value) => !value)}>
                  {showApiKey ? t('settings.provider.dialog.hideApiKey') : t('settings.provider.dialog.showApiKey')}
                </button>
              </div>
              {!showApiKey && maskedApiKey && (
                <small className="form-hint">{maskedApiKey}</small>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="apiKeyEnv">{t('settings.codexProvider.dialog.apiKeyEnv')}</label>
              <input
                id="apiKeyEnv"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.apiKeyEnv')}
                placeholder={t('settings.codexProvider.dialog.apiKeyEnvPlaceholder')}
                value={apiKeyEnv}
                onChange={(e) => setApiKeyEnv(e.target.value)}
              />
              <small className="form-hint">{t('settings.codexProvider.dialog.apiKeyEnvHint')}</small>
            </div>

            <div className="form-group">
              <div style={MODEL_HEADER_STYLE}>
                <label>{t('settings.codexProvider.dialog.modelList')}</label>
                <small className="form-hint">{t('settings.codexProvider.dialog.modelAliasHelp')}</small>
                {models.map((model, index) => (
                  <div key={`${index}-${model.id}`} style={MODEL_ROW_STYLE}>
                    <input
                      type="text"
                      className="form-input"
                      placeholder={t('settings.codexProvider.dialog.modelIdPlaceholder')}
                      value={model.id}
                      onChange={(e) => handleModelFieldChange(index, 'id', e.target.value)}
                    />
                    <input
                      type="text"
                      className="form-input"
                      placeholder={t('settings.codexProvider.dialog.modelLabelPlaceholder')}
                      value={model.label}
                      onChange={(e) => handleModelFieldChange(index, 'label', e.target.value)}
                    />
                    <input
                      type="text"
                      className="form-input"
                      placeholder={t('settings.codexProvider.dialog.modelDescriptionPlaceholder')}
                      value={model.description || ''}
                      onChange={(e) => handleModelFieldChange(index, 'description', e.target.value)}
                    />
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => setModels((prev) => prev.length > 1 ? prev.filter((_, currentIndex) => currentIndex !== index) : [createEmptyModelRow()])}
                    >
                      {t('common.delete')}
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setModels((prev) => {
                    if (prev.some(isEmptyModelRow)) {
                      return prev;
                    }
                    return [...prev, createEmptyModelRow()];
                  })}
                >
                  {t('settings.codexProvider.dialog.addModelRow')}
                </button>
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={() => setShowAdvancedJsonEditor((value) => !value)}
                >
                  {t('settings.codexProvider.dialog.advancedJsonToggle', { defaultValue: 'Advanced JSON Editor' })}
                </button>
                {showAdvancedJsonEditor && (
                  <>
                    <small className="form-hint">
                      {t('settings.codexProvider.dialog.advancedJsonHelp', {
                        defaultValue: 'Paste a model JSON array in bulk and sync it back to the structured list.',
                      })}
                    </small>
                    <label htmlFor="modelsJson">
                      {t('settings.codexProvider.dialog.modelsJsonLabel', { defaultValue: 'Model JSON' })}
                    </label>
                    <textarea
                      id="modelsJson"
                      className="form-input"
                      aria-label={t('settings.codexProvider.dialog.modelsJsonLabel', { defaultValue: 'Model JSON' })}
                      rows={8}
                      value={modelsJsonText}
                      onChange={(e) => setModelsJsonText(e.target.value)}
                    />
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={handleApplyModelsJson}
                    >
                      {t('settings.codexProvider.dialog.applyModelsJson', { defaultValue: 'Apply JSON' })}
                    </button>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>

        <div className="dialog-footer">
          <div className="footer-actions" style={FOOTER_ACTIONS_STYLE}>
            <button className="btn btn-secondary" onClick={onClose}>
              <span className="codicon codicon-close" />
              {t('common.cancel')}
            </button>
            <button className="btn btn-secondary" onClick={() => handleSave(false)} disabled={!providerName.trim()}>
              <span className="codicon codicon-save" />
              {isAdding ? t('settings.provider.dialog.confirmAdd') : t('settings.provider.dialog.saveChanges')}
            </button>
            <button className="btn btn-primary" onClick={() => handleSave(true)} disabled={!providerName.trim()}>
              <span className="codicon codicon-play" />
              {t('settings.codexProvider.dialog.saveAndActivate')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
