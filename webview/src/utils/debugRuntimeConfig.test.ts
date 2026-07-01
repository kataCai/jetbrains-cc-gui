import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

describe('frontend debug runtime config helpers', () => {
  beforeEach(() => {
    vi.resetModules();
    vi.unstubAllEnvs();
    window.sendToJava = vi.fn();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  /**
   * 验证运行时配置会按最新 payload 覆盖，避免设置页和聊天页分别注册回调后出现状态漂移。
   */
  it('stores the latest frontend debug runtime config snapshot', async () => {
    const debugModule = await import('./debug');

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: true,
      archiveEnabled: false,
    });

    expect(debugModule.getFrontendDebugRuntimeConfig()).toEqual({
      panelEnabled: true,
      archiveEnabled: false,
    });

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: false,
      archiveEnabled: true,
    });

    expect(debugModule.getFrontendDebugRuntimeConfig()).toEqual({
      panelEnabled: false,
      archiveEnabled: true,
    });
  });

  /**
   * 验证当后端明确声明“当前值未被用户配置”时，运行时配置会回退到构建期开关默认值。
   * 这条规则用于保证 runIde 与诊断构建包即使尚未保存设置页开关，也能按构建期默认值立即生效。
   */
  it('falls back to build-time defaults when backend marks frontend debug flags as unconfigured', async () => {
    vi.stubEnv('VITE_WEBVIEW_DEBUG', 'true');
    vi.stubEnv('VITE_BRIDGE_DIAGNOSTIC_LOG', 'true');

    const debugModule = await import('./debug');

    expect(debugModule.getFrontendDebugRuntimeConfig()).toEqual({
      panelEnabled: true,
      archiveEnabled: true,
    });

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: false,
      archiveEnabled: false,
      panelConfigured: false,
      archiveConfigured: false,
    });

    expect(debugModule.getFrontendDebugRuntimeConfig()).toEqual({
      panelEnabled: true,
      archiveEnabled: true,
    });
  });
});
