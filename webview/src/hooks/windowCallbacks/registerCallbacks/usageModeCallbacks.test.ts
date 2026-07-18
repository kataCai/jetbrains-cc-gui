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
    setActiveSessionRuntimeSnapshot: vi.fn(),
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
    shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef: { current: true },
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

  it('restores active runtime snapshot without overwriting the current selector when desired selection was customized locally', () => {
    /**
     * 中文注释：用户在聊天区手动改过目标模型后，后端再回推活动分段运行态时，
     * 只能更新 active runtime 快照，不能把聊天区当前选择器强行改回旧运行态。
     */
    const options = createOptions('codex');
    options.shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef.current = false;
    registerUsageModeCallbacks(options);

    window.restoreTabRuntimeState?.(JSON.stringify({
      provider: 'codex',
      model: 'gpt-5.4',
      permissionMode: 'acceptEdits',
      reasoningEffort: 'medium',
      codexProviderId: 'managed-openai',
      logicalConversationId: 'logical-001',
      activeSegmentSessionId: 'segment-002',
    }));

    expect(options.setActiveSessionRuntimeSnapshot).toHaveBeenCalledWith(expect.objectContaining({
      provider: 'codex',
      model: 'gpt-5.4',
      reasoningEffort: 'medium',
      codexProviderId: 'managed-openai',
    }));
    expect(options.setCurrentProvider).not.toHaveBeenCalled();
    expect(options.setSelectedCodexModel).not.toHaveBeenCalled();
    expect(options.setSelectedCodexSelectionKey).not.toHaveBeenCalled();
    expect(options.setReasoningEffort).not.toHaveBeenCalled();
  });

  it('consumes pending tab runtime restore after callbacks mount without overwriting a customized selector', () => {
    /**
     * 中文注释：
     * 该用例覆盖 Task 1 Step 6 中的“窗口重开 / 回调晚于 payload 注册”场景。
     * 当 main.tsx 先把恢复 payload 暂存在 `__pendingTabRuntimeState`，随后聊天页才重新挂载时，
     * restore 仍应只刷新 active runtime snapshot 与 continued 运行态字段，
     * 不能把用户已经改过的聊天区选择器重新改回旧 session 的 provider/model/reasoning。
     */
    const options = createOptions('codex');
    options.shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef.current = false;
    window.__pendingTabRuntimeState = JSON.stringify({
      provider: 'codex',
      model: 'gpt-5.4',
      permissionMode: 'default',
      reasoningEffort: 'medium',
      codexProviderId: 'managed-openai',
      logicalConversationId: 'logical-reopen-001',
      activeSegmentSessionId: 'segment-reopen-002',
      parentSegmentSessionId: 'segment-reopen-001',
      continuationPending: true,
      continuationSourceSessionId: 'segment-reopen-001',
    });

    registerUsageModeCallbacks(options);

    expect(window.__pendingTabRuntimeState).toBeUndefined();
    expect(options.setActiveSessionRuntimeSnapshot).toHaveBeenCalledWith(expect.objectContaining({
      provider: 'codex',
      model: 'gpt-5.4',
      reasoningEffort: 'medium',
      codexProviderId: 'managed-openai',
    }));
    expect(options.setLogicalConversationId).toHaveBeenCalledWith('logical-reopen-001');
    expect(options.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-reopen-002');
    expect(options.setParentSegmentSessionId).toHaveBeenCalledWith('segment-reopen-001');
    expect(options.setContinuationPending).toHaveBeenCalledWith(true);
    expect(options.setContinuationSourceSessionId).toHaveBeenCalledWith('segment-reopen-001');
    expect(options.setCurrentProvider).not.toHaveBeenCalled();
    expect(options.setSelectedCodexModel).not.toHaveBeenCalled();
    expect(options.setSelectedCodexSelectionKey).not.toHaveBeenCalled();
    expect(options.setReasoningEffort).not.toHaveBeenCalled();
  });

  it('keeps a customized selector stable across locked-task restore pushback and later history replay refresh', () => {
    /**
     * 中文注释：
     * 该用例把 Task 1 Step 6 剩余的“锁定任务回推不覆盖选择器 / 历史回放不覆盖选择器”场景合并验证：
     * 1. 锁定任务按旧 runtime 执行后，后端回推 active runtime snapshot；
     * 2. 随后历史回放又用 latestSessionId 兜底恢复最新活动分段。
     * 这两次 restore 都只能更新 active runtime 与 continued 会话锚点，
     * 不能再把聊天区当前选择器强行改回旧 runtime。
     */
    const options = createOptions('claude');
    options.shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef.current = false;
    registerUsageModeCallbacks(options);

    window.restoreTabRuntimeState?.(JSON.stringify({
      provider: 'codex',
      model: 'gpt-5.4',
      permissionMode: 'default',
      reasoningEffort: 'low',
      codexProviderId: 'managed-openai',
      logicalConversationId: 'logical-locked-001',
      activeSegmentSessionId: 'segment-locked-002',
      parentSegmentSessionId: 'segment-locked-001',
    }));
    window.restoreTabRuntimeState?.(JSON.stringify({
      provider: 'codex',
      model: 'gpt-5.5',
      permissionMode: 'default',
      reasoningEffort: 'high',
      codexProviderId: 'managed-openai',
      logicalConversationId: 'logical-locked-001',
      latestSessionId: 'segment-history-003',
      parentSegmentSessionId: 'segment-locked-002',
      continuationPending: true,
    }));

    expect(options.setActiveSessionRuntimeSnapshot).toHaveBeenNthCalledWith(1, expect.objectContaining({
      provider: 'codex',
      model: 'gpt-5.4',
      reasoningEffort: 'low',
      codexProviderId: 'managed-openai',
    }));
    expect(options.setActiveSessionRuntimeSnapshot).toHaveBeenNthCalledWith(2, expect.objectContaining({
      provider: 'codex',
      model: 'gpt-5.5',
      reasoningEffort: 'high',
      codexProviderId: 'managed-openai',
    }));
    expect(options.setActiveSegmentSessionId).toHaveBeenNthCalledWith(1, 'segment-locked-002');
    expect(options.setActiveSegmentSessionId).toHaveBeenNthCalledWith(2, 'segment-history-003');
    expect(options.setParentSegmentSessionId).toHaveBeenNthCalledWith(2, 'segment-locked-002');
    expect(options.setContinuationPending).toHaveBeenNthCalledWith(2, false);
    expect(options.setContinuationSourceSessionId).toHaveBeenNthCalledWith(2, null);
    expect(options.setCurrentProvider).not.toHaveBeenCalled();
    expect(options.setSelectedCodexModel).not.toHaveBeenCalled();
    expect(options.setSelectedCodexSelectionKey).not.toHaveBeenCalled();
    expect(options.setReasoningEffort).not.toHaveBeenCalled();
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
