// hooks/useSettingsBasicActions.ts
import { useState, useEffect, useCallback } from 'react';
import type { TaskReminderChannel, TaskReminderConfig, TaskReminderState } from '../../../types/taskReminder';
import {
  DEFAULT_TASK_REMINDER_CONFIG,
  normalizeTaskReminderConfig,
} from '../../../types/taskReminder';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

export interface UseSettingsBasicActionsProps {
  streamingEnabledProp?: boolean;
  onStreamingEnabledChangeProp?: (enabled: boolean) => void;
  sendShortcutProp?: 'enter' | 'cmdEnter';
  onSendShortcutChangeProp?: (shortcut: 'enter' | 'cmdEnter') => void;
  autoOpenFileEnabledProp?: boolean;
  onAutoOpenFileEnabledChangeProp?: (enabled: boolean) => void;
}

export interface UseSettingsBasicActionsReturn {
  // =========================================================================
  // Public read-only state (safe to read in components)
  // =========================================================================
  nodePath: string;
  nodeVersion: string | null;
  minNodeVersion: number;
  savingNodePath: boolean;
  workingDirectory: string;
  savingWorkingDirectory: boolean;
  editorFontConfig:
    | {
        fontFamily: string;
        fontSize: number;
        lineSpacing: number;
      }
    | undefined;
  /** Streaming enabled state (prefers prop over local state) */
  streamingEnabled: boolean;
  localStreamingEnabled: boolean;
  codexSandboxMode: 'workspace-write' | 'danger-full-access';
  /** Send shortcut state (prefers prop over local state) */
  sendShortcut: 'enter' | 'cmdEnter';
  localSendShortcut: 'enter' | 'cmdEnter';
  /** Auto open file state (prefers prop over local state) */
  autoOpenFileEnabled: boolean;
  localAutoOpenFileEnabled: boolean;
  commitPrompt: string;
  savingCommitPrompt: boolean;
  taskReminderConfig: TaskReminderConfig;
  diffExpandedByDefault: boolean;
  historyCompletionEnabled: boolean;
  commitGenerationEnabled: boolean;
  statusBarWidgetEnabled: boolean;

  // =========================================================================
  // Handler functions (public API for components)
  // =========================================================================
  handleSaveNodePath: () => void;
  handleSaveWorkingDirectory: () => void;
  handleStreamingEnabledChange: (enabled: boolean) => void;
  handleCodexSandboxModeChange: (mode: 'workspace-write' | 'danger-full-access') => void;
  handleSendShortcutChange: (shortcut: 'enter' | 'cmdEnter') => void;
  handleAutoOpenFileEnabledChange: (enabled: boolean) => void;
  handleTaskReminderEnabledChange: (channel: TaskReminderChannel, enabled: boolean) => void;
  handleTaskReminderStateToggle: (
    channel: TaskReminderChannel,
    state: TaskReminderState,
    enabled: boolean,
  ) => void;
  handleTaskReminderOnlyWhenIdeUnfocusedChange: (
    channel: TaskReminderChannel,
    enabled: boolean,
  ) => void;
  handleTaskReminderSelectedSoundChange: (soundId: string) => void;
  handleTaskReminderCustomSoundPathChange: (path: string) => void;
  handleTaskRecoveryPolicyFieldChange: (
    field: 'enabled' | 'recoverCompletedOnParseNoise' | 'retryTransientErrors' | 'maxAttempts' | 'initialDelayMs',
    value: boolean | number,
  ) => void;
  handleSaveCustomSoundPath: () => void;
  handleTestSound: () => void;
  handleTestPopup: () => void;
  handleTestBalloon: () => void;
  handleBrowseSound: () => void;
  handleSaveCommitPrompt: () => void;
  handleCommitGenerationEnabledChange: (enabled: boolean) => void;
  handleStatusBarWidgetEnabledChange: (enabled: boolean) => void;

  // =========================================================================
  // @internal — State setters used only by useSettingsWindowCallbacks.
  // Components should not call these directly; use handlers above instead.
  // =========================================================================
  /** @internal */ setNodePath: (path: string) => void;
  /** @internal */ setNodeVersion: (version: string | null) => void;
  /** @internal */ setMinNodeVersion: (version: number) => void;
  /** @internal */ setSavingNodePath: (saving: boolean) => void;
  /** @internal */ setWorkingDirectory: (dir: string) => void;
  /** @internal */ setSavingWorkingDirectory: (saving: boolean) => void;
  /** @internal */ setEditorFontConfig: (
    config:
      | {
          fontFamily: string;
          fontSize: number;
          lineSpacing: number;
        }
      | undefined
  ) => void;
  /** @internal */ setLocalStreamingEnabled: (enabled: boolean) => void;
  /** @internal */ setCodexSandboxMode: (mode: 'workspace-write' | 'danger-full-access') => void;
  /** @internal */ setLocalSendShortcut: (shortcut: 'enter' | 'cmdEnter') => void;
  /** @internal */ setLocalAutoOpenFileEnabled: (enabled: boolean) => void;
  /** @internal */ setCommitPrompt: (prompt: string) => void;
  /** @internal */ setSavingCommitPrompt: (saving: boolean) => void;
  /** @internal */ setTaskReminderConfig: (
    config: TaskReminderConfig | ((prev: TaskReminderConfig) => TaskReminderConfig)
  ) => void;
  /** @internal */ setDiffExpandedByDefault: (expanded: boolean) => void;
  /** @internal */ setHistoryCompletionEnabled: (enabled: boolean) => void;
  /** @internal */ setCommitGenerationEnabled: (enabled: boolean) => void;
  /** @internal */ setStatusBarWidgetEnabled: (enabled: boolean) => void;
}

export function useSettingsBasicActions({
  streamingEnabledProp,
  onStreamingEnabledChangeProp,
  sendShortcutProp,
  onSendShortcutChangeProp,
  autoOpenFileEnabledProp,
  onAutoOpenFileEnabledChangeProp,
}: UseSettingsBasicActionsProps): UseSettingsBasicActionsReturn {
  // Node.js path
  const [nodePath, setNodePath] = useState('');
  const [nodeVersion, setNodeVersion] = useState<string | null>(null);
  const [minNodeVersion, setMinNodeVersion] = useState(18);
  const [savingNodePath, setSavingNodePath] = useState(false);

  // Working directory configuration
  const [workingDirectory, setWorkingDirectory] = useState('');
  const [savingWorkingDirectory, setSavingWorkingDirectory] = useState(false);

  // IDEA editor font configuration (read-only display)
  const [editorFontConfig, setEditorFontConfig] = useState<
    | {
        fontFamily: string;
        fontSize: number;
        lineSpacing: number;
      }
    | undefined
  >();

  // Streaming configuration - prefer props, fallback to local state
  const [localStreamingEnabled, setLocalStreamingEnabled] = useState<boolean>(false);
  const streamingEnabled = streamingEnabledProp ?? localStreamingEnabled;

  const [codexSandboxMode, setCodexSandboxMode] = useState<'workspace-write' | 'danger-full-access'>(
    'danger-full-access'
  );

  // Send shortcut configuration - prefer props, fallback to local state
  const [localSendShortcut, setLocalSendShortcut] = useState<'enter' | 'cmdEnter'>('enter');
  const sendShortcut = sendShortcutProp ?? localSendShortcut;

  // Auto open file configuration - prefer props, fallback to local state
  const [localAutoOpenFileEnabled, setLocalAutoOpenFileEnabled] = useState<boolean>(false);
  const autoOpenFileEnabled = autoOpenFileEnabledProp ?? localAutoOpenFileEnabled;

  // Commit AI prompt configuration
  const [commitPrompt, setCommitPrompt] = useState('');
  const [savingCommitPrompt, setSavingCommitPrompt] = useState(false);

  // Canonical task reminder configuration
  const [taskReminderConfig, setTaskReminderConfigState] = useState<TaskReminderConfig>(
    DEFAULT_TASK_REMINDER_CONFIG,
  );

  // Diff expanded by default configuration (localStorage-only)
  const [diffExpandedByDefault, setDiffExpandedByDefault] = useState<boolean>(() => {
    try {
      return localStorage.getItem('diffExpandedByDefault') === 'true';
    } catch {
      return false;
    }
  });

  // History completion toggle configuration
  const [historyCompletionEnabled, setHistoryCompletionEnabled] = useState<boolean>(() => {
    const saved = localStorage.getItem('historyCompletionEnabled');
    return saved !== 'false'; // Enabled by default
  });

  // AI commit generation toggle (default: true)
  const [commitGenerationEnabled, setCommitGenerationEnabled] = useState<boolean>(true);

  // Status bar widget toggle (default: true)
  const [statusBarWidgetEnabled, setStatusBarWidgetEnabled] = useState<boolean>(true);

  // Diff expanded by default handler
  useEffect(() => {
    try {
      if (diffExpandedByDefault) {
        localStorage.setItem('diffExpandedByDefault', 'true');
      } else {
        localStorage.removeItem('diffExpandedByDefault');
      }
    } catch { /* ignore storage errors */ }
  }, [diffExpandedByDefault]);

  const handleSaveNodePath = useCallback(() => {
    setSavingNodePath(true);
    const payload = { path: (nodePath || '').trim() };
    sendToJava(`set_node_path:${JSON.stringify(payload)}`);
  }, [nodePath]);

  const handleSaveWorkingDirectory = useCallback(() => {
    setSavingWorkingDirectory(true);
    const payload = { customWorkingDir: (workingDirectory || '').trim() };
    sendToJava(`set_working_directory:${JSON.stringify(payload)}`);
  }, [workingDirectory]);

  // Streaming toggle change handler
  const handleStreamingEnabledChange = useCallback((enabled: boolean) => {
    // If prop callback is provided (from App.tsx), use it for centralized state management
    if (onStreamingEnabledChangeProp) {
      onStreamingEnabledChangeProp(enabled);
    } else {
      // Fallback to local state if no prop callback provided
      setLocalStreamingEnabled(enabled);
      const payload = { streamingEnabled: enabled };
      sendToJava(`set_streaming_enabled:${JSON.stringify(payload)}`);
    }
  }, [onStreamingEnabledChangeProp]);

  const handleCodexSandboxModeChange = useCallback((mode: 'workspace-write' | 'danger-full-access') => {
    setCodexSandboxMode(mode);
    const payload = { sandboxMode: mode };
    sendToJava(`set_codex_sandbox_mode:${JSON.stringify(payload)}`);
  }, []);

  // Send shortcut change handler
  const handleSendShortcutChange = useCallback((shortcut: 'enter' | 'cmdEnter') => {
    // If prop callback is provided (from App.tsx), use it for centralized state management
    if (onSendShortcutChangeProp) {
      onSendShortcutChangeProp(shortcut);
    } else {
      // Fallback to local state if no prop callback provided
      setLocalSendShortcut(shortcut);
      const payload = { sendShortcut: shortcut };
      sendToJava(`set_send_shortcut:${JSON.stringify(payload)}`);
    }
  }, [onSendShortcutChangeProp]);

  // Auto open file toggle change handler
  const handleAutoOpenFileEnabledChange = useCallback((enabled: boolean) => {
    // If prop callback is provided (from App.tsx), use it for centralized state management
    if (onAutoOpenFileEnabledChangeProp) {
      onAutoOpenFileEnabledChangeProp(enabled);
    } else {
      // Fallback to local state if no prop callback provided
      setLocalAutoOpenFileEnabled(enabled);
      const payload = { autoOpenFileEnabled: enabled };
      sendToJava(`set_auto_open_file_enabled:${JSON.stringify(payload)}`);
    }
  }, [onAutoOpenFileEnabledChangeProp]);

  const setTaskReminderConfig: UseSettingsBasicActionsReturn['setTaskReminderConfig'] = useCallback((nextConfig) => {
    setTaskReminderConfigState((prev) => {
      // 无论数据来自窗口回调还是本地交互，都重新做一次 normalize，
      // 保证 settings 组件树内部看到的永远是完整、合法、可渲染的配置。
      const resolved = normalizeTaskReminderConfig(
        typeof nextConfig === 'function' ? nextConfig(prev) : nextConfig
      );
      return resolved;
    });
  }, []);

  const updateAndPersistTaskReminder = useCallback((updater: (prev: TaskReminderConfig) => TaskReminderConfig) => {
    setTaskReminderConfigState((prev) => {
      const next = normalizeTaskReminderConfig(updater(prev));
      // 采用“先本地乐观更新，再异步发给 Java”的方式，
      // 让设置页切换体验立即生效；Java 回推配置后再做一次最终对齐。
      sendToJava(`set_task_reminder_config:${JSON.stringify(next)}`);
      return next;
    });
  }, []);

  const handleTaskReminderEnabledChange = useCallback((channel: TaskReminderChannel, enabled: boolean) => {
    updateAndPersistTaskReminder((prev) => ({
      ...prev,
      [channel]: {
        ...prev[channel],
        enabled,
      },
    }));
  }, [updateAndPersistTaskReminder]);

  const handleTaskReminderStateToggle = useCallback((
    channel: TaskReminderChannel,
    state: TaskReminderState,
    enabled: boolean,
  ) => {
    updateAndPersistTaskReminder((prev) => {
      const currentStates = prev[channel].states;
      // 用 Set 保证状态列表天然去重，避免用户反复点击或旧数据回放后出现重复状态。
      const nextStates = enabled
        ? Array.from(new Set([...currentStates, state]))
        : currentStates.filter((item) => item !== state);
      return {
        ...prev,
        [channel]: {
          ...prev[channel],
          states: nextStates,
        },
      };
    });
  }, [updateAndPersistTaskReminder]);

  const handleTaskReminderOnlyWhenIdeUnfocusedChange = useCallback((
    channel: TaskReminderChannel,
    enabled: boolean,
  ) => {
    updateAndPersistTaskReminder((prev) => ({
      ...prev,
      [channel]: {
        ...prev[channel],
        onlyWhenIdeUnfocused: enabled,
      },
    }));
  }, [updateAndPersistTaskReminder]);

  const handleTaskReminderSelectedSoundChange = useCallback((soundId: string) => {
    updateAndPersistTaskReminder((prev) => ({
      ...prev,
      sound: {
        ...prev.sound,
        selectedSound: soundId,
      },
    }));
  }, [updateAndPersistTaskReminder]);

  const handleTaskReminderCustomSoundPathChange = useCallback((path: string) => {
    // 输入路径时先只更新本地草稿，避免用户每敲一个字符就把配置写回后端。
    setTaskReminderConfigState((prev) => ({
      ...prev,
      sound: {
        ...prev.sound,
        customSoundPath: path,
      },
    }));
  }, []);

  const handleTaskRecoveryPolicyFieldChange = useCallback((
    field: 'enabled' | 'recoverCompletedOnParseNoise' | 'retryTransientErrors' | 'maxAttempts' | 'initialDelayMs',
    value: boolean | number,
  ) => {
    updateAndPersistTaskReminder((prev) => ({
      ...prev,
      recoveryPolicy: {
        ...prev.recoveryPolicy,
        [field]: value,
      },
    }));
  }, [updateAndPersistTaskReminder]);

  const handleSaveCustomSoundPath = useCallback(() => {
    // 自定义路径走显式保存，和 browse / test 的行为拆开，
    // 这样用户可以先编辑路径，再决定是否真正写入配置。
    sendToJava(`set_task_reminder_config:${JSON.stringify(taskReminderConfig)}`);
  }, [taskReminderConfig]);

  // Test sound
  const handleTestSound = useCallback(() => {
    const payload = {
      soundId: taskReminderConfig.sound.selectedSound,
      path: taskReminderConfig.sound.customSoundPath,
    };
    sendToJava(`test_sound:${JSON.stringify(payload)}`);
  }, [taskReminderConfig.sound.customSoundPath, taskReminderConfig.sound.selectedSound]);

  const handleTestPopup = useCallback(() => {
    sendToJava('test_task_reminder_popup:');
  }, []);

  const handleTestBalloon = useCallback(() => {
    sendToJava('test_task_reminder_balloon:');
  }, []);

  // Browse sound file
  const handleBrowseSound = useCallback(() => {
    sendToJava('browse_sound_file:');
  }, []);

  // AI commit generation toggle change handler
  const handleCommitGenerationEnabledChange = useCallback((enabled: boolean) => {
    setCommitGenerationEnabled(enabled);
    const payload = { commitGenerationEnabled: enabled };
    sendToJava(`set_commit_generation_enabled:${JSON.stringify(payload)}`);
  }, []);

  // Status bar widget toggle change handler
  const handleStatusBarWidgetEnabledChange = useCallback((enabled: boolean) => {
    setStatusBarWidgetEnabled(enabled);
    const payload = { statusBarWidgetEnabled: enabled };
    sendToJava(`set_status_bar_widget_enabled:${JSON.stringify(payload)}`);
  }, []);

  // Commit AI prompt save handler
  const handleSaveCommitPrompt = useCallback(() => {
    setSavingCommitPrompt(true);
    const payload = { prompt: commitPrompt };
    sendToJava(`set_commit_prompt:${JSON.stringify(payload)}`);
  }, [commitPrompt]);

  return {
    nodePath,
    setNodePath,
    nodeVersion,
    setNodeVersion,
    minNodeVersion,
    setMinNodeVersion,
    savingNodePath,
    setSavingNodePath,
    workingDirectory,
    setWorkingDirectory,
    savingWorkingDirectory,
    setSavingWorkingDirectory,
    editorFontConfig,
    setEditorFontConfig,
    localStreamingEnabled,
    setLocalStreamingEnabled,
    streamingEnabled,
    codexSandboxMode,
    setCodexSandboxMode,
    localSendShortcut,
    setLocalSendShortcut,
    sendShortcut,
    localAutoOpenFileEnabled,
    setLocalAutoOpenFileEnabled,
    autoOpenFileEnabled,
    commitPrompt,
    setCommitPrompt,
    savingCommitPrompt,
    setSavingCommitPrompt,
    taskReminderConfig,
    setTaskReminderConfig,
    diffExpandedByDefault,
    setDiffExpandedByDefault,
    historyCompletionEnabled,
    setHistoryCompletionEnabled,
    handleSaveNodePath,
    handleSaveWorkingDirectory,
    handleStreamingEnabledChange,
    handleCodexSandboxModeChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
    handleTaskReminderEnabledChange,
    handleTaskReminderStateToggle,
    handleTaskReminderOnlyWhenIdeUnfocusedChange,
    handleTaskReminderSelectedSoundChange,
    handleTaskReminderCustomSoundPathChange,
    handleTaskRecoveryPolicyFieldChange,
    handleSaveCustomSoundPath,
    handleTestSound,
    handleTestPopup,
    handleTestBalloon,
    handleBrowseSound,
    handleSaveCommitPrompt,
    commitGenerationEnabled,
    setCommitGenerationEnabled,
    handleCommitGenerationEnabledChange,
    statusBarWidgetEnabled,
    setStatusBarWidgetEnabled,
    handleStatusBarWidgetEnabledChange,
  };
}
