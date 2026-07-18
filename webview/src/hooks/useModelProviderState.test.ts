import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useModelProviderState } from './useModelProviderState';
import { sendBridgeEvent } from '../utils/bridge';
import { debugLog } from '../utils/debug';

vi.mock('../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
}));

vi.mock('../utils/debug', () => ({
  debugLog: vi.fn(),
}));

describe('useModelProviderState', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    window.sendToJava = vi.fn();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('ignores shared codex runtime state persisted in local storage', () => {
    localStorage.setItem('model-selection-state', JSON.stringify({
      provider: 'codex',
      codexModel: 'gpt-5-codex',
      claudeModel: 'claude-sonnet-4-6',
      codexPermissionMode: 'plan',
      claudePermissionMode: 'bypassPermissions',
    }));

    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(result.current.currentProvider).toBe('claude');
    expect(result.current.codexPermissionMode).toBe('default');
    expect(result.current.permissionMode).toBe('bypassPermissions');
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_mode', 'default');
  });

  it('downgrades codex plan selection to default before syncing backend', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.handleModeSelect('plan');
    });

    expect(result.current.codexPermissionMode).toBe('default');
    expect(result.current.permissionMode).toBe('default');
    expect(sendBridgeEvent).toHaveBeenCalledWith('set_mode', 'default');
  });

  it('keeps plan mode when selecting plan under claude provider', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('claude');
      result.current.handleModeSelect('plan');
    });

    expect(result.current.currentProvider).toBe('claude');
    expect(result.current.claudePermissionMode).toBe('plan');
    expect(result.current.permissionMode).toBe('plan');
    expect(sendBridgeEvent).toHaveBeenCalledWith('set_mode', 'plan');
  });

  it('shows downgrade toast when switching from claude plan to codex', () => {
    const addToast = vi.fn();
    const { result } = renderHook(() => useModelProviderState({
      addToast,
      t: ((key: string, options?: { defaultValue?: string }) => options?.defaultValue ?? key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('claude');
      result.current.handleModeSelect('plan');
    });

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    expect(result.current.currentProvider).toBe('codex');
    expect(result.current.permissionMode).toBe('default');
    expect(addToast).toHaveBeenCalledWith(
      'Codex does not support Plan mode. Switched back to Chat/default.',
      'warning',
    );
  });

  it('keeps unknown codex model ids when the current tab selects them explicitly', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.setActiveCodexProviderId('provider-a');
    });

    act(() => {
      result.current.handleModelSelect('gpt-5.5');
    });

    expect(result.current.currentProvider).toBe('codex');
    expect(result.current.selectedCodexModel).toBe('gpt-5.5');
    // Codex 选择器现在只持久化当前标签页的目标选择，不再直接改写 live runtime。
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'set_selected_codex_model',
      JSON.stringify({
        providerId: 'provider-a',
        modelId: 'gpt-5.5',
      }),
    );
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_model', 'gpt-5.5');
  });

  it('keeps user-selected codex session model while storing cli default metadata separately', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.handleModelSelect('gpt-5.4');
    });

    expect(result.current.selectedCodexModel).toBe('gpt-5.4');
    expect(result.current.defaultCodexModelFromConfig).toBeNull();
  });

  it('stops adopting cli default reasoning after the user manually changes codex reasoning effort', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.handleReasoningChange('low');
    });

    expect(result.current.shouldAdoptCodexDefaultReasoningEffortRef.current).toBe(false);
    // 推理强度切换现在只更新聊天区 desired selection，不应再直接改写 live session。
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_reasoning_effort', 'low');
  });

  it('keeps claude model selection local and does not send live set_model events', () => {
    /**
     * 中文注释：Claude 选择器与 Codex 一样，应只更新“下一条消息的目标模型”。
     * 若这里仍然立刻发送 set_model，旧运行中的会话会被提前污染，破坏发送时静默切段语义。
     */
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('claude');
    });

    act(() => {
      result.current.handleModelSelect('claude-opus-4-7');
    });

    expect(result.current.selectedClaudeModel).toBe('claude-opus-4-7');
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_model', expect.stringContaining('claude-opus-4-7'));
  });

  it('keeps claude long-context toggles local and does not send live set_model events', () => {
    /**
     * 中文注释：long-context 开关本质上也是 desired selection 的一部分。
     * 它只能影响下一次发送携带的 runtime intent，不能在切换时立刻改写当前 live runtime。
     */
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('claude');
    });

    act(() => {
      result.current.handleLongContextChange(true);
    });

    expect(result.current.longContextEnabled).toBe(true);
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_model', expect.any(String));
  });

  it('uses the provider id encoded in the composite catalog key for codex selection', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.handleModelSelect('managed-openai::gpt-5.5');
    });

    /**
     * 中文注释：
     * 这里直接覆盖本次回归的主根因。
     * 复合 catalog key 不只是为了给下拉框高亮，它还必须立刻把目标 Codex providerId
     * 写回聊天区的 desired runtime selection。
     * 否则发送时 resolveRuntimeIntentForMessage 读到的仍会是空 providerId，
     * 后端就会把本条消息误判成需要继续当前会话的新分段。
     */
    expect(result.current.activeCodexProviderId).toBe('managed-openai');
    expect(result.current.selectedCodexModel).toBe('gpt-5.5');
    expect(result.current.selectedCodexSelectionKey).toBe('managed-openai::gpt-5.5');
    expect(result.current.desiredRuntimeSelectionRef.current).toEqual(expect.objectContaining({
      provider: 'codex',
      model: 'gpt-5.5',
      codexProviderId: 'managed-openai',
    }));
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'set_selected_codex_model',
      JSON.stringify({
        providerId: 'managed-openai',
        modelId: 'gpt-5.5',
      }),
    );
  });

  it('falls back to the current tab codex provider id for plain model ids', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.setActiveCodexProviderId('provider-a');
    });

    act(() => {
      result.current.handleModelSelect('gpt-5.4');
    });

    act(() => {
      result.current.setActiveCodexProviderId('provider-b');
    });

    act(() => {
      result.current.handleModelSelect('gpt-5.5');
    });

    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'set_selected_codex_model',
      JSON.stringify({
        providerId: 'provider-a',
        modelId: 'gpt-5.4',
      }),
    );
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'set_selected_codex_model',
      JSON.stringify({
        providerId: 'provider-b',
        modelId: 'gpt-5.5',
      }),
    );
    // provider 变化同样只更新 desired selection 持久化，不应同步触发 live session 的 set_model。
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_model', 'gpt-5.5');
  });

  it('does not request a continued codex segment payload when switching provider or model', () => {
    const onCodexConversationConfigChanged = vi.fn();
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
      onCodexConversationConfigChanged,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.setActiveCodexProviderId('managed-buycode');
      result.current.handleModelSelect('gpt-5.4');
    });

    expect(onCodexConversationConfigChanged).not.toHaveBeenCalled();
  });

  it('does not reset the current codex tab when global active provider changes elsewhere', () => {
    const onCodexConversationConfigChanged = vi.fn();
    renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
      onCodexConversationConfigChanged,
    }));

    expect(onCodexConversationConfigChanged).not.toHaveBeenCalledWith('activeProvider');
  });

  it('uses tab-local desired selection persistence for codex changes and never triggers live runtime switching events', () => {
    const tabAConversationChanged = vi.fn();
    const tabBConversationChanged = vi.fn();
    const tabA = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
      onCodexConversationConfigChanged: tabAConversationChanged,
    }));
    const tabB = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
      onCodexConversationConfigChanged: tabBConversationChanged,
    }));

    act(() => {
      tabA.result.current.handleProviderSelect('codex');
    });
    act(() => {
      tabA.result.current.setActiveCodexProviderId('managed-openai');
    });
    act(() => {
      tabA.result.current.handleModelSelect('gpt-5.4');
    });

    act(() => {
      tabB.result.current.handleProviderSelect('codex');
    });
    act(() => {
      tabB.result.current.setActiveCodexProviderId('managed-minimax');
    });
    act(() => {
      tabB.result.current.handleModelSelect('MiniMax-M3');
    });

    expect(tabA.result.current.currentProvider).toBe('codex');
    expect(tabAConversationChanged).not.toHaveBeenCalled();

    expect(tabB.result.current.currentProvider).toBe('codex');
    expect(tabBConversationChanged).not.toHaveBeenCalled();

    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'set_selected_codex_model',
      JSON.stringify({
        providerId: 'managed-openai',
        modelId: 'gpt-5.4',
      }),
    );
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'set_selected_codex_model',
      JSON.stringify({
        providerId: 'managed-minimax',
        modelId: 'MiniMax-M3',
      }),
    );
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_provider', 'codex');
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_model', expect.anything());
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('switch_codex_provider', expect.anything());
  });

  it('keeps selection changes local when switching provider and model inside the current tab', () => {
    const onCodexConversationConfigChanged = vi.fn();
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
      onCodexConversationConfigChanged,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.setActiveCodexProviderId('managed-minimax');
    });

    act(() => {
      result.current.handleModelSelect('MiniMax-M3');
    });

    expect(debugLog).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] providerSelect',
      expect.objectContaining({
        previousProvider: 'claude',
        nextProvider: 'codex',
      }),
    );
    expect(debugLog).toHaveBeenCalledWith(
      '[CODEX_RUNTIME_TRACE][Webview] codexModelSelect',
      expect.objectContaining({
        targetProviderId: 'managed-minimax',
        targetModelId: 'MiniMax-M3',
      }),
    );
    expect(onCodexConversationConfigChanged).not.toHaveBeenCalled();
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_provider', 'codex');
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('set_model', expect.anything());
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('select_codex_model', expect.anything());
  });

  it('records desired-selection-only diagnostics for provider, model, and reasoning changes', () => {
    /**
     * 中文注释：
     * 该用例直接覆盖 Task 2 Step 6。
     * 聊天区下拉框切换后，日志里必须显式说明“不会立刻切 runtime”，
     * 同时带上切换前后 desired runtime 与当前 active runtime snapshot，便于排查
     * “为何旧任务仍在原模型执行，而下一条消息会在发送时静默切段”。
     */
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.setActiveSessionRuntimeSnapshot({
        provider: 'claude',
        model: 'claude-sonnet-4-6',
        reasoningEffort: 'high',
        codexProviderId: '',
      });
    });

    const findTracePayload = (eventName: string) => {
      const matched = (debugLog as any).mock.calls.find(
        (call: unknown[]) => call[0] === `[CODEX_RUNTIME_TRACE][Webview] ${eventName}`,
      );
      expect(matched, `missing runtime trace for ${eventName}`).toBeTruthy();
      return matched?.[1];
    };

    const providerBeforeChange = {
      ...result.current.desiredRuntimeSelectionRef.current,
    };
    act(() => {
      result.current.handleProviderSelect('codex');
    });

    const providerPayload = findTracePayload('providerSelect');
    expect(providerPayload).toEqual(expect.objectContaining({
      willSwitchNow: false,
      previousDesiredRuntime: providerBeforeChange,
      nextDesiredRuntime: expect.objectContaining({
        provider: 'codex',
      }),
      activeRuntimeSnapshot: expect.objectContaining({
        provider: 'claude',
        model: 'claude-sonnet-4-6',
      }),
    }));

    act(() => {
      result.current.setActiveCodexProviderId('managed-openai');
    });
    const modelBeforeChange = {
      ...result.current.desiredRuntimeSelectionRef.current,
    };
    act(() => {
      result.current.handleModelSelect('gpt-5.4');
    });

    const modelPayload = findTracePayload('codexModelSelect');
    expect(modelPayload).toEqual(expect.objectContaining({
      willSwitchNow: false,
      previousDesiredRuntime: modelBeforeChange,
      nextDesiredRuntime: expect.objectContaining({
        provider: 'codex',
        model: 'gpt-5.4',
        codexProviderId: 'managed-openai',
      }),
      activeRuntimeSnapshot: expect.objectContaining({
        provider: 'claude',
        model: 'claude-sonnet-4-6',
      }),
    }));

    const reasoningBeforeChange = {
      ...result.current.desiredRuntimeSelectionRef.current,
    };
    act(() => {
      result.current.handleReasoningChange('low');
    });

    const reasoningPayload = findTracePayload('reasoningEffortSelect');
    expect(reasoningPayload).toEqual(expect.objectContaining({
      willSwitchNow: false,
      previousDesiredRuntime: reasoningBeforeChange,
      nextDesiredRuntime: expect.objectContaining({
        provider: 'codex',
        model: 'gpt-5.4',
        reasoningEffort: 'low',
      }),
      activeRuntimeSnapshot: expect.objectContaining({
        provider: 'claude',
        model: 'claude-sonnet-4-6',
      }),
    }));
  });
});
