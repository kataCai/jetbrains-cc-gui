import { act, renderHook } from '@testing-library/react';
import { useWindowCallbacks } from './useWindowCallbacks.js';
import type { UseWindowCallbacksOptions } from './useWindowCallbacks.js';
import type { ClaudeMessage } from '../types/index.js';
import * as debugModule from '../utils/debug.js';

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
    messagesContainerRef: { current: null },
    isUserAtBottomRef: { current: true },
    userPausedRef: { current: false },
    suppressNextStatusToastRef: { current: false },
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
    window.__pendingCompleteContinuedSegmentTransitionSessionId = 'segment-early-1';

    const opts = createOptions({
      continuationPendingRef: { current: true },
    });
    renderHook(() => useWindowCallbacks(opts));

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

    const updater = (opts.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
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
