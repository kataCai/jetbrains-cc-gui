import type { Dispatch, SetStateAction } from 'react';
import { useTranslation } from 'react-i18next';
import type { GotifyWebRemoteCollabConfig } from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface GotifyWebProviderDetailProps {
  gotifyDraft: GotifyWebRemoteCollabConfig;
  setGotifyDraft: Dispatch<SetStateAction<GotifyWebRemoteCollabConfig>>;
  onSaveRemoteCollabProviderConfig: (providerId: string, config: unknown) => void;
}

/**
 * Gotify/Web 渠道详情视图。
 * 该组件只负责工作台链路所需的配置录入，避免主设置页继续堆积 provider 特有字段。
 */
const GotifyWebProviderDetail = ({
  gotifyDraft,
  setGotifyDraft,
  onSaveRemoteCollabProviderConfig,
}: GotifyWebProviderDetailProps) => {
  const { t } = useTranslation();

  return (
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
  );
};

export default GotifyWebProviderDetail;
