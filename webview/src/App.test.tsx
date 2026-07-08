import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';
import { buildContinuedSegmentSwitchRequest } from './App';
import { DialogProvider } from './contexts/DialogContext';
import { SessionProvider } from './contexts/SessionContext';
import { MessagesProvider } from './contexts/MessagesContext';
import { UIStateProvider } from './contexts/UIStateContext';

const mockSendBridgeEvent = vi.fn();
const mockUseSessionManagement = vi.fn();

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, fallback?: unknown) => (typeof fallback === 'string' ? fallback : _key),
  }),
}));

vi.mock('./utils/bridge', () => ({
  sendBridgeEvent: (...args: unknown[]) => mockSendBridgeEvent(...args),
}));

vi.mock('./utils/diffTheme', () => ({
  applyDiffTheme: vi.fn(),
  getStoredDiffTheme: vi.fn(() => 'side-by-side'),
}));

vi.mock('./components/ChatInputBox/providers', () => ({
  preloadSlashCommands: vi.fn(),
  forceRefreshPrompts: vi.fn(),
}));

vi.mock('./components/history/HistoryView', () => ({
  default: () => <div data-testid="history-view" />,
}));

vi.mock('./components/settings', () => ({
  default: () => <div data-testid="settings-view" />,
}));

vi.mock('./components/ChatInputBox', () => ({
  ChatInputBox: () => <div data-testid="chat-input-box" />,
}));

vi.mock('./components/StatusPanel', () => ({
  StatusPanel: () => <div data-testid="status-panel" />,
  StatusPanelErrorBoundary: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}));

vi.mock('./components/Toast', () => ({
  ToastContainer: () => null,
}));

vi.mock('./components/ScrollControl', () => ({
  ScrollControl: () => null,
}));

vi.mock('./components/ChatHeader', () => ({
  ChatHeader: ({ modeStrip }: { modeStrip?: React.ReactNode }) => (
    <div data-testid="chat-header">{modeStrip}</div>
  ),
}));

vi.mock('./components/ChatModeStrip', () => ({
  ChatModeStrip: () => <div data-testid="chat-mode-strip" />,
}));

vi.mock('./components/WelcomeScreen', () => ({
  WelcomeScreen: () => <div data-testid="welcome-screen" />,
}));

vi.mock('./components/MessageList', () => ({
  MessageList: () => <div data-testid="message-list" />,
}));

vi.mock('./components/MessageAnchorRail', () => ({
  MessageAnchorRail: () => null,
}));

vi.mock('./components/AppDialogs', () => ({
  AppDialogs: () => null,
}));

vi.mock('./hooks', () => {
  const makeRef = <T,>(value: T) => ({ current: value });
  const noop = vi.fn();

  return {
    useScrollBehavior: () => ({
      messagesContainerRef: makeRef<HTMLDivElement | null>(null),
      messagesEndRef: makeRef<HTMLDivElement | null>(null),
      inputAreaRef: makeRef<HTMLDivElement | null>(null),
      isUserAtBottomRef: makeRef(true),
      userPausedRef: makeRef(false),
    }),
    useDialogManagement: () => ({
      permissionDialogOpen: false,
      currentPermissionRequest: null,
      openPermissionDialog: noop,
      handlePermissionApprove: noop,
      handlePermissionApproveAlways: noop,
      handlePermissionSkip: noop,
      askUserQuestionDialogOpen: false,
      currentAskUserQuestionRequest: null,
      openAskUserQuestionDialog: noop,
      handleAskUserQuestionSubmit: noop,
      handleAskUserQuestionCancel: noop,
      planApprovalDialogOpen: false,
      currentPlanApprovalRequest: null,
      openPlanApprovalDialog: noop,
      handlePlanApprovalApprove: noop,
      handlePlanApprovalReject: noop,
      rewindDialogOpen: false,
      setRewindDialogOpen: noop,
      currentRewindRequest: null,
      setCurrentRewindRequest: noop,
      isRewinding: false,
      setIsRewinding: noop,
      rewindSelectDialogOpen: false,
      setRewindSelectDialogOpen: noop,
    }),
    useSessionManagement: (...args: unknown[]) => mockUseSessionManagement(...args),
    useStreamingMessages: () => ({
      streamingContentRef: makeRef(''),
      isStreamingRef: makeRef(false),
      useBackendStreamingRenderRef: makeRef(false),
      streamingMessageIndexRef: makeRef<number | null>(null),
      streamingTextSegmentsRef: makeRef<string[]>([]),
      activeTextSegmentIndexRef: makeRef(-1),
      streamingThinkingSegmentsRef: makeRef<string[]>([]),
      activeThinkingSegmentIndexRef: makeRef(-1),
      seenToolUseCountRef: makeRef(0),
      contentUpdateTimeoutRef: makeRef<ReturnType<typeof setTimeout> | null>(null),
      thinkingUpdateTimeoutRef: makeRef<ReturnType<typeof setTimeout> | null>(null),
      lastContentUpdateRef: makeRef(0),
      lastThinkingUpdateRef: makeRef(0),
      autoExpandedThinkingKeysRef: makeRef(new Set<string>()),
      streamingTurnIdRef: makeRef<string | null>(null),
      turnIdCounterRef: makeRef(0),
      findLastAssistantIndex: () => -1,
      extractRawBlocks: () => [],
      getOrCreateStreamingAssistantIndex: () => -1,
      patchAssistantForStreaming: noop,
    }),
    useWindowCallbacks: noop,
    useRewindHandlers: () => ({
      handleRewindConfirm: noop,
      handleRewindCancel: noop,
      handleOpenRewindSelectDialog: noop,
      handleRewindSelect: noop,
      handleRewindSelectCancel: noop,
    }),
    useHistoryLoader: noop,
    useFileChanges: () => [],
    useSubagents: () => [],
    useMessageQueue: () => ({
      queue: [],
      enqueue: noop,
      dequeue: noop,
    }),
    useThemeInit: noop,
    useContextActions: noop,
    useMessageProcessing: () => ({
      getMessageText: (message: { content?: string }) => message.content ?? '',
      getContentBlocks: () => [],
      mergedMessages: [],
      sentAttachmentsRef: makeRef([]),
    }),
    useMessageSender: () => ({
      handleSubmit: noop,
      executeMessage: noop,
      interruptSession: noop,
    }),
    useFileChangesManagement: () => ({
      processedFiles: [],
      baseMessageIndex: 0,
      handleUndoFile: noop,
      handleDiscardAll: noop,
      handleKeepAll: noop,
    }),
    useModelProviderState: () => ({
      currentProvider: 'claude',
      selectedModel: 'claude-3-5-sonnet',
      permissionMode: 'default',
      selectedAgent: null,
      sdkStatusLoaded: true,
      currentSdkInstalled: true,
      currentProviderRef: makeRef('claude'),
      activeProviderConfig: null,
      claudeSettingsAlwaysThinkingEnabled: false,
      reasoningEffort: 'medium',
      streamingEnabledSetting: true,
      sendShortcut: 'enter',
      autoOpenFileEnabled: true,
      usagePercentage: null,
      usageUsedTokens: null,
      usageMaxTokens: null,
      setPermissionMode: noop,
      setClaudePermissionMode: noop,
      setCodexPermissionMode: noop,
      setSelectedClaudeModel: noop,
      setSelectedCodexModel: noop,
      setProviderConfigVersion: noop,
      setActiveProviderConfig: noop,
      setClaudeSettingsAlwaysThinkingEnabled: noop,
      setStreamingEnabledSetting: noop,
      setSendShortcut: noop,
      setAutoOpenFileEnabled: noop,
      setSdkStatus: noop,
      setSdkStatusLoaded: noop,
      setSelectedAgent: noop,
      setUsagePercentage: noop,
      setUsageUsedTokens: noop,
      setUsageMaxTokens: noop,
      syncActiveProviderModelMapping: noop,
      handleModeSelect: noop,
      handleModelSelect: noop,
      handleProviderSelect: noop,
      handleReasoningChange: noop,
      handleAgentSelect: noop,
      handleToggleThinking: noop,
      handleStreamingEnabledChange: noop,
      handleSendShortcutChange: noop,
      handleAutoOpenFileEnabledChange: noop,
    }),
    useChatComputations: () => ({
      findToolResult: noop,
      getToolResultRaw: noop,
      fileChangeMgmt: {
        processedFiles: [],
        baseMessageIndex: 0,
        handleUndoFile: noop,
        handleDiscardAll: noop,
        handleKeepAll: noop,
      },
      filteredFileChanges: [],
      subagents: [],
      globalTodos: [],
      rewindableMessages: [],
      sessionTitle: 'test-session-title',
    }),
  };
});

describe('App task reminder callback integration', () => {
  const renderWithProviders = () => render(
    <MessagesProvider>
      <SessionProvider>
        <UIStateProvider>
          <DialogProvider>
            <App />
          </DialogProvider>
        </UIStateProvider>
      </SessionProvider>
    </MessagesProvider>
  );

  beforeEach(() => {
    mockSendBridgeEvent.mockReset();
    mockUseSessionManagement.mockReset();
    window.showTaskReminderDialog = undefined;
    window.__pendingTaskReminderDialogRequests = undefined;

    mockUseSessionManagement.mockReturnValue({
      showNewSessionConfirm: false,
      showInterruptConfirm: false,
      suppressNextStatusToastRef: { current: false },
      createNewSession: vi.fn(),
      forceCreateNewSession: vi.fn(),
      handleConfirmNewSession: vi.fn(),
      handleCancelNewSession: vi.fn(),
      handleConfirmInterrupt: vi.fn(),
      handleCancelInterrupt: vi.fn(),
      loadHistorySession: vi.fn(),
      deleteHistorySession: vi.fn(),
      deleteHistorySessions: vi.fn(),
      exportHistorySession: vi.fn(),
      toggleFavoriteSession: vi.fn(),
      updateHistoryTitle: vi.fn(),
      syncCurrentTabTitle: vi.fn(),
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('passes real historyData into useSessionManagement instead of null', () => {
    renderWithProviders();

    expect(mockUseSessionManagement).toHaveBeenCalledTimes(1);
    const callArg = mockUseSessionManagement.mock.calls[0]?.[0] as Record<string, unknown>;
    expect(callArg).toHaveProperty('historyData');
    expect(callArg.historyData).not.toBeUndefined();
  });

  it('shows reminder dialog for valid waiting_confirm payload', async () => {
    // 合法 payload 到达后，App 应注册回调并真正把提醒弹窗渲染出来。
    renderWithProviders();

    await waitFor(() => {
      expect(typeof window.showTaskReminderDialog).toBe('function');
    });

    act(() => {
      window.showTaskReminderDialog?.(JSON.stringify({
        state: 'waiting_confirm',
        message: 'Please confirm this task in chat',
        sessionId: 's-1',
        requestId: 'r-1',
      }));
    });

    expect(screen.getByText('Task is waiting for confirmation')).toBeTruthy();
    expect(screen.getByText('Please confirm this task in chat')).toBeTruthy();
  });

  it('sends navigate_task_reminder when clicking open session', async () => {
    renderWithProviders();

    await waitFor(() => {
      expect(typeof window.showTaskReminderDialog).toBe('function');
    });

    act(() => {
      window.showTaskReminderDialog?.(JSON.stringify({
        state: 'waiting_confirm',
        message: 'Please confirm this task in chat',
        sessionId: 's-open',
        requestId: 'r-open',
      }));
    });

    fireEvent.click(screen.getByRole('button', { name: 'Open session' }));

    expect(mockSendBridgeEvent).toHaveBeenCalledWith(
      'navigate_task_reminder',
      JSON.stringify({ sessionId: 's-open', requestId: 'r-open' }),
    );
  });

  it('drains pending queue after callback registration and shows dialog', async () => {
    // 如果后端早于 React 初始化完成就塞入 pending 请求，App 挂载后应补回放。
    window.__pendingTaskReminderDialogRequests = [
      JSON.stringify({
        state: 'waiting_confirm',
        message: 'Queued reminder',
        sessionId: 's-2',
      }),
    ];

    renderWithProviders();

    expect(await screen.findByText('Queued reminder')).toBeTruthy();
    expect(window.__pendingTaskReminderDialogRequests).toEqual([]);
  });

  it('ignores invalid payload and unsupported state', async () => {
    // 非法 payload 不能污染状态树，也不应把弹窗错误打开。
    renderWithProviders();

    await waitFor(() => {
      expect(typeof window.showTaskReminderDialog).toBe('function');
    });

    act(() => {
      window.showTaskReminderDialog?.('invalid-json');
      window.showTaskReminderDialog?.(JSON.stringify({
        state: 'running',
        message: 'Should be ignored',
      }));
      window.showTaskReminderDialog?.(JSON.stringify({
        state: 'final_error',
      }));
    });

    expect(screen.queryByText('Should be ignored')).toBeNull();
    expect(screen.queryByText('Task requires attention')).toBeNull();
  });

  it('sends restart_session when clicking retry for final_error reminder', async () => {
    // final_error 弹窗点击 retry 后，应该复用既有 restart_session 协议。
    renderWithProviders();

    await waitFor(() => {
      expect(typeof window.showTaskReminderDialog).toBe('function');
    });

    act(() => {
      window.showTaskReminderDialog?.(JSON.stringify({
        state: 'final_error',
        message: 'Execution failed, retry?',
      }));
    });

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    expect(mockSendBridgeEvent).toHaveBeenCalledWith('restart_session');
    expect(screen.queryByText('Execution failed, retry?')).toBeNull();
  });
});

describe('buildContinuedSegmentSwitchRequest', () => {
  it('prefers active segment and continuation anchors instead of overwriting sourceSessionId with currentSessionId', () => {
    // 中文注释：首次或多次切模型时，continued request 的 sourceSessionId 需要优先锚定活动分段，
    // 不能被瞬时 currentSessionId 回退覆盖为空，否则后端无法建立正确的 parent/source 关系。
    const request = buildContinuedSegmentSwitchRequest(
      {
        switchReason: 'model',
        targetProvider: 'codex',
        targetRuntimeFamily: 'codex',
        targetModel: 'gpt-5.4-mini',
      },
      {
        currentSessionId: null,
        logicalConversationId: 'logical-001',
        activeSegmentSessionId: 'segment-002',
        continuationPending: true,
      },
    );

    expect(request).toEqual(expect.objectContaining({
      logicalConversationId: 'logical-001',
      activeSegmentSessionId: 'segment-002',
      continuationSourceSessionId: 'segment-002',
      sourceSessionId: 'segment-002',
    }));
  });
});
