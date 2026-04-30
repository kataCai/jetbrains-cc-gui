import { useTranslation } from 'react-i18next';
import type {
  RemoteCollabProviderOption,
  RemoteCollabRoutingPolicy,
} from '../hooks/useRemoteCollabSettings';
import ProviderList from './ProviderList';
import RoutingPolicyPanel from './RoutingPolicyPanel';
import styles from './style.module.less';

interface RemoteCollabHubViewProps {
  enabled: boolean;
  interactiveProviderId: string;
  notifyProviderIds: string[];
  providerOptions: RemoteCollabProviderOption[];
  onEnabledChange: (enabled: boolean) => void;
  onSaveRemoteCollabRoutingPolicy: (policy: RemoteCollabRoutingPolicy) => void;
  onOpenProvider: (providerId: string) => void;
}

/**
 * 远程协作 Hub 首页。
 * 这里只保留总开关、路由摘要与 provider 入口，避免一级页被具体渠道表单淹没。
 */
const RemoteCollabHubView = ({
  enabled,
  interactiveProviderId,
  notifyProviderIds,
  providerOptions,
  onEnabledChange,
  onSaveRemoteCollabRoutingPolicy,
  onOpenProvider,
}: RemoteCollabHubViewProps) => {
  const { t } = useTranslation();
  const providerNameMap = new Map(
    providerOptions.map((provider) => [provider.providerId, provider.displayName || provider.providerId])
  );

  // 这里统一把路由策略里的 providerId 转成展示名，避免 Hub 摘要继续暴露内部标识影响可读性。
  const formatProviderSummary = (providerIds: string[]): string => {
    const labels = providerIds
      .map((providerId) => providerNameMap.get(providerId) || providerId)
      .filter(Boolean);

    return labels.length > 0 ? labels.join(', ') : '-';
  };

  return (
    <>
      <div className={styles.panel}>
        <div className={styles.toggleRow}>
          <div className={styles.toggleText}>
            <span className={styles.toggleTitle}>
              {t('settings.remoteCollab.enableTitle', { defaultValue: 'Enable remote collaboration' })}
            </span>
            <span className={styles.toggleDesc}>
              {t('settings.remoteCollab.enableDesc', {
                defaultValue: 'When disabled, Telegram stops sending updates and no phone replies will be received.',
              })}
            </span>
          </div>
          <label className={styles.toggleSwitch}>
            <input
              type="checkbox"
              className={styles.toggleInput}
              checked={enabled}
              onChange={(e) => onEnabledChange(e.target.checked)}
            />
            <span className={styles.toggleSlider} />
          </label>
        </div>

        <div className={styles.summaryGrid}>
          <div className={styles.summaryCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.interactiveProvider', { defaultValue: 'Interactive provider' })}
            </span>
            <span className={styles.statusValue}>{formatProviderSummary([interactiveProviderId])}</span>
          </div>
          <div className={styles.summaryCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.notifyProviders', { defaultValue: 'Notify providers' })}
            </span>
            <span className={styles.statusValue}>{formatProviderSummary(notifyProviderIds)}</span>
          </div>
        </div>

        <RoutingPolicyPanel
          providerOptions={providerOptions}
          interactiveProviderId={interactiveProviderId}
          notifyProviderIds={notifyProviderIds}
          onSave={onSaveRemoteCollabRoutingPolicy}
        />
      </div>

      <div className={styles.panel}>
        <h4 className={styles.panelTitle}>
          {t('settings.remoteCollab.providersPanel', { defaultValue: 'Supported Providers' })}
        </h4>
        <ProviderList
          providerOptions={providerOptions}
          interactiveProviderId={interactiveProviderId}
          notifyProviderIds={notifyProviderIds}
          onOpenProvider={onOpenProvider}
        />
      </div>
    </>
  );
};

export default RemoteCollabHubView;
