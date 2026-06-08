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
});

describe('useMessageSender', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
});
