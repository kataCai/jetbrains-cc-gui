import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  installRuntimeProviderDispatchers,
  resetRuntimeProviderCapabilitiesForTest,
  subscribeCodexModelCatalog,
} from './runtimeProviderCapabilities';

describe('runtimeProviderCapabilities', () => {
  beforeEach(() => {
    resetRuntimeProviderCapabilitiesForTest();
  });

  /**
   * 验证新增的 Codex model catalog dispatcher 会正确把后端回调广播给订阅者。
   * 这是聊天区改造后的唯一统一入口，若这里失效，前端会悄悄退回旧数据源。
   */
  it('dispatches updateCodexModelCatalog payloads to subscribers', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeCodexModelCatalog(listener);

    installRuntimeProviderDispatchers();
    window.updateCodexModelCatalog?.('[{"key":"provider::model"}]');

    expect(listener).toHaveBeenCalledWith('[{"key":"provider::model"}]');

    unsubscribe();
  });

  /**
   * 验证目标：即使统一目录先到、订阅者后挂载，晚订阅者也必须立即拿到最近一次 catalog。
   * 断言意图：防止设置页或聊天区因为挂载时序稍晚而永远停留在初始 loading/旧目录。
   */
  it('replays the latest updateCodexModelCatalog payload to late subscribers', () => {
    installRuntimeProviderDispatchers();
    window.updateCodexModelCatalog?.('[{"key":"provider::model"}]');

    const listener = vi.fn();
    const unsubscribe = subscribeCodexModelCatalog(listener);

    expect(listener).toHaveBeenCalledWith('[{"key":"provider::model"}]');

    unsubscribe();
  });
});
