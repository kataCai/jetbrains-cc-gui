import type { Dispatch, SetStateAction } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  FeishuRemoteCollabConfig,
  GotifyWebRemoteCollabConfig,
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
  RemoteCollabProviderOption,
  TelegramRemoteCollabConfig,
} from '../hooks/useRemoteCollabSettings';
import RemoteCollabDebugTools from './RemoteCollabDebugTools';
import FeishuProviderDetail from './FeishuProviderDetail';
import GotifyWebProviderDetail from './GotifyWebProviderDetail';
import TelegramProviderDetail from './TelegramProviderDetail';
import styles from './style.module.less';

interface RemoteCollabProviderDetailViewProps {
  activeProvider: RemoteCollabProviderOption;
  debugEnabled: boolean;
  remoteCollabDebugSnapshot: RemoteCollabDebugSnapshot;
  remoteCollabProviderOperationResult: RemoteCollabProviderOperationResult | null;
  telegramDraft: TelegramRemoteCollabConfig;
  gotifyDraft: GotifyWebRemoteCollabConfig;
  feishuDraft: FeishuRemoteCollabConfig;
  testMessage: string;
  setTelegramDraft: Dispatch<SetStateAction<TelegramRemoteCollabConfig>>;
  setGotifyDraft: Dispatch<SetStateAction<GotifyWebRemoteCollabConfig>>;
  setFeishuDraft: Dispatch<SetStateAction<FeishuRemoteCollabConfig>>;
  setTestMessage: Dispatch<SetStateAction<string>>;
  onBack: () => void;
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
 * 远程协作 provider 二级详情页。
 * 这里统一负责“返回头部 + 当前 provider 主体表单 + 当前 provider 调试区”，
 * 让父组件只保留状态编排职责。
 */
const RemoteCollabProviderDetailView = ({
  activeProvider,
  debugEnabled,
  remoteCollabDebugSnapshot,
  remoteCollabProviderOperationResult,
  telegramDraft,
  gotifyDraft,
  feishuDraft,
  testMessage,
  setTelegramDraft,
  setGotifyDraft,
  setFeishuDraft,
  setTestMessage,
  onBack,
  onSaveRemoteCollabProviderConfig,
  onSaveTelegramConfig,
  onStartTelegramBinding,
  onSendRemoteTestMessage,
  onTestRemoteCollabProvider,
  onRunRemoteCollabProviderAction,
  onDebugEnabledChange,
  onRefreshDebugSnapshot,
}: RemoteCollabProviderDetailViewProps) => {
  const { t } = useTranslation();
  const isTelegramProvider = activeProvider.providerId === 'telegram';
  const isFeishuProvider = activeProvider.providerId === 'feishu';

  return (
    <div className={styles.detailLayout}>
      <div className={styles.detailHeader}>
        <div className={styles.detailHeaderBack}>
          <button
            type="button"
            className={`${styles.button} ${styles.secondaryButton}`}
            onClick={onBack}
          >
            {t('settings.remoteCollab.backToProviders', { defaultValue: 'Back to providers' })}
          </button>
        </div>
        <div className={styles.detailHeaderText}>
          <span className={styles.detailEyebrow}>
            {t('settings.remoteCollab.providerDetailEyebrow', { defaultValue: 'Provider detail' })}
          </span>
          <h4 className={styles.detailTitle}>
            {activeProvider.displayName || activeProvider.providerId}
          </h4>
          <p className={styles.sectionDesc}>{activeProvider.description || '-'}</p>
          <div className={styles.detailMetaRow}>
            <span className={styles.providerStatusPill}>{activeProvider.connectionStatus || 'disabled'}</span>
            {activeProvider.capabilities.map((capability) => (
              <span key={capability} className={styles.capabilityTag}>
                {capability}
              </span>
            ))}
          </div>
        </div>
      </div>

      {isTelegramProvider ? (
        <TelegramProviderDetail
          telegramDraft={telegramDraft}
          testMessage={testMessage}
          setTelegramDraft={setTelegramDraft}
          setTestMessage={setTestMessage}
          onSaveTelegramConfig={onSaveTelegramConfig}
          onStartTelegramBinding={onStartTelegramBinding}
          onSendRemoteTestMessage={onSendRemoteTestMessage}
        />
      ) : isFeishuProvider ? (
        <FeishuProviderDetail
          feishuDraft={feishuDraft}
          setFeishuDraft={setFeishuDraft}
          onSaveRemoteCollabProviderConfig={onSaveRemoteCollabProviderConfig}
          onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
        />
      ) : (
        <GotifyWebProviderDetail
          gotifyDraft={gotifyDraft}
          setGotifyDraft={setGotifyDraft}
          onSaveRemoteCollabProviderConfig={onSaveRemoteCollabProviderConfig}
        />
      )}

      <RemoteCollabDebugTools
        debugEnabled={debugEnabled}
        activeProviderId={activeProvider.providerId}
        remoteCollabDebugSnapshot={remoteCollabDebugSnapshot}
        remoteCollabProviderOperationResult={remoteCollabProviderOperationResult}
        telegramConfig={telegramDraft}
        gotifyConfig={gotifyDraft}
        feishuConfig={feishuDraft}
        onDebugEnabledChange={onDebugEnabledChange}
        onStartTelegramBinding={onStartTelegramBinding}
        onSendRemoteTestMessage={onSendRemoteTestMessage}
        onTestRemoteCollabProvider={onTestRemoteCollabProvider}
        onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
        onRefreshDebugSnapshot={onRefreshDebugSnapshot}
      />
    </div>
  );
};

export default RemoteCollabProviderDetailView;
