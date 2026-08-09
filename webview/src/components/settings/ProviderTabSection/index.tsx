import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  ProviderConfig,
  CodexProviderConfig,
  CodexCustomModel,
  CodexModelCatalogItem,
  CodexModelVisibilityConfig,
} from '../../../types/provider';
import { SPECIAL_PROVIDER_IDS, STORAGE_KEYS } from '../../../types/provider';
import ProviderManageSection from '../ProviderManageSection';
import CodexProviderSection from '../CodexProviderSection';
import CodexModelVisibilitySection from '../CodexModelVisibilitySection';
import CustomModelDialog from '../CustomModelDialog';
import { usePluginModels } from '../hooks/usePluginModels';
import styles from './style.module.less';

const BLOCK_STYLE: React.CSSProperties = { display: 'block' };
const NONE_STYLE: React.CSSProperties = { display: 'none' };
const ICON_14_STYLE: React.CSSProperties = { fontSize: 14 };
const FLEX_1_STYLE: React.CSSProperties = { flex: 1 };

interface ProviderTabSectionProps {
  currentProvider: 'claude' | 'codex' | string;
  // Claude provider props
  providers: ProviderConfig[];
  loading: boolean;
  onAddProvider: () => void;
  onEditProvider: (provider: ProviderConfig) => void;
  onDeleteProvider: (provider: ProviderConfig) => void;
  onSwitchProvider: (id: string) => void;
  // Codex provider props
  codexProviders: CodexProviderConfig[];
  codexLoading: boolean;
  codexModelCatalog: CodexModelCatalogItem[];
  codexModelCatalogLoading: boolean;
  syncingCodexProviderId?: string;
  testingCodexProviderId?: string;
  onAddCodexProvider: () => void;
  onCreateCodexProviderFromAlias?: (providerDraft: Partial<CodexProviderConfig>) => void;
  onEditCodexProvider: (provider: CodexProviderConfig) => void;
  onDeleteCodexProvider: (provider: CodexProviderConfig) => void;
  onFetchCodexProviderModels: (provider: CodexProviderConfig) => void;
  onTestCodexProvider: (provider: CodexProviderConfig) => void;
  onDuplicateCodexProvider?: (provider: CodexProviderConfig) => void;
  onAuthorizeCodexLocalConfig: () => void;
  onRevokeCodexLocalConfigAuthorization: (fallbackProviderId?: string) => void;
  onRefreshCodexModelCatalog: () => void;
  onSaveCodexModelVisibility: (visibilityConfig: CodexModelVisibilityConfig) => void;
  onDeleteCodexModelCatalogItem: (catalogItem: CodexModelCatalogItem) => void;
  // Shared
  addToast: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;
}

const ProviderTabSection = ({
  currentProvider,
  providers,
  loading,
  onAddProvider,
  onEditProvider,
  onDeleteProvider,
  onSwitchProvider,
  codexProviders,
  codexLoading,
  codexModelCatalog,
  codexModelCatalogLoading,
  syncingCodexProviderId,
  testingCodexProviderId,
  onAddCodexProvider,
  onCreateCodexProviderFromAlias,
  onEditCodexProvider,
  onDeleteCodexProvider,
  onFetchCodexProviderModels,
  onTestCodexProvider,
  onDuplicateCodexProvider,
  onAuthorizeCodexLocalConfig,
  onRevokeCodexLocalConfigAuthorization,
  onRefreshCodexModelCatalog,
  onSaveCodexModelVisibility,
  onDeleteCodexModelCatalogItem,
  addToast,
}: ProviderTabSectionProps) => {
  const { t } = useTranslation();

  const [activeTab, setActiveTab] = useState<'claude' | 'codex'>(
    () => currentProvider === 'codex' ? 'codex' : 'claude'
  );

  // Plugin-level custom model management
  const claudeModels = usePluginModels(STORAGE_KEYS.CLAUDE_CUSTOM_MODELS);
  const codexModels = usePluginModels(STORAGE_KEYS.CODEX_CUSTOM_MODELS);

  // Dialog state
  const [modelDialogOpen, setModelDialogOpen] = useState(false);
  const [modelDialogAddMode, setModelDialogAddMode] = useState(false);
  // Which plugin's models the dialog is editing
  const [dialogTarget, setDialogTarget] = useState<'claude' | 'codex'>('claude');

  const openModelDialog = useCallback((target: 'claude' | 'codex', addMode = false) => {
    setDialogTarget(target);
    setModelDialogAddMode(addMode);
    setModelDialogOpen(true);
  }, []);

  const closeModelDialog = useCallback(() => {
    setModelDialogOpen(false);
    setModelDialogAddMode(false);
  }, []);

  const activeModels = dialogTarget === 'claude' ? claudeModels : codexModels;
  const isCodexDialogTarget = dialogTarget === 'codex';
  const cliLoginProvider = codexProviders.find(
    (provider) => provider.id === SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN
  ) as (CodexProviderConfig & { isAuthorized?: boolean }) | undefined;

  /**
   * 基于当前模型别名生成一个新的 Codex provider 草稿。
   * 这里只预填 provider 名称和模型列表，不自动补齐 Base URL / API Key，
   * 目的是把“历史别名”升级为真正可运行的配置，同时避免做不安全的自动迁移。
   *
   * @param model 当前选中的模型别名
   */
  const handleCreateCodexProviderFromAlias = useCallback((model: CodexCustomModel) => {
    if (!onCreateCodexProviderFromAlias) {
      return;
    }
    onCreateCodexProviderFromAlias({
      name: model.label?.trim() || model.id,
      providerType: 'custom_gateway',
      presetId: 'custom_gateway',
      authMode: 'api_key',
      requestMode: 'codex_sdk',
      models: [
        {
          id: model.id,
          label: model.label?.trim() || model.id,
          description: model.description,
          reasoningEffort: model.reasoningEffort,
        },
      ],
    });
    closeModelDialog();
  }, [closeModelDialog, onCreateCodexProviderFromAlias]);

  return (
    <div className={styles.providerTabSection}>
      <h3 className={styles.sectionTitle}>{t('settings.providers')}</h3>
      <p className={styles.sectionDesc}>{t('settings.providersDesc')}</p>

      <div className={styles.tabSelector} role="tablist" aria-label={t('settings.providers')}>
        <button
          role="tab"
          aria-selected={activeTab === 'claude'}
          aria-controls="panel-claude-providers"
          className={`${styles.tabBtn} ${activeTab === 'claude' ? styles.active : ''}`}
          onClick={() => setActiveTab('claude')}
        >
          <span className="codicon codicon-vm-connect" aria-hidden="true" />
          {t('settings.providerTab.claude')}
        </button>
        <button
          role="tab"
          aria-selected={activeTab === 'codex'}
          aria-controls="panel-codex-providers"
          className={`${styles.tabBtn} ${activeTab === 'codex' ? styles.active : ''}`}
          onClick={() => setActiveTab('codex')}
        >
          <span className="codicon codicon-terminal" aria-hidden="true" />
          {t('settings.providerTab.codex')}
        </button>
      </div>

      {/* Use display to preserve component state across tab switches */}
      <div id="panel-claude-providers" role="tabpanel" style={activeTab === 'claude' ? BLOCK_STYLE : NONE_STYLE}>
        <div
          className={styles.pluginModelsRow}
          onClick={() => openModelDialog('claude')}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') openModelDialog('claude'); }}
        >
          <span className="codicon codicon-symbol-misc" style={ICON_14_STYLE} />
          <span className={styles.pluginModelsLabel}>
            {t('settings.pluginModels.title')}
          </span>
          {claudeModels.models.length > 0 && (
            <span className={styles.pluginModelsBadge}>{claudeModels.models.length}</span>
          )}
          <span style={FLEX_1_STYLE} />
          <button
            className={styles.pluginModelsManageBtn}
            onClick={(e) => { e.stopPropagation(); openModelDialog('claude'); }}
          >
            {t('settings.pluginModels.manage')}
          </button>
        </div>
        <ProviderManageSection
          providers={providers}
          loading={loading}
          onAddProvider={onAddProvider}
          onEditProvider={onEditProvider}
          onDeleteProvider={onDeleteProvider}
          onSwitchProvider={onSwitchProvider}
          addToast={addToast}
          showHeader={false}
        />
      </div>

      <div id="panel-codex-providers" role="tabpanel" style={activeTab === 'codex' ? BLOCK_STYLE : NONE_STYLE}>
        <div className={styles.codexTabLayout}>
          <div className={styles.entryCluster}>
            <div
              className={styles.primaryEntryRow}
              onClick={onAddCodexProvider}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') onAddCodexProvider(); }}
            >
              <div className={styles.entryInfo}>
                <span className="codicon codicon-cloud-upload" style={ICON_14_STYLE} />
                <div className={styles.entryTextGroup}>
                  <span className={styles.pluginModelsLabel}>
                    {t('settings.codexProvider.quickCreateTitle')}
                  </span>
                  <span className={styles.entryDescription}>
                    {t('settings.codexProvider.quickCreateDescription')}
                  </span>
                </div>
              </div>
              <button
                className={styles.pluginModelsManageBtn}
                onClick={(e) => { e.stopPropagation(); onAddCodexProvider(); }}
              >
                {t('common.add')}
              </button>
            </div>
            <div
              className={styles.pluginModelsRow}
              onClick={() => openModelDialog('codex')}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') openModelDialog('codex'); }}
            >
              <span className="codicon codicon-symbol-misc" style={ICON_14_STYLE} />
              <div className={styles.entryTextGroup}>
                <span className={styles.pluginModelsLabel}>
                  {t('settings.codexProvider.aliasTitle')}
                </span>
                <span className={styles.entryDescription}>
                  {t('settings.codexProvider.aliasDescription')}
                </span>
              </div>
              {codexModels.models.length > 0 && (
                <span className={styles.pluginModelsBadge}>{codexModels.models.length}</span>
              )}
              <span style={FLEX_1_STYLE} />
              <button
                className={styles.pluginModelsManageBtn}
                onClick={(e) => { e.stopPropagation(); openModelDialog('codex'); }}
              >
                {t('settings.pluginModels.manage')}
              </button>
            </div>
          </div>

          <section className={styles.sectionBlock} aria-label={t('settings.provider.allProviders')}>
            <div className={styles.sectionLead}>
              <span className={styles.sectionEyebrow}>{t('settings.providerTab.codex')}</span>
              <h4 className={styles.sectionLeadTitle}>{t('settings.provider.allProviders')}</h4>
              <p className={styles.sectionLeadDescription}>{t('settings.codexProvider.description')}</p>
            </div>
            <CodexProviderSection
              codexProviders={codexProviders}
              codexLocalConfigAuthorized={cliLoginProvider?.isAuthorized === true}
              codexLoading={codexLoading}
              syncingCodexProviderId={syncingCodexProviderId}
              testingCodexProviderId={testingCodexProviderId}
              onAddCodexProvider={onAddCodexProvider}
              onEditCodexProvider={onEditCodexProvider}
              onDeleteCodexProvider={onDeleteCodexProvider}
              onFetchCodexProviderModels={onFetchCodexProviderModels}
              onTestCodexProvider={onTestCodexProvider}
              onDuplicateCodexProvider={onDuplicateCodexProvider}
              onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
              onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
              showHeader={false}
              showProviderListHeader={false}
            />
          </section>

          <section className={styles.sectionBlock} aria-label={t('settings.codexProvider.modelsTitle')}>
            <div className={styles.sectionLead}>
              <span className={styles.sectionEyebrow}>{t('settings.providerTab.codex')}</span>
              <h4 className={styles.sectionLeadTitle}>{t('settings.codexProvider.modelsTitle')}</h4>
              <p className={styles.sectionLeadDescription}>{t('settings.codexProvider.modelsDescription')}</p>
            </div>
            <CodexModelVisibilitySection
              catalog={codexModelCatalog}
              loading={codexModelCatalogLoading}
              onRefresh={onRefreshCodexModelCatalog}
              onSaveVisibility={onSaveCodexModelVisibility}
              onDeleteCatalogItem={onDeleteCodexModelCatalogItem}
              showHeader={false}
            />
          </section>
        </div>
      </div>

      {/* Shared model management dialog */}
      <CustomModelDialog
        isOpen={modelDialogOpen}
        models={activeModels.models}
        onModelsChange={activeModels.updateModels}
        onClose={closeModelDialog}
        initialAddMode={modelDialogAddMode}
        title={isCodexDialogTarget ? t('settings.codexProvider.aliasTitle') : undefined}
        description={isCodexDialogTarget ? t('settings.codexProvider.aliasDescription') : undefined}
        onCreateProviderFromModel={isCodexDialogTarget ? handleCreateCodexProviderFromAlias : undefined}
      />
    </div>
  );
};

export default ProviderTabSection;
