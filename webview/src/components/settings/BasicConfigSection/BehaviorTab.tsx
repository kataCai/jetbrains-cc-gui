import { useState, useRef, useEffect, useMemo, useCallback } from 'react';
import styles from './style.module.less';
import { useTranslation } from 'react-i18next';
import type {
  TaskReminderChannel,
  TaskReminderConfig,
  TaskReminderState,
} from '../../../types/taskReminder';
import {
  DEFAULT_TASK_REMINDER_CONFIG,
  TASK_REMINDER_CHANNEL_STATES,
} from '../../../types/taskReminder';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from '../../../utils/permissionDialogTimeout';
import { PermissionDialogTimeoutSetting } from './PermissionDialogTimeoutSetting';

/** Upward-opening custom select for sound selection (avoids JCEF clipping) */
const SoundSelectUpward = ({
  value,
  onChange,
  options,
  onTestSound,
  testSoundLabel,
}: {
  value: string;
  onChange: (val: string) => void;
  options: { value: string; label: string }[];
  onTestSound: () => void;
  testSoundLabel: string;
}) => {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const selectedLabel = options.find((o) => o.value === value)?.label ?? value;

  const handleClickOutside = useCallback((e: MouseEvent) => {
    if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
      setOpen(false);
    }
  }, []);

  useEffect(() => {
    if (open) {
      document.addEventListener('mousedown', handleClickOutside);
    }
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [open, handleClickOutside]);

  return (
    <div className={styles.soundSelectRow}>
      <div className={styles.upwardSelect} ref={containerRef}>
        <button
          type="button"
          className={`${styles.upwardSelectTrigger} ${open ? styles.open : ''}`}
          onClick={() => setOpen((prev) => !prev)}
        >
          {selectedLabel}
        </button>
        {open && (
          <div className={styles.upwardSelectDropdown}>
            {options.map((opt) => (
              <div
                key={opt.value}
                className={`${styles.upwardSelectOption} ${opt.value === value ? styles.selected : ''}`}
                onClick={() => {
                  onChange(opt.value);
                  setOpen(false);
                }}
              >
                {opt.label}
              </div>
            ))}
          </div>
        )}
      </div>
      <button
        className={styles.soundTestBtn}
        onClick={onTestSound}
        title={testSoundLabel}
      >
        <span className="codicon codicon-play" />
      </button>
    </div>
  );
};

export interface BehaviorTabProps {
  sendShortcut?: 'enter' | 'cmdEnter';
  onSendShortcutChange?: (shortcut: 'enter' | 'cmdEnter') => void;
  streamingEnabled?: boolean;
  onStreamingEnabledChange?: (enabled: boolean) => void;
  autoOpenFileEnabled?: boolean;
  onAutoOpenFileEnabledChange?: (enabled: boolean) => void;
  frontendDebugPanelEnabled?: boolean;
  onFrontendDebugPanelEnabledChange?: (enabled: boolean) => void;
  frontendDiagnosticArchiveEnabled?: boolean;
  onFrontendDiagnosticArchiveEnabledChange?: (enabled: boolean) => void;
  rightClickOpenDevToolsEnabled?: boolean;
  onRightClickOpenDevToolsEnabledChange?: (enabled: boolean) => void;
  diffExpandedByDefault?: boolean;
  onDiffExpandedByDefaultChange?: (enabled: boolean) => void;
  commitGenerationEnabled?: boolean;
  onCommitGenerationEnabledChange?: (enabled: boolean) => void;
  statusBarWidgetEnabled?: boolean;
  onStatusBarWidgetEnabledChange?: (enabled: boolean) => void;
  taskReminderConfig?: TaskReminderConfig;
  onTaskReminderEnabledChange?: (channel: TaskReminderChannel, enabled: boolean) => void;
  onTaskReminderStateToggle?: (
    channel: TaskReminderChannel,
    state: TaskReminderState,
    enabled: boolean,
  ) => void;
  onTaskReminderOnlyWhenIdeUnfocusedChange?: (
    channel: TaskReminderChannel,
    enabled: boolean,
  ) => void;
  onTaskReminderSelectedSoundChange?: (soundId: string) => void;
  onTaskReminderCustomSoundPathChange?: (path: string) => void;
  onTaskRecoveryPolicyFieldChange?: (
    field: 'enabled' | 'recoverCompletedOnParseNoise' | 'retryTransientErrors' | 'maxAttempts' | 'initialDelayMs',
    value: boolean | number,
  ) => void;
  aiTitleGenerationEnabled?: boolean;
  onAiTitleGenerationEnabledChange?: (enabled: boolean) => void;
  onSaveCustomSoundPath?: () => void;
  onTestSound?: () => void;
  onTestPopup?: () => void;
  onTestBalloon?: () => void;
  onBrowseSound?: () => void;
  taskCompletionNotificationEnabled?: boolean;
  onTaskCompletionNotificationEnabledChange?: (enabled: boolean) => void;
  permissionDialogTimeoutSeconds?: number;
  onPermissionDialogTimeoutChange?: (seconds: number) => void;
}

/**
 * 行为配置页签。
 * 并轨策略是保留当前主线的 canonical taskReminder / recovery policy 配置，
 * 同时补上 upstream 的 AI title generation 与 task completion notification 开关。
 * 这两类配置面向的职责不同，不应在同一阶段互相覆盖。
 *
 * @param props 行为配置所需的全部状态与回调
 * @return 行为配置内容
 */
const BehaviorTab = ({
  sendShortcut = 'enter',
  onSendShortcutChange = () => {},
  streamingEnabled = true,
  onStreamingEnabledChange = () => {},
  autoOpenFileEnabled = true,
  onAutoOpenFileEnabledChange = () => {},
  frontendDebugPanelEnabled = false,
  onFrontendDebugPanelEnabledChange = () => {},
  frontendDiagnosticArchiveEnabled = false,
  onFrontendDiagnosticArchiveEnabledChange = () => {},
  rightClickOpenDevToolsEnabled = false,
  onRightClickOpenDevToolsEnabledChange = () => {},
  diffExpandedByDefault = false,
  onDiffExpandedByDefaultChange = () => {},
  commitGenerationEnabled = true,
  onCommitGenerationEnabledChange = () => {},
  statusBarWidgetEnabled = true,
  onStatusBarWidgetEnabledChange = () => {},
  taskReminderConfig = DEFAULT_TASK_REMINDER_CONFIG,
  onTaskReminderEnabledChange = () => {},
  onTaskReminderStateToggle = () => {},
  onTaskReminderOnlyWhenIdeUnfocusedChange = () => {},
  onTaskReminderSelectedSoundChange = () => {},
  onTaskReminderCustomSoundPathChange = () => {},
  onTaskRecoveryPolicyFieldChange = () => {},
  aiTitleGenerationEnabled = true,
  onAiTitleGenerationEnabledChange = () => {},
  onSaveCustomSoundPath = () => {},
  onTestSound = () => {},
  onTestPopup = () => {},
  onTestBalloon = () => {},
  onBrowseSound = () => {},
  taskCompletionNotificationEnabled = false,
  onTaskCompletionNotificationEnabledChange = () => {},
  permissionDialogTimeoutSeconds = DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  onPermissionDialogTimeoutChange = () => {},
}: BehaviorTabProps) => {
  const { t } = useTranslation();

  const soundOptions = useMemo(() => [
    { value: 'default', label: t('settings.basic.soundNotification.soundDefault') },
    { value: 'chime', label: t('settings.basic.soundNotification.soundChime') },
    { value: 'bell', label: t('settings.basic.soundNotification.soundBell') },
    { value: 'ding', label: t('settings.basic.soundNotification.soundDing') },
    { value: 'success', label: t('settings.basic.soundNotification.soundSuccess') },
    { value: 'custom', label: t('settings.basic.soundNotification.soundCustom') },
  ], [t]);

  const stateLabelMap = useMemo<Record<TaskReminderState, string>>(() => ({
    waiting_confirm: t('settings.basic.taskReminder.state.waitingConfirm', 'Waiting for confirmation'),
    retrying: t('settings.basic.taskReminder.state.retrying', 'Retrying'),
    recovered: t('settings.basic.taskReminder.state.recovered', 'Recovered'),
    final_error: t('settings.basic.taskReminder.state.finalError', 'Final error'),
    completed: t('settings.basic.taskReminder.state.completed', 'Completed'),
  }), [t]);

  const renderReminderStates = (
    channel: TaskReminderChannel,
    title: string,
    selectedStates: TaskReminderState[],
  ) => (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginBottom: '10px' }}>
      {TASK_REMINDER_CHANNEL_STATES[channel].map((state) => {
        const checked = selectedStates.includes(state);
        return (
          <label key={`${channel}-${state}`} style={{ display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
            <input
              aria-label={`${title} state ${state}`}
              type="checkbox"
              checked={checked}
              onChange={(event) => onTaskReminderStateToggle(channel, state, event.target.checked)}
            />
            <span>{stateLabelMap[state]}</span>
          </label>
        );
      })}
    </div>
  );

  return (
    <div className={styles.tabContent}>
      <div className={styles.sendShortcutSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-keyboard" />
          <span className={styles.fieldLabel}>{t('settings.basic.sendShortcut.label')}</span>
        </div>
        <div className={styles.themeGrid}>
          <div
            className={`${styles.themeCard} ${sendShortcut === 'enter' ? styles.active : ''}`}
            onClick={() => onSendShortcutChange('enter')}
          >
            {sendShortcut === 'enter' && (
              <div className={styles.checkBadge}>
                <span className="codicon codicon-check" />
              </div>
            )}
            <div className={styles.themeCardTitle}>{t('settings.basic.sendShortcut.enter')}</div>
            <div className={styles.themeCardDesc}>{t('settings.basic.sendShortcut.enterDesc')}</div>
          </div>

          <div
            className={`${styles.themeCard} ${sendShortcut === 'cmdEnter' ? styles.active : ''}`}
            onClick={() => onSendShortcutChange('cmdEnter')}
          >
            {sendShortcut === 'cmdEnter' && (
              <div className={styles.checkBadge}>
                <span className="codicon codicon-check" />
              </div>
            )}
            <div className={styles.themeCardTitle}>{t('settings.basic.sendShortcut.cmdEnter')}</div>
            <div className={styles.themeCardDesc}>{t('settings.basic.sendShortcut.cmdEnterDesc')}</div>
          </div>
        </div>
      </div>

      <PermissionDialogTimeoutSetting
        permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
        onPermissionDialogTimeoutChange={onPermissionDialogTimeoutChange}
      />

      {/* Streaming configuration */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-sync" />
          <span className={styles.fieldLabel}>{t('settings.basic.streaming.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={streamingEnabled}
            onChange={(e) => onStreamingEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {streamingEnabled
              ? t('settings.basic.streaming.enabled')
              : t('settings.basic.streaming.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.streaming.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-file" />
          <span className={styles.fieldLabel}>{t('settings.basic.autoOpenFile.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={autoOpenFileEnabled}
            onChange={(e) => onAutoOpenFileEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {autoOpenFileEnabled
              ? t('settings.basic.autoOpenFile.enabled')
              : t('settings.basic.autoOpenFile.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.autoOpenFile.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-debug" />
          <span className={styles.fieldLabel}>{t('settings.basic.frontendDebugPanel.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={frontendDebugPanelEnabled}
            onChange={(e) => onFrontendDebugPanelEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {frontendDebugPanelEnabled
              ? t('settings.basic.frontendDebugPanel.enabled')
              : t('settings.basic.frontendDebugPanel.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.frontendDebugPanel.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-output" />
          <span className={styles.fieldLabel}>{t('settings.basic.frontendDiagnosticArchive.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={frontendDiagnosticArchiveEnabled}
            onChange={(e) => onFrontendDiagnosticArchiveEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {frontendDiagnosticArchiveEnabled
              ? t('settings.basic.frontendDiagnosticArchive.enabled')
              : t('settings.basic.frontendDiagnosticArchive.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.frontendDiagnosticArchive.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-debug" />
          <span className={styles.fieldLabel}>{t('settings.basic.rightClickOpenDevTools.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={rightClickOpenDevToolsEnabled}
            onChange={(e) => onRightClickOpenDevToolsEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {rightClickOpenDevToolsEnabled
              ? t('settings.basic.rightClickOpenDevTools.enabled')
              : t('settings.basic.rightClickOpenDevTools.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.rightClickOpenDevTools.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-diff" />
          <span className={styles.fieldLabel}>{t('settings.basic.diffExpanded.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={diffExpandedByDefault}
            onChange={(e) => onDiffExpandedByDefaultChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {diffExpandedByDefault
              ? t('settings.basic.diffExpanded.enabled')
              : t('settings.basic.diffExpanded.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.diffExpanded.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-git-commit" />
          <span className={styles.fieldLabel}>{t('settings.basic.commitGeneration.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={commitGenerationEnabled}
            onChange={(e) => onCommitGenerationEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {commitGenerationEnabled
              ? t('settings.basic.commitGeneration.enabled')
              : t('settings.basic.commitGeneration.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.commitGeneration.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-layout-statusbar" />
          <span className={styles.fieldLabel}>{t('settings.basic.statusBarWidget.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={statusBarWidgetEnabled}
            onChange={(e) => onStatusBarWidgetEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {statusBarWidgetEnabled
              ? t('settings.basic.statusBarWidget.enabled')
              : t('settings.basic.statusBarWidget.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.statusBarWidget.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-bell" />
          <span className={styles.fieldLabel}>{t('settings.basic.taskCompletionNotification.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={taskCompletionNotificationEnabled}
            onChange={(e) => onTaskCompletionNotificationEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {taskCompletionNotificationEnabled
              ? t('settings.basic.taskCompletionNotification.enabled')
              : t('settings.basic.taskCompletionNotification.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.taskCompletionNotification.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-sparkle" />
          <span className={styles.fieldLabel}>{t('settings.other.aiTitleGeneration.label')}</span>
        </div>
        <label className={styles.toggleWrapper}>
          <input
            type="checkbox"
            className={styles.toggleInput}
            checked={aiTitleGenerationEnabled}
            onChange={(e) => onAiTitleGenerationEnabledChange(e.target.checked)}
          />
          <span className={styles.toggleSlider} />
          <span className={styles.toggleLabel}>
            {aiTitleGenerationEnabled
              ? t('settings.other.aiTitleGeneration.enabled')
              : t('settings.other.aiTitleGeneration.disabled')}
          </span>
        </label>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.other.aiTitleGeneration.hint')}</span>
        </small>
      </div>

      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-bell" />
          <span className={styles.fieldLabel}>{t('settings.basic.taskReminder.label', 'Task Reminder / 状态提醒')}</span>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.taskReminder.hint', 'Configure popup, balloon, sound, and system reminders by task state.')}</span>
        </small>
        <small className={styles.formHint}>
          <span className="codicon codicon-bell-dot" />
          <span>
            {t(
              'settings.basic.taskReminder.balloonNotificationsHint',
              'Balloon visibility also depends on IDE Notifications settings: Settings | Appearance & Behavior | Notifications.',
            )}
          </span>
        </small>

        <div className={styles.customSoundSection}>
          <div className={styles.fieldHeader}>
            <span className="codicon codicon-comment-discussion" />
            <span className={styles.fieldLabel}>{t('settings.basic.taskReminder.popup', 'Popup')}</span>
          </div>
          <label style={{ display: 'block', marginBottom: '8px' }}>
            <input
              aria-label="Popup enabled"
              type="checkbox"
              checked={taskReminderConfig.popup.enabled}
              onChange={(event) => onTaskReminderEnabledChange('popup', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.enabled', 'Enabled')}</span>
          </label>
          {renderReminderStates('popup', 'Popup', taskReminderConfig.popup.states)}
          <label style={{ display: 'block' }}>
            <input
              aria-label="Popup only when IDE unfocused"
              type="checkbox"
              checked={taskReminderConfig.popup.onlyWhenIdeUnfocused}
              onChange={(event) => onTaskReminderOnlyWhenIdeUnfocusedChange('popup', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.onlyWhenIdeUnfocused', 'Only when IDE unfocused')}</span>
          </label>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '12px' }}>
            <button
              type="button"
              className={styles.saveBtn}
              onClick={onTestPopup}
            >
              {t('settings.basic.taskReminder.testPopup', 'Test popup')}
            </button>
          </div>
        </div>

        <div className={styles.customSoundSection}>
          <div className={styles.fieldHeader}>
            <span className="codicon codicon-notifications" />
            <span className={styles.fieldLabel}>{t('settings.basic.taskReminder.balloon', 'Balloon')}</span>
          </div>
          <label style={{ display: 'block', marginBottom: '8px' }}>
            <input
              aria-label="Balloon enabled"
              type="checkbox"
              checked={taskReminderConfig.balloon.enabled}
              onChange={(event) => onTaskReminderEnabledChange('balloon', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.enabled', 'Enabled')}</span>
          </label>
          {renderReminderStates('balloon', 'Balloon', taskReminderConfig.balloon.states)}
          <label style={{ display: 'block' }}>
            <input
              aria-label="Balloon only when IDE unfocused"
              type="checkbox"
              checked={taskReminderConfig.balloon.onlyWhenIdeUnfocused}
              onChange={(event) => onTaskReminderOnlyWhenIdeUnfocusedChange('balloon', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.onlyWhenIdeUnfocused', 'Only when IDE unfocused')}</span>
          </label>
          <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '12px' }}>
            <button
              type="button"
              className={styles.saveBtn}
              onClick={onTestBalloon}
            >
              {t('settings.basic.taskReminder.testBalloon', 'Test balloon')}
            </button>
          </div>
        </div>

        <div className={styles.customSoundSection}>
          <div className={styles.fieldHeader}>
            <span className="codicon codicon-unmute" />
            <span className={styles.fieldLabel}>{t('settings.basic.taskReminder.sound', 'Sound')}</span>
          </div>
          <label style={{ display: 'block', marginBottom: '8px' }}>
            <input
              aria-label="Sound enabled"
              type="checkbox"
              checked={taskReminderConfig.sound.enabled}
              onChange={(event) => onTaskReminderEnabledChange('sound', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.enabled', 'Enabled')}</span>
          </label>
          {renderReminderStates('sound', 'Sound', taskReminderConfig.sound.states)}
          <label style={{ display: 'block', marginBottom: '12px' }}>
            <input
              aria-label="Sound only when IDE unfocused"
              type="checkbox"
              checked={taskReminderConfig.sound.onlyWhenIdeUnfocused}
              onChange={(event) => onTaskReminderOnlyWhenIdeUnfocusedChange('sound', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.onlyWhenIdeUnfocused', 'Only when IDE unfocused')}</span>
          </label>

          <div className={styles.fieldHeader}>
            <span className="codicon codicon-library" />
            <span className={styles.fieldLabel}>{t('settings.basic.soundNotification.selectSound', 'Select sound')}</span>
          </div>
          <SoundSelectUpward
            value={taskReminderConfig.sound.selectedSound}
            onChange={onTaskReminderSelectedSoundChange}
            options={soundOptions}
            onTestSound={onTestSound}
            testSoundLabel={t('settings.basic.soundNotification.testSound', 'Test sound')}
          />

          {taskReminderConfig.sound.selectedSound === 'custom' && (
            <div className={styles.customSoundFileSection}>
              <div className={styles.fieldHeader}>
                <span className="codicon codicon-file-media" />
                <span className={styles.fieldLabel}>{t('settings.basic.soundNotification.customSound', 'Custom sound')}</span>
              </div>
              <div className={styles.nodePathInputWrapper}>
                <input
                  type="text"
                  className={styles.nodePathInput}
                  placeholder={t('settings.basic.soundNotification.customSoundPlaceholder', 'Input custom sound path')}
                  value={taskReminderConfig.sound.customSoundPath}
                  onChange={(event) => onTaskReminderCustomSoundPathChange(event.target.value)}
                />
                <button
                  className={styles.saveBtn}
                  onClick={onBrowseSound}
                  title={t('settings.basic.soundNotification.browse', 'Browse')}
                >
                  <span className="codicon codicon-folder-opened" />
                </button>
                <button
                  className={styles.saveBtn}
                  onClick={onSaveCustomSoundPath}
                >
                  {t('common.save', 'Save')}
                </button>
              </div>
            </div>
          )}
        </div>

        <div className={styles.customSoundSection}>
          <div className={styles.fieldHeader}>
            <span className="codicon codicon-device-desktop" />
            <span className={styles.fieldLabel}>{t('settings.basic.taskReminder.system', 'System')}</span>
          </div>
          <label style={{ display: 'block', marginBottom: '8px' }}>
            <input
              aria-label="System enabled"
              type="checkbox"
              checked={taskReminderConfig.system.enabled}
              onChange={(event) => onTaskReminderEnabledChange('system', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.enabled', 'Enabled')}</span>
          </label>
          {renderReminderStates('system', 'System', taskReminderConfig.system.states)}
          <label style={{ display: 'block' }}>
            <input
              aria-label="System only when IDE unfocused"
              type="checkbox"
              checked={taskReminderConfig.system.onlyWhenIdeUnfocused}
              onChange={(event) => onTaskReminderOnlyWhenIdeUnfocusedChange('system', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskReminder.onlyWhenIdeUnfocused', 'Only when IDE unfocused')}</span>
          </label>
        </div>

        <div className={styles.customSoundSection}>
          <div className={styles.fieldHeader}>
            <span className="codicon codicon-debug-restart" />
            <span className={styles.fieldLabel}>{t('settings.basic.taskRecovery.label', 'Task Recovery / 任务恢复')}</span>
          </div>
          <label style={{ display: 'block', marginBottom: '8px' }}>
            <input
              aria-label="Recovery policy enabled"
              type="checkbox"
              checked={taskReminderConfig.recoveryPolicy.enabled}
              onChange={(event) => onTaskRecoveryPolicyFieldChange('enabled', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>{t('settings.basic.taskRecovery.enabled', 'Enabled')}</span>
          </label>
          <label style={{ display: 'block', marginBottom: '8px' }}>
            <input
              aria-label="Recover parse noise as completed"
              type="checkbox"
              checked={taskReminderConfig.recoveryPolicy.recoverCompletedOnParseNoise}
              onChange={(event) => onTaskRecoveryPolicyFieldChange('recoverCompletedOnParseNoise', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>
              {t('settings.basic.taskRecovery.promoteSuccess', 'Treat post-success parse noise as completed')}
            </span>
          </label>
          <label style={{ display: 'block', marginBottom: '12px' }}>
            <input
              aria-label="Retry transient errors"
              type="checkbox"
              checked={taskReminderConfig.recoveryPolicy.retryTransientErrors}
              onChange={(event) => onTaskRecoveryPolicyFieldChange('retryTransientErrors', event.target.checked)}
            />
            <span style={{ marginLeft: '6px' }}>
              {t('settings.basic.taskRecovery.retryTransient', 'Auto retry transient network/provider errors')}
            </span>
          </label>
          <div className={styles.nodePathInputWrapper} style={{ marginBottom: '8px' }}>
            <input
              type="number"
              min={1}
              max={5}
              className={styles.nodePathInput}
              aria-label="Max retry attempts"
              value={taskReminderConfig.recoveryPolicy.maxAttempts}
              onChange={(event) => onTaskRecoveryPolicyFieldChange('maxAttempts', Number(event.target.value || 1))}
            />
            <span className={styles.fieldLabel}>{t('settings.basic.taskRecovery.maxAttempts', 'Max retry attempts')}</span>
          </div>
          <div className={styles.nodePathInputWrapper}>
            <input
              type="number"
              min={0}
              step={100}
              className={styles.nodePathInput}
              aria-label="Initial retry delay milliseconds"
              value={taskReminderConfig.recoveryPolicy.initialDelayMs}
              onChange={(event) => onTaskRecoveryPolicyFieldChange('initialDelayMs', Number(event.target.value || 0))}
            />
            <span className={styles.fieldLabel}>{t('settings.basic.taskRecovery.initialDelayMs', 'Initial retry delay (ms)')}</span>
          </div>
        </div>
      </div>
    </div>
  );
};

export default BehaviorTab;
