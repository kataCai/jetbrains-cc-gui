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

  /**
   * 验证空模型列表在保存时仍会显式透传为 `models: []`。
   * 只有这样后端更新现有 provider 时，才能真正清空旧模型而不是因为字段缺失继续保留历史配置。
   */
  it('preserves an explicit empty models array when saving a Codex provider', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleAddCodexProvider();
    });

    act(() => {
      result.current.handleSaveCodexProvider({
        id: 'provider-empty-models',
        name: 'Provider Empty Models',
        authMode: 'api_key',
        requestMode: 'codex_sdk',
        baseUrl: 'https://gateway.example.com/v1',
        apiKey: 'sk-empty',
        models: [],
      });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'add_codex_provider:{"id":"provider-empty-models","name":"Provider Empty Models","authMode":"api_key","requestMode":"codex_sdk","baseUrl":"https://gateway.example.com/v1","apiKey":"sk-empty","models":[]}'
    );
  });

  /**
   * 验证供应商卡片复制只生成新增态草稿，不复用原 provider 的 id、运行状态或诊断字段。
   * 该断言覆盖复制链路最容易出错的边界：编辑弹窗必须按新增模式打开，保存时由现有逻辑生成新 ID。
   */
  it('opens a duplicate provider as a sanitized add-dialog draft', () => {
    const { result } = renderHook(() => useCodexProviderManagement());
    const sourceProvider = {
      id: 'provider-source',
      name: 'Provider Source',
      remark: 'source remark',
      providerType: 'custom_gateway',
      presetId: 'custom_gateway',
      authMode: 'api_key' as const,
      requestMode: 'codex_sdk' as const,
      baseUrl: 'https://gateway.example.com/v1',
      apiKey: 'sk-source',
      apiKeyEnv: 'SOURCE_API_KEY',
      models: [{ id: 'source-model', label: 'Source Model' }],
      messageEnvVars: [{ key: 'MESSAGE_ENV', value: 'source' }],
      mcpEnvVars: [{ key: 'MCP_ENV', value: 'source' }],
      isActive: true,
      createdAt: 123,
      apiKeyMasked: 'sk-s******urce',
      effectiveConfigSource: 'managed_provider',
    };

    act(() => {
      result.current.handleDuplicateCodexProvider(sourceProvider);
    });

    expect(result.current.codexProviderDialog.isOpen).toBe(true);
    expect(result.current.codexProviderDialog.provider).toBeNull();
    expect(result.current.codexProviderDialog.initialProviderData).toEqual({
      name: 'Provider Source 副本',
      remark: 'source remark',
      providerType: 'custom_gateway',
      presetId: 'custom_gateway',
      authMode: 'api_key',
      requestMode: 'codex_sdk',
      baseUrl: 'https://gateway.example.com/v1',
      apiKey: 'sk-source',
      apiKeyEnv: 'SOURCE_API_KEY',
      models: [{ id: 'source-model', label: 'Source Model' }],
      messageEnvVars: [{ key: 'MESSAGE_ENV', value: 'source' }],
      mcpEnvVars: [{ key: 'MCP_ENV', value: 'source' }],
    });
  });

  /**
   * 验证编辑弹窗的拉模动作传递当前草稿字段，而不是只传已保存 provider id。
   * 这样用户刚修改的 Base URL、API Key 或环境变量才能真正参与后端模型发现。
   */
  it('sends the current provider draft through the dedicated draft-model bridge', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleFetchCodexProviderModelsFromDraft({
        id: 'provider-draft',
        name: 'Draft Provider',
        authMode: 'api_key',
        requestMode: 'codex_sdk',
        baseUrl: 'https://new-gateway.example.com/v1',
        apiKey: 'sk-new',
        apiKeyEnv: 'NEW_API_KEY',
        models: [{ id: 'existing-model', label: 'Existing Model' }],
      });
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'fetch_codex_provider_models_from_draft:{"providerId":"provider-draft","name":"Draft Provider","authMode":"api_key","requestMode":"codex_sdk","baseUrl":"https://new-gateway.example.com/v1","apiKey":"sk-new","apiKeyEnv":"NEW_API_KEY","models":[{"id":"existing-model","label":"Existing Model"}],"messageEnvVars":[],"mcpEnvVars":[]}'
    );
    expect(result.current.syncingCodexProviderDraftId).toBe('provider-draft');
  });

  /**
   * 验证草稿级拉模结果只进入弹窗专属状态，不刷新 provider 列表，也不把远端结果直接写入持久化配置。
   * 弹窗组件后续会依据 modelIds 做“只补缺失项”的本地合并。
   */
  it('stores draft model fetch results without changing the provider list', () => {
    const { result } = renderHook(() => useCodexProviderManagement());

    act(() => {
      result.current.handleEditCodexProvider({
        id: 'provider-draft-result',
        name: 'Draft Result Provider',
        authMode: 'api_key',
        requestMode: 'codex_sdk',
        baseUrl: 'https://gateway.example.com/v1',
        apiKey: 'sk-test',
        models: [{ id: 'legacy-model', label: 'Legacy Model' }],
      });
    });
    const draftRequestId = (result.current.codexProviderDialog as any).draftRequestId
      ?? result.current.codexProviderDialog.provider?.id;

    act(() => {
      result.current.handleFetchCodexProviderModelsFromDraft({
        id: draftRequestId,
        name: 'Draft Result Provider',
        authMode: 'api_key',
        requestMode: 'codex_sdk',
        baseUrl: 'https://gateway.example.com/v1',
        apiKey: 'sk-test',
      });
    });

    act(() => {
      result.current.updateCodexProviderDraftModels({
        providerId: draftRequestId,
        modelIds: ['legacy-model', 'synced-model'],
        duplicateCount: 1,
        skippedCount: 0,
      });
    });

    expect(result.current.codexProviders).toEqual([]);
    expect(result.current.codexProviderDialog.provider?.models).toEqual([
      { id: 'legacy-model', label: 'Legacy Model' },
    ]);
    expect(result.current.codexProviderDialog.draftModelsResult).toEqual({
      providerId: draftRequestId,
      modelIds: ['legacy-model', 'synced-model'],
      duplicateCount: 1,
      skippedCount: 0,
    });
    expect(result.current.codexProviderDialog.draftModelsRevision).toBe(1);
    expect(result.current.syncingCodexProviderDraftId).toBe('');
  });

  /**
   * 验证关闭后重新打开同一 provider 时，旧草稿拉模回包不会污染新的编辑会话。
   * 该场景覆盖真实竞态：
   * 1. 用户对同一 provider 发起第一次拉模；
   * 2. 在回包到达前关闭并重新打开弹窗，再次发起第二次拉模；
   * 3. 第一次旧回包必须被忽略，且不能清掉第二次请求的 loading。
   */
  it('ignores stale draft model fetch results after reopening the same provider', () => {
    const { result } = renderHook(() => useCodexProviderManagement());
    const provider = {
      id: 'provider-race',
      name: 'Race Provider',
      authMode: 'api_key' as const,
      requestMode: 'codex_sdk' as const,
      baseUrl: 'https://gateway.example.com/v1',
      apiKey: 'sk-race',
      models: [{ id: 'legacy-model', label: 'Legacy Model' }],
    };

    act(() => {
      result.current.handleEditCodexProvider(provider);
    });
    const firstDraftRequestId = (result.current.codexProviderDialog as any).draftRequestId
      ?? result.current.codexProviderDialog.provider?.id;

    act(() => {
      result.current.handleFetchCodexProviderModelsFromDraft({
        ...provider,
        id: firstDraftRequestId,
      });
    });

    act(() => {
      result.current.handleCloseCodexProviderDialog();
      result.current.handleEditCodexProvider(provider);
    });
    const secondDraftRequestId = (result.current.codexProviderDialog as any).draftRequestId
      ?? result.current.codexProviderDialog.provider?.id;

    act(() => {
      result.current.handleFetchCodexProviderModelsFromDraft({
        ...provider,
        id: secondDraftRequestId,
      });
    });

    act(() => {
      result.current.updateCodexProviderDraftModels({
        providerId: firstDraftRequestId,
        modelIds: ['stale-model'],
        duplicateCount: 0,
        skippedCount: 0,
      });
    });

    expect(secondDraftRequestId).not.toBe(firstDraftRequestId);
    expect(result.current.codexProviderDialog.draftModelsResult).toBeNull();
    expect(result.current.codexProviderDialog.draftModelsRevision).toBe(0);
    expect(result.current.syncingCodexProviderDraftId).toBe(secondDraftRequestId);

    act(() => {
      result.current.updateCodexProviderDraftModels({
        providerId: secondDraftRequestId,
        modelIds: ['fresh-model'],
        duplicateCount: 0,
        skippedCount: 0,
      });
    });

    expect(result.current.codexProviderDialog.draftModelsResult).toEqual({
      providerId: secondDraftRequestId,
      modelIds: ['fresh-model'],
      duplicateCount: 0,
      skippedCount: 0,
    });
    expect(result.current.codexProviderDialog.draftModelsRevision).toBe(1);
    expect(result.current.syncingCodexProviderDraftId).toBe('');
  });
});
