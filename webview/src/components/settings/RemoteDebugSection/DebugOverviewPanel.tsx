import { useTranslation } from 'react-i18next';
import type {
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
} from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface DebugOverviewPanelProps {
  debugSnapshot: RemoteCollabDebugSnapshot;
  providerOperationResult: RemoteCollabProviderOperationResult | null;
}

/**
 * 远程协作调试概览面板。
 * 这里只展示最核心的运行态计数和最近一次 provider 动作摘要，方便先快速判断链路是否活着。
 */
const DebugOverviewPanel = ({
  debugSnapshot,
  providerOperationResult,
}: DebugOverviewPanelProps) => {
  const { t } = useTranslation();
  const lastProviderActionMessage = typeof providerOperationResult?.result?.message === 'string'
    ? providerOperationResult.result.message
    : '';

  return (
    <div className={styles.section}>
      <div className={styles.statusGrid}>
        <div className={styles.statusCard}>
          <span className={styles.statusLabel}>
            {t('settings.remoteCollab.debugRecentRequests', { defaultValue: 'Recent request count' })}
          </span>
          <span className={styles.statusValue}>{debugSnapshot.recentRequests.length}</span>
        </div>
        <div className={styles.statusCard}>
          <span className={styles.statusLabel}>
            {t('settings.remoteCollab.debugRecentErrors', { defaultValue: 'Recent error count' })}
          </span>
          <span className={styles.statusValue}>{debugSnapshot.recentErrors.length}</span>
        </div>
        <div className={styles.statusCard}>
          <span className={styles.statusLabel}>
            {t('settings.remoteCollab.debugRecentActions', { defaultValue: 'Recent action count' })}
          </span>
          <span className={styles.statusValue}>{debugSnapshot.recentActions.length}</span>
        </div>
        <div className={styles.statusCard}>
          <span className={styles.statusLabel}>
            {t('settings.remoteCollab.debugLastAction', { defaultValue: 'Last provider action' })}
          </span>
          <span className={styles.statusValue}>
            {providerOperationResult
              ? `${providerOperationResult.providerId} / ${providerOperationResult.actionKey}`
              : '-'}
          </span>
        </div>
      </div>
      {lastProviderActionMessage && (
        <div className={styles.statusHint}>
          {t('settings.remoteCollab.debugLastActionMessage', { defaultValue: 'Last action message: ' })}
          {lastProviderActionMessage}
        </div>
      )}
    </div>
  );
};

export default DebugOverviewPanel;
