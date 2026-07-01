import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import HistoryView from './components/history/HistoryView';
import SettingsView from './components/settings';
import { sendBridgeEvent } from './utils/bridge';
import { preloadSlashCommands, forceRefreshPrompts } from './components/ChatInputBox/providers';
import {
  useScrollBehavior,
  useSessionManagement,
  useStreamingMessages,
  useWindowCallbacks,
  useRewindHandlers,
  useHistoryLoader,
  useMessageQueue,
  useThemeInit,
  useContextActions,
  useMessageProcessing,
  useMessageSender,
  useModelProviderState,
  useChatComputations,
} from './hooks';
import {
  NEW_SESSION_COMMANDS,
  PLAN_COMMANDS,
  RESUME_COMMANDS,
  CONTEXT_COMMANDS,
} from './hooks/useMessageSender';
import { applyDiffTheme, getStoredDiffTheme } from './utils/diffTheme';
import type { Attachment, ChatInputBoxHandle } from './components/ChatInputBox/types';
import { ChatHeader } from './components/ChatHeader';
import { ChatModeStrip } from './components/ChatModeStrip';
import { AppDialogs } from './components/AppDialogs';
import TaskReminderDialog, { type TaskReminderDialogRequest } from './components/TaskReminderDialog';
import { ToastContainer } from './components/Toast';
import { ChatScreen } from './components/ChatScreen';
import {
  KNOWN_TASK_STATES,
  type TaskStripState,
} from './components/StatusPanel/types';
import { useSubagentContextValues } from './contexts/SubagentContext';
import { useMessages } from './contexts/MessagesContext';
import { useSession } from './contexts/SessionContext';
import { useUIState } from './contexts/UIStateContext';
import { useDialogs } from './contexts/DialogContext';
import { getComposerUsageMode } from './components/ChatInputBox/modeViewModel';
import type { ToolResultBlock } from './types';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from './utils/permissionDialogTimeout';
import { debugLog } from './utils/debug';

/**
 * 仅允许弹出强提醒对话框的任务状态。
 * 其它状态继续通过顶部 mode strip、StatusPanel 和 toast 展示，避免打断用户操作流。
 */
const isTaskReminderState = (state: unknown): state is TaskReminderDialogRequest['state'] => (
  state === 'waiting_confirm' || state === 'final_error'
);

/**
 * 解析后端推送的任务提醒弹窗负载。
 * 这里只接收当前前端明确支持的字段，避免历史脏数据或不完整 JSON 污染状态树。
 *
 * @param json 后端传入的 JSON 字符串
 * @return 合法时返回标准化后的提醒请求；非法时返回 null
 */
const parseTaskReminderDialogPayload = (json: string): TaskReminderDialogRequest | null => {
  try {
    const parsed = JSON.parse(json) as Record<string, unknown>;
    if (!isTaskReminderState(parsed.state) || typeof parsed.message !== 'string') {
      return null;
    }
    return {
      state: parsed.state,
      message: parsed.message,
      sessionId: typeof parsed.sessionId === 'string' ? parsed.sessionId : undefined,
      requestId: typeof parsed.requestId === 'string' ? parsed.requestId : undefined,
    };
  } catch {
    return null;
  }
};

const App = () => {
  const { t } = useTranslation();

  const {
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
    openContextUsageDialog,
    updateContextUsageData,
    closeContextUsageDialog,
    setRewindDialogOpen,
    setCurrentRewindRequest,
    isRewinding,
    setIsRewinding,
    setRewindSelectDialogOpen,
  } = useDialogs();

  const {
    messages,
    setMessages,
    subagentHistories,
    setSubagentHistories,
    status,
    setStatus,
    loading,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    streamingActive,
    setStreamingActive,
  } = useMessages();

  const {
    currentSessionId,
    setCurrentSessionId,
    customSessionTitle,
    setCustomSessionTitle,
  } = useSession();

  const {
    currentView,
    setCurrentView,
    settingsInitialTab,
    setSettingsInitialTab,
    toasts,
    addToast,
    dismissToast,
    clearToasts,
    setContextInfo,
  } = useUIState();

  // ── Permission dialog timeout (synced with backend config) ──
  const [permissionDialogTimeoutSeconds, setPermissionDialogTimeoutSeconds] = useState(DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS);

  // ── Local refs (don't trigger re-render, kept in App.tsx) ──
  const isFirstMountRef = useRef(true);
  const chatInputRef = useRef<ChatInputBoxHandle>(null);
  const userCollapsedRef = useRef(false);
  const [, forceStatusUpdate] = useState(0);
  const [taskReminderRequest, setTaskReminderRequest] = useState<TaskReminderDialogRequest | null>(null);
  const [historyData, setHistoryData] = useState<null | Parameters<typeof HistoryView>[0]['historyData']>(null);

  const currentSessionIdRef = useRef(currentSessionId);
  useEffect(() => {
    currentSessionIdRef.current = currentSessionId;
  }, [currentSessionId]);

  const customSessionTitleRef = useRef(customSessionTitle);
  useEffect(() => {
    customSessionTitleRef.current = customSessionTitle;
  }, [customSessionTitle]);

  const messageNodeMapRef = useRef<Map<string, HTMLDivElement>>(new Map());
  const forceCreateNewSessionRef = useRef<(() => void) | null>(null);
  const [anchorCollapsedCount, setAnchorCollapsedCount] = useState(0);
  const handleMessageNodeRef = useCallback((id: string, node: HTMLDivElement | null) => {
    if (node) {
      messageNodeMapRef.current.set(id, node);
    } else {
      messageNodeMapRef.current.delete(id);
    }
  }, []);

  const handleShowTaskReminderDialog = useCallback((json: string) => {
    const payload = parseTaskReminderDialogPayload(json);
    if (!payload) {
      return;
    }
    setTaskReminderRequest(payload);
  }, []);

  const closeTaskReminderDialog = useCallback(() => {
    setTaskReminderRequest(null);
  }, []);

  const handleTaskReminderOpenSession = useCallback((request: TaskReminderDialogRequest) => {
    setCurrentView('chat');
    sendBridgeEvent(
      'navigate_task_reminder',
      JSON.stringify({
        sessionId: request.sessionId ?? null,
        requestId: request.requestId ?? null,
      }),
    );
    setTaskReminderRequest(null);
  }, [setCurrentView]);

  const handleTaskReminderRetry = useCallback(() => {
    setCurrentView('chat');
    sendBridgeEvent('restart_session');
    setTaskReminderRequest(null);
  }, [setCurrentView]);

  /**
   * 当 Codex 运行时配置发生变化时，强制切到新会话。
   * 这里通过 ref 延迟绑定 `forceCreateNewSession`，避免在 hook 初始化阶段提前引用后置变量，
   * 同时确保 provider、model 或 active provider 变化后不会继续复用旧线程。
   *
   * @param reason 触发会话重建的配置变更来源，仅用于诊断日志
   */
  const handleCodexConversationConfigChanged = useCallback((reason: 'provider' | 'model' | 'activeProvider') => {
    debugLog('[CODEX_RUNTIME_TRACE][Webview] handleCodexConversationConfigChanged', {
      reason,
      currentSessionId: currentSessionIdRef.current,
      customSessionTitle: customSessionTitleRef.current,
    });
    forceCreateNewSessionRef.current?.();
  }, []);

  useEffect(() => {
    window.showTaskReminderDialog = handleShowTaskReminderDialog;
    if (
      Array.isArray(window.__pendingTaskReminderDialogRequests)
      && window.__pendingTaskReminderDialogRequests.length > 0
    ) {
      const pending = window.__pendingTaskReminderDialogRequests.slice();
      window.__pendingTaskReminderDialogRequests = [];
      pending.forEach((json) => handleShowTaskReminderDialog(json));
    }

    return () => {
      if (window.showTaskReminderDialog === handleShowTaskReminderDialog) {
        window.showTaskReminderDialog = undefined;
      }
    };
  }, [handleShowTaskReminderDialog]);

  useThemeInit();
  useContextActions();

  useEffect(() => {
    const ideTheme = window.__INITIAL_IDE_THEME__ ?? null;
    applyDiffTheme(getStoredDiffTheme(), ideTheme);
  }, []);

  const {
    messagesContainerRef,
    messagesEndRef,
    inputAreaRef,
    isUserAtBottomRef,
    userPausedRef,
  } = useScrollBehavior({ currentView, messages, loading, streamingActive });

  const {
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    streamingMessageIndexRef,
    contentUpdateTimeoutRef,
    thinkingUpdateTimeoutRef,
    lastContentUpdateRef,
    lastThinkingUpdateRef,
    autoExpandedThinkingKeysRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    findLastAssistantIndex,
    extractRawBlocks,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,
  } = useStreamingMessages();

  const {
    currentProvider,
    selectedModel,
    selectedCodexSelectionKey,
    permissionMode,
    selectedAgent,
    sdkStatusLoaded,
    currentSdkInstalled,
    currentProviderRef,
    activeCodexProviderIdRef,
    shouldAdoptCodexDefaultModelRef,
    shouldAdoptCodexDefaultReasoningEffortRef,
    activeProviderConfig,
    claudeSettingsAlwaysThinkingEnabled,
    reasoningEffort,
    streamingEnabledSetting,
    sendShortcut,
    autoOpenFileEnabled,
    longContextEnabled,
    usagePercentage,
    usageUsedTokens,
    usageMaxTokens,
    setPermissionMode,
    setCurrentProvider,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedCodexSelectionKey,
    setActiveCodexProviderId,
    setDefaultCodexModelFromConfig,
    setCodexBaseUrl,
    setCodexUsesCustomBaseUrl,
    setReasoningEffort,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled,
    setStreamingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    rightClickOpenDevToolsEnabled,
    setRightClickOpenDevToolsEnabled,
    setSdkStatus,
    setSdkStatusLoaded,
    setSelectedAgent,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    syncActiveProviderModelMapping,
    handleModeSelect,
    handleModelSelect,
    handleProviderSelect,
    handleReasoningChange,
    handleAgentSelect,
    handleToggleThinking,
    handleStreamingEnabledChange,
    handleSendShortcutChange,
    handleAutoOpenFileEnabledChange,
    handleRightClickOpenDevToolsEnabledChange,
    handleLongContextChange,
  } = useModelProviderState({
    addToast,
    t,
    onCodexConversationConfigChanged: handleCodexConversationConfigChanged,
  });

  useEffect(() => {
    const preventExternalDrop = (e: DragEvent) => {
      const types = Array.from(e.dataTransfer?.types ?? []);
      const isExternalDrop = types.includes('Files') || types.includes('text/uri-list');
      if (!isExternalDrop) {
        return;
      }
      e.preventDefault();
      e.stopPropagation();
    };

    document.addEventListener('dragover', preventExternalDrop);
    document.addEventListener('drop', preventExternalDrop);
    document.addEventListener('dragenter', preventExternalDrop);

    return () => {
      document.removeEventListener('dragover', preventExternalDrop);
      document.removeEventListener('drop', preventExternalDrop);
      document.removeEventListener('dragenter', preventExternalDrop);
    };
  }, []);

  useEffect(() => {
    preloadSlashCommands();
    forceRefreshPrompts();
    const retryTimer = setTimeout(() => {
      forceRefreshPrompts();
    }, 1000);
    return () => clearTimeout(retryTimer);
  }, []);

  useEffect(() => {
    if (isFirstMountRef.current) {
      isFirstMountRef.current = false;
      return;
    }
    if (currentView === 'chat') {
      forceRefreshPrompts();
    }
  }, [currentView]);

  const {
    showNewSessionConfirm,
    showInterruptConfirm,
    suppressNextStatusToastRef,
    createNewSession,
    forceCreateNewSession,
    forceCreateNewSessionWithProvider,
    handleConfirmNewSession,
    handleCancelNewSession,
    handleConfirmInterrupt,
    handleCancelInterrupt,
    loadHistorySession,
    deleteHistorySession,
    deleteHistorySessions,
    exportHistorySession,
    toggleFavoriteSession,
    updateHistoryTitle,
    syncCurrentTabTitle,
    applyHistoryTitleLocal,
  } = useSessionManagement({
    messages,
    loading,
    historyData,
    currentSessionId,
    setHistoryData,
    setMessages,
    setCurrentView,
    setCurrentSessionId,
    setCustomSessionTitle,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setStatus,
    setLoading,
    setIsThinking,
    setStreamingActive,
    clearToasts,
    addToast,
    t,
  });

  /**
   * 当 Codex 的 provider 或 model 发生变化时，强制重建会话。
   * 这里复用既有 `create_new_session` 链路，确保前端 `currentSessionId`
   * 与后端缓存的 Codex `threadId` 一起失效，避免新配置继续沿用旧线程。
   *
   * @param reason 触发重建的配置变化来源，仅用于诊断日志
   */
  forceCreateNewSessionRef.current = forceCreateNewSession;

  useHistoryLoader({ currentView, currentProvider });

  useWindowCallbacks({
    t,
    addToast,
    clearToasts,
    setMessages,
    setStatus,
    setLoading,
    setLoadingStartTime,
    setIsThinking,
    setStreamingActive,
    setHistoryData,
    setCurrentSessionId,
    setCustomSessionTitle,
    setUsagePercentage,
    setUsageUsedTokens,
    setUsageMaxTokens,
    setCurrentProvider,
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setSelectedCodexSelectionKey,
    setActiveCodexProviderId,
    setDefaultCodexModelFromConfig,
    setCodexBaseUrl,
    setCodexUsesCustomBaseUrl,
    setReasoningEffort,
    setProviderConfigVersion,
    setActiveProviderConfig,
    setClaudeSettingsAlwaysThinkingEnabled,
    setStreamingEnabledSetting,
    setSendShortcut,
    setAutoOpenFileEnabled,
    setRightClickOpenDevToolsEnabled,
    setSdkStatus,
    setSdkStatusLoaded,
    setIsRewinding,
    setRewindDialogOpen,
    setCurrentRewindRequest,
    setContextInfo,
    setSelectedAgent,
    setSubagentHistories,
    currentProviderRef,
    activeCodexProviderIdRef,
    shouldAdoptCodexDefaultModelRef,
    shouldAdoptCodexDefaultReasoningEffortRef,
    messagesContainerRef,
    isUserAtBottomRef,
    userPausedRef,
    suppressNextStatusToastRef,
    streamingContentRef,
    streamingThinkingRef,
    isStreamingRef,
    useBackendStreamingRenderRef,
    autoExpandedThinkingKeysRef,
    streamingMessageIndexRef,
    streamingTurnIdRef,
    turnIdCounterRef,
    lastContentUpdateRef,
    contentUpdateTimeoutRef,
    lastThinkingUpdateRef,
    thinkingUpdateTimeoutRef,
    findLastAssistantIndex,
    extractRawBlocks,
    getOrCreateStreamingAssistantIndex,
    patchAssistantForStreaming,
    syncActiveProviderModelMapping,
    openPermissionDialog,
    openAskUserQuestionDialog,
    openPlanApprovalDialog,
    openContextUsageDialog,
    updateContextUsageData,
    closeContextUsageDialog,
    customSessionTitleRef,
    currentSessionIdRef,
    updateHistoryTitle,
    applyHistoryTitleLocal,
    setPermissionDialogTimeoutSeconds,
  });

  const {
    getMessageText,
    getContentBlocks,
    mergedMessages,
    sentAttachmentsRef,
  } = useMessageProcessing({ messages, currentSessionId, t });

  const wrappedHandleProviderSelect = useCallback((providerId: string) => {
    chatInputRef.current?.clear();
    handleProviderSelect(providerId);
    forceCreateNewSessionWithProvider(providerId);
  }, [forceCreateNewSessionWithProvider, handleProviderSelect]);

  const {
    handleSubmit: hookHandleSubmit,
    executeMessage,
    interruptSession,
  } = useMessageSender({
    t,
    addToast,
    currentProvider,
    selectedModel,
    permissionMode,
    selectedAgent,
    sdkStatusLoaded,
    currentSdkInstalled,
    sentAttachmentsRef,
    chatInputRef,
    messagesContainerRef,
    isUserAtBottomRef,
    userPausedRef,
    isStreamingRef,
    setMessages,
    setLoading,
    setLoadingStartTime,
    setStreamingActive,
    setSettingsInitialTab,
    setCurrentView,
    forceCreateNewSession,
    handleModeSelect,
    longContextEnabled,
    openContextUsageDialog,
    closeContextUsageDialog,
  });

  const {
    queue: messageQueue,
    enqueue: enqueueMessage,
    dequeue: dequeueMessage,
  } = useMessageQueue({ isLoading: loading, onExecute: executeMessage });

  const handleSubmit = useCallback((content: string, attachments?: Attachment[]) => {
    const text = content.replace(/[\u200B-\u200D\uFEFF]/g, '').trim();
    const hasAttachments = Array.isArray(attachments) && attachments.length > 0;
    if (!text && !hasAttachments) {
      return;
    }

    if (text.startsWith('/')) {
      const command = text.split(/\s+/)[0].toLowerCase();
      if (NEW_SESSION_COMMANDS.has(command)) {
        forceCreateNewSession();
        return;
      }
      if (RESUME_COMMANDS.has(command)) {
        setCurrentView('history');
        return;
      }
      if (PLAN_COMMANDS.has(command)) {
        if (currentProvider === 'codex') {
          addToast(
            t('chat.planModeNotAvailableForCodex', {
              defaultValue: 'Plan mode is not available for Codex provider',
            }),
            'warning',
          );
        } else {
          handleModeSelect('plan');
          addToast(
            t('chat.planModeEnabled', {
              defaultValue: 'Plan mode enabled',
            }),
            'info',
          );
        }
        return;
      }
      if (CONTEXT_COMMANDS.has(command)) {
        hookHandleSubmit(content, attachments);
        return;
      }
    }

    if (loading) {
      enqueueMessage(content, attachments);
      return;
    }

    hookHandleSubmit(content, attachments);
  }, [
    addToast,
    currentProvider,
    enqueueMessage,
    forceCreateNewSession,
    handleModeSelect,
    hookHandleSubmit,
    loading,
    setCurrentView,
    t,
  ]);

  const {
    findToolResult,
    getToolResultRaw,
    fileChangeMgmt,
    filteredFileChanges,
    subagents,
    globalTodos,
    rewindableMessages,
    sessionTitle,
  } = useChatComputations({
    t,
    messages,
    mergedMessages,
    customSessionTitle,
    streamingActive,
    currentProvider,
    currentSessionId,
    currentSessionIdRef,
    getMessageText,
    getContentBlocks,
  });

  const { handleUndoFile, handleDiscardAll: handleDiscardAllRaw, handleKeepAll } = fileChangeMgmt;
  const onDiscardAll = useCallback(() => {
    handleDiscardAllRaw(filteredFileChanges);
  }, [filteredFileChanges, handleDiscardAllRaw]);

  const { subagentHistoryCtxValue, sessionIdCtxValue } = useSubagentContextValues(
    subagentHistories,
    currentSessionId,
  );

  const handleNavigateToProviderSettings = useCallback(() => {
    setSettingsInitialTab('providers');
    setCurrentView('settings');
  }, [setCurrentView, setSettingsInitialTab]);

  const {
    handleRewindConfirm,
    handleRewindCancel,
    handleOpenRewindSelectDialog,
    handleRewindSelect,
    handleRewindSelectCancel,
  } = useRewindHandlers({
    t,
    addToast,
    currentSessionId,
    mergedMessages,
    getMessageText,
    setCurrentRewindRequest,
    setRewindDialogOpen,
    setRewindSelectDialogOpen,
    setIsRewinding,
    isRewinding,
  });

  const statusPanelExpanded = !userCollapsedRef.current;
  const usageMode = getComposerUsageMode(permissionMode);
  const taskState = useMemo<TaskStripState | null>(() => {
    if (loading || streamingActive) {
      return 'running';
    }
    const normalized = status.trim().toLowerCase() as TaskStripState;
    return KNOWN_TASK_STATES.has(normalized) ? normalized : null;
  }, [loading, status, streamingActive]);

  return (
    <>
      <ToastContainer messages={toasts} onDismiss={dismissToast} />
      <ChatHeader
        currentView={currentView}
        sessionTitle={sessionTitle}
        t={t}
        modeStrip={(
          <ChatModeStrip
            usageMode={usageMode}
            taskState={taskState}
            currentProvider={currentProvider as 'claude' | 'codex'}
          />
        )}
        onBack={() => setCurrentView('chat')}
        onNewSession={createNewSession}
        onNewTab={() => sendBridgeEvent('create_new_tab')}
        onHistory={() => setCurrentView('history')}
        onSettings={() => {
          setSettingsInitialTab(undefined);
          setCurrentView('settings');
        }}
        titleEditable
        onTitleChange={(newTitle) => {
          setCustomSessionTitle(newTitle);
          if (currentSessionId) {
            updateHistoryTitle(currentSessionId, newTitle);
          } else {
            syncCurrentTabTitle(newTitle);
          }
        }}
      />

      {currentView === 'settings' ? (
        <SettingsView
          onClose={() => setCurrentView('chat')}
          initialTab={settingsInitialTab}
          currentProvider={currentProvider}
          streamingEnabled={streamingEnabledSetting}
          onStreamingEnabledChange={handleStreamingEnabledChange}
          sendShortcut={sendShortcut}
          onSendShortcutChange={handleSendShortcutChange}
          autoOpenFileEnabled={autoOpenFileEnabled}
          onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
          rightClickOpenDevToolsEnabled={rightClickOpenDevToolsEnabled}
          onRightClickOpenDevToolsEnabledChange={handleRightClickOpenDevToolsEnabledChange}
          permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
          onPermissionDialogTimeoutChange={setPermissionDialogTimeoutSeconds}
        />
      ) : currentView === 'chat' ? (
        <ChatScreen
          mergedMessages={mergedMessages}
          getMessageText={getMessageText}
          getContentBlocks={getContentBlocks}
          findToolResult={findToolResult as (toolUseId?: string, messageIndex?: number) => ToolResultBlock | null}
          getToolResultRaw={getToolResultRaw}
          subagents={subagents}
          globalTodos={globalTodos}
          filteredFileChanges={filteredFileChanges}
          subagentHistoryCtxValue={subagentHistoryCtxValue}
          sessionIdCtxValue={sessionIdCtxValue}
          chatInputRef={chatInputRef}
          messagesContainerRef={messagesContainerRef}
          messagesEndRef={messagesEndRef}
          inputAreaRef={inputAreaRef}
          messageNodeMapRef={messageNodeMapRef}
          userCollapsedRef={userCollapsedRef}
          anchorCollapsedCount={anchorCollapsedCount}
          setAnchorCollapsedCount={setAnchorCollapsedCount}
          onMessageNodeRef={handleMessageNodeRef}
          statusPanelExpanded={statusPanelExpanded}
          forceStatusUpdate={forceStatusUpdate}
          onUndoFile={handleUndoFile}
          onDiscardAll={onDiscardAll}
          onKeepAll={handleKeepAll}
          onSubmit={handleSubmit}
          onInterrupt={interruptSession}
          onRewind={handleOpenRewindSelectDialog}
          onNavigateToProviderSettings={handleNavigateToProviderSettings}
          onProviderSelect={wrappedHandleProviderSelect}
          currentProvider={currentProvider}
          selectedModel={selectedModel}
          selectedCodexSelectionKey={selectedCodexSelectionKey}
          permissionMode={permissionMode}
          selectedAgent={selectedAgent}
          sdkStatusLoaded={sdkStatusLoaded}
          currentSdkInstalled={currentSdkInstalled}
          activeProviderConfig={activeProviderConfig}
          claudeSettingsAlwaysThinkingEnabled={claudeSettingsAlwaysThinkingEnabled}
          reasoningEffort={reasoningEffort}
          streamingEnabledSetting={streamingEnabledSetting}
          sendShortcut={sendShortcut}
          autoOpenFileEnabled={autoOpenFileEnabled}
          rightClickOpenDevToolsEnabled={rightClickOpenDevToolsEnabled}
          longContextEnabled={longContextEnabled}
          usagePercentage={usagePercentage}
          usageUsedTokens={usageUsedTokens}
          usageMaxTokens={usageMaxTokens}
          onModeSelect={handleModeSelect}
          onModelSelect={handleModelSelect}
          onAgentSelect={handleAgentSelect}
          onReasoningChange={handleReasoningChange}
          onToggleThinking={handleToggleThinking}
          onStreamingEnabledChange={handleStreamingEnabledChange}
          onAutoOpenFileEnabledChange={handleAutoOpenFileEnabledChange}
          onRightClickOpenDevToolsEnabledChange={handleRightClickOpenDevToolsEnabledChange}
          onLongContextChange={handleLongContextChange}
          messageQueue={messageQueue}
          onRemoveFromQueue={dequeueMessage}
        />
      ) : (
        <HistoryView
          historyData={historyData}
          currentProvider={currentProvider}
          onLoadSession={loadHistorySession}
          onDeleteSession={deleteHistorySession}
          onDeleteSessions={deleteHistorySessions}
          onExportSession={exportHistorySession}
          onToggleFavorite={toggleFavoriteSession}
          onUpdateTitle={updateHistoryTitle}
        />
      )}

      <div id="image-preview-root" />

      <AppDialogs
        showNewSessionConfirm={showNewSessionConfirm}
        onConfirmNewSession={handleConfirmNewSession}
        onCancelNewSession={handleCancelNewSession}
        showInterruptConfirm={showInterruptConfirm}
        onConfirmInterrupt={handleConfirmInterrupt}
        onCancelInterrupt={handleCancelInterrupt}
        rewindableMessages={rewindableMessages}
        onRewindSelect={handleRewindSelect}
        onRewindSelectCancel={handleRewindSelectCancel}
        onRewindConfirm={handleRewindConfirm}
        onRewindCancel={handleRewindCancel}
        currentProvider={currentProvider}
        permissionDialogTimeoutSeconds={permissionDialogTimeoutSeconds}
      />

      <TaskReminderDialog
        isOpen={taskReminderRequest !== null}
        request={taskReminderRequest}
        onOpenSession={handleTaskReminderOpenSession}
        onDismiss={closeTaskReminderDialog}
        onRetry={handleTaskReminderRetry}
      />
    </>
  );
};

export default App;
