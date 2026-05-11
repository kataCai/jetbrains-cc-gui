import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RemoteDebugSection from './index';
import type {
  FeishuRemoteCollabConfig,
  GotifyWebRemoteCollabConfig,
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
  TelegramRemoteCollabConfig,
} from '../hooks/useRemoteCollabSettings';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.defaultValue ?? key,
  }),
}));

const createDebugSnapshot = (): RemoteCollabDebugSnapshot => ({
  recentRequests: [
    { requestId: 'req-1', summary: 'approve plan', providerId: 'telegram' },
    { requestId: 'req-2', summary: 'apply patch', providerId: 'gotify_web' },
  ],
  recentErrors: [
    { providerId: 'telegram', message: 'timeout', createdAt: 100 },
  ],
  recentActions: [
    { providerId: 'telegram', actionKey: 'test_connection', createdAt: 200 },
    { providerId: 'gotify_web', actionKey: 'poll_results', createdAt: 300 },
  ],
});

const createOperationResult = (): RemoteCollabProviderOperationResult => ({
  operationType: 'test',
  providerId: 'gotify_web',
  actionKey: 'health_check',
  result: {
    message: 'workspace ok',
  },
});

const createTelegramConfig = (): TelegramRemoteCollabConfig => ({
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
});

const createGotifyConfig = (): GotifyWebRemoteCollabConfig => ({
  enabled: true,
  serverUrl: 'https://gotify.example',
  apiToken: 'token',
  workspaceBaseUrl: 'https://workspace.example',
  resultPollIntervalSeconds: 3,
  connectionStatus: 'connected',
  lastError: '',
});

const createFeishuConfig = (): FeishuRemoteCollabConfig => ({
  enabled: true,
  appId: 'cli_test',
  appSecret: 'secret_test',
  encryptKey: '',
  verificationToken: '',
  botName: 'cc-bot',
  boundOpenId: 'ou_123',
  boundChatId: '',
  bindingToken: 'bind-1',
  connectionStatus: 'error',
  lastError: 'invalid app secret',
  eventMode: 'long_poll',
});

describe('RemoteDebugSection', () => {
  it('renders overview metrics and latest operation summary', () => {
    render(
      <RemoteDebugSection
        debugSnapshot={createDebugSnapshot()}
        providerOperationResult={createOperationResult()}
        telegramConfig={createTelegramConfig()}
        gotifyConfig={createGotifyConfig()}
        feishuConfig={createFeishuConfig()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onRefresh={vi.fn()}
      /> as any
    );

    expect(screen.getByRole('heading', { name: 'Remote Debug Snapshot' })).toBeTruthy();
    expect(screen.getByText('Recent request count')).toBeTruthy();
    expect(screen.getAllByText('2')).toHaveLength(2);
    expect(screen.getByText('Last provider action')).toBeTruthy();
    expect(screen.getByText('gotify_web / health_check')).toBeTruthy();
    expect(screen.getByText((content) => content.includes('Last action message: workspace ok'))).toBeTruthy();
  });

  it('renders recent request, error, and action activity lists', () => {
    render(
      <RemoteDebugSection
        debugSnapshot={createDebugSnapshot()}
        providerOperationResult={createOperationResult()}
        telegramConfig={createTelegramConfig()}
        gotifyConfig={createGotifyConfig()}
        feishuConfig={createFeishuConfig()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onRefresh={vi.fn()}
      /> as any
    );

    expect(screen.getByText('Recent requests')).toBeTruthy();
    expect(screen.getByText((content) => content.includes('approve plan'))).toBeTruthy();
    expect(screen.getByText((content) => content.includes('apply patch'))).toBeTruthy();
    expect(screen.getByText('Recent errors')).toBeTruthy();
    expect(screen.getByText((content) => content.includes('timeout'))).toBeTruthy();
    expect(screen.getByText('Recent actions')).toBeTruthy();
    expect(screen.getByText((content) => content.includes('poll_results'))).toBeTruthy();
  });

  it('forwards refresh action to caller', () => {
    const onRefresh = vi.fn();

    render(
      <RemoteDebugSection
        debugSnapshot={createDebugSnapshot()}
        providerOperationResult={createOperationResult()}
        telegramConfig={createTelegramConfig()}
        gotifyConfig={createGotifyConfig()}
        feishuConfig={createFeishuConfig()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onRefresh={onRefresh}
      /> as any
    );

    fireEvent.click(screen.getByRole('button', { name: 'Refresh debug snapshot' }));

    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it('renders telegram debug panel and forwards telegram debug actions', () => {
    const onStartTelegramBinding = vi.fn();
    const onSendRemoteTestMessage = vi.fn();

    render(
      <RemoteDebugSection
        debugSnapshot={createDebugSnapshot()}
        providerOperationResult={{
          operationType: 'test',
          providerId: 'telegram',
          actionKey: 'send_test_message',
          result: {
            message: 'telegram ok',
          },
        }}
        telegramConfig={createTelegramConfig()}
        gotifyConfig={createGotifyConfig()}
        feishuConfig={createFeishuConfig()}
        onStartTelegramBinding={onStartTelegramBinding}
        onSendRemoteTestMessage={onSendRemoteTestMessage}
        onTestRemoteCollabProvider={vi.fn()}
        onRunRemoteCollabProviderAction={vi.fn()}
        onRefresh={vi.fn()}
        activeProviderId="telegram"
      /> as any
    );

    expect(screen.getByRole('heading', { name: 'Telegram Debug Panel' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Gotify/Web Debug Panel' })).toBeNull();
    expect(screen.getByText('alice')).toBeTruthy();
    expect(screen.getByText('This IDE instance is receiving updates.')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Start Telegram binding' }));
    fireEvent.click(screen.getByRole('button', { name: 'Send Telegram test message' }));

    expect(onStartTelegramBinding).toHaveBeenCalledTimes(1);
    expect(onSendRemoteTestMessage).toHaveBeenCalledWith('CC GUI Telegram test message');
  });

  it('renders gotify web debug panel and forwards gotify debug actions', () => {
    const onTestRemoteCollabProvider = vi.fn();
    const onRunRemoteCollabProviderAction = vi.fn();

    render(
      <RemoteDebugSection
        debugSnapshot={createDebugSnapshot()}
        providerOperationResult={{
          operationType: 'action',
          providerId: 'gotify_web',
          actionKey: 'poll_results_once',
          result: {
            workspaceLink: 'https://workspace.example/request/backend-1',
            completedRequestIds: ['backend-1'],
            remainingTrackedCount: 0,
          },
        }}
        telegramConfig={createTelegramConfig()}
        gotifyConfig={createGotifyConfig()}
        feishuConfig={createFeishuConfig()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={onTestRemoteCollabProvider}
        onRunRemoteCollabProviderAction={onRunRemoteCollabProviderAction}
        onRefresh={vi.fn()}
        activeProviderId="gotify_web"
      /> as any
    );

    expect(screen.getByRole('heading', { name: 'Gotify/Web Debug Panel' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Telegram Debug Panel' })).toBeNull();
    expect(screen.getByText('https://workspace.example/request/backend-1')).toBeTruthy();
    expect(screen.getByText('backend-1')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Run Gotify/Web health check' }));
    fireEvent.click(screen.getByRole('button', { name: 'Send Gotify/Web test event' }));
    fireEvent.click(screen.getByRole('button', { name: 'Create Gotify/Web test request' }));
    fireEvent.click(screen.getByRole('button', { name: 'Poll Gotify/Web results once' }));

    expect(onTestRemoteCollabProvider).toHaveBeenCalledWith('gotify_web', 'health_check');
    expect(onRunRemoteCollabProviderAction).toHaveBeenCalledWith('gotify_web', 'send_test_event');
    expect(onRunRemoteCollabProviderAction).toHaveBeenCalledWith('gotify_web', 'send_test_pending_request');
    expect(onRunRemoteCollabProviderAction).toHaveBeenCalledWith('gotify_web', 'poll_results_once');
  });

  it('renders feishu debug panel and forwards feishu debug actions', () => {
    const onTestRemoteCollabProvider = vi.fn();

    render(
      <RemoteDebugSection
        debugSnapshot={createDebugSnapshot()}
        providerOperationResult={{
          operationType: 'test',
          providerId: 'feishu',
          actionKey: 'health_check',
          result: {
            message: 'tenant token ready',
          },
        }}
        telegramConfig={createTelegramConfig()}
        gotifyConfig={createGotifyConfig()}
        feishuConfig={createFeishuConfig()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
        onTestRemoteCollabProvider={onTestRemoteCollabProvider}
        onRunRemoteCollabProviderAction={vi.fn()}
        onRefresh={vi.fn()}
        activeProviderId="feishu"
      /> as any
    );

    expect(screen.getByRole('heading', { name: 'Feishu Debug Panel' })).toBeTruthy();
    expect(screen.queryByRole('heading', { name: 'Telegram Debug Panel' })).toBeNull();
    expect(screen.queryByRole('heading', { name: 'Gotify/Web Debug Panel' })).toBeNull();
    expect(screen.getByText('ou_123')).toBeTruthy();
    expect(screen.getByText((content) => content.includes('invalid app secret'))).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Run Feishu health check' }));
    fireEvent.click(screen.getByRole('button', { name: 'Send Feishu test message' }));
    fireEvent.change(screen.getByPlaceholderText('ou_xxx'), {
      target: { value: 'ou_debug' },
    });
    fireEvent.change(screen.getByPlaceholderText('oc_xxx'), {
      target: { value: 'oc_debug' },
    });
    fireEvent.change(screen.getByDisplayValue('/cc-bind <bindingToken>'), {
      target: { value: '/cc-approve req-9' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Inject Feishu inbound command' }));

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
        openId: 'ou_debug',
        chatId: 'oc_debug',
        message: '/cc-approve req-9',
      }
    );
  });
});
