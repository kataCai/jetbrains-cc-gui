import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useMessageSender } from './useMessageSender';
import { sendBridgeEvent } from '../utils/bridge';
import type { Attachment, PermissionMode } from '../components/ChatInputBox/types';

vi.mock('../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
}));

const createOptions = (currentProvider: string, permissionMode: PermissionMode) => ({
  t: ((key: string) => key) as any,
  addToast: vi.fn(),
  currentProvider,
  selectedModel: 'test-model',
  permissionMode,
  selectedAgent: null,
  sdkStatusLoaded: true,
  currentSdkInstalled: true,
  sentAttachmentsRef: { current: new Map() },
  chatInputRef: { current: { getFileTags: () => [] } as any },
  messagesContainerRef: { current: null },
  isUserAtBottomRef: { current: true },
  userPausedRef: { current: false },
  isStreamingRef: { current: false },
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
