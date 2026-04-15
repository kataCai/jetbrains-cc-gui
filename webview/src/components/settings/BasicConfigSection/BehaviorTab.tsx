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
  onSaveCustomSoundPath?: () => void;
  onTestSound?: () => void;
  onTestPopup?: () => void;
  onTestBalloon?: () => void;
  onBrowseSound?: () => void;
}

const BehaviorTab = ({
  sendShortcut = 'enter',
  onSendShortcutChange = () => {},
  streamingEnabled = true,
  onStreamingEnabledChange = () => {},
  autoOpenFileEnabled = true,
  onAutoOpenFileEnabledChange = () => {},
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
  onSaveCustomSoundPath = () => {},
  onTestSound = () => {},
  onTestPopup = () => {},
  onTestBalloon = () => {},
  onBrowseSound = () => {},
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
    // popup 只展示当前前端真正支持的强提醒状态，避免出现“可配置但永远不会生效”的选项。
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
      {/* Send shortcut configuration */}
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

      {/* Auto open file configuration */}
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

      {/* Diff expanded by default configuration */}
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

      {/* AI commit generation toggle */}
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

      {/* Status bar widget toggle */}
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

      {/* Task reminder configuration */}
      <div className={styles.streamingSection}>
        <div className={styles.fieldHeader}>
          <span className="codicon codicon-bell" />
          <span className={styles.fieldLabel}>{t('settings.basic.taskReminder.label', 'Task Reminder / 状态提醒')}</span>
        </div>
        <small className={styles.formHint}>
          <span className="codicon codicon-info" />
          <span>{t('settings.basic.taskReminder.hint', 'Configure popup, balloon, and sound reminders by task state.')}</span>
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

        {/* Popup reminder */}
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

        {/* Balloon reminder */}
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

        {/* Sound reminder */}
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
      </div>
    </div>
  );
};

export default BehaviorTab;
