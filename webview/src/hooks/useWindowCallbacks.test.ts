import { act, renderHook } from '@testing-library/react';
import { useWindowCallbacks } from './useWindowCallbacks.js';
import type { UseWindowCallbacksOptions } from './useWindowCallbacks.js';
import type { ClaudeMessage } from '../types/index.js';

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
    window.__deniedToolIds = new Set();
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
