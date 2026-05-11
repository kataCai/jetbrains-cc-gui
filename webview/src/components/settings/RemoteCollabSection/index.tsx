import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  FeishuRemoteCollabConfig,
  GotifyWebRemoteCollabConfig,
  RemoteCollabConfig,
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
  RemoteCollabRoutingPolicy,
  TelegramRemoteCollabConfig,
} from '../hooks/useRemoteCollabSettings';
import RemoteCollabHubView from './RemoteCollabHubView';
import RemoteCollabProviderDetailView from './RemoteCollabProviderDetailView';
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
 * 当前阶段将一级 Hub 和二级 Provider 详情彻底分层，父组件只负责状态编排与视图切换，
 * 以便后续继续新增 provider 时不再把所有表单都堆回同一个文件。
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
  const [feishuDraft, setFeishuDraft] = useState<FeishuRemoteCollabConfig>(remoteCollabConfig.providers.feishu);
  const [testMessage, setTestMessage] = useState('CC GUI Telegram test message');
  const [activeProviderId, setActiveProviderId] = useState<string | null>(null);

  // 后端回推运行时配置后，需要回填本地草稿，避免用户刚保存后界面仍停留在旧值。
  useEffect(() => {
    setTelegramDraft(remoteCollabConfig.telegram);
  }, [remoteCollabConfig.telegram]);

  useEffect(() => {
    setGotifyDraft(remoteCollabConfig.providers.gotify_web);
  }, [remoteCollabConfig.providers.gotify_web]);

  useEffect(() => {
    setFeishuDraft(remoteCollabConfig.providers.feishu);
  }, [remoteCollabConfig.providers.feishu]);

  const activeProvider = remoteCollabConfig.providerOptions.find((provider) => provider.providerId === activeProviderId) ?? null;

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

      {activeProvider ? (
        <RemoteCollabProviderDetailView
          activeProvider={activeProvider}
          debugEnabled={remoteCollabConfig.debug.enabled}
          remoteCollabDebugSnapshot={remoteCollabDebugSnapshot}
          remoteCollabProviderOperationResult={remoteCollabProviderOperationResult}
          telegramDraft={telegramDraft}
          gotifyDraft={gotifyDraft}
          feishuDraft={feishuDraft}
          testMessage={testMessage}
          setTelegramDraft={setTelegramDraft}
          setGotifyDraft={setGotifyDraft}
          setFeishuDraft={setFeishuDraft}
          setTestMessage={setTestMessage}
          onBack={() => setActiveProviderId(null)}
          onSaveRemoteCollabProviderConfig={onSaveRemoteCollabProviderConfig}
          onSaveTelegramConfig={onSaveTelegramConfig}
          onStartTelegramBinding={onStartTelegramBinding}
          onSendRemoteTestMessage={onSendRemoteTestMessage}
          onTestRemoteCollabProvider={onTestRemoteCollabProvider}
          onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
          onDebugEnabledChange={onDebugEnabledChange}
          onRefreshDebugSnapshot={onRefreshDebugSnapshot}
        />
      ) : (
        <RemoteCollabHubView
          enabled={remoteCollabConfig.enabled}
          interactiveProviderId={remoteCollabConfig.interactiveProviderId}
          notifyProviderIds={remoteCollabConfig.notifyProviderIds}
          providerOptions={remoteCollabConfig.providerOptions}
          onEnabledChange={onEnabledChange}
          onSaveRemoteCollabRoutingPolicy={onSaveRemoteCollabRoutingPolicy}
          onOpenProvider={setActiveProviderId}
        />
      )}
    </div>
  );
};

export default RemoteCollabSection;
