import { beforeEach, describe, expect, it, vi } from 'vitest';
import { drainPendingSettings } from './settingsBootstrap';

describe('settingsBootstrap', () => {
  beforeEach(() => {
    vi.stubGlobal('window', globalThis);
    window.updateStreamingEnabled = undefined;
    window.updateSendShortcut = undefined;
    window.updateAutoOpenFileEnabled = undefined;
    window.onModeReceived = undefined;
    window.updateCodexModelState = undefined;
    window.__pendingStreamingEnabled = undefined;
    window.__pendingSendShortcut = undefined;
    window.__pendingAutoOpenFileEnabled = undefined;
    window.__pendingModeReceived = undefined;
    window.__pendingCodexModelState = undefined;
  });

  it('replays pending codex model state captured before callback registration', () => {
    // Codex model state 可能早于 React callback 注册到达；
    // 这里必须像 streaming/sendShortcut 一样回放，避免默认模型和 base_url 提示丢失。
    const updateCodexModelState = vi.fn();
    window.updateCodexModelState = updateCodexModelState;
    window.__pendingCodexModelState = JSON.stringify({
      model: 'gpt-5.5',
      baseUrl: 'https://example.test/v1',
      usesCustomBaseUrl: true,
    });

    drainPendingSettings();

    expect(updateCodexModelState).toHaveBeenCalledWith(JSON.stringify({
      model: 'gpt-5.5',
      baseUrl: 'https://example.test/v1',
      usesCustomBaseUrl: true,
    }));
    expect(window.__pendingCodexModelState).toBeUndefined();
  });
});
