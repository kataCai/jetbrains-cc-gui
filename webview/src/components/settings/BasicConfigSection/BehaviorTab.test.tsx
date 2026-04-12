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
};

describe('BehaviorTab task reminder section', () => {
  it('renders task reminder group and popup/balloon/sound sections', () => {
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
    expect(screen.getByLabelText('Popup only when IDE unfocused')).toBeTruthy();
    expect(screen.getByLabelText('Sound only when IDE unfocused')).toBeTruthy();

    const popupState = screen.getByLabelText('Popup state waiting_confirm');
    fireEvent.click(popupState);
    expect(onTaskReminderStateToggle).toHaveBeenCalledWith('popup', 'waiting_confirm', false);
  });
});
