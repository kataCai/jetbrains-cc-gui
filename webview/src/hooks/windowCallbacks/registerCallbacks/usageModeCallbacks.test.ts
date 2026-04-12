import { beforeEach, describe, expect, it, vi } from 'vitest';
import { registerUsageModeCallbacks } from './usageModeCallbacks';

vi.mock('../settingsBootstrap', () => ({
  drainPendingSettings: vi.fn(),
  startInitialSettingsRequest: vi.fn(),
}));

function createOptions(provider: 'claude' | 'codex' = 'claude') {
  return {
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setPermissionMode: vi.fn(),
    setClaudePermissionMode: vi.fn(),
    setCodexPermissionMode: vi.fn(),
    setSelectedClaudeModel: vi.fn(),
    setSelectedCodexModel: vi.fn(),
    setProviderConfigVersion: vi.fn(),
    setActiveProviderConfig: vi.fn(),
    setClaudeSettingsAlwaysThinkingEnabled: vi.fn(),
    setStreamingEnabledSetting: vi.fn(),
    setSendShortcut: vi.fn(),
    setAutoOpenFileEnabled: vi.fn(),
    currentProviderRef: { current: provider },
    syncActiveProviderModelMapping: vi.fn(),
  } as any;
}

describe('registerUsageModeCallbacks', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.onModeChanged = undefined;
    window.onModeReceived = undefined;
  });

  it('passes through plan mode for claude on mode changes', () => {
    // Claude 的 mode change 应保留 plan，不应被兼容逻辑误降级。
    const options = createOptions('claude');
    registerUsageModeCallbacks(options);

    window.onModeChanged?.('plan');

    const permissionUpdater = options.setPermissionMode.mock.calls[0][0];
    const claudeUpdater = options.setClaudePermissionMode.mock.calls[0][0];
    expect(permissionUpdater('default')).toBe('plan');
    expect(claudeUpdater('default')).toBe('plan');
    expect(options.setCodexPermissionMode).not.toHaveBeenCalled();
  });

  it('downgrades codex plan mode to default on received mode', () => {
    // 收到来自后端/恢复链路的 plan 时，Codex 仍需回落为 default。
    const options = createOptions('codex');
    registerUsageModeCallbacks(options);

    window.onModeReceived?.('plan');

    const permissionUpdater = options.setPermissionMode.mock.calls[0][0];
    const codexUpdater = options.setCodexPermissionMode.mock.calls[0][0];
    expect(permissionUpdater('plan')).toBe('default');
    expect(codexUpdater('plan')).toBe('default');
    expect(options.setClaudePermissionMode).not.toHaveBeenCalled();
  });

  it('keeps non-plan modes unchanged for codex', () => {
    // 兼容逻辑只影响 plan，其它 execution mode 必须保持原样。
    const options = createOptions('codex');
    registerUsageModeCallbacks(options);

    window.onModeChanged?.('acceptEdits');

    const permissionUpdater = options.setPermissionMode.mock.calls[0][0];
    const codexUpdater = options.setCodexPermissionMode.mock.calls[0][0];
    expect(permissionUpdater('default')).toBe('acceptEdits');
    expect(codexUpdater('default')).toBe('acceptEdits');
  });
});
