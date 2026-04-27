import { useTranslation } from 'react-i18next';
import type {
  GotifyWebRemoteCollabConfig,
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
  TelegramRemoteCollabConfig,
} from '../hooks/useRemoteCollabSettings';
import DebugActivityPanel from './DebugActivityPanel';
import DebugOverviewPanel from './DebugOverviewPanel';
import GotifyDebugPanel from './GotifyDebugPanel';
import ProviderDebugPanel from './ProviderDebugPanel';
import styles from './style.module.less';

interface RemoteDebugSectionProps {
  debugSnapshot: RemoteCollabDebugSnapshot;
  providerOperationResult: RemoteCollabProviderOperationResult | null;
  telegramConfig: TelegramRemoteCollabConfig;
  gotifyConfig: GotifyWebRemoteCollabConfig;
  onStartTelegramBinding: () => void;
  onSendRemoteTestMessage: (message: string) => void;
  onTestRemoteCollabProvider: (providerId: string, actionKey?: string, request?: Record<string, unknown>) => void;
  onRunRemoteCollabProviderAction: (providerId: string, actionKey: string, request?: Record<string, unknown>) => void;
  onRefresh: () => void;
}

/**
 * 远程协作调试面板入口。
 * 这里先承接“通用诊断 + 最近活动列表”两块能力，后续 Telegram / GotifyWeb 专属调试动作可以继续往这里扩展。
 */
const RemoteDebugSection = ({
  debugSnapshot,
  providerOperationResult,
  telegramConfig,
  gotifyConfig,
  onStartTelegramBinding,
  onSendRemoteTestMessage,
  onTestRemoteCollabProvider,
  onRunRemoteCollabProviderAction,
  onRefresh,
}: RemoteDebugSectionProps) => {
  const { t } = useTranslation();

  return (
    <div className={styles.section}>
      <div className={styles.header}>
        <h4 className={styles.title}>
          {t('settings.remoteCollab.debugPanel', { defaultValue: 'Remote Debug Snapshot' })}
        </h4>
        <button
          type="button"
          className={styles.button}
          onClick={onRefresh}
        >
          {t('settings.remoteCollab.refreshDebugSnapshot', { defaultValue: 'Refresh debug snapshot' })}
        </button>
      </div>
      <DebugOverviewPanel
        debugSnapshot={debugSnapshot}
        providerOperationResult={providerOperationResult}
      />
      <div className={styles.activityGrid}>
        <ProviderDebugPanel
          telegramConfig={telegramConfig}
          providerOperationResult={providerOperationResult}
          onStartTelegramBinding={onStartTelegramBinding}
          onSendRemoteTestMessage={onSendRemoteTestMessage}
        />
        <GotifyDebugPanel
          gotifyConfig={gotifyConfig}
          providerOperationResult={providerOperationResult}
          onTestRemoteCollabProvider={onTestRemoteCollabProvider}
          onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
        />
      </div>
      <DebugActivityPanel debugSnapshot={debugSnapshot} />
    </div>
  );
};

export default RemoteDebugSection;
