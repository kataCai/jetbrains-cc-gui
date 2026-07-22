import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import i18n from '../../i18n/config';
import type { CodexModelCatalogItem, CodexProviderConfig } from '../../types/provider';
import { SPECIAL_PROVIDER_IDS } from '../../types/provider';
import ProviderTabSection from './ProviderTabSection';

/**
 * 构造真实设置页集成测试使用的 Codex provider 列表。
 * 这里同时覆盖 CLI Login 卡片和普通托管 provider，确保真实 locale 下两类文案都正常显示。
 *
 * @return 用于设置页渲染的最小 provider 集合。
 */
function createCodexProviders(): CodexProviderConfig[] {
  return [
    {
      id: SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN,
      name: 'Virtual CLI Login',
      isActive: false,
      isAuthorized: true,
    } as CodexProviderConfig & { isAuthorized?: boolean },
    {
      id: 'managed-provider',
      name: 'MiniMax',
      presetId: 'minimax',
      baseUrl: 'https://api.minimaxi.com/v1',
      models: [
        { id: 'MiniMax-M3', label: 'MiniMax-M3' },
      ],
      isActive: false,
    },
  ];
}

/**
 * 构造模型目录场景：
 * 1. CLI Login 分组下有一个可见但未授权模型；
 * 2. MiniMax 分组下有一个可见模型和两个隐藏模型；
 * 3. Local Config 分组下有一个可见模型。
 * 这样可以覆盖默认分组视图、展开视图和组级开关文案。
 *
 * @return 用于设置页集成渲染的统一模型目录。
 */
function createCatalog(): CodexModelCatalogItem[] {
  return [
    {
      key: '__codex_cli_login__::gpt-5.5',
      providerId: '__codex_cli_login__',
      providerName: 'Codex CLI',
      modelId: 'gpt-5.5',
      label: 'GPT-5.5',
      description: 'Default GPT model',
      source: 'codex_cli_login',
      visible: true,
      runnable: false,
    },
    {
      key: 'managed-provider::MiniMax-M3',
      providerId: 'managed-provider',
      providerName: 'MiniMax',
      modelId: 'MiniMax-M3',
      label: 'MiniMax-M3',
      description: 'Visible managed provider model',
      source: 'managed_provider',
      visible: true,
      runnable: true,
    },
    {
      key: 'managed-provider::MiniMax-M3-Preview',
      providerId: 'managed-provider',
      providerName: 'MiniMax',
      modelId: 'MiniMax-M3-Preview',
      label: 'MiniMax-M3-Preview',
      description: 'Hidden managed provider model',
      source: 'managed_provider',
      visible: false,
      runnable: true,
    },
    {
      key: 'managed-provider::MiniMax-Reasoner',
      providerId: 'managed-provider',
      providerName: 'MiniMax',
      modelId: 'MiniMax-Reasoner',
      label: 'MiniMax-Reasoner',
      description: 'Another hidden managed provider model',
      source: 'managed_provider',
      visible: false,
      runnable: true,
    },
    {
      key: 'local::gpt-5.4',
      providerId: 'local',
      providerName: 'Local Config',
      modelId: 'gpt-5.4',
      label: 'GPT-5.4',
      description: 'Local config fallback model',
      source: 'local_config',
      visible: true,
      runnable: true,
    },
    {
      key: 'oppo::oppo-a',
      providerId: 'oppo',
      providerName: 'OPPO',
      modelId: 'oppo-a',
      label: 'OPPO-A',
      description: 'Hidden OPPO model',
      source: 'managed_provider',
      visible: false,
      runnable: true,
    },
  ];
}

/**
 * 使用真实 i18n 资源渲染 Codex 设置页页签。
 * 该辅助方法不 mock 翻译，专门用于确认真实 locale 下不会再出现 `settings.codexProvider.*` 裸 key。
 *
 * @return Testing Library 的渲染结果，便于直接检查完整 DOM 文本。
 */
async function renderCodexSettingsTab() {
  let renderResult: ReturnType<typeof render> | undefined;
  await act(async () => {
    renderResult = render(
      <I18nextProvider i18n={i18n}>
        <ProviderTabSection
          currentProvider="codex"
          providers={[]}
          loading={false}
          onAddProvider={vi.fn()}
          onEditProvider={vi.fn()}
          onDeleteProvider={vi.fn()}
          onSwitchProvider={vi.fn()}
          codexProviders={createCodexProviders()}
          codexLoading={false}
          codexModelCatalog={createCatalog()}
          codexModelCatalogLoading={false}
          onAddCodexProvider={vi.fn()}
          onCreateCodexProviderFromAlias={vi.fn()}
          onEditCodexProvider={vi.fn()}
          onDeleteCodexProvider={vi.fn()}
          onFetchCodexProviderModels={vi.fn()}
          onTestCodexProvider={vi.fn()}
          onAuthorizeCodexLocalConfig={vi.fn()}
          onRevokeCodexLocalConfigAuthorization={vi.fn()}
          onRefreshCodexModelCatalog={vi.fn()}
          onSaveCodexModelVisibility={vi.fn()}
          onDeleteCodexModelCatalogItem={vi.fn()}
          addToast={vi.fn()}
        />
      </I18nextProvider>,
    );
  });
  return renderResult!;
}

describe('Codex settings real-i18n integration', () => {
  beforeEach(async () => {
    localStorage.clear();
    await act(async () => {
      await i18n.changeLanguage('en');
    });
  });

  afterEach(async () => {
    await act(async () => {
      await i18n.changeLanguage('en');
    });
  });

  /**
   * 验证英文场景下整张 Codex 设置页不会出现裸翻译 key，并且 Models 区域已经按供应商分组。
   */
  it('renders the Codex settings tab in English without raw translation keys and with provider-grouped models', async () => {
    const { container } = await renderCodexSettingsTab();

    expect(container.textContent).not.toContain('settings.codexProvider.');
    expect(screen.getByText('Use local Codex CLI profile')).toBeTruthy();
    expect(screen.getByText('Models')).toBeTruthy();
    expect(screen.getByText('Available after authorization')).toBeTruthy();
    expect(screen.getByTestId('provider-group:__codex_cli_login__')).toBeTruthy();
    expect(screen.getByTestId('provider-group:managed-provider')).toBeTruthy();
    // 多个已全选分组会同时展示相同文案，这里只验证真实 locale 文案至少存在一个即可。
    expect(screen.getAllByText('Deselect All').length).toBeGreaterThan(0);
    expect(screen.getByTestId('provider-group:oppo')).toBeTruthy();
    expect(screen.getByText('OPPO-A')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'View All Models' })).toBeNull();
  });

  /**
   * 验证中文场景下：
   * 1. 真实中文文案不再回退成 key；
   * 2. 默认态下隐藏模型不会直接出现；
   * 3. 展开后会在 MiniMax 分组下看到隐藏模型；
   * 4. 组级文案即使在多个分组重复出现，也能按真实 locale 正常显示。
   */
  it('renders the Chinese Codex settings tab with readable grouped model cards and expandable provider sections', async () => {
    await act(async () => {
      await i18n.changeLanguage('zh');
    });
    const { container } = await renderCodexSettingsTab();

    expect(container.textContent).not.toContain('settings.codexProvider.');
    expect(screen.getByText('使用本地 Codex 配置')).toBeTruthy();
    expect(screen.getByText('授权状态')).toBeTruthy();
    expect(screen.getByText('当前请求来源')).toBeTruthy();
    expect(screen.getByTestId('provider-group:__codex_cli_login__')).toBeTruthy();
    expect(screen.getByTestId('provider-group:managed-provider')).toBeTruthy();
    expect(screen.getByTestId('provider-group:oppo')).toBeTruthy();
    expect(screen.getByText('OPPO-A')).toBeTruthy();
    // 中文场景同样可能出现多个“全不选”，断言“至少存在”即可避免把重复文案误判成异常。
    expect(screen.getAllByText('全不选').length).toBeGreaterThan(0);
    expect(screen.queryByText('MiniMax-M3-Preview')).toBeNull();
    expect(screen.queryByRole('button', { name: '查看全部模型' })).toBeNull();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'toggle-provider-group:managed-provider' }));
    });

    expect(screen.getByText('MiniMax-M3-Preview')).toBeTruthy();
    expect(screen.getByText('MiniMax-Reasoner')).toBeTruthy();
    expect(screen.getByText('授权后可用')).toBeTruthy();
  });
});
