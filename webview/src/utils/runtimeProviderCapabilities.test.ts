import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  installRuntimeProviderDispatchers,
  subscribeCodexModelCatalog,
} from './runtimeProviderCapabilities';

describe('runtimeProviderCapabilities', () => {
  beforeEach(() => {
    window.updateCodexModelCatalog = undefined;
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
});
