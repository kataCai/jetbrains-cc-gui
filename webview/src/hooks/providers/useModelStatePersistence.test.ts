import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useModelStatePersistence } from './useModelStatePersistence';

vi.mock('../../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
}));

function createOptions() {
  return {
    setCurrentProvider: vi.fn(),
    setSelectedClaudeModel: vi.fn(),
    setSelectedCodexModel: vi.fn(),
    setClaudePermissionMode: vi.fn(),
    setCodexPermissionMode: vi.fn(),
    setPermissionMode: vi.fn(),
    setLongContextEnabled: vi.fn(),
    setReasoningEffort: vi.fn(),
    currentProvider: 'claude',
    selectedClaudeModel: 'claude-sonnet-4-6',
    selectedCodexModel: 'gpt-5.5',
    claudePermissionMode: 'bypassPermissions',
    codexPermissionMode: 'default',
    longContextEnabled: true,
    reasoningEffort: 'medium',
    onCodexModelHydrated: vi.fn(),
  } as const;
}

describe('useModelStatePersistence', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    window.sendToJava = vi.fn();
    vi.useFakeTimers();
  });

  it('ignores shared codex runtime state persisted in localStorage', () => {
    localStorage.setItem('model-selection-state', JSON.stringify({
      provider: 'codex',
      codexModel: 'MiniMax-M2.5',
      codexPermissionMode: 'acceptEdits',
      reasoningEffort: 'high',
    }));

    const options = createOptions();
    renderHook(() => useModelStatePersistence(options));

    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(options.setCurrentProvider).not.toHaveBeenCalled();
    expect(options.setSelectedCodexModel).not.toHaveBeenCalled();
    expect(options.setCodexPermissionMode).toHaveBeenCalledWith('default');
    expect(options.setPermissionMode).toHaveBeenCalledWith('bypassPermissions');
    expect(options.setReasoningEffort).not.toHaveBeenCalled();
  });

  it('does not push restored localStorage runtime values back into the backend session', () => {
    localStorage.setItem('model-selection-state', JSON.stringify({
      provider: 'claude',
      claudeModel: 'claude-sonnet-4-6',
      claudePermissionMode: 'plan',
      longContextEnabled: false,
    }));

    const options = createOptions();
    renderHook(() => useModelStatePersistence(options));

    act(() => {
      vi.runOnlyPendingTimers();
    });

    expect(window.sendToJava).not.toHaveBeenCalled();
  });
});
