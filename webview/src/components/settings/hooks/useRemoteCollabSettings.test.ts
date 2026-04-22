import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_REMOTE_COLLAB_CONFIG,
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
        "set_remote_collab_enabled:{\"enabled\":true}"
    );
  });

  it('normalizes telegram config before saving it', () => {
    const { result } = renderHook(() => useRemoteCollabSettings());

    act(() => {
      result.current.handleSaveTelegramConfig({
        ...DEFAULT_REMOTE_COLLAB_CONFIG.telegram,
        botToken: 'bot-token',
        pollIntervalSeconds: 0,
      });
    });

    expect(result.current.remoteCollabConfig.telegram.botToken).toBe('bot-token');
    expect(result.current.remoteCollabConfig.telegram.pollIntervalSeconds).toBe(1);
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('save_telegram_config:')
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
});
