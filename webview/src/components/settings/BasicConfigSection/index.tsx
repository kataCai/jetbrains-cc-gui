import { useState } from 'react';
import styles from './style.module.less';
import { useTranslation } from 'react-i18next';
import type { DiffThemeMode } from '../../../utils/diffTheme';
import type { UiFontConfig } from '../hooks/useSettingsBasicActions';
import type { TaskReminderChannel, TaskReminderConfig, TaskReminderState } from '../../../types/taskReminder';
import AppearanceTab from './AppearanceTab';
import BehaviorTab from './BehaviorTab';
import EnvironmentTab from './EnvironmentTab';

type BasicTab = 'appearance' | 'behavior' | 'environment';

const BASIC_TABS: { key: BasicTab; icon: string; labelKey: string }[] = [
  { key: 'appearance', icon: 'codicon-symbol-color', labelKey: 'settings.basic.tabs.appearance' },
  { key: 'behavior', icon: 'codicon-gear', labelKey: 'settings.basic.tabs.behavior' },
  { key: 'environment', icon: 'codicon-terminal', labelKey: 'settings.basic.tabs.environment' },
];

interface BasicConfigSectionProps {
  theme: 'light' | 'dark' | 'system';
  onThemeChange: (theme: 'light' | 'dark' | 'system') => void;
  fontSizeLevel: number;
  onFontSizeLevelChange: (level: number) => void;
  nodePath: string;
  onNodePathChange: (path: string) => void;
  onSaveNodePath: () => void;
  savingNodePath: boolean;
  nodeVersion?: string | null;
  minNodeVersion?: number;
  claudeCliPath?: string;
  onClaudeCliPathChange?: (path: string) => void;
  onSaveClaudeCliPath?: () => void;
  savingClaudeCliPath?: boolean;
  workingDirectory?: string;
  onWorkingDirectoryChange?: (dir: string) => void;
  onSaveWorkingDirectory?: () => void;
  savingWorkingDirectory?: boolean;
  codexHistoryImageCacheDir?: string;
  codexHistoryImageCacheResolvedDir?: string;
  codexHistoryImageCacheRetentionDays?: number;
  codexHistoryImageCacheMaxSizeMb?: number;
  onCodexHistoryImageCacheDirChange?: (dir: string) => void;
  onCodexHistoryImageCacheRetentionDaysChange?: (days: number) => void;
  onCodexHistoryImageCacheMaxSizeMbChange?: (size: number) => void;
  onBrowseCodexHistoryImageCacheDir?: () => void;
  onResetCodexHistoryImageCacheDir?: () => void;
  onSaveCodexHistoryImageCacheConfig?: () => void;
  savingCodexHistoryImageCache?: boolean;
  editorFontConfig?: {
    fontFamily: string;
    fontSize: number;
    lineSpacing: number;
  };
  uiFontConfig?: UiFontConfig;
  onUiFontSelectionChange?: (selection: string) => void;
  onSaveUiFontCustomPath?: (path: string) => void;
  onBrowseUiFontFile?: () => void;
  streamingEnabled?: boolean;
  onStreamingEnabledChange?: (enabled: boolean) => void;
  autoOpenFileEnabled?: boolean;
  onAutoOpenFileEnabledChange?: (enabled: boolean) => void;
  sendShortcut?: 'enter' | 'cmdEnter';
  onSendShortcutChange?: (shortcut: 'enter' | 'cmdEnter') => void;
  chatBgColor?: string;
  onChatBgColorChange?: (color: string) => void;
  userMsgColor?: string;
  onUserMsgColorChange?: (color: string) => void;
  diffTheme?: DiffThemeMode;
  onDiffThemeChange?: (theme: DiffThemeMode) => void;
  diffExpandedByDefault?: boolean;
  onDiffExpandedByDefaultChange?: (enabled: boolean) => void;
  commitGenerationEnabled?: boolean;
  onCommitGenerationEnabledChange?: (enabled: boolean) => void;
  statusBarWidgetEnabled?: boolean;
  onStatusBarWidgetEnabledChange?: (enabled: boolean) => void;
  taskReminderConfig?: TaskReminderConfig;
  onTaskReminderEnabledChange?: (channel: TaskReminderChannel, enabled: boolean) => void;
  onTaskReminderStateToggle?: (channel: TaskReminderChannel, state: TaskReminderState, enabled: boolean) => void;
  onTaskReminderOnlyWhenIdeUnfocusedChange?: (channel: TaskReminderChannel, enabled: boolean) => void;
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
  // Permission dialog timeout configuration
  permissionDialogTimeoutSeconds?: number;
  onPermissionDialogTimeoutChange?: (seconds: number) => void;
}

/**
 * 设置页基础配置区域。
 * Appearance / Behavior / Environment 三个子面板都通过这里统一装配 props。
 * 并轨后这里需要同时承接当前主线的 taskReminder 行为配置，以及 upstream 的 UI 字体与 AI title 等增强项。
 *
 * @param props 基础配置区所有可配置项与回调
 * @return 基础配置 Tab 视图
 */
const BasicConfigSection = (props: BasicConfigSectionProps) => {
  const { t } = useTranslation();
  const [activeTab, setActiveTab] = useState<BasicTab>('appearance');

  return (
    <div className={styles.configSection}>
      <h3 className={styles.sectionTitle}>{t('settings.basic.title')}</h3>
      <p className={styles.sectionDesc}>{t('settings.basic.description')}</p>

      <div className={styles.basicTabSelector}>
        {BASIC_TABS.map((tab) => (
          <button
            key={tab.key}
            className={`${styles.basicTabBtn} ${activeTab === tab.key ? styles.active : ''}`}
            onClick={() => setActiveTab(tab.key)}
          >
            <span className={`codicon ${tab.icon}`} />
            <span>{t(tab.labelKey)}</span>
          </button>
        ))}
      </div>

      {activeTab === 'appearance' && (
        <AppearanceTab
          theme={props.theme}
          onThemeChange={props.onThemeChange}
          fontSizeLevel={props.fontSizeLevel}
          onFontSizeLevelChange={props.onFontSizeLevelChange}
          editorFontConfig={props.editorFontConfig}
          uiFontConfig={props.uiFontConfig}
          onUiFontSelectionChange={props.onUiFontSelectionChange}
          onSaveUiFontCustomPath={props.onSaveUiFontCustomPath}
          onBrowseUiFontFile={props.onBrowseUiFontFile}
          chatBgColor={props.chatBgColor}
          onChatBgColorChange={props.onChatBgColorChange}
          userMsgColor={props.userMsgColor}
          onUserMsgColorChange={props.onUserMsgColorChange}
          diffTheme={props.diffTheme}
          onDiffThemeChange={props.onDiffThemeChange}
        />
      )}

      {activeTab === 'behavior' && (
        <BehaviorTab
          sendShortcut={props.sendShortcut}
          onSendShortcutChange={props.onSendShortcutChange}
          streamingEnabled={props.streamingEnabled}
          onStreamingEnabledChange={props.onStreamingEnabledChange}
          autoOpenFileEnabled={props.autoOpenFileEnabled}
          onAutoOpenFileEnabledChange={props.onAutoOpenFileEnabledChange}
          diffExpandedByDefault={props.diffExpandedByDefault}
          onDiffExpandedByDefaultChange={props.onDiffExpandedByDefaultChange}
          commitGenerationEnabled={props.commitGenerationEnabled}
          onCommitGenerationEnabledChange={props.onCommitGenerationEnabledChange}
          statusBarWidgetEnabled={props.statusBarWidgetEnabled}
          onStatusBarWidgetEnabledChange={props.onStatusBarWidgetEnabledChange}
          taskReminderConfig={props.taskReminderConfig}
          onTaskReminderEnabledChange={props.onTaskReminderEnabledChange}
          onTaskReminderStateToggle={props.onTaskReminderStateToggle}
          onTaskReminderOnlyWhenIdeUnfocusedChange={props.onTaskReminderOnlyWhenIdeUnfocusedChange}
          onTaskReminderSelectedSoundChange={props.onTaskReminderSelectedSoundChange}
          onTaskReminderCustomSoundPathChange={props.onTaskReminderCustomSoundPathChange}
          onTaskRecoveryPolicyFieldChange={props.onTaskRecoveryPolicyFieldChange}
          aiTitleGenerationEnabled={props.aiTitleGenerationEnabled}
          onAiTitleGenerationEnabledChange={props.onAiTitleGenerationEnabledChange}
          onSaveCustomSoundPath={props.onSaveCustomSoundPath}
          onTestSound={props.onTestSound}
          onTestPopup={props.onTestPopup}
          onTestBalloon={props.onTestBalloon}
          onBrowseSound={props.onBrowseSound}
          taskCompletionNotificationEnabled={props.taskCompletionNotificationEnabled}
          onTaskCompletionNotificationEnabledChange={props.onTaskCompletionNotificationEnabledChange}
          permissionDialogTimeoutSeconds={props.permissionDialogTimeoutSeconds}
          onPermissionDialogTimeoutChange={props.onPermissionDialogTimeoutChange}
        />
      )}

      {activeTab === 'environment' && (
        <EnvironmentTab
          nodePath={props.nodePath}
          onNodePathChange={props.onNodePathChange}
          onSaveNodePath={props.onSaveNodePath}
          savingNodePath={props.savingNodePath}
          nodeVersion={props.nodeVersion}
          minNodeVersion={props.minNodeVersion}
          claudeCliPath={props.claudeCliPath}
          onClaudeCliPathChange={props.onClaudeCliPathChange}
          onSaveClaudeCliPath={props.onSaveClaudeCliPath}
          savingClaudeCliPath={props.savingClaudeCliPath}
          workingDirectory={props.workingDirectory}
          onWorkingDirectoryChange={props.onWorkingDirectoryChange}
          onSaveWorkingDirectory={props.onSaveWorkingDirectory}
          savingWorkingDirectory={props.savingWorkingDirectory}
          codexHistoryImageCacheDir={props.codexHistoryImageCacheDir}
          codexHistoryImageCacheResolvedDir={props.codexHistoryImageCacheResolvedDir}
          codexHistoryImageCacheRetentionDays={props.codexHistoryImageCacheRetentionDays}
          codexHistoryImageCacheMaxSizeMb={props.codexHistoryImageCacheMaxSizeMb}
          onCodexHistoryImageCacheDirChange={props.onCodexHistoryImageCacheDirChange}
          onCodexHistoryImageCacheRetentionDaysChange={props.onCodexHistoryImageCacheRetentionDaysChange}
          onCodexHistoryImageCacheMaxSizeMbChange={props.onCodexHistoryImageCacheMaxSizeMbChange}
          onBrowseCodexHistoryImageCacheDir={props.onBrowseCodexHistoryImageCacheDir}
          onResetCodexHistoryImageCacheDir={props.onResetCodexHistoryImageCacheDir}
          onSaveCodexHistoryImageCacheConfig={props.onSaveCodexHistoryImageCacheConfig}
          savingCodexHistoryImageCache={props.savingCodexHistoryImageCache}
        />
      )}
    </div>
  );
};

export default BasicConfigSection;
