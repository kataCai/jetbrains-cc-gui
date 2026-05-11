import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_REMOTE_COLLAB_CONFIG,
  normalizeRemoteCollabConfig,
  useRemoteCollabSettings,
} from './useRemoteCollabSettings';

describe('useRemoteCollabSettings', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  it('updates enabled state and sends the toggle command', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.handleRemoteCollabEnabledChange(true);
    });

    expect(result.current.remoteCollabConfig.enabled).toBe(true);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'set_remote_collab_enabled:{"enabled":true}'
    );
  });

  it('normalizes legacy telegram config into multi-provider state', () => {
    const normalized = normalizeRemoteCollabConfig({
      enabled: true,
      telegram: {
        botToken: 'legacy-token',
        pollIntervalSeconds: 0,
        currentInstanceReceivesUpdates: true,
      },
    });

    expect(normalized.enabled).toBe(true);
    expect(normalized.debug.enabled).toBe(false);
    expect(normalized.interactiveProviderId).toBe('telegram');
    expect(normalized.notifyProviderIds).toEqual(['telegram']);
    expect(normalized.telegram.botToken).toBe('legacy-token');
    expect(normalized.telegram.pollIntervalSeconds).toBe(1);
    expect(normalized.providers.telegram.botToken).toBe('legacy-token');
    expect(normalized.providers.telegram.currentInstanceReceivesUpdates).toBe(true);
    expect(normalized.providers.gotify_web.resultPollIntervalSeconds).toBe(3);
  });

  it('normalizes provider tree when remote config is replaced from backend', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.setRemoteCollabConfig({
        enabled: true,
        debug: { enabled: true },
        interactiveProviderId: ' gotify_web ',
        notifyProviderIds: ['telegram', 'gotify_web', 'telegram', ''],
        providerOptions: [
          {
            providerId: 'telegram',
            displayName: 'Telegram',
            description: 'Inline chat collaboration',
            capabilities: ['TASK_EVENT_PUSH', 'INLINE_ACTION_CALLBACK'],
            registered: true,
            enabled: true,
            connectionStatus: 'connected',
            config: {
              botToken: 'telegram-token',
            },
          },
          {
            providerId: 'gotify_web',
            displayName: 'Gotify + Web',
            description: 'Workspace based collaboration',
            capabilities: ['TASK_EVENT_PUSH', 'RESULT_POLLING'],
            registered: false,
            enabled: true,
            connectionStatus: 'disabled',
            config: {
              serverUrl: 'https://gotify.example',
            },
          },
          {
            providerId: 'feishu',
            displayName: 'Feishu',
            description: 'Feishu bot direct messages',
            capabilities: ['BINDING', 'HEALTH_CHECK', 'INLINE_ACTION_CALLBACK'],
            registered: true,
            enabled: true,
            connectionStatus: 'error',
            config: {
              appId: 'cli_test',
              boundOpenId: 'ou_123',
              lastError: 'invalid secret',
            },
          },
        ],
        providers: {
          telegram: {
            ...DEFAULT_REMOTE_COLLAB_CONFIG.providers.telegram,
            botToken: 'telegram-token',
          },
          gotify_web: {
            ...DEFAULT_REMOTE_COLLAB_CONFIG.providers.gotify_web,
            enabled: true,
            serverUrl: 'https://gotify.example',
            resultPollIntervalSeconds: 0,
          },
          feishu: {
            ...DEFAULT_REMOTE_COLLAB_CONFIG.providers.feishu,
            enabled: true,
            appId: 'cli_test',
            boundOpenId: 'ou_123',
            lastError: 'invalid secret',
          },
        },
      });
    });

    expect(result.current.remoteCollabConfig.debug.enabled).toBe(true);
    expect(result.current.remoteCollabConfig.interactiveProviderId).toBe('gotify_web');
    expect(result.current.remoteCollabConfig.notifyProviderIds).toEqual(['telegram', 'gotify_web']);
    expect(result.current.remoteCollabConfig.providerOptions).toHaveLength(3);
    expect(result.current.remoteCollabConfig.providerOptions[1].providerId).toBe('gotify_web');
    expect(result.current.remoteCollabConfig.providerOptions[2].providerId).toBe('feishu');
    expect(result.current.remoteCollabConfig.providers.telegram.botToken).toBe('telegram-token');
    expect(result.current.remoteCollabConfig.providers.gotify_web.serverUrl).toBe('https://gotify.example');
    expect(result.current.remoteCollabConfig.providers.gotify_web.resultPollIntervalSeconds).toBe(1);
    expect(result.current.remoteCollabConfig.providers.feishu.appId).toBe('cli_test');
    expect(result.current.remoteCollabConfig.providers.feishu.boundOpenId).toBe('ou_123');
    expect(result.current.remoteCollabConfig.providers.feishu.lastError).toBe('invalid secret');
    expect(result.current.remoteCollabConfig.telegram.botToken).toBe('telegram-token');
  });

  it('normalizes feishu config before saving it and preserves status fields', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.handleSaveRemoteCollabProviderConfig('feishu', {
        enabled: true,
        appId: 'cli_test',
        appSecret: 'secret_test',
        botName: 'cc-bot',
        boundOpenId: 'ou_abc',
        bindingToken: 'bind-1',
        eventMode: 'long_poll',
        connectionStatus: 'connected',
        lastError: '',
      });
    });

    expect(result.current.remoteCollabConfig.providers.feishu).toEqual(
      expect.objectContaining({
        enabled: true,
        appId: 'cli_test',
        appSecret: 'secret_test',
        botName: 'cc-bot',
        boundOpenId: 'ou_abc',
        bindingToken: 'bind-1',
        eventMode: 'long_poll',
        connectionStatus: 'connected',
        lastError: '',
      })
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      'save_remote_collab_provider_config:{"providerId":"feishu","config":{"enabled":true,"appId":"cli_test","appSecret":"secret_test","encryptKey":"","verificationToken":"","botName":"cc-bot","boundOpenId":"ou_abc","boundChatId":"","bindingToken":"bind-1","connectionStatus":"connected","lastError":"","eventMode":"long_poll"}}'
    );
  });

  it('normalizes telegram config before saving it and keeps other providers intact', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.setRemoteCollabConfig({
        ...DEFAULT_REMOTE_COLLAB_CONFIG,
        enabled: true,
        debug: { enabled: true },
        interactiveProviderId: 'gotify_web',
        notifyProviderIds: ['telegram', 'gotify_web'],
        providers: {
          ...DEFAULT_REMOTE_COLLAB_CONFIG.providers,
          gotify_web: {
            ...DEFAULT_REMOTE_COLLAB_CONFIG.providers.gotify_web,
            enabled: true,
            workspaceBaseUrl: 'https://workspace.example',
          },
        },
      });
      result.current.handleSaveTelegramConfig({
        ...DEFAULT_REMOTE_COLLAB_CONFIG.providers.telegram,
        botToken: 'bot-token',
        pollIntervalSeconds: 0,
      });
    });

    expect(result.current.remoteCollabConfig.telegram.botToken).toBe('bot-token');
    expect(result.current.remoteCollabConfig.telegram.pollIntervalSeconds).toBe(1);
    expect(result.current.remoteCollabConfig.providers.gotify_web.workspaceBaseUrl).toBe('https://workspace.example');
    expect(result.current.remoteCollabConfig.interactiveProviderId).toBe('gotify_web');
    expect(window.sendToJava).toHaveBeenCalledWith(
      'save_telegram_config:{"telegram":{"enabled":true,"botToken":"bot-token","botUsername":"","chatId":"","boundUserId":"","boundUsername":"","bindingToken":"","pollingEnabled":true,"pollIntervalSeconds":1,"singleActive":true,"connectionStatus":"disabled","lastError":"","currentInstanceReceivesUpdates":false}}'
    );
  });

  it('sends binding and test message commands', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.handleStartTelegramBinding();
      result.current.handleSendRemoteTestMessage('hello remote');
    });

    expect(window.sendToJava).toHaveBeenCalledWith('start_telegram_binding:{}');
    expect(window.sendToJava).toHaveBeenCalledWith(
      'send_remote_test_message:{"message":"hello remote"}'
    );
  });

  it('sends generic provider debug commands for gotify web actions', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      (result.current as any).handleTestRemoteCollabProvider('gotify_web', 'health_check');
      (result.current as any).handleRunRemoteCollabProviderAction('gotify_web', 'send_test_event');
      (result.current as any).handleRunRemoteCollabProviderAction(
        'gotify_web',
        'poll_results_once',
        { requestId: 'debug-1' }
      );
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'test_remote_collab_provider:{"providerId":"gotify_web","actionKey":"health_check"}'
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      'run_remote_collab_provider_action:{"providerId":"gotify_web","actionKey":"send_test_event"}'
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      'run_remote_collab_provider_action:{"providerId":"gotify_web","actionKey":"poll_results_once","requestId":"debug-1"}'
    );
  });

  it('tracks debug mode and requests debug snapshots', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.handleRemoteCollabDebugEnabledChange(true);
      result.current.requestRemoteCollabDebugSnapshot();
      result.current.setRemoteCollabDebugSnapshot({
        recentRequests: [{ requestId: 'req-1' }],
        recentErrors: [{ message: 'network down' }],
        recentActions: [{ actionKey: 'test_connection' }],
      });
    });

    expect(result.current.remoteCollabConfig.debug.enabled).toBe(true);
    expect(result.current.remoteCollabDebugSnapshot.recentRequests).toHaveLength(1);
    expect(result.current.remoteCollabDebugSnapshot.recentErrors).toHaveLength(1);
    expect(result.current.remoteCollabDebugSnapshot.recentActions).toHaveLength(1);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'set_remote_collab_debug_enabled:{"enabled":true}'
    );
    expect(window.sendToJava).toHaveBeenCalledWith('get_remote_collab_debug_snapshot:{}');
  });

  it('stores the latest provider operation result for debug panel display', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.setRemoteCollabProviderOperationResult({
        operationType: 'test',
        providerId: 'telegram',
        actionKey: 'send_test_message',
        result: {
          message: 'ok',
        },
      });
    });

    expect(result.current.remoteCollabProviderOperationResult).toEqual({
      operationType: 'test',
      providerId: 'telegram',
      actionKey: 'send_test_message',
      result: {
        message: 'ok',
      },
    });
  });

  it('saves a generic provider config without overwriting other providers', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.handleSaveRemoteCollabProviderConfig('gotify_web', {
        enabled: true,
        serverUrl: 'https://gotify.example',
        apiToken: 'token',
        workspaceBaseUrl: 'https://workspace.example',
        resultPollIntervalSeconds: 0,
      });
    });

    expect(result.current.remoteCollabConfig.providers.gotify_web).toEqual(
      expect.objectContaining({
        enabled: true,
        serverUrl: 'https://gotify.example',
        apiToken: 'token',
        workspaceBaseUrl: 'https://workspace.example',
        resultPollIntervalSeconds: 1,
      })
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      'save_remote_collab_provider_config:{"providerId":"gotify_web","config":{"enabled":true,"serverUrl":"https://gotify.example","apiToken":"token","workspaceBaseUrl":"https://workspace.example","resultPollIntervalSeconds":1,"connectionStatus":"disabled","lastError":""}}'
    );
  });

  it('saves routing policy through dedicated bridge command and keeps provider state intact', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.handleSaveRemoteCollabRoutingPolicy({
        interactiveProviderId: 'gotify_web',
        notifyProviderIds: ['telegram', 'gotify_web', 'telegram', ''],
      });
    });

    expect(result.current.remoteCollabConfig.interactiveProviderId).toBe('gotify_web');
    expect(result.current.remoteCollabConfig.notifyProviderIds).toEqual(['telegram', 'gotify_web']);
    expect(result.current.remoteCollabConfig.providers.telegram).toEqual(DEFAULT_REMOTE_COLLAB_CONFIG.providers.telegram);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'save_remote_collab_routing_policy:{"interactiveProviderId":"gotify_web","notifyProviderIds":["telegram","gotify_web"]}'
    );
  });
});
