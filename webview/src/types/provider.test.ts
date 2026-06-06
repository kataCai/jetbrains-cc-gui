import { describe, expect, it } from 'vitest';
import {
  PROVIDER_PRESETS,
  getCodexProviderModeFieldGroups,
  isCodexRequestModeImplemented,
} from './provider';
import type { CodexProviderConfig, CodexSelectedModel } from './provider';

describe('PROVIDER_PRESETS', () => {
  it('uses the current DeepSeek Anthropic-compatible defaults', () => {
    const deepseek = PROVIDER_PRESETS.find(provider => provider.id === 'deepseek');

    expect(deepseek?.env).toMatchObject({
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_BASE_URL: 'https://api.deepseek.com/anthropic',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'deepseek-v4-pro[1m]',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'deepseek-v4-pro[1m]',
      ANTHROPIC_SMALL_FAST_MODEL: 'deepseek-v4-flash',
      CLAUDE_CODE_EFFORT_LEVEL: 'max',
    });
  });

  it('uses the current Xiaomi MiMo model for all Claude model slots', () => {
    const xiaomi = PROVIDER_PRESETS.find(provider => provider.id === 'xiaomi');

    expect(xiaomi?.env).toMatchObject({
      ANTHROPIC_SMALL_FAST_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
    });
  });
});

describe('Codex runtime provider types', () => {
  it('describes request-level provider profile fields', () => {
    const provider: CodexProviderConfig = {
      id: 'minimax-cn',
      name: 'MiniMax CN',
      authMode: 'api_key_env',
      requestMode: 'codex_sdk',
      baseUrl: 'https://api.minimaxi.com/anthropic',
      apiKeyEnv: 'MINIMAX_CN_API_KEY',
      models: [{ id: 'MiniMax-M2.7', label: 'MiniMax M2.7', reasoningEffort: 'medium' }],
    };
    const selected: CodexSelectedModel = { providerId: provider.id, modelId: provider.models?.[0]?.id || '' };

    expect(provider.requestMode).toBe('codex_sdk');
    expect(selected).toEqual({ providerId: 'minimax-cn', modelId: 'MiniMax-M2.7' });
  });

  it('describes cc_switch_proxy mode-specific provider fields', () => {
    const provider: CodexProviderConfig = {
      id: 'cc-switch-route-a',
      name: 'CC Switch Route A',
      authMode: 'proxy',
      requestMode: 'cc_switch_proxy',
      models: [{ id: 'route-model-a', label: 'Route Model A' }],
      ccSwitchProxy: {
        proxyEndpoint: 'http://127.0.0.1:15721',
        providerRoute: 'minimax',
        requestPath: '/v1/responses',
        requestHeaders: {
          'x-route': 'minimax',
        },
      },
    };

    expect(provider.requestMode).toBe('cc_switch_proxy');
    expect(provider.ccSwitchProxy?.proxyEndpoint).toBe('http://127.0.0.1:15721');
    expect(provider.ccSwitchProxy?.providerRoute).toBe('minimax');
    expect(provider.ccSwitchProxy?.requestPath).toBe('/v1/responses');
    expect(provider.ccSwitchProxy?.requestHeaders).toEqual({ 'x-route': 'minimax' });
  });

  it('describes custom_adapter mode-specific provider fields', () => {
    const provider: CodexProviderConfig = {
      id: 'custom-adapter-a',
      name: 'Custom Adapter A',
      authMode: 'api_key',
      requestMode: 'custom_adapter',
      models: [{ id: 'adapter-model-a', label: 'Adapter Model A' }],
      customAdapter: {
        adapterId: 'minimax-adapter',
        adapterEndpoint: 'http://127.0.0.1:8080/adapter/codex',
        adapterHeaders: {
          Authorization: 'Bearer test',
        },
        adapterExtras: {
          provider: 'minimax',
          upstreamPath: '/v1/chat/completions',
        },
      },
    };

    expect(provider.requestMode).toBe('custom_adapter');
    expect(provider.customAdapter?.adapterId).toBe('minimax-adapter');
    expect(provider.customAdapter?.adapterEndpoint).toBe('http://127.0.0.1:8080/adapter/codex');
    expect(provider.customAdapter?.adapterHeaders).toEqual({ Authorization: 'Bearer test' });
    expect(provider.customAdapter?.adapterExtras).toEqual({
      provider: 'minimax',
      upstreamPath: '/v1/chat/completions',
    });
  });

  it('exposes common and mode-specific field groups for all request modes', () => {
    expect(getCodexProviderModeFieldGroups('codex_sdk')).toEqual({
      commonFields: [
        'id',
        'name',
        'providerType',
        'presetId',
        'remark',
        'websiteUrl',
        'apiKeyApplyUrl',
        'createdAt',
        'isActive',
        'authMode',
        'requestMode',
        'apiKey',
        'apiKeyEnv',
        'apiKeyMasked',
        'configToml',
        'authJson',
        'customModels',
        'autoActivate',
      ],
      modeFields: ['baseUrl', 'models'],
    });
    expect(getCodexProviderModeFieldGroups('cc_switch_proxy').modeFields).toEqual([
      'models',
      'ccSwitchProxy.proxyEndpoint',
      'ccSwitchProxy.providerRoute',
      'ccSwitchProxy.requestPath',
      'ccSwitchProxy.requestHeaders',
    ]);
    expect(getCodexProviderModeFieldGroups('custom_adapter').modeFields).toEqual([
      'models',
      'customAdapter.adapterId',
      'customAdapter.adapterEndpoint',
      'customAdapter.adapterHeaders',
      'customAdapter.adapterExtras',
    ]);
  });

  it('marks only codex_sdk as implemented for the current runtime', () => {
    expect(isCodexRequestModeImplemented('codex_sdk')).toBe(true);
    expect(isCodexRequestModeImplemented('cc_switch_proxy')).toBe(false);
    expect(isCodexRequestModeImplemented('custom_adapter')).toBe(false);
  });
});
