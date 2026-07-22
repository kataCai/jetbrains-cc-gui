import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { useCodexProviderManagement } from './useCodexProviderManagement';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('useCodexProviderManagement', () => {
  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  it('sends a revoke message when local Codex authorization is canceled', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleRevokeCodexLocalConfigAuthorization('provider-1');
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'revoke_codex_local_config_authorization:{"fallbackProviderId":"provider-1"}'
    );
    expect(result.current.codexLoading).toBe(true);
  });

  it('sends a test provider message', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleTestCodexProvider({ id: 'provider-1', name: 'Provider 1' });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'test_codex_provider:{"id":"provider-1"}'
    );
    expect(result.current.testingCodexProviderId).toBe('provider-1');
  });

  /**
   * 验证“获取模型列表”入口会发送独立桥接消息，并只记录当前同步中的 provider id。
   * 断言意图：新增动作必须和测试连接走不同消息类型，避免后端误入旧的连通性测试链路。
   */
  it('sends a fetch provider models message and tracks the syncing provider id', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleFetchCodexProviderModels({ id: 'provider-sync', name: 'Provider Sync' });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'fetch_codex_provider_models:{"id":"provider-sync"}'
    );
    expect(result.current.syncingCodexProviderId).toBe('provider-sync');
  });

  /**
   * 验证本地 Codex 配置卡片的“同步模型”同样复用 `fetch_codex_provider_models`，
   * 只是把 provider id 固定为 `__codex_cli_login__`。
   */
  it('reuses fetch provider models bridge message for the local Codex config pseudo provider', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleFetchCodexProviderModels({ id: '__codex_cli_login__', name: 'Local Codex Config' });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'fetch_codex_provider_models:{"id":"__codex_cli_login__"}'
    );
    expect(result.current.syncingCodexProviderId).toBe('__codex_cli_login__');
  });

  /**
   * 验证目标：设置页删除单个目录项时，前端必须走独立桥接消息，避免被误当成“只改 visible=false”的普通显示配置保存。
   * 断言意图：
   * 1. Java bridge 收到的 payload 必须保留 key/providerId/modelId/source；
   * 2. 该操作不应复用 `set_codex_model_visibility`；
   * 3. 删除请求发出后，目录 loading 应进入刷新态。
   */
  it('opens confirm state before sending dedicated delete catalog item bridge message', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleDeleteCodexModelCatalogItem({
        key: 'minimax::MiniMax-M3',
        providerId: 'minimax',
        providerName: 'MiniMax',
        modelId: 'MiniMax-M3',
        label: 'MiniMax-M3',
        source: 'managed_provider',
        visible: true,
        runnable: true,
      });
    });

    // 删除按钮只打开确认框，不应立刻发桥接请求。
    expect(window.sendToJava).not.toHaveBeenCalled();
    expect(result.current.deleteCodexModelCatalogConfirm.isOpen).toBe(true);
    expect(result.current.deleteCodexModelCatalogConfirm.catalogItem?.key).toBe('minimax::MiniMax-M3');

    act(() => {
      result.current.confirmDeleteCodexModelCatalogItem();
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'delete_codex_model_catalog_item:{"key":"minimax::MiniMax-M3","providerId":"minimax","modelId":"MiniMax-M3","source":"managed_provider"}'
    );
    expect(result.current.codexModelCatalogLoading).toBe(true);
    expect(result.current.deleteCodexModelCatalogConfirm.isOpen).toBe(false);
  });

  /**
   * 验证目标：只读来源目录项删除时，前端桥接参数中的 `source` 必须保持原值。
   * 断言意图：避免 hook 层把 local_config / codex_cli_login 误归并成 managed_provider，导致后端走错删除分支。
   */
  it('preserves readonly source markers in confirmed delete catalog item bridge payloads', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleDeleteCodexModelCatalogItem({
        key: '__codex_cli_login__::gpt-5.5',
        providerId: '__codex_cli_login__',
        providerName: 'Codex CLI',
        modelId: 'gpt-5.5',
        label: 'gpt-5.5',
        source: 'codex_cli_login',
        visible: true,
        runnable: false,
      });
    });
    act(() => {
      result.current.confirmDeleteCodexModelCatalogItem();
    });

    act(() => {
      result.current.handleDeleteCodexModelCatalogItem({
        key: '__codex_cli_login__::gpt-5.4-local',
        providerId: '__codex_cli_login__',
        providerName: 'Codex CLI',
        modelId: 'gpt-5.4-local',
        label: 'gpt-5.4-local',
        source: 'local_config',
        visible: true,
        runnable: true,
      });
    });
    act(() => {
      result.current.confirmDeleteCodexModelCatalogItem();
    });

    expect(window.sendToJava).toHaveBeenNthCalledWith(
      1,
      'delete_codex_model_catalog_item:{"key":"__codex_cli_login__::gpt-5.5","providerId":"__codex_cli_login__","modelId":"gpt-5.5","source":"codex_cli_login"}'
    );
    expect(window.sendToJava).toHaveBeenNthCalledWith(
      2,
      'delete_codex_model_catalog_item:{"key":"__codex_cli_login__::gpt-5.4-local","providerId":"__codex_cli_login__","modelId":"gpt-5.4-local","source":"local_config"}'
    );
    expect(result.current.codexModelCatalogLoading).toBe(true);
  });

  /**
   * 验证新增 provider 时，前端 payload 会完整保留模板与站点元信息。
   * 本测试覆盖本次方案新增的 providerType / presetId / websiteUrl / apiKeyApplyUrl，
   * 防止表单字段虽然存在，但在发往 Java 的桥接载荷里被静默丢失。
   */
  it('should include provider preset metadata when saving a Codex provider', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleAddCodexProvider();
    });

    act(() => {
      result.current.handleSaveCodexProvider({
        id: 'provider-1',
        name: 'MiniMax',
        authMode: 'api_key',
        requestMode: 'codex_sdk',
        baseUrl: 'https://api.minimaxi.com/v1',
        apiKey: 'sk-test-12345678',
        providerType: 'minimax',
        presetId: 'minimax',
        websiteUrl: 'https://platform.minimaxi.com',
        apiKeyApplyUrl: 'https://platform.minimaxi.com/user-center/basic-information/interface-key',
        models: [
          {
            id: 'MiniMax-M2.5',
            label: 'MiniMax-M2.5',
          },
        ],
      });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'add_codex_provider:{"id":"provider-1","name":"MiniMax","authMode":"api_key","requestMode":"codex_sdk","baseUrl":"https://api.minimaxi.com/v1","apiKey":"sk-test-12345678","providerType":"minimax","presetId":"minimax","websiteUrl":"https://platform.minimaxi.com","apiKeyApplyUrl":"https://platform.minimaxi.com/user-center/basic-information/interface-key","models":[{"id":"MiniMax-M2.5","label":"MiniMax-M2.5"}]}'
    );
  });

  it('should preserve cc_switch_proxy mode-specific payload fields when saving a Codex provider', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleAddCodexProvider();
    });

    act(() => {
      result.current.handleSaveCodexProvider({
        id: 'provider-proxy-1',
        name: 'Proxy Provider',
        authMode: 'proxy',
        requestMode: 'cc_switch_proxy',
        models: [
          {
            id: 'proxy-model',
            label: 'Proxy Model',
          },
        ],
        ccSwitchProxy: {
          proxyEndpoint: 'http://127.0.0.1:15721',
          providerRoute: 'minimax',
          requestPath: '/v1/responses',
          requestHeaders: {
            'x-route': 'minimax',
          },
        },
      });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'add_codex_provider:{"id":"provider-proxy-1","name":"Proxy Provider","authMode":"proxy","requestMode":"cc_switch_proxy","models":[{"id":"proxy-model","label":"Proxy Model"}],"ccSwitchProxy":{"proxyEndpoint":"http://127.0.0.1:15721","providerRoute":"minimax","requestPath":"/v1/responses","requestHeaders":{"x-route":"minimax"}}}'
    );
  });

  it('should preserve custom_adapter mode-specific payload fields when saving a Codex provider', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleAddCodexProvider();
    });

    act(() => {
      result.current.handleSaveCodexProvider({
        id: 'provider-adapter-1',
        name: 'Adapter Provider',
        authMode: 'api_key',
        requestMode: 'custom_adapter',
        apiKey: 'sk-adapter',
        models: [
          {
            id: 'adapter-model',
            label: 'Adapter Model',
          },
        ],
        customAdapter: {
          adapterId: 'minimax-adapter',
          adapterEndpoint: 'http://127.0.0.1:8080/adapter/codex',
          adapterHeaders: {
            Authorization: 'Bearer adapter',
          },
          adapterExtras: {
            provider: 'minimax',
            mode: 'responses',
          },
        },
      });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'add_codex_provider:{"id":"provider-adapter-1","name":"Adapter Provider","authMode":"api_key","requestMode":"custom_adapter","apiKey":"sk-adapter","models":[{"id":"adapter-model","label":"Adapter Model"}],"customAdapter":{"adapterId":"minimax-adapter","adapterEndpoint":"http://127.0.0.1:8080/adapter/codex","adapterHeaders":{"Authorization":"Bearer adapter"},"adapterExtras":{"provider":"minimax","mode":"responses"}}}'
    );
  });
});
