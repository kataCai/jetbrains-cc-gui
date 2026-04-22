import { useCallback, useState } from 'react';

// 远程协作设置仍然通过现有的 JS -> Java 桥发送，保持与其它设置项一致的通信方式。
const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

export interface TelegramRemoteCollabConfig {
  botToken: string;
  botUsername: string;
  chatId: string;
  boundUserId: string;
  boundUsername: string;
  bindingToken: string;
  pollingEnabled: boolean;
  pollIntervalSeconds: number;
  singleActive: boolean;
  connectionStatus: string;
  lastError: string;
  currentInstanceReceivesUpdates: boolean;
}

export interface RemoteCollabConfig {
  enabled: boolean;
  telegram: TelegramRemoteCollabConfig;
}

export const DEFAULT_REMOTE_COLLAB_CONFIG: RemoteCollabConfig = {
  enabled: false,
  telegram: {
    botToken: '',
    botUsername: '',
    chatId: '',
    boundUserId: '',
    boundUsername: '',
    bindingToken: '',
    pollingEnabled: true,
    pollIntervalSeconds: 1,
    singleActive: true,
    connectionStatus: 'disabled',
    lastError: '',
    currentInstanceReceivesUpdates: false,
  },
};

/**
 * 统一清洗后端返回的远程协作配置。
 * 这里做兜底后，设置页组件可以只面向稳定结构渲染，避免到处判空。
 */
export const normalizeRemoteCollabConfig = (value: unknown): RemoteCollabConfig => {
  const source = value && typeof value === 'object' ? value as Record<string, any> : {};
  const telegramSource = source.telegram && typeof source.telegram === 'object'
    ? source.telegram as Record<string, any>
    : {};
  return {
    enabled: Boolean(source.enabled),
    telegram: {
      botToken: typeof telegramSource.botToken === 'string' ? telegramSource.botToken : '',
      botUsername: typeof telegramSource.botUsername === 'string' ? telegramSource.botUsername : '',
      chatId: typeof telegramSource.chatId === 'string' ? telegramSource.chatId : '',
      boundUserId: typeof telegramSource.boundUserId === 'string' ? telegramSource.boundUserId : '',
      boundUsername: typeof telegramSource.boundUsername === 'string' ? telegramSource.boundUsername : '',
      bindingToken: typeof telegramSource.bindingToken === 'string' ? telegramSource.bindingToken : '',
      pollingEnabled: telegramSource.pollingEnabled !== false,
      pollIntervalSeconds: Math.max(1, Number(telegramSource.pollIntervalSeconds) || 1),
      singleActive: telegramSource.singleActive !== false,
      connectionStatus: typeof telegramSource.connectionStatus === 'string'
        ? telegramSource.connectionStatus
        : 'disabled',
      lastError: typeof telegramSource.lastError === 'string' ? telegramSource.lastError : '',
      currentInstanceReceivesUpdates: Boolean(telegramSource.currentInstanceReceivesUpdates),
    },
  };
};

export interface UseRemoteCollabSettingsReturn {
  remoteCollabConfig: RemoteCollabConfig;
  setRemoteCollabConfig: (config: RemoteCollabConfig | ((prev: RemoteCollabConfig) => RemoteCollabConfig)) => void;
  handleRemoteCollabEnabledChange: (enabled: boolean) => void;
  handleSaveTelegramConfig: (telegram: TelegramRemoteCollabConfig) => void;
  handleStartTelegramBinding: () => void;
  handleSendRemoteTestMessage: (message: string) => void;
}

/**
 * 远程协作设置页状态管理 Hook。
 * 负责本地配置态、消息桥接和对外暴露的操作回调，组件层只关心展示与交互。
 */
export const useRemoteCollabSettings = (): UseRemoteCollabSettingsReturn => {
  const [remoteCollabConfigState, setRemoteCollabConfigState] = useState<RemoteCollabConfig>(DEFAULT_REMOTE_COLLAB_CONFIG);

  const setRemoteCollabConfig = useCallback<UseRemoteCollabSettingsReturn['setRemoteCollabConfig']>((nextConfig) => {
    setRemoteCollabConfigState((prev) => normalizeRemoteCollabConfig(
      typeof nextConfig === 'function' ? nextConfig(prev) : nextConfig
    ));
  }, []);

  const handleRemoteCollabEnabledChange = useCallback((enabled: boolean) => {
    setRemoteCollabConfigState((prev) => ({
      ...prev,
      enabled,
    }));
    sendToJava(`set_remote_collab_enabled:${JSON.stringify({ enabled })}`);
  }, []);

  const handleSaveTelegramConfig = useCallback((telegram: TelegramRemoteCollabConfig) => {
    const normalized = normalizeRemoteCollabConfig({
      enabled: remoteCollabConfigState.enabled,
      telegram,
    });
    setRemoteCollabConfigState(normalized);
    sendToJava(`save_telegram_config:${JSON.stringify({ telegram: normalized.telegram })}`);
  }, [remoteCollabConfigState.enabled]);

  const handleStartTelegramBinding = useCallback(() => {
    sendToJava('start_telegram_binding:{}');
  }, []);

  const handleSendRemoteTestMessage = useCallback((message: string) => {
    sendToJava(`send_remote_test_message:${JSON.stringify({ message })}`);
  }, []);

  return {
    remoteCollabConfig: remoteCollabConfigState,
    setRemoteCollabConfig,
    handleRemoteCollabEnabledChange,
    handleSaveTelegramConfig,
    handleStartTelegramBinding,
    handleSendRemoteTestMessage,
  };
};
