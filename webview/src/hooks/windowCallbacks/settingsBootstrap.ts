import { sendBridgeEvent } from '../../utils/bridge';

const MAX_RETRIES = 30;
const INITIAL_REQUEST_DELAY_MS = 200;
const RETRY_INTERVAL_MS = 100;

/**
 * 在 `window.sendToJava` 可用后请求基础设置。
 *
 * 这里统一拉取 WebView 初始化所需的几个设置项，包括并轨新增的
 * `get_permission_dialog_timeout` 和 `get_codex_model_state`。
 * 若 bridge 尚未就绪，则做有限次重试，避免首次打开页面时丢失初始化状态。
 */
export const startInitialSettingsRequest = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  let settingsRetryCount = 0;
  const requestInitialSettings = () => {
    if (typeof window === 'undefined') {
      return;
    }

    if (window.sendToJava) {
      window.sendToJava('get_streaming_enabled:');
      window.sendToJava('get_send_shortcut:');
      window.sendToJava('get_auto_open_file_enabled:');
      window.sendToJava('get_codex_model_state:');
      window.sendToJava('get_permission_dialog_timeout:');
      return;
    }

    settingsRetryCount += 1;
    if (settingsRetryCount < MAX_RETRIES) {
      setTimeout(requestInitialSettings, RETRY_INTERVAL_MS);
    }
  };

  setTimeout(requestInitialSettings, INITIAL_REQUEST_DELAY_MS);
};

/**
 * 请求当前激活的 provider 配置。
 */
export const startActiveProviderRequest = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  let retryCount = 0;
  const requestActiveProvider = () => {
    if (typeof window === 'undefined') {
      return;
    }

    if (window.sendToJava) {
      sendBridgeEvent('get_active_provider');
      return;
    }

    retryCount += 1;
    if (retryCount < MAX_RETRIES) {
      setTimeout(requestActiveProvider, RETRY_INTERVAL_MS);
    }
  };

  setTimeout(requestActiveProvider, INITIAL_REQUEST_DELAY_MS);
};

/**
 * 请求当前权限模式。
 */
export const startModeRequest = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  let modeRetryCount = 0;
  const requestMode = () => {
    if (typeof window === 'undefined') {
      return;
    }

    if (window.sendToJava) {
      sendBridgeEvent('get_mode');
      return;
    }

    modeRetryCount += 1;
    if (modeRetryCount < MAX_RETRIES) {
      setTimeout(requestMode, RETRY_INTERVAL_MS);
    }
  };

  setTimeout(requestMode, INITIAL_REQUEST_DELAY_MS);
};

/**
 * 请求 Claude 的 thinking 开关。
 */
export const startThinkingEnabledRequest = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  let thinkingRetryCount = 0;
  const requestThinkingEnabled = () => {
    if (typeof window === 'undefined') {
      return;
    }

    if (window.sendToJava) {
      sendBridgeEvent('get_thinking_enabled');
      return;
    }

    thinkingRetryCount += 1;
    if (thinkingRetryCount < MAX_RETRIES) {
      setTimeout(requestThinkingEnabled, RETRY_INTERVAL_MS);
    }
  };

  setTimeout(requestThinkingEnabled, INITIAL_REQUEST_DELAY_MS);
};

/**
 * 消费主线程在回调注册前缓存的设置事件。
 *
 * main.tsx 在 React 挂载前若先收到 bridge 回调，会把原始 JSON 暂存到
 * `window.__pending*` 槽位。这里必须在对应 `window.updateXxx/onXxx`
 * 回调注册完成后立即回放，并清掉槽位，避免后续重复回放陈旧值。
 */
export const drainPendingSettings = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  const w = window as unknown as Record<string, unknown>;

  if (typeof w.__pendingStreamingEnabled === 'string') {
    const pending = w.__pendingStreamingEnabled;
    delete w.__pendingStreamingEnabled;
    window.updateStreamingEnabled?.(pending);
  }

  if (typeof w.__pendingSendShortcut === 'string') {
    const pending = w.__pendingSendShortcut;
    delete w.__pendingSendShortcut;
    window.updateSendShortcut?.(pending);
  }

  if (typeof w.__pendingAutoOpenFileEnabled === 'string') {
    const pending = w.__pendingAutoOpenFileEnabled;
    delete w.__pendingAutoOpenFileEnabled;
    window.updateAutoOpenFileEnabled?.(pending);
  }

  if (typeof w.__pendingPermissionDialogTimeout === 'string') {
    const pending = w.__pendingPermissionDialogTimeout;
    delete w.__pendingPermissionDialogTimeout;
    window.updatePermissionDialogTimeout?.(pending);
  }

  if (typeof w.__pendingModeReceived === 'string') {
    const pending = w.__pendingModeReceived;
    delete w.__pendingModeReceived;
    window.onModeReceived?.(pending);
  }

  if (w.__pendingCodexModelState) {
    const pending = w.__pendingCodexModelState as string;
    delete w.__pendingCodexModelState;
    window.updateCodexModelState?.(pending);
  }
};

/**
 * 回放并刷新依赖状态。
 */
export const drainAndRequestDependencyStatus = (): void => {
  if (typeof window === 'undefined') {
    return;
  }

  const w = window as unknown as Record<string, unknown>;
  if (typeof w.__pendingDependencyStatus === 'string') {
    const pending = w.__pendingDependencyStatus;
    delete w.__pendingDependencyStatus;
    window.updateDependencyStatus?.(pending);
  }

  if (window.sendToJava) {
    window.sendToJava('get_dependency_status:');
  }
};
