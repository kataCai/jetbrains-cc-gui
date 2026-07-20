import { useState, useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type { CodexProviderConfig } from '../../../types/provider';
import {
  getCodexRuntimeSourceTranslationKey,
  hasCodexRuntimeSourceDiagnostics,
  isCodexProviderModelFetchSupported,
  isCodexRequestModeImplemented,
  resolveCodexRuntimeSource,
  SPECIAL_PROVIDER_IDS,
} from '../../../types/provider';
import { sendToJava } from '../../../utils/bridge';
import { useDragSort } from '../hooks/useDragSort';
import sharedStyles from '../ProviderList/style.module.less';
import styles from './style.module.less';

const ICON_MR_8_STYLE: React.CSSProperties = { marginRight: '8px' };

/**
 * 生成普通 Codex provider 卡片上的元信息摘要项。
 * 这些字段来自结构化 provider 创建表单，用于在列表层快速确认当前配置的模板、
 * 请求地址和模型数量；该摘要不参与运行时解析，只负责帮助用户在设置页检查配置。
 *
 * @param provider Codex provider 配置对象。
 * @param t i18n 翻译函数。
 * @return 可展示的元信息文本数组；没有可用信息时返回空数组。
 */
function buildProviderMetaItems(
  provider: CodexProviderConfig,
  t: (key: string, options?: Record<string, string | number>) => string
): string[] {
  const metaItems: string[] = [];
  const providerType = provider.presetId || provider.providerType;
  if (providerType) {
    metaItems.push(t('settings.codexProvider.providerTypeMeta', { type: providerType }));
  }
  if (provider.baseUrl) {
    metaItems.push(t('settings.codexProvider.baseUrlMeta', { baseUrl: provider.baseUrl }));
  }
  const modelCount = provider.models?.length || provider.customModels?.length || 0;
  if (modelCount > 0) {
    metaItems.push(t('settings.codexProvider.modelCountMeta', { count: modelCount }));
  }
  if (hasCodexRuntimeSourceDiagnostics(provider)) {
    const runtimeSource = resolveCodexRuntimeSource(provider);
    metaItems.push(t('settings.codexProvider.runtimeSourceLabel', {
      source: t(`settings.codexProvider.runtimeSource.${getCodexRuntimeSourceTranslationKey(runtimeSource)}`),
    }));
  }
  if (!isCodexRequestModeImplemented(provider.requestMode)) {
    metaItems.push(t('settings.codexProvider.requestModeUnavailableBadge'));
  }
  return metaItems;
}

interface CodexProviderSectionProps {
  codexProviders: CodexProviderConfig[];
  codexLocalConfigAuthorized?: boolean;
  codexLoading: boolean;
  syncingCodexProviderId?: string;
  testingCodexProviderId?: string;
  onAddCodexProvider: () => void;
  onEditCodexProvider: (provider: CodexProviderConfig) => void;
  onDeleteCodexProvider: (provider: CodexProviderConfig) => void;
  onFetchCodexProviderModels: (provider: CodexProviderConfig) => void;
  onTestCodexProvider: (provider: CodexProviderConfig) => void;
  onAuthorizeCodexLocalConfig?: () => void;
  onRevokeCodexLocalConfigAuthorization: (fallbackProviderId?: string) => void;
  showHeader?: boolean;
  showProviderListHeader?: boolean;
}

/**
 * 组合 CLI Login 卡片的状态摘要行。
 * 这里显式把“授权状态”和“当前请求来源”拆成两个独立标签，
 * 避免继续使用旧的技术化长句，保证用户能快速理解当前配置状态。
 *
 * @param isAuthorized 当前是否已授权读取本地 Codex 配置。
 * @param isActive 当前请求是否正在使用 CLI Login 来源。
 * @param t i18n 翻译函数。
 * @return 用于渲染状态徽标的标签和值列表。
 */
function buildCliLoginStatusItems(
  isAuthorized: boolean,
  isActive: boolean,
  t: (key: string) => string
): Array<{ label: string; value: string }> {
  return [
    {
      label: t('settings.codexProvider.cliLogin.authorizationStatus'),
      value: t(
        isAuthorized
          ? 'settings.codexProvider.cliLogin.authorized'
          : 'settings.codexProvider.cliLogin.notAuthorized'
      ),
    },
    {
      label: t('settings.codexProvider.cliLogin.currentUsageStatus'),
      value: t(
        isActive
          ? 'settings.codexProvider.cliLogin.currentlyUsed'
          : 'settings.codexProvider.cliLogin.notInUse'
      ),
    },
  ];
}

/**
 * 渲染 Codex 设置页中的 provider 管理卡片区。
 * 该组件同时承担三类职责：
 * 1. 展示普通托管 provider 的摘要信息与管理动作；
 * 2. 展示只读的 CLI Login 虚拟 provider 授权卡片；
 * 3. 在不打破现有测试、编辑、删除交互的前提下，为普通 provider 提供“拉取模型列表”入口。
 * 新增的模型拉取按钮只服务于设置页配置管理，不负责运行时切换；运行时模型启用仍由聊天区选择器决定。
 *
 * @param props Codex provider 卡片区渲染所需的列表数据、动作回调和 loading 状态
 * @return 设置页内的 Codex provider 管理区 JSX
 */
const CodexProviderSection = ({
  codexProviders,
  codexLocalConfigAuthorized = false,
  codexLoading,
  syncingCodexProviderId,
  testingCodexProviderId,
  onAddCodexProvider,
  onEditCodexProvider,
  onDeleteCodexProvider,
  onFetchCodexProviderModels,
  onTestCodexProvider,
  onAuthorizeCodexLocalConfig,
  onRevokeCodexLocalConfigAuthorization,
  showHeader = true,
  showProviderListHeader = true,
}: CodexProviderSectionProps) => {
  const { t } = useTranslation();

  const [showCliLoginConfirm, setShowCliLoginConfirm] = useState(false);
  const [showCliLoginDisableConfirm, setShowCliLoginDisableConfirm] = useState(false);

  const onSort = useCallback((orderedIds: string[]) => {
    sendToJava('sort_codex_providers', { orderedIds });
  }, []);

  const regularProviders = useMemo(
    () => codexProviders.filter((provider) => provider.id !== SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN),
    [codexProviders]
  );

  const {
    localItems: localProviders,
    draggedId: draggedProviderId,
    dragOverId: dragOverProviderId,
    handlePointerDown,
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    handleDragEnd,
  } = useDragSort({
    items: regularProviders,
    onSort,
  });

  const cliLoginProvider = useMemo(
    () => codexProviders.find((provider) => provider.id === SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN) as
      | (CodexProviderConfig & { isAuthorized?: boolean })
      | undefined,
    [codexProviders]
  );
  const isCliLoginActive = cliLoginProvider?.isActive === true;
  const isCliLoginAuthorized = codexLocalConfigAuthorized || cliLoginProvider?.isAuthorized === true;
  const cliLoginStatusItems = useMemo(
    () => buildCliLoginStatusItems(isCliLoginAuthorized, isCliLoginActive, t),
    [isCliLoginActive, isCliLoginAuthorized, t]
  );

  return (
    <div className={styles.configSection}>
      {showHeader && (
        <>
          <h3 className={styles.sectionTitle}>{t('settings.codexProvider.title')}</h3>
          <p className={styles.sectionDesc}>{t('settings.codexProvider.description')}</p>
        </>
      )}

      {showCliLoginConfirm && (
        <div className={sharedStyles.warningOverlay}>
          <div className={sharedStyles.warningDialog}>
            <div className={sharedStyles.warningTitle}>
              <span className="codicon codicon-key" />
              {t('settings.codexProvider.dialog.cliLoginAuthorizeTitle')}
            </div>
            <div className={sharedStyles.warningContent}>
              {t('settings.codexProvider.dialog.cliLoginAuthorizeMessage')}
              <br />
              <br />
              {t('settings.codexProvider.dialog.cliLoginAuthorizeDetail')}
            </div>
            <div className={sharedStyles.warningActions}>
              <button
                className={sharedStyles.btnSecondary}
                onClick={() => setShowCliLoginConfirm(false)}
              >
                {t('common.cancel')}
              </button>
              <button
                className={sharedStyles.btnPrimary}
                onClick={() => {
                  setShowCliLoginConfirm(false);
                  onAuthorizeCodexLocalConfig?.();
                }}
              >
                {t('settings.codexProvider.cliLogin.authorizeOnly')}
              </button>
            </div>
          </div>
        </div>
      )}

      {showCliLoginDisableConfirm && (
        <div className={sharedStyles.warningOverlay}>
          <div className={sharedStyles.warningDialog}>
            <div className={sharedStyles.warningTitle}>
              <span className="codicon codicon-circle-slash" />
              {t('settings.codexProvider.dialog.cliLoginDisableTitle')}
            </div>
            <div className={sharedStyles.warningContent}>
              {t('settings.codexProvider.dialog.cliLoginDisableMessage')}
            </div>
            <div className={sharedStyles.warningActions}>
              <button
                className={sharedStyles.btnSecondary}
                onClick={() => setShowCliLoginDisableConfirm(false)}
              >
                {t('common.cancel')}
              </button>
              <button
                className={sharedStyles.btnDanger}
                onClick={() => {
                  setShowCliLoginDisableConfirm(false);
                  const firstRegularProvider = regularProviders[0];
                  onRevokeCodexLocalConfigAuthorization(firstRegularProvider?.id);
                }}
              >
                {t('settings.provider.revokeAuthorization')}
              </button>
            </div>
          </div>
        </div>
      )}

      {codexLoading && (
        <div className={styles.tempNotice}>
          <span className="codicon codicon-loading codicon-modifier-spin" />
          <p>{t('settings.provider.loading')}</p>
        </div>
      )}

      {!codexLoading && (
        <div className={styles.providerListContainer}>
          {showProviderListHeader && (
            <div className={sharedStyles.header}>
              <h4 className={sharedStyles.title}>{t('settings.provider.allProviders')}</h4>
              <div className={sharedStyles.actions}>
                <button
                  className={sharedStyles.btnPrimary}
                  onClick={onAddCodexProvider}
                >
                  <span className="codicon codicon-add" />
                  {t('common.add')}
                </button>
              </div>
            </div>
          )}

          <div className={sharedStyles.list}>
            {cliLoginProvider && (
              <div
                className={`${sharedStyles.card} ${isCliLoginActive ? sharedStyles.active : ''} ${sharedStyles.localProviderCard} ${styles.cliLoginCard}`}
              >
                <div className={sharedStyles.cardInfo}>
                  <div className={sharedStyles.name}>
                    <span className="codicon codicon-key" style={ICON_MR_8_STYLE} />
                    {t('settings.codexProvider.cliLogin.title')}
                  </div>
                  <div className={styles.cliLoginDescription}>
                    {t('settings.codexProvider.cliLogin.description')}
                  </div>
                  <div className={styles.cliLoginReadonlyHint}>
                    {t('settings.codexProvider.cliLogin.readonlyHint')}
                  </div>
                  <div className={styles.providerMeta}>
                    {cliLoginStatusItems.map((item) => (
                      <span key={item.label} className={styles.providerMetaItem}>
                        <span className={styles.providerMetaLabel}>{item.label}</span>
                        <span className={styles.providerMetaValue}>{item.value}</span>
                      </span>
                    ))}
                  </div>
                </div>

                <div className={`${sharedStyles.cardActions} ${styles.cliLoginActions}`}>
                  {!isCliLoginAuthorized ? (
                    <button
                      className={sharedStyles.useButton}
                      onClick={() => setShowCliLoginConfirm(true)}
                    >
                      <span className="codicon codicon-key" />
                      {t('settings.codexProvider.cliLogin.authorizeOnly')}
                    </button>
                  ) : (
                    <button
                      className={sharedStyles.revokeButton}
                      onClick={() => setShowCliLoginDisableConfirm(true)}
                    >
                      <span className="codicon codicon-circle-slash" />
                      {t('settings.provider.revokeAuthorization')}
                    </button>
                  )}
                </div>
              </div>
            )}

            {localProviders.length > 0 ? (
              localProviders.map((provider) => {
                const metaItems = buildProviderMetaItems(provider, t);
                const requestModeUnavailable = !isCodexRequestModeImplemented(provider.requestMode);
                const modelDiscoverySupported = isCodexProviderModelFetchSupported(provider);
                const isSyncingModels = syncingCodexProviderId === provider.id;
                return (
                  <div
                    key={provider.id}
                    className={[
                      sharedStyles.card,
                      provider.isActive && sharedStyles.active,
                      draggedProviderId === provider.id && styles.dragging,
                      dragOverProviderId === provider.id && styles.dragOver,
                    ].filter(Boolean).join(' ')}
                    data-drag-sort-id={provider.id}
                    draggable={true}
                    onDragStart={(event) => handleDragStart(event, provider.id)}
                    onDragOver={(event) => handleDragOver(event, provider.id)}
                    onDragLeave={handleDragLeave}
                    onDrop={(event) => handleDrop(event, provider.id)}
                    onDragEnd={handleDragEnd}
                  >
                    <div
                      className={sharedStyles.dragHandle}
                      title={t('settings.provider.dragToSort')}
                      onPointerDown={(event) => handlePointerDown(
                        event,
                        provider.id,
                        event.currentTarget.closest<HTMLElement>('[data-drag-sort-id]')
                      )}
                    >
                      <span className="codicon codicon-gripper" />
                    </div>
                    <div className={sharedStyles.cardInfo}>
                      <div className={sharedStyles.name}>{provider.name}</div>
                      {provider.remark && (
                        <div className={sharedStyles.website}>{provider.remark}</div>
                      )}
                      {metaItems.length > 0 && (
                        <div className={styles.providerMeta}>
                          {metaItems.map((item) => (
                            <span key={item} className={styles.providerMetaItem}>{item}</span>
                          ))}
                        </div>
                      )}
                    </div>

                    <div className={sharedStyles.cardActions}>
                      <div className={sharedStyles.actionButtons}>
                        {/* 模型拉取只针对当前托管 provider 生效，避免把不支持的 auth/request mode 放进错误链路。 */}
                        <button
                          className={sharedStyles.iconBtn}
                          onClick={() => onFetchCodexProviderModels(provider)}
                          title={!modelDiscoverySupported
                            ? t('settings.codexProvider.fetchModelsUnsupportedTooltip')
                            : isSyncingModels
                              ? t('settings.codexProvider.fetchModelsLoading')
                              : t('settings.codexProvider.fetchModels')}
                          disabled={isSyncingModels || !modelDiscoverySupported}
                        >
                          <span className={isSyncingModels
                            ? 'codicon codicon-loading codicon-modifier-spin'
                            : 'codicon codicon-refresh'} />
                        </button>
                        <button
                          className={sharedStyles.iconBtn}
                          onClick={() => onTestCodexProvider(provider)}
                          title={requestModeUnavailable
                            ? t('settings.codexProvider.requestModeUnavailableTooltip')
                            : testingCodexProviderId === provider.id
                              ? t('settings.provider.loading')
                              : t('settings.codexProvider.dialog.testProvider')}
                          disabled={testingCodexProviderId === provider.id || requestModeUnavailable}
                        >
                          <span className={testingCodexProviderId === provider.id
                            ? 'codicon codicon-loading codicon-modifier-spin'
                            : 'codicon codicon-plug'} />
                        </button>
                        <button
                          className={sharedStyles.iconBtn}
                          onClick={() => onEditCodexProvider(provider)}
                          title={t('common.edit')}
                        >
                          <span className="codicon codicon-edit" />
                        </button>
                        <button
                          className={sharedStyles.iconBtn}
                          onClick={() => onDeleteCodexProvider(provider)}
                          title={t('common.delete')}
                        >
                          <span className="codicon codicon-trash" />
                        </button>
                      </div>
                    </div>
                  </div>
                );
              })
            ) : !cliLoginProvider ? (
              <div className={sharedStyles.emptyState}>
                <span className="codicon codicon-info" />
                <p>{t('settings.codexProvider.emptyProvider')}</p>
              </div>
            ) : null}
          </div>
        </div>
      )}
    </div>
  );
};

export default CodexProviderSection;
