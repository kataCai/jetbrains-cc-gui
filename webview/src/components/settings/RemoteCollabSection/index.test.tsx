import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RemoteCollabSection from './index';
import type {
  RemoteCollabConfig,
  RemoteCollabDebugSnapshot,
} from '../hooks/useRemoteCollabSettings';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => {
      const template = options?.defaultValue ?? key;
      return typeof template === 'string'
        ? template.replace(/\{\{(\w+)\}\}/g, (_match, name: string) => options?.[name] ?? '')
        : template;
    },
  }),
}));

const createConfig = (): RemoteCollabConfig => {
  const telegram = {
    enabled: true,
    botToken: 'bot-token',
    botUsername: 'cc_gui_bot',
    chatId: '42',
    boundUserId: '7',
    boundUsername: 'alice',
    bindingToken: 'bind-token',
    pollingEnabled: true,
    pollIntervalSeconds: 3,
    singleActive: true,
    connectionStatus: 'connected',
    lastError: '',
    currentInstanceReceivesUpdates: true,
  };

  return {
    enabled: true,
    debug: { enabled: false },
    interactiveProviderId: 'telegram',
    notifyProviderIds: ['telegram'],
    providerOptions: [
      {
        providerId: 'telegram',
        displayName: 'Telegram',
        description: 'Inline chat collaboration',
        capabilities: ['TASK_EVENT_PUSH', 'INLINE_ACTION_CALLBACK'],
        registered: true,
        enabled: true,
        connectionStatus: 'connected',
        config: telegram,
        currentInstanceReceivesUpdates: true,
      },
      {
        providerId: 'gotify_web',
        displayName: 'Gotify + Web',
        description: 'Workspace based collaboration',
        capabilities: ['TASK_EVENT_PUSH', 'RESULT_POLLING'],
        registered: false,
        enabled: false,
        connectionStatus: 'disabled',
        config: {
          enabled: false,
          serverUrl: '',
          apiToken: '',
          workspaceBaseUrl: '',
          resultPollIntervalSeconds: 3,
          connectionStatus: 'disabled',
          lastError: '',
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
          enabled: true,
          appId: 'cli_test',
          appSecret: 'secret_test',
          encryptKey: '',
          verificationToken: '',
          botName: 'cc-gui-bot',
          boundOpenId: 'ou_123',
          boundChatId: '',
          bindingToken: 'bind-1',
          connectionStatus: 'error',
          lastError: 'invalid app secret',
          eventMode: 'long_poll',
        },
      },
    ],
    providers: {
      telegram,
      gotify_web: {
        enabled: false,
        serverUrl: '',
        apiToken: '',
        workspaceBaseUrl: '',
        resultPollIntervalSeconds: 3,
        connectionStatus: 'disabled',
        lastError: '',
      },
      feishu: {
        enabled: true,
        appId: 'cli_test',
        appSecret: 'secret_test',
        encryptKey: '',
        verificationToken: '',
        botName: 'cc-gui-bot',
        boundOpenId: 'ou_123',
        boundChatId: '',
        bindingToken: 'bind-1',
        connectionStatus: 'error',
        lastError: 'invalid app secret',
        eventMode: 'long_poll',
      },
    },
    telegram,
  };
};

const createDebugSnapshot = (): RemoteCollabDebugSnapshot => ({
  recentRequests: [{ requestId: 'req-1' }, { requestId: 'req-2' }],
  recentErrors: [{ message: 'timeout' }],
  recentActions: [{ actionKey: 'test_connection' }],
});

const createHubSummaryConfig = (): RemoteCollabConfig => {
  const config = createConfig();
  config.notifyProviderIds = ['telegram', 'gotify_web'];
  return config;
};

describe('RemoteCollabSection', () => {
  it('renders hub view first and opens provider detail on demand', () => {
    render(
      <RemoteCollabSection
        remoteCollabConfig={createConfig()}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={null}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={vi.fn()}
        onSaveRemoteCollabProviderConfig={vi.fn()}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onDebugEnabledChange={vi.fn()}
        onRefreshDebugSnapshot={vi.fn()}
      />
    );

    expect(screen.getByRole('heading', { name: 'Remote Collaboration' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Supported Providers' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Telegram Settings' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Connection Status' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Open Telegram settings' }));

    expect(screen.getByRole('button', { name: 'Back to providers' })).toBeTruthy();
    expect(screen.getByText('Provider detail')).toBeTruthy();
    expect(screen.getAllByText('TASK_EVENT_PUSH').length).toBeGreaterThan(0);
    expect(screen.getByText('INLINE_ACTION_CALLBACK')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Telegram Settings' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Connection Status' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Gotify/Web Settings' })).toBeNull();
    expect(screen.getByDisplayValue('CC GUI Telegram test message')).toBeTruthy();
  });

  it('shows provider display names in hub summary cards', () => {
    render(
      <RemoteCollabSection
        remoteCollabConfig={createHubSummaryConfig()}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={null}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={vi.fn()}
        onSaveRemoteCollabProviderConfig={vi.fn()}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onDebugEnabledChange={vi.fn()}
        onRefreshDebugSnapshot={vi.fn()}
      />
    );

    expect(screen.getAllByText('Telegram, Gotify + Web')).toHaveLength(2);
    expect(screen.queryByText('telegram, gotify_web')).toBeNull();
  });

  it('passes edited telegram settings and actions back to the caller', () => {
    const onSaveTelegramConfig = vi.fn();
    const onStartTelegramBinding = vi.fn();
    const onSendRemoteTestMessage = vi.fn();

    render(
      <RemoteCollabSection
        remoteCollabConfig={createConfig()}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={null}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={vi.fn()}
        onSaveRemoteCollabProviderConfig={vi.fn()}
        onSaveTelegramConfig={onSaveTelegramConfig}
        onStartTelegramBinding={onStartTelegramBinding}
        onSendRemoteTestMessage={onSendRemoteTestMessage}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onDebugEnabledChange={vi.fn()}
        onRefreshDebugSnapshot={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Open Telegram settings' }));

    fireEvent.change(screen.getByPlaceholderText('123456:ABCDEF...'), {
      target: { value: 'new-token' },
    });
    fireEvent.change(screen.getAllByRole('spinbutton')[0], {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save Telegram settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Start Telegram binding' }));
    fireEvent.change(screen.getByDisplayValue('CC GUI Telegram test message'), {
      target: { value: 'Remote ping' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Send test message' }));

    expect(onSaveTelegramConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        botToken: 'new-token',
        pollIntervalSeconds: 5,
      })
    );
    expect(onStartTelegramBinding).toHaveBeenCalledTimes(1);
    expect(onSendRemoteTestMessage).toHaveBeenCalledWith('Remote ping');
  });

  it('shows debug controls and refreshes snapshot when debug mode is enabled', () => {
    const onDebugEnabledChange = vi.fn();
    const onRefreshDebugSnapshot = vi.fn();
    const config = createConfig();
    config.debug.enabled = true;

    render(
      <RemoteCollabSection
        remoteCollabConfig={config}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={{
          operationType: 'test',
          providerId: 'telegram',
          actionKey: 'send_test_message',
          result: {
            message: 'sent',
          },
        }}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={vi.fn()}
        onSaveRemoteCollabProviderConfig={vi.fn()}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onDebugEnabledChange={onDebugEnabledChange}
        onRefreshDebugSnapshot={onRefreshDebugSnapshot}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Open Telegram settings' }));

    fireEvent.click(screen.getByLabelText('Enable remote debug tools'));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh debug snapshot' }));

    expect(screen.getByText('Recent request count')).toBeTruthy();
    expect(screen.getByText('2')).toBeTruthy();
    expect(screen.getByText('Last provider action')).toBeTruthy();
    expect(screen.getByText('telegram / send_test_message')).toBeTruthy();
    expect(onDebugEnabledChange).toHaveBeenCalledWith(false);
    expect(onRefreshDebugSnapshot).toHaveBeenCalledTimes(1);
  });

  it('shows provider cards and saves gotify web settings through generic provider action', () => {
    const onSaveRemoteCollabProviderConfig = vi.fn();

    render(
      <RemoteCollabSection
        remoteCollabConfig={createConfig()}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={null}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={vi.fn()}
        onSaveRemoteCollabProviderConfig={onSaveRemoteCollabProviderConfig}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onDebugEnabledChange={vi.fn()}
        onRefreshDebugSnapshot={vi.fn()}
      />
    );

    expect(screen.getAllByText('Telegram').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Gotify + Web').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Open Gotify + Web settings' }));

    expect(screen.getByRole('heading', { name: 'Gotify/Web Settings' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Telegram Settings' })).toBeNull();

    fireEvent.change(screen.getByPlaceholderText('https://gotify.example.com'), {
      target: { value: 'https://gotify.example.com' },
    });
    fireEvent.change(screen.getByPlaceholderText('https://workspace.example.com'), {
      target: { value: 'https://workspace.example.com' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save Gotify/Web settings' }));

    expect(onSaveRemoteCollabProviderConfig).toHaveBeenCalledWith(
      'gotify_web',
      expect.objectContaining({
        serverUrl: 'https://gotify.example.com',
        workspaceBaseUrl: 'https://workspace.example.com',
      })
    );
  });

  it('renders feishu detail with connection status and last error, and forwards feishu actions', () => {
    const onSaveRemoteCollabProviderConfig = vi.fn();
    const onTestRemoteCollabProvider = vi.fn();
    const onRunRemoteCollabProviderAction = vi.fn();
    const config = createConfig();
    config.debug.enabled = true;

    render(
      <RemoteCollabSection
        remoteCollabConfig={config}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={{
          operationType: 'test',
          providerId: 'feishu',
          actionKey: 'health_check',
          result: {
            message: 'token failed',
          },
        }}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={vi.fn()}
        onSaveRemoteCollabProviderConfig={onSaveRemoteCollabProviderConfig}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={onTestRemoteCollabProvider}
        onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
        onDebugEnabledChange={vi.fn()}
        onRefreshDebugSnapshot={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Open Feishu settings' }));

    expect(screen.getByRole('heading', { name: 'Feishu Settings' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Connection Status' })).toBeTruthy();
    expect(screen.getAllByText((content) => content.includes('invalid app secret')).length).toBeGreaterThan(0);
    expect(screen.getByDisplayValue('cli_test')).toBeTruthy();
    expect(screen.getAllByText('ou_123').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByLabelText('Enable remote debug tools'));
    expect(screen.getByRole('heading', { name: 'Feishu Debug Panel' })).toBeTruthy();

    fireEvent.change(screen.getByPlaceholderText('cli_xxx'), {
      target: { value: 'cli_new' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save Feishu settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Start Feishu binding' }));
    fireEvent.click(screen.getByRole('button', { name: 'Run Feishu health check' }));
    fireEvent.click(screen.getByRole('button', { name: 'Send Feishu test message' }));
    fireEvent.change(screen.getByPlaceholderText('ou_xxx'), {
      target: { value: 'ou_simulated' },
    });
    fireEvent.change(screen.getByPlaceholderText('oc_xxx'), {
      target: { value: 'oc_simulated' },
    });
    fireEvent.change(screen.getByDisplayValue('/cc-bind <bindingToken>'), {
      target: { value: '/cc-reply req-22 looks good' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Inject Feishu inbound command' }));

    expect(onSaveRemoteCollabProviderConfig).toHaveBeenCalledWith(
      'feishu',
      expect.objectContaining({
        appId: 'cli_new',
      })
    );
    expect(onRunRemoteCollabProviderAction).toHaveBeenCalledWith('feishu', 'start_binding');
    expect(onTestRemoteCollabProvider).toHaveBeenCalledWith('feishu', 'health_check');
    expect(onTestRemoteCollabProvider).toHaveBeenCalledWith(
      'feishu',
      'send_test_message',
      { message: 'CC GUI Feishu test message' }
    );
    expect(onTestRemoteCollabProvider).toHaveBeenCalledWith(
      'feishu',
      'handle_inbound_message',
      {
        openId: 'ou_simulated',
        chatId: 'oc_simulated',
        message: '/cc-reply req-22 looks good',
      }
    );
  });

  it('shows only current provider debug panel inside provider detail', () => {
    const config = createConfig();
    config.debug.enabled = true;

    render(
      <RemoteCollabSection
        remoteCollabConfig={config}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={null}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={vi.fn()}
        onSaveRemoteCollabProviderConfig={vi.fn()}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onDebugEnabledChange={vi.fn()}
        onRefreshDebugSnapshot={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Open Telegram settings' }));
    expect(screen.getByRole('heading', { name: 'Telegram Debug Panel' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Gotify/Web Debug Panel' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Back to providers' }));
    fireEvent.click(screen.getByRole('button', { name: 'Open Gotify + Web settings' }));
    expect(screen.getByRole('heading', { name: 'Gotify/Web Debug Panel' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Telegram Debug Panel' })).toBeNull();
  });

  it('saves routing policy from the shared policy panel', () => {
    const onSaveRemoteCollabRoutingPolicy = vi.fn();

    render(
      <RemoteCollabSection
        remoteCollabConfig={createConfig()}
        remoteCollabDebugSnapshot={createDebugSnapshot()}
        remoteCollabProviderOperationResult={null}
        onEnabledChange={vi.fn()}
        onSaveRemoteCollabRoutingPolicy={onSaveRemoteCollabRoutingPolicy}
        onSaveRemoteCollabProviderConfig={vi.fn()}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onDebugEnabledChange={vi.fn()}
        onRefreshDebugSnapshot={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Edit routing' }));
    fireEvent.click(screen.getByLabelText('Interactive provider: Gotify + Web'));
    fireEvent.click(screen.getByLabelText('Notify provider: Gotify + Web'));
    fireEvent.click(screen.getByRole('button', { name: 'Save routing policy' }));

    expect(onSaveRemoteCollabRoutingPolicy).toHaveBeenCalledWith({
      interactiveProviderId: 'gotify_web',
      notifyProviderIds: ['telegram', 'gotify_web'],
    });
  });
});
