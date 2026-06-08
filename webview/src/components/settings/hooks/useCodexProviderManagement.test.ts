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
