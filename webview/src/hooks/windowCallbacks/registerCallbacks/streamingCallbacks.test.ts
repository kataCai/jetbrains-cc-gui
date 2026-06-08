import { describe, expect, it, vi } from 'vitest';
import type { UseWindowCallbacksOptions } from '../../useWindowCallbacks';
import { registerStreamingCallbacks } from './streamingCallbacks';
import type { ClaudeMessage } from '../../../types';

const createOptions = (): UseWindowCallbacksOptions => ({
  t: ((key: string) => key) as any,
  addToast: vi.fn(),
  clearToasts: vi.fn(),
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
  setPermissionMode: vi.fn(),
  setClaudePermissionMode: vi.fn(),
  setCodexPermissionMode: vi.fn(),
  setSelectedClaudeModel: vi.fn(),
  setSelectedCodexModel: vi.fn(),
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
  currentProviderRef: { current: 'claude' },
  shouldAdoptCodexDefaultModelRef: { current: true },
  messagesContainerRef: { current: null },
  isUserAtBottomRef: { current: true },
  userPausedRef: { current: false },
  suppressNextStatusToastRef: { current: false },
  streamingContentRef: { current: '' },
  // streamingCallbacks 现已同时维护 content/thinking 两路流式缓冲，测试夹具需要补齐 thinking ref。
  // 否则 onStreamStart/onStreamEnd 在重置 thinking 缓冲时会因依赖缺失而抛错，无法覆盖真正要验证的流结束收口逻辑。
  streamingThinkingRef: { current: '' },
  isStreamingRef: { current: false },
  useBackendStreamingRenderRef: { current: false },
  autoExpandedThinkingKeysRef: { current: new Set<string>() },
  streamingMessageIndexRef: { current: -1 },
  streamingTurnIdRef: { current: -1 },
  turnIdCounterRef: { current: 0 },
  lastContentUpdateRef: { current: 0 },
  contentUpdateTimeoutRef: { current: null },
  lastThinkingUpdateRef: { current: 0 },
  thinkingUpdateTimeoutRef: { current: null },
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
  customSessionTitleRef: { current: null },
  currentSessionIdRef: { current: null },
  updateHistoryTitle: vi.fn(),
  applyHistoryTitleLocal: vi.fn(),
});

describe('registerStreamingCallbacks', () => {
  it('onStreamStart clears stale pending update payload from previous turn', () => {
    const options = createOptions();
    registerStreamingCallbacks(options);

    (window as any).__sessionTransitioning = false;
    (window as any).__pendingUpdateJson = JSON.stringify([
      { type: 'assistant', content: 'stale pending payload' },
    ]);
    (window as any).__cancelPendingUpdateMessages = vi.fn();

    (window as any).onStreamStart?.();

    expect((window as any).__cancelPendingUpdateMessages).toHaveBeenCalledTimes(1);
    expect((window as any).__pendingUpdateJson).toBeNull();
  });

  it('onStreamEnd keeps the buffered final content when pending updateMessages is cancelled', () => {
    const options = createOptions();
    options.streamingContentRef.current = 'final buffered tail';
    options.isStreamingRef.current = true;
    options.streamingMessageIndexRef.current = 0;
    options.streamingTurnIdRef.current = 3;

    registerStreamingCallbacks(options);

    const previousMessages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: 'stale snapshot',
        isStreaming: true,
        timestamp: new Date().toISOString(),
        __turnId: 3,
      },
    ];

    (window as any).__cancelPendingUpdateMessages = vi.fn();
    (window as any).__sessionTransitioning = false;

    (window as any).onStreamEnd?.('10');

    expect(options.setMessages).toHaveBeenCalledTimes(1);
    const updater = (options.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater(previousMessages);

    expect((window as any).__cancelPendingUpdateMessages).toHaveBeenCalled();
    expect(nextMessages[0].content).toBe('final buffered tail');
    expect(nextMessages[0].isStreaming).toBe(false);
  });

  it('onStreamEnd prefers the longer pending snapshot content over stale buffered content', () => {
    const options = createOptions();
    options.streamingContentRef.current = 'short';
    options.isStreamingRef.current = true;
    options.streamingMessageIndexRef.current = 0;
    options.streamingTurnIdRef.current = 8;

    registerStreamingCallbacks(options);

    const previousMessages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: 'short',
        isStreaming: true,
        timestamp: new Date().toISOString(),
        __turnId: 8,
        raw: {
          message: {
            content: [{ type: 'text', text: 'short' }],
          },
        } as any,
      },
    ];

    (window as any).__sessionTransitioning = false;
    (window as any).__pendingUpdateJson = JSON.stringify([
      {
        type: 'assistant',
        content: 'short but final snapshot is longer',
        raw: {
          message: {
            content: [{ type: 'text', text: 'short but final snapshot is longer' }],
          },
        },
      },
    ]);
    (window as any).__cancelPendingUpdateMessages = vi.fn();

    (window as any).onStreamEnd?.('11');

    const updater = (options.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater(previousMessages);

    expect(nextMessages[0].content).toBe('short but final snapshot is longer');
  });

  it('onStreamEnd is idempotent for the same turn and does not process twice', () => {
    const options = createOptions();
    options.streamingContentRef.current = 'final buffered tail';
    options.isStreamingRef.current = true;
    options.streamingMessageIndexRef.current = 0;
    options.streamingTurnIdRef.current = 12;

    registerStreamingCallbacks(options);

    (window as any).__sessionTransitioning = false;
    (window as any).__cancelPendingUpdateMessages = vi.fn();

    (window as any).onStreamEnd?.('20');
    (window as any).onStreamEnd?.('20');

    expect(options.setMessages).toHaveBeenCalledTimes(1);
  });

  it('onStreamEnd keeps recently ended turn markers for downstream reconcile guards', () => {
    const options = createOptions();
    options.streamingContentRef.current = 'final buffered tail';
    options.isStreamingRef.current = true;
    options.streamingMessageIndexRef.current = 0;
    options.streamingTurnIdRef.current = 15;

    registerStreamingCallbacks(options);

    (window as any).__sessionTransitioning = false;
    (window as any).__cancelPendingUpdateMessages = vi.fn();

    (window as any).onStreamEnd?.('30');

    expect((window as any).__lastStreamEndedTurnId).toBe(15);
    expect(typeof (window as any).__lastStreamEndedAt).toBe('number');
    expect((window as any).__streamEndProcessedTurnId).toBe(15);
  });

  it('onStreamEnd stamps durationMs on the completed assistant message', () => {
    const options = createOptions();
    options.streamingContentRef.current = 'final buffered tail';
    options.isStreamingRef.current = true;
    options.streamingMessageIndexRef.current = 0;
    options.streamingTurnIdRef.current = 18;

    registerStreamingCallbacks(options);

    const previousMessages: ClaudeMessage[] = [
      {
        type: 'assistant',
        content: 'stale snapshot',
        isStreaming: true,
        timestamp: new Date().toISOString(),
        __turnId: 18,
      },
    ];

    (window as any).__sessionTransitioning = false;
    (window as any).__cancelPendingUpdateMessages = vi.fn();
    (window as any).__turnStartedAt = Date.now() - 65000;

    (window as any).onStreamEnd?.('31');

    const updater = (options.setMessages as any).mock.calls[0][0] as (messages: ClaudeMessage[]) => ClaudeMessage[];
    const nextMessages = updater(previousMessages);

    expect(typeof nextMessages[0].durationMs).toBe('number');
    expect((nextMessages[0].durationMs as number)).toBeGreaterThanOrEqual(64000);
  });
});
