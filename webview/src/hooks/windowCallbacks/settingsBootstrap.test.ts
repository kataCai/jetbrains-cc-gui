import { beforeEach, describe, expect, it, vi } from 'vitest';
import { drainPendingSettings } from './settingsBootstrap';

describe('settingsBootstrap', () => {
  beforeEach(() => {
    vi.stubGlobal('window', globalThis);
    window.updateStreamingEnabled = undefined;
    window.updateSendShortcut = undefined;
    window.updateAutoOpenFileEnabled = undefined;
    window.updateRightClickOpenDevToolsEnabled = undefined;
    window.updateFrontendDebugConfig = undefined;
    window.onModeReceived = undefined;
    window.updateCodexModelState = undefined;
    window.__pendingStreamingEnabled = undefined;
    window.__pendingSendShortcut = undefined;
    window.__pendingAutoOpenFileEnabled = undefined;
    window.__pendingRightClickOpenDevToolsEnabled = undefined;
    window.__pendingFrontendDebugConfig = undefined;
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

  it('replays pending right click devtools setting captured before callback registration', () => {
    // 新增全局开关需要和 streaming/autoOpenFile 一样支持预注册回放，
    // 否则后端若在 React callback 注册前先返回配置，聊天页右键菜单会拿到错误默认值。
    const updateRightClickOpenDevToolsEnabled = vi.fn();
    window.updateRightClickOpenDevToolsEnabled = updateRightClickOpenDevToolsEnabled;
    window.__pendingRightClickOpenDevToolsEnabled = JSON.stringify({
      rightClickOpenDevToolsEnabled: true,
    });

    drainPendingSettings();

    expect(updateRightClickOpenDevToolsEnabled).toHaveBeenCalledWith(JSON.stringify({
      rightClickOpenDevToolsEnabled: true,
    }));
    expect(window.__pendingRightClickOpenDevToolsEnabled).toBeUndefined();
  });

  it('replays pending frontend debug config captured before callback registration', () => {
    // 前端调试双开关需要支持和其他基础设置相同的预注册回放，否则启动早期返回的配置会在 React 注册前丢失。
    const updateFrontendDebugConfig = vi.fn();
    window.updateFrontendDebugConfig = updateFrontendDebugConfig;
    window.__pendingFrontendDebugConfig = JSON.stringify({
      panelEnabled: true,
      archiveEnabled: true,
    });

    drainPendingSettings();

    expect(updateFrontendDebugConfig).toHaveBeenCalledWith(JSON.stringify({
      panelEnabled: true,
      archiveEnabled: true,
    }));
    expect(window.__pendingFrontendDebugConfig).toBeUndefined();
  });
});
