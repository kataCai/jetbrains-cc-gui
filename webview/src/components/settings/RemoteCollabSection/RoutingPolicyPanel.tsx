import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  RemoteCollabProviderOption,
  RemoteCollabRoutingPolicy,
} from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface RoutingPolicyPanelProps extends RemoteCollabRoutingPolicy {
  providerOptions: RemoteCollabProviderOption[];
  onSave: (policy: RemoteCollabRoutingPolicy) => void;
}

const uniq = (items: string[]): string[] => Array.from(new Set(items.filter(Boolean)));

/**
 * 公共路由策略编辑区。
 * 当前阶段只管理“谁负责交互 / 谁负责通知”，不在这里掺入 provider 细项配置，便于后续扩展更多方案时复用。
 */
const RoutingPolicyPanel = ({
  providerOptions,
  interactiveProviderId,
  notifyProviderIds,
  onSave,
}: RoutingPolicyPanelProps) => {
  const { t } = useTranslation();
  const selectableProviders = useMemo(
    () => providerOptions.filter((provider) => provider.providerId),
    [providerOptions]
  );
  const providerNameMap = useMemo(
    () => new Map(selectableProviders.map((provider) => [provider.providerId, provider.displayName || provider.providerId])),
    [selectableProviders]
  );
  const fallbackProviderId = selectableProviders[0]?.providerId ?? '';
  const [interactiveDraft, setInteractiveDraft] = useState(interactiveProviderId || fallbackProviderId);
  const [notifyDraft, setNotifyDraft] = useState<string[]>(uniq(notifyProviderIds.length > 0 ? notifyProviderIds : [interactiveProviderId || fallbackProviderId]));
  const [isEditing, setIsEditing] = useState(false);

  useEffect(() => {
    const nextInteractive = interactiveProviderId || fallbackProviderId;
    setInteractiveDraft(nextInteractive);
    setNotifyDraft(uniq(notifyProviderIds.length > 0 ? notifyProviderIds : [nextInteractive]));
    setIsEditing(false);
  }, [fallbackProviderId, interactiveProviderId, notifyProviderIds]);

  const toggleNotifyProvider = (providerId: string, checked: boolean) => {
    setNotifyDraft((prev) => checked ? uniq([...prev, providerId]) : prev.filter((item) => item !== providerId));
  };

  // 折叠态摘要同样统一展示 provider 的人类可读名称，避免用户在总览页看到内部 id。
  const formatProviderSummary = (providerIds: string[]) => {
    const labels = providerIds
      .map((providerId) => providerNameMap.get(providerId) || providerId)
      .filter(Boolean);

    return labels.length > 0 ? labels.join(', ') : '-';
  };

  const handleSave = () => {
    const normalizedNotifyIds = uniq(
      notifyDraft.length > 0 ? notifyDraft : [interactiveDraft || fallbackProviderId]
    );
    onSave({
      interactiveProviderId: interactiveDraft || fallbackProviderId,
      notifyProviderIds: normalizedNotifyIds,
    });
    setIsEditing(false);
  };

  return (
    <div className={styles.routingPanel}>
      <h4 className={styles.panelTitle}>
        {t('settings.remoteCollab.routingPanel', { defaultValue: 'Routing policy' })}
      </h4>

      {isEditing ? (
        <>
          <div className={styles.routingColumns}>
            <div className={styles.routingGroup}>
              <span className={styles.label}>
                {t('settings.remoteCollab.routingInteractiveLabel', { defaultValue: 'Interactive provider' })}
              </span>
              <div className={styles.routingOptions}>
                {selectableProviders.map((provider) => (
                  <label key={`interactive-${provider.providerId}`} className={styles.choiceItem}>
                    <input
                      type="radio"
                      name="remote-collab-interactive-provider"
                      checked={interactiveDraft === provider.providerId}
                      onChange={() => setInteractiveDraft(provider.providerId)}
                      aria-label={`${t('settings.remoteCollab.routingInteractiveLabel', { defaultValue: 'Interactive provider' })}: ${provider.displayName || provider.providerId}`}
                    />
                    <span>{provider.displayName || provider.providerId}</span>
                  </label>
                ))}
              </div>
            </div>

            <div className={styles.routingGroup}>
              <span className={styles.label}>
                {t('settings.remoteCollab.routingNotifyLabel', { defaultValue: 'Notify providers' })}
              </span>
              <div className={styles.routingOptions}>
                {selectableProviders.map((provider) => (
                  <label key={`notify-${provider.providerId}`} className={styles.choiceItem}>
                    <input
                      type="checkbox"
                      checked={notifyDraft.includes(provider.providerId)}
                      onChange={(event) => toggleNotifyProvider(provider.providerId, event.target.checked)}
                      aria-label={`Notify provider: ${provider.displayName || provider.providerId}`}
                    />
                    <span>{provider.displayName || provider.providerId}</span>
                  </label>
                ))}
              </div>
            </div>
          </div>

          <div className={styles.actions}>
            <button type="button" className={`${styles.button} ${styles.secondaryButton}`} onClick={() => setIsEditing(false)}>
              {t('settings.remoteCollab.cancelRoutingEdit', { defaultValue: 'Cancel' })}
            </button>
            <button type="button" className={`${styles.button} ${styles.primaryButton}`} onClick={handleSave}>
              {t('settings.remoteCollab.saveRoutingPolicy', { defaultValue: 'Save routing policy' })}
            </button>
          </div>
        </>
      ) : (
        <>
          <p className={styles.routingSummaryNote}>
            {t('settings.remoteCollab.routingSummaryNote', {
              defaultValue: 'Edit which provider handles phone interaction and which channels only receive notifications.',
            })}
          </p>
          <div className={styles.summaryGrid}>
            <div className={styles.summaryCard}>
              <span className={styles.statusLabel}>
                {t('settings.remoteCollab.routingInteractiveLabel', { defaultValue: 'Interactive provider' })}
              </span>
              <span className={styles.statusValue}>
                {formatProviderSummary([interactiveProviderId || fallbackProviderId])}
              </span>
            </div>
            <div className={styles.summaryCard}>
              <span className={styles.statusLabel}>
                {t('settings.remoteCollab.routingNotifyLabel', { defaultValue: 'Notify providers' })}
              </span>
              <span className={styles.statusValue}>
                {formatProviderSummary(notifyProviderIds.length > 0 ? notifyProviderIds : [interactiveProviderId || fallbackProviderId])}
              </span>
            </div>
          </div>
          <div className={styles.actions}>
            <button type="button" className={`${styles.button} ${styles.secondaryButton}`} onClick={() => setIsEditing(true)}>
              {t('settings.remoteCollab.editRoutingPolicy', { defaultValue: 'Edit routing' })}
            </button>
          </div>
        </>
      )}
    </div>
  );
};

export default RoutingPolicyPanel;
