import { act, renderHook } from '@testing-library/react';
import { useWindowCallbacks } from './useWindowCallbacks.js';
import type { UseWindowCallbacksOptions } from './useWindowCallbacks.js';
import type { ClaudeMessage } from '../types/index.js';
import * as debugModule from '../utils/debug.js';
import * as exportMarkdownModule from '../utils/exportMarkdown.js';

vi.mock('./windowCallbacks/settingsBootstrap', async () => {
  const actual = await vi.importActual<typeof import('./windowCallbacks/settingsBootstrap')>('./windowCallbacks/settingsBootstrap');
  return {
    ...actual,
    startInitialSettingsRequest: vi.fn(),
    startActiveProviderRequest: vi.fn(),
    startModeRequest: vi.fn(),
    startThinkingEnabledRequest: vi.fn(),
    drainAndRequestDependencyStatus: vi.fn(),
  };
});

vi.mock('../utils/exportMarkdown.js', async () => {
  const actual = await vi.importActual<typeof import('../utils/exportMarkdown.js')>('../utils/exportMarkdown.js');
  return {
    ...actual,
    downloadJSON: vi.fn(),
  };
});

/**
 * Integration tests for useWindowCallbacks — verifies the real window callback
 * chain (historyLoadComplete, addErrorMessage, updateMessages guard, clearMessages,
 * setSessionId) rather than simulating state bits.
 */
describe('useWindowCallbacks integration', () => {
  const t = ((key: string) => key) as any;

  /** Build the full options object with vi.fn() stubs for every field. */
  const createOptions = (overrides?: Partial<UseWindowCallbacksOptions>): UseWindowCallbacksOptions => ({
    t,
    addToast: vi.fn(),
    clearToasts: vi.fn(),

    // State setters
    setMessages: vi.fn(),
    setStatus: vi.fn(),
    setLoading: vi.fn(),
    setLoadingStartTime: vi.fn(),
    setIsThinking: vi.fn(),
    setExpandedThinking: vi.fn(),
    setStreamingActive: vi.fn(),
    setHistoryData: vi.fn(),
    setCurrentSessionId: vi.fn(),
    setCustomSessionTitle: vi.fn(),
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setLogicalConversationId: vi.fn(),
    setActiveSegmentSessionId: vi.fn(),
    setParentSegmentSessionId: vi.fn(),
    setContinuationPending: vi.fn(),
    setContinuationSourceSessionId: vi.fn(),
    setCurrentProvider: vi.fn(),
    setSubagentHistories: vi.fn(),
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
    setSdkStatus: vi.fn(),
    setSdkStatusLoaded: vi.fn(),
    setIsRewinding: vi.fn(),
    setRewindDialogOpen: vi.fn(),
    setCurrentRewindRequest: vi.fn(),
    setContextInfo: vi.fn(),
    setSelectedAgent: vi.fn(),

    // Refs
    currentProviderRef: { current: 'claude' },
    activeCodexProviderIdRef: { current: '' },
    shouldAdoptCodexDefaultModelRef: { current: true },
    shouldAdoptCodexDefaultReasoningEffortRef: { current: true },
    shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef: { current: true },
    messagesContainerRef: { current: null },
    isUserAtBottomRef: { current: true },
    userPausedRef: { current: false },
    suppressNextStatusToastRef: { current: false },
    messagesRef: { current: [] },
    streamingContentRef: { current: '' },
    streamingThinkingRef: { current: '' },
    isStreamingRef: { current: false },
    useBackendStreamingRenderRef: { current: false },
    autoExpandedThinkingKeysRef: { current: new Set<string>() },
    streamingMessageIndexRef: { current: -1 },
    streamingTurnIdRef: { current: -1 },
    turnIdCounterRef: { current: 0 },
    lastContentUpdateRef: { current: 0 },
    contentUpdateTimeoutRef: { current: null } as { current: number | null },
    lastThinkingUpdateRef: { current: 0 },
    thinkingUpdateTimeoutRef: { current: null } as { current: number | null },

    // Functions
    findLastAssistantIndex: (msgs: ClaudeMessage[]) =>
      msgs.reduce((acc, m, i) => (m.type === 'assistant' ? i : acc), -1),
    extractRawBlocks: () => [],
    getOrCreateStreamingAssistantIndex: () => 0,
    patchAssistantForStreaming: (msg: ClaudeMessage) => msg,
    syncActiveProviderModelMapping: vi.fn(),
    openPermissionDialog: vi.fn(),
    openAskUserQuestionDialog: vi.fn(),
    openPlanApprovalDialog: vi.fn(),
    openContextUsageDialog: vi.fn(),
    updateContextUsageData: vi.fn(() => true),
    closeContextUsageDialog: vi.fn(() => false),

    // B-011
    customSessionTitleRef: { current: null },
    currentSessionIdRef: { current: null },
    logicalConversationIdRef: { current: null },
    activeSegmentSessionIdRef: { current: null },
    continuationPendingRef: { current: false },
    updateHistoryTitle: vi.fn(),
    applyHistoryTitleLocal: vi.fn(),

    ...overrides,
  });

  beforeEach(() => {
    window.__sessionTransitioning = false;
    window.__sessionTransitionToken = null;
    window.__pendingSessionTransitionToast = undefined;
    window.__pendingCompleteContinuedSegmentTransitionPayload = undefined;
    window.__pendingCompleteContinuedSegmentTransitionSessionId = undefined;
    window.__continuedSegmentFirstSnapshotSessionId = null;
    window.__continuedSegmentHistoryPrefixMessages = null;
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingTailMessages = null;
    (window as any).__continuedSegmentPendingSourceSessionId = null;
    (window as any).__continuedSegmentPendingLogicalConversationId = null;
    (window as any).__continuedSegmentPendingCreatedAt = null;
    (window as any).__continuedSegmentPendingReason = null;
    (window as any).__continuedSegmentAwaitingFirstSessionId = false;
    window.__deniedToolIds = new Set();
    window.__preparedHistoryRestoreKey = null;
    window.__preparedHistoryRestoreSignature = null;
    window.__preparedHistoryRestoreKind = null;
    window.__lastAppliedHistoryRestoreKey = null;
    window.__lastAppliedHistoryRestoreSignature = null;
    window.__lastAppliedHistoryRestoreKind = null;
    window.exportFrontendTranscriptDiagnosticSnapshot = undefined;
    window.sendToJava = vi.fn();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('restoreTabRuntimeState 会为 Codex 恢复 provider 维度的复合选中 key', () => {
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      activeCodexProviderIdRef: { current: '' },
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.restoreTabRuntimeState?.(JSON.stringify({
        provider: 'codex',
        model: 'gpt-5.4',
        codexProviderId: 'custom_gateway',
      }));
    });

    expect(opts.setSelectedCodexModel).toHaveBeenCalledWith('gpt-5.4');
    expect(opts.setSelectedCodexSelectionKey).toHaveBeenCalledWith('custom_gateway::gpt-5.4');
    expect(opts.setActiveCodexProviderId).toHaveBeenCalledWith('custom_gateway');
  });

  it('updateCodexModelState 在沿用 CLI 默认模型时会同步复合选中 key', () => {
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      activeCodexProviderIdRef: { current: 'custom_gateway' },
      shouldAdoptCodexDefaultModelRef: { current: true },
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.updateCodexModelState?.(JSON.stringify({
        model: 'gpt-5.5',
      }));
    });

    expect(opts.setSelectedCodexModel).toHaveBeenCalledWith('gpt-5.5');
    expect(opts.setSelectedCodexSelectionKey).toHaveBeenCalledWith('custom_gateway::gpt-5.5');
  });

  /**
   * 验证聊天页收到后端下发的前端调试配置时，会同步刷新运行时诊断日志配置。
   * 这样即使设置页未打开，rich paste 与历史恢复链路的关键诊断日志也能立即按最新开关生效。
   */
  it('updateFrontendDebugConfig refreshes runtime diagnostic config in chat mode', () => {
    const updateRuntimeSpy = vi.spyOn(debugModule, 'updateFrontendDebugRuntimeConfig');
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.updateFrontendDebugConfig?.(JSON.stringify({
        panelEnabled: true,
        archiveEnabled: true,
        panelConfigured: false,
        archiveConfigured: false,
      }));
    });

    expect(updateRuntimeSpy).toHaveBeenCalledWith({
      panelEnabled: true,
      archiveEnabled: true,
      panelConfigured: false,
      archiveConfigured: false,
    });
  });

  it('restoreTabRuntimeState 恢复逻辑会话与 continued segment 运行态字段', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.restoreTabRuntimeState?.(JSON.stringify({
        provider: 'codex',
        model: 'gpt-5.4',
        codexProviderId: 'buycode',
        logicalConversationId: 'logical-001',
        activeSegmentSessionId: 'segment-002',
        parentSegmentSessionId: 'segment-001',
        continuationPending: true,
        continuationSourceSessionId: 'segment-001',
      }));
    });

    expect(opts.setLogicalConversationId).toHaveBeenCalledWith('logical-001');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-002');
    expect(opts.setParentSegmentSessionId).toHaveBeenCalledWith('segment-001');
    expect(opts.setContinuationPending).toHaveBeenCalledWith(true);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith('segment-001');
  });

  it('restoreTabRuntimeState falls back to latestSessionId when active segment is missing', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.restoreTabRuntimeState?.(JSON.stringify({
        provider: 'codex',
        model: 'gpt-5.4',
        codexProviderId: 'buycode',
        logicalConversationId: 'logical-001',
        latestSessionId: 'segment-003',
        parentSegmentSessionId: 'segment-002',
      }));
    });

    expect(opts.setLogicalConversationId).toHaveBeenCalledWith('logical-001');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-003');
    expect(opts.setParentSegmentSessionId).toHaveBeenCalledWith('segment-002');
    expect(opts.setContinuationPending).toHaveBeenCalledWith(false);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith(null);
  });

  /**
   * 已完成的 continued 会话在 Tab 恢复时，若已经能确定当前活动分段，就不应继续恢复为 pending。
   * 这个场景直接覆盖“旧快照残留 continuationPending=true，恢复后又把发送门禁卡死”的回归问题。
   */
  it('restoreTabRuntimeState normalizes stale continuationPending when active segment is already settled', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.restoreTabRuntimeState?.(JSON.stringify({
        provider: 'codex',
        model: 'gpt-5.4',
        codexProviderId: 'buycode',
        logicalConversationId: 'logical-001',
        activeSegmentSessionId: 'segment-003',
        parentSegmentSessionId: 'segment-002',
        continuationPending: true,
      }));
    });

    expect(opts.setLogicalConversationId).toHaveBeenCalledWith('logical-001');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-003');
    expect(opts.setParentSegmentSessionId).toHaveBeenCalledWith('segment-002');
    expect(opts.setContinuationPending).toHaveBeenCalledWith(false);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith(null);
  });

  it('restoreTabRuntimeState only refreshes active runtime snapshot after the user customized desired selection', () => {
    /**
     * 中文注释：
     * 该用例覆盖 Task 1 Step 6 的关键约束：一旦用户已经手动改过聊天区选择器，
     * 后端 restore 只能刷新 active runtime snapshot，不能再把聊天区 provider/model/reasoning 改回旧运行态。
     * 这也是后续锁定任务或 continued 回推不应覆盖当前选择器的基础保护。
     */
    const opts = createOptions({
      shouldSyncDesiredRuntimeSelectionFromActiveRuntimeRef: { current: false },
      currentProviderRef: { current: 'claude' },
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.restoreTabRuntimeState?.(JSON.stringify({
        provider: 'codex',
        model: 'gpt-5.4',
        permissionMode: 'default',
        reasoningEffort: 'low',
        codexProviderId: 'managed-openai',
        logicalConversationId: 'logical-locked-001',
        activeSegmentSessionId: 'segment-locked-002',
      }));
    });

    expect(opts.setActiveSessionRuntimeSnapshot).toHaveBeenCalledWith(expect.objectContaining({
      provider: 'codex',
      model: 'gpt-5.4',
      reasoningEffort: 'low',
      codexProviderId: 'managed-openai',
    }));
    expect(opts.setCurrentProvider).not.toHaveBeenCalled();
    expect(opts.setSelectedCodexModel).not.toHaveBeenCalled();
    expect(opts.setSelectedCodexSelectionKey).not.toHaveBeenCalled();
    expect(opts.setReasoningEffort).not.toHaveBeenCalled();
    expect(opts.setLogicalConversationId).toHaveBeenCalledWith('logical-locked-001');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-locked-002');
  });

  // ===== historyLoadComplete releases transition guard =====

  it('historyLoadComplete releases __sessionTransitioning guard', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    // Simulate: beginSessionTransition sets guard
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-1';

    // Simulate: Java calls historyLoadComplete on success
    act(() => {
      window.historyLoadComplete!();
    });

    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
  });

  it('consumes pending historyLoadComplete signal registered before callbacks mount', () => {
    window.__pendingHistoryLoadComplete = true;

    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    expect(window.__pendingHistoryLoadComplete).toBe(false);
    expect(opts.setMessages).not.toHaveBeenCalled();
  });

  it('historyLoadComplete shows pending session transition toast', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    window.__pendingSessionTransitionToast = {
      message: 'history.sessionDeleted',
      type: 'success',
    };

    act(() => {
      window.historyLoadComplete!();
    });

    expect(opts.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
    expect(window.__pendingSessionTransitionToast).toBeUndefined();
  });

  // ===== historyLoadComplete no longer forces full message re-render =====

  it('historyLoadComplete does not rebuild message objects when only releasing transition state', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.historyLoadComplete!();
    });

    expect(opts.setMessages).not.toHaveBeenCalled();
  });

  // ===== setSessionId releases transition guard =====

  it('setSessionId releases __sessionTransitioning guard', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-2';

    act(() => {
      window.setSessionId!('new-session-123');
    });

    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
    expect(opts.setCurrentSessionId).toHaveBeenCalledWith('new-session-123');
  });

  it('setSessionId marks the first continued-segment snapshot and refreshes active segment anchor', () => {
    const opts = createOptions({
      continuationPendingRef: { current: true },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.setSessionId!('segment-002');
    });

    expect(opts.setCurrentSessionId).toHaveBeenCalledWith('segment-002');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-002');
    expect(opts.setContinuationPending).toHaveBeenCalledWith(false);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith(null);
    expect(opts.currentSessionIdRef.current).toBe('segment-002');
    expect(opts.continuationPendingRef.current).toBe(false);
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBe('segment-002');
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'window.setSessionId received',
      expect.objectContaining({
        oldSessionId: null,
        activeSegmentSessionId: null,
        sessionId: 'segment-002',
        continuationPending: true,
      }),
    );
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'window.setSessionId marked continued segment first snapshot',
      expect.objectContaining({
        sessionId: 'segment-002',
      }),
    );
  });

  it('setSessionId keeps continued first snapshot state when pending flag fell back but prefix cache exists', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: 'logical-001' },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=？', timestamp: '2026-07-03T10:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-03T10:00:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;

    act(() => {
      window.setSessionId!('segment-002');
    });

    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-002');
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBe('segment-002');
    expect(window.__continuedSegmentHistoryPrefixSessionId).toBe('segment-002');
    expect(window.__continuedSegmentHistoryPrefixMessages).toHaveLength(2);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'window.setSessionId marked continued segment first snapshot',
      expect.objectContaining({
        sessionId: 'segment-002',
        fallbackMatched: true,
        fallbackBindingSource: 'prefix_cache_context',
      }),
    );
  });

  it('setSessionId does not bind continued fallback when prefix cache lost its source anchor', () => {
    // 中文注释：prefix cache 即使还在，也不能只靠 logicalConversationId 把新 session 误绑到 continued 首帧。
    // 一旦 source anchor 已丢失，就必须回到普通分支并清理过渡缓存，避免旧前缀继续污染后续消息排序。
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: 'logical-001' },
      activeSegmentSessionIdRef: { current: null },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    (window as any).__continuedSegmentPendingSourceSessionId = null;
    (window as any).__continuedSegmentPendingLogicalConversationId = 'logical-001';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    act(() => {
      window.setSessionId!('segment-002');
    });

    expect(opts.setActiveSegmentSessionId).not.toHaveBeenCalledWith('segment-002');
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBeNull();
    expect(window.__continuedSegmentHistoryPrefixSessionId).toBeNull();
    expect(window.__continuedSegmentHistoryPrefixMessages).toBeNull();
    expect((window as any).__continuedSegmentPendingLogicalConversationId).toBeNull();
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued transition cache cleared',
      expect.objectContaining({
        cleanupReason: 'ordinary_session_branch_without_continued_context',
      }),
    );
  });

  it('setSessionId uses transition cache when continued refs were lost before the real session id arrives', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: null },
      activeSegmentSessionIdRef: { current: null },
    });
    renderHook(() => useWindowCallbacks(opts));

    // 中文注释：复现日志中的关键状态：prefix cache 已经建立，但 React refs 中的
    // logicalConversationId / activeSegmentSessionId / continuationPending 已经丢失。
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=？', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    (window as any).__continuedSegmentPendingSourceSessionId = 'segment-001';
    (window as any).__continuedSegmentPendingLogicalConversationId = 'logical-001';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    act(() => {
      window.setSessionId!('segment-002');
    });

    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-002');
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBe('segment-002');
    expect(window.__continuedSegmentHistoryPrefixSessionId).toBe('segment-002');
    expect(window.__continuedSegmentHistoryPrefixMessages).toHaveLength(2);
    expect((window as any).__continuedSegmentAwaitingFirstSessionId).toBe(false);
  });

  it('completeContinuedSegmentTransition preserves prefix when it arrives after setSessionId', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: null },
      activeSegmentSessionIdRef: { current: null },
    });
    renderHook(() => useWindowCallbacks(opts));

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=？', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    (window as any).__continuedSegmentPendingSourceSessionId = 'segment-001';
    (window as any).__continuedSegmentPendingLogicalConversationId = 'logical-001';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    act(() => {
      window.setSessionId!('segment-002');
      window.completeContinuedSegmentTransition?.('segment-002');
    });

    expect(window.__continuedSegmentHistoryPrefixMessages).toHaveLength(2);
    expect(window.__continuedSegmentHistoryPrefixSessionId).toBe('segment-002');
  });

  it('beginContinuedSegmentTransition primes silent-switch cache without clearing the visible session anchor', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: 'segment-visible-001' },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: null },
      activeSegmentSessionIdRef: { current: 'segment-visible-001' },
      messagesRef: {
        current: [
          { type: 'user', content: 'old question', timestamp: '2026-07-23T11:00:00.000Z' },
          { type: 'assistant', content: 'old answer', timestamp: '2026-07-23T11:00:01.000Z' },
        ],
      },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).beginContinuedSegmentTransition?.(JSON.stringify({
        sourceSessionId: 'segment-visible-001',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_model',
      }));
    });

    expect(window.__sessionTransitioning).toBe(false);
    expect(opts.setCurrentSessionId).not.toHaveBeenCalledWith(null);
    expect(opts.setContinuationPending).toHaveBeenCalledWith(true);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith('segment-visible-001');
    expect(opts.currentSessionIdRef.current).toBe('segment-visible-001');
    expect(window.__continuedSegmentHistoryPrefixMessages).toEqual(opts.messagesRef.current);
    expect(window.__continuedSegmentPendingSourceSessionId).toBe('segment-visible-001');
    expect(window.__continuedSegmentPendingLogicalConversationId).toBe('logical-001');
    expect(window.__continuedSegmentPendingReason).toBe('send_time_model');
    expect(window.__continuedSegmentAwaitingFirstSessionId).toBe(true);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'window.beginContinuedSegmentTransition applied',
      expect.objectContaining({
        sourceSessionId: 'segment-visible-001',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_model',
      }),
    );
  });

  it('consumes pending beginContinuedSegmentTransition signal registered before callbacks mount', () => {
    (window as any).__pendingBeginContinuedSegmentTransitionPayload = JSON.stringify({
      sourceSessionId: 'segment-early-001',
      logicalConversationId: 'logical-early-001',
      switchReason: 'send_time_model',
    });
    const opts = createOptions({
      currentSessionIdRef: { current: 'segment-early-001' },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: null },
      messagesRef: {
        current: [
          { type: 'assistant', content: 'existing context', timestamp: '2026-07-23T11:00:00.000Z' },
        ],
      },
    });

    renderHook(() => useWindowCallbacks(opts));

    expect((window as any).__pendingBeginContinuedSegmentTransitionPayload).toBeUndefined();
    expect(opts.setContinuationPending).toHaveBeenCalledWith(true);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith('segment-early-001');
    expect(window.__continuedSegmentPendingLogicalConversationId).toBe('logical-early-001');
    expect(window.__continuedSegmentHistoryPrefixMessages).toEqual(opts.messagesRef.current);
  });

  it('pre-bind short snapshot after silent switch keeps visible history until the new session id is bound', () => {
    /**
     * 中文注释：
     * 该用例精确复现方案文档里的 silent switch 第三态：
     * 1. beginContinuedSegmentTransition 之后，currentSessionId 仍指向旧分段；
     * 2. prefix cache 已建立，但 prefixSessionId 尚未绑定到新 session；
     * 3. 此时先收到仅包含当前追问的短快照，前端必须保留旧历史，不能让可见列表只剩最新提问。
     */
    const opts = createOptions({
      currentSessionIdRef: { current: 'segment-old-001' },
      continuationPendingRef: { current: false },
      activeSegmentSessionIdRef: { current: 'segment-old-001' },
      messagesRef: {
        current: [
          { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
          { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
          { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
        ],
      },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    act(() => {
      (window as any).beginContinuedSegmentTransition?.(JSON.stringify({
        sourceSessionId: 'segment-old-001',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_model',
      }));
      window.updateMessages?.(JSON.stringify([
        { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
      ]));
    });

    const updater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(updater).toBeTypeOf('function');

    const previousVisibleMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
      { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
    ] satisfies ClaudeMessage[];
    const nextVisibleMessages = updater!(previousVisibleMessages);

    expect(nextVisibleMessages).toEqual(previousVisibleMessages);
    expect(window.__continuedSegmentPendingTailMessages).toEqual([
      { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
    ]);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued prefix merge skipped',
      expect.objectContaining({
        skipReason: 'prefix_session_id_mismatch',
        currentSessionId: 'segment-old-001',
        prefixSessionId: null,
        awaitingFirstSessionId: true,
      }),
    );
  });

  it('pre-bind fuller snapshot after silent switch shows merged prefix and tail before the new session id is bound', () => {
    /**
     * 中文注释：
     * 该用例补足 silent switch pre-bind 的另一条关键分支：
     * 1. 旧分段仍保持可见，prefix cache 也还没有绑定到新 sessionId；
     * 2. 但如果首个 tail 已经比当前可见列表更完整，例如已经带上了新 assistant 回复；
     * 3. 前端就应该立即把旧 prefix 与新 tail 合成后展示出来，而不是继续只保留旧列表等待 setSessionId。
     * 这样可以验证 preserveDecision=merge_prefix 的真实可见结果，而不只是 pending tail 缓存行为。
     */
    const opts = createOptions({
      currentSessionIdRef: { current: 'segment-old-001' },
      continuationPendingRef: { current: false },
      activeSegmentSessionIdRef: { current: 'segment-old-001' },
      messagesRef: {
        current: [
          { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
          { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
          { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
        ],
      },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    act(() => {
      (window as any).beginContinuedSegmentTransition?.(JSON.stringify({
        sourceSessionId: 'segment-old-001',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_model',
      }));
      window.updateMessages?.(JSON.stringify([
        { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
        { type: 'assistant', content: '3', timestamp: '2026-07-24T14:18:09.000Z' },
      ]));
    });

    const updater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(updater).toBeTypeOf('function');

    const previousVisibleMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
      { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
    ] satisfies ClaudeMessage[];
    const nextVisibleMessages = updater!(previousVisibleMessages);

    expect(nextVisibleMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=?',
      'assistant:2',
      'user:再+1=?',
      'assistant:3',
    ]);
    expect(window.__continuedSegmentPendingTailMessages).toHaveLength(2);
    expect(window.__continuedSegmentPendingTailMessages?.[0]).toMatchObject({
      type: 'user',
      content: '再+1=?',
      timestamp: '2026-07-24T14:17:45.000Z',
    });
    expect(window.__continuedSegmentPendingTailMessages?.[1]).toMatchObject({
      type: 'assistant',
      content: '3',
    });
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued pending tail cached',
      expect.objectContaining({
        preserveDecision: 'merge_prefix',
        pendingTailCount: 2,
        awaitingFirstSessionId: true,
      }),
    );
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued prefix merge skipped',
      expect.objectContaining({
        skipReason: 'prefix_session_id_mismatch',
        preserveDecision: 'merge_prefix',
        currentSessionId: 'segment-old-001',
        prefixSessionId: null,
        awaitingFirstSessionId: true,
      }),
    );
  });

  it('silent switch continued merge deduplicates optimistic user messages when the backend timestamp differs', () => {
    /**
     * 中文注释：
     * 该用例直接覆盖线上复现的重复显示场景：
     * 1. 旧可见 transcript 的尾部是前端刚插入的 optimistic user；
     * 2. silent switch pre-bind 阶段收到的新 tail 里，后端 confirmed user 与 optimistic user 文本相同、时间戳不同；
     * 3. continued merge 必须把两者视为同一条逻辑消息，并优先保留 tail 版本，避免思考过程中短暂出现两条“再+1=?”；
     * 4. setSessionId 后的首帧 snapshot 与最终 authoritative restore 也必须维持同样的单条 user 结果。
     */
    const optimisticFollowUpMessage: ClaudeMessage = {
      type: 'user',
      content: '再+1=?',
      timestamp: '2026-07-24T14:17:45.000Z',
      isOptimistic: true,
      raw: {
        message: {
          content: [{ type: 'text', text: '再+1=?' }],
        },
      } as any,
    };
    const confirmedFollowUpMessage: ClaudeMessage = {
      type: 'user',
      content: '再+1=?',
      timestamp: '2026-07-24T14:17:47.000Z',
      raw: {
        timestamp: '2026-07-24T14:17:47.000Z',
        message: {
          content: [{ type: 'text', text: '再+1=?' }],
        },
      } as any,
    };
    const firstSegmentAssistantMessage: ClaudeMessage = {
      type: 'assistant',
      content: '3',
      timestamp: '2026-07-24T14:18:09.000Z',
    };
    const previousVisibleMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
      optimisticFollowUpMessage,
    ] satisfies ClaudeMessage[];
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: 'segment-old-001' },
      continuationPendingRef: { current: false },
      activeSegmentSessionIdRef: { current: 'segment-old-001' },
      messagesRef: {
        current: previousVisibleMessages,
      },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    act(() => {
      (window as any).beginContinuedSegmentTransition?.(JSON.stringify({
        sourceSessionId: 'segment-old-001',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_model',
      }));
      window.updateMessages?.(JSON.stringify([
        confirmedFollowUpMessage,
        firstSegmentAssistantMessage,
      ]));
    });

    const preBindUpdater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(preBindUpdater).toBeTypeOf('function');

    const preBindVisibleMessages = preBindUpdater!(previousVisibleMessages);

    expect(preBindVisibleMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=?',
      'assistant:2',
      'user:再+1=?',
      'assistant:3',
    ]);
    expect(preBindVisibleMessages.filter((message) => message.type === 'user' && message.content === '再+1=?')).toHaveLength(1);
    expect(preBindVisibleMessages[2]).toMatchObject({
      type: 'user',
      content: '再+1=?',
      timestamp: '2026-07-24T14:17:47.000Z',
    });
    expect(preBindVisibleMessages[2].isOptimistic).not.toBe(true);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued pending tail cached',
      expect.objectContaining({
        preserveDecision: 'merge_prefix',
        optimisticOverlapMatched: true,
        overlapCount: 1,
        overlapResolvedBy: 'optimistic_content_window',
        preferTailOnOverlap: true,
      }),
    );

    act(() => {
      window.setSessionId?.('segment-new-001');
    });

    expect(window.__continuedSegmentHistoryPrefixSessionId).toBe('segment-new-001');
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBe('segment-new-001');
    (opts.setMessages as any).mockClear();

    act(() => {
      window.updateMessages?.(JSON.stringify([
        confirmedFollowUpMessage,
        firstSegmentAssistantMessage,
      ]));
    });

    const postBindUpdater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(postBindUpdater).toBeTypeOf('function');

    const postBindVisibleMessages = postBindUpdater!(previousVisibleMessages);

    expect(postBindVisibleMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=?',
      'assistant:2',
      'user:再+1=?',
      'assistant:3',
    ]);
    expect(postBindVisibleMessages.filter((message) => message.type === 'user' && message.content === '再+1=?')).toHaveLength(1);
    expect(postBindVisibleMessages[2]).toMatchObject({
      type: 'user',
      content: '再+1=?',
      timestamp: '2026-07-24T14:17:47.000Z',
    });
    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBeNull();
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued segment first snapshot applied',
      expect.objectContaining({
        optimisticOverlapMatched: true,
        overlapCount: 1,
        overlapResolvedBy: 'optimistic_content_window',
        preferTailOnOverlap: true,
      }),
    );

    const authoritativeMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: '1+1=?',
        timestamp: '2026-07-24T14:17:05.000Z',
        messageIdentity: { key: 'user|round=1|prompt' },
      },
      {
        type: 'assistant',
        content: '2',
        timestamp: '2026-07-24T14:17:15.000Z',
        messageIdentity: { key: 'assistant|round=1|answer' },
      },
      {
        ...confirmedFollowUpMessage,
        messageIdentity: { key: 'user|round=2|prompt' },
      },
      {
        ...firstSegmentAssistantMessage,
        messageIdentity: { key: 'assistant|round=2|answer' },
      },
    ];

    (opts.setMessages as any).mockClear();

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|transition-optimistic-overlap',
        'snapshot-signature-optimistic-overlap',
        'runtime_continue_authoritative',
      );
      window.clearMessages?.();
      window.updateMessages?.(JSON.stringify(authoritativeMessages));
    });

    const authoritativeUpdater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(authoritativeUpdater).toBeTypeOf('function');

    const authoritativeVisibleMessages = authoritativeUpdater!(postBindVisibleMessages);

    expect(authoritativeVisibleMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=?',
      'assistant:2',
      'user:再+1=?',
      'assistant:3',
    ]);
    expect(authoritativeVisibleMessages.filter((message) => message.type === 'user' && message.content === '再+1=?')).toHaveLength(1);
    expect(authoritativeVisibleMessages[2]).toMatchObject({
      type: 'user',
      content: '再+1=?',
      timestamp: '2026-07-24T14:17:47.000Z',
      messageIdentity: { key: 'user|round=2|prompt' },
    });
  });

  it('silent switch pre-bind tail is consumed after setSessionId and first snapshot restores the full transcript', () => {
    /**
     * 中文注释：
     * 该用例覆盖完整 silent switch 时序：
     * 1. 旧分段仍保持可见；
     * 2. 新分段首个短 tail 先到，前端缓存 pending tail 且不打短历史；
     * 3. setSessionId(new) 绑定后，首帧 snapshot 必须消费 pending tail，恢复完整 transcript。
     */
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: 'segment-old-001' },
      continuationPendingRef: { current: false },
      activeSegmentSessionIdRef: { current: 'segment-old-001' },
      messagesRef: {
        current: [
          { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
          { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
          { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
        ],
      },
    });
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    act(() => {
      (window as any).beginContinuedSegmentTransition?.(JSON.stringify({
        sourceSessionId: 'segment-old-001',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_model',
      }));
      window.updateMessages?.(JSON.stringify([
        { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
      ]));
    });

    const preBindUpdater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(preBindUpdater).toBeTypeOf('function');
    preBindUpdater!([
      { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
      { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
    ]);

    expect(window.__continuedSegmentPendingTailMessages).toEqual([
      { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
    ]);

    act(() => {
      window.setSessionId?.('segment-new-001');
    });

    expect(window.__continuedSegmentHistoryPrefixSessionId).toBe('segment-new-001');
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBe('segment-new-001');
    expect(opts.currentSessionIdRef.current).toBe('segment-new-001');
    (opts.setMessages as any).mockClear();

    act(() => {
      window.updateMessages?.(JSON.stringify([
        { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
        { type: 'assistant', content: '3', timestamp: '2026-07-24T14:18:09.000Z' },
      ]));
    });

    const updater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(updater).toBeTypeOf('function');

    const nextVisibleMessages = updater!([
      { type: 'user', content: '1+1=?', timestamp: '2026-07-24T14:17:05.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-24T14:17:15.000Z' },
      { type: 'user', content: '再+1=?', timestamp: '2026-07-24T14:17:45.000Z' },
    ]);

    expect(nextVisibleMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=?',
      'assistant:2',
      'user:再+1=?',
      'assistant:3',
    ]);
    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBeNull();
  });

  it('completeContinuedSegmentTransition explicitly closes the continued runtime state', () => {
    const opts = createOptions({
      continuationPendingRef: { current: true },
    });
    renderHook(() => useWindowCallbacks(opts));

    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-continued-1';

    act(() => {
      window.completeContinuedSegmentTransition?.('segment-003');
    });

    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
    expect(opts.setCurrentSessionId).toHaveBeenCalledWith('segment-003');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-003');
    expect(opts.setContinuationPending).toHaveBeenCalledWith(false);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith(null);
    expect(opts.currentSessionIdRef.current).toBe('segment-003');
    expect(opts.continuationPendingRef.current).toBe(false);
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBe('segment-003');
    expect(window.__continuedSegmentHistoryPrefixSessionId).toBe('segment-003');
  });

  it('completeContinuedSegmentTransition accepts structured metadata payload and updates logical refs', () => {
    const logicalConversationIdRef = { current: null as string | null };
    const activeSegmentSessionIdRef = { current: null as string | null };
    const opts = createOptions({
      continuationPendingRef: { current: true },
      logicalConversationIdRef,
      activeSegmentSessionIdRef,
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.completeContinuedSegmentTransition?.(JSON.stringify({
        sessionId: 'segment-004',
        logicalConversationId: 'logical-004',
        activeSegmentSessionId: 'segment-004',
        sourceSessionId: 'segment-003',
      }));
    });

    expect(opts.setCurrentSessionId).toHaveBeenCalledWith('segment-004');
    expect(opts.setLogicalConversationId).toHaveBeenCalledWith('logical-004');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-004');
    expect(opts.setParentSegmentSessionId).toHaveBeenCalledWith('segment-003');
    expect(logicalConversationIdRef.current).toBe('logical-004');
    expect(activeSegmentSessionIdRef.current).toBe('segment-004');
    expect(window.__continuedSegmentPendingLogicalConversationId).toBe('logical-004');
  });

  /**
   * 验证 continued 创建失败后，前端回滚入口会恢复旧会话锚点并清理 continued 过渡缓存。
   * 该场景用于约束“失败后不能继续保留 pending/source 状态，否则标签页会永久卡在 not ready”。
   */
  it('abortContinuedSegmentTransition restores previous session anchor and clears continued caches', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: true },
    });
    renderHook(() => useWindowCallbacks(opts));

    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-abort-1';
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: 'old question', timestamp: '2026-07-07T10:00:00.000Z' },
      { type: 'assistant', content: 'old answer', timestamp: '2026-07-07T10:00:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = 'segment-new-002';
    window.__continuedSegmentPendingTailMessages = [
      { type: 'user', content: 'continue', timestamp: '2026-07-07T10:01:00.000Z' },
    ];
    window.__continuedSegmentPendingSourceSessionId = 'segment-old-001';
    window.__continuedSegmentPendingLogicalConversationId = 'logical-001';
    window.__continuedSegmentPendingCreatedAt = 1_751_886_000_000;
    window.__continuedSegmentPendingReason = 'model';
    window.__continuedSegmentAwaitingFirstSessionId = true;

    act(() => {
      (window as any).abortContinuedSegmentTransition?.('segment-old-001');
    });

    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
    expect(opts.setCurrentSessionId).toHaveBeenCalledWith('segment-old-001');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-old-001');
    expect(opts.setContinuationPending).toHaveBeenCalledWith(false);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith(null);
    expect(opts.currentSessionIdRef.current).toBe('segment-old-001');
    expect(opts.continuationPendingRef.current).toBe(false);
    expect(window.__continuedSegmentHistoryPrefixMessages).toBeNull();
    expect(window.__continuedSegmentHistoryPrefixSessionId).toBeNull();
    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
    expect(window.__continuedSegmentPendingSourceSessionId).toBeNull();
    expect(window.__continuedSegmentPendingLogicalConversationId).toBeNull();
    expect(window.__continuedSegmentPendingCreatedAt).toBeNull();
    expect(window.__continuedSegmentPendingReason).toBeNull();
    expect(window.__continuedSegmentAwaitingFirstSessionId).toBe(false);
  });

  it('consumes pending completeContinuedSegmentTransition signal registered before callbacks mount', () => {
    window.__pendingCompleteContinuedSegmentTransitionPayload = 'segment-early-1';

    const opts = createOptions({
      continuationPendingRef: { current: true },
    });
    renderHook(() => useWindowCallbacks(opts));

    expect(window.__pendingCompleteContinuedSegmentTransitionPayload).toBeUndefined();
    expect(window.__pendingCompleteContinuedSegmentTransitionSessionId).toBeUndefined();
    expect(opts.setCurrentSessionId).toHaveBeenCalledWith('segment-early-1');
    expect(opts.setActiveSegmentSessionId).toHaveBeenCalledWith('segment-early-1');
    expect(opts.setContinuationPending).toHaveBeenCalledWith(false);
    expect(opts.setContinuationSourceSessionId).toHaveBeenCalledWith(null);
  });

  it('updateSessionTitle keeps backward compatibility for legacy single-argument title replay', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).updateSessionTitle?.('中东局势怎样了');
    });

    expect(opts.setCustomSessionTitle).toHaveBeenCalledWith('中东局势怎样了');
  });

  it('updateSessionTitle updates matching current session when sessionId and title are provided', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: 'session-123' },
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).updateSessionTitle?.('session-123', '同步github主线修改');
    });

    expect(opts.setCustomSessionTitle).toHaveBeenCalledWith('同步github主线修改');
    expect(opts.updateHistoryTitle).toHaveBeenCalledWith('session-123', '同步github主线修改');
  });

  it('updateSessionTitle ignores mismatched sessionId in two-argument mode', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: 'session-current' },
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).updateSessionTitle?.('session-other', '不应覆盖');
    });

    expect(opts.setCustomSessionTitle).not.toHaveBeenCalled();
    expect(opts.updateHistoryTitle).not.toHaveBeenCalled();
  });

  // ===== updateMessages is blocked during transition =====

  it('updateMessages is silently dropped while __sessionTransitioning is true', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    window.__sessionTransitioning = true;

    const staleMessages: ClaudeMessage[] = [
      { type: 'assistant', content: 'stale content', timestamp: new Date().toISOString() },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(staleMessages));
    });

    // setMessages should NOT be called because guard is active
    expect(opts.setMessages).not.toHaveBeenCalled();
  });

  it('updateMessages works normally after guard is released', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    // Guard is NOT set
    expect(window.__sessionTransitioning).toBe(false);

    const freshMessages: ClaudeMessage[] = [
      { type: 'user', content: 'hello', timestamp: new Date().toISOString() },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(freshMessages));
    });

    // setMessages SHOULD be called
    expect(opts.setMessages).toHaveBeenCalled();
  });

  it('updateMessages keeps the preserved logical conversation prefix when the first continued-segment snapshot only contains the new segment tail', () => {
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: 'segment-002' },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));

    window.__continuedSegmentFirstSnapshotSessionId = 'segment-002';
    (window as any).__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '旧问题', timestamp: '2026-07-02T14:20:00.000Z' },
      { type: 'assistant', content: '旧回答', timestamp: '2026-07-02T14:20:01.000Z' },
    ];
    (window as any).__continuedSegmentHistoryPrefixSessionId = 'segment-002';

    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '继续', timestamp: '2026-07-02T14:23:37.000Z' },
      ]));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const previousMessages: ClaudeMessage[] = [
      { type: 'user', content: '旧问题', timestamp: '2026-07-02T14:20:00.000Z' },
      { type: 'assistant', content: '旧回答', timestamp: '2026-07-02T14:20:01.000Z' },
      { type: 'user', content: '继续', timestamp: '2026-07-02T14:23:37.000Z' },
    ];

    const nextMessages = updater(previousMessages);

    expect(nextMessages).toEqual([
      { type: 'user', content: '旧问题', timestamp: '2026-07-02T14:20:00.000Z' },
      { type: 'assistant', content: '旧回答', timestamp: '2026-07-02T14:20:01.000Z' },
      { type: 'user', content: '继续', timestamp: '2026-07-02T14:23:37.000Z' },
    ]);
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBeNull();
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued segment first snapshot applied',
      expect.objectContaining({
        currentSessionId: 'segment-002',
        continuationPending: false,
      }),
    );
  });

  it('updateMessages keeps replacing only the continued-segment suffix on later snapshots from the same runtime segment', () => {
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: 'segment-002' },
    });
    renderHook(() => useWindowCallbacks(opts));

    (window as any).__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '旧问题', timestamp: '2026-07-02T14:20:00.000Z' },
      { type: 'assistant', content: '旧回答', timestamp: '2026-07-02T14:20:01.000Z' },
    ];
    (window as any).__continuedSegmentHistoryPrefixSessionId = 'segment-002';

    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '继续', timestamp: '2026-07-02T14:23:37.000Z' },
        { type: 'assistant', content: '新的回答', timestamp: '2026-07-02T14:23:39.000Z' },
      ]));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const previousMessages: ClaudeMessage[] = [
      { type: 'user', content: '旧问题', timestamp: '2026-07-02T14:20:00.000Z' },
      { type: 'assistant', content: '旧回答', timestamp: '2026-07-02T14:20:01.000Z' },
      { type: 'user', content: '继续', timestamp: '2026-07-02T14:23:37.000Z' },
    ];

    const nextMessages = updater(previousMessages);

    expect(nextMessages).toEqual([
      { type: 'user', content: '旧问题', timestamp: '2026-07-02T14:20:00.000Z' },
      { type: 'assistant', content: '旧回答', timestamp: '2026-07-02T14:20:01.000Z' },
      { type: 'user', content: '继续', timestamp: '2026-07-02T14:23:37.000Z' },
      { type: 'assistant', content: '新的回答', timestamp: '2026-07-02T14:23:39.000Z' },
    ]);
  });

  it('updateMessages preserves the full 1+1 continued conversation order after the new segment tail arrives', () => {
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: 'segment-002' },
    });
    renderHook(() => useWindowCallbacks(opts));

    // 中文注释：这个用例直接覆盖用户日志中的语义链路：
    // 旧分段已有“1+1=？ -> 2”，新分段首帧只返回“再+1=？ -> 3”时，
    // 前端最终仍必须展示四条完整消息，且顺序不能被 shrink 保护或 assistant 身份复用打乱。
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=？', timestamp: '2026-07-03T10:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-03T10:00:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = 'segment-002';

    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '再+1=？', timestamp: '2026-07-03T10:01:00.000Z' },
        { type: 'assistant', content: '3', timestamp: '2026-07-03T10:01:01.000Z' },
      ]));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([
      { type: 'user', content: '1+1=？', timestamp: '2026-07-03T10:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-03T10:00:01.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-03T10:01:00.000Z' },
    ]);

    expect(nextMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=？',
      'assistant:2',
      'user:再+1=？',
      'assistant:3',
    ]);
  });

  it('updateMessages keeps prefix through guard-timeout tail snapshot and merges after setSessionId binds the segment', () => {
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: null },
      activeSegmentSessionIdRef: { current: null },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));

    // 中文注释：覆盖真实日志链路：guard timeout 后先收到“再+1=？”局部快照，
    // 此时 currentSessionId 和 prefixSessionId 都为空；随后 setSessionId 才回推真实分段 ID。
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=？', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    (window as any).__continuedSegmentPendingSourceSessionId = 'segment-001';
    (window as any).__continuedSegmentPendingLogicalConversationId = 'logical-001';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '再+1=？', timestamp: '2026-07-04T15:20:00.000Z' },
      ]));
    });
    // 中文注释：setMessages 在测试里是 mock，必须手动执行 updater，
    // 才能真正覆盖“首个 tail 先到、sessionId 后绑定”这条 continued fallback 路径上的日志和缓存更新。
    let updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    updater([
      { type: 'user', content: '1+1=？', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-04T15:20:00.000Z' },
    ]);

    expect(window.__continuedSegmentHistoryPrefixMessages).toHaveLength(2);

    act(() => {
      window.setSessionId!('segment-002');
    });
    expect(opts.currentSessionIdRef.current).toBe('segment-002');
    (opts.setMessages as any).mockClear();

    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '再+1=？', timestamp: '2026-07-04T15:20:00.000Z' },
        { type: 'assistant', content: '3', timestamp: '2026-07-04T15:20:01.000Z' },
      ]));
    });

    updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([
      { type: 'user', content: '1+1=？', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-04T15:20:00.000Z' },
    ]);

    expect(nextMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=？',
      'assistant:2',
      'user:再+1=？',
      'assistant:3',
    ]);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued prefix merge skipped',
      expect.objectContaining({
        skipReason: 'awaiting_first_session_id',
      }),
    );
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued prefix cache hit',
      expect.objectContaining({
        currentSessionId: 'segment-002',
      }),
    );
  });

  it('caches the pre-bound continued tail and clears it after a richer bound snapshot arrives', () => {
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
      logicalConversationIdRef: { current: null },
      activeSegmentSessionIdRef: { current: null },
    });
    renderHook(() => useWindowCallbacks(opts));

    // 中文注释：覆盖真实日志中的“continued prefix merge skipped”窗口。
    // 在真实 sessionId 回推前，前端先收到 user-only 尾部快照时，必须先缓存该尾部，
    // 否则后续即便 sessionId 绑定完成，也无法判断下一帧是否是在补齐同一段 runtime tail。
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=？', timestamp: '2026-07-06T15:18:03.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-06T15:18:37.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:19:52.000Z' },
      { type: 'assistant', content: '3', timestamp: '2026-07-06T15:20:08.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:20:38.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingSourceSessionId = 'segment-001';
    window.__continuedSegmentPendingLogicalConversationId = 'logical-001';
    window.__continuedSegmentAwaitingFirstSessionId = true;

    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:20:38.000Z' },
      ]));
    });

    let updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    updater([]);

    expect(window.__continuedSegmentPendingTailMessages).toEqual([
      { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:20:38.000Z' },
    ]);

    act(() => {
      window.setSessionId!('segment-002');
    });
    expect(opts.currentSessionIdRef.current).toBe('segment-002');
    (opts.setMessages as any).mockClear();

    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:20:38.000Z' },
        { type: 'assistant', content: '使用 superpowers:using-superpowers 来确认本轮流程要求。', timestamp: '2026-07-06T15:20:57.000Z' },
      ]));
    });

    updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    let nextMessages = updater([
      { type: 'user', content: '1+1=？', timestamp: '2026-07-06T15:18:03.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-06T15:18:37.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:19:52.000Z' },
      { type: 'assistant', content: '3', timestamp: '2026-07-06T15:20:08.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:20:38.000Z' },
    ]);

    expect(nextMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=？',
      'assistant:2',
      'user:再+1=？',
      'assistant:3',
      'user:再+1=？',
      'assistant:使用 superpowers:using-superpowers 来确认本轮流程要求。',
    ]);

    (opts.setMessages as any).mockClear();
    act(() => {
      window.updateMessages!(JSON.stringify([
        { type: 'user', content: '再+1=？', timestamp: '2026-07-06T15:20:38.000Z' },
        { type: 'assistant', content: '使用 superpowers:using-superpowers 来确认本轮流程要求。', timestamp: '2026-07-06T15:20:57.000Z' },
        { type: 'assistant', content: '4', timestamp: '2026-07-06T15:21:10.000Z' },
      ]));
    });

    updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    nextMessages = updater(nextMessages);

    expect(nextMessages.map((message) => `${message.type}:${message.content}`)).toEqual([
      'user:1+1=？',
      'assistant:2',
      'user:再+1=？',
      'assistant:3',
      'user:再+1=？',
      'assistant:使用 superpowers:using-superpowers 来确认本轮流程要求。',
      'assistant:4',
    ]);
    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
  });

  it('silent switch pre-bind keeps the second same-text follow-up cached until the new segment binds', () => {
    /**
     * 中文注释：
     * 该用例直接对应 2026-07-26 的真实日志链路。
     * 1. 旧前缀里已经有一轮真实的“再+1=？”；
     * 2. 第二次 silent switch 的 pre-bind 阶段又先收到一条新的真实“再+1=？”，但此时还没有新的 sessionId；
     * 3. 前端必须先缓存这条新 tail，而不是把两条同文案真实追问一起提前展示到可见列表；
     * 4. authoritative snapshot 最终仍应保留这两条真实 user turn，本用例只约束 pre-bind 显示策略。
     */
    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: 'segment-first-001' },
      continuationPendingRef: { current: true },
      logicalConversationIdRef: { current: 'logical-001' },
      activeSegmentSessionIdRef: { current: 'segment-first-001' },
      messagesRef: {
        current: [
          { type: 'user', content: '1+1=?', timestamp: '2026-07-26T11:58:00.888Z' },
          { type: 'assistant', content: '2', timestamp: '2026-07-26T11:58:10.000Z' },
          { type: 'system', content: '已切换到 BuyCode-Pro-Codex / gpt-5.4-mini', timestamp: '2026-07-26T11:58:12.000Z' },
          { type: 'user', content: '再+1=？', timestamp: '2026-07-26T11:58:18.614Z' },
          { type: 'assistant', content: '3', timestamp: '2026-07-26T11:58:34.000Z' },
          { type: 'system', content: '已切换到 Codex CLI Login / gpt-5.4-mini', timestamp: '2026-07-26T11:58:40.000Z' },
        ],
      },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    act(() => {
      (window as any).beginContinuedSegmentTransition?.(JSON.stringify({
        sourceSessionId: 'segment-first-001',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_codex_provider',
      }));
      window.updateMessages?.(JSON.stringify([
        { type: 'user', content: '再+1=？', timestamp: '2026-07-26T11:58:58.971Z' },
      ]));
    });

    const preBindUpdater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as ((messages: ClaudeMessage[]) => ClaudeMessage[]) | undefined;
    expect(preBindUpdater).toBeTypeOf('function');

    const previousVisibleMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-26T11:58:00.888Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-26T11:58:10.000Z' },
      { type: 'system', content: '已切换到 BuyCode-Pro-Codex / gpt-5.4-mini', timestamp: '2026-07-26T11:58:12.000Z' },
      { type: 'user', content: '再+1=？', timestamp: '2026-07-26T11:58:18.614Z' },
      { type: 'assistant', content: '3', timestamp: '2026-07-26T11:58:34.000Z' },
      { type: 'system', content: '已切换到 Codex CLI Login / gpt-5.4-mini', timestamp: '2026-07-26T11:58:40.000Z' },
    ] satisfies ClaudeMessage[];

    const preBindVisibleMessages = preBindUpdater!(previousVisibleMessages);

    expect(preBindVisibleMessages).toEqual(previousVisibleMessages);
    expect(preBindVisibleMessages.filter((message) => message.type === 'user' && message.content === '再+1=？')).toHaveLength(1);
    expect(window.__continuedSegmentPendingTailMessages).toEqual([
      { type: 'user', content: '再+1=？', timestamp: '2026-07-26T11:58:58.971Z' },
    ]);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued pending tail cached',
      expect.objectContaining({
        preserveDecision: 'cache_pending_tail',
        pendingTailCount: 1,
        firstMessagePreview: '再+1=？',
        repeatedUserOnlyTailSuppressed: true,
        repeatedUserOnlyTailComparableContent: '再+1=？',
      }),
    );
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued prefix merge skipped',
      expect.objectContaining({
        skipReason: 'prefix_session_id_mismatch',
        preserveDecision: 'cache_pending_tail',
        awaitingFirstSessionId: true,
        pendingTailCount: 1,
        repeatedUserOnlyTailSuppressed: true,
        repeatedUserOnlyTailComparableContent: '再+1=？',
      }),
    );
  });

  it('ignores duplicate history snapshot when restore key and snapshot signature are unchanged', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const restoredMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: 'Paste images',
        timestamp: '2026-06-29T12:00:00.000Z',
        raw: {
          message: {
            content: [
              { type: 'image', src: 'data:image/png;base64,AAAA', mediaType: 'image/png', alt: 'diagram.png' },
              { type: 'text', text: 'Paste images' },
            ],
          },
        } as any,
      },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.('session-001|history_switch|transition-001', 'snapshot-signature-001');
      window.updateMessages!(JSON.stringify(restoredMessages));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    (opts.setMessages as any).mockClear();

    act(() => {
      window.prepareHistoryRestoreSnapshot?.('session-001|history_switch|transition-001', 'snapshot-signature-001');
      window.updateMessages!(JSON.stringify(restoredMessages));
    });

    expect(opts.setMessages).not.toHaveBeenCalled();
  });

  /**
   * continued 首帧如果只收到 permissions/skills 污染消息，前端不应把它缓存成 pending tail。
   * 否则等真实 sessionId 回推后，这类内部残留还会参与 prefix merge，再次污染界面。
   */
  it('does not cache internal permissions and skills message as continued pending tail', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
    });
    renderHook(() => useWindowCallbacks(opts));

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-09T09:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-09T09:00:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingSourceSessionId = 'segment-001';
    window.__continuedSegmentPendingLogicalConversationId = 'logical-001';
    window.__continuedSegmentAwaitingFirstSessionId = true;

    const pollutedPendingTail: ClaudeMessage[] = [
      {
        type: 'user',
        content: 'Filesystem sandboxing defines which files can be read or written.\n\n'
          + '## Skills A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. '
          + '### Skill roots - `r0` = `D:/Users/example/.agents/skills` '
          + '### Available skills - demo (file: r0/demo/SKILL.md) '
          + '### How to use skills - read the skill first.',
        timestamp: '2026-07-09T09:01:00.000Z',
      },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(pollutedPendingTail));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    updater([]);

    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
  });

  /**
   * 验证 continued 过渡态收到“真实用户问题 + permissions/skills 内部尾巴”混合消息时，
   * 前端会只缓存净化后的真实问题，而不会把后台注入残留继续带入后续 prefix merge。
   */
  it('sanitizes mixed continued pending tail down to visible user text', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
    });
    renderHook(() => useWindowCallbacks(opts));

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-09T09:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-09T09:00:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingSourceSessionId = 'segment-001';
    window.__continuedSegmentPendingLogicalConversationId = 'logical-001';
    window.__continuedSegmentAwaitingFirstSessionId = true;

    const mixedPendingTail: ClaudeMessage[] = [
      {
        type: 'user',
        content: '再+1=?\n\n'
          + '<permissions instructions>\n'
          + 'Filesystem sandboxing defines which files can be read or written.\n'
          + '</permissions instructions>\n\n'
          + '## Skills A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. '
          + '### Skill roots - `r0` = `D:/Users/example/.agents/skills` '
          + '### Available skills - demo (file: r0/demo/SKILL.md) '
          + '### How to use skills - read the skill first.',
        timestamp: '2026-07-09T09:01:00.000Z',
        raw: {
          role: 'user',
          content: [
            {
              type: 'text',
              text: '再+1=?\n\n<permissions instructions>\nFilesystem sandboxing defines which files can be read or written.\n</permissions instructions>',
            },
          ],
        } as any,
      },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(mixedPendingTail));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    updater([]);

    expect(window.__continuedSegmentPendingTailMessages).toEqual([
      expect.objectContaining({
        type: 'user',
        content: '再+1=?',
      }),
    ]);
  });

  /**
   * 验证直接前端追加 user 消息时，如果内容净化后为空，则不会把纯后台注入文本渲染到聊天列表。
   */
  it('does not cache pure continuation carryover block as continued pending tail', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: null },
      continuationPendingRef: { current: false },
    });
    renderHook(() => useWindowCallbacks(opts));

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: 'Need follow-up', timestamp: '2026-07-09T09:00:00.000Z' },
      { type: 'assistant', content: 'Sure.', timestamp: '2026-07-09T09:00:01.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingSourceSessionId = 'segment-001';
    window.__continuedSegmentPendingLogicalConversationId = 'logical-001';
    window.__continuedSegmentAwaitingFirstSessionId = true;

    const continuationOnlyTail: ClaudeMessage[] = [
      {
        type: 'user',
        content: '## Conversation Continuation\n'
          + 'You are continuing an existing conversation in a new runtime segment.\n'
          + 'Logical conversation id: logical-001\n'
          + 'Previous segment session id: segment-001\n'
          + 'Recent conversation turns: User: hello\n'
          + "Preserve the user's intent and continue from that context unless the latest request overrides it.\n\n",
        timestamp: '2026-07-09T09:01:00.000Z',
      },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(continuationOnlyTail));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    updater([]);

    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
  });

  it('drops addUserMessage payload when only internal prompt text remains after sanitization', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.addUserMessage?.(
        '<permissions instructions>\n'
        + 'Filesystem sandboxing defines which files can be read or written.\n'
        + '</permissions instructions>\n\n'
        + '## Skills A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. '
        + '### Available skills - demo (file: r0/demo/SKILL.md) '
        + '### How to use skills - read the skill first.',
      );
    });

    expect(opts.setMessages).not.toHaveBeenCalled();
  });

  /**
   * 验证历史追加链路收到混合 user 消息时，会只保留真实用户可见文本，并同步裁剪 raw.content，
   * 避免后续 MessageParser 再从 raw block 把污染文本重新渲染出来。
   */
  it('sanitizes addHistoryMessage user payload and keeps raw content in sync', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const pollutedHistoryMessage: ClaudeMessage = {
      type: 'user',
      content: '继续分析\n\n<permissions instructions>\nFilesystem sandboxing defines which files can be read or written.\n</permissions instructions>',
      timestamp: '2026-07-09T09:10:00.000Z',
      raw: {
        role: 'user',
        content: [
          {
            type: 'text',
            text: '继续分析\n\n<permissions instructions>\nFilesystem sandboxing defines which files can be read or written.\n</permissions instructions>',
          },
        ],
      } as any,
    };

    act(() => {
      window.addHistoryMessage?.(pollutedHistoryMessage);
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([]);

    expect(nextMessages).toEqual([
      expect.objectContaining({
        type: 'user',
        content: '继续分析',
        raw: expect.objectContaining({
          content: [{ type: 'text', text: '继续分析' }],
        }),
      }),
    ]);
  });

  /**
   * 验证 addHistoryMessage 收到 assistant 的 `AGENTS.md instructions` 正常讲解消息时，
   * 历史追加链路不会把它误判成后台残留而隐藏。
   * 这样历史恢复与实时追加两条前端入口在 AGENTS 场景下保持一致。
   */
  it('keeps assistant AGENTS instructions explanation in addHistoryMessage', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const explanation = '# AGENTS.md instructions\n\n'
      + '下面只是文档示例。\n\n'
      + '<INSTRUCTIONS>\n'
      + '- 默认使用中文回复。\n'
      + '</INSTRUCTIONS>\n\n'
      + '<environment_context>\n'
      + '- 这里只是标签示例，不包含 cwd、shell、current_date 等运行时字段。\n'
      + '</environment_context>\n\n'
      + '因此这条消息应继续显示。';
    const historyMessage: ClaudeMessage = {
      type: 'assistant',
      content: explanation,
      timestamp: '2026-07-10T09:12:00.000Z',
      raw: {
        role: 'assistant',
        content: [
          {
            type: 'text',
            text: explanation,
          },
        ],
      } as any,
    };

    act(() => {
      window.addHistoryMessage?.(historyMessage);
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([]);

    expect(nextMessages).toEqual([historyMessage]);
  });

  /**
   * 验证 `processUpdateMessages` 在处理 authoritative/update 快照时，
   * 会直接丢弃 assistant 形态的高置信 permissions/skills 内部残留消息。
   * 这样即使后端某处仍把污染消息放进数组，前端统一净化入口也不会再把它渲染成聊天气泡。
   */
  it('preserves image blocks when sanitizing addHistoryMessage user payload', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const pollutedHistoryMessage: ClaudeMessage = {
      type: 'user',
      content: 'Continue analysis\n\n<permissions instructions>\nFilesystem sandboxing defines which files can be read or written.\n</permissions instructions>',
      timestamp: '2026-07-09T09:10:00.000Z',
      raw: {
        role: 'user',
        content: [
          { type: 'image', src: 'data:image/png;base64,AAAA', mediaType: 'image/png', alt: 'diagram.png' },
          {
            type: 'text',
            text: 'Continue analysis\n\n<permissions instructions>\nFilesystem sandboxing defines which files can be read or written.\n</permissions instructions>',
          },
        ],
      } as any,
    };

    act(() => {
      window.addHistoryMessage?.(pollutedHistoryMessage);
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([]);
    const rawContent = (nextMessages[0].raw as any).content;

    expect(nextMessages[0]).toEqual(expect.objectContaining({
      type: 'user',
      content: 'Continue analysis',
    }));
    expect(rawContent[0]).toEqual(expect.objectContaining({
      type: 'image',
      src: 'data:image/png;base64,AAAA',
    }));
    expect(rawContent[1]).toEqual(expect.objectContaining({
      type: 'text',
      text: 'Continue analysis',
    }));
  });

  it('keeps user message with only image block during updateMessages sanitization', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const imageOnlyMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: '',
        timestamp: '2026-07-10T16:00:00.000Z',
        raw: {
          role: 'user',
          content: [
            { type: 'image', src: 'data:image/png;base64,BBBB', mediaType: 'image/png', alt: 'chart.png' },
          ],
        } as any,
      },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(imageOnlyMessages));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([]);

    expect(nextMessages).toHaveLength(1);
    expect((nextMessages[0].raw as any).content).toEqual([
      expect.objectContaining({
        type: 'image',
        src: 'data:image/png;base64,BBBB',
      }),
    ]);
  });

  it('filters non-user internal residue during updateMessages processing', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const pollutedAssistantMessages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: '<permissions instructions>\n'
          + 'Filesystem sandboxing defines which files can be read or written.\n'
          + '</permissions instructions>\n\n'
          + '## Skills\n\n'
          + '### Skill roots\n\n'
          + '### Available skills\n\n'
          + '- demo (file: r0/demo/SKILL.md)\n\n'
          + '### How to use skills',
        timestamp: '2026-07-10T16:00:00.000Z',
        raw: {
          role: 'assistant',
          message: {
            content: [
              {
                type: 'text',
                text: '<permissions instructions>\n'
                  + 'Filesystem sandboxing defines which files can be read or written.\n'
                  + '</permissions instructions>\n\n'
                  + '## Skills\n\n'
                  + '### Skill roots\n\n'
                  + '### Available skills\n\n'
                  + '- demo (file: r0/demo/SKILL.md)\n\n'
                  + '### How to use skills',
              },
            ],
          },
        } as any,
      },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(pollutedAssistantMessages));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([]);

    expect(nextMessages).toEqual([]);
  });

  /**
   * 验证 authoritative/update 快照中的 assistant 若只是正常讲解 `AGENTS.md instructions` 示例，
   * 前端统一净化入口不会把它误判成内部残留而丢弃。
   * 该断言用于防止后端已放行、前端 updateMessages 兜底层仍继续隐藏消息。
   */
  it('keeps assistant AGENTS instructions explanation during updateMessages processing', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const explanation = '# AGENTS.md instructions\n\n'
      + '下面只是文档示例。\n\n'
      + '<INSTRUCTIONS>\n'
      + '- 默认使用中文回复。\n'
      + '</INSTRUCTIONS>\n\n'
      + '<environment_context>\n'
      + '- 这里只是标签示例，不包含 cwd、shell、current_date 等运行时字段。\n'
      + '</environment_context>\n\n'
      + '因此这条消息应继续显示。';
    const assistantMessages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: explanation,
        timestamp: '2026-07-10T16:05:00.000Z',
        raw: {
          role: 'assistant',
          message: {
            content: [
              {
                type: 'text',
                text: explanation,
              },
            ],
          },
        } as any,
      },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify(assistantMessages));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([]);

    expect(nextMessages).toEqual(assistantMessages);
  });

  it('accepts history snapshot again when snapshot signature changes under the same restore key', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const firstMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: 'Paste images',
        timestamp: '2026-06-29T12:00:00.000Z',
        raw: {
          message: {
            content: [
              { type: 'image', src: 'data:image/png;base64,AAAA', mediaType: 'image/png', alt: 'diagram.png' },
              { type: 'text', text: 'Paste images' },
            ],
          },
        } as any,
      },
    ];
    const secondMessages: ClaudeMessage[] = [
      {
        ...firstMessages[0],
        raw: {
          message: {
            content: [
              { type: 'image', src: 'data:image/png;base64,BBBB', mediaType: 'image/png', alt: 'diagram.png' },
              { type: 'text', text: 'Paste images' },
            ],
          },
        } as any,
      },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.('session-001|history_switch|transition-001', 'snapshot-signature-001');
      window.updateMessages!(JSON.stringify(firstMessages));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);

    act(() => {
      window.prepareHistoryRestoreSnapshot?.('session-001|history_switch|transition-001', 'snapshot-signature-002');
      window.updateMessages!(JSON.stringify(secondMessages));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(2);
  });

  /**
   * 同一个 restore key 与 snapshot signature 只在 restoreKind 也相同的情况下才应视为重复。
   * 这样 continued 首帧 merge 之后，再收到后端 authoritative restore 时，不能被旧去重条件误拦截。
   */
  it('accepts history snapshot again when restore kind changes under the same restore key and signature', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const restoredMessages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: 'continued-refresh:segment-002',
        timestamp: '2026-07-08T12:00:00.000Z',
      },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|transition-001',
        'snapshot-signature-001',
        'single_session',
      );
      window.updateMessages!(JSON.stringify(restoredMessages));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    (opts.setMessages as any).mockClear();

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|transition-001',
        'snapshot-signature-001',
        'runtime_continue_authoritative',
      );
      window.updateMessages!(JSON.stringify(restoredMessages));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
  });

  /**
   * authoritative continued restore 到达后，前端必须直接采用后端完整快照并清空全部 continued 过渡缓存，
   * 不能再保留首帧 prefix merge、pending tail 或旧 first snapshot 状态，否则会再次把旧消息拼回界面。
   */
  it('authoritative continued restore replaces merged local history and clears continued transition caches', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: 'old prefix', timestamp: '2026-07-08T11:59:00.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = 'segment-001';
    window.__continuedSegmentFirstSnapshotSessionId = 'segment-002';
    window.__continuedSegmentPendingTailMessages = [
      { type: 'assistant', content: 'optimistic tail', timestamp: '2026-07-08T11:59:59.000Z' },
    ];
    (window as any).__continuedSegmentPendingSourceSessionId = 'segment-001';
    (window as any).__continuedSegmentPendingLogicalConversationId = 'logical-001';
    (window as any).__continuedSegmentPendingCreatedAt = Date.now();
    (window as any).__continuedSegmentPendingReason = 'provider_switch';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    const authoritativeMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: '真实提问',
        timestamp: '2026-07-08T12:00:00.000Z',
        logicalOrder: 0,
        segmentIndex: 0,
        segmentSessionId: 'segment-001',
        segmentLocalIndex: 0,
        messageIdentity: {
          key: 'user|source=msg-001',
          role: 'user',
          sourceId: 'msg-001',
          segmentSessionId: 'segment-001',
          segmentIndex: 0,
          segmentLocalIndex: 0,
          logicalOrder: 0,
        },
        raw: {
          content: [{ type: 'text', text: '真实提问' }],
          logicalOrder: 0,
          segmentIndex: 0,
          segmentSessionId: 'segment-001',
          segmentLocalIndex: 0,
          messageIdentity: {
            key: 'user|source=msg-001',
            role: 'user',
            sourceId: 'msg-001',
            segmentSessionId: 'segment-001',
            segmentIndex: 0,
            segmentLocalIndex: 0,
            logicalOrder: 0,
          },
        } as any,
      },
      {
        type: 'assistant',
        content: '真实回答',
        timestamp: '2026-07-08T12:00:01.000Z',
        logicalOrder: 1,
        segmentIndex: 0,
        segmentSessionId: 'segment-001',
        segmentLocalIndex: 1,
        messageIdentity: {
          key: 'assistant|segment=segment-001|local=1|content=真实回答',
          role: 'assistant',
          segmentSessionId: 'segment-001',
          segmentIndex: 0,
          segmentLocalIndex: 1,
          logicalOrder: 1,
        },
        raw: {
          content: [{ type: 'text', text: '真实回答' }],
          logicalOrder: 1,
          segmentIndex: 0,
          segmentSessionId: 'segment-001',
          segmentLocalIndex: 1,
          messageIdentity: {
            key: 'assistant|segment=segment-001|local=1|content=真实回答',
            role: 'assistant',
            segmentSessionId: 'segment-001',
            segmentIndex: 0,
            segmentLocalIndex: 1,
            logicalOrder: 1,
          },
        } as any,
      },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|transition-001',
        'snapshot-signature-authoritative',
        'runtime_continue_authoritative',
      );
      window.updateMessages!(JSON.stringify(authoritativeMessages));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const previousMergedMessages: ClaudeMessage[] = [
      { type: 'user', content: 'old prefix', timestamp: '2026-07-08T11:59:00.000Z' },
      { type: 'assistant', content: 'optimistic tail', timestamp: '2026-07-08T11:59:59.000Z' },
      { type: 'assistant', content: 'stale local merge', timestamp: '2026-07-08T12:00:02.000Z' },
    ];

    const nextMessages = updater(previousMergedMessages);

    expect(nextMessages).toEqual(authoritativeMessages);
    expect(window.__continuedSegmentHistoryPrefixMessages).toBeNull();
    expect(window.__continuedSegmentHistoryPrefixSessionId).toBeNull();
    expect(window.__continuedSegmentFirstSnapshotSessionId).toBeNull();
    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
    expect((window as any).__continuedSegmentPendingSourceSessionId).toBeNull();
    expect((window as any).__continuedSegmentPendingLogicalConversationId).toBeNull();
    expect((window as any).__continuedSegmentPendingCreatedAt).toBeNull();
    expect((window as any).__continuedSegmentPendingReason).toBeNull();
    expect((window as any).__continuedSegmentAwaitingFirstSessionId).toBe(false);
  });

  it('authoritative continued restore does not append optimistic user while streaming', () => {
    const opts = createOptions({
      isStreamingRef: { current: true },
      streamingContentRef: { current: '' },
      streamingTurnIdRef: { current: 7 },
    });
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    const authoritativeMessages: ClaudeMessage[] = [
      { type: 'user', content: '净化后的问题', timestamp: '2026-07-08T12:10:00.000Z' },
      { type: 'assistant', content: '净化后的回答', timestamp: '2026-07-08T12:10:01.000Z' },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|transition-002',
        'snapshot-signature-authoritative-streaming',
        'runtime_continue_authoritative',
      );
      window.updateMessages!(JSON.stringify(authoritativeMessages));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const previousMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: '## Conversation Continuation\n\n污染的 optimistic user',
        timestamp: '2026-07-08T12:10:02.000Z',
      },
    ];

    const nextMessages = updater(previousMessages);

    expect(nextMessages).toHaveLength(2);
    expect(nextMessages.map((message) => message.content)).toEqual(['净化后的问题', '净化后的回答']);
  });

  /**
   * authoritative continued restore 到来后，应完全以权威快照替换界面。
   * 即便 pending tail 缓存里残留过内部污染消息，也不能再被拼回 authoritative snapshot。
   */
  it('authoritative continued restore does not stitch filtered internal pending tail back', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: 'old prefix', timestamp: '2026-07-09T10:00:00.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = 'segment-001';
    window.__continuedSegmentFirstSnapshotSessionId = 'segment-002';
    window.__continuedSegmentPendingTailMessages = [
      {
        type: 'user',
        content: 'Filesystem sandboxing defines which files can be read or written.\n\n'
          + '## Skills A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. '
          + '### Skill roots - `r0` = `D:/Users/example/.agents/skills` '
          + '### Available skills - demo (file: r0/demo/SKILL.md) '
          + '### How to use skills - read the skill first.',
        timestamp: '2026-07-09T10:00:10.000Z',
      },
    ];
    (window as any).__continuedSegmentPendingSourceSessionId = 'segment-001';
    (window as any).__continuedSegmentPendingLogicalConversationId = 'logical-001';
    (window as any).__continuedSegmentPendingCreatedAt = Date.now();
    (window as any).__continuedSegmentPendingReason = 'provider_switch';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    const authoritativeMessages: ClaudeMessage[] = [
      { type: 'user', content: '继续分析当前问题', timestamp: '2026-07-09T10:00:20.000Z' },
      { type: 'assistant', content: '好的，先看重复消息根因。', timestamp: '2026-07-09T10:00:21.000Z' },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|transition-009',
        'snapshot-signature-authoritative-filtered-tail',
        'runtime_continue_authoritative',
      );
      window.updateMessages!(JSON.stringify(authoritativeMessages));
    });

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([
      { type: 'user', content: 'old prefix', timestamp: '2026-07-09T10:00:00.000Z' },
      {
        type: 'user',
        content: 'Filesystem sandboxing defines which files can be read or written.',
        timestamp: '2026-07-09T10:00:10.000Z',
      },
    ]);

    expect(nextMessages).toEqual(authoritativeMessages);
    expect(nextMessages.some((message) => message.content?.includes('Filesystem sandboxing'))).toBe(false);
    expect(window.__continuedSegmentPendingTailMessages).toBeNull();
  });

  it('patchMessageUuid updates the latest unresolved user message using raw text fallback', () => {
    const opts = createOptions({
      extractRawBlocks: (raw) => {
        if (!raw || typeof raw !== 'object') return [];
        const content = (raw as any).message?.content;
        return Array.isArray(content) ? content : [];
      },
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.patchMessageUuid?.('Generated attachment summary', 'uuid-123');
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const previous: ClaudeMessage[] = [
      {
        type: 'user',
        content: 'older',
        timestamp: new Date().toISOString(),
        raw: {},
      },
      {
        type: 'user',
        content: '',
        timestamp: new Date().toISOString(),
        raw: {
          message: {
            content: [
              { type: 'attachment', fileName: 'trace.log' },
              { type: 'text', text: 'Generated attachment summary' },
            ],
          },
        } as any,
      },
    ];

    const next = updater(previous);

    expect((next[0].raw as any)?.uuid).toBeUndefined();
    expect((next[1].raw as any)?.uuid).toBe('uuid-123');
  });

  it('patchMessageUuid is ignored while __sessionTransitioning is true', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    window.__sessionTransitioning = true;

    act(() => {
      window.patchMessageUuid?.('hello', 'uuid-guarded');
    });

    expect(opts.setMessages).not.toHaveBeenCalled();
  });

  it('updateStatus does not release an active transition token', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-status';

    act(() => {
      window.updateStatus!('warming runtime');
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBe('transition-status');
    expect(opts.setStatus).toHaveBeenCalledWith('warming runtime');
  });

  // ===== addErrorMessage only shows toast (no status) =====

  it('addErrorMessage shows toast but does not set status', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.addErrorMessage!('Something went wrong');
    });

    expect(opts.addToast).toHaveBeenCalledWith('Something went wrong', 'error');
    expect(opts.setStatus).not.toHaveBeenCalled();
  });



  /**
   * 中文注释：
   * Task 4/5：runtime_continue_authoritative + send-time silent switch 上下文下，
   * clearMessages 可记录 clear 事件，但不能把可见 messages 列表置空。
   */
  it('runtime_continue_authoritative clear keeps visible messages when silent-switch continued context is active', () => {
    const opts = createOptions();
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    window.__continuedSegmentPendingReason = 'send_time_model';
    window.__continuedSegmentPendingSourceSessionId = 'segment-source';
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-23T10:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-23T10:00:01.000Z' },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|null',
        'snapshot-signature-skip-clear',
        'runtime_continue_authoritative',
      );
      window.clearMessages?.();
    });

    expect(opts.setMessages).not.toHaveBeenCalledWith([]);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'clearMessagesBeforeRestore',
      expect.objectContaining({
        restoreKind: 'runtime_continue_authoritative',
        skipVisibleMessageClear: true,
      }),
    );
  });

  /**
   * 中文注释：
   * Task 5：一次发送中即使发生多次 authoritative restore，也不应先把可见列表清成空数组。
   * 最终仍由 updateMessages replace 接管 transcript。
   */
  it('repeated runtime_continue_authoritative restores do not blank the visible transcript', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    window.__continuedSegmentPendingReason = 'send_time_model';
    window.__continuedSegmentPendingSourceSessionId = 'segment-source';
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-23T10:00:00.000Z' },
    ];

    const midSnapshot: ClaudeMessage[] = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-23T10:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-23T10:00:01.000Z' },
      { type: 'user', content: '再+1=?', timestamp: '2026-07-23T10:01:00.000Z' },
    ];
    const finalSnapshot: ClaudeMessage[] = [
      ...midSnapshot,
      { type: 'assistant', content: '3', timestamp: '2026-07-23T10:01:05.000Z' },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.('k1|runtime_continue|t1', 'sig-1', 'runtime_continue_authoritative');
      window.clearMessages?.();
      window.updateMessages?.(JSON.stringify(midSnapshot));
      window.prepareHistoryRestoreSnapshot?.('k1|runtime_continue|t2', 'sig-2', 'runtime_continue_authoritative');
      window.clearMessages?.();
      window.updateMessages?.(JSON.stringify(finalSnapshot));
    });

    const emptyClears = (opts.setMessages as any).mock.calls.filter((call: unknown[]) => {
      return Array.isArray(call[0]) && call[0].length === 0;
    });
    expect(emptyClears).toHaveLength(0);

    const lastUpdater = (opts.setMessages as any).mock.calls.at(-1)[0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    expect(lastUpdater([{ type: 'user', content: 'stale', timestamp: 'x' }])).toEqual(finalSnapshot);
  });

  /**
   * 中文注释：
   * Task 2：silent switch 预热后 completeContinuedSegmentTransition 不应再出现 missing prefix cache。
   */
  it('completeContinuedSegmentTransition does not report missing prefix cache after silent begin', () => {
    const opts = createOptions({
      currentSessionIdRef: { current: 'segment-source' },
      messagesRef: {
        current: [
          { type: 'user', content: '1+1=?', timestamp: '2026-07-23T10:00:00.000Z' },
        ],
      },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      (window as any).beginContinuedSegmentTransition?.(JSON.stringify({
        sourceSessionId: 'segment-source',
        logicalConversationId: 'logical-001',
        switchReason: 'send_time_model',
      }));
      window.setSessionId?.('segment-new');
      window.completeContinuedSegmentTransition?.(JSON.stringify({
        sessionId: 'segment-new',
        logicalConversationId: 'logical-001',
        activeSegmentSessionId: 'segment-new',
        sourceSessionId: 'segment-source',
        parentSegmentSessionId: 'segment-source',
      }));
    });

    const missingPrefixLogs = diagnosticSpy.mock.calls.filter((call) => (
      call[1] === 'window.completeContinuedSegmentTransition missing prefix cache'
    ));
    expect(missingPrefixLogs).toHaveLength(0);
    expect(window.__continuedSegmentHistoryPrefixMessages).toHaveLength(1);
  });

  // ===== clearMessages resets all transient UI state =====

  it('clearMessages resets streaming refs, loading, thinking, and status', () => {
    const isStreamingRef = { current: true };
    const streamingContentRef = { current: 'partial content' };
    const streamingMessageIndexRef = { current: 3 };
    const opts = createOptions({
      isStreamingRef,
      streamingContentRef,
      streamingMessageIndexRef,
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.clearMessages!();
    });

    expect(opts.setMessages).toHaveBeenCalledWith([]);
    expect(opts.clearToasts).toHaveBeenCalled();
    expect(opts.setStatus).toHaveBeenCalledWith('');
    expect(opts.setLoading).toHaveBeenCalledWith(false);
    expect(opts.setIsThinking).toHaveBeenCalledWith(false);
    expect(opts.setStreamingActive).toHaveBeenCalledWith(false);
    expect(isStreamingRef.current).toBe(false);
    expect(streamingContentRef.current).toBe('');
    expect(streamingMessageIndexRef.current).toBe(-1);
  });

  /**
   * 后端历史恢复链路的顺序固定为 `prepareHistoryRestoreSnapshot -> clearMessages -> updateMessages`。
   * 这里要验证 clearMessages 不会提前把待消费的 restore 元数据清掉，否则下一次 updateMessages
   * 就无法进入 authoritative replace 分支，旧前缀和脏 tail 会重新混回列表。
   */
  it('clearMessages preserves prepared history restore context for the immediately following updateMessages', () => {
    const opts = createOptions();
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: 'stale prefix', timestamp: '2026-07-15T09:59:00.000Z' },
    ];
    window.__continuedSegmentHistoryPrefixSessionId = 'segment-001';
    window.__continuedSegmentFirstSnapshotSessionId = 'segment-002';

    const authoritativeMessages: ClaudeMessage[] = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-15T10:00:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-15T10:00:01.000Z' },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-001|runtime_continue|transition-015',
        'snapshot-signature-after-clear',
        'runtime_continue_authoritative',
      );
      window.clearMessages?.();
      window.updateMessages?.(JSON.stringify(authoritativeMessages));
    });

    expect(window.__preparedHistoryRestoreKey).toBeNull();
    expect(window.__preparedHistoryRestoreSignature).toBeNull();
    expect(window.__preparedHistoryRestoreKind).toBeNull();
    expect(window.__lastAppliedHistoryRestoreKey).toBe('logical-001|runtime_continue|transition-015');
    expect(window.__lastAppliedHistoryRestoreSignature).toBe('snapshot-signature-after-clear');
    expect(window.__lastAppliedHistoryRestoreKind).toBe('runtime_continue_authoritative');

    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'apply snapshot',
      expect.objectContaining({
        restoreKey: 'logical-001|runtime_continue|transition-015',
        restoreKind: 'runtime_continue_authoritative',
      }),
    );

    const updater = (opts.setMessages as any).mock.calls.at(-1)[0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([
      { type: 'user', content: 'stale prefix', timestamp: '2026-07-15T09:59:00.000Z' },
    ]);

    expect(nextMessages).toEqual(authoritativeMessages);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'authoritative snapshot replaced messages',
      expect.objectContaining({
        restoreKey: 'logical-001|runtime_continue|transition-015',
        restoreKind: 'runtime_continue_authoritative',
      }),
    );
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'CodexRuntime.Frontend',
      'continued transition cache cleared',
      expect.objectContaining({
        cleanupReason: 'authoritative_restore_replace',
      }),
    );
  });

  it('authoritative restore diagnostics keep a consistent restoreRequestKey through prepare, clear, apply, update, and replace', () => {
    /**
     * 中文注释：
     * 该用例同时覆盖 Task 5 Step 4 与 Step 6。
     * 历史恢复日志链路必须至少包含：
     * `prepared snapshot context -> clearMessagesBeforeRestore -> apply snapshot -> updateMessagesForRestore -> authoritative snapshot replaced messages`
     * 且结构化诊断字段要能通过同一个 restoreRequestKey 串联。
     */
    const opts = createOptions();
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    const restoreRequestKey = 'logical-restore-002|runtime_continue|transition-009';
    // 中文注释：构造 silent-switch-continued 上下文，使 clearMessages 仅清理 transient 而不清空可见列表。
    window.__continuedSegmentPendingReason = 'send_time_model';
    window.__continuedSegmentPendingSourceSessionId = 'segment-001';
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: 'old visible history', timestamp: '2026-07-16T10:58:00.000Z' },
    ];
    const authoritativeMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: '再+1=？',
        timestamp: '2026-07-16T11:00:00.000Z',
        messageIdentity: { key: 'user|round=2|follow-up' },
      },
      {
        type: 'assistant',
        content: '4',
        timestamp: '2026-07-16T11:00:01.000Z',
        messageIdentity: { key: 'assistant|round=2|answer' },
      },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        restoreRequestKey,
        'snapshot-signature-chain',
        'runtime_continue_authoritative',
      );
      window.clearMessages?.();
      window.updateMessages?.(JSON.stringify(authoritativeMessages));
    });

    const updater = (opts.setMessages as any).mock.calls.at(-1)[0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    updater([
      {
        type: 'user',
        content: 'stale old round',
        timestamp: '2026-07-16T10:59:00.000Z',
      },
    ]);

    const diagnosticCalls = diagnosticSpy.mock.calls.map(([scope, event, payload]) => ({
      scope,
      event,
      payload,
    }));
    const findEventIndex = (eventName: string) => diagnosticCalls.findIndex((call) => call.event === eventName);

    expect(findEventIndex('prepared snapshot context')).toBeGreaterThanOrEqual(0);
    expect(findEventIndex('clearMessagesBeforeRestore')).toBeGreaterThanOrEqual(0);
    expect(findEventIndex('apply snapshot')).toBeGreaterThanOrEqual(0);
    expect(findEventIndex('updateMessagesForRestore')).toBeGreaterThanOrEqual(0);
    expect(findEventIndex('authoritative snapshot replaced messages')).toBeGreaterThanOrEqual(0);
    expect(findEventIndex('prepared snapshot context')).toBeLessThan(findEventIndex('clearMessagesBeforeRestore'));
    expect(findEventIndex('clearMessagesBeforeRestore')).toBeLessThan(findEventIndex('apply snapshot'));
    expect(findEventIndex('apply snapshot')).toBeLessThan(findEventIndex('updateMessagesForRestore'));
    expect(findEventIndex('updateMessagesForRestore')).toBeLessThan(findEventIndex('authoritative snapshot replaced messages'));

    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'clearMessagesBeforeRestore',
      expect.objectContaining({
        restoreRequestKey,
        skipVisibleMessageClear: true,
      }),
    );
    // 中文注释：允许记录 clear 事件，但不能把可见消息列表置空；最终由 updateMessages replace 接管。
    expect(opts.setMessages).not.toHaveBeenCalledWith([]);
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'applyHistoryRestoreSnapshot',
      expect.objectContaining({
        restoreRequestKey,
      }),
    );
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'updateMessagesForRestore',
      expect.objectContaining({
        restoreRequestKey,
        restoreKind: 'runtime_continue_authoritative',
      }),
    );
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'authoritativeSnapshotReplacedMessages',
      expect.objectContaining({
        restoreRequestKey,
      }),
    );
  });

  it('authoritative restore emits a lightweight full transcript message dump after replace', () => {
    /**
     * 中文注释：
     * 该用例验证 authoritative replace 完成后，会额外输出一份轻量 message dump。
     * dump 必须包含 index / key / type / timestamp / contentPreview / messageIdentity.key，
     * 这样后续排查 `scroll.log` 与真实 transcript 不一致时，能直接在 idea.log 对账真实消息数组。
     */
    const opts = createOptions();
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    const restoreRequestKey = 'logical-dump-001|runtime_continue|transition-021';
    const authoritativeMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: '再+1=？',
        timestamp: '2026-07-16T19:44:00.000Z',
        messageIdentity: { key: 'user|round=2|follow-up' },
      },
      {
        type: 'assistant',
        content: '4',
        timestamp: '2026-07-16T19:44:01.000Z',
        messageIdentity: { key: 'assistant|round=2|answer' },
      },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        restoreRequestKey,
        'snapshot-signature-dump',
        'runtime_continue_authoritative',
      );
      window.clearMessages?.();
      window.updateMessages?.(JSON.stringify(authoritativeMessages));
    });

    const updater = (opts.setMessages as any).mock.calls.at(-1)[0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    updater([
      {
        type: 'user',
        content: 'old stale message',
        timestamp: '2026-07-16T19:43:00.000Z',
      },
    ]);

    expect(diagnosticSpy).toHaveBeenCalledWith(
      'HistoryRestore.Frontend',
      'authoritative snapshot message dump',
      expect.objectContaining({
        restoreRequestKey,
        snapshotKind: 'full_transcript_snapshot',
        transcriptSource: 'react_messages_state',
        messageCount: 2,
        messageDump: [
          expect.objectContaining({
            index: 0,
            key: 'user|round=2|follow-up',
            type: 'user',
            timestamp: '2026-07-16T19:44:00.000Z',
            messageIdentityKey: 'user|round=2|follow-up',
            contentPreview: '再+1=？',
          }),
          expect.objectContaining({
            index: 1,
            key: 'assistant|round=2|answer',
            type: 'assistant',
            timestamp: '2026-07-16T19:44:01.000Z',
            messageIdentityKey: 'assistant|round=2|answer',
            contentPreview: '4',
          }),
        ],
      }),
    );
  });

  it('exports the full frontend transcript snapshot from messages state instead of the visible scroll window', () => {
    /**
     * 中文注释：
     * 该用例验证诊断导出入口读取的是前端真实 message array，而不是 MessageList 的可视窗口。
     * 这里故意放入 16 条消息，确保导出结果会标记“界面上本应存在折叠窗口，但导出仍绕过它输出完整 transcript”。
     */
    const transcriptMessages: ClaudeMessage[] = Array.from({ length: 16 }, (_, index) => ({
      type: index % 2 === 0 ? 'user' : 'assistant',
      content: `message-${index + 1}`,
      timestamp: `2026-07-16T19:${String(index).padStart(2, '0')}:00.000Z`,
      messageIdentity: { key: `message|${index + 1}` },
    }));
    const opts = createOptions({
      messagesRef: { current: transcriptMessages },
      currentProviderRef: { current: 'codex' },
      currentSessionIdRef: { current: 'segment-12345678' },
      logicalConversationIdRef: { current: 'logical-frontend-001' },
      activeSegmentSessionIdRef: { current: 'segment-12345678' },
    });
    const diagnosticSpy = vi.spyOn(debugModule, 'emitFrontendDiagnosticLog').mockImplementation(() => {});
    const downloadSpy = vi.mocked(exportMarkdownModule.downloadJSON);
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.exportFrontendTranscriptDiagnosticSnapshot?.(JSON.stringify({
        reason: 'manual_scroll_log_compare',
      }));
    });

    expect(downloadSpy).toHaveBeenCalledTimes(1);
    const [exportContent, filename] = downloadSpy.mock.calls[0] as [string, string];
    const snapshot = JSON.parse(exportContent);

    expect(filename).toContain('frontend-transcript-');
    expect(snapshot).toMatchObject({
      exportKind: 'frontend_transcript_diagnostic',
      snapshotKind: 'full_transcript_snapshot',
      transcriptSource: 'react_messages_state',
      provider: 'codex',
      sessionId: 'segment-12345678',
      logicalConversationId: 'logical-frontend-001',
      activeSegmentSessionId: 'segment-12345678',
      messageCount: 16,
      messageListWindowInfo: {
        visibleWindowSize: 15,
        wouldCollapseEarlierMessages: true,
        bypassedForExport: true,
      },
      messages: transcriptMessages,
    });
    expect(snapshot.note).toContain('not limited by the MessageList visible window');
    expect(diagnosticSpy).toHaveBeenCalledWith(
      'TranscriptDiagnostics.Frontend',
      'export full transcript snapshot',
      expect.objectContaining({
        reason: 'manual_scroll_log_compare',
        snapshotKind: 'full_transcript_snapshot',
        transcriptSource: 'react_messages_state',
        provider: 'codex',
        sessionId: 'segment-12345678',
        logicalConversationId: 'logical-frontend-001',
        activeSegmentSessionId: 'segment-12345678',
        messageCount: 16,
        messageDump: expect.any(Array),
      }),
    );
  });

  it('authoritative restore replaces repeated follow-up messages with the new round timestamp and identity', () => {
    /**
     * 中文注释：
     * 该用例覆盖 Task 6 Step 4 中“续接后 authoritative replace / 时间戳不再错挂”。
     * 即使旧列表里已经有一条同文案追问，authoritative 快照也必须完整替换为新轮次的 timestamp 与 messageIdentity。
     */
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));
    (opts.setMessages as any).mockClear();

    const authoritativeMessages: ClaudeMessage[] = [
      {
        type: 'user',
        content: '再+1=？',
        timestamp: '2026-07-16T11:05:00.000Z',
        messageIdentity: { key: 'user|round=2|follow-up' },
      },
      {
        type: 'assistant',
        content: '4',
        timestamp: '2026-07-16T11:05:01.000Z',
        messageIdentity: { key: 'assistant|round=2|answer' },
      },
    ];

    act(() => {
      window.prepareHistoryRestoreSnapshot?.(
        'logical-restore-003|runtime_continue|transition-010',
        'snapshot-signature-replace',
        'runtime_continue_authoritative',
      );
      window.updateMessages?.(JSON.stringify(authoritativeMessages));
    });

    const updater = (opts.setMessages as any).mock.calls.at(-1)[0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater([
      {
        type: 'user',
        content: '再+1=？',
        timestamp: '2026-07-16T11:00:00.000Z',
        messageIdentity: { key: 'user|round=1|follow-up' },
      },
      {
        type: 'assistant',
        content: '3',
        timestamp: '2026-07-16T11:00:01.000Z',
        messageIdentity: { key: 'assistant|round=1|answer' },
      },
    ]);

    expect(nextMessages).toEqual(authoritativeMessages);
    expect(nextMessages[0].timestamp).toBe('2026-07-16T11:05:00.000Z');
    expect(nextMessages[0].messageIdentity?.key).toBe('user|round=2|follow-up');
    expect(nextMessages[1].timestamp).toBe('2026-07-16T11:05:01.000Z');
    expect(nextMessages[1].messageIdentity?.key).toBe('assistant|round=2|answer');
  });

  // ===== clearMessages resets turn tracking refs =====

  it('clearMessages resets streamingTurnIdRef but preserves turnIdCounterRef', () => {
    const streamingTurnIdRef = { current: 5 };
    const turnIdCounterRef = { current: 10 };
    const opts = createOptions({
      streamingTurnIdRef,
      turnIdCounterRef,
    });
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.clearMessages!();
    });

    // Turn ID should be reset to -1 (no active streaming turn)
    expect(streamingTurnIdRef.current).toBe(-1);
    // Counter stays monotonically increasing (NOT reset) so React keys stay unique across sessions
    expect(turnIdCounterRef.current).toBe(10);
  });

  // ===== Full failure scenario: load history fails, guard is released, new messages work =====

  it('full flow: history load failure releases guard so new messages can arrive', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    // Step 1: Frontend begins session transition
    window.__sessionTransitioning = true;

    // Step 2: During transition, stale messages are blocked
    act(() => {
      window.updateMessages!(JSON.stringify([{ type: 'assistant', content: 'stale' }]));
    });
    expect(opts.setMessages).not.toHaveBeenCalled();

    // Step 3: Java calls historyLoadComplete (failure path also calls this before addErrorMessage)
    act(() => {
      window.historyLoadComplete!();
    });
    expect(window.__sessionTransitioning).toBe(false);

    // Step 4: Java calls addErrorMessage
    act(() => {
      window.addErrorMessage!('Failed to load session: network error');
    });
    expect(opts.addToast).toHaveBeenCalledWith('Failed to load session: network error', 'error');

    // Step 5: After guard release, new messages work
    act(() => {
      window.updateMessages!(
        JSON.stringify([{ type: 'user', content: 'new message' }])
      );
    });
    expect(opts.setMessages).toHaveBeenCalled();
  });

  it('ignores stale updateMessages snapshots that arrive after stream end', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.onStreamStart?.();
    });

    act(() => {
      window.onStreamEnd?.('10');
    });

    opts.isStreamingRef.current = false;
    (opts.setMessages as any).mockClear();

    act(() => {
      window.updateMessages!(
        JSON.stringify([{ type: 'assistant', content: 'stale backlog', timestamp: new Date().toISOString() }]),
        '9',
      );
    });

    expect(opts.setMessages).not.toHaveBeenCalled();

    act(() => {
      window.updateMessages!(
        JSON.stringify([{ type: 'assistant', content: 'final snapshot', timestamp: new Date().toISOString() }]),
        '10',
      );
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
  });

  it('accepts streaming updateMessages when assistant raw blocks gain spawn_agent tool_use', () => {
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
    vi.stubGlobal('cancelAnimationFrame', vi.fn());

    const patchAssistantForStreaming = vi.fn((msg: ClaudeMessage) => ({
      ...msg,
      isStreaming: true,
    }));
    const extractRawBlocks = (raw: unknown) => {
      if (!raw || typeof raw !== 'object') return [];
      const rawObj = raw as { content?: unknown; message?: { content?: unknown } };
      const blocks = rawObj.content ?? rawObj.message?.content;
      return Array.isArray(blocks) ? blocks : [];
    };

    const opts = createOptions({
      currentProviderRef: { current: 'codex' },
      isStreamingRef: { current: true },
      streamingTurnIdRef: { current: 7 },
      patchAssistantForStreaming,
      extractRawBlocks,
    });
    renderHook(() => useWindowCallbacks(opts));

    const previousMessages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: 'Working',
        timestamp: '2026-04-02T10:00:00.000Z',
        __turnId: 7,
        isStreaming: true,
        raw: {
          message: {
            content: [{ type: 'text', text: 'Working' }],
          },
        } as any,
      },
    ];

    act(() => {
      window.updateMessages!(JSON.stringify([
        {
          type: 'assistant',
          content: 'Working',
          timestamp: '2026-04-02T10:00:00.000Z',
          raw: {
            message: {
              content: [
                { type: 'tool_use', id: 'spawn-1', name: 'spawn_agent', input: { agent_type: 'Explore', message: 'Inspect renderer' } },
                { type: 'text', text: 'Working' },
              ],
            },
          },
        },
      ]));
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater(previousMessages);

    expect(nextMessages).not.toBe(previousMessages);
    expect(patchAssistantForStreaming).toHaveBeenCalled();
    expect((nextMessages[0].raw as any).message.content[0]).toMatchObject({
      type: 'tool_use',
      name: 'spawn_agent',
      id: 'spawn-1',
    });
    expect(nextMessages[0].__turnId).toBe(7);
  });

  it('reuses replayed in-progress assistant when stream restarts after webview reload', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    act(() => {
      window.onStreamStart?.();
    });

    expect(opts.setMessages).toHaveBeenCalledTimes(1);
    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const replayedMessages: ClaudeMessage[] = [
      { type: 'user', content: 'question', timestamp: '2026-04-27T00:00:00.000Z' },
      { type: 'assistant', content: 'partial answer', timestamp: '2026-04-27T00:00:01.000Z' },
    ];

    const nextMessages = updater(replayedMessages);

    expect(nextMessages).toHaveLength(2);
    expect(nextMessages[1]).toMatchObject({
      type: 'assistant',
      content: 'partial answer',
      isStreaming: true,
      __turnId: 1,
    });
  });

  it('onSubagentHistoryLoaded skips updates only when history payload is truly unchanged', () => {
    const opts = createOptions();
    renderHook(() => useWindowCallbacks(opts));

    const firstPayload = {
      success: true,
      toolUseId: 'task-1',
      sessionId: 'session-1',
      messages: [{ type: 'assistant', content: [{ type: 'text', text: 'draft result' }] }],
    };

    act(() => {
      window.onSubagentHistoryLoaded?.(JSON.stringify(firstPayload));
    });

    expect(opts.setSubagentHistories).toHaveBeenCalledTimes(1);
    const firstUpdater = (opts.setSubagentHistories as any).mock.calls[0][0] as (prev: Record<string, unknown>) => Record<string, unknown>;
    const initialState = firstUpdater({});

    act(() => {
      window.onSubagentHistoryLoaded?.(JSON.stringify(firstPayload));
    });

    const secondUpdater = (opts.setSubagentHistories as any).mock.calls[1][0] as (prev: Record<string, unknown>) => Record<string, unknown>;
    expect(secondUpdater(initialState)).toBe(initialState);

    act(() => {
      window.onSubagentHistoryLoaded?.(JSON.stringify({
        ...firstPayload,
        messages: [{ type: 'assistant', content: [{ type: 'text', text: 'final result' }] }],
      }));
    });

    const thirdUpdater = (opts.setSubagentHistories as any).mock.calls[2][0] as (prev: Record<string, any>) => Record<string, any>;
    const updatedState = thirdUpdater(initialState);
    expect(updatedState).not.toBe(initialState);
    expect(updatedState['task-1'].messages[0].content[0].text).toBe('final result');
  });

  // ===== onStreamEnd idempotency (dual-path delivery) =====

  describe('onStreamEnd idempotency', () => {
    it('second onStreamEnd for same turn is ignored', () => {
      const opts = createOptions();
      // Simulate streaming state
      opts.streamingTurnIdRef.current = 5;
      opts.isStreamingRef.current = true;
      opts.streamingMessageIndexRef.current = 0;
      opts.turnIdCounterRef.current = 5;

      renderHook(() => useWindowCallbacks(opts));

      // Simulate onStreamStart to set up streaming state
      act(() => {
        window.onStreamStart!();
      });

      const turnId = opts.streamingTurnIdRef.current;

      // First onStreamEnd — should process
      act(() => {
        window.onStreamEnd!('10');
      });
      expect(window.__streamEndProcessedTurnId).toBe(turnId);

      // Record call count after first onStreamEnd
      const callsAfterFirstEnd = (opts.setStreamingActive as any).mock.calls.length;

      // Second onStreamEnd with same turn — should be no-op
      act(() => {
        window.onStreamEnd!('10');
      });

      // setStreamingActive should not have been called again (idempotency)
      expect((opts.setStreamingActive as any).mock.calls.length).toBe(callsAfterFirstEnd);
    });

    it('onStreamStart clears __streamEndProcessedTurnId for next turn', () => {
      const opts = createOptions();
      renderHook(() => useWindowCallbacks(opts));

      // Simulate a completed turn
      window.__streamEndProcessedTurnId = 3;

      // New turn starts
      act(() => {
        window.onStreamStart!();
      });

      expect(window.__streamEndProcessedTurnId).toBeUndefined();
    });
  });

  describe('streaming completed semantics', () => {
    it('showLoading(false) clears non-streaming loading UI without reporting completed or synthesizing stream end cleanup', () => {
      /**
       * 中文注释：这条回归用例直接对应 send-time 失败链路的前端契约。
       * 后端在真正进入流式阶段前就失败时，只会补发 `showLoading(false)`，不会补发 `onStreamEnd()`；
       * 因此前端必须在非 streaming 场景下仅关闭 loading，并且不能顺带制造 completed / stream-end 语义。
       */
      const opts = createOptions({
        isStreamingRef: { current: false },
        streamingMessageIndexRef: { current: -1 },
      });
      renderHook(() => useWindowCallbacks(opts));

      act(() => {
        window.showLoading?.(false);
      });

      expect(window.sendToJava).not.toHaveBeenCalledWith(
        'tab_status_changed:{"status":"completed"}',
      );
      expect(opts.setLoading).toHaveBeenCalled();
      const loadingUpdater = (opts.setLoading as any).mock.calls.at(-1)?.[0] as
        | ((prev: boolean) => boolean)
        | undefined;
      expect(loadingUpdater).toBeTypeOf('function');
      expect(loadingUpdater?.(true)).toBe(false);

      expect(opts.setLoadingStartTime).toHaveBeenCalled();
      const startTimeUpdater = (opts.setLoadingStartTime as any).mock.calls.at(-1)?.[0] as
        | ((prev: number | null) => number | null)
        | undefined;
      expect(startTimeUpdater).toBeTypeOf('function');
      expect(startTimeUpdater?.(12345)).toBe(null);

      expect(opts.setStreamingActive).not.toHaveBeenCalledWith(false);
      expect(opts.setIsThinking).not.toHaveBeenCalledWith(false);
      expect(opts.isStreamingRef.current).toBe(false);
    });

    it('onStreamEnd only closes local streaming UI and no longer reports completed bridge event', () => {
      const opts = createOptions({
        isStreamingRef: { current: true },
        streamingMessageIndexRef: { current: 0 },
      });
      renderHook(() => useWindowCallbacks(opts));

      const previousMessages: ClaudeMessage[] = [
        {
          type: 'assistant',
          content: 'partial answer',
          timestamp: '2026-05-25T10:00:00.000Z',
          isStreaming: true,
          __turnId: 1,
        },
      ];

      act(() => {
        window.onStreamStart?.();
        window.onContentDelta?.(' final');
        // 中文注释：测试环境里的 setMessages 是 mock，不会真正执行 onStreamStart 的 updater，
        // 这里手动补齐当前流式消息索引，确保 onStreamEnd 走到真实收口分支。
        opts.streamingMessageIndexRef.current = 0;
        window.onStreamEnd?.('12');
      });

      expect(window.sendToJava).not.toHaveBeenCalledWith(
        'tab_status_changed:{"status":"completed"}',
      );
      expect(opts.setStreamingActive).toHaveBeenCalledWith(false);
      expect(opts.setLoading).toHaveBeenCalledWith(false);
      expect(opts.setLoadingStartTime).toHaveBeenCalledWith(null);
      expect(opts.setIsThinking).toHaveBeenCalledWith(false);

      const updater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as
        | ((messages: ClaudeMessage[]) => ClaudeMessage[])
        | undefined;
      expect(updater).toBeTypeOf('function');
      const nextMessages = updater!(previousMessages);
      expect(nextMessages[0]).toMatchObject({
        type: 'assistant',
        content: ' final',
        isStreaming: false,
        __turnId: 1,
      });
    });

    it('stall watchdog timeout only performs local recovery and does not report completed bridge event', () => {
      vi.useFakeTimers();
      const opts = createOptions();
      renderHook(() => useWindowCallbacks(opts));

      act(() => {
        window.onStreamStart?.();
      });

      expect(opts.isStreamingRef.current).toBe(true);

      act(() => {
        vi.advanceTimersByTime(65_000);
      });

      expect(window.sendToJava).not.toHaveBeenCalledWith(
        'tab_status_changed:{"status":"completed"}',
      );
      expect(opts.setStreamingActive).toHaveBeenCalledWith(false);
      expect(opts.setLoading).toHaveBeenCalledWith(false);
      expect(opts.setLoadingStartTime).toHaveBeenCalledWith(null);
      expect(opts.setIsThinking).toHaveBeenCalledWith(false);
      expect(opts.isStreamingRef.current).toBe(false);

      vi.useRealTimers();
    });

    it('onStreamEnd recovers missing tool_result snapshot even when no assistant index is available', () => {
      const opts = createOptions({
        isStreamingRef: { current: true },
        streamingMessageIndexRef: { current: -1 },
      });
      renderHook(() => useWindowCallbacks(opts));

      const toolResultRaw = {
        message: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: 'tool-1',
              content: 'done',
            },
          ],
        },
      };

      act(() => {
        window.onStreamStart?.();
        window.__pendingUpdateJson = JSON.stringify([
          {
            type: 'assistant',
            content: 'final answer',
            raw: {
              message: {
                content: [{ type: 'text', text: 'final answer' }],
              },
            },
          },
          {
            type: 'user',
            content: '[tool_result]',
            raw: toolResultRaw,
          },
        ]);
        window.onStreamEnd?.('12');
      });

      const updater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as
        | ((messages: ClaudeMessage[]) => ClaudeMessage[])
        | undefined;
      expect(updater).toBeTypeOf('function');

      const previousMessages: ClaudeMessage[] = [
        {
          type: 'assistant',
          content: 'calling tool',
          timestamp: '2026-06-27T09:00:00.000Z',
          raw: {
            message: {
              content: [{ type: 'tool_use', id: 'tool-1', name: 'Read' }],
            },
          } as any,
        },
      ];
      const nextMessages = updater!(previousMessages);

      expect(nextMessages).toHaveLength(2);
      expect(nextMessages[1]).toMatchObject({
        type: 'user',
        content: '[tool_result]',
        raw: toolResultRaw,
      });
      // 中文注释：这里先收窄 timestamp 的类型，再校验恢复出来的工具结果消息时间戳仍是合法 ISO 字符串，
      // 避免 tsconfig.test 在严格空值检查下把可选字段直接传给 Date 构造函数时报错。
      const recoveredTimestamp = nextMessages[1].timestamp;
      expect(typeof recoveredTimestamp).toBe('string');
      expect(new Date(recoveredTimestamp as string).toISOString()).toBe(recoveredTimestamp);
    });

    it('onStreamEnd deduplicates tool_result recovery against existing messages and within snapshot', () => {
      const opts = createOptions({
        isStreamingRef: { current: true },
        streamingMessageIndexRef: { current: 0 },
      });
      renderHook(() => useWindowCallbacks(opts));

      const sharedToolResultRaw = {
        message: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: 'tool-1',
              content: 'done',
            },
          ],
        },
      };
      const secondToolResultRaw = {
        message: {
          content: [
            {
              type: 'tool_result',
              tool_use_id: 'tool-2',
              content: 'done-2',
            },
          ],
        },
      };

      act(() => {
        window.onStreamStart?.();
        window.__pendingUpdateJson = JSON.stringify([
          {
            type: 'assistant',
            content: 'final answer',
            raw: {
              message: {
                content: [{ type: 'text', text: 'final answer' }],
              },
            },
          },
          {
            type: 'user',
            content: '   [tool_result]   ',
            raw: sharedToolResultRaw,
          },
          {
            type: 'user',
            content: '[tool_result]',
            raw: sharedToolResultRaw,
          },
          {
            type: 'user',
            content: '[tool_result]',
            raw: secondToolResultRaw,
          },
        ]);
        window.onStreamEnd?.('18');
      });

      const updater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as
        | ((messages: ClaudeMessage[]) => ClaudeMessage[])
        | undefined;
      expect(updater).toBeTypeOf('function');

      const previousMessages: ClaudeMessage[] = [
        {
          type: 'assistant',
          content: 'calling tool',
          timestamp: '2026-06-27T09:10:00.000Z',
          isStreaming: true,
          __turnId: 1,
          raw: {
            message: {
              content: [{ type: 'tool_use', id: 'tool-1', name: 'Read' }],
            },
          } as any,
        },
        {
          type: 'user',
          content: '[tool_result]',
          timestamp: '2026-06-27T09:10:01.000Z',
          raw: sharedToolResultRaw as any,
        },
      ];
      const nextMessages = updater!(previousMessages);
      const recoveredMessages = nextMessages.filter((msg) => msg.type === 'user');

      expect(recoveredMessages).toHaveLength(2);
      expect(
        recoveredMessages.filter(
          (msg) =>
            ((msg.raw as any)?.message?.content?.[0]?.tool_use_id) === 'tool-1',
        ),
      ).toHaveLength(1);
      expect(
        recoveredMessages.filter(
          (msg) =>
            ((msg.raw as any)?.message?.content?.[0]?.tool_use_id) === 'tool-2',
        ),
      ).toHaveLength(1);
    });

    it('onStreamEnd prefers the backend snapshot when it rewrites content with the same length', () => {
      const opts = createOptions({
        isStreamingRef: { current: true },
        streamingMessageIndexRef: { current: 0 },
      });
      renderHook(() => useWindowCallbacks(opts));

      const correctedRaw = {
        message: {
          content: [{ type: 'text', text: 'Alpha zeta' }],
        },
      };

      act(() => {
        window.onStreamStart?.();
        // 中文注释：测试环境里的 setMessages mock 不会真正执行 onStreamStart 内部的 updater，
        // 因此这里手动补回当前流式 assistant 索引，模拟真实页面中“已有第 0 条 assistant 正在 streaming”的状态。
        opts.streamingMessageIndexRef.current = 0;
        window.onContentDelta?.('Alpha beta');
        window.__pendingUpdateJson = JSON.stringify([
          {
            type: 'assistant',
            content: 'Alpha zeta',
            raw: correctedRaw,
          },
        ]);
        window.onStreamEnd?.('21');
      });

      const updater = (opts.setMessages as any).mock.calls.at(-1)?.[0] as
        | ((messages: ClaudeMessage[]) => ClaudeMessage[])
        | undefined;
      expect(updater).toBeTypeOf('function');

      const previousMessages: ClaudeMessage[] = [
        {
          type: 'assistant',
          content: 'Alpha beta',
          timestamp: '2026-06-27T10:00:00.000Z',
          isStreaming: true,
          __turnId: 1,
          raw: {
            message: {
              content: [{ type: 'text', text: 'Alpha beta' }],
            },
          } as any,
        },
      ];
      const nextMessages = updater!(previousMessages);

      expect(nextMessages[0]).toMatchObject({
        type: 'assistant',
        content: 'Alpha zeta',
        isStreaming: false,
      });
      expect((nextMessages[0].raw as any)?.message?.content?.[0]?.text).toBe('Alpha zeta');
    });
  });
});
