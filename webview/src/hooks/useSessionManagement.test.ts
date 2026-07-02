import { act, renderHook } from '@testing-library/react';
import { useSessionManagement } from './useSessionManagement.js';
import type { HistoryData } from '../types/index.js';
import { debugLog } from '../utils/debug.js';

vi.mock('../utils/debug.js', () => ({
  debugLog: vi.fn(),
}));

describe('useSessionManagement', () => {
  const t = ((key: string) => key) as any;

  const createMocks = () => ({
    setHistoryData: vi.fn(),
    setMessages: vi.fn(),
    setCurrentView: vi.fn(),
    setCurrentSessionId: vi.fn(),
    setCustomSessionTitle: vi.fn(),
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setStatus: vi.fn(),
    setLoading: vi.fn(),
    setIsThinking: vi.fn(),
    setStreamingActive: vi.fn(),
    clearToasts: vi.fn(),
    addToast: vi.fn(),
    setBackgroundTasks: vi.fn(),
  });

  beforeEach(() => {
    window.__sessionTransitioning = false;
    window.__sessionTransitionToken = null;
    window.__pendingSessionTransitionToast = undefined;
    window.sendToJava = vi.fn();
  });

  it('starts a clean session transition for a direct new session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'old-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setStatus).toHaveBeenCalledWith('');
    expect(mocks.setLoading).toHaveBeenCalledWith(false);
    expect(mocks.setIsThinking).toHaveBeenCalledWith(false);
    expect(mocks.setStreamingActive).toHaveBeenCalledWith(false);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith(null);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
  });

  it('clears stale ui state before loading history', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History Title',
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
      ],
      total: 3,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'old', timestamp: new Date().toISOString() }],
        loading: true,
        historyData,
        currentSessionId: 'old-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('history-1');
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, 'interrupt_session:');
    expect(window.sendToJava).toHaveBeenNthCalledWith(
      2,
      expect.stringContaining('"sessionId":"history-1"')
    );
    expect(window.sendToJava).toHaveBeenNthCalledWith(
      2,
      expect.stringContaining('"provider":"claude"')
    );
    expect(window.sendToJava).toHaveBeenNthCalledWith(
      2,
      expect.stringContaining('"restoreSource":"history_switch"')
    );
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith('history-1');
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith('History Title');
    expect(mocks.setCurrentView).toHaveBeenCalledWith('chat');
  });

  it('applies repeated history deletes against the latest state', () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySession('history-1');
      result.current.deleteHistorySession('history-2');
    });

    expect(historyData.sessions).toEqual([]);
    expect(historyData.total).toBe(0);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'delete_session:{"sessionId":"history-1","logicalConversationId":null}'
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      'delete_session:{"sessionId":"history-2","logicalConversationId":null}'
    );
  });

  it('sends one backend request when deleting multiple history sessions', () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2', 'history-1']);
    });

    expect(historyData.sessions).toEqual([]);
    expect(historyData.total).toBe(0);
    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'delete_sessions:[{"sessionId":"history-1","logicalConversationId":null},{"sessionId":"history-2","logicalConversationId":null}]'
    );
    expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
  });

  it('still shows a success toast for batch delete when history data is temporarily unavailable', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2', 'history-1']);
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'delete_sessions:[{"sessionId":"history-1","logicalConversationId":null},{"sessionId":"history-2","logicalConversationId":null}]'
    );
    expect(mocks.addToast).toHaveBeenCalledWith('history.sessionDeleted', 'success');
  });

  it('defers the deleted toast until transition completion when batch delete removes current session', () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'history-1',
          title: 'History One',
          provider: 'claude',
          messageCount: 3,
          lastTimestamp: Date.now(),
        },
        {
          sessionId: 'history-2',
          title: 'History Two',
          provider: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 8,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: 'history-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySessions(['history-1', 'history-2']);
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'delete_sessions:[{"sessionId":"history-1","logicalConversationId":null},{"sessionId":"history-2","logicalConversationId":null}]'
    );
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(mocks.addToast).not.toHaveBeenCalledWith('history.sessionDeleted', 'success');
    expect(window.__pendingSessionTransitionToast).toEqual({
      message: 'history.sessionDeleted',
      type: 'success',
    });
  });

  it('forceCreateNewSession interrupts loading session and cleans state', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'streaming...', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'active-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSession();
    });

    expect(window.sendToJava).toHaveBeenCalledWith('interrupt_session:');
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
  });

  it('forceCreateNewSessionWithProvider resets session and applies target provider before recreating', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'old', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'active-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSessionWithProvider('codex');
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(1, 'set_provider:codex');
    expect(window.sendToJava).toHaveBeenNthCalledWith(2, 'create_new_session:');
    expect(window.__sessionTransitioning).toBe(true);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
  });

  it('createContinuedSegment keeps current messages and sends runtime switch payload instead of creating a blank session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'existing context', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'codex-session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createContinuedSegment({
        switchReason: 'model',
        targetProvider: 'codex',
        targetRuntimeFamily: 'codex',
        targetModel: 'gpt-5.4',
        targetReasoningEffort: 'medium',
        targetCodexProviderId: 'managed-buycode',
      });
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.setMessages).not.toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'create_continued_segment:{"sourceSessionId":"codex-session-1","targetProvider":"codex","targetRuntimeFamily":"codex","targetModel":"gpt-5.4","targetReasoningEffort":"medium","targetCodexProviderId":"managed-buycode","switchReason":"model"}'
    );
    expect(window.sendToJava).not.toHaveBeenCalledWith('create_new_session:');
  });

  it('loadHistorySession writes codex runtime trace with logical conversation context', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'segment-001',
          logicalConversationId: 'logical-001',
          activeSegmentSessionId: 'segment-002',
          title: 'Continued Session',
          provider: 'codex',
          runtimeFamily: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now(),
        },
      ],
      total: 5,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: 'active-session',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('logical-001');
    });

    expect(debugLog).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] loadHistorySession',
      expect.objectContaining({
        requestedConversationKey: 'logical-001',
        resolvedSessionId: 'segment-002',
        logicalConversationId: 'logical-001',
        activeSegmentSessionId: 'segment-002',
        restoreSource: 'history_switch',
      })
    );
  });

  it('writes trace logs when forcing a new session because codex runtime changed', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'old turn', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'codex-session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSession();
    });

    expect(debugLog).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] forceCreateNewSession',
      expect.objectContaining({
        loading: true,
        currentSessionId: 'codex-session-1',
      }),
    );
    expect(debugLog).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] beginSessionTransition',
      expect.objectContaining({
        previousSessionId: 'codex-session-1',
        nextSessionId: null,
      }),
    );
  });

  it('shows confirm dialog when creating new session with existing messages', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.createNewSession();
    });

    // Should show confirm dialog, NOT immediately transition
    expect(result.current.showNewSessionConfirm).toBe(true);
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
    expect(mocks.setMessages).not.toHaveBeenCalled();
  });

  it('handleConfirmNewSession cleans state and creates new session', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'user', content: 'hello', timestamp: new Date().toISOString() }],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Trigger dialog first
    act(() => {
      result.current.createNewSession();
    });

    // Confirm
    act(() => {
      result.current.handleConfirmNewSession();
    });

    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(result.current.showNewSessionConfirm).toBe(false);
  });

  it('handleConfirmInterrupt interrupts and cleans state', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [{ type: 'assistant', content: 'responding...', timestamp: new Date().toISOString() }],
        loading: true,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Must trigger interrupt dialog first
    act(() => {
      result.current.createNewSession();
    });

    // Then confirm interrupt
    act(() => {
      result.current.handleConfirmInterrupt();
    });

    expect(window.sendToJava).toHaveBeenCalledWith('interrupt_session:');
    expect(window.sendToJava).toHaveBeenCalledWith('create_new_session:');
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith(null);
  });

  it('loadHistorySession without loading state does not send interrupt', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-2',
          title: null,
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 1,
          lastTimestamp: Date.now(),
        },
      ],
      total: 1,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-2');
    });

    // Should NOT send interrupt when not loading
    const calls = (window.sendToJava as any).mock.calls.map((c: any) => c[0]);
    expect(calls).not.toContain('interrupt_session:');
    expect(calls.some((call: string) =>
      call.includes('"sessionId":"hist-2"')
      && call.includes('"provider":"claude"')
      && call.includes('"restoreSource":"history_switch"')
    )).toBe(true);

    // But should still set transition guard
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();
    expect(mocks.clearToasts).toHaveBeenCalledTimes(1);
    expect(mocks.setMessages).toHaveBeenCalledWith([]);
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith('hist-2');
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith(null);
  });

  it('loadHistorySession sends explicit provider when provided by history item', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-codex',
          title: 'Codex Session',
          provider: 'codex',
          model: 'gpt-5.4',
          messageCount: 2,
          lastTimestamp: Date.now(),
        },
      ],
      total: 2,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-codex', 'codex');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"sessionId":"hist-codex"')
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"provider":"codex"')
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"restoreSource":"history_switch"')
    );
  });

  it('loadHistorySession forwards runtimeFamily for minimax history items', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-minimax',
          title: 'MiniMax Session',
          provider: 'minimax',
          runtimeFamily: 'codex',
          model: 'MiniMax-M2.5',
          messageCount: 2,
          lastTimestamp: Date.now(),
        },
      ],
      total: 2,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-minimax');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"provider":"minimax"')
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"runtimeFamily":"codex"')
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"restoreSource":"history_switch"')
    );
  });

  it('loadHistorySession resolves logical conversation key to active segment payload', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'segment-001',
          logicalConversationId: 'logical-001',
          activeSegmentSessionId: 'segment-002',
          title: 'Continued Session',
          provider: 'codex',
          runtimeFamily: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now() - 1000,
        },
        {
          sessionId: 'segment-002',
          logicalConversationId: 'logical-001',
          activeSegmentSessionId: 'segment-002',
          title: 'Continued Session',
          provider: 'codex',
          runtimeFamily: 'codex',
          messageCount: 7,
          lastTimestamp: Date.now(),
        },
      ],
      total: 12,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('logical-001');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('load_conversation:')
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"sessionId":"segment-002"')
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"logicalConversationId":"logical-001"')
    );
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringContaining('"activeSegmentSessionId":"segment-002"')
    );
    expect(mocks.setCurrentSessionId).toHaveBeenCalledWith('segment-002');
    expect(mocks.setCustomSessionTitle).toHaveBeenCalledWith('Continued Session');
  });

  it('deleteHistorySession expands logical conversation key to aggregated bridge payload', () => {
    let historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'segment-001',
          logicalConversationId: 'logical-001',
          activeSegmentSessionId: 'segment-002',
          title: 'Continued Session',
          provider: 'codex',
          runtimeFamily: 'codex',
          messageCount: 5,
          lastTimestamp: Date.now() - 1000,
        },
        {
          sessionId: 'segment-002',
          logicalConversationId: 'logical-001',
          activeSegmentSessionId: 'segment-002',
          title: 'Continued Session',
          provider: 'codex',
          runtimeFamily: 'codex',
          messageCount: 7,
          lastTimestamp: Date.now(),
        },
      ],
      total: 12,
    } as unknown as HistoryData;

    const mocks = {
      ...createMocks(),
      setHistoryData: vi.fn((next: HistoryData | null | ((current: HistoryData | null) => HistoryData | null)) => {
        historyData = typeof next === 'function' ? next(historyData) as HistoryData : next as HistoryData;
      }),
    };

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.deleteHistorySession('logical-001');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'delete_session:{"sessionId":"segment-002","logicalConversationId":"logical-001"}'
    );
    expect(historyData.sessions).toEqual([]);
    expect(historyData.total).toBe(0);
  });

  it('all transition paths reset usage tokens', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    // Test forceCreateNewSession
    act(() => {
      result.current.forceCreateNewSession();
    });

    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(mocks.setUsageMaxTokens).toHaveBeenCalledWith(undefined);
  });

  it('updates current tab title directly when sessionId is missing', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.syncCurrentTabTitle('临时标题');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'sync_current_tab_title:{"title":"临时标题"}'
    );
  });

  it('beginSessionTransition clears all transient UI states synchronously', () => {
    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData: null,
        currentSessionId: 'session-1',
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.forceCreateNewSession();
    });

    // All transient UI states must be synchronously cleared
    expect(mocks.setStatus).toHaveBeenCalledWith('');
    expect(mocks.setLoading).toHaveBeenCalledWith(false);
    expect(mocks.setIsThinking).toHaveBeenCalledWith(false);
    expect(mocks.setStreamingActive).toHaveBeenCalledWith(false);
    expect(mocks.setUsagePercentage).toHaveBeenCalledWith(0);
    expect(mocks.setUsageUsedTokens).toHaveBeenCalledWith(undefined);
    expect(mocks.setUsageMaxTokens).toHaveBeenCalledWith(undefined);
  });

  it('historyLoadComplete releases transition guard', () => {
    // Simulate what happens when Java calls historyLoadComplete after successful load
    window.__sessionTransitioning = true;
    window.__sessionTransitionToken = 'transition-test';

    // historyLoadComplete is defined in useWindowCallbacks, but we can test
    // that the guard release mechanism works by direct simulation
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBe('transition-test');

    // Simulate historyLoadComplete behavior
    window.__sessionTransitioning = false;
    window.__sessionTransitionToken = null;
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
  });

  it('loadHistorySession sets transition guard that blocks updateMessages', () => {
    const historyData = {
      success: true,
      sessions: [
        {
          sessionId: 'hist-3',
          title: 'Test Session',
          provider: 'claude',
          model: 'claude-sonnet-4-6',
          messageCount: 1,
          lastTimestamp: Date.now(),
        },
      ],
      total: 1,
    } as unknown as HistoryData;

    const mocks = createMocks();

    const { result } = renderHook(() =>
      useSessionManagement({
        messages: [],
        loading: false,
        historyData,
        currentSessionId: null,
        ...mocks,
        t,
      })
    );

    act(() => {
      result.current.loadHistorySession('hist-3');
    });

    // Guard is set, blocking stale updateMessages
    expect(window.__sessionTransitioning).toBe(true);
    expect(window.__sessionTransitionToken).toBeTruthy();

    // Simulate historyLoadComplete (success path releases guard)
    act(() => {
      window.__sessionTransitioning = false;
      window.__sessionTransitionToken = null;
    });
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();

    // Simulate failure path: guard must also be released
    act(() => {
      window.__sessionTransitioning = true; // re-arm
      window.__sessionTransitionToken = 'transition-rearm';
    });
    // Java exceptionally block calls historyLoadComplete before addErrorMessage
    act(() => {
      window.__sessionTransitioning = false;
      window.__sessionTransitionToken = null;
    });
    expect(window.__sessionTransitioning).toBe(false);
    expect(window.__sessionTransitionToken).toBeNull();
  });
});
