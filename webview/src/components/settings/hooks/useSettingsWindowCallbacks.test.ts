import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSettingsWindowCallbacks, type SettingsWindowCallbacksDeps } from './useSettingsWindowCallbacks';
import type { CommitAiConfig } from '../../../types/aiFeatureConfig';
import type { PromptEnhancerConfig } from '../../../types/promptEnhancer';
import * as debugModule from '../../../utils/debug';

const translations: Record<string, string> = {
  'settings.codexProvider.runtimeSourceLabel': 'Runtime Source: {{source}}',
  'settings.codexProvider.runtimeSource.managedProvider': 'Managed Provider',
  'settings.codexProvider.runtimeSource.codexLocalConfig': 'Codex Local Config',
  'settings.codexProvider.runtimeSource.sdkDefault': 'SDK Default',
  'settings.codexProvider.runtimeSource.proxyFallback': 'Proxy Fallback',
};

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string | number>) => {
      const template = translations[key];
      if (!template) {
        return key;
      }
      if (!options) {
        return template;
      }
      return Object.entries(options).reduce(
        (result, [token, value]) => result.replace(`{{${token}}}`, String(value)),
        template
      );
    },
  }),
}));

describe('useSettingsWindowCallbacks merged callback registry', () => {
  const createDeps = (): SettingsWindowCallbacksDeps => ({
    setNodePath: vi.fn(),
    setNodeVersion: vi.fn(),
    setMinNodeVersion: vi.fn(),
    setSavingNodePath: vi.fn(),
    setClaudeCliPath: vi.fn(),
    setSavingClaudeCliPath: vi.fn(),
    setWorkingDirectory: vi.fn(),
    setSavingWorkingDirectory: vi.fn(),
    setCodexHistoryImageCacheDir: vi.fn(),
    setCodexHistoryImageCacheResolvedDir: vi.fn(),
    setCodexHistoryImageCacheRetentionDays: vi.fn(),
    setCodexHistoryImageCacheMaxSizeMb: vi.fn(),
    setSavingCodexHistoryImageCache: vi.fn(),
    setCommitPrompt: vi.fn(),
    setSavingCommitPrompt: vi.fn(),
    setCommitAiConfig: vi.fn(),
    setPromptEnhancerConfig: vi.fn(),
    setProjectCommitPrompt: vi.fn(),
    setSavingProjectCommitPrompt: vi.fn(),
    setEditorFontConfig: vi.fn(),
    setUiFontConfig: vi.fn(),
    setIdeTheme: vi.fn(),
    setLocalStreamingEnabled: vi.fn(),
    setCodexSandboxMode: vi.fn(),
    setLocalSendShortcut: vi.fn(),
    setFrontendDebugPanelEnabled: vi.fn(),
    setFrontendDiagnosticArchiveEnabled: vi.fn(),
    setLoading: vi.fn(),
    setCodexLoading: vi.fn(),
    setCodexConfigLoading: vi.fn(),
    setCodexModelCatalogLoading: vi.fn(),
    setTestingCodexProviderId: vi.fn(),
    setCommitGenerationEnabled: vi.fn(),
    setAiTitleGenerationEnabled: vi.fn(),
    setStatusBarWidgetEnabled: vi.fn(),
    setTaskCompletionNotificationEnabled: vi.fn(),
    setTaskReminderConfig: vi.fn(),
    setRemoteCollabConfig: vi.fn(),
    setRemoteCollabDebugSnapshot: vi.fn(),
    setRemoteCollabProviderOperationResult: vi.fn(),
    updateProviders: vi.fn(),
    updateActiveProvider: vi.fn(),
    loadProviders: vi.fn(),
    loadCodexProviders: vi.fn(),
    loadCodexModelCatalog: vi.fn(),
    loadAgents: vi.fn(),
    updateAgents: vi.fn(),
    handleAgentOperationResult: vi.fn(),
    handleAgentImportPreviewResult: vi.fn(),
    handleAgentImportResult: vi.fn(),
    updateCodexProviders: vi.fn(),
    updateActiveCodexProvider: vi.fn(),
    updateCurrentCodexConfig: vi.fn(),
    updateCodexModelCatalog: vi.fn(),
    cleanupAgentsTimeout: vi.fn(),
    showAlert: vi.fn(),
    addToast: vi.fn(),
  });

  beforeEach(() => {
    window.sendToJava = vi.fn();
    window.applyUiFontConfig = vi.fn();
  });

  /**
   * 验证设置页挂载时会同时请求主线和 upstream 合并后的全部关键配置。
   * 断言意图：taskReminder / remoteCollab / commitAI / promptEnhancer / uiFont 等入口都不能漏。
   */
  it('requests the merged settings payload set on mount', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    expect(deps.loadProviders).toHaveBeenCalledTimes(1);
    expect(deps.loadCodexProviders).toHaveBeenCalledTimes(1);
    expect(deps.loadAgents).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).not.toHaveBeenCalledWith('get_current_claude_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_node_path:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_claude_cli_path:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_working_directory:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_codex_history_image_cache_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_editor_font_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_ui_font_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_streaming_enabled:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_codex_sandbox_mode:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_commit_prompt:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_task_reminder_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_remote_collab_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_commit_ai_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_prompt_enhancer_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_sound_notification_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_frontend_debug_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_right_click_open_devtools_enabled:');
  });

  /**
   * 验证 Codex provider 列表回调会主动刷新统一模型目录。
   * 断言意图：provider 增删改、授权状态变化或排序后，Models 面板不需要用户手动二次刷新。
   */
  it('reloads Codex model catalog after provider list callback updates', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    window.updateCodexProviders?.(JSON.stringify([{ id: 'provider-a', name: 'Provider A' }]));

    expect(deps.updateCodexProviders).toHaveBeenCalledWith([{ id: 'provider-a', name: 'Provider A' }]);
    expect(deps.loadCodexModelCatalog).toHaveBeenCalled();
  });

  /**
   * 验证 canonical taskReminder 回调会被规范化后写入状态。
   * 断言意图：兼容旧配置缺字段场景，并保持主线 recoveryPolicy/system 默认值。
   */
  it('normalizes canonical task reminder config from backend callback', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    const config = {
      popup: { enabled: true, states: ['waiting_confirm'], onlyWhenIdeUnfocused: false },
      balloon: { enabled: true, states: ['completed'], onlyWhenIdeUnfocused: true },
      sound: {
        enabled: true,
        states: ['completed'],
        onlyWhenIdeUnfocused: true,
        selectedSound: 'default',
        customSoundPath: '',
      },
    };

    window.updateTaskReminderConfig?.(JSON.stringify(config));
    expect(deps.setTaskReminderConfig).toHaveBeenCalledWith({
      ...config,
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
    });
  });

  /**
   * 验证 legacy sound callback 只桥接到 taskReminder.sound，不破坏其他子树。
   * 断言意图：保留并轨后的 canonical 结构，同时继续兼容旧后端入口。
   */
  it('merges legacy sound callback into taskReminderConfig sound subtree', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    window.updateSoundNotificationConfig?.(JSON.stringify({
      enabled: false,
      states: ['final_error'],
      onlyWhenIdeUnfocused: false,
      selectedSound: 'custom',
      customSoundPath: '/tmp/a.wav',
    }));

    const setCallArg = (deps.setTaskReminderConfig as ReturnType<typeof vi.fn>).mock.calls.at(-1)?.[0];
    expect(typeof setCallArg).toBe('function');

    const prevConfig = {
      popup: { enabled: true, states: ['waiting_confirm'], onlyWhenIdeUnfocused: false },
      balloon: { enabled: true, states: ['completed'], onlyWhenIdeUnfocused: true },
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
    const nextConfig = setCallArg(prevConfig);

    expect(nextConfig.popup).toEqual(prevConfig.popup);
    expect(nextConfig.balloon).toEqual(prevConfig.balloon);
    expect(nextConfig.system).toEqual(prevConfig.system);
    expect(nextConfig.sound.enabled).toBe(false);
    expect(nextConfig.sound.states).toEqual(['final_error']);
    expect(nextConfig.sound.selectedSound).toBe('custom');
    expect(nextConfig.sound.customSoundPath).toBe('/tmp/a.wav');
  });

  /**
   * 验证 remote collaboration 回调仍会写入对应状态。
   * 断言意图：确保当前主线新增的 remote 协作能力没有在并轨时丢失。
   */
  it('writes remote collaboration config and debug callbacks', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    const config = {
      enabled: true,
      telegram: {
        botToken: 'bot-token',
        connectionStatus: 'connected',
        currentInstanceReceivesUpdates: true,
      },
    };
    const snapshot = {
      recentRequests: [{ requestId: 'req-1' }],
      recentErrors: [{ providerId: 'telegram', message: 'timeout' }],
      recentActions: [{ providerId: 'telegram', actionKey: 'test_connection' }],
    };
    const operationResult = {
      operationType: 'test',
      providerId: 'telegram',
      actionKey: 'send_test_message',
      result: { message: 'ok' },
    };

    window.updateRemoteCollabConfig?.(JSON.stringify(config));
    window.updateRemoteCollabDebugSnapshot?.(JSON.stringify(snapshot));
    window.updateRemoteCollabProviderOperationResult?.(JSON.stringify(operationResult));

    expect(deps.setRemoteCollabConfig).toHaveBeenCalledWith(config);
    expect(deps.setRemoteCollabDebugSnapshot).toHaveBeenCalledWith(snapshot);
    expect(deps.setRemoteCollabProviderOperationResult).toHaveBeenCalledWith(operationResult);
  });

  /**
   * 验证 Prompt Enhancer 和 Commit AI 各自只更新自己的状态槽位。
   * 断言意图：两组 AI feature 配置不能互相污染。
   */
  it('routes prompt enhancer and commit AI payloads to their respective state setters', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    const promptPayload: PromptEnhancerConfig = {
      provider: null,
      effectiveProvider: 'codex',
      resolutionSource: 'auto',
      models: {
        claude: 'claude-sonnet-4-6',
        codex: 'gpt-5.5',
      },
      availability: {
        claude: true,
        codex: true,
      },
    };
    const commitPayload: CommitAiConfig = {
      provider: null,
      effectiveProvider: 'codex',
      resolutionSource: 'auto',
      models: {
        claude: 'claude-sonnet-4-6',
        codex: 'gpt-5.5',
      },
      availability: {
        claude: true,
        codex: true,
      },
    };

    window.updatePromptEnhancerConfig?.(JSON.stringify(promptPayload));
    window.updateCommitAiConfig?.(JSON.stringify(commitPayload));

    expect(deps.setPromptEnhancerConfig).toHaveBeenCalledWith(promptPayload);
    expect(deps.setCommitAiConfig).toHaveBeenCalledWith(commitPayload);
  });

  /**
   * 验证后端推送“右键打开调试面板”配置时，设置页会把值回写到对应状态槽位。
   * 断言意图：保证新增全局布尔配置沿用现有 window.updateXxx 协议，
   * 并且不会污染其他行为类设置状态。
   */
  it('writes right click devtools toggle updates into the dedicated settings state', () => {
    const deps = createDeps();
    const setRightClickOpenDevToolsEnabled = vi.fn();
    renderHook(() => useSettingsWindowCallbacks({
      ...deps,
      setRightClickOpenDevToolsEnabled,
    } as SettingsWindowCallbacksDeps & {
      setRightClickOpenDevToolsEnabled: (enabled: boolean) => void;
    }));

    window.updateRightClickOpenDevToolsEnabled?.(JSON.stringify({
      rightClickOpenDevToolsEnabled: true,
    }));

    expect(setRightClickOpenDevToolsEnabled).toHaveBeenCalledWith(true);
  });

  it('writes frontend debug config updates into the dedicated settings state', () => {
    // 验证设置页会把后端返回的双开关分别回写到各自状态位，避免两个开关在前端被错误复用成同一个布尔值。
    const deps = createDeps();
    const updateRuntimeSpy = vi.spyOn(debugModule, 'updateFrontendDebugRuntimeConfig');
    const setFrontendDebugPanelEnabled = vi.fn();
    const setFrontendDiagnosticArchiveEnabled = vi.fn();
    renderHook(() => useSettingsWindowCallbacks({
      ...deps,
      setFrontendDebugPanelEnabled,
      setFrontendDiagnosticArchiveEnabled,
    }));

    window.updateFrontendDebugConfig?.(JSON.stringify({
      panelEnabled: true,
      archiveEnabled: false,
      panelConfigured: false,
      archiveConfigured: false,
    }));

    expect(setFrontendDebugPanelEnabled).toHaveBeenCalledWith(true);
    expect(setFrontendDiagnosticArchiveEnabled).toHaveBeenCalledWith(false);
    expect(updateRuntimeSpy).toHaveBeenCalledWith({
      panelEnabled: true,
      archiveEnabled: false,
      panelConfigured: false,
      archiveConfigured: false,
    });
  });

  /**
   * 验证 UI 字体配置会同时落状态并立即应用到页面。
   * 断言意图：保留 upstream 的 UI 字体即时生效能力。
   */
  it('applies ui font config immediately when backend pushes an updated payload', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    const payload = {
      mode: 'customFile',
      effectiveMode: 'customFile',
      customFontPath: '/tmp/MapleMono.ttf',
      fontFamily: 'CC GUI Custom',
      fontSize: 14,
      lineSpacing: 1.35,
      fontBase64: 'AAECA',
      fontFormat: 'truetype',
    };

    window.onUiFontConfigReceived?.(JSON.stringify(payload));

    expect(deps.setUiFontConfig).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'customFile',
      customFontPath: '/tmp/MapleMono.ttf',
      fontFamily: 'CC GUI Custom',
    }));
    expect(window.applyUiFontConfig).toHaveBeenCalledWith(expect.objectContaining({
      mode: 'customFile',
      customFontPath: '/tmp/MapleMono.ttf',
      fontBase64: 'AAECA',
      fontFormat: 'truetype',
    }));
  });

  /**
   * 验证 Claude CLI 路径回调会更新输入框状态并关闭保存中标记。
   * 该断言覆盖路径校验失败时“保留用户输入”的交互前提。
   */
  it('updates Claude CLI path state and clears saving flag when backend responds', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    window.updateClaudeCliPath?.(JSON.stringify({ path: '/opt/claude/bin/claude' }));

    expect(deps.setClaudeCliPath).toHaveBeenCalledWith('/opt/claude/bin/claude');
    expect(deps.setSavingClaudeCliPath).toHaveBeenCalledWith(false);
  });

  /**
   * 验证后端推送的 Codex 历史图片缓存配置会正确写入设置页状态。
   * 这保证设置页打开时既能显示用户自定义目录，也能展示默认生效目录与治理参数。
   */
  it('updates Codex history image cache config state from backend callback', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    window.updateCodexHistoryImageCacheConfig?.(JSON.stringify({
      customDir: '/tmp/codex-history-images',
      resolvedDir: '/tmp/codex-history-images',
      retentionDays: 45,
      maxSizeMb: 2048,
    }));

    expect(deps.setCodexHistoryImageCacheDir).toHaveBeenCalledWith('/tmp/codex-history-images');
    expect(deps.setCodexHistoryImageCacheResolvedDir).toHaveBeenCalledWith('/tmp/codex-history-images');
    expect(deps.setCodexHistoryImageCacheRetentionDays).toHaveBeenCalledWith(45);
    expect(deps.setCodexHistoryImageCacheMaxSizeMb).toHaveBeenCalledWith(2048);
    expect(deps.setSavingCodexHistoryImageCache).toHaveBeenCalledWith(false);
  });

  /**
   * 验证 provider 测试结果走独立回调，不再复用切换成功提示。
   * 断言意图：测试成功与失败应分别映射到独立标题，避免与 switch toast 混淆。
   */
  it('shows dedicated alerts for structured codex provider test results', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    window.showTestResult?.(JSON.stringify({
      success: true,
      providerId: 'minimax-cn',
      requestMode: 'codex_sdk',
      model: 'MiniMax-M2.5',
      resolvedBaseUrl: 'https://api.minimaxi.com/v1',
      credentialSource: 'apiKeyEnv:MINIMAX_API_KEY',
      transport: 'codex_sdk',
      effectiveConfigSource: 'codemoss_managed_provider',
      fallbackDetected: false,
      endpointSource: 'provider',
      authMode: 'api_key_env',
      message: 'provider ok',
    }));
    window.showTestResult?.(JSON.stringify({
      success: false,
      providerId: 'minimax-cn',
      requestMode: 'codex_sdk',
      model: 'MiniMax-M2.5',
      resolvedBaseUrl: 'https://local.example.com/v1',
      credentialSource: 'apiKeyEnv:MINIMAX_API_KEY',
      transport: 'codex_sdk',
      effectiveConfigSource: 'codemoss_managed_provider',
      fallbackDetected: true,
      endpointSource: 'codex_local_config',
      authMode: 'api_key_env',
      message: 'provider failed',
    }));

    expect(deps.showAlert).toHaveBeenNthCalledWith(
      1,
      'success',
      'toast.testResultPassed',
      expect.stringContaining('provider ok')
    );
    expect(deps.showAlert).toHaveBeenNthCalledWith(
      2,
      'error',
      'toast.testResultFailed',
      expect.stringContaining('provider failed')
    );
    expect((deps.showAlert as ReturnType<typeof vi.fn>).mock.calls[0]?.[2]).toContain('Runtime Source: Managed Provider');
    expect((deps.showAlert as ReturnType<typeof vi.fn>).mock.calls[1]?.[2]).toContain('Runtime Source: Proxy Fallback');
    expect(deps.setTestingCodexProviderId).toHaveBeenCalledWith('');
  });

  /**
   * 验证设置页卸载时会清理 provider 测试结果回调。
   * 断言意图：避免旧页面闭包残留到下次挂载。
   */
  it('cleans up showTestResult callback on unmount', () => {
    const deps = createDeps();
    const { unmount } = renderHook(() => useSettingsWindowCallbacks(deps));

    expect(window.showTestResult).toBeTypeOf('function');

    unmount();

    expect(window.showTestResult).toBeUndefined();
  });
});
