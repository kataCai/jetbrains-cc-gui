import { useTranslation } from 'react-i18next';
import type { RemoteCollabProviderOption } from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface ProviderListProps {
  providerOptions: RemoteCollabProviderOption[];
  interactiveProviderId: string;
  notifyProviderIds: string[];
  onOpenProvider: (providerId: string) => void;
}

/**
 * 已支持 provider 列表。
 * 这里除了展示基础能力外，还直接标记当前承担的交互/通知角色，减少用户在 Hub 页上下对照成本。
 */
const ProviderList = ({
  providerOptions,
  interactiveProviderId,
  notifyProviderIds,
  onOpenProvider,
}: ProviderListProps) => {
  const { t } = useTranslation();

  // 仅对已标记缺失国际化的 provider 描述做前端兜底，避免后端返回旧英文时列表页仍然漏翻译。
  const getLocalizedProviderDescription = (providerId: string, fallback: string): string => {
    if (providerId === 'gotify_web') {
      return t('settings.remoteCollab.gotifyWebDescription', { defaultValue: fallback });
    }
    if (providerId === 'feishu') {
      return t('settings.remoteCollab.feishuDescription', { defaultValue: fallback });
    }
    return fallback;
  };

  return (
    <div className={styles.providerGrid}>
      {providerOptions.map((provider) => {
        const isInteractiveRoute = provider.providerId === interactiveProviderId;
        const isNotifyRoute = notifyProviderIds.includes(provider.providerId);
        const providerDisplayName = provider.displayName || provider.providerId;
        const providerDescription = getLocalizedProviderDescription(provider.providerId, provider.description || '-');

        return (
          <div key={provider.providerId} className={styles.providerCard}>
            <div className={styles.providerHeader}>
              <div className={styles.providerTitleGroup}>
                <span className={styles.providerName}>{providerDisplayName}</span>
                {(isInteractiveRoute || isNotifyRoute) && (
                  <div className={styles.roleTagGroup}>
                    {isInteractiveRoute && (
                      <span className={`${styles.roleTag} ${styles.roleTagPrimary}`}>
                        {t('settings.remoteCollab.providerInteractiveRoute', { defaultValue: 'Interactive route' })}
                      </span>
                    )}
                    {isNotifyRoute && (
                      <span className={`${styles.roleTag} ${styles.roleTagSecondary}`}>
                        {t('settings.remoteCollab.providerNotifyRoute', { defaultValue: 'Notify route' })}
                      </span>
                    )}
                  </div>
                )}
              </div>
              <span className={styles.providerStatus}>{provider.connectionStatus || 'disabled'}</span>
            </div>
            <div className={styles.providerDesc}>{providerDescription}</div>
            <div className={styles.providerMeta}>
              <span>{provider.providerId}</span>
              <span>
                {provider.enabled
                  ? t('settings.remoteCollab.providerEnabled', { defaultValue: 'enabled' })
                  : t('settings.remoteCollab.providerDisabled', { defaultValue: 'disabled' })}
              </span>
              <span>
                {provider.registered
                  ? t('settings.remoteCollab.providerRegistered', { defaultValue: 'registered' })
                  : t('settings.remoteCollab.providerUnregistered', { defaultValue: 'unregistered' })}
              </span>
            </div>
            <div className={styles.capabilityList}>
              {provider.capabilities.map((capability) => (
                <span key={capability} className={styles.capabilityTag}>{capability}</span>
              ))}
            </div>
            <div className={styles.providerCardFooter}>
              <button
                type="button"
                className={`${styles.button} ${styles.secondaryButton}`}
                onClick={() => onOpenProvider(provider.providerId)}
              >
                {t('settings.remoteCollab.openProviderSettings', {
                  provider: providerDisplayName,
                  defaultValue: 'Open {{provider}} settings',
                })}
              </button>
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default ProviderList;
