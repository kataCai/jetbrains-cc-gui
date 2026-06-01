import type { ComponentProps } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import BehaviorTab from './BehaviorTab';
import type { TaskReminderConfig } from '../../../types/taskReminder';
import {
  MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS,
} from '../../../utils/permissionDialogTimeout';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: unknown) => (typeof fallback === 'string' ? fallback : key),
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
  recoveryPolicy: {
    enabled: true,
    recoverCompletedOnParseNoise: true,
    retryTransientErrors: true,
    maxAttempts: 2,
    initialDelayMs: 1200,
  },
};

/**
 * 构造 BehaviorTab 的完整默认 props，避免新增设置项后测试只传局部属性导致组件运行态失真。
 *
 * @param overrides 需要覆盖的局部 props
 * @return 实际传入组件的 props，便于断言回调
 */
function renderBehaviorTab(overrides: Partial<ComponentProps<typeof BehaviorTab>> = {}) {
  const props = {
    streamingEnabled: true,
    onStreamingEnabledChange: vi.fn(),
    codexSandboxMode: 'workspace-write' as const,
    onCodexSandboxModeChange: vi.fn(),
    sendShortcut: 'enter' as const,
    onSendShortcutChange: vi.fn(),
    autoOpenFileEnabled: false,
    onAutoOpenFileEnabledChange: vi.fn(),
    commitGenerationEnabled: true,
    onCommitGenerationEnabledChange: vi.fn(),
    aiTitleGenerationEnabled: true,
    onAiTitleGenerationEnabledChange: vi.fn(),
    taskCompletionNotificationEnabled: false,
    onTaskCompletionNotificationEnabledChange: vi.fn(),
    permissionDialogTimeoutSeconds: 300,
    onPermissionDialogTimeoutChange: vi.fn(),
    taskReminderConfig: defaultTaskReminderConfig,
    onTaskReminderEnabledChange: vi.fn(),
    onTaskReminderStateToggle: vi.fn(),
    onTaskReminderOnlyWhenIdeUnfocusedChange: vi.fn(),
    onTaskReminderSelectedSoundChange: vi.fn(),
    onTaskReminderCustomSoundPathChange: vi.fn(),
    onSaveCustomSoundPath: vi.fn(),
    onTestSound: vi.fn(),
    onBrowseSound: vi.fn(),
    ...overrides,
  };

  render(<BehaviorTab {...props} />);
  return props;
}

describe('BehaviorTab task reminder section', () => {
  it('renders task reminder group and popup/balloon/sound/system sections', () => {
    // 验证任务提醒四类渠道仍完整渲染，覆盖主线新增的 task reminder 设置区。
    renderBehaviorTab();

    expect(screen.getByText(/Task Reminder|状态提醒/)).toBeTruthy();
    expect(screen.getByText('Popup')).toBeTruthy();
    expect(screen.getByText('Balloon')).toBeTruthy();
    expect(screen.getByText('Sound')).toBeTruthy();
    expect(screen.getByText('System')).toBeTruthy();
    expect(screen.getByText(/Notifications/i)).toBeTruthy();
  });

  it('shows key toggles and state multi-select controls', () => {
    // 验证渠道开关、仅失焦提醒和状态多选仍向外透传变更。
    const onTaskReminderStateToggle = vi.fn();
    renderBehaviorTab({ onTaskReminderStateToggle });

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
    // 验证强提醒弹窗只暴露允许打断用户流程的状态，避免 completed/recovered 误弹强提醒。
    renderBehaviorTab();

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
    // 验证测试按钮仍能触发对应后端预览动作，防止并轨时丢掉 task reminder 入口。
    const onTestPopup = vi.fn();
    const onTestBalloon = vi.fn();

    renderBehaviorTab({ onTestPopup, onTestBalloon });

    fireEvent.click(screen.getByRole('button', { name: 'Test popup' }));
    fireEvent.click(screen.getByRole('button', { name: 'Test balloon' }));

    expect(onTestPopup).toHaveBeenCalledTimes(1);
    expect(onTestBalloon).toHaveBeenCalledTimes(1);
  });
});

describe('BehaviorTab permission dialog timeout', () => {
  it('exposes the timeout number input with an accessible label', () => {
    // 验证上游新增的权限对话框超时设置在行为页可见。
    renderBehaviorTab();

    expect(
      screen.getByRole('spinbutton', { name: /settings.basic.permissionDialogTimeout.label/i }),
    ).toBeTruthy();
  });

  it('exposes native HTML5 min/max attributes that mirror the clamp constants', () => {
    // 浏览器原生 min/max 与 JS clamp 常量必须一致，避免 UI 提示和实际保存规则不一致。
    renderBehaviorTab();

    const input = screen.getByRole('spinbutton', {
      name: /settings.basic.permissionDialogTimeout.label/i,
    });

    expect(input.getAttribute('min')).toBe(String(MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS));
    expect(input.getAttribute('max')).toBe(String(MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS));
  });

  it('clamps low values on blur', () => {
    // 低于最小值时，失焦应回调最小允许秒数。
    const onPermissionDialogTimeoutChange = vi.fn();
    renderBehaviorTab({ onPermissionDialogTimeoutChange });

    const input = screen.getByRole('spinbutton', { name: /settings.basic.permissionDialogTimeout.label/i });
    fireEvent.change(input, { target: { value: '1' } });
    fireEvent.blur(input);

    expect(onPermissionDialogTimeoutChange).toHaveBeenCalledWith(30);
  });

  it('clamps high values on Enter', () => {
    // 高于最大值时，按 Enter 应回调最大允许秒数。
    const onPermissionDialogTimeoutChange = vi.fn();
    renderBehaviorTab({ onPermissionDialogTimeoutChange });

    const input = screen.getByRole('spinbutton', { name: /settings.basic.permissionDialogTimeout.label/i });
    fireEvent.change(input, { target: { value: '99999' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    expect(onPermissionDialogTimeoutChange).toHaveBeenCalledWith(3600);
  });
});
