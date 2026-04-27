import { useTranslation } from 'react-i18next';
import type { RemoteCollabDebugSnapshot } from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface DebugActivityPanelProps {
  debugSnapshot: RemoteCollabDebugSnapshot;
}

/**
 * 远程协作最近活动列表。
 * 通过把 request / error / action 分开展示，方便联调时快速定位卡在“发出请求”“provider 报错”还是“动作回收”。
 */
const DebugActivityPanel = ({ debugSnapshot }: DebugActivityPanelProps) => {
  const { t } = useTranslation();

  const renderItems = (items: Record<string, unknown>[], fields: string[]) => {
    if (items.length === 0) {
      return (
        <div className={styles.emptyText}>
          {t('settings.remoteCollab.debugEmpty', { defaultValue: 'No recent activity.' })}
        </div>
      );
    }

    return (
      <ul className={styles.activityList}>
        {items.map((item, index) => {
          const text = fields
            .map((field) => item[field])
            .filter((value): value is string | number => typeof value === 'string' || typeof value === 'number')
            .join(' / ');
          return (
            <li key={`${text}-${index}`} className={styles.activityItem}>
              {text || '-'}
            </li>
          );
        })}
      </ul>
    );
  };

  return (
    <div className={styles.activityGrid}>
      <div className={styles.activityCard}>
        <h5 className={styles.activityTitle}>
          {t('settings.remoteCollab.debugRequestsTitle', { defaultValue: 'Recent requests' })}
        </h5>
        {renderItems(debugSnapshot.recentRequests, ['requestId', 'summary', 'providerId'])}
      </div>
      <div className={styles.activityCard}>
        <h5 className={styles.activityTitle}>
          {t('settings.remoteCollab.debugErrorsTitle', { defaultValue: 'Recent errors' })}
        </h5>
        {renderItems(debugSnapshot.recentErrors, ['providerId', 'message', 'createdAt'])}
      </div>
      <div className={styles.activityCard}>
        <h5 className={styles.activityTitle}>
          {t('settings.remoteCollab.debugActionsTitle', { defaultValue: 'Recent actions' })}
        </h5>
        {renderItems(debugSnapshot.recentActions, ['providerId', 'actionKey', 'createdAt'])}
      </div>
    </div>
  );
};

export default DebugActivityPanel;
