import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useModelProviderState } from './useModelProviderState';
import { sendBridgeEvent } from '../utils/bridge';

const runtimeProviderMock = vi.hoisted(() => {
  const activeCodexProviderListeners = new Set<(json: string) => void>();
  return {
    subscribeActiveCodexProvider: (listener: (json: string) => void) => {
      activeCodexProviderListeners.add(listener);
      return () => {
        activeCodexProviderListeners.delete(listener);
      };
    },
    emitActiveCodexProvider: (provider: Record<string, unknown>) => {
      const payload = JSON.stringify(provider);
      activeCodexProviderListeners.forEach((listener) => listener(payload));
    },
  };
});

vi.mock('../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
}));

vi.mock('../utils/runtimeProviderCapabilities', () => ({
  subscribeActiveCodexProvider: runtimeProviderMock.subscribeActiveCodexProvider,
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

  it('downgrades persisted codex plan mode to default on restore', () => {
    // 从本地存储恢复状态时，也必须执行 Codex plan -> default 的兼容降级。
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

    expect(result.current.currentProvider).toBe('codex');
    expect(result.current.codexPermissionMode).toBe('default');
    expect(result.current.permissionMode).toBe('default');
    expect(sendBridgeEvent).toHaveBeenCalledWith('set_mode', 'default');
  });

  it('downgrades codex plan selection to default before syncing backend', () => {
    // 用户在 Codex 下选 plan 时，前端本地状态和后端同步都应该看到 default。
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
    // Claude provider 仍支持 plan，因此不应做降级。
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

  it('restores unknown persisted codex model ids from local storage', () => {
    localStorage.setItem('model-selection-state', JSON.stringify({
      provider: 'codex',
      codexModel: 'gpt-5.5',
      claudeModel: 'claude-sonnet-4-6',
      codexPermissionMode: 'default',
      claudePermissionMode: 'bypassPermissions',
    }));

    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(result.current.currentProvider).toBe('codex');
    expect(result.current.selectedCodexModel).toBe('gpt-5.5');
    expect(sendBridgeEvent).toHaveBeenCalledWith('set_model', 'gpt-5.5');
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

  it('persists the selected codex model with the active codex provider id', () => {
    // 持久化 Codex 下拉框选中值时，必须带上真实 active provider id，
    // 否则 provider 切换后会把模型错误归属到当前瞬时 provider，导致恢复结果串味。
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      runtimeProviderMock.emitActiveCodexProvider({
        id: 'managed-openai',
        name: 'Managed OpenAI',
      });
    });

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      result.current.handleModelSelect('gpt-5.5');
    });

    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'set_selected_codex_model',
      JSON.stringify({
        providerId: 'managed-openai',
        modelId: 'gpt-5.5',
      }),
    );
  });

  /**
   * 验证 active Codex provider 切换后，后续模型选择会绑定到新的 provider id。
   * 这个场景直接覆盖“provider 切换后模型归属不能串味”的核心约束：
   * 即使前一个 provider 已经选过模型，新的选择事件也必须落到当前激活 provider 上，
   * 否则恢复 selected model 或后端摘要时就会把模型错归到旧 provider。
   */
  it('uses the latest active codex provider id after provider switching', () => {
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
    }));

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      runtimeProviderMock.emitActiveCodexProvider({
        id: 'provider-a',
        name: 'Provider A',
      });
    });
    act(() => {
      result.current.handleModelSelect('gpt-5.4');
    });

    act(() => {
      runtimeProviderMock.emitActiveCodexProvider({
        id: 'provider-b',
        name: 'Provider B',
      });
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
    expect(sendBridgeEvent).toHaveBeenCalledWith('set_model', 'gpt-5.5');
  });

  it('requests a new codex conversation when switching provider or model', () => {
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
      result.current.handleModelSelect('gpt-5.4');
    });

    expect(onCodexConversationConfigChanged).toHaveBeenCalledWith('provider');
    expect(onCodexConversationConfigChanged).toHaveBeenCalledWith('model');
  });

  it('requests a new codex conversation when active codex provider changes while staying on codex', () => {
    const onCodexConversationConfigChanged = vi.fn();
    const { result } = renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
      onCodexConversationConfigChanged,
    }));

    act(() => {
      runtimeProviderMock.emitActiveCodexProvider({
        id: 'provider-a',
        name: 'Provider A',
      });
    });

    expect(onCodexConversationConfigChanged).not.toHaveBeenCalled();

    act(() => {
      result.current.handleProviderSelect('codex');
    });

    act(() => {
      runtimeProviderMock.emitActiveCodexProvider({
        id: 'provider-b',
        name: 'Provider B',
      });
    });

    expect(onCodexConversationConfigChanged).toHaveBeenCalledWith('provider');
    expect(onCodexConversationConfigChanged).toHaveBeenCalledWith('activeProvider');
  });
});
