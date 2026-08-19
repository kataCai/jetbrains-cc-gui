// hooks/useSettingsWindowCallbacks.ts
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
  getCodexRuntimeSourceTranslationKey,
  resolveCodexRuntimeSource,
  type ProviderConfig,
  type CodexProviderConfig,
  type CodexProviderDraftModelsFetchResult,
  type CodexProviderTestResult,
} from '../../../types/provider';
import type { AgentConfig } from '../../../types/agent';
import type { ImportPreviewResult } from '../../../types/import';
import type { PromptConfig } from '../../../types/prompt';
import type { CommitAiConfig } from '../../../types/aiFeatureConfig';
import type { PromptEnhancerConfig } from '../../../types/promptEnhancer';
import type { TaskReminderConfig } from '../../../types/taskReminder';
import {
  mergeLegacySoundConfig,
  normalizeTaskReminderConfig,
} from '../../../types/taskReminder';
import type { UiFontConfig } from './useSettingsBasicActions';
import type {
  RemoteCollabConfig,
  RemoteCollabDebugSnapshot,
  RemoteCollabProviderOperationResult,
} from './useRemoteCollabSettings';
import type { AlertType } from '../../AlertDialog';
import type { ToastMessage } from '../../Toast';
import {
  subscribeActiveCodexProvider,
  subscribeActiveProvider,
  subscribeCodexModelCatalog,
  subscribeCodexProviderList,
  subscribeProviderList,
} from '../../../utils/runtimeProviderCapabilities';
import { updateFrontendDebugRuntimeConfig } from '../../../utils/debug';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
};

/**
 * 设置页成功提示支持的结构化 i18n payload。
 * 该协议由后端以 JSON 字符串形式通过 `window.showSuccess` 回传：
 * 1. `mode=i18n` 时前端走当前语言插值；
 * 2. 可选 `suffixKey` 用于附加条件句，避免后端继续拼自然语言；
 * 3. 解析失败时保持旧的纯字符串展示兼容。
 */
interface SettingsSuccessI18nPayload {
  mode: 'i18n';
  key: string;
  params?: Record<string, string | number>;
  suffixKey?: string;
  suffixParams?: Record<string, string | number>;
}

/**
 * 解析 `window.showSuccess` 入参，兼容纯字符串与结构化 i18n payload。
 * 关键逻辑：
 * 1. 非 JSON / 非 `mode=i18n` 时直接回退原文；
 * 2. 命中结构化协议后，用当前 `t(...)` 翻译主句；
 * 3. 若存在 `suffixKey`，再拼接后缀句翻译结果。
 *
 * @param message 后端回传的成功提示字符串
 * @param translate 当前设置页语言下的翻译函数
 * @return 可直接展示给成功弹窗的最终文案
 */
function resolveShowSuccessMessage(
  message: string,
  translate: (key: string, options?: Record<string, string | number>) => string,
): string {
  const trimmed = (message ?? '').trim();
  if (!trimmed.startsWith('{')) {
    return message;
  }

  try {
    const payload = JSON.parse(trimmed) as Partial<SettingsSuccessI18nPayload>;
    if (payload.mode !== 'i18n' || typeof payload.key !== 'string' || !payload.key.trim()) {
      return message;
    }

    const mainMessage = translate(payload.key, payload.params ?? {});
    if (typeof payload.suffixKey === 'string' && payload.suffixKey.trim()) {
      return `${mainMessage}${translate(payload.suffixKey, payload.suffixParams ?? {})}`;
    }
    return mainMessage;
  } catch {
    // 兼容历史纯字符串成功提示，解析失败时直接展示原文。
    return message;
  }
}

export interface SettingsWindowCallbacksDeps {
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
  setCommitPrompt: (prompt: string) => void;
  setSavingCommitPrompt: (saving: boolean) => void;
  setProjectCommitPrompt?: (prompt: string) => void;
  setSavingProjectCommitPrompt?: (saving: boolean) => void;
  setCommitAiConfig: (config: CommitAiConfig) => void;
  setPromptEnhancerConfig: (config: PromptEnhancerConfig) => void;
  setEditorFontConfig: (config: { fontFamily: string; fontSize: number; lineSpacing: number } | undefined) => void;
  setUiFontConfig: (config: UiFontConfig | undefined) => void;
  setIdeTheme: (theme: 'light' | 'dark' | null) => void;
  setLocalStreamingEnabled: (enabled: boolean) => void;
  setCodexSandboxMode?: (mode: 'workspace-write' | 'danger-full-access') => void;
  setLocalSendShortcut: (shortcut: 'enter' | 'cmdEnter') => void;
  setFrontendDebugPanelEnabled?: (enabled: boolean) => void;
  setFrontendDiagnosticArchiveEnabled?: (enabled: boolean) => void;
  setLoading: (loading: boolean) => void;
  setCodexLoading: (loading: boolean) => void;
  setCodexConfigLoading: (loading: boolean) => void;
  setCodexModelCatalogLoading: (loading: boolean) => void;
  setSyncingCodexProviderId: (providerId: string) => void;
  setSyncingCodexProviderDraftId: (providerId: string) => void;
  setTestingCodexProviderId: (providerId: string) => void;
  setCommitGenerationEnabled?: (enabled: boolean) => void;
  setAiTitleGenerationEnabled?: (enabled: boolean) => void;
  setStatusBarWidgetEnabled?: (enabled: boolean) => void;
  setTaskCompletionNotificationEnabled?: (enabled: boolean) => void;
  setTaskReminderConfig?: (
    config: TaskReminderConfig | ((prev: TaskReminderConfig) => TaskReminderConfig)
  ) => void;
  setRightClickOpenDevToolsEnabled?: (enabled: boolean) => void;
  setRemoteCollabConfig?: (
    config: RemoteCollabConfig | ((prev: RemoteCollabConfig) => RemoteCollabConfig)
  ) => void;
  setRemoteCollabDebugSnapshot?: (snapshot: RemoteCollabDebugSnapshot) => void;
  setRemoteCollabProviderOperationResult?: (result: RemoteCollabProviderOperationResult) => void;

  updateProviders: (providers: ProviderConfig[]) => void;
  updateActiveProvider: (provider: ProviderConfig) => void;
  loadProviders: () => void;
  loadCodexProviders: () => void;
  loadCodexModelCatalog: () => void;
  loadAgents: () => void;
  updateAgents: (agents: AgentConfig[]) => void;
  handleAgentOperationResult: (result: { success: boolean; operation?: string; error?: string }) => void;
  handleAgentImportPreviewResult: (previewData: ImportPreviewResult<AgentConfig>) => void;
  handleAgentImportResult: (
    result: { success: boolean; imported: number; updated: number; skipped: number; error?: string }
  ) => void;
  updateCodexProviders: (providers: CodexProviderConfig[]) => void;
  updateCodexProviderDraftModels: (result: CodexProviderDraftModelsFetchResult) => void;
  updateActiveCodexProvider: (provider: CodexProviderConfig) => void;
  updateCurrentCodexConfig: (config: unknown) => void;
  updateCodexModelCatalog: (catalog: import('../../../types/provider').CodexModelCatalogItem[]) => void;
  cleanupAgentsTimeout: () => void;

  loadPrompts?: () => void;
  updatePrompts?: (prompts: PromptConfig[]) => void;
  handlePromptOperationResult?: (result: unknown) => void;
  handlePromptImportPreviewResult?: (previewData: unknown) => void;
  handlePromptImportResult?: (result: unknown) => void;
  cleanupPromptsTimeout?: () => void;

  showAlert: (type: AlertType, title: string, message: string) => void;
  addToast: (message: string, type?: ToastMessage['type']) => void;
  onStreamingEnabledChangeProp?: (enabled: boolean) => void;
  onSendShortcutChangeProp?: (shortcut: 'enter' | 'cmdEnter') => void;
}

/**
 * 注册设置页的 Java bridge 回调。
 * 并轨阶段这里必须同时保留两类协议：
 * 1. upstream 的 runtime provider registry、UI 字体、Commit AI、Prompt Enhancer、AI title 等新能力；
 * 2. 当前主线的 canonical taskReminder、remoteCollab 与 legacy sound 兼容桥接。
 *
 * @param deps 设置页各状态 setter 与业务回调
 */
export function useSettingsWindowCallbacks(deps: SettingsWindowCallbacksDeps) {
  const { t } = useTranslation();
  const depsRef = useRef(deps);
  const tRef = useRef(t);
  depsRef.current = deps;
  tRef.current = t;

  useEffect(() => {
    const d = () => depsRef.current;
    const translate = (key: string, options?: Record<string, string | number>) => tRef.current(key, options);

    const unsubscribeProviders = subscribeProviderList((jsonStr: string) => {
      try {
        const providersList: ProviderConfig[] = JSON.parse(jsonStr);
        d().updateProviders(providersList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse providers:', error);
        d().setLoading(false);
      }
    });

    const unsubscribeActiveProvider = subscribeActiveProvider((jsonStr: string) => {
      try {
        const activeProvider: ProviderConfig = JSON.parse(jsonStr);
        if (activeProvider) {
          d().updateActiveProvider(activeProvider);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse active provider:', error);
      }
    });

    const unsubscribeCodexProviders = subscribeCodexProviderList((jsonStr: string) => {
      try {
        const providersList: CodexProviderConfig[] = JSON.parse(jsonStr);
        d().updateCodexProviders(providersList);
        d().setSyncingCodexProviderId('');
        d().loadCodexModelCatalog();
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex providers:', error);
        d().setSyncingCodexProviderId('');
        d().setCodexLoading(false);
      }
    });

    window.onCodexProviderDraftModelsFetched = (jsonStr: string) => {
      try {
        const result = JSON.parse(jsonStr) as CodexProviderDraftModelsFetchResult;
        d().updateCodexProviderDraftModels(result);
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex draft model fetch result:', error);
        d().setSyncingCodexProviderDraftId('');
      }
    };

    const unsubscribeActiveCodexProvider = subscribeActiveCodexProvider((jsonStr: string) => {
      try {
        const activeProvider: CodexProviderConfig = JSON.parse(jsonStr);
        if (activeProvider) {
          d().updateActiveCodexProvider(activeProvider);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse active Codex provider:', error);
      }
    });

    const unsubscribeCodexModelCatalog = subscribeCodexModelCatalog((jsonStr: string) => {
      try {
        const catalog = JSON.parse(jsonStr);
        d().updateCodexModelCatalog(catalog);
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex model catalog:', error);
        d().setCodexModelCatalogLoading(false);
      }
    });

    window.showError = (message: string) => {
      d().showAlert('error', translate('toast.operationFailed'), message);
      d().setSyncingCodexProviderId('');
      d().setSyncingCodexProviderDraftId('');
      d().setLoading(false);
      // 删除/同步统一目录失败时也必须退出 loading，避免 Models 面板卡在“加载中”。
      d().setCodexModelCatalogLoading(false);
      d().setSavingNodePath(false);
      d().setSavingClaudeCliPath(false);
      d().setSavingWorkingDirectory(false);
      d().setSavingCodexHistoryImageCache(false);
      d().setSavingCommitPrompt(false);
    };

    window.showSwitchSuccess = (message: string) => {
      d().showAlert('success', translate('toast.switchSuccess'), message);
    };

    window.showTestResult = (payloadOrSuccess: string | boolean, legacyMessage?: string) => {
      const payload = normalizeCodexProviderTestResultPayload(payloadOrSuccess, legacyMessage);
      d().setTestingCodexProviderId('');
      const runtimeSource = resolveCodexRuntimeSource(payload);
      d().showAlert(
        payload.success ? 'success' : 'error',
        payload.success ? translate('toast.testResultPassed') : translate('toast.testResultFailed'),
        formatCodexProviderTestResultMessage(payload, translate, runtimeSource)
      );
    };

    window.updateNodePath = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setNodePath(data.path || '');
        d().setNodeVersion(data.version || null);
        if (data.minVersion) {
          d().setMinNodeVersion(data.minVersion);
        }
      } catch (e) {
        console.warn('[SettingsView] Failed to parse updateNodePath JSON, fallback to legacy format:', e);
        d().setNodePath(jsonStr || '');
      }
      d().setSavingNodePath(false);
      window.dispatchEvent(new CustomEvent('nodePathReady'));
    };

    window.updateClaudeCliPath = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setClaudeCliPath(data.path || '');
      } catch (e) {
        console.warn('[SettingsView] Failed to parse updateClaudeCliPath JSON, fallback to legacy format:', e);
        d().setClaudeCliPath(jsonStr || '');
      }
      d().setSavingClaudeCliPath(false);
    };

    window.updateWorkingDirectory = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setWorkingDirectory(data.customWorkingDir || '');
      } catch (error) {
        console.error('[SettingsView] Failed to parse working directory:', error);
      } finally {
        d().setSavingWorkingDirectory(false);
      }
    };

    window.updateCodexHistoryImageCacheConfig = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setCodexHistoryImageCacheDir(data.customDir || '');
        d().setCodexHistoryImageCacheResolvedDir(data.resolvedDir || '');
        d().setCodexHistoryImageCacheRetentionDays(Number(data.retentionDays) || 30);
        d().setCodexHistoryImageCacheMaxSizeMb(Number(data.maxSizeMb) || 1024);
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex history image cache config:', error);
      } finally {
        d().setSavingCodexHistoryImageCache(false);
      }
    };

    window.onCodexHistoryImageCacheDirBrowsed = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setCodexHistoryImageCacheDir(data.path || '');
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex history image cache directory browse result:', error);
      }
    };

    window.showSuccess = (message: string) => {
      // 兼容旧纯字符串与新结构化 i18n payload，确保 provider 模型同步提示可跟随设置页语言切换。
      const resolvedMessage = resolveShowSuccessMessage(message, translate);
      d().showAlert('success', translate('toast.operationSuccess'), resolvedMessage);
      d().setSyncingCodexProviderId('');
      d().setSyncingCodexProviderDraftId('');
      d().setSavingNodePath(false);
      d().setSavingClaudeCliPath(false);
      d().setSavingWorkingDirectory(false);
      d().setSavingCodexHistoryImageCache(false);
    };

    window.showSuccessI18n = (i18nKey: string) => {
      d().addToast(translate(i18nKey), 'success');
    };

    window.onEditorFontConfigReceived = (jsonStr: string) => {
      try {
        d().setEditorFontConfig(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse editor font config:', error);
      }
    };

    window.onUiFontConfigReceived = (jsonStr: string) => {
      try {
        const config = JSON.parse(jsonStr);
        d().setUiFontConfig(config);
        window.applyUiFontConfig?.(config);
      } catch {
        // Silently ignore malformed UI font config from backend.
      }
    };

    const previousOnIdeThemeReceived = window.onIdeThemeReceived;
    window.onIdeThemeReceived = (jsonStr: string) => {
      try {
        const themeData = JSON.parse(jsonStr);
        const theme = themeData.isDark ? 'dark' : 'light';
        d().setIdeTheme(theme);
        previousOnIdeThemeReceived?.(jsonStr);
      } catch (error) {
        console.error('[SettingsView] Failed to parse IDE theme:', error);
      }
    };

    const previousUpdateStreamingEnabled = window.updateStreamingEnabled;
    if (!d().onStreamingEnabledChangeProp) {
      window.updateStreamingEnabled = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr);
          d().setLocalStreamingEnabled(data.streamingEnabled ?? true);
        } catch (error) {
          console.error('[SettingsView] Failed to parse streaming config:', error);
        }
      };
    }

    window.updateCodexSandboxMode = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        const mode = data?.sandboxMode;
        if (mode === 'workspace-write' || mode === 'danger-full-access') {
          d().setCodexSandboxMode?.(mode);
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex sandbox mode config:', error);
      }
    };

    const previousUpdateSendShortcut = window.updateSendShortcut;
    if (!d().onSendShortcutChangeProp) {
      window.updateSendShortcut = (jsonStr: string) => {
        try {
          const data = JSON.parse(jsonStr);
          d().setLocalSendShortcut(data.sendShortcut ?? 'enter');
        } catch (error) {
          console.error('[SettingsView] Failed to parse send shortcut config:', error);
        }
      };
    }

    window.updateCommitPrompt = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setCommitPrompt(data.commitPrompt || '');
        if (data.saved) {
          d().addToast(translate('toast.saveSuccess'), 'success');
        }
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit prompt:', error);
        d().addToast(translate('toast.saveFailed'), 'error');
      } finally {
        d().setSavingCommitPrompt(false);
      }
    };

    window.updateCommitAiConfig = (jsonStr: string) => {
      try {
        d().setCommitAiConfig(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit AI config:', error);
      }
    };

    window.updatePromptEnhancerConfig = (jsonStr: string) => {
      try {
        d().setPromptEnhancerConfig(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt enhancer config:', error);
      }
    };

    /**
     * 回写“右键打开调试面板”开关。
     * 该开关同时影响设置页和聊天区右键菜单，因此必须在 React 注册后
     * 恢复到同一份状态槽位，避免不同页面出现不同默认值。
     */
    window.updateRightClickOpenDevToolsEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setRightClickOpenDevToolsEnabled?.(data.rightClickOpenDevToolsEnabled ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse right click devtools config:', error);
      }
    };

    window.updateFrontendDebugConfig = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        const panelEnabled = data.panelEnabled === true;
        const archiveEnabled = data.archiveEnabled === true;
        updateFrontendDebugRuntimeConfig({
          panelEnabled,
          archiveEnabled,
          panelConfigured: data.panelConfigured === true,
          archiveConfigured: data.archiveConfigured === true,
        });
        d().setFrontendDebugPanelEnabled?.(panelEnabled);
        d().setFrontendDiagnosticArchiveEnabled?.(archiveEnabled);
      } catch (error) {
        console.error('[SettingsView] Failed to parse frontend debug config:', error);
      }
    };

    window.updateCommitGenerationEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setCommitGenerationEnabled?.(data.commitGenerationEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse commit generation config:', error);
      }
    };

    window.updateAiTitleGenerationEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setAiTitleGenerationEnabled?.(data.aiTitleGenerationEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse AI title generation config:', error);
      }
    };

    window.updateStatusBarWidgetEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setStatusBarWidgetEnabled?.(data.statusBarWidgetEnabled ?? true);
      } catch (error) {
        console.error('[SettingsView] Failed to parse status bar widget config:', error);
      }
    };

    window.updateTaskCompletionNotificationEnabled = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        d().setTaskCompletionNotificationEnabled?.(data.taskCompletionNotificationEnabled ?? false);
      } catch (error) {
        console.error('[SettingsView] Failed to parse task completion notification config:', error);
      }
    };

    window.updateTaskReminderConfig = (jsonStr: string) => {
      try {
        d().setTaskReminderConfig?.(normalizeTaskReminderConfig(JSON.parse(jsonStr)));
      } catch (error) {
        console.error('[SettingsView] Failed to parse task reminder config:', error);
      }
    };

    window.updateSoundNotificationConfig = (jsonStr: string) => {
      try {
        const data = JSON.parse(jsonStr);
        // 当前主线以 canonical taskReminder 为准，这里只把 legacy sound 桥接进 sound 子树。
        d().setTaskReminderConfig?.((prev) => mergeLegacySoundConfig(prev, data));
      } catch (error) {
        console.error('[SettingsView] Failed to parse sound notification config:', error);
      }
    };

    window.updateRemoteCollabConfig = (jsonStr: string) => {
      try {
        d().setRemoteCollabConfig?.(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse remote collab config:', error);
      }
    };

    window.updateRemoteCollabDebugSnapshot = (jsonStr: string) => {
      try {
        d().setRemoteCollabDebugSnapshot?.(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse remote collab debug snapshot:', error);
      }
    };

    window.updateRemoteCollabProviderOperationResult = (jsonStr: string) => {
      try {
        d().setRemoteCollabProviderOperationResult?.(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse remote collab provider operation result:', error);
      }
    };

    const previousUpdateAgents = window.updateAgents;
    window.updateAgents = (jsonStr: string) => {
      try {
        const agentsList: AgentConfig[] = JSON.parse(jsonStr);
        d().updateAgents(agentsList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agents:', error);
      }
      previousUpdateAgents?.(jsonStr);
    };

    window.agentOperationResult = (jsonStr: string) => {
      try {
        d().handleAgentOperationResult(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent operation result:', error);
      }
    };

    window.agentImportPreviewResult = (jsonStr: string) => {
      try {
        const previewData = JSON.parse(jsonStr);
        if (!Array.isArray(previewData?.items) || typeof previewData?.summary !== 'object') {
          console.error('[SettingsView] Invalid agent import preview data structure');
          return;
        }
        d().handleAgentImportPreviewResult(previewData);
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent import preview result:', error);
      }
    };

    window.agentImportResult = (jsonStr: string) => {
      try {
        d().handleAgentImportResult(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse agent import result:', error);
      }
    };

    const previousUpdatePrompts = window.updatePrompts;
    window.updatePrompts = (jsonStr: string) => {
      try {
        const promptsList: PromptConfig[] = JSON.parse(jsonStr);
        d().updatePrompts?.(promptsList);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompts:', error);
      }
      previousUpdatePrompts?.(jsonStr);
    };

    window.promptOperationResult = (jsonStr: string) => {
      try {
        d().handlePromptOperationResult?.(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt operation result:', error);
      }
    };

    window.promptImportPreviewResult = (jsonStr: string) => {
      try {
        const previewData = JSON.parse(jsonStr);
        if (!Array.isArray(previewData?.items) || typeof previewData?.summary !== 'object') {
          console.error('[SettingsView] Invalid prompt import preview data structure');
          return;
        }
        d().handlePromptImportPreviewResult?.(previewData);
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt import preview result:', error);
      }
    };

    window.promptImportResult = (jsonStr: string) => {
      try {
        d().handlePromptImportResult?.(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse prompt import result:', error);
      }
    };

    window.updateCurrentCodexConfig = (jsonStr: string) => {
      try {
        d().updateCurrentCodexConfig(JSON.parse(jsonStr));
      } catch (error) {
        console.error('[SettingsView] Failed to parse Codex config:', error);
        d().setCodexConfigLoading(false);
      }
    };

    d().loadProviders();
    d().loadCodexProviders();
    d().loadCodexModelCatalog();
    d().loadAgents();
    d().loadPrompts?.();
    sendToJava('get_node_path:');
    sendToJava('get_claude_cli_path:');
    sendToJava('get_working_directory:');
    sendToJava('get_codex_history_image_cache_config:');
    sendToJava('get_editor_font_config:');
    sendToJava('get_ui_font_config:');
    sendToJava('get_streaming_enabled:');
    sendToJava('get_codex_sandbox_mode:');
    sendToJava('get_commit_prompt:');
    sendToJava('get_task_reminder_config:');
    sendToJava('get_remote_collab_config:');
    sendToJava('get_commit_ai_config:');
    sendToJava('get_prompt_enhancer_config:');
    sendToJava('get_sound_notification_config:');
    sendToJava('get_frontend_debug_config:');
    sendToJava('get_right_click_open_devtools_enabled:');
    sendToJava('get_commit_generation_enabled:');
    sendToJava('get_ai_title_generation_enabled:');
    sendToJava('get_status_bar_widget_enabled:');
    sendToJava('get_task_completion_notification_enabled:');
    sendToJava('get_permission_dialog_timeout:');
    sendToJava('get_current_codex_config:');

    return () => {
      d().cleanupAgentsTimeout();
      d().cleanupPromptsTimeout?.();
      unsubscribeProviders();
      unsubscribeActiveProvider();
      unsubscribeCodexProviders();
      unsubscribeActiveCodexProvider();
      unsubscribeCodexModelCatalog();

      window.showError = undefined;
      window.showSwitchSuccess = undefined;
      window.showTestResult = undefined;
      window.updateNodePath = undefined;
      window.updateClaudeCliPath = undefined;
      window.updateWorkingDirectory = undefined;
      window.updateCodexHistoryImageCacheConfig = undefined;
      window.onCodexHistoryImageCacheDirBrowsed = undefined;
      window.showSuccess = undefined;
      window.showSuccessI18n = undefined;
      window.onCodexProviderDraftModelsFetched = undefined;
      window.onEditorFontConfigReceived = undefined;
      window.onUiFontConfigReceived = undefined;
      window.onIdeThemeReceived = previousOnIdeThemeReceived;
      if (!d().onStreamingEnabledChangeProp) {
        window.updateStreamingEnabled = previousUpdateStreamingEnabled;
      }
      window.updateCodexSandboxMode = undefined;
      if (!d().onSendShortcutChangeProp) {
        window.updateSendShortcut = previousUpdateSendShortcut;
      }
      window.updateCommitPrompt = undefined;
      window.updateCommitAiConfig = undefined;
      window.updatePromptEnhancerConfig = undefined;
      window.updateFrontendDebugConfig = undefined;
      window.updateRightClickOpenDevToolsEnabled = undefined;
      window.updateTaskReminderConfig = undefined;
      window.updateSoundNotificationConfig = undefined;
      window.updateRemoteCollabConfig = undefined;
      window.updateRemoteCollabDebugSnapshot = undefined;
      window.updateRemoteCollabProviderOperationResult = undefined;
      window.updateCommitGenerationEnabled = undefined;
      window.updateAiTitleGenerationEnabled = undefined;
      window.updateStatusBarWidgetEnabled = undefined;
      window.updateTaskCompletionNotificationEnabled = undefined;
      window.updateAgents = previousUpdateAgents;
      window.agentOperationResult = undefined;
      window.agentImportPreviewResult = undefined;
      window.agentImportResult = undefined;
      window.updatePrompts = previousUpdatePrompts;
      window.promptOperationResult = undefined;
      window.promptImportPreviewResult = undefined;
      window.promptImportResult = undefined;
      window.updateCurrentCodexConfig = undefined;
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
}

/**
 * 兼容旧版 `(success, message)` 与新版结构化 JSON payload 两种 showTestResult 回调格式。
 * 这样可以在 Java 侧逐步升级的同时，保证旧版本返回值不会让设置页直接失效。
 *
 * @param payloadOrSuccess 结构化 JSON 字符串，或旧版 success 布尔值
 * @param legacyMessage 旧版第二参数消息
 * @returns 统一后的结构化测试结果
 */
function normalizeCodexProviderTestResultPayload(
  payloadOrSuccess: string | boolean,
  legacyMessage?: string,
): CodexProviderTestResult {
  if (typeof payloadOrSuccess === 'string') {
    try {
      return JSON.parse(payloadOrSuccess) as CodexProviderTestResult;
    } catch {
      return {
        success: false,
        providerId: '',
        requestMode: 'codex_sdk',
        model: '',
        resolvedBaseUrl: '',
        credentialSource: '',
        transport: 'codex_sdk',
        effectiveConfigSource: '',
        fallbackDetected: false,
        forcedModelProvider: '',
        localCodexModelProvider: '',
        localConfigConflictDetected: false,
        finalModelProvider: '',
        message: payloadOrSuccess,
      };
    }
  }

  return {
    success: payloadOrSuccess,
    providerId: '',
    requestMode: 'codex_sdk',
    model: '',
    resolvedBaseUrl: '',
    credentialSource: '',
    transport: 'codex_sdk',
    effectiveConfigSource: '',
    fallbackDetected: false,
    forcedModelProvider: '',
    localCodexModelProvider: '',
    localConfigConflictDetected: false,
    finalModelProvider: '',
    message: legacyMessage || '',
  };
}

const CODEX_NO_MODEL_CONFIGURED_ERROR = 'No Codex model configured';

/**
 * 将结构化 provider 测试结果格式化为设置页展示文案。
 * 文案明确展示真实命中的 provider/model/baseUrl/requestMode，并补充 testStage 与空模型下一步提示。
 * 底层 `No Codex model configured` 不会再作为首行主错误直接暴露。
 *
 * @param payload 结构化测试结果
 * @param t i18n 翻译函数
 * @param runtimeSource 已解析的运行时来源
 * @returns 供 AlertDialog 直接展示的多行消息
 */
function formatCodexProviderTestResultMessage(
  payload: CodexProviderTestResult,
  t: (key: string, options?: Record<string, string | number>) => string,
  runtimeSource = resolveCodexRuntimeSource(payload),
): string {
  const rawMessage = payload.message || '';
  const hidesRawNoModelError = rawMessage.includes(CODEX_NO_MODEL_CONFIGURED_ERROR);
  const lines = [
    hidesRawNoModelError
      ? t('settings.codexProvider.testResult.requiresModel')
      : rawMessage,
  ];
  if (payload.testStage) {
    const stageLabel = t(`settings.codexProvider.testStage.${payload.testStage}`, {
      defaultValue: payload.testStage,
    });
    lines.push(t('settings.codexProvider.testResult.testStage', {
      stage: stageLabel,
    }));
  }
  if (payload.success && payload.testStage === 'model_discovery') {
    lines.push(t('settings.codexProvider.testResult.modelDiscoverySuccess'));
  }
  if (payload.requiresModel && !hidesRawNoModelError) {
    lines.push(t('settings.codexProvider.testResult.requiresModel'));
  }
  const managedProviderConfirmed = payload.forcedModelProvider
    && payload.finalModelProvider
    && payload.forcedModelProvider === payload.finalModelProvider;
  lines.push(t('settings.codexProvider.runtimeSourceLabel', {
    source: t(`settings.codexProvider.runtimeSource.${getCodexRuntimeSourceTranslationKey(runtimeSource)}`),
  }));
  if (managedProviderConfirmed) {
    lines.push(t('settings.codexProvider.testResult.guiProviderConfirmed', {
      provider: payload.forcedModelProvider || 'unknown',
      defaultValue: 'GUI managed provider is active for this request: {{provider}}',
    }));
  }
  if (payload.providerId) {
    lines.push(`providerId=${payload.providerId}`);
  }
  if (payload.model) {
    lines.push(`model=${payload.model}`);
  }
  if (payload.requestMode) {
    lines.push(`requestMode=${payload.requestMode}`);
  }
  if (payload.transport) {
    lines.push(`transport=${payload.transport}`);
  }
  if (payload.resolvedBaseUrl) {
    lines.push(`baseUrl=${payload.resolvedBaseUrl}`);
  }
  if (payload.authMode) {
    lines.push(`authMode=${payload.authMode}`);
  }
  if (payload.credentialSource) {
    lines.push(`credentialSource=${payload.credentialSource}`);
  }
  if (payload.endpointSource) {
    lines.push(`endpointSource=${payload.endpointSource}`);
  }
  if (payload.effectiveConfigSource) {
    lines.push(`effectiveConfigSource=${payload.effectiveConfigSource}`);
  }
  if (payload.forcedModelProvider) {
    lines.push(`forcedModelProvider=${payload.forcedModelProvider}`);
  }
  if (payload.localCodexModelProvider) {
    lines.push(`localCodexModelProvider=${payload.localCodexModelProvider}`);
  }
  if (payload.finalModelProvider) {
    lines.push(`finalModelProvider=${payload.finalModelProvider}`);
  }
  if (payload.localConfigConflictDetected) {
    lines.push(t('settings.codexProvider.testResult.localConfigConflict', {
      provider: payload.localCodexModelProvider || 'unknown',
      defaultValue: 'Local Codex CLI default provider may still conflict: {{provider}}',
    }));
  }
  if (payload.fallbackDetected) {
    lines.push('warning=fallback_detected');
  }
  return lines.filter((line) => line && line.trim().length > 0).join('\n');
}
