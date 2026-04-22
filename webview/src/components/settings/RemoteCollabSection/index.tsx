import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { RemoteCollabConfig, TelegramRemoteCollabConfig } from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface RemoteCollabSectionProps {
  remoteCollabConfig: RemoteCollabConfig;
  onEnabledChange: (enabled: boolean) => void;
  onSaveTelegramConfig: (telegram: TelegramRemoteCollabConfig) => void;
  onStartTelegramBinding: () => void;
  onSendRemoteTestMessage: (message: string) => void;
}

/**
 * 远程协作设置区块。
 * 这里只维护表单草稿态和展示逻辑，真正的配置落盘与绑定动作都通过回调交给 Hook/后端桥接处理。
 */
const RemoteCollabSection = ({
  remoteCollabConfig,
  onEnabledChange,
  onSaveTelegramConfig,
  onStartTelegramBinding,
  onSendRemoteTestMessage,
}: RemoteCollabSectionProps) => {
  const { t } = useTranslation();
  const [telegramDraft, setTelegramDraft] = useState<TelegramRemoteCollabConfig>(remoteCollabConfig.telegram);
  const [testMessage, setTestMessage] = useState('CC GUI Telegram test message');

  // 当后端推送了新的运行时状态（如绑定成功、当前实例是否接收更新）时，
  // 需要回填到本地草稿，避免设置页还停留在旧值。
  useEffect(() => {
    setTelegramDraft(remoteCollabConfig.telegram);
  }, [remoteCollabConfig.telegram]);

  return (
    <div className={styles.configSection}>
      <h3 className={styles.sectionTitle}>
        {t('settings.remoteCollab.title', { defaultValue: 'Remote Collaboration' })}
      </h3>
      <p className={styles.sectionDesc}>
        {t('settings.remoteCollab.description', {
          defaultValue: 'Sync task states, pending actions, and answers to Telegram so your phone can continue the local IDE flow.',
        })}
      </p>

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
              checked={remoteCollabConfig.enabled}
              onChange={(e) => onEnabledChange(e.target.checked)}
            />
            <span className={styles.toggleSlider} />
          </label>
        </div>

        <div className={styles.toggleRow}>
          <div className={styles.toggleText}>
            <span className={styles.toggleTitle}>
              {t('settings.remoteCollab.pollingTitle', { defaultValue: 'Enable polling receiver' })}
            </span>
            <span className={styles.toggleDesc}>
              {t('settings.remoteCollab.pollingDesc', {
                defaultValue: 'The first version uses getUpdates polling for binding, buttons, and text replies.',
              })}
            </span>
          </div>
          <label className={styles.toggleSwitch}>
            <input
              type="checkbox"
              className={styles.toggleInput}
              checked={telegramDraft.pollingEnabled}
              onChange={(e) => setTelegramDraft((prev) => ({ ...prev, pollingEnabled: e.target.checked }))}
            />
            <span className={styles.toggleSlider} />
          </label>
        </div>

        <div className={styles.toggleRow}>
          <div className={styles.toggleText}>
            <span className={styles.toggleTitle}>
              {t('settings.remoteCollab.singleActiveTitle', { defaultValue: 'Single active instance policy' })}
            </span>
            <span className={styles.toggleDesc}>
              {t('settings.remoteCollab.singleActiveDesc', {
                defaultValue: 'Recommend allowing only one IDE instance per Bot Token to receive updates and avoid polling conflicts.',
              })}
            </span>
          </div>
          <label className={styles.toggleSwitch}>
            <input
              type="checkbox"
              className={styles.toggleInput}
              checked={telegramDraft.singleActive}
              onChange={(e) => setTelegramDraft((prev) => ({ ...prev, singleActive: e.target.checked }))}
            />
            <span className={styles.toggleSlider} />
          </label>
        </div>
      </div>

      <div className={styles.panel}>
        <h4 className={styles.panelTitle}>
          {t('settings.remoteCollab.telegramConfig', { defaultValue: 'Telegram Settings' })}
        </h4>
        <div className={styles.fieldGrid}>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.botToken', { defaultValue: 'Bot Token' })}</span>
            <input
              className={styles.input}
              type="password"
              value={telegramDraft.botToken}
              onChange={(e) => setTelegramDraft((prev) => ({ ...prev, botToken: e.target.value }))}
              placeholder="123456:ABCDEF..."
            />
          </label>

          <label className={styles.field}>
            <span className={styles.label}>
              {t('settings.remoteCollab.pollInterval', { defaultValue: 'Polling interval (seconds)' })}
            </span>
            <input
              className={styles.numberInput}
              type="number"
              min={1}
              value={telegramDraft.pollIntervalSeconds}
              onChange={(e) => setTelegramDraft((prev) => ({
                ...prev,
                pollIntervalSeconds: Math.max(1, Number(e.target.value) || 1),
              }))}
            />
          </label>
        </div>

        <div className={styles.actions}>
          <button
            type="button"
            className={`${styles.button} ${styles.primaryButton}`}
            onClick={() => onSaveTelegramConfig(telegramDraft)}
          >
            {t('settings.remoteCollab.saveTelegram', { defaultValue: 'Save Telegram settings' })}
          </button>
          <button
            type="button"
            className={`${styles.button} ${styles.secondaryButton}`}
            onClick={onStartTelegramBinding}
          >
            {t('settings.remoteCollab.startBinding', { defaultValue: 'Start Telegram binding' })}
          </button>
        </div>
      </div>

      <div className={styles.panel}>
        <h4 className={styles.panelTitle}>
          {t('settings.remoteCollab.statusPanel', { defaultValue: 'Connection Status' })}
        </h4>
        <div className={styles.statusGrid}>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.connectionStatus', { defaultValue: 'Connection Status' })}
            </span>
            <span className={styles.statusValue}>{telegramDraft.connectionStatus || '-'}</span>
          </div>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.receiverStatus', { defaultValue: 'Current receiver status' })}
            </span>
            <span className={styles.statusValue}>
              {telegramDraft.currentInstanceReceivesUpdates
                ? t('settings.remoteCollab.receiverActive', { defaultValue: 'This IDE instance is receiving updates.' })
                : t('settings.remoteCollab.receiverInactive', { defaultValue: 'This IDE instance is not receiving updates.' })}
            </span>
            <span className={styles.statusHint}>
              {telegramDraft.singleActive
                ? t('settings.remoteCollab.receiverHintSingle', { defaultValue: 'singleActive is enabled.' })
                : t('settings.remoteCollab.receiverHintMulti', { defaultValue: 'Multiple instances are allowed, but only one polling instance is recommended.' })}
            </span>
          </div>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.boundUser', { defaultValue: 'Bound user' })}
            </span>
            <span className={styles.statusValue}>{telegramDraft.boundUsername || telegramDraft.boundUserId || '-'}</span>
          </div>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>{t('settings.remoteCollab.chatId', { defaultValue: 'Chat ID' })}</span>
            <span className={styles.statusValue}>{telegramDraft.chatId || '-'}</span>
          </div>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.botUsername', { defaultValue: 'Bot username' })}
            </span>
            <span className={styles.statusValue}>{telegramDraft.botUsername || '-'}</span>
          </div>
        </div>
        {telegramDraft.lastError && (
          <div className={styles.dangerText}>
            {t('settings.remoteCollab.lastError', { defaultValue: 'Last error: ' })}
            {telegramDraft.lastError}
          </div>
        )}
      </div>

      <div className={styles.panel}>
        <h4 className={styles.panelTitle}>
          {t('settings.remoteCollab.testPanel', { defaultValue: 'Test Message' })}
        </h4>
        <label className={styles.field}>
          <span className={styles.label}>
            {t('settings.remoteCollab.testMessage', { defaultValue: 'Message' })}
          </span>
          <textarea
            className={styles.textarea}
            value={testMessage}
            onChange={(e) => setTestMessage(e.target.value)}
          />
        </label>
        <div className={styles.actions}>
          <button
            type="button"
            className={`${styles.button} ${styles.secondaryButton}`}
            onClick={() => onSendRemoteTestMessage(testMessage)}
          >
            {t('settings.remoteCollab.sendTestMessage', { defaultValue: 'Send test message' })}
          </button>
        </div>
      </div>
    </div>
  );
};

export default RemoteCollabSection;
