import { useMemo, useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CodexAuthMode,
  CodexCustomModel,
  CodexProviderConfig,
  CodexRequestMode,
} from '../types/provider';

const GRID_STYLE: React.CSSProperties = { display: 'grid', gap: '12px' };
const FOOTER_ACTIONS_STYLE: React.CSSProperties = { marginLeft: 'auto' };
const INLINE_ACTION_STYLE: React.CSSProperties = { display: 'flex', gap: '8px', alignItems: 'center' };

const AUTH_MODE_OPTIONS: CodexAuthMode[] = ['api_key', 'api_key_env', 'codex_cli_login', 'proxy', 'oauth'];
const REQUEST_MODE_OPTIONS: CodexRequestMode[] = ['codex_sdk', 'cc_switch_proxy', 'custom_adapter'];

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
  onClose: () => void;
  onSave: (provider: CodexProviderConfig) => void;
  addToast: (message: string, type: 'success' | 'error' | 'info') => void;
}

export default function CodexProviderDialog({
  isOpen,
  provider,
  onClose,
  onSave,
  addToast,
}: CodexProviderDialogProps) {
  const { t } = useTranslation();
  const isAdding = !provider;

  const [providerName, setProviderName] = useState('');
  const [remark, setRemark] = useState('');
  const [authMode, setAuthMode] = useState<CodexAuthMode>('api_key_env');
  const [requestMode, setRequestMode] = useState<CodexRequestMode>('codex_sdk');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [apiKeyEnv, setApiKeyEnv] = useState('');
  const [models, setModels] = useState<CodexCustomModel[]>([]);
  const [showApiKey, setShowApiKey] = useState(false);

  // Initialize form
  useEffect(() => {
    if (isOpen) {
      if (provider) {
        // 修改原因：设置页改为直接维护 requests 运行态字段，不再编辑 ~/.codex 原始文件内容。
        setProviderName(provider.name || '');
        setRemark(provider.remark || '');
        setAuthMode(provider.authMode || 'api_key_env');
        setRequestMode(provider.requestMode || 'codex_sdk');
        setBaseUrl(provider.baseUrl || '');
        setApiKey(provider.apiKey || '');
        setApiKeyEnv(provider.apiKeyEnv || '');
        setModels(provider.models || provider.customModels || []);
        setShowApiKey(false);
      } else {
        // 修改原因：新增 provider 时直接生成 runtime profile 表单初始值，避免继续引导用户编辑 ~/.codex。
        setProviderName('');
        setRemark('');
        setAuthMode('api_key_env');
        setRequestMode('codex_sdk');
        setBaseUrl('');
        setApiKey('');
        setApiKeyEnv('');
        setModels([]);
        setShowApiKey(false);
      }
    }
  }, [isOpen, provider]);

  // ESC key to close
  useEffect(() => {
    if (isOpen) {
      const handleEscape = (e: KeyboardEvent) => {
        if (e.key === 'Escape') {
          onClose();
        }
      };
      window.addEventListener('keydown', handleEscape);
      return () => window.removeEventListener('keydown', handleEscape);
    }
  }, [isOpen, onClose]);

  const maskedApiKey = useMemo(() => maskApiKey(apiKey), [apiKey]);

  const handleSave = () => {
    if (!providerName.trim()) {
      addToast(t('settings.codexProvider.dialog.nameRequired'), 'error');
      return;
    }
    if ((authMode === 'api_key_env' || authMode === 'proxy') && !apiKeyEnv.trim() && !apiKey.trim()) {
      addToast(t('settings.codexProvider.dialog.apiKeyOrEnvRequired'), 'error');
      return;
    }
    if (requestMode !== 'codex_sdk' && !baseUrl.trim()) {
      addToast(t('settings.codexProvider.dialog.baseUrlRequired'), 'error');
      return;
    }
    if (!models.length && authMode !== 'codex_cli_login') {
      addToast(t('settings.codexProvider.dialog.modelsRequired'), 'error');
      return;
    }

    const providerData: CodexProviderConfig = {
      id: provider?.id || (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString()),
      name: providerName.trim(),
      remark: remark.trim() || undefined,
      createdAt: provider?.createdAt,
      authMode,
      requestMode,
      baseUrl: baseUrl.trim() || undefined,
      apiKey: apiKey.trim() || undefined,
      apiKeyEnv: apiKeyEnv.trim() || undefined,
      models,
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
            <span className="codicon codicon-close"></span>
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
              <label htmlFor="providerName">
                {t('settings.codexProvider.dialog.providerName')}
                <span className="required">{t('settings.provider.dialog.required')}</span>
              </label>
              <input
                id="providerName"
                type="text"
                className="form-input"
                placeholder={t('settings.codexProvider.dialog.providerNamePlaceholder')}
                value={providerName}
                onChange={(e) => setProviderName(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="providerRemark">{t('settings.provider.dialog.remark')}</label>
              <input
                id="providerRemark"
                type="text"
                className="form-input"
                placeholder={t('settings.provider.dialog.remarkPlaceholder')}
                value={remark}
                onChange={(e) => setRemark(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="authMode">{t('settings.codexProvider.dialog.authMode')}</label>
              <select id="authMode" className="form-input" value={authMode} onChange={(e) => setAuthMode(e.target.value as CodexAuthMode)}>
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
              <select id="requestMode" className="form-input" value={requestMode} onChange={(e) => setRequestMode(e.target.value as CodexRequestMode)}>
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
                  placeholder={t('settings.codexProvider.dialog.apiKeyPlaceholder')}
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                />
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setShowApiKey((value) => !value)}>
                  {showApiKey ? t('settings.provider.dialog.hideApiKey') : t('settings.provider.dialog.showApiKey')}
                </button>
              </div>
              {!showApiKey && maskedApiKey && (
                <small className="form-hint">{t('settings.codexProvider.dialog.apiKeyMaskedHint', { masked: maskedApiKey })}</small>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="apiKeyEnv">{t('settings.codexProvider.dialog.apiKeyEnv')}</label>
              <input
                id="apiKeyEnv"
                type="text"
                className="form-input"
                placeholder={t('settings.codexProvider.dialog.apiKeyEnvPlaceholder')}
                value={apiKeyEnv}
                onChange={(e) => setApiKeyEnv(e.target.value)}
              />
              <small className="form-hint">{t('settings.codexProvider.dialog.apiKeyEnvHint')}</small>
            </div>

            <div className="form-group">
              <label htmlFor="models">{t('settings.codexProvider.dialog.models')}</label>
              <textarea
                id="models"
                className="form-input code-input"
                rows={8}
                value={JSON.stringify(models, null, 2)}
                onChange={(e) => {
                  try {
                    const parsed = JSON.parse(e.target.value) as CodexCustomModel[];
                    setModels(Array.isArray(parsed) ? parsed : []);
                  } catch {
                    setModels([]);
                  }
                }}
              />
              <small className="form-hint">{t('settings.codexProvider.dialog.modelsHint')}</small>
            </div>
          </div>
        </div>

        <div className="dialog-footer">
          <div className="footer-actions" style={FOOTER_ACTIONS_STYLE}>
            <button className="btn btn-secondary" onClick={onClose}>
              <span className="codicon codicon-close" />
              {t('common.cancel')}
            </button>
            <button className="btn btn-primary" onClick={handleSave} disabled={!providerName.trim()}>
              <span className="codicon codicon-save" />
              {isAdding ? t('settings.provider.dialog.confirmAdd') : t('settings.provider.dialog.saveChanges')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
