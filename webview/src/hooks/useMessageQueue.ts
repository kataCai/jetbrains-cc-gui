import { useState, useCallback, useRef, useEffect } from 'react';
import type { Attachment } from '../components/ChatInputBox/types';
import type {
  LockedRuntimeIntentSource,
  RuntimeIntent,
  RuntimeIntentResolutionPolicy,
} from '../types/runtimeIntent';
import { debugLog } from '../utils/debug';

/**
 * 队列消息的公共字段。
 * 普通聊天与锁定任务都会复用这组元数据，便于 UI 展示与诊断日志统一串联 queueId。
 */
interface BaseQueuedMessage {
  id: string;
  content: string;
  attachments?: Attachment[];
  queuedAt: number;
  runtimeIntentResolution: RuntimeIntentResolutionPolicy;
}

/**
 * 普通聊天消息的排队结构。
 * 这里刻意不保存 target provider/model，确保出队时始终按“执行当下”的最新选择态重新解析 runtime intent。
 */
export interface QueuedChatMessage extends BaseQueuedMessage {
  kind: 'chat';
  runtimeIntentResolution: 'dynamic_at_execution';
}

/**
 * 锁定任务的排队结构。
 * 该结构用于计划清单子任务等“入队时已明确指定模型”的场景，后续聊天区切换不应污染它。
 */
export interface LockedTaskEnvelope extends BaseQueuedMessage {
  kind: 'locked_task';
  runtimeIntentResolution: 'locked_at_enqueue';
  lockedBy: LockedRuntimeIntentSource;
  lockedRuntimeIntent: RuntimeIntent;
}

/**
 * 聊天输入区队列允许混排普通消息与锁定任务。
 * 两者通过 `kind`、`runtimeIntentResolution` 和 `lockedBy` 显式区分，避免普通消息误带锁定字段。
 */
export type QueuedMessage = QueuedChatMessage | LockedTaskEnvelope;

/**
 * 锁定任务入队参数。
 * 该结构只暴露必要字段，避免未来调用方再次散落 target provider/model 的临时传参。
 */
export interface EnqueueLockedTaskOptions {
  attachments?: Attachment[];
  lockedBy: LockedRuntimeIntentSource;
  lockedRuntimeIntent: RuntimeIntent;
}

export interface UseMessageQueueOptions {
  /** Whether AI is currently processing */
  isLoading: boolean;
  /** Callback to execute a queued message envelope */
  onExecute: (message: QueuedMessage) => void;
}

export interface UseMessageQueueReturn {
  /** Current queue */
  queue: QueuedMessage[];
  /** Add message to queue */
  enqueue: (content: string, attachments?: Attachment[]) => void;
  /** Add a runtime-locked task to queue */
  enqueueLockedTask: (content: string, options: EnqueueLockedTaskOptions) => void;
  /** Remove message from queue by id */
  dequeue: (id: string) => void;
  /** Clear entire queue */
  clearQueue: () => void;
  /** Whether queue has items */
  hasQueuedMessages: boolean;
}

/**
 * Hook for managing message queue.
 * 普通聊天队列刻意只保留内容和附件，不冻结 target provider/model；
 * 这样旧消息出队时才能按执行瞬间的最新 desired selection 解析 `runtimeIntent`。
 * 同时为计划清单等锁定任务预留独立 envelope，确保入队时锁定的 runtime 不会被后续聊天区切换污染。
 */
export function useMessageQueue({
  isLoading,
  onExecute,
}: UseMessageQueueOptions): UseMessageQueueReturn {
  const [queue, setQueue] = useState<QueuedMessage[]>([]);
  const prevLoadingRef = useRef(isLoading);
  const isExecutingFromQueueRef = useRef(false);

  /**
   * 统一记录队列诊断日志。
   * 这里只输出 queueId、锁定来源和目标 runtime 摘要，不打印完整 prompt 内容，避免日志泄露用户正文。
   *
   * @param event 本次诊断事件名
   * @param message 本次排队或出队的消息 envelope
   * @return 无返回值
   */
  const traceQueuedMessage = useCallback((event: string, message: QueuedMessage) => {
    const basePayload = {
      queueId: message.id,
      queuedAt: message.queuedAt,
      kind: message.kind,
      runtimeIntentResolution: message.runtimeIntentResolution,
      contentLength: message.content.length,
      attachmentCount: Array.isArray(message.attachments) ? message.attachments.length : 0,
    };
    if (message.kind === 'locked_task') {
      debugLog('[CODEX_RUNTIME_TRACE][Webview] ' + event, {
        ...basePayload,
        lockedBy: message.lockedBy,
        targetRuntime: {
          sourceKind: message.lockedRuntimeIntent.sourceKind,
          resolutionPolicy: message.lockedRuntimeIntent.resolutionPolicy,
          targetProvider: message.lockedRuntimeIntent.targetProvider,
          targetRuntimeFamily: message.lockedRuntimeIntent.targetRuntimeFamily,
          targetModel: message.lockedRuntimeIntent.targetModel,
          targetReasoningEffort: message.lockedRuntimeIntent.targetReasoningEffort,
          targetCodexProviderId: message.lockedRuntimeIntent.targetCodexProviderId,
        },
      });
      return;
    }
    debugLog('[CODEX_RUNTIME_TRACE][Webview] ' + event, {
      ...basePayload,
      lockedBy: null,
      lockedRuntimeIntentPresent: false,
    });
  }, []);

  // Generate unique ID
  const generateId = useCallback(() => {
    return `queue-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }, []);

  // Add message to queue
  const enqueue = useCallback((content: string, attachments?: Attachment[]) => {
    const newItem: QueuedChatMessage = {
      id: generateId(),
      kind: 'chat',
      content,
      attachments,
      queuedAt: Date.now(),
      runtimeIntentResolution: 'dynamic_at_execution',
    };
    setQueue(prev => [...prev, newItem]);
    traceQueuedMessage('chatMessageQueued', newItem);
  }, [generateId, traceQueuedMessage]);

  /**
   * 把锁定任务加入统一队列。
   * 锁定任务必须在入队时携带完整 `lockedRuntimeIntent`，后续出队执行时直接复用，不能再回退到聊天区当前选择器。
   *
   * @param content 任务展示内容
   * @param options 锁定来源、附件和完整目标 runtime
   * @return 无返回值
   */
  const enqueueLockedTask = useCallback((content: string, options: EnqueueLockedTaskOptions) => {
    const newItem: LockedTaskEnvelope = {
      id: generateId(),
      kind: 'locked_task',
      content,
      attachments: options.attachments,
      queuedAt: Date.now(),
      runtimeIntentResolution: 'locked_at_enqueue',
      lockedBy: options.lockedBy,
      lockedRuntimeIntent: options.lockedRuntimeIntent,
    };
    setQueue(prev => [...prev, newItem]);
    traceQueuedMessage('lockedTaskQueued', newItem);
  }, [generateId, traceQueuedMessage]);

  // Remove message from queue
  const dequeue = useCallback((id: string) => {
    setQueue(prev => prev.filter(item => item.id !== id));
  }, []);

  // Clear entire queue
  const clearQueue = useCallback(() => {
    setQueue([]);
  }, []);

  // Auto-execute next message when loading completes
  useEffect(() => {
    // Detect transition from loading to not loading
    const wasLoading = prevLoadingRef.current;
    prevLoadingRef.current = isLoading;

    // If just finished loading and queue has items, execute next
    if (wasLoading && !isLoading && !isExecutingFromQueueRef.current && queue.length > 0) {
      const nextMessage = queue[0];
      isExecutingFromQueueRef.current = true;

      // Remove from queue first
      setQueue(prev => prev.slice(1));

      // Execute with small delay to ensure state updates
      setTimeout(() => {
        traceQueuedMessage(
          nextMessage.kind === 'locked_task'
            ? 'lockedTaskDequeuedResolveRuntime'
            : 'chatMessageDequeuedResolveRuntime',
          nextMessage,
        );
        onExecute(nextMessage);
        isExecutingFromQueueRef.current = false;
      }, 50);
    }
  }, [isLoading, onExecute, queue, traceQueuedMessage]);

  return {
    queue,
    enqueue,
    enqueueLockedTask,
    dequeue,
    clearQueue,
    hasQueuedMessages: queue.length > 0,
  };
}
