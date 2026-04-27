import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import RemoteDebugSection from '../RemoteDebugSection';
import type {
  GotifyWebRemoteCollabConfig,
  RemoteCollabConfig,
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
  RemoteCollabRoutingPolicy,
  TelegramRemoteCollabConfig,
} from '../hooks/useRemoteCollabSettings';
import ProviderList from './ProviderList';
import RoutingPolicyPanel from './RoutingPolicyPanel';
import styles from './style.module.less';

interface RemoteCollabSectionProps {
  remoteCollabConfig: RemoteCollabConfig;
  remoteCollabDebugSnapshot: RemoteCollabDebugSnapshot;
  remoteCollabProviderOperationResult: RemoteCollabProviderOperationResult | null;
  onEnabledChange: (enabled: boolean) => void;
  onSaveRemoteCollabRoutingPolicy: (policy: RemoteCollabRoutingPolicy) => void;
  onSaveRemoteCollabProviderConfig: (providerId: string, config: unknown) => void;
  onSaveTelegramConfig: (telegram: TelegramRemoteCollabConfig) => void;
  onStartTelegramBinding: () => void;
  onSendRemoteTestMessage: (message: string) => void;
  onTestRemoteCollabProvider: (providerId: string, actionKey?: string, request?: Record<string, unknown>) => void;
  onRunRemoteCollabProviderAction: (providerId: string, actionKey: string, request?: Record<string, unknown>) => void;
  onDebugEnabledChange: (enabled: boolean) => void;
  onRefreshDebugSnapshot: () => void;
}

/**
 * 远程协作设置区块。
 * 当前阶段已经拆出“公共路由策略 + provider 列表 + Telegram/Gotify 表单 + 调试面板”，
 * 让多方案骨架与每个 provider 的细项配置保持解耦。
 */
const RemoteCollabSection = ({
  remoteCollabConfig,
  remoteCollabDebugSnapshot,
  remoteCollabProviderOperationResult,
  onEnabledChange,
  onSaveRemoteCollabRoutingPolicy,
  onSaveRemoteCollabProviderConfig,
  onSaveTelegramConfig,
  onStartTelegramBinding,
  onSendRemoteTestMessage,
  onTestRemoteCollabProvider,
  onRunRemoteCollabProviderAction,
  onDebugEnabledChange,
  onRefreshDebugSnapshot,
}: RemoteCollabSectionProps) => {
  const { t } = useTranslation();
  const [telegramDraft, setTelegramDraft] = useState<TelegramRemoteCollabConfig>(remoteCollabConfig.telegram);
  const [gotifyDraft, setGotifyDraft] = useState<GotifyWebRemoteCollabConfig>(remoteCollabConfig.providers.gotify_web);
  const [testMessage, setTestMessage] = useState('CC GUI Telegram test message');

  // 后端回推运行时配置后，需要回填本地草稿，避免用户刚保存后界面仍停留在旧值。
  useEffect(() => {
    setTelegramDraft(remoteCollabConfig.telegram);
  }, [remoteCollabConfig.telegram]);

  useEffect(() => {
    setGotifyDraft(remoteCollabConfig.providers.gotify_web);
  }, [remoteCollabConfig.providers.gotify_web]);

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
              checked={remoteCollabConfig.debug.enabled}
              onChange={(e) => onDebugEnabledChange(e.target.checked)}
            />
            <span className={styles.toggleSlider} />
          </label>
        </div>

        <div className={styles.summaryGrid}>
          <div className={styles.summaryCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.interactiveProvider', { defaultValue: 'Interactive provider' })}
            </span>
            <span className={styles.statusValue}>{remoteCollabConfig.interactiveProviderId || '-'}</span>
          </div>
          <div className={styles.summaryCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.notifyProviders', { defaultValue: 'Notify providers' })}
            </span>
            <span className={styles.statusValue}>{remoteCollabConfig.notifyProviderIds.join(', ') || '-'}</span>
          </div>
        </div>

        <RoutingPolicyPanel
          providerOptions={remoteCollabConfig.providerOptions}
          interactiveProviderId={remoteCollabConfig.interactiveProviderId}
          notifyProviderIds={remoteCollabConfig.notifyProviderIds}
          onSave={onSaveRemoteCollabRoutingPolicy}
        />
      </div>

      <div className={styles.panel}>
        <h4 className={styles.panelTitle}>
          {t('settings.remoteCollab.providersPanel', { defaultValue: 'Supported Providers' })}
        </h4>
        <ProviderList providerOptions={remoteCollabConfig.providerOptions} />
      </div>

      {remoteCollabConfig.debug.enabled && (
        <div className={styles.panel}>
          <RemoteDebugSection
            debugSnapshot={remoteCollabDebugSnapshot}
            providerOperationResult={remoteCollabProviderOperationResult}
            telegramConfig={telegramDraft}
            gotifyConfig={gotifyDraft}
            onStartTelegramBinding={onStartTelegramBinding}
            onSendRemoteTestMessage={onSendRemoteTestMessage}
            onTestRemoteCollabProvider={onTestRemoteCollabProvider}
            onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
            onRefresh={onRefreshDebugSnapshot}
          />
        </div>
      )}

      <div className={styles.panel}>
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
          {t('settings.remoteCollab.gotifyConfig', { defaultValue: 'Gotify/Web Settings' })}
        </h4>
        <div className={styles.fieldGrid}>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.gotifyServerUrl', { defaultValue: 'Gotify server URL' })}</span>
            <input
              className={styles.input}
              type="text"
              value={gotifyDraft.serverUrl}
              onChange={(e) => setGotifyDraft((prev) => ({ ...prev, serverUrl: e.target.value }))}
              placeholder="https://gotify.example.com"
            />
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.gotifyWorkspaceUrl', { defaultValue: 'Workspace base URL' })}</span>
            <input
              className={styles.input}
              type="text"
              value={gotifyDraft.workspaceBaseUrl}
              onChange={(e) => setGotifyDraft((prev) => ({ ...prev, workspaceBaseUrl: e.target.value }))}
              placeholder="https://workspace.example.com"
            />
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.gotifyApiToken', { defaultValue: 'API token' })}</span>
            <input
              className={styles.input}
              type="password"
              value={gotifyDraft.apiToken}
              onChange={(e) => setGotifyDraft((prev) => ({ ...prev, apiToken: e.target.value }))}
              placeholder="gotify-token"
            />
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.gotifyPollInterval', { defaultValue: 'Result poll interval (seconds)' })}</span>
            <input
              className={styles.numberInput}
              type="number"
              min={1}
              value={gotifyDraft.resultPollIntervalSeconds}
              onChange={(e) => setGotifyDraft((prev) => ({
                ...prev,
                resultPollIntervalSeconds: Math.max(1, Number(e.target.value) || 1),
              }))}
            />
          </label>
        </div>
        <div className={styles.actions}>
          <button
            type="button"
            className={`${styles.button} ${styles.primaryButton}`}
            onClick={() => onSaveRemoteCollabProviderConfig('gotify_web', gotifyDraft)}
          >
            {t('settings.remoteCollab.saveGotify', { defaultValue: 'Save Gotify/Web settings' })}
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
