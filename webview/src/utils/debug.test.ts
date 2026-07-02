import { beforeEach, describe, expect, it, vi } from 'vitest';

describe('frontend diagnostic debug runtime config', () => {
  beforeEach(() => {
    vi.resetModules();
    window.sendToJava = vi.fn();
  });

  /**
   * 验证运行时双开关关闭时，诊断日志既不会打印到前端面板，也不会桥接回 Java。
   * 这能防止分发包默认状态下引入额外日志噪音或无意义的 IPC 开销。
   */
  it('suppresses diagnostic log output when panel and archive are both disabled', async () => {
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
    const debugModule = await import('./debug');

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: false,
      archiveEnabled: false,
    });
    debugModule.emitFrontendDiagnosticLog('RichPaste.Apply', 'suppressed log', {
      textLength: 12,
    });

    expect(infoSpy).not.toHaveBeenCalled();
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  /**
   * 验证开启调试面板开关后，诊断日志会打印到前端控制台。
   * 这里要求日志不依赖外部业务模块自行判断开关，统一由 debug 工具控制。
   */
  it('prints diagnostic logs to console when panel output is enabled', async () => {
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
    const debugModule = await import('./debug');

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: true,
      archiveEnabled: false,
    });
    debugModule.emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'panel log', {
      restoreKey: 'restore-1',
    });

    expect(infoSpy).toHaveBeenCalledWith(
      '[FrontendDebug][HistoryRestore.Frontend]',
      'panel log',
      { restoreKey: 'restore-1' },
    );
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  /**
   * 验证开启归档开关后，诊断日志会通过独立桥接事件发往 Java。
   * 这条链路不能依赖 console.info，否则分发包静音后就无法进入 idea.log。
   */
  it('bridges diagnostic logs to Java when archive output is enabled', async () => {
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
    const debugModule = await import('./debug');

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: false,
      archiveEnabled: true,
    });
    debugModule.emitFrontendDiagnosticLog('RichPaste.Native', 'archive log', {
      hasImage: true,
      hasText: false,
    });

    expect(infoSpy).not.toHaveBeenCalled();
    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledWith(
      expect.stringMatching(/^frontend_debug_log:\{/),
    );
    const message = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls[0]?.[0] as string;
    const payload = JSON.parse(message.slice('frontend_debug_log:'.length));
    expect(payload).toMatchObject({
      scope: 'RichPaste.Native',
      message: 'archive log',
      details: {
        hasImage: true,
        hasText: false,
      },
    });
  });

  /**
   * 验证桥接诊断日志会在前端侧先做裁剪与脱敏，再发送到 Java。
   * 断言意图：避免把整段 HTML、data URL/base64 或超长文本直接写入 idea.log，
   * 同时保留足够的摘要信息，便于 rich paste 与 history restore 问题排查。
   */
  it('sanitizes oversized and sensitive diagnostic details before bridging', async () => {
    const infoSpy = vi.spyOn(console, 'info').mockImplementation(() => {});
    const debugModule = await import('./debug');
    const longHtml = `<div>${'x'.repeat(800)}</div>`;
    const dataUrl = `data:image/png;base64,${'A'.repeat(256)}`;

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: true,
      archiveEnabled: true,
    });
    debugModule.emitFrontendDiagnosticLog('RichPaste.Apply', 'sanitize log', {
      html: longHtml,
      imageData: dataUrl,
      nested: {
        description: 'y'.repeat(700),
      },
    });

    expect(infoSpy).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).toHaveBeenCalledTimes(1);

    const message = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls[0]?.[0] as string;
    const payload = JSON.parse(message.slice('frontend_debug_log:'.length));

    expect(payload.details).toEqual({
      html: expect.stringContaining('[truncated'),
      imageData: '[omitted data-url]',
      nested: {
        description: expect.stringContaining('[truncated'),
      },
    });
    expect(payload.details.html.length).toBeLessThan(longHtml.length);
    expect(payload.details.nested.description.length).toBeLessThan(700);
  });

  /**
   * 验证当两个输出开关都关闭时，诊断日志工具不会先递归清洗详情对象。
   * 这里使用循环引用对象作为输入，目的是锁住“关闭日志时也不能把诊断工具变成业务路径异常源”的约束。
   */
  it('does not traverse cyclic details when both outputs are disabled', async () => {
    const debugModule = await import('./debug');
    const cyclic: Record<string, unknown> = { label: 'root' };
    cyclic.self = cyclic;

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: false,
      archiveEnabled: false,
    });

    expect(() => {
      debugModule.emitFrontendDiagnosticLog('RichPaste.Apply', 'cyclic suppressed log', cyclic);
    }).not.toThrow();
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  /**
   * 验证关闭日志输出时不会读取详情对象的属性值。
   * 这里用会抛错的 getter 作为探针，确保“无输出”场景不会先执行对象清洗逻辑。
   */
  it('does not read diagnostic detail getters when both outputs are disabled', async () => {
    const debugModule = await import('./debug');
    const guardedDetails = Object.defineProperty({}, 'danger', {
      enumerable: true,
      get() {
        throw new Error('getter should not be touched');
      },
    });

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: false,
      archiveEnabled: false,
    });

    expect(() => {
      debugModule.emitFrontendDiagnosticLog(
        'HistoryRestore.Frontend',
        'guarded suppressed log',
        guardedDetails as Record<string, unknown>,
      );
    }).not.toThrow();
    expect(window.sendToJava).not.toHaveBeenCalled();
  });

  /**
   * 验证开启桥接输出后，循环引用详情会被安全替换而不是递归爆栈。
   * 该测试覆盖的场景是后续调用方不小心传入带自引用的对象时，日志链路仍应稳定产出可解析 payload。
   */
  it('sanitizes cyclic diagnostic details before bridging', async () => {
    const debugModule = await import('./debug');
    const cyclic: Record<string, unknown> = { label: 'root' };
    cyclic.self = cyclic;

    debugModule.updateFrontendDebugRuntimeConfig({
      panelEnabled: false,
      archiveEnabled: true,
    });

    debugModule.emitFrontendDiagnosticLog('HistoryRestore.Frontend', 'cyclic archive log', cyclic);

    expect(window.sendToJava).toHaveBeenCalledTimes(1);
    const message = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls[0]?.[0] as string;
    const payload = JSON.parse(message.slice('frontend_debug_log:'.length));
    expect(payload.details).toEqual({
      label: 'root',
      self: '[circular]',
    });
  });
});
