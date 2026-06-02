import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSettingsWindowCallbacks, type SettingsWindowCallbacksDeps } from './useSettingsWindowCallbacks';
import type { CommitAiConfig } from '../../../types/aiFeatureConfig';
import type { PromptEnhancerConfig } from '../../../types/promptEnhancer';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('useSettingsWindowCallbacks merged callback registry', () => {
  const createDeps = (): SettingsWindowCallbacksDeps => ({
    setNodePath: vi.fn(),
    setNodeVersion: vi.fn(),
    setMinNodeVersion: vi.fn(),
    setSavingNodePath: vi.fn(),
    setWorkingDirectory: vi.fn(),
    setSavingWorkingDirectory: vi.fn(),
    setCommitPrompt: vi.fn(),
    setSavingCommitPrompt: vi.fn(),
    setCommitAiConfig: vi.fn(),
    setPromptEnhancerConfig: vi.fn(),
    setEditorFontConfig: vi.fn(),
    setUiFontConfig: vi.fn(),
    setIdeTheme: vi.fn(),
    setLocalStreamingEnabled: vi.fn(),
    setCodexSandboxMode: vi.fn(),
    setLocalSendShortcut: vi.fn(),
    setLoading: vi.fn(),
    setCodexLoading: vi.fn(),
    setCodexConfigLoading: vi.fn(),
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
    loadAgents: vi.fn(),
    updateAgents: vi.fn(),
    handleAgentOperationResult: vi.fn(),
    handleAgentImportPreviewResult: vi.fn(),
    handleAgentImportResult: vi.fn(),
    updateCodexProviders: vi.fn(),
    updateActiveCodexProvider: vi.fn(),
    updateCurrentCodexConfig: vi.fn(),
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
    expect(window.sendToJava).toHaveBeenCalledWith('get_working_directory:');
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
   * 验证 provider 测试结果走独立回调，不再复用切换成功提示。
   * 断言意图：测试成功与失败应分别映射到独立标题，避免与 switch toast 混淆。
   */
  it('shows dedicated alerts for codex provider test results', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    window.showTestResult?.(true, 'provider ok');
    window.showTestResult?.(false, 'provider failed');

    expect(deps.showAlert).toHaveBeenNthCalledWith(
      1,
      'success',
      'toast.testResultPassed',
      'provider ok'
    );
    expect(deps.showAlert).toHaveBeenNthCalledWith(
      2,
      'error',
      'toast.testResultFailed',
      'provider failed'
    );
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
