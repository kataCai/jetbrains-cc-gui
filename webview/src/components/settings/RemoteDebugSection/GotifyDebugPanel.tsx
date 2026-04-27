import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  GotifyWebRemoteCollabConfig,
  RemoteCollabProviderOperationResult,
} from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface GotifyDebugPanelProps {
  gotifyConfig: GotifyWebRemoteCollabConfig;
  providerOperationResult: RemoteCollabProviderOperationResult | null;
  onTestRemoteCollabProvider: (providerId: string, actionKey?: string, request?: Record<string, unknown>) => void;
  onRunRemoteCollabProviderAction: (providerId: string, actionKey: string, request?: Record<string, unknown>) => void;
}

const GOTIFY_PROVIDER_ID = 'gotify_web';

/**
 * Gotify/Web 专属调试面板。
 * 这里统一承接健康检查、测试推送、调试 request 和手动轮询动作，便于后续继续扩展工作台型 provider 的调试入口。
 */
const GotifyDebugPanel = ({
  gotifyConfig,
  providerOperationResult,
  onTestRemoteCollabProvider,
  onRunRemoteCollabProviderAction,
}: GotifyDebugPanelProps) => {
  const { t } = useTranslation();
  const latestResult = providerOperationResult?.providerId === GOTIFY_PROVIDER_ID
    ? providerOperationResult
    : null;

  const latestWorkspaceLink = typeof latestResult?.result?.workspaceLink === 'string'
    ? latestResult.result.workspaceLink
    : '';

  const latestResultSummary = useMemo(() => {
    if (!latestResult) {
      return '';
    }
    const { result } = latestResult;
    const completedRequestIds = Array.isArray(result.completedRequestIds)
      ? result.completedRequestIds.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
      : [];
    if (completedRequestIds.length > 0) {
      return completedRequestIds.join(', ');
    }
    if (typeof result.message === 'string' && result.message.trim().length > 0) {
      return result.message;
    }
    if (typeof result.requestId === 'string' && result.requestId.trim().length > 0) {
      return result.requestId;
    }
    return '';
  }, [latestResult]);

  return (
    <div className={styles.activityCard}>
      <h5 className={styles.activityTitle}>
        {t('settings.remoteCollab.gotifyDebugPanel', { defaultValue: 'Gotify/Web Debug Panel' })}
      </h5>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.connectionStatus', { defaultValue: 'Connection Status' })}
        </span>
        <span className={styles.statusValue}>{gotifyConfig.connectionStatus || '-'}</span>
      </div>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.gotifyDebugWorkspaceLink', { defaultValue: 'Latest workspace link' })}
        </span>
        <span className={styles.statusValue}>{latestWorkspaceLink || '-'}</span>
      </div>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.gotifyDebugResultSummary', { defaultValue: 'Latest poll summary' })}
        </span>
        <span className={styles.statusValue}>{latestResultSummary || '-'}</span>
      </div>
      {gotifyConfig.lastError && (
        <div className={styles.statusHint}>
          {t('settings.remoteCollab.lastError', { defaultValue: 'Last error: ' })}
          {gotifyConfig.lastError}
        </div>
      )}
      <div className={styles.providerDebugActions}>
        <button
          type="button"
          className={styles.button}
          onClick={() => onTestRemoteCollabProvider(GOTIFY_PROVIDER_ID, 'health_check')}
        >
          {t('settings.remoteCollab.gotifyDebugHealthCheck', { defaultValue: 'Run Gotify/Web health check' })}
        </button>
        <button
          type="button"
          className={styles.button}
          onClick={() => onRunRemoteCollabProviderAction(GOTIFY_PROVIDER_ID, 'send_test_event')}
        >
          {t('settings.remoteCollab.gotifyDebugSendEvent', { defaultValue: 'Send Gotify/Web test event' })}
        </button>
        <button
          type="button"
          className={styles.button}
          onClick={() => onRunRemoteCollabProviderAction(GOTIFY_PROVIDER_ID, 'send_test_pending_request')}
        >
          {t('settings.remoteCollab.gotifyDebugCreateRequest', { defaultValue: 'Create Gotify/Web test request' })}
        </button>
        <button
          type="button"
          className={styles.button}
          onClick={() => onRunRemoteCollabProviderAction(GOTIFY_PROVIDER_ID, 'poll_results_once')}
        >
          {t('settings.remoteCollab.gotifyDebugPollOnce', { defaultValue: 'Poll Gotify/Web results once' })}
        </button>
      </div>
    </div>
  );
};

export default GotifyDebugPanel;
