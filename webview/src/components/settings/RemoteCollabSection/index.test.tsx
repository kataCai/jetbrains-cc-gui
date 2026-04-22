import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RemoteCollabSection from './index';
import type { RemoteCollabConfig } from '../hooks/useRemoteCollabSettings';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => options?.defaultValue ?? key,
  }),
}));

const createConfig = (): RemoteCollabConfig => ({
  enabled: true,
  telegram: {
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
  },
});

describe('RemoteCollabSection', () => {
  it('renders readable remote collaboration copy', () => {
    render(
      <RemoteCollabSection
        remoteCollabConfig={createConfig()}
        onEnabledChange={vi.fn()}
        onSaveTelegramConfig={vi.fn()}
        onStartTelegramBinding={vi.fn()}
        onSendRemoteTestMessage={vi.fn()}
      />
    );

    expect(screen.getByRole('heading', { name: 'Remote Collaboration' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Telegram Settings' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Connection Status' })).toBeTruthy();
    expect(screen.getByDisplayValue('CC GUI Telegram test message')).toBeTruthy();
  });

  it('passes edited telegram settings and actions back to the caller', () => {
    const onSaveTelegramConfig = vi.fn();
    const onStartTelegramBinding = vi.fn();
    const onSendRemoteTestMessage = vi.fn();

    render(
      <RemoteCollabSection
        remoteCollabConfig={createConfig()}
        onEnabledChange={vi.fn()}
        onSaveTelegramConfig={onSaveTelegramConfig}
        onStartTelegramBinding={onStartTelegramBinding}
        onSendRemoteTestMessage={onSendRemoteTestMessage}
      />
    );

    fireEvent.change(screen.getByPlaceholderText('123456:ABCDEF...'), {
      target: { value: 'new-token' },
    });
    fireEvent.change(screen.getByRole('spinbutton'), {
      target: { value: '5' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save Telegram settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Start Telegram binding' }));
    fireEvent.change(screen.getByRole('textbox'), {
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
});
