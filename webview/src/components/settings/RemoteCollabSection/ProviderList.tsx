import { useTranslation } from 'react-i18next';
import type { RemoteCollabProviderOption } from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface ProviderListProps {
  providerOptions: RemoteCollabProviderOption[];
}

/**
 * 已支持 provider 列表。
 * 这里先抽出只读卡片展示，避免 RemoteCollabSection 继续堆积 provider 元信息渲染细节。
 */
const ProviderList = ({ providerOptions }: ProviderListProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.providerGrid}>
      {providerOptions.map((provider) => (
        <div key={provider.providerId} className={styles.providerCard}>
          <div className={styles.providerHeader}>
            <span className={styles.providerName}>{provider.displayName || provider.providerId}</span>
            <span className={styles.providerStatus}>{provider.connectionStatus || 'disabled'}</span>
          </div>
          <div className={styles.providerDesc}>{provider.description || '-'}</div>
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
        </div>
      ))}
    </div>
  );
};

export default ProviderList;
