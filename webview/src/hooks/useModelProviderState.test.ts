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
    expect(sendBridgeEvent).toHaveBeenCalledWith('set_model', 'gpt-5.5');
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'select_codex_model',
      JSON.stringify({
        providerId: 'provider-a',
        modelId: 'gpt-5.5',
      }),
    );
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
    expect(sendBridgeEvent).toHaveBeenCalledWith('set_reasoning_effort', 'low');
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

    expect(result.current.selectedCodexModel).toBe('gpt-5.5');
    expect(result.current.selectedCodexSelectionKey).toBe('managed-openai::gpt-5.5');
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'select_codex_model',
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
      'select_codex_model',
      JSON.stringify({
        providerId: 'provider-a',
        modelId: 'gpt-5.4',
      }),
    );
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'select_codex_model',
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

  it('does not reset the current codex tab when global active provider changes elsewhere', () => {
    const onCodexConversationConfigChanged = vi.fn();
    renderHook(() => useModelProviderState({
      addToast: vi.fn(),
      t: ((key: string) => key) as any,
      onCodexConversationConfigChanged,
    }));

    expect(onCodexConversationConfigChanged).not.toHaveBeenCalledWith('activeProvider');
  });

  it('uses tab-local provider/model events for codex runtime changes and never falls back to switch_codex_provider', () => {
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
    expect(tabAConversationChanged).toHaveBeenCalledWith('provider');
    expect(tabAConversationChanged).toHaveBeenCalledWith('model');

    expect(tabB.result.current.currentProvider).toBe('codex');
    expect(tabBConversationChanged).toHaveBeenCalledWith('provider');
    expect(tabBConversationChanged).toHaveBeenCalledWith('model');

    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'select_codex_model',
      JSON.stringify({
        providerId: 'managed-openai',
        modelId: 'gpt-5.4',
      }),
    );
    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'select_codex_model',
      JSON.stringify({
        providerId: 'managed-minimax',
        modelId: 'MiniMax-M3',
      }),
    );
    expect(sendBridgeEvent).not.toHaveBeenCalledWith('switch_codex_provider', expect.anything());
  });

  it('writes codex runtime trace logs when switching provider and model inside the current tab', () => {
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
    expect(onCodexConversationConfigChanged).toHaveBeenCalledWith('provider');
    expect(onCodexConversationConfigChanged).toHaveBeenCalledWith('model');
  });
});
