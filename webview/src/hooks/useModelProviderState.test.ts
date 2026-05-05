import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useModelProviderState } from './useModelProviderState';
import { sendBridgeEvent } from '../utils/bridge';

vi.mock('../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
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
});
