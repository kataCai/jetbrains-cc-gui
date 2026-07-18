import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessageSender } from './useMessageSender';
import { sendBridgeEvent } from '../utils/bridge';
import type { Attachment, PermissionMode } from '../components/ChatInputBox/types';
import { buildRuntimeIntentFromSelection } from '../types/runtimeIntent';
import type { RuntimeSelectionState } from '../types/runtimeSelection';
import type { UseMessageSenderOptions } from './useMessageSender';

vi.mock('../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
}));

type RuntimeAwareMessageSenderOptions = UseMessageSenderOptions & {
  desiredRuntimeSelectionRef: { current: RuntimeSelectionState };
  activeSessionRuntimeSnapshotRef: { current: RuntimeSelectionState };
};

const createOptions = (
  currentProvider: string,
  permissionMode: PermissionMode,
): RuntimeAwareMessageSenderOptions => ({
  t: ((key: string) => key) as any,
  addToast: vi.fn(),
  currentProvider,
  selectedModel: 'test-model',
  permissionMode,
  selectedAgent: null,
  sdkStatusLoaded: true,
  currentSdkInstalled: true,
  reasoningEffort: 'medium',
  sentAttachmentsRef: { current: new Map() },
  chatInputRef: { current: { getFileTags: () => [] } as any },
  messagesContainerRef: { current: null },
  isUserAtBottomRef: { current: true },
  userPausedRef: { current: false },
  isStreamingRef: { current: false },
  desiredRuntimeSelectionRef: {
    current: {
      provider: currentProvider,
      model: 'test-model',
      reasoningEffort: 'medium',
      codexProviderId: currentProvider === 'codex' ? 'managed-openai' : '',
    } satisfies RuntimeSelectionState,
  },
  activeSessionRuntimeSnapshotRef: {
    current: {
      provider: currentProvider,
      model: 'test-model',
      reasoningEffort: 'medium',
      codexProviderId: currentProvider === 'codex' ? 'managed-openai' : '',
    } satisfies RuntimeSelectionState,
  },
  setMessages: vi.fn(),
  setLoading: vi.fn(),
  setLoadingStartTime: vi.fn(),
  setStreamingActive: vi.fn(),
  setSettingsInitialTab: vi.fn(),
  setCurrentView: vi.fn(),
  forceCreateNewSession: vi.fn(),
  openContextUsageDialog: vi.fn(),
  closeContextUsageDialog: vi.fn(() => false),
  currentSessionId: 'session-001',
  continuationPending: false,
});

describe('useMessageSender continued guard', () => {
  it('blocks first continued-segment submit when transition cache has no source anchor and only logical conversation metadata remains', () => {
    // 中文注释：只剩 logicalConversationId 不足以支撑 continued 首发。
    // 如果没有稳定 source anchor，就不能在空 sessionId 上继续首发，否则后端会把这轮请求绑定到错误分段。
    const currentSessionIdRef: { current: string | null } = { current: null };
    const continuationPendingRef: { current: boolean } = { current: true };
    const options = {
      ...createOptions('codex', 'default'),
      currentSessionId: null,
      continuationPending: true,
      currentSessionIdRef,
      continuationPendingRef,
    };
    window.__sessionTransitioning = false;
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=?', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
    ] as any;
    (window as any).__continuedSegmentPendingSourceSessionId = null;
    (window as any).__continuedSegmentPendingLogicalConversationId = 'logical-001';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    const { result } = renderHook(() => useMessageSender(options as any));

    act(() => {
      result.current.handleSubmit('再+1=?');
    });

    expect(sendBridgeEvent).not.toHaveBeenCalledWith('send_message', expect.anything());
    expect(options.addToast).toHaveBeenCalledWith('chat.continuedSegmentNotReady', 'info');
  });
});

describe('useMessageSender', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.__sessionTransitioning = false;
    window.__continuedSegmentHistoryPrefixMessages = null;
    window.__continuedSegmentHistoryPrefixSessionId = null;
    window.__continuedSegmentPendingTailMessages = null;
    (window as any).__continuedSegmentPendingSourceSessionId = null;
    (window as any).__continuedSegmentPendingLogicalConversationId = null;
    (window as any).__continuedSegmentPendingCreatedAt = null;
    (window as any).__continuedSegmentPendingReason = null;
    (window as any).__continuedSegmentAwaitingFirstSessionId = false;
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      cb(0);
      return 0;
    });
  });

  it('downgrades codex plan messages to default permission mode', () => {
    // 对 Codex 来说，真实执行 permissionMode 要降级，但 requestedUsageMode 仍保留 plan 语义。
    const options = createOptions('codex', 'plan');
    const { result } = renderHook(() => useMessageSender(options));

    act(() => {
      result.current.executeMessage('hello codex');
    });

    const sendMessageCall = (sendBridgeEvent as any).mock.calls.find((call: any[]) => call[0] === 'send_message');
    expect(sendMessageCall).toBeTruthy();
    const payload = JSON.parse(sendMessageCall[1]);
    expect(payload.permissionMode).toBe('default');
    expect(payload.requestedUsageMode).toBe('plan');
    expect(payload.runtimeIntent).toMatchObject({
      sourceKind: 'chat',
      resolutionPolicy: 'dynamic_at_execution',
      targetProvider: 'codex',
      targetRuntimeFamily: 'codex',
      targetModel: 'test-model',
    });
  });

  it('keeps plan permission mode for non-codex providers', () => {
    // Claude 等 provider 仍应保留 plan 原样透传。
    const options = createOptions('claude', 'plan');
    const { result } = renderHook(() => useMessageSender(options));

    act(() => {
      result.current.executeMessage('hello claude');
    });

    const sendMessageCall = (sendBridgeEvent as any).mock.calls.find((call: any[]) => call[0] === 'send_message');
    expect(sendMessageCall).toBeTruthy();
    const payload = JSON.parse(sendMessageCall[1]);
    expect(payload.permissionMode).toBe('plan');
    expect(payload.requestedUsageMode).toBe('plan');
    expect(payload.runtimeIntent.targetProvider).toBe('claude');
  });

  it('includes requested usage mode in attachment sends', () => {
    // 附件发送和普通发送必须保持一致，不能丢 requestedUsageMode。
    const options = createOptions('codex', 'plan');
    const { result } = renderHook(() => useMessageSender(options));
    const attachments: Attachment[] = [{
      id: 'att-1',
      fileName: 'demo.txt',
      mediaType: 'text/plain',
      data: 'ZGVtbw==',
    }];

    act(() => {
      result.current.executeMessage('hello attachments', attachments);
    });

    const sendMessageCall = (sendBridgeEvent as any).mock.calls.find(
      (call: any[]) => call[0] === 'send_message_with_attachments'
    );
    expect(sendMessageCall).toBeTruthy();
    const payload = JSON.parse(sendMessageCall[1]);
    expect(payload.permissionMode).toBe('default');
    expect(payload.requestedUsageMode).toBe('plan');
    expect(payload.attachments).toHaveLength(1);
    expect(payload.runtimeIntent.targetProvider).toBe('codex');
  });

  it('passes chat requested usage mode for non-plan sends', () => {
    // 非 plan 场景下也要显式告诉后端：这次请求来自 chat usage mode。
    const options = createOptions('codex', 'default');
    const { result } = renderHook(() => useMessageSender(options));

    act(() => {
      result.current.executeMessage('hello chat');
    });

    const sendMessageCall = (sendBridgeEvent as any).mock.calls.find((call: any[]) => call[0] === 'send_message');
    expect(sendMessageCall).toBeTruthy();
    const payload = JSON.parse(sendMessageCall[1]);
    expect(payload.permissionMode).toBe('default');
    expect(payload.requestedUsageMode).toBe('chat');
    expect(payload.runtimeIntent.targetProvider).toBe('codex');
  });

  it('resolves runtime intent from the latest desired selection ref at execution time', () => {
    // 排队消息出队时必须跟随“最终选择”的 runtime，而不是继续使用旧 render 闭包里的 provider/model。
    const options = createOptions('claude', 'plan');
    options.desiredRuntimeSelectionRef.current = {
      provider: 'codex',
      model: 'gpt-5.4',
      reasoningEffort: 'low',
      codexProviderId: 'managed-openai',
    } as RuntimeSelectionState;
    options.activeSessionRuntimeSnapshotRef.current = {
      provider: 'claude',
      model: 'claude-sonnet-4-6',
      reasoningEffort: 'high',
      codexProviderId: '',
    } as RuntimeSelectionState;
    const { result } = renderHook(() => useMessageSender(options));

    act(() => {
      result.current.executeMessage('use latest desired runtime');
    });

    const sendMessageCall = (sendBridgeEvent as any).mock.calls.find((call: any[]) => call[0] === 'send_message');
    expect(sendMessageCall).toBeTruthy();
    const payload = JSON.parse(sendMessageCall[1]);
    expect(payload.permissionMode).toBe('default');
    expect(payload.runtimeIntent).toEqual({
      sourceKind: 'chat',
      resolutionPolicy: 'dynamic_at_execution',
      targetProvider: 'codex',
      targetRuntimeFamily: 'codex',
      targetModel: 'gpt-5.4',
      targetReasoningEffort: 'low',
      targetCodexProviderId: 'managed-openai',
    });
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_provider', expect.anything());
  });

  it('keeps locked task runtime intent and lets the next ordinary message return to the latest desired selection', () => {
    /**
     * 中文注释：
     * 该用例覆盖 Task 3 Step 9。
     * 先让计划子任务以锁定模型 A 发送，再保持聊天区 desired selection 仍指向模型 C；
     * 下一条普通消息必须重新按聊天区最终选择解析，而不是继续复用锁定任务的模型 A。
     */
    const options = createOptions('codex', 'default');
    options.desiredRuntimeSelectionRef.current = {
      provider: 'codex',
      model: 'gpt-5.5',
      reasoningEffort: 'high',
      codexProviderId: 'managed-openai',
    } as RuntimeSelectionState;
    options.activeSessionRuntimeSnapshotRef.current = {
      provider: 'codex',
      model: 'gpt-5.5',
      reasoningEffort: 'high',
      codexProviderId: 'managed-openai',
    } as RuntimeSelectionState;
    const lockedRuntimeIntent = buildRuntimeIntentFromSelection(
      {
        provider: 'codex',
        model: 'gpt-5.4',
        reasoningEffort: 'low',
        codexProviderId: 'managed-openai',
      },
      'locked_task',
      'locked_at_enqueue',
      'plan_subtask',
    );
    const { result } = renderHook(() => useMessageSender(options));

    act(() => {
      result.current.executeMessage('run locked plan step', undefined, {
        kind: 'locked_task',
        lockedBy: 'plan_subtask',
        lockedRuntimeIntent,
      });
    });

    let sendMessageCalls = (sendBridgeEvent as any).mock.calls.filter((call: any[]) => call[0] === 'send_message');
    expect(sendMessageCalls).toHaveLength(1);
    let payload = JSON.parse(sendMessageCalls[0][1]);
    expect(payload.runtimeIntent).toEqual(lockedRuntimeIntent);

    options.activeSessionRuntimeSnapshotRef.current = {
      provider: 'codex',
      model: 'gpt-5.4',
      reasoningEffort: 'low',
      codexProviderId: 'managed-openai',
    } as RuntimeSelectionState;

    act(() => {
      result.current.executeMessage('back to chat runtime');
    });

    sendMessageCalls = (sendBridgeEvent as any).mock.calls.filter((call: any[]) => call[0] === 'send_message');
    expect(sendMessageCalls).toHaveLength(2);
    payload = JSON.parse(sendMessageCalls[1][1]);
    expect(payload.runtimeIntent).toEqual({
      sourceKind: 'chat',
      resolutionPolicy: 'dynamic_at_execution',
      targetProvider: 'codex',
      targetRuntimeFamily: 'codex',
      targetModel: 'gpt-5.5',
      targetReasoningEffort: 'high',
      targetCodexProviderId: 'managed-openai',
    });
  });

  it('blocks continued-segment submit before the real session id is assigned', () => {
    // 中文注释：continued segment 刚创建后，如果真实 sessionId 还没回推到前端，
    // 则不应允许首条“继续”直接发往后端，否则会在空 sessionId 状态下启动发送链路。
    const options = {
      ...createOptions('codex', 'default'),
      currentSessionId: null,
      continuationPending: true,
    };
    window.__sessionTransitioning = true;

    const { result } = renderHook(() => useMessageSender(options));

    act(() => {
      result.current.handleSubmit('继续');
    });

    expect(sendBridgeEvent).not.toHaveBeenCalledWith('send_message', expect.anything());
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('send_message_with_attachments', expect.anything());
    expect(options.setMessages).not.toHaveBeenCalled();
    expect(options.setLoading).not.toHaveBeenCalledWith(true);
    expect(options.addToast).toHaveBeenCalledTimes(1);
    expect(options.addToast).toHaveBeenCalledWith('chat.continuedSegmentNotReady', 'info');
  });

  it('blocks continued-segment submit by the latest refs even before React re-renders with pending state', () => {
    // 中文注释：这个场景直接覆盖“切模型动作刚把 continued 运行态改成 pending，
    // 但发送回调还握着上一帧 props”的竞态。只要 ref 已经表明 sessionId 为空且 continued pending，
    // 就必须立刻拦截发送，不能等下一次 rerender。
    const currentSessionIdRef: { current: string | null } = { current: 'session-001' };
    const continuationPendingRef: { current: boolean } = { current: false };
    const options = {
      ...createOptions('codex', 'default'),
      currentSessionId: 'session-001',
      continuationPending: false,
      currentSessionIdRef,
      continuationPendingRef,
    };
    window.__sessionTransitioning = false;

    const { result } = renderHook(() => useMessageSender(options as any));

    currentSessionIdRef.current = null;
    continuationPendingRef.current = true;

    act(() => {
      result.current.handleSubmit('继续追问');
    });

    expect(sendBridgeEvent).not.toHaveBeenCalledWith('send_message', expect.anything());
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('send_message_with_attachments', expect.anything());
    expect(options.setMessages).not.toHaveBeenCalled();
    expect(options.setLoading).not.toHaveBeenCalledWith(true);
    expect(options.addToast).toHaveBeenCalledTimes(1);
    expect(options.addToast).toHaveBeenCalledWith('chat.continuedSegmentNotReady', 'info');
  });

  it('allows first continued-segment submit after guard timeout when transition cache waits for sdk session id', () => {
    // 中文注释：真实日志显示后端 continued session 已初始化后，SDK 的真实 sessionId 会在首问发送后才回推。
    // 因此 guard 已释放且 transition cache 明确等待首个 sessionId 时，不能再用空 currentSessionId 拦截首问。
    const currentSessionIdRef: { current: string | null } = { current: null };
    const continuationPendingRef: { current: boolean } = { current: true };
    const options = {
      ...createOptions('codex', 'default'),
      currentSessionId: null,
      continuationPending: true,
      currentSessionIdRef,
      continuationPendingRef,
    };
    window.__sessionTransitioning = false;
    window.__continuedSegmentHistoryPrefixMessages = [
      { type: 'user', content: '1+1=？', timestamp: '2026-07-04T15:19:00.000Z' },
      { type: 'assistant', content: '2', timestamp: '2026-07-04T15:19:01.000Z' },
    ] as any;
    (window as any).__continuedSegmentPendingSourceSessionId = 'segment-001';
    (window as any).__continuedSegmentAwaitingFirstSessionId = true;

    const { result } = renderHook(() => useMessageSender(options as any));

    act(() => {
      result.current.handleSubmit('再+1=？');
    });

    expect(options.addToast).not.toHaveBeenCalledWith('chat.continuedSegmentNotReady', 'info');
    expect(sendBridgeEvent).toHaveBeenCalledWith('send_message', expect.stringContaining('再+1=？'));
    expect(window.__continuedSegmentHistoryPrefixMessages).toHaveLength(2);
  });
});
