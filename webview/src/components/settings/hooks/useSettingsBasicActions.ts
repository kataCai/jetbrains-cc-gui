// hooks/useSettingsBasicActions.ts
import { useState, useEffect, useCallback } from 'react';
export type { UiFontConfig } from '../../../types/uiFontConfig';
import type { UiFontConfig } from '../../../types/uiFontConfig';
import type { CommitAiConfig, CommitAiProvider } from '../../../types/aiFeatureConfig';
import { DEFAULT_COMMIT_AI_CONFIG } from '../../../types/aiFeatureConfig';
import type { PromptEnhancerConfig, PromptEnhancerProvider } from '../../../types/promptEnhancer';
import { DEFAULT_PROMPT_ENHANCER_CONFIG } from '../../../types/promptEnhancer';
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
  uiFontConfig: UiFontConfig | undefined;
  streamingEnabled: boolean;
  localStreamingEnabled: boolean;
  codexSandboxMode: 'workspace-write' | 'danger-full-access';
  sendShortcut: 'enter' | 'cmdEnter';
  localSendShortcut: 'enter' | 'cmdEnter';
  autoOpenFileEnabled: boolean;
  localAutoOpenFileEnabled: boolean;
  commitPrompt: string;
  savingCommitPrompt: boolean;
  taskReminderConfig: TaskReminderConfig;
  diffExpandedByDefault: boolean;
  historyCompletionEnabled: boolean;
  commitGenerationEnabled: boolean;
  aiTitleGenerationEnabled: boolean;
  statusBarWidgetEnabled: boolean;
  taskCompletionNotificationEnabled: boolean;
  commitAiConfig: CommitAiConfig;
  promptEnhancerConfig: PromptEnhancerConfig;

  handleSaveNodePath: () => void;
  handleSaveWorkingDirectory: () => void;
  handleUiFontSelectionChange: (selection: string) => void;
  handleSaveUiFontCustomPath: (path: string) => void;
  handleBrowseUiFontFile: () => void;
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
  handleAiTitleGenerationEnabledChange: (enabled: boolean) => void;
  handleStatusBarWidgetEnabledChange: (enabled: boolean) => void;
  handleTaskCompletionNotificationEnabledChange: (enabled: boolean) => void;
  handleCommitAiProviderChange: (provider: CommitAiProvider) => void;
  handleCommitAiModelChange: (model: string) => void;
  handleCommitAiResetToDefault: () => void;
  handlePromptEnhancerProviderChange: (provider: PromptEnhancerProvider) => void;
  handlePromptEnhancerModelChange: (model: string) => void;
  handlePromptEnhancerResetToDefault: () => void;

  setNodePath: (path: string) => void;
  setNodeVersion: (version: string | null) => void;
  setMinNodeVersion: (version: number) => void;
  setSavingNodePath: (saving: boolean) => void;
  setWorkingDirectory: (dir: string) => void;
  setSavingWorkingDirectory: (saving: boolean) => void;
  setEditorFontConfig: (
    config:
      | {
          fontFamily: string;
          fontSize: number;
          lineSpacing: number;
        }
      | undefined
  ) => void;
  setUiFontConfig: (config: UiFontConfig | undefined) => void;
  setLocalStreamingEnabled: (enabled: boolean) => void;
  setCodexSandboxMode: (mode: 'workspace-write' | 'danger-full-access') => void;
  setLocalSendShortcut: (shortcut: 'enter' | 'cmdEnter') => void;
  setLocalAutoOpenFileEnabled: (enabled: boolean) => void;
  setCommitPrompt: (prompt: string) => void;
  setSavingCommitPrompt: (saving: boolean) => void;
  setTaskReminderConfig: (
    config: TaskReminderConfig | ((prev: TaskReminderConfig) => TaskReminderConfig)
  ) => void;
  setDiffExpandedByDefault: (expanded: boolean) => void;
  setHistoryCompletionEnabled: (enabled: boolean) => void;
  setCommitGenerationEnabled: (enabled: boolean) => void;
  setAiTitleGenerationEnabled: (enabled: boolean) => void;
  setStatusBarWidgetEnabled: (enabled: boolean) => void;
  setTaskCompletionNotificationEnabled: (enabled: boolean) => void;
  setCommitAiConfig: (config: CommitAiConfig) => void;
  setPromptEnhancerConfig: (config: PromptEnhancerConfig) => void;
}

/**
 * 管理设置页基础行为配置。
 * 这里并轨后的关键原则是“保留主线 canonical taskReminder/remote collab 相关语义，
 * 同时接纳 upstream 的 UI 字体、Prompt Enhancer、Commit AI、AI title 和任务完成通知配置”。
 *
 * 对外仍暴露扁平的 state + handler + setter 结构，因为设置页窗口回调与页面组件都直接依赖这些字段。
 *
 * @param streamingEnabledProp 来自 App 的流式总开关
 * @param onStreamingEnabledChangeProp 来自 App 的流式切换回调
 * @param sendShortcutProp 来自 App 的发送快捷键
 * @param onSendShortcutChangeProp 来自 App 的发送快捷键回调
 * @param autoOpenFileEnabledProp 来自 App 的自动打开文件开关
 * @param onAutoOpenFileEnabledChangeProp 来自 App 的自动打开文件回调
 * @return 设置页基础行为状态与处理函数
 */
export function useSettingsBasicActions({
  streamingEnabledProp,
  onStreamingEnabledChangeProp,
  sendShortcutProp,
  onSendShortcutChangeProp,
  autoOpenFileEnabledProp,
  onAutoOpenFileEnabledChangeProp,
}: UseSettingsBasicActionsProps): UseSettingsBasicActionsReturn {
  const [nodePath, setNodePath] = useState('');
  const [nodeVersion, setNodeVersion] = useState<string | null>(null);
  const [minNodeVersion, setMinNodeVersion] = useState(18);
  const [savingNodePath, setSavingNodePath] = useState(false);

  const [workingDirectory, setWorkingDirectory] = useState('');
  const [savingWorkingDirectory, setSavingWorkingDirectory] = useState(false);

  const [editorFontConfig, setEditorFontConfig] = useState<
    | {
        fontFamily: string;
        fontSize: number;
        lineSpacing: number;
      }
    | undefined
  >();
  const [uiFontConfig, setUiFontConfig] = useState<UiFontConfig | undefined>();

  const [localStreamingEnabled, setLocalStreamingEnabled] = useState<boolean>(false);
  const streamingEnabled = streamingEnabledProp ?? localStreamingEnabled;

  const [codexSandboxMode, setCodexSandboxMode] = useState<'workspace-write' | 'danger-full-access'>(
    'danger-full-access',
  );

  const [localSendShortcut, setLocalSendShortcut] = useState<'enter' | 'cmdEnter'>('enter');
  const sendShortcut = sendShortcutProp ?? localSendShortcut;

  const [localAutoOpenFileEnabled, setLocalAutoOpenFileEnabled] = useState<boolean>(false);
  const autoOpenFileEnabled = autoOpenFileEnabledProp ?? localAutoOpenFileEnabled;

  const [commitPrompt, setCommitPrompt] = useState('');
  const [savingCommitPrompt, setSavingCommitPrompt] = useState(false);
  const [taskReminderConfig, setTaskReminderConfigState] = useState<TaskReminderConfig>(
    DEFAULT_TASK_REMINDER_CONFIG,
  );

  const [diffExpandedByDefault, setDiffExpandedByDefault] = useState<boolean>(() => {
    try {
      return localStorage.getItem('diffExpandedByDefault') === 'true';
    } catch {
      return false;
    }
  });

  const [historyCompletionEnabled, setHistoryCompletionEnabled] = useState<boolean>(() => {
    const saved = localStorage.getItem('historyCompletionEnabled');
    return saved !== 'false';
  });

  const [commitGenerationEnabled, setCommitGenerationEnabled] = useState<boolean>(true);
  const [aiTitleGenerationEnabled, setAiTitleGenerationEnabled] = useState<boolean>(true);
  const [statusBarWidgetEnabled, setStatusBarWidgetEnabled] = useState<boolean>(true);
  const [taskCompletionNotificationEnabled, setTaskCompletionNotificationEnabled] = useState<boolean>(false);

  const [commitAiConfig, setCommitAiConfig] = useState<CommitAiConfig>(
    DEFAULT_COMMIT_AI_CONFIG,
  );
  const [promptEnhancerConfig, setPromptEnhancerConfig] = useState<PromptEnhancerConfig>(
    DEFAULT_PROMPT_ENHANCER_CONFIG,
  );

  useEffect(() => {
    try {
      if (diffExpandedByDefault) {
        localStorage.setItem('diffExpandedByDefault', 'true');
      } else {
        localStorage.removeItem('diffExpandedByDefault');
      }
    } catch {
      // ignore storage errors
    }
  }, [diffExpandedByDefault]);

  /**
   * 对外暴露规范化后的 taskReminder setter。
   * 无论数据来自本地交互还是窗口回调，都先做一次 normalize，
   * 保证设置页组件树里看到的永远是完整、合法、可渲染的结构。
   *
   * @param nextConfig 新配置或基于旧配置的 updater
   */
  const setTaskReminderConfig: UseSettingsBasicActionsReturn['setTaskReminderConfig'] = useCallback((nextConfig) => {
    setTaskReminderConfigState((prev) => normalizeTaskReminderConfig(
      typeof nextConfig === 'function' ? nextConfig(prev) : nextConfig,
    ));
  }, []);

  /**
   * 本地乐观更新 + 异步写回 Java 的 taskReminder 通用桥接。
   * 这样设置页切换可以立即反馈，后端后续回推时再做最终对齐。
   *
   * @param updater 基于旧配置生成新配置的函数
   */
  const updateAndPersistTaskReminder = useCallback((updater: (prev: TaskReminderConfig) => TaskReminderConfig) => {
    setTaskReminderConfigState((prev) => {
      const next = normalizeTaskReminderConfig(updater(prev));
      sendToJava(`set_task_reminder_config:${JSON.stringify(next)}`);
      return next;
    });
  }, []);

  const handleSaveNodePath = useCallback(() => {
    setSavingNodePath(true);
    sendToJava(`set_node_path:${JSON.stringify({ path: (nodePath || '').trim() })}`);
  }, [nodePath]);

  const handleSaveWorkingDirectory = useCallback(() => {
    setSavingWorkingDirectory(true);
    sendToJava(`set_working_directory:${JSON.stringify({ customWorkingDir: (workingDirectory || '').trim() })}`);
  }, [workingDirectory]);

  const handleUiFontSelectionChange = useCallback((selection: string) => {
    if (selection === 'followEditor') {
      sendToJava(`set_ui_font_config:${JSON.stringify({ mode: 'followEditor' })}`);
      return;
    }

    if (selection === 'customFile' && uiFontConfig?.customFontPath) {
      sendToJava(`set_ui_font_config:${JSON.stringify({
        mode: 'customFile',
        customFontPath: uiFontConfig.customFontPath,
      })}`);
    }
  }, [uiFontConfig?.customFontPath]);

  const handleSaveUiFontCustomPath = useCallback((path: string) => {
    sendToJava(`set_ui_font_config:${JSON.stringify({
      mode: 'customFile',
      customFontPath: path,
    })}`);
  }, []);

  const handleBrowseUiFontFile = useCallback(() => {
    sendToJava('browse_ui_font_file:');
  }, []);

  const handleStreamingEnabledChange = useCallback((enabled: boolean) => {
    if (onStreamingEnabledChangeProp) {
      onStreamingEnabledChangeProp(enabled);
      return;
    }
    setLocalStreamingEnabled(enabled);
    sendToJava(`set_streaming_enabled:${JSON.stringify({ streamingEnabled: enabled })}`);
  }, [onStreamingEnabledChangeProp]);

  const handleCodexSandboxModeChange = useCallback((mode: 'workspace-write' | 'danger-full-access') => {
    setCodexSandboxMode(mode);
    sendToJava(`set_codex_sandbox_mode:${JSON.stringify({ sandboxMode: mode })}`);
  }, []);

  const handleSendShortcutChange = useCallback((shortcut: 'enter' | 'cmdEnter') => {
    if (onSendShortcutChangeProp) {
      onSendShortcutChangeProp(shortcut);
      return;
    }
    setLocalSendShortcut(shortcut);
    sendToJava(`set_send_shortcut:${JSON.stringify({ sendShortcut: shortcut })}`);
  }, [onSendShortcutChangeProp]);

  const handleAutoOpenFileEnabledChange = useCallback((enabled: boolean) => {
    if (onAutoOpenFileEnabledChangeProp) {
      onAutoOpenFileEnabledChangeProp(enabled);
      return;
    }
    setLocalAutoOpenFileEnabled(enabled);
    sendToJava(`set_auto_open_file_enabled:${JSON.stringify({ autoOpenFileEnabled: enabled })}`);
  }, [onAutoOpenFileEnabledChangeProp]);

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
    // 输入路径时先只更新本地草稿，避免每次敲字都立即写回后端。
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
    sendToJava(`set_task_reminder_config:${JSON.stringify(taskReminderConfig)}`);
  }, [taskReminderConfig]);

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

  const handleBrowseSound = useCallback(() => {
    sendToJava('browse_sound_file:');
  }, []);

  const handleCommitGenerationEnabledChange = useCallback((enabled: boolean) => {
    setCommitGenerationEnabled(enabled);
    sendToJava(`set_commit_generation_enabled:${JSON.stringify({ commitGenerationEnabled: enabled })}`);
  }, []);

  const handleAiTitleGenerationEnabledChange = useCallback((enabled: boolean) => {
    setAiTitleGenerationEnabled(enabled);
    sendToJava(`set_ai_title_generation_enabled:${JSON.stringify({ aiTitleGenerationEnabled: enabled })}`);
  }, []);

  const handleStatusBarWidgetEnabledChange = useCallback((enabled: boolean) => {
    setStatusBarWidgetEnabled(enabled);
    sendToJava(`set_status_bar_widget_enabled:${JSON.stringify({ statusBarWidgetEnabled: enabled })}`);
  }, []);

  const handleTaskCompletionNotificationEnabledChange = useCallback((enabled: boolean) => {
    setTaskCompletionNotificationEnabled(enabled);
    sendToJava(`set_task_completion_notification_enabled:${JSON.stringify({ taskCompletionNotificationEnabled: enabled })}`);
  }, []);

  const handleCommitAiProviderChange = useCallback((provider: CommitAiProvider) => {
    const providerAvailable = commitAiConfig.availability[provider];
    const nextConfig: CommitAiConfig = {
      ...commitAiConfig,
      provider,
      effectiveProvider: providerAvailable ? provider : null,
      resolutionSource: providerAvailable ? 'manual' : 'unavailable',
    };
    setCommitAiConfig(nextConfig);
    sendToJava(`set_commit_ai_config:${JSON.stringify({
      provider,
      models: nextConfig.models,
    })}`);
  }, [commitAiConfig]);

  const handleCommitAiModelChange = useCallback((model: string) => {
    const activeProvider = commitAiConfig.provider ?? commitAiConfig.effectiveProvider ?? 'codex';
    const nextConfig: CommitAiConfig = {
      ...commitAiConfig,
      models: {
        ...commitAiConfig.models,
        [activeProvider]: model,
      },
    };
    setCommitAiConfig(nextConfig);
    sendToJava(`set_commit_ai_config:${JSON.stringify({
      provider: commitAiConfig.provider,
      models: nextConfig.models,
    })}`);
  }, [commitAiConfig]);

  const handleCommitAiResetToDefault = useCallback(() => {
    const nextConfig: CommitAiConfig = {
      ...commitAiConfig,
      provider: null,
      effectiveProvider: commitAiConfig.availability.codex
        ? 'codex'
        : (commitAiConfig.availability.claude ? 'claude' : null),
      resolutionSource: commitAiConfig.availability.codex || commitAiConfig.availability.claude
        ? 'auto'
        : 'unavailable',
    };
    setCommitAiConfig(nextConfig);
    sendToJava(`set_commit_ai_config:${JSON.stringify({
      provider: null,
      models: nextConfig.models,
    })}`);
  }, [commitAiConfig]);

  const handlePromptEnhancerProviderChange = useCallback((provider: PromptEnhancerProvider) => {
    const providerAvailable = promptEnhancerConfig.availability[provider];
    const nextConfig: PromptEnhancerConfig = {
      ...promptEnhancerConfig,
      provider,
      effectiveProvider: providerAvailable ? provider : null,
      resolutionSource: providerAvailable ? 'manual' : 'unavailable',
    };
    setPromptEnhancerConfig(nextConfig);
    sendToJava(`set_prompt_enhancer_config:${JSON.stringify({
      provider,
      models: nextConfig.models,
    })}`);
  }, [promptEnhancerConfig]);

  const handlePromptEnhancerModelChange = useCallback((model: string) => {
    const activeProvider = promptEnhancerConfig.provider ?? promptEnhancerConfig.effectiveProvider ?? 'claude';
    const nextConfig: PromptEnhancerConfig = {
      ...promptEnhancerConfig,
      models: {
        ...promptEnhancerConfig.models,
        [activeProvider]: model,
      },
    };
    setPromptEnhancerConfig(nextConfig);
    sendToJava(`set_prompt_enhancer_config:${JSON.stringify({
      provider: promptEnhancerConfig.provider,
      models: nextConfig.models,
    })}`);
  }, [promptEnhancerConfig]);

  const handlePromptEnhancerResetToDefault = useCallback(() => {
    const nextConfig: PromptEnhancerConfig = {
      ...promptEnhancerConfig,
      provider: null,
      effectiveProvider: promptEnhancerConfig.availability.codex
        ? 'codex'
        : (promptEnhancerConfig.availability.claude ? 'claude' : null),
      resolutionSource: promptEnhancerConfig.availability.codex || promptEnhancerConfig.availability.claude
        ? 'auto'
        : 'unavailable',
    };
    setPromptEnhancerConfig(nextConfig);
    sendToJava(`set_prompt_enhancer_config:${JSON.stringify({
      provider: null,
      models: nextConfig.models,
    })}`);
  }, [promptEnhancerConfig]);

  const handleSaveCommitPrompt = useCallback(() => {
    setSavingCommitPrompt(true);
    sendToJava(`set_commit_prompt:${JSON.stringify({ prompt: commitPrompt })}`);
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
    uiFontConfig,
    setUiFontConfig,
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
    handleUiFontSelectionChange,
    handleSaveUiFontCustomPath,
    handleBrowseUiFontFile,
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
    aiTitleGenerationEnabled,
    setAiTitleGenerationEnabled,
    handleAiTitleGenerationEnabledChange,
    statusBarWidgetEnabled,
    setStatusBarWidgetEnabled,
    handleStatusBarWidgetEnabledChange,
    taskCompletionNotificationEnabled,
    setTaskCompletionNotificationEnabled,
    handleTaskCompletionNotificationEnabledChange,
    commitAiConfig,
    setCommitAiConfig,
    handleCommitAiProviderChange,
    handleCommitAiModelChange,
    handleCommitAiResetToDefault,
    promptEnhancerConfig,
    setPromptEnhancerConfig,
    handlePromptEnhancerProviderChange,
    handlePromptEnhancerModelChange,
    handlePromptEnhancerResetToDefault,
  };
}
