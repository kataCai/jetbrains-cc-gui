import { useTranslation } from 'react-i18next';
import RemoteDebugSection from '../RemoteDebugSection';
import type {
  FeishuRemoteCollabConfig,
  GotifyWebRemoteCollabConfig,
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
  TelegramRemoteCollabConfig,
} from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface RemoteCollabDebugToolsProps {
  debugEnabled: boolean;
  activeProviderId?: string;
  remoteCollabDebugSnapshot: RemoteCollabDebugSnapshot;
  remoteCollabProviderOperationResult: RemoteCollabProviderOperationResult | null;
  telegramConfig: TelegramRemoteCollabConfig;
  gotifyConfig: GotifyWebRemoteCollabConfig;
  feishuConfig: FeishuRemoteCollabConfig;
  onDebugEnabledChange: (enabled: boolean) => void;
  onStartTelegramBinding: () => void;
  onSendRemoteTestMessage: (message: string) => void;
  onTestRemoteCollabProvider: (providerId: string, actionKey?: string, request?: Record<string, unknown>) => void;
  onRunRemoteCollabProviderAction: (providerId: string, actionKey: string, request?: Record<string, unknown>) => void;
  onRefreshDebugSnapshot: () => void;
}

/**
 * 远程协作公共调试区。
 * 该组件统一承接调试开关与调试面板，避免 Hub/detail 视图重复拼装同一套调试逻辑。
 */
const RemoteCollabDebugTools = ({
  debugEnabled,
  activeProviderId,
  remoteCollabDebugSnapshot,
  remoteCollabProviderOperationResult,
  telegramConfig,
  gotifyConfig,
  feishuConfig,
  onDebugEnabledChange,
  onStartTelegramBinding,
  onSendRemoteTestMessage,
  onTestRemoteCollabProvider,
  onRunRemoteCollabProviderAction,
  onRefreshDebugSnapshot,
}: RemoteCollabDebugToolsProps) => {
  const { t } = useTranslation();

  return (
    <>
      <div className={styles.panel}>
        <div className={styles.toggleRow}>
          <div className={styles.toggleText}>
            <span className={styles.toggleTitle}>
              {t('settings.remoteCollab.debugModeTitle', { defaultValue: 'Enable remote debug tools' })}
            </span>
            <span className={styles.toggleDesc}>
              {t('settings.remoteCollab.debugModeDesc', {
                defaultValue: 'Show request snapshots and provider action results for Telegram, Gotify/Web, and future adapters.',
              })}
            </span>
          </div>
          <label
            className={styles.toggleSwitch}
            aria-label={t('settings.remoteCollab.debugModeTitle', { defaultValue: 'Enable remote debug tools' })}
          >
            <input
              type="checkbox"
              className={styles.toggleInput}
              checked={debugEnabled}
              onChange={(e) => onDebugEnabledChange(e.target.checked)}
            />
            <span className={styles.toggleSlider} />
          </label>
        </div>
      </div>

      {debugEnabled && (
        <div className={styles.panel}>
          <RemoteDebugSection
            debugSnapshot={remoteCollabDebugSnapshot}
            providerOperationResult={remoteCollabProviderOperationResult}
            telegramConfig={telegramConfig}
            gotifyConfig={gotifyConfig}
            feishuConfig={feishuConfig}
            activeProviderId={activeProviderId}
            onStartTelegramBinding={onStartTelegramBinding}
            onSendRemoteTestMessage={onSendRemoteTestMessage}
            onTestRemoteCollabProvider={onTestRemoteCollabProvider}
            onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
            onRefresh={onRefreshDebugSnapshot}
          />
        </div>
      )}
    </>
  );
};

export default RemoteCollabDebugTools;
