import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import BehaviorTab from './BehaviorTab';
import type { TaskReminderConfig } from '../../../types/taskReminder';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, fallback?: unknown) => (typeof fallback === 'string' ? fallback : _key),
  }),
}));

const defaultTaskReminderConfig: TaskReminderConfig = {
  popup: {
    enabled: true,
    states: ['waiting_confirm', 'final_error'],
    onlyWhenIdeUnfocused: false,
  },
  balloon: {
    enabled: true,
    states: ['completed', 'recovered', 'final_error'],
    onlyWhenIdeUnfocused: true,
  },
  sound: {
    enabled: true,
    states: ['completed'],
    onlyWhenIdeUnfocused: true,
    selectedSound: 'default',
    customSoundPath: '',
  },
  system: {
    enabled: false,
    states: ['waiting_confirm', 'final_error', 'completed'],
    onlyWhenIdeUnfocused: true,
  },
  // 补齐恢复策略默认值，确保测试夹具覆盖新增配置字段并与运行时模型一致。
  recoveryPolicy: {
    enabled: true,
    recoverCompletedOnParseNoise: true,
    retryTransientErrors: true,
    maxAttempts: 2,
    initialDelayMs: 1200,
  },
};

describe('BehaviorTab task reminder section', () => {
  it('renders task reminder group and popup/balloon/sound/system sections', () => {
    // 设置页应完整展示三类提醒渠道，确保新配置结构在 UI 层可见。
    render(
      <BehaviorTab
        taskReminderConfig={defaultTaskReminderConfig}
        onTaskReminderEnabledChange={vi.fn()}
        onTaskReminderStateToggle={vi.fn()}
        onTaskReminderOnlyWhenIdeUnfocusedChange={vi.fn()}
        onTaskReminderSelectedSoundChange={vi.fn()}
        onTaskReminderCustomSoundPathChange={vi.fn()}
        onSaveCustomSoundPath={vi.fn()}
        onTestSound={vi.fn()}
        onBrowseSound={vi.fn()}
      />
    );

    expect(screen.getByText(/Task Reminder|状态提醒/)).toBeTruthy();
    expect(screen.getByText('Popup')).toBeTruthy();
    expect(screen.getByText('Balloon')).toBeTruthy();
    expect(screen.getByText('Sound')).toBeTruthy();
    expect(screen.getByText('System')).toBeTruthy();
    expect(screen.getByText(/Notifications/i)).toBeTruthy();
  });

  it('shows key toggles and state multi-select controls', () => {
    // 多选状态和渠道开关都应可操作，并把变更透传到回调。
    const onTaskReminderStateToggle = vi.fn();

    render(
      <BehaviorTab
        taskReminderConfig={defaultTaskReminderConfig}
        onTaskReminderEnabledChange={vi.fn()}
        onTaskReminderStateToggle={onTaskReminderStateToggle}
        onTaskReminderOnlyWhenIdeUnfocusedChange={vi.fn()}
        onTaskReminderSelectedSoundChange={vi.fn()}
        onTaskReminderCustomSoundPathChange={vi.fn()}
        onSaveCustomSoundPath={vi.fn()}
        onTestSound={vi.fn()}
        onBrowseSound={vi.fn()}
      />
    );

    expect(screen.getByLabelText('Popup enabled')).toBeTruthy();
    expect(screen.getByLabelText('Balloon enabled')).toBeTruthy();
    expect(screen.getByLabelText('Sound enabled')).toBeTruthy();
    expect(screen.getByLabelText('System enabled')).toBeTruthy();
    expect(screen.getByLabelText('Popup only when IDE unfocused')).toBeTruthy();
    expect(screen.getByLabelText('Sound only when IDE unfocused')).toBeTruthy();
    expect(screen.getByLabelText('System only when IDE unfocused')).toBeTruthy();

    const popupState = screen.getByLabelText('Popup state waiting_confirm');
    fireEvent.click(popupState);
    expect(onTaskReminderStateToggle).toHaveBeenCalledWith('popup', 'waiting_confirm', false);
  });

  it('limits popup state options to supported strong reminder states', () => {
    render(
      <BehaviorTab
        taskReminderConfig={defaultTaskReminderConfig}
        onTaskReminderEnabledChange={vi.fn()}
        onTaskReminderStateToggle={vi.fn()}
        onTaskReminderOnlyWhenIdeUnfocusedChange={vi.fn()}
        onTaskReminderSelectedSoundChange={vi.fn()}
        onTaskReminderCustomSoundPathChange={vi.fn()}
        onSaveCustomSoundPath={vi.fn()}
        onTestSound={vi.fn()}
        onBrowseSound={vi.fn()}
      />
    );

    expect(screen.getByLabelText('Popup state waiting_confirm')).toBeTruthy();
    expect(screen.getByLabelText('Popup state final_error')).toBeTruthy();
    expect(screen.queryByLabelText('Popup state completed')).toBeNull();
    expect(screen.queryByLabelText('Popup state retrying')).toBeNull();
    expect(screen.queryByLabelText('Popup state recovered')).toBeNull();
    expect(screen.getByLabelText('Balloon state completed')).toBeTruthy();
    expect(screen.getByLabelText('System state waiting_confirm')).toBeTruthy();
    expect(screen.getByLabelText('System state final_error')).toBeTruthy();
    expect(screen.getByLabelText('System state completed')).toBeTruthy();
    expect(screen.queryByLabelText('System state retrying')).toBeNull();
    expect(screen.queryByLabelText('System state recovered')).toBeNull();
  });

  it('renders popup and balloon test actions and triggers callbacks', () => {
    const onTestPopup = vi.fn();
    const onTestBalloon = vi.fn();

    render(
      <BehaviorTab
        taskReminderConfig={defaultTaskReminderConfig}
        onTaskReminderEnabledChange={vi.fn()}
        onTaskReminderStateToggle={vi.fn()}
        onTaskReminderOnlyWhenIdeUnfocusedChange={vi.fn()}
        onTaskReminderSelectedSoundChange={vi.fn()}
        onTaskReminderCustomSoundPathChange={vi.fn()}
        onSaveCustomSoundPath={vi.fn()}
        onTestSound={vi.fn()}
        onBrowseSound={vi.fn()}
        onTestPopup={onTestPopup}
        onTestBalloon={onTestBalloon}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Test popup' }));
    fireEvent.click(screen.getByRole('button', { name: 'Test balloon' }));

    expect(onTestPopup).toHaveBeenCalledTimes(1);
    expect(onTestBalloon).toHaveBeenCalledTimes(1);
  });
});
