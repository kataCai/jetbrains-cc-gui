import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSettingsBasicActions } from './useSettingsBasicActions';

describe('useSettingsBasicActions task reminder config', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  it('sends set_task_reminder_config after updating canonical task reminder config', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.handleTaskReminderEnabledChange('popup', false);
    });

    const calls = (window.sendToJava as any).mock.calls;
    const command = calls.find(([message]: [unknown]) => String(message).startsWith('set_task_reminder_config:'))?.[0];
    expect(command).toBeTruthy();

    const payload = JSON.parse(String(command).slice('set_task_reminder_config:'.length));
    expect(payload.popup.enabled).toBe(false);
    expect(Array.isArray(payload.popup.states)).toBe(true);
  });

  it('sends task reminder test events for popup and balloon', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.handleTestPopup();
      result.current.handleTestBalloon();
    });

    expect(window.sendToJava).toHaveBeenCalledWith('test_task_reminder_popup:');
    expect(window.sendToJava).toHaveBeenCalledWith('test_task_reminder_balloon:');
  });
});
