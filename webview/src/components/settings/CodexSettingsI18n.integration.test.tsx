import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nextProvider } from 'react-i18next';
import i18n from '../../i18n/config';
import type { CodexModelCatalogItem, CodexProviderConfig } from '../../types/provider';
import { SPECIAL_PROVIDER_IDS } from '../../types/provider';
import ProviderTabSection from './ProviderTabSection';

/**
 * 构造真实设置页集成测试使用的 Codex provider 列表。
 * 这里同时覆盖 CLI Login 卡片和普通托管 provider，确保中英文切换时两类文案都走真实 locale。
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
 * 1. 默认可见但未授权的 CLI Login 模型；
 * 2. 默认隐藏的托管 provider 模型；
 * 3. 默认可见的本地兜底模型。
 * 这样可以同时覆盖截图 1 的授权提示和截图 2 的折叠/展开模型视图。
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
      key: 'minimax::MiniMax-M3',
      providerId: 'managed-provider',
      providerName: 'MiniMax',
      modelId: 'MiniMax-M3',
      label: 'MiniMax-M3',
      description: 'Managed provider model',
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
  ];
}

/**
 * 使用真实 i18n 资源渲染 Codex 设置页页签。
 * 该辅助方法不 mock 翻译，专门用于确认真实 locale 下不会再出现 `settings.codexProvider.*` 裸 key。
 *
 * @return Testing Library 的渲染结果，便于后续直接检查整棵 DOM 文本。
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
          addToast={vi.fn()}
        />
      </I18nextProvider>
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
   * 验证英文场景下整张 Codex 设置页不会再暴露原始 i18n key。
   * 断言意图：真实 locale 资源而非 mock 翻译已经补齐，CLI Login 卡片与 Models 面板都能直接面向用户展示。
   */
  it('renders the Codex settings tab in English without raw translation keys', async () => {
    const { container } = await renderCodexSettingsTab();

    expect(container.textContent).not.toContain('settings.codexProvider.');
    expect(screen.getByText('Use local Codex CLI profile')).toBeTruthy();
    expect(screen.getByText('Models')).toBeTruthy();
    expect(screen.getByText('Available after authorization')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'View All Models' })).toBeTruthy();
  });

  /**
   * 验证中文场景下截图对应问题已经收口：
   * 1. CLI Login 区不再显示裸 key；
   * 2. Models 区默认只展示可见模型；
   * 3. 展开后可以看到隐藏模型，符合新 UI 的折叠/展开设计。
   */
  it('renders the Chinese Codex settings tab with readable status copy and expandable model list', async () => {
    await act(async () => {
      await i18n.changeLanguage('zh');
    });
    const { container } = await renderCodexSettingsTab();

    expect(container.textContent).not.toContain('settings.codexProvider.');
    expect(screen.getByText('使用本地 Codex 配置')).toBeTruthy();
    expect(screen.getByText('授权状态')).toBeTruthy();
    expect(screen.getByText('当前请求来源')).toBeTruthy();
    expect(screen.getByText('当前显示的模型')).toBeTruthy();
    expect(screen.queryByText('MiniMax-M3')).toBeNull();

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: '查看全部模型' }));
    });

    expect(screen.getByText('全部匹配模型')).toBeTruthy();
    expect(screen.getByText('MiniMax-M3')).toBeTruthy();
    expect(screen.getByText('授权后可用')).toBeTruthy();
  });
});
