import type { Dispatch, SetStateAction } from 'react';
import { useTranslation } from 'react-i18next';
import type { FeishuRemoteCollabConfig } from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface FeishuProviderDetailProps {
  feishuDraft: FeishuRemoteCollabConfig;
  setFeishuDraft: Dispatch<SetStateAction<FeishuRemoteCollabConfig>>;
  onSaveRemoteCollabProviderConfig: (providerId: string, config: unknown) => void;
  onRunRemoteCollabProviderAction: (providerId: string, actionKey: string, request?: Record<string, unknown>) => void;
}

/**
 * Feishu 渠道详情视图。
 * 这里单独承接飞书配置、绑定态和最近错误展示，避免继续复用 Gotify 表单导致字段语义混乱。
 */
const FeishuProviderDetail = ({
  feishuDraft,
  setFeishuDraft,
  onSaveRemoteCollabProviderConfig,
  onRunRemoteCollabProviderAction,
}: FeishuProviderDetailProps) => {
  const { t } = useTranslation();

  return (
    <>
      <div className={styles.panel}>
        <h4 className={styles.panelTitle}>
          {t('settings.remoteCollab.feishuConfig', { defaultValue: 'Feishu Settings' })}
        </h4>
        <div className={styles.fieldGrid}>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.feishuAppId', { defaultValue: 'App ID' })}</span>
            <input
              className={styles.input}
              type="text"
              value={feishuDraft.appId}
              onChange={(e) => setFeishuDraft((prev) => ({ ...prev, appId: e.target.value }))}
              placeholder="cli_xxx"
            />
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.feishuAppSecret', { defaultValue: 'App Secret' })}</span>
            <input
              className={styles.input}
              type="password"
              value={feishuDraft.appSecret}
              onChange={(e) => setFeishuDraft((prev) => ({ ...prev, appSecret: e.target.value }))}
              placeholder="secret_xxx"
            />
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.feishuBotName', { defaultValue: 'Bot name' })}</span>
            <input
              className={styles.input}
              type="text"
              value={feishuDraft.botName}
              onChange={(e) => setFeishuDraft((prev) => ({ ...prev, botName: e.target.value }))}
              placeholder="cc-gui-bot"
            />
          </label>
          <label className={styles.field}>
            <span className={styles.label}>{t('settings.remoteCollab.feishuEventMode', { defaultValue: 'Event mode' })}</span>
            <input
              className={styles.input}
              type="text"
              value={feishuDraft.eventMode}
              onChange={(e) => setFeishuDraft((prev) => ({ ...prev, eventMode: e.target.value }))}
              placeholder="long_poll"
            />
          </label>
        </div>
        <div className={styles.actions}>
          <button
            type="button"
            className={`${styles.button} ${styles.primaryButton}`}
            onClick={() => onSaveRemoteCollabProviderConfig('feishu', feishuDraft)}
          >
            {t('settings.remoteCollab.saveFeishu', { defaultValue: 'Save Feishu settings' })}
          </button>
          <button
            type="button"
            className={`${styles.button} ${styles.secondaryButton}`}
            onClick={() => onRunRemoteCollabProviderAction('feishu', 'start_binding')}
          >
            {t('settings.remoteCollab.startFeishuBinding', { defaultValue: 'Start Feishu binding' })}
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
            <span className={styles.statusValue}>{feishuDraft.connectionStatus || '-'}</span>
          </div>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.feishuBoundOpenId', { defaultValue: 'Bound Open ID' })}
            </span>
            <span className={styles.statusValue}>{feishuDraft.boundOpenId || '-'}</span>
          </div>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.feishuBindingToken', { defaultValue: 'Binding token' })}
            </span>
            <span className={styles.statusValue}>{feishuDraft.bindingToken || '-'}</span>
          </div>
          <div className={styles.statusCard}>
            <span className={styles.statusLabel}>
              {t('settings.remoteCollab.feishuBotName', { defaultValue: 'Bot name' })}
            </span>
            <span className={styles.statusValue}>{feishuDraft.botName || '-'}</span>
          </div>
        </div>
        {feishuDraft.lastError && (
          <div className={styles.dangerText}>
            {t('settings.remoteCollab.lastError', { defaultValue: 'Last error: ' })}
            {feishuDraft.lastError}
          </div>
        )}
      </div>
    </>
  );
};

export default FeishuProviderDetail;
