import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import * as debugModule from '../utils/debug';
import { buildRuntimeIntentFromSelection } from '../types/runtimeIntent';
import {
  useMessageQueue,
  type QueuedMessage,
} from './useMessageQueue';

/**
 * useMessageQueue 回归测试。
 * 该文件同时覆盖普通聊天动态解析队列、锁定任务 envelope 以及队列诊断日志，
 * 对应方案文档里的 Task 3 Step 7、Step 8 和 Step 10。
 */
describe('useMessageQueue', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(debugModule, 'debugLog').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  /**
   * 构造一个入队时即锁定的 Codex runtime intent。
   * 这里固定使用 model A，便于后续断言聊天区切到 model C 后队列仍保留原始目标 runtime。
   *
   * @return 供锁定任务复用的稳定 runtime intent
   */
  const createLockedRuntimeIntent = () => buildRuntimeIntentFromSelection(
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

  it('executes queued chat messages through the latest onExecute callback after runtime selection changes', () => {
    /**
     * 中文注释：
     * 先在 loading=true 时把两条普通消息排队，然后模拟用户已经把聊天区目标模型切到 model-c；
     * 最后确认两条消息都通过最新 onExecute 执行，而不是沿用入队时旧的 model-a 闭包。
     */
    const executionOrder: string[] = [];
    const onExecuteModelA = vi.fn((message: QueuedMessage) => {
      executionOrder.push(`model-a:${message.content}`);
    });
    const onExecuteModelC = vi.fn((message: QueuedMessage) => {
      executionOrder.push(`model-c:${message.content}`);
    });

    const { result, rerender } = renderHook(
      ({ isLoading, onExecute }: { isLoading: boolean; onExecute: (message: QueuedMessage) => void }) => useMessageQueue({
        isLoading,
        onExecute,
      }),
      {
        initialProps: {
          isLoading: true,
          onExecute: onExecuteModelA,
        },
      },
    );

    act(() => {
      result.current.enqueue('message-1');
      result.current.enqueue('message-2');
    });

    expect(result.current.queue).toHaveLength(2);
    expect(result.current.queue[0]).toEqual(expect.objectContaining({
      kind: 'chat',
      content: 'message-1',
      runtimeIntentResolution: 'dynamic_at_execution',
    }));
    expect(result.current.queue[0]).not.toHaveProperty('lockedBy');
    expect(result.current.queue[0]).not.toHaveProperty('lockedRuntimeIntent');

    act(() => {
      rerender({
        isLoading: true,
        onExecute: onExecuteModelC,
      });
    });
    act(() => {
      rerender({
        isLoading: false,
        onExecute: onExecuteModelC,
      });
    });
    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(executionOrder).toEqual(['model-c:message-1']);
    expect(onExecuteModelA).not.toHaveBeenCalled();
    expect(onExecuteModelC).toHaveBeenCalledTimes(1);
    expect(onExecuteModelC).toHaveBeenNthCalledWith(1, expect.objectContaining({
      kind: 'chat',
      content: 'message-1',
      runtimeIntentResolution: 'dynamic_at_execution',
    }));

    act(() => {
      rerender({
        isLoading: true,
        onExecute: onExecuteModelC,
      });
    });
    act(() => {
      rerender({
        isLoading: false,
        onExecute: onExecuteModelC,
      });
    });
    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(executionOrder).toEqual(['model-c:message-1', 'model-c:message-2']);
    expect(onExecuteModelC).toHaveBeenCalledTimes(2);
    expect(onExecuteModelC).toHaveBeenNthCalledWith(2, expect.objectContaining({
      kind: 'chat',
      content: 'message-2',
      runtimeIntentResolution: 'dynamic_at_execution',
    }));
  });

  it('preserves locked runtime intent while queued and emits separate chat/locked queue diagnostics', () => {
    /**
     * 中文注释：
     * 该用例同时覆盖两件事：
     * 1. 锁定任务在排队期间即便用户继续切聊天区模型，出队时仍要保留入队时锁定的 runtime intent。
     * 2. 队列日志必须明确区分普通消息与锁定任务，普通消息不能带 lockedRuntimeIntent。
     */
    const lockedRuntimeIntent = createLockedRuntimeIntent();
    const onExecute = vi.fn();
    const debugLogSpy = vi.spyOn(debugModule, 'debugLog');

    const { result, rerender } = renderHook(
      ({ isLoading }: { isLoading: boolean }) => useMessageQueue({
        isLoading,
        onExecute,
      }),
      {
        initialProps: {
          isLoading: true,
        },
      },
    );

    act(() => {
      result.current.enqueueLockedTask('plan-step-1', {
        lockedBy: 'plan_subtask',
        lockedRuntimeIntent,
      });
      result.current.enqueue('chat-follow-up');
    });

    expect(result.current.queue).toHaveLength(2);
    expect(result.current.queue[0]).toEqual(expect.objectContaining({
      kind: 'locked_task',
      content: 'plan-step-1',
      runtimeIntentResolution: 'locked_at_enqueue',
      lockedBy: 'plan_subtask',
      lockedRuntimeIntent,
    }));
    expect(result.current.queue[1]).toEqual(expect.objectContaining({
      kind: 'chat',
      content: 'chat-follow-up',
      runtimeIntentResolution: 'dynamic_at_execution',
    }));
    expect(result.current.queue[1]).not.toHaveProperty('lockedBy');
    expect(result.current.queue[1]).not.toHaveProperty('lockedRuntimeIntent');

    act(() => {
      rerender({ isLoading: false });
    });
    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(onExecute).toHaveBeenNthCalledWith(1, expect.objectContaining({
      kind: 'locked_task',
      content: 'plan-step-1',
      lockedBy: 'plan_subtask',
      lockedRuntimeIntent,
    }));

    act(() => {
      rerender({ isLoading: true });
    });
    act(() => {
      rerender({ isLoading: false });
    });
    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(onExecute).toHaveBeenNthCalledWith(2, expect.objectContaining({
      kind: 'chat',
      content: 'chat-follow-up',
      runtimeIntentResolution: 'dynamic_at_execution',
    }));

    expect(debugLogSpy).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] lockedTaskQueued',
      expect.objectContaining({
        runtimeIntentResolution: 'locked_at_enqueue',
        lockedBy: 'plan_subtask',
        targetRuntime: expect.objectContaining({
          targetModel: 'gpt-5.4',
          targetReasoningEffort: 'low',
        }),
      }),
    );
    expect(debugLogSpy).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] lockedTaskDequeuedResolveRuntime',
      expect.objectContaining({
        runtimeIntentResolution: 'locked_at_enqueue',
        lockedBy: 'plan_subtask',
        targetRuntime: expect.objectContaining({
          targetProvider: 'codex',
          targetModel: 'gpt-5.4',
        }),
      }),
    );
    expect(debugLogSpy).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] chatMessageQueued',
      expect.objectContaining({
        runtimeIntentResolution: 'dynamic_at_execution',
        lockedBy: null,
        lockedRuntimeIntentPresent: false,
      }),
    );
    expect(debugLogSpy).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] chatMessageDequeuedResolveRuntime',
      expect.objectContaining({
        runtimeIntentResolution: 'dynamic_at_execution',
        lockedBy: null,
        lockedRuntimeIntentPresent: false,
      }),
    );
  });
});
