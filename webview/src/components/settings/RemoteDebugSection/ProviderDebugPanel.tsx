import { useTranslation } from 'react-i18next';
import type {
  RemoteCollabProviderOperationResult,
  TelegramRemoteCollabConfig,
} from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface ProviderDebugPanelProps {
  telegramConfig: TelegramRemoteCollabConfig;
  providerOperationResult: RemoteCollabProviderOperationResult | null;
  onStartTelegramBinding: () => void;
  onSendRemoteTestMessage: (message: string) => void;
}

const TELEGRAM_DEBUG_TEST_MESSAGE = 'CC GUI Telegram test message';

/**
 * Telegram 专属调试面板。
 * 当前先承接 Telegram 的绑定、测试消息和接收状态诊断，后续 Gotify/Web 接入时可复用同一面板结构继续扩展。
 */
const ProviderDebugPanel = ({
  telegramConfig,
  providerOperationResult,
  onStartTelegramBinding,
  onSendRemoteTestMessage,
}: ProviderDebugPanelProps) => {
  const { t } = useTranslation();
  const telegramActionMessage = providerOperationResult?.providerId === 'telegram'
    && typeof providerOperationResult.result?.message === 'string'
    ? providerOperationResult.result.message
    : '';

  return (
    <div className={styles.activityCard}>
      <h5 className={styles.activityTitle}>
        {t('settings.remoteCollab.telegramDebugPanel', { defaultValue: 'Telegram Debug Panel' })}
      </h5>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.connectionStatus', { defaultValue: 'Connection Status' })}
        </span>
        <span className={styles.statusValue}>{telegramConfig.connectionStatus || '-'}</span>
      </div>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.receiverStatus', { defaultValue: 'Current receiver status' })}
        </span>
        <span className={styles.statusValue}>
          {telegramConfig.currentInstanceReceivesUpdates
            ? t('settings.remoteCollab.receiverActive', { defaultValue: 'This IDE instance is receiving updates.' })
            : t('settings.remoteCollab.receiverInactive', { defaultValue: 'This IDE instance is not receiving updates.' })}
        </span>
      </div>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.boundUser', { defaultValue: 'Bound user' })}
        </span>
        <span className={styles.statusValue}>
          {telegramConfig.boundUsername || telegramConfig.boundUserId || '-'}
        </span>
      </div>
      {telegramActionMessage && (
        <div className={styles.statusHint}>
          {t('settings.remoteCollab.telegramDebugLastAction', { defaultValue: 'Last Telegram action: ' })}
          {telegramActionMessage}
        </div>
      )}
      <div className={styles.providerDebugActions}>
        <button
          type="button"
          className={styles.button}
          onClick={onStartTelegramBinding}
        >
          {t('settings.remoteCollab.startBinding', { defaultValue: 'Start Telegram binding' })}
        </button>
        <button
          type="button"
          className={styles.button}
          onClick={() => onSendRemoteTestMessage(TELEGRAM_DEBUG_TEST_MESSAGE)}
        >
          {t('settings.remoteCollab.telegramDebugSendTestMessage', { defaultValue: 'Send Telegram test message' })}
        </button>
      </div>
    </div>
  );
};

export default ProviderDebugPanel;
