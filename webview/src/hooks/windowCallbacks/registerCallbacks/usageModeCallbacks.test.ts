import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registerUsageModeCallbacks } from './usageModeCallbacks';

vi.mock('../settingsBootstrap', async () => {
  const actual = await vi.importActual<typeof import('../settingsBootstrap')>('../settingsBootstrap');
  return {
    ...actual,
    startInitialSettingsRequest: vi.fn(),
  };
});

function createOptions(provider: 'claude' | 'codex' = 'claude') {
  return {
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setLogicalConversationId: vi.fn(),
    setActiveSegmentSessionId: vi.fn(),
    setParentSegmentSessionId: vi.fn(),
    setContinuationPending: vi.fn(),
    setContinuationSourceSessionId: vi.fn(),
    setCurrentProvider: vi.fn(),
    setPermissionMode: vi.fn(),
    setClaudePermissionMode: vi.fn(),
    setCodexPermissionMode: vi.fn(),
    setSelectedClaudeModel: vi.fn(),
    setSelectedCodexModel: vi.fn(),
    setSelectedCodexSelectionKey: vi.fn(),
    setActiveCodexProviderId: vi.fn(),
    setDefaultCodexModelFromConfig: vi.fn(),
    setCodexBaseUrl: vi.fn(),
    setCodexUsesCustomBaseUrl: vi.fn(),
    setReasoningEffort: vi.fn(),
    setProviderConfigVersion: vi.fn(),
    setActiveProviderConfig: vi.fn(),
    setClaudeSettingsAlwaysThinkingEnabled: vi.fn(),
    setStreamingEnabledSetting: vi.fn(),
    setSendShortcut: vi.fn(),
    setAutoOpenFileEnabled: vi.fn(),
    setPermissionDialogTimeoutSeconds: vi.fn(),
    currentProviderRef: { current: provider },
    activeCodexProviderIdRef: { current: '' },
    shouldAdoptCodexDefaultModelRef: { current: true },
    shouldAdoptCodexDefaultReasoningEffortRef: { current: true },
    syncActiveProviderModelMapping: vi.fn(),
  } as any;
}

describe('registerUsageModeCallbacks', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.onModeChanged = undefined;
    window.onModeReceived = undefined;
  });

  it('passes through plan mode for claude on mode changes', () => {
    // Claude 的 mode change 应保留 plan，不应被兼容逻辑误降级。
    const options = createOptions('claude');
    registerUsageModeCallbacks(options);

    window.onModeChanged?.('plan');

    const permissionUpdater = options.setPermissionMode.mock.calls[0][0];
    const claudeUpdater = options.setClaudePermissionMode.mock.calls[0][0];
    expect(permissionUpdater('default')).toBe('plan');
    expect(claudeUpdater('default')).toBe('plan');
    expect(options.setCodexPermissionMode).not.toHaveBeenCalled();
  });

  it('downgrades codex plan mode to default on received mode', () => {
    // 收到来自后端/恢复链路的 plan 时，Codex 仍需回落为 default。
    const options = createOptions('codex');
    registerUsageModeCallbacks(options);

    window.onModeReceived?.('plan');

    const permissionUpdater = options.setPermissionMode.mock.calls[0][0];
    const codexUpdater = options.setCodexPermissionMode.mock.calls[0][0];
    expect(permissionUpdater('plan')).toBe('default');
    expect(codexUpdater('plan')).toBe('default');
    expect(options.setClaudePermissionMode).not.toHaveBeenCalled();
  });

  it('keeps non-plan modes unchanged for codex', () => {
    // 兼容逻辑只影响 plan，其它 execution mode 必须保持原样。
    const options = createOptions('codex');
    registerUsageModeCallbacks(options);

    window.onModeChanged?.('acceptEdits');

    const permissionUpdater = options.setPermissionMode.mock.calls[0][0];
    const codexUpdater = options.setCodexPermissionMode.mock.calls[0][0];
    expect(permissionUpdater('default')).toBe('acceptEdits');
    expect(codexUpdater('default')).toBe('acceptEdits');
  });
  it('updates codex selected model from backend codex model state callback', () => {
    /**
     * 验证后端同步 Codex 默认模型时，除了更新 raw modelId，
     * 还会按当前 active provider 生成聊天区使用的复合选中 key。
     */
    const options = createOptions('codex');
    options.activeCodexProviderIdRef.current = 'managed-openai';
    registerUsageModeCallbacks(options);

    window.updateCodexModelState?.(JSON.stringify({
      model: 'gpt-5.5',
      reasoningEffort: 'high',
    }));

    expect(options.setDefaultCodexModelFromConfig).toHaveBeenCalledWith('gpt-5.5');
    expect(options.setCodexBaseUrl).toHaveBeenCalledWith(null);
    expect(options.setCodexUsesCustomBaseUrl).toHaveBeenCalledWith(false);
    expect(options.setSelectedCodexModel).toHaveBeenCalledWith('gpt-5.5');
    expect(options.setSelectedCodexSelectionKey).toHaveBeenCalledWith('managed-openai::gpt-5.5');
    expect(options.setReasoningEffort).toHaveBeenCalledWith('high');
  });

  it('does not trigger another codex model state refresh after backend sync', () => {
    const options = createOptions('codex');
    registerUsageModeCallbacks(options);

    window.updateCodexModelState?.(JSON.stringify({
      model: 'gpt-5.5',
      reasoningEffort: 'high',
    }));

    expect((options as any).setCodexModelStateVersion).toBeUndefined();
  });

  it('stores cli default model without overriding a user-selected codex session model', () => {
    /**
     * 验证标签页已形成显式选择后，CLI 默认模型只更新展示用途的默认值，
     * 不应再覆盖当前会话中的 raw modelId 或复合选中 key。
     */
    const options = createOptions('codex');
    options.shouldAdoptCodexDefaultModelRef.current = false;
    registerUsageModeCallbacks(options);

    window.updateCodexModelState?.(JSON.stringify({
      model: 'gpt-5.5',
    }));

    expect(options.setDefaultCodexModelFromConfig).toHaveBeenCalledWith('gpt-5.5');
    expect(options.setSelectedCodexModel).not.toHaveBeenCalled();
    expect(options.setSelectedCodexSelectionKey).not.toHaveBeenCalled();
  });

  it('stores cli default reasoning without overriding a user-selected codex session reasoning', () => {
    const options = createOptions('codex');
    options.shouldAdoptCodexDefaultReasoningEffortRef.current = false;
    registerUsageModeCallbacks(options);

    window.updateCodexModelState?.(JSON.stringify({
      reasoningEffort: 'high',
    }));

    expect(options.setReasoningEffort).not.toHaveBeenCalled();
  });

  it('stores custom codex base_url warning metadata from backend sync', () => {
    const options = createOptions('codex');
    registerUsageModeCallbacks(options);

    window.updateCodexModelState?.(JSON.stringify({
      model: 'gpt-5.5',
      baseUrl: 'https://rayplus.site',
      usesCustomBaseUrl: true,
    }));

    expect(options.setCodexBaseUrl).toHaveBeenCalledWith('https://rayplus.site');
    expect(options.setCodexUsesCustomBaseUrl).toHaveBeenCalledWith(true);
  });

  it('restores tab-local codex runtime state before generic bootstrap defaults', () => {
    /**
     * 验证恢复标签页快照时，会同步恢复 providerId、raw modelId 与复合选中 key，
     * 避免聊天区重新退化为仅按 modelId 判断勾选态。
     */
    const options = createOptions('claude');
    registerUsageModeCallbacks(options);

    window.restoreTabRuntimeState?.(JSON.stringify({
      provider: 'codex',
      model: 'MiniMax-M2.5',
      permissionMode: 'acceptEdits',
      reasoningEffort: 'high',
      codexProviderId: 'minimax',
    }));

    expect(options.setCurrentProvider).toHaveBeenCalledWith('codex');
    expect(options.setSelectedCodexModel).toHaveBeenCalledWith('MiniMax-M2.5');
    expect(options.setSelectedCodexSelectionKey).toHaveBeenCalledWith('minimax::MiniMax-M2.5');
    expect(options.setActiveCodexProviderId).toHaveBeenCalledWith('minimax');
    expect(options.setReasoningEffort).toHaveBeenCalledWith('high');

    const permissionUpdater = options.setPermissionMode.mock.calls[0][0];
    const codexUpdater = options.setCodexPermissionMode.mock.calls[0][0];
    expect(permissionUpdater('default')).toBe('acceptEdits');
    expect(codexUpdater('default')).toBe('acceptEdits');
    expect(options.shouldAdoptCodexDefaultModelRef.current).toBe(false);
  });

  it('applies fresh new tab defaults and protects remembered model and reasoning from later cli overwrite', () => {
    /**
     * 验证新建标签页默认值链路会为 Codex 同步建立复合选中 key，
     * 并在 remembered_model / remembered_reasoning 场景下关闭后续 CLI 覆盖。
     */
    const options = createOptions('claude');
    registerUsageModeCallbacks(options);

    window.applyNewTabDefaults?.(JSON.stringify({
      provider: 'codex',
      model: 'gpt-5.4',
      permissionMode: 'bypassPermissions',
      reasoningEffort: 'low',
      codexProviderId: 'managed-openai',
      modelSource: 'remembered_model',
      reasoningSource: 'remembered_reasoning',
    }));

    expect(options.setCurrentProvider).toHaveBeenCalledWith('codex');
    expect(options.setSelectedCodexModel).toHaveBeenCalledWith('gpt-5.4');
    expect(options.setSelectedCodexSelectionKey).toHaveBeenCalledWith('managed-openai::gpt-5.4');
    expect(options.setActiveCodexProviderId).toHaveBeenCalledWith('managed-openai');
    expect(options.setReasoningEffort).toHaveBeenCalledWith('low');
    expect(options.shouldAdoptCodexDefaultModelRef.current).toBe(false);
    expect(options.shouldAdoptCodexDefaultReasoningEffortRef.current).toBe(false);
  });
});
