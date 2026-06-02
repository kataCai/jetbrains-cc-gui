import { describe, expect, it } from 'vitest';
import { PROVIDER_PRESETS } from './provider';
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
});
