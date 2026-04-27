import { useCallback, useState } from 'react';

const TELEGRAM_PROVIDER_ID = 'telegram';
const GOTIFY_WEB_PROVIDER_ID = 'gotify_web';

// WebView 与 Java 侧桥接统一走 sendToJava，避免各个设置面板自行拼接窗口对象访问。
const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

const isRecord = (value: unknown): value is Record<string, any> =>
  Boolean(value) && typeof value === 'object' && !Array.isArray(value);

const normalizeString = (value: unknown, fallback = ''): string =>
  typeof value === 'string' && value.trim() ? value.trim() : fallback;

const normalizePositiveInt = (value: unknown, fallback: number): number => {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? Math.max(1, Math.trunc(parsed)) : fallback;
};

export interface TelegramRemoteCollabConfig {
  enabled: boolean;
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

export interface GotifyWebRemoteCollabConfig {
  enabled: boolean;
  serverUrl: string;
  apiToken: string;
  workspaceBaseUrl: string;
  resultPollIntervalSeconds: number;
  connectionStatus: string;
  lastError: string;
}

export interface RemoteCollabDebugConfig {
  enabled: boolean;
}

export interface RemoteCollabDebugSnapshot {
  recentRequests: Record<string, unknown>[];
  recentErrors: Record<string, unknown>[];
  recentActions: Record<string, unknown>[];
}

export interface RemoteCollabProviderOperationResult {
  operationType: string;
  providerId: string;
  actionKey: string;
  result: Record<string, unknown>;
}

export interface RemoteCollabProviderOption {
  providerId: string;
  displayName: string;
  description: string;
  capabilities: string[];
  registered: boolean;
  enabled: boolean;
  connectionStatus: string;
  config: Record<string, unknown>;
  currentInstanceReceivesUpdates?: boolean;
}

export interface RemoteCollabRoutingPolicy {
  interactiveProviderId: string;
  notifyProviderIds: string[];
}

export type RemoteCollabProviderConfig = Record<string, unknown>;
type NormalizedProviderConfig =
  | TelegramRemoteCollabConfig
  | GotifyWebRemoteCollabConfig
  | RemoteCollabProviderConfig;

export interface RemoteCollabProvidersConfig {
  telegram: TelegramRemoteCollabConfig;
  gotify_web: GotifyWebRemoteCollabConfig;
  [providerId: string]: TelegramRemoteCollabConfig | GotifyWebRemoteCollabConfig | RemoteCollabProviderConfig;
}

export interface RemoteCollabConfig extends RemoteCollabRoutingPolicy {
  enabled: boolean;
  debug: RemoteCollabDebugConfig;
  providerOptions: RemoteCollabProviderOption[];
  providers: RemoteCollabProvidersConfig;
  // 保留旧字段给未完全重构的 Telegram UI 消费，阶段 5 结束后再删除。
  telegram: TelegramRemoteCollabConfig;
}

const createDefaultTelegramConfig = (): TelegramRemoteCollabConfig => ({
  enabled: true,
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
});

const createDefaultGotifyWebConfig = (): GotifyWebRemoteCollabConfig => ({
  enabled: false,
  serverUrl: '',
  apiToken: '',
  workspaceBaseUrl: '',
  resultPollIntervalSeconds: 3,
  connectionStatus: 'disabled',
  lastError: '',
});

const createDefaultProviderOptions = (
  telegram: TelegramRemoteCollabConfig,
  gotifyWeb: GotifyWebRemoteCollabConfig
): RemoteCollabProviderOption[] => ([
  {
    providerId: TELEGRAM_PROVIDER_ID,
    displayName: 'Telegram',
    description: 'Inline chat collaboration',
    capabilities: ['BINDING', 'INLINE_ACTION_CALLBACK', 'PENDING_REQUEST_PUSH', 'TASK_EVENT_PUSH'],
    registered: false,
    enabled: telegram.enabled,
    connectionStatus: telegram.connectionStatus,
    config: { ...telegram },
    currentInstanceReceivesUpdates: telegram.currentInstanceReceivesUpdates,
  },
  {
    providerId: GOTIFY_WEB_PROVIDER_ID,
    displayName: 'Gotify + Web',
    description: 'Workspace based collaboration',
    capabilities: ['HEALTH_CHECK', 'PENDING_REQUEST_PUSH', 'RESULT_POLLING', 'TASK_EVENT_PUSH', 'WORKSPACE_LINK'],
    registered: false,
    enabled: gotifyWeb.enabled,
    connectionStatus: gotifyWeb.connectionStatus,
    config: { ...gotifyWeb },
  },
]);

const normalizeTelegramConfig = (value: unknown): TelegramRemoteCollabConfig => {
  const source = isRecord(value) ? value : {};
  return {
    enabled: source.enabled !== false,
    botToken: typeof source.botToken === 'string' ? source.botToken : '',
    botUsername: typeof source.botUsername === 'string' ? source.botUsername : '',
    chatId: typeof source.chatId === 'string' ? source.chatId : '',
    boundUserId: typeof source.boundUserId === 'string' ? source.boundUserId : '',
    boundUsername: typeof source.boundUsername === 'string' ? source.boundUsername : '',
    bindingToken: typeof source.bindingToken === 'string' ? source.bindingToken : '',
    pollingEnabled: source.pollingEnabled !== false,
    pollIntervalSeconds: normalizePositiveInt(source.pollIntervalSeconds, 1),
    singleActive: source.singleActive !== false,
    connectionStatus: typeof source.connectionStatus === 'string' ? source.connectionStatus : 'disabled',
    lastError: typeof source.lastError === 'string' ? source.lastError : '',
    currentInstanceReceivesUpdates: Boolean(source.currentInstanceReceivesUpdates),
  };
};

const normalizeGotifyWebConfig = (value: unknown): GotifyWebRemoteCollabConfig => {
  const source = isRecord(value) ? value : {};
  return {
    enabled: Boolean(source.enabled),
    serverUrl: typeof source.serverUrl === 'string' ? source.serverUrl : '',
    apiToken: typeof source.apiToken === 'string' ? source.apiToken : '',
    workspaceBaseUrl: typeof source.workspaceBaseUrl === 'string' ? source.workspaceBaseUrl : '',
    resultPollIntervalSeconds: normalizePositiveInt(source.resultPollIntervalSeconds, 3),
    connectionStatus: typeof source.connectionStatus === 'string' ? source.connectionStatus : 'disabled',
    lastError: typeof source.lastError === 'string' ? source.lastError : '',
  };
};

const normalizeDebugConfig = (value: unknown): RemoteCollabDebugConfig => {
  const source = isRecord(value) ? value : {};
  return {
    enabled: Boolean(source.enabled),
  };
};

const normalizeNotifyProviderIds = (value: unknown, interactiveProviderId: string): string[] => {
  const source = Array.isArray(value) ? value : [];
  const normalized = Array.from(
    new Set(
      source
        .map((item) => normalizeString(item, ''))
        .filter(Boolean)
    )
  );
  return normalized.length > 0 ? normalized : [interactiveProviderId];
};

const normalizeExtraProviders = (value: unknown): Record<string, RemoteCollabProviderConfig> => {
  const source = isRecord(value) ? value : {};
  return Object.entries(source).reduce<Record<string, RemoteCollabProviderConfig>>((accumulator, [providerId, providerConfig]) => {
    if (providerId === TELEGRAM_PROVIDER_ID || providerId === GOTIFY_WEB_PROVIDER_ID || !isRecord(providerConfig)) {
      return accumulator;
    }
    accumulator[providerId] = { ...providerConfig };
    return accumulator;
  }, {});
};

const normalizeProviderOptions = (
  value: unknown,
  telegram: TelegramRemoteCollabConfig,
  gotifyWeb: GotifyWebRemoteCollabConfig
): RemoteCollabProviderOption[] => {
  const source = Array.isArray(value) ? value : [];
  if (source.length === 0) {
    return createDefaultProviderOptions(telegram, gotifyWeb);
  }

  return source
    .filter(isRecord)
    .map((item) => ({
      providerId: normalizeString(item.providerId),
      displayName: normalizeString(item.displayName),
      description: normalizeString(item.description),
      capabilities: Array.isArray(item.capabilities)
        ? item.capabilities.filter((capability): capability is string => typeof capability === 'string' && capability.trim().length > 0)
        : [],
      registered: Boolean(item.registered),
      enabled: Boolean(item.enabled),
      connectionStatus: normalizeString(item.connectionStatus, 'disabled'),
      config: isRecord(item.config) ? { ...item.config } : {},
      currentInstanceReceivesUpdates: typeof item.currentInstanceReceivesUpdates === 'boolean'
        ? item.currentInstanceReceivesUpdates
        : undefined,
    }))
    .filter((item) => item.providerId.length > 0);
};

const buildRemoteCollabConfig = (params: {
  enabled: boolean;
  debug: RemoteCollabDebugConfig;
  interactiveProviderId: string;
  notifyProviderIds: string[];
  providerOptions: RemoteCollabProviderOption[];
  telegram: TelegramRemoteCollabConfig;
  gotifyWeb: GotifyWebRemoteCollabConfig;
  extraProviders?: Record<string, RemoteCollabProviderConfig>;
}): RemoteCollabConfig => {
  const providers: RemoteCollabProvidersConfig = {
    ...(params.extraProviders ?? {}),
    telegram: params.telegram,
    gotify_web: params.gotifyWeb,
  };

  return {
    enabled: params.enabled,
    debug: params.debug,
    interactiveProviderId: params.interactiveProviderId,
    notifyProviderIds: params.notifyProviderIds,
    providerOptions: params.providerOptions,
    providers,
    telegram: params.telegram,
  };
};

const defaultTelegram = createDefaultTelegramConfig();
const defaultGotifyWeb = createDefaultGotifyWebConfig();

export const DEFAULT_REMOTE_COLLAB_CONFIG: RemoteCollabConfig = buildRemoteCollabConfig({
  enabled: false,
  debug: { enabled: false },
  interactiveProviderId: TELEGRAM_PROVIDER_ID,
  notifyProviderIds: [TELEGRAM_PROVIDER_ID],
  providerOptions: createDefaultProviderOptions(defaultTelegram, defaultGotifyWeb),
  telegram: defaultTelegram,
  gotifyWeb: defaultGotifyWeb,
});

/**
 * 统一规范化远程协作配置。
 * 这里既兼容旧的 telegram 顶层结构，也兼容新的 providers/providerOptions 树形结构。
 */
export const normalizeRemoteCollabConfig = (value: unknown): RemoteCollabConfig => {
  const source = isRecord(value) ? value : {};
  const providersSource = isRecord(source.providers) ? source.providers : {};
  const telegram = normalizeTelegramConfig(providersSource.telegram ?? source.telegram);
  const gotifyWeb = normalizeGotifyWebConfig(providersSource.gotify_web ?? source.gotify_web);
  const interactiveProviderId = normalizeString(source.interactiveProviderId, TELEGRAM_PROVIDER_ID);

  return buildRemoteCollabConfig({
    enabled: Boolean(source.enabled),
    debug: normalizeDebugConfig(source.debug),
    interactiveProviderId,
    notifyProviderIds: normalizeNotifyProviderIds(source.notifyProviderIds, interactiveProviderId),
    providerOptions: normalizeProviderOptions(source.providerOptions, telegram, gotifyWeb),
    telegram,
    gotifyWeb,
    extraProviders: normalizeExtraProviders(providersSource),
  });
};

export interface UseRemoteCollabSettingsReturn {
  remoteCollabConfig: RemoteCollabConfig;
  remoteCollabDebugSnapshot: RemoteCollabDebugSnapshot;
  remoteCollabProviderOperationResult: RemoteCollabProviderOperationResult | null;
  setRemoteCollabConfig: (config: unknown | ((prev: RemoteCollabConfig) => unknown)) => void;
  setRemoteCollabDebugSnapshot: (snapshot: unknown) => void;
  setRemoteCollabProviderOperationResult: (result: unknown) => void;
  handleRemoteCollabEnabledChange: (enabled: boolean) => void;
  handleRemoteCollabDebugEnabledChange: (enabled: boolean) => void;
  handleSaveRemoteCollabRoutingPolicy: (policy: RemoteCollabRoutingPolicy) => void;
  handleSaveRemoteCollabProviderConfig: (providerId: string, config: unknown) => void;
  handleSaveTelegramConfig: (telegram: TelegramRemoteCollabConfig) => void;
  handleStartTelegramBinding: () => void;
  handleSendRemoteTestMessage: (message: string) => void;
  handleTestRemoteCollabProvider: (providerId: string, actionKey?: string, request?: Record<string, unknown>) => void;
  handleRunRemoteCollabProviderAction: (providerId: string, actionKey: string, request?: Record<string, unknown>) => void;
  requestRemoteCollabDebugSnapshot: () => void;
}

const DEFAULT_REMOTE_COLLAB_DEBUG_SNAPSHOT: RemoteCollabDebugSnapshot = {
  recentRequests: [],
  recentErrors: [],
  recentActions: [],
};

const normalizeDebugItems = (value: unknown): Record<string, unknown>[] =>
  Array.isArray(value)
    ? value.map((item) => (isRecord(item) ? { ...item } : {}))
    : [];

const normalizeRemoteCollabDebugSnapshot = (value: unknown): RemoteCollabDebugSnapshot => {
  const source = isRecord(value) ? value : {};
  return {
    recentRequests: normalizeDebugItems(source.recentRequests),
    recentErrors: normalizeDebugItems(source.recentErrors),
    recentActions: normalizeDebugItems(source.recentActions),
  };
};

const normalizeRemoteCollabProviderOperationResult = (value: unknown): RemoteCollabProviderOperationResult | null => {
  if (!isRecord(value)) {
    return null;
  }
  return {
    operationType: normalizeString(value.operationType),
    providerId: normalizeString(value.providerId),
    actionKey: normalizeString(value.actionKey),
    result: isRecord(value.result) ? { ...value.result } : {},
  };
};

const normalizeProviderConfigForSave = (providerId: string, config: unknown): NormalizedProviderConfig => {
  if (providerId === TELEGRAM_PROVIDER_ID) {
    return normalizeTelegramConfig(config);
  }
  if (providerId === GOTIFY_WEB_PROVIDER_ID) {
    return normalizeGotifyWebConfig(config);
  }
  return isRecord(config) ? { ...config } : {};
};

const normalizeRoutingPolicyForSave = (policy: unknown): RemoteCollabRoutingPolicy => {
  const source = isRecord(policy) ? policy : {};
  const interactiveProviderId = normalizeString(source.interactiveProviderId, TELEGRAM_PROVIDER_ID);
  return {
    interactiveProviderId,
    notifyProviderIds: normalizeNotifyProviderIds(source.notifyProviderIds, interactiveProviderId),
  };
};

/**
 * 远程协作设置 Hook。
 * 当前阶段同时维护多 provider 配置、调试快照，以及旧 Telegram 页面仍需要的兼容操作。
 */
export const useRemoteCollabSettings = (): UseRemoteCollabSettingsReturn => {
  const [remoteCollabConfigState, setRemoteCollabConfigState] = useState<RemoteCollabConfig>(DEFAULT_REMOTE_COLLAB_CONFIG);
  const [remoteCollabDebugSnapshot, setRemoteCollabDebugSnapshotState] = useState<RemoteCollabDebugSnapshot>(
    DEFAULT_REMOTE_COLLAB_DEBUG_SNAPSHOT
  );
  const [remoteCollabProviderOperationResult, setRemoteCollabProviderOperationResultState] =
    useState<RemoteCollabProviderOperationResult | null>(null);

  const setRemoteCollabConfig = useCallback<UseRemoteCollabSettingsReturn['setRemoteCollabConfig']>((nextConfig) => {
    setRemoteCollabConfigState((prev) => normalizeRemoteCollabConfig(
      typeof nextConfig === 'function' ? nextConfig(prev) : nextConfig
    ));
  }, []);

  // 统一规范化后端推送的调试快照，避免 UI 直接依赖数组字段的完整性。
  const setRemoteCollabDebugSnapshot = useCallback<UseRemoteCollabSettingsReturn['setRemoteCollabDebugSnapshot']>((snapshot) => {
    setRemoteCollabDebugSnapshotState(normalizeRemoteCollabDebugSnapshot(snapshot));
  }, []);

  // 最近一次 provider 动作结果只保留最新一条，供调试面板展示摘要。
  const setRemoteCollabProviderOperationResult = useCallback<UseRemoteCollabSettingsReturn['setRemoteCollabProviderOperationResult']>((result) => {
    setRemoteCollabProviderOperationResultState(normalizeRemoteCollabProviderOperationResult(result));
  }, []);

  const handleRemoteCollabEnabledChange = useCallback((enabled: boolean) => {
    setRemoteCollabConfigState((prev) => ({
      ...prev,
      enabled,
    }));
    sendToJava(`set_remote_collab_enabled:${JSON.stringify({ enabled })}`);
  }, []);

  const handleRemoteCollabDebugEnabledChange = useCallback((enabled: boolean) => {
    setRemoteCollabConfigState((prev) => ({
      ...prev,
      debug: {
        ...prev.debug,
        enabled,
      },
    }));
    sendToJava(`set_remote_collab_debug_enabled:${JSON.stringify({ enabled })}`);
  }, []);

  /**
   * 保存公共路由策略。
   * 当前阶段只允许一个交互 provider，但允许多个通知 provider，因此这里统一做去重与兜底规范化。
   */
  const handleSaveRemoteCollabRoutingPolicy = useCallback((policy: RemoteCollabRoutingPolicy) => {
    const normalizedPolicy = normalizeRoutingPolicyForSave(policy);
    setRemoteCollabConfigState((prev) => ({
      ...prev,
      interactiveProviderId: normalizedPolicy.interactiveProviderId,
      notifyProviderIds: normalizedPolicy.notifyProviderIds,
    }));
    sendToJava(`save_remote_collab_routing_policy:${JSON.stringify(normalizedPolicy)}`);
  }, []);

  /**
   * 通用 provider 配置保存入口。
   * 这里先更新前端本地状态，再下发到 Java，避免保存后还没收到回推时界面闪回旧值。
   */
  const handleSaveRemoteCollabProviderConfig = useCallback((providerId: string, config: unknown) => {
    const normalizedProviderId = normalizeString(providerId);
    if (!normalizedProviderId) {
      return;
    }

    const normalizedConfig = normalizeProviderConfigForSave(normalizedProviderId, config);
    const normalizedConfigRecord = normalizedConfig as Record<string, unknown>;
    const telegramReceivesUpdates = normalizedProviderId === TELEGRAM_PROVIDER_ID
      && typeof normalizedConfigRecord.currentInstanceReceivesUpdates === 'boolean'
      ? normalizedConfigRecord.currentInstanceReceivesUpdates
      : undefined;
    setRemoteCollabConfigState((prev) => normalizeRemoteCollabConfig({
      ...prev,
      providers: {
        ...prev.providers,
        [normalizedProviderId]: normalizedConfig,
      },
      providerOptions: prev.providerOptions.map((option) => option.providerId === normalizedProviderId
        ? {
            ...option,
            enabled: Boolean(normalizedConfig.enabled),
            connectionStatus: normalizeString(normalizedConfig.connectionStatus, option.connectionStatus),
            config: { ...normalizedConfig },
            currentInstanceReceivesUpdates: telegramReceivesUpdates ?? option.currentInstanceReceivesUpdates,
          }
        : option),
    }));

    sendToJava(`save_remote_collab_provider_config:${JSON.stringify({
      providerId: normalizedProviderId,
      config: normalizedConfig,
    })}`);
  }, []);

  const handleSaveTelegramConfig = useCallback((telegram: TelegramRemoteCollabConfig) => {
    const normalizedTelegram = normalizeTelegramConfig(telegram);
    handleSaveRemoteCollabProviderConfig(TELEGRAM_PROVIDER_ID, normalizedTelegram);
    // 兼容旧 Java 入口，直到 Telegram provider 迁移完成前仍保留一份旧指令。
    sendToJava(`save_telegram_config:${JSON.stringify({ telegram: normalizedTelegram })}`);
  }, [handleSaveRemoteCollabProviderConfig]);

  const handleStartTelegramBinding = useCallback(() => {
    sendToJava('start_telegram_binding:{}');
  }, []);

  const handleSendRemoteTestMessage = useCallback((message: string) => {
    sendToJava(`send_remote_test_message:${JSON.stringify({ message })}`);
  }, []);

  /**
   * 通用 provider 测试入口。
   * 优先复用 Java 侧统一 action bridge，避免每新增一种远程协作方案都再扩一组专用调试命令。
   */
  const handleTestRemoteCollabProvider = useCallback((
    providerId: string,
    actionKey = 'test_connection',
    request: Record<string, unknown> = {}
  ) => {
    const normalizedProviderId = normalizeString(providerId);
    const normalizedActionKey = normalizeString(actionKey, 'test_connection');
    if (!normalizedProviderId) {
      return;
    }
    sendToJava(`test_remote_collab_provider:${JSON.stringify({
      providerId: normalizedProviderId,
      actionKey: normalizedActionKey,
      ...request,
    })}`);
  }, []);

  /**
   * 通用 provider 自定义动作入口。
   * 该入口主要给调试页使用，用于触发 Gotify/Web 的轮询、测试推送等 provider 专属动作。
   */
  const handleRunRemoteCollabProviderAction = useCallback((
    providerId: string,
    actionKey: string,
    request: Record<string, unknown> = {}
  ) => {
    const normalizedProviderId = normalizeString(providerId);
    const normalizedActionKey = normalizeString(actionKey);
    if (!normalizedProviderId || !normalizedActionKey) {
      return;
    }
    sendToJava(`run_remote_collab_provider_action:${JSON.stringify({
      providerId: normalizedProviderId,
      actionKey: normalizedActionKey,
      ...request,
    })}`);
  }, []);

  const requestRemoteCollabDebugSnapshot = useCallback(() => {
    sendToJava('get_remote_collab_debug_snapshot:{}');
  }, []);

  return {
    remoteCollabConfig: remoteCollabConfigState,
    remoteCollabDebugSnapshot,
    remoteCollabProviderOperationResult,
    setRemoteCollabConfig,
    setRemoteCollabDebugSnapshot,
    setRemoteCollabProviderOperationResult,
    handleRemoteCollabEnabledChange,
    handleRemoteCollabDebugEnabledChange,
    handleSaveRemoteCollabRoutingPolicy,
    handleSaveRemoteCollabProviderConfig,
    handleSaveTelegramConfig,
    handleStartTelegramBinding,
    handleSendRemoteTestMessage,
    handleTestRemoteCollabProvider,
    handleRunRemoteCollabProviderAction,
    requestRemoteCollabDebugSnapshot,
  };
};
