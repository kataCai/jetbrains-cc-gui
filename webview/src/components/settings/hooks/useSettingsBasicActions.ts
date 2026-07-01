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
import {
  DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
  clampPermissionDialogTimeoutSeconds,
} from '../../../utils/permissionDialogTimeout';

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
  permissionDialogTimeoutSecondsProp?: number;
  onPermissionDialogTimeoutChangeProp?: (seconds: number) => void;
}

export interface UseSettingsBasicActionsReturn {
  nodePath: string;
  nodeVersion: string | null;
  minNodeVersion: number;
  savingNodePath: boolean;
  claudeCliPath: string;
  savingClaudeCliPath: boolean;
  workingDirectory: string;
  savingWorkingDirectory: boolean;
  codexHistoryImageCacheDir: string;
  codexHistoryImageCacheResolvedDir: string;
  codexHistoryImageCacheRetentionDays: number;
  codexHistoryImageCacheMaxSizeMb: number;
  savingCodexHistoryImageCache: boolean;
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
  projectCommitPrompt: string;
  savingProjectCommitPrompt: boolean;
  diffExpandedByDefault: boolean;
  historyCompletionEnabled: boolean;
  commitGenerationEnabled: boolean;
  aiTitleGenerationEnabled: boolean;
  statusBarWidgetEnabled: boolean;
  taskCompletionNotificationEnabled: boolean;
  commitAiConfig: CommitAiConfig;
  promptEnhancerConfig: PromptEnhancerConfig;

  handleSaveNodePath: () => void;
  handleSaveClaudeCliPath: () => void;
  handleSaveWorkingDirectory: () => void;
  handleSaveCodexHistoryImageCacheConfig: () => void;
  handleBrowseCodexHistoryImageCacheDir: () => void;
  handleResetCodexHistoryImageCacheDir: () => void;
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
  handleSaveProjectCommitPrompt: () => void;
  handleCommitGenerationEnabledChange: (enabled: boolean) => void;
  handleAiTitleGenerationEnabledChange: (enabled: boolean) => void;
  handleStatusBarWidgetEnabledChange: (enabled: boolean) => void;
  handleTaskCompletionNotificationEnabledChange: (enabled: boolean) => void;
  permissionDialogTimeoutSeconds: number;
  handlePermissionDialogTimeoutChange: (seconds: number) => void;
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
  setClaudeCliPath: (path: string) => void;
  setSavingClaudeCliPath: (saving: boolean) => void;
  setWorkingDirectory: (dir: string) => void;
  setSavingWorkingDirectory: (saving: boolean) => void;
  setCodexHistoryImageCacheDir: (dir: string) => void;
  setCodexHistoryImageCacheResolvedDir: (dir: string) => void;
  setCodexHistoryImageCacheRetentionDays: (days: number) => void;
  setCodexHistoryImageCacheMaxSizeMb: (size: number) => void;
  setSavingCodexHistoryImageCache: (saving: boolean) => void;
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
  /** @internal */ setLocalStreamingEnabled: (enabled: boolean) => void;
  /** @internal */ setCodexSandboxMode: (mode: 'workspace-write' | 'danger-full-access') => void;
  /** @internal */ setLocalSendShortcut: (shortcut: 'enter' | 'cmdEnter') => void;
  /** @internal */ setLocalAutoOpenFileEnabled: (enabled: boolean) => void;
  /** @internal */ setCommitPrompt: (prompt: string) => void;
  /** @internal */ setSavingCommitPrompt: (saving: boolean) => void;
  setTaskReminderConfig: (
    config: TaskReminderConfig | ((prev: TaskReminderConfig) => TaskReminderConfig)
  ) => void;
  setProjectCommitPrompt: (prompt: string) => void;
  /** @internal */ setSavingProjectCommitPrompt: (saving: boolean) => void;
  /** @internal */ setDiffExpandedByDefault: (expanded: boolean) => void;
  /** @internal */ setHistoryCompletionEnabled: (enabled: boolean) => void;
  /** @internal */ setCommitGenerationEnabled: (enabled: boolean) => void;
  /** @internal */ setAiTitleGenerationEnabled: (enabled: boolean) => void;
  /** @internal */ setStatusBarWidgetEnabled: (enabled: boolean) => void;
  /** @internal */ setTaskCompletionNotificationEnabled: (enabled: boolean) => void;
  /** @internal */ setCommitAiConfig: (config: CommitAiConfig) => void;
  /** @internal */ setPromptEnhancerConfig: (config: PromptEnhancerConfig) => void;
}

/**
 * 管理设置页基础行为配置。
 *
 * 并轨后该 Hook 同时承载本地主线的任务提醒配置、上游的权限弹窗超时配置，
 * 以及 Commit AI / Prompt Enhancer / UI 字体等基础设置状态。对外仍保持
 * 扁平返回结构，避免设置页组件和窗口回调调用方大范围改造。
 *
 * @param streamingEnabledProp 来自 App 的流式开关；存在时优先使用外部状态。
 * @param onStreamingEnabledChangeProp 流式开关变更回调；存在时由 App 负责持久化。
 * @param sendShortcutProp 来自 App 的发送快捷键；存在时优先使用外部状态。
 * @param onSendShortcutChangeProp 发送快捷键变更回调；存在时由 App 负责持久化。
 * @param autoOpenFileEnabledProp 来自 App 的自动打开文件开关；存在时优先使用外部状态。
 * @param onAutoOpenFileEnabledChangeProp 自动打开文件开关变更回调；存在时由 App 负责持久化。
 * @param permissionDialogTimeoutSecondsProp 来自 App 的权限弹窗超时时间；存在时优先使用外部状态。
 * @param onPermissionDialogTimeoutChangeProp 权限弹窗超时时间变更回调；存在时由 App 负责持久化。
 * @return 设置页基础行为状态、事件处理函数和供窗口回调使用的内部 setter。
 */
export function useSettingsBasicActions({
  streamingEnabledProp,
  onStreamingEnabledChangeProp,
  sendShortcutProp,
  onSendShortcutChangeProp,
  autoOpenFileEnabledProp,
  onAutoOpenFileEnabledChangeProp,
  permissionDialogTimeoutSecondsProp,
  onPermissionDialogTimeoutChangeProp,
}: UseSettingsBasicActionsProps): UseSettingsBasicActionsReturn {
  const [nodePath, setNodePath] = useState('');
  const [nodeVersion, setNodeVersion] = useState<string | null>(null);
  const [minNodeVersion, setMinNodeVersion] = useState(18);
  const [savingNodePath, setSavingNodePath] = useState(false);
  const [claudeCliPath, setClaudeCliPath] = useState('');
  const [savingClaudeCliPath, setSavingClaudeCliPath] = useState(false);

  const [workingDirectory, setWorkingDirectory] = useState('');
  const [savingWorkingDirectory, setSavingWorkingDirectory] = useState(false);
  const [codexHistoryImageCacheDir, setCodexHistoryImageCacheDir] = useState('');
  const [codexHistoryImageCacheResolvedDir, setCodexHistoryImageCacheResolvedDir] = useState('');
  const [codexHistoryImageCacheRetentionDays, setCodexHistoryImageCacheRetentionDays] = useState(30);
  const [codexHistoryImageCacheMaxSizeMb, setCodexHistoryImageCacheMaxSizeMb] = useState(1024);
  const [savingCodexHistoryImageCache, setSavingCodexHistoryImageCache] = useState(false);

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
  // Project-level commit AI prompt configuration
  const [projectCommitPrompt, setProjectCommitPrompt] = useState('');
  const [savingProjectCommitPrompt, setSavingProjectCommitPrompt] = useState(false);
  const [diffExpandedByDefault, setDiffExpandedByDefault] = useState<boolean>(() => {
    try {
      return localStorage.getItem('diffExpandedByDefault') === 'true';
    } catch {
      return false;
    }
  });
  const [commitAiConfig, setCommitAiConfig] = useState<CommitAiConfig>(
    DEFAULT_COMMIT_AI_CONFIG,
  );
  const [promptEnhancerConfig, setPromptEnhancerConfig] = useState<PromptEnhancerConfig>(
    DEFAULT_PROMPT_ENHANCER_CONFIG,
  );

  const [historyCompletionEnabled, setHistoryCompletionEnabled] = useState<boolean>(() => {
    const saved = localStorage.getItem('historyCompletionEnabled');
    return saved !== 'false';
  });
  const [commitGenerationEnabled, setCommitGenerationEnabled] = useState<boolean>(true);
  const [aiTitleGenerationEnabled, setAiTitleGenerationEnabled] = useState<boolean>(true);
  const [statusBarWidgetEnabled, setStatusBarWidgetEnabled] = useState<boolean>(true);
  const [taskCompletionNotificationEnabled, setTaskCompletionNotificationEnabled] = useState<boolean>(false);
  const permissionDialogTimeoutSeconds =
    permissionDialogTimeoutSecondsProp ?? DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS;

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
   * 鐎电懓顦婚弳鎾苟鐟欏嫯瀵栭崠鏍ф倵閻?taskReminder setter閵?   * 閺冪姾顔戦弫鐗堝祦閺夈儴鍤滈張顒€婀存禍銈勭鞍鏉╂ɑ妲哥粣妤€褰涢崶鐐剁殶閿涘矂鍏橀崗鍫濅粵娑撯偓濞?normalize閿?   * 娣囨繆鐦夌拋鍓х枂妞ょ數绮嶆禒鑸电埐闁插瞼婀呴崚鎵畱濮樻瓕绻欓弰顖氱暚閺佹番鈧礁鎮庡▔鏇樷偓浣稿讲濞撳弶鐓嬮惃鍕波閺嬪嫨鈧?   *
   * @param nextConfig 閺備即鍘ょ純顔藉灗閸╄桨绨弮褔鍘ょ純顔炬畱 updater
   */
  const setTaskReminderConfig: UseSettingsBasicActionsReturn['setTaskReminderConfig'] = useCallback((nextConfig) => {
    setTaskReminderConfigState((prev) => normalizeTaskReminderConfig(
      typeof nextConfig === 'function' ? nextConfig(prev) : nextConfig,
    ));
  }, []);

  /**
   * 閺堫剙婀存稊鎰潎閺囧瓨鏌?+ 瀵倹顒為崘娆忔礀 Java 閻?taskReminder 闁氨鏁ゅ銉﹀复閵?   * 鏉╂瑦鐗辩拋鍓х枂妞ら潧鍨忛幑銏犲讲娴犮儳鐝涢崡鍐插冀妫ｅ牞绱濋崥搴ｎ伂閸氬海鐢婚崶鐐村腹閺冭泛鍟€閸嬫碍娓剁紒鍫濐嚠姒绘劑鈧?   *
   * @param updater 閸╄桨绨弮褔鍘ょ純顔炬晸閹存劖鏌婇柊宥囩枂閻ㄥ嫬鍤遍弫?   */
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

  const handleSaveClaudeCliPath = useCallback(() => {
    setSavingClaudeCliPath(true);
    sendToJava(`set_claude_cli_path:${JSON.stringify({ path: (claudeCliPath || '').trim() })}`);
  }, [claudeCliPath]);

  const handleSaveWorkingDirectory = useCallback(() => {
    setSavingWorkingDirectory(true);
    sendToJava(`set_working_directory:${JSON.stringify({ customWorkingDir: (workingDirectory || '').trim() })}`);
  }, [workingDirectory]);

  /**
   * 保存 Codex 历史图片缓存配置。
   * 这里把目录、保留天数和容量上限作为同一组配置一次性提交，避免用户分别保存后出现中间态。
   */
  const handleSaveCodexHistoryImageCacheConfig = useCallback(() => {
    setSavingCodexHistoryImageCache(true);
    sendToJava(`set_codex_history_image_cache_config:${JSON.stringify({
      customDir: (codexHistoryImageCacheDir || '').trim(),
      retentionDays: Math.max(1, Math.trunc(codexHistoryImageCacheRetentionDays || 0)),
      maxSizeMb: Math.max(64, Math.trunc(codexHistoryImageCacheMaxSizeMb || 0)),
    })}`);
  }, [
    codexHistoryImageCacheDir,
    codexHistoryImageCacheMaxSizeMb,
    codexHistoryImageCacheRetentionDays,
  ]);

  /**
   * 打开目录选择器，仅回填输入框，不直接持久化。
   */
  const handleBrowseCodexHistoryImageCacheDir = useCallback(() => {
    sendToJava('browse_codex_history_image_cache_dir:');
  }, []);

  /**
   * 恢复到默认缓存目录。
   * 真正写配置仍通过统一的保存按钮完成，避免“浏览自动保存、恢复默认自动保存”的交互不一致。
   */
  const handleResetCodexHistoryImageCacheDir = useCallback(() => {
    setCodexHistoryImageCacheDir('');
  }, []);

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
    // 自定义提示音路径只更新 sound 分支，不改动其他提醒配置字段。
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

  // Permission dialog timeout change handler
  const handlePermissionDialogTimeoutChange = useCallback((seconds: number) => {
    const clamped = clampPermissionDialogTimeoutSeconds(seconds);
    // App.tsx owns the canonical state and provides the callback in production.
    onPermissionDialogTimeoutChangeProp?.(clamped);
    const payload = { permissionDialogTimeoutSeconds: clamped };
    sendToJava(`set_permission_dialog_timeout:${JSON.stringify(payload)}`);
  }, [onPermissionDialogTimeoutChangeProp]);

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

  // Project-level commit AI prompt save handler
  const handleSaveProjectCommitPrompt = useCallback(() => {
    setSavingProjectCommitPrompt(true);
    const payload = { prompt: projectCommitPrompt };
    sendToJava(`set_project_commit_prompt:${JSON.stringify(payload)}`);
  }, [projectCommitPrompt]);

  return {
    nodePath,
    setNodePath,
    nodeVersion,
    setNodeVersion,
    minNodeVersion,
    setMinNodeVersion,
    savingNodePath,
    setSavingNodePath,
    claudeCliPath,
    setClaudeCliPath,
    savingClaudeCliPath,
    setSavingClaudeCliPath,
    workingDirectory,
    setWorkingDirectory,
    savingWorkingDirectory,
    setSavingWorkingDirectory,
    codexHistoryImageCacheDir,
    setCodexHistoryImageCacheDir,
    codexHistoryImageCacheResolvedDir,
    setCodexHistoryImageCacheResolvedDir,
    codexHistoryImageCacheRetentionDays,
    setCodexHistoryImageCacheRetentionDays,
    codexHistoryImageCacheMaxSizeMb,
    setCodexHistoryImageCacheMaxSizeMb,
    savingCodexHistoryImageCache,
    setSavingCodexHistoryImageCache,
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
    handleSaveClaudeCliPath,
    handleSaveWorkingDirectory,
    handleSaveCodexHistoryImageCacheConfig,
    handleBrowseCodexHistoryImageCacheDir,
    handleResetCodexHistoryImageCacheDir,
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
    projectCommitPrompt,
    setProjectCommitPrompt,
    savingProjectCommitPrompt,
    setSavingProjectCommitPrompt,
    handleSaveProjectCommitPrompt,
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
    permissionDialogTimeoutSeconds,
    handlePermissionDialogTimeoutChange,
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
