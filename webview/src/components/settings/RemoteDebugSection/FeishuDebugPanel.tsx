import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  FeishuRemoteCollabConfig,
  RemoteCollabProviderOperationResult,
} from '../hooks/useRemoteCollabSettings';
import styles from './style.module.less';

interface FeishuDebugPanelProps {
  feishuConfig: FeishuRemoteCollabConfig;
  providerOperationResult: RemoteCollabProviderOperationResult | null;
  onTestRemoteCollabProvider: (providerId: string, actionKey?: string, request?: Record<string, unknown>) => void;
}

const FEISHU_PROVIDER_ID = 'feishu';
const FEISHU_DEBUG_TEST_MESSAGE = 'CC GUI Feishu test message';
const FEISHU_DEFAULT_DEBUG_MESSAGE = '/cc-bind <bindingToken>';

/**
 * Feishu 专属调试面板。
 * 这里集中暴露健康检查和测试消息动作，方便联调时直接核对最近错误和绑定状态。
 */
const FeishuDebugPanel = ({
  feishuConfig,
  providerOperationResult,
  onTestRemoteCollabProvider,
}: FeishuDebugPanelProps) => {
  const { t } = useTranslation();
  const [inboundOpenId, setInboundOpenId] = useState(() => feishuConfig.boundOpenId || '');
  const [inboundChatId, setInboundChatId] = useState(() => feishuConfig.boundChatId || '');
  const [inboundMessage, setInboundMessage] = useState(FEISHU_DEFAULT_DEBUG_MESSAGE);
  const feishuActionMessage = providerOperationResult?.providerId === FEISHU_PROVIDER_ID
    && typeof providerOperationResult.result?.message === 'string'
    ? providerOperationResult.result.message
    : '';

  return (
    <div className={styles.activityCard}>
      <h5 className={styles.activityTitle}>
        {t('settings.remoteCollab.feishuDebugPanel', { defaultValue: 'Feishu Debug Panel' })}
      </h5>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.connectionStatus', { defaultValue: 'Connection Status' })}
        </span>
        <span className={styles.statusValue}>{feishuConfig.connectionStatus || '-'}</span>
      </div>
      <div className={styles.providerDebugMeta}>
        <span className={styles.statusLabel}>
          {t('settings.remoteCollab.feishuBoundOpenId', { defaultValue: 'Bound Open ID' })}
        </span>
        <span className={styles.statusValue}>{feishuConfig.boundOpenId || '-'}</span>
      </div>
      {feishuConfig.lastError && (
        <div className={styles.statusHint}>
          {t('settings.remoteCollab.lastError', { defaultValue: 'Last error: ' })}
          {feishuConfig.lastError}
        </div>
      )}
      {feishuActionMessage && (
        <div className={styles.statusHint}>
          {t('settings.remoteCollab.feishuDebugLastAction', { defaultValue: 'Last Feishu action: ' })}
          {feishuActionMessage}
        </div>
      )}
      <div className={styles.providerDebugActions}>
        <button
          type="button"
          className={styles.button}
          onClick={() => onTestRemoteCollabProvider(FEISHU_PROVIDER_ID, 'health_check')}
        >
          {t('settings.remoteCollab.feishuDebugHealthCheck', { defaultValue: 'Run Feishu health check' })}
        </button>
        <button
          type="button"
          className={styles.button}
          onClick={() => onTestRemoteCollabProvider(
            FEISHU_PROVIDER_ID,
            'send_test_message',
            { message: FEISHU_DEBUG_TEST_MESSAGE }
          )}
        >
          {t('settings.remoteCollab.feishuDebugSendTestMessage', { defaultValue: 'Send Feishu test message' })}
        </button>
      </div>
      <div className={styles.providerDebugForm}>
        <label className={styles.providerDebugField}>
          <span className={styles.statusLabel}>
            {t('settings.remoteCollab.feishuDebugOpenId', { defaultValue: 'Inbound Open ID' })}
          </span>
          <input
            className={styles.providerDebugInput}
            type="text"
            value={inboundOpenId}
            onChange={(event) => setInboundOpenId(event.target.value)}
            placeholder="ou_xxx"
          />
        </label>
        <label className={styles.providerDebugField}>
          <span className={styles.statusLabel}>
            {t('settings.remoteCollab.feishuDebugChatId', { defaultValue: 'Inbound Chat ID' })}
          </span>
          <input
            className={styles.providerDebugInput}
            type="text"
            value={inboundChatId}
            onChange={(event) => setInboundChatId(event.target.value)}
            placeholder="oc_xxx"
          />
        </label>
        <label className={styles.providerDebugField}>
          <span className={styles.statusLabel}>
            {t('settings.remoteCollab.feishuDebugMessage', { defaultValue: 'Inbound command' })}
          </span>
          <input
            className={styles.providerDebugInput}
            type="text"
            value={inboundMessage}
            onChange={(event) => setInboundMessage(event.target.value)}
            placeholder={FEISHU_DEFAULT_DEBUG_MESSAGE}
          />
        </label>
        <button
          type="button"
          className={styles.button}
          onClick={() => onTestRemoteCollabProvider(
            FEISHU_PROVIDER_ID,
            'handle_inbound_message',
            {
              openId: inboundOpenId,
              chatId: inboundChatId,
              message: inboundMessage,
            }
          )}
        >
          {t('settings.remoteCollab.feishuDebugInjectInbound', { defaultValue: 'Inject Feishu inbound command' })}
        </button>
      </div>
    </div>
  );
};

export default FeishuDebugPanel;
