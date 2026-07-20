import { readFileSync } from 'node:fs';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CodexProviderSection from './index';
import { SPECIAL_PROVIDER_IDS } from '../../../types/provider';

const providerListStyles = readFileSync(
  'src/components/settings/ProviderList/style.module.less',
  'utf8',
);

const translations: Record<string, string> = {
  'settings.codexProvider.title': 'Codex Provider Management',
  'settings.codexProvider.description': 'Manage CC-GUI Codex runtime profiles here. Use the chat model picker to apply them per tab.',
  'settings.codexProvider.emptyProvider': 'No Codex providers configured',
  'settings.codexProvider.cliLogin.title': 'Use local Codex CLI profile',
  'settings.codexProvider.cliLogin.description': 'Read your existing Codex CLI login and default settings so you can use them in CC-GUI.',
  'settings.codexProvider.cliLogin.readonlyHint': 'Read-only access. The plugin never overwrites ~/.codex/config.toml or auth.json.',
  'settings.codexProvider.dialog.cliLoginAuthorizeTitle': 'Authorize Local Codex Config Access',
  'settings.codexProvider.dialog.cliLoginAuthorizeMessage': 'Read local Codex config files.',
  'settings.codexProvider.dialog.cliLoginAuthorizeDetail': 'Do not overwrite config.toml or auth.json.',
  'settings.codexProvider.dialog.cliLoginDisableTitle': 'Revoke Local Codex Config Authorization',
  'settings.codexProvider.dialog.cliLoginDisableMessage': 'Stop reading local Codex config files.',
  'settings.codexProvider.cliLogin.authorizationStatus': 'Authorization',
  'settings.codexProvider.cliLogin.currentUsageStatus': 'Request Source',
  'settings.codexProvider.cliLogin.authorized': 'Authorized',
  'settings.codexProvider.cliLogin.notAuthorized': 'Not authorized',
  'settings.codexProvider.cliLogin.currentlyUsed': 'Currently used',
  'settings.codexProvider.cliLogin.notInUse': 'Not in use',
  'settings.codexProvider.cliLogin.authorizeOnly': 'Authorize',
  'settings.codexProvider.providerTypeMeta': 'Preset: {{type}}',
  'settings.codexProvider.baseUrlMeta': 'Base URL: {{baseUrl}}',
  'settings.codexProvider.modelCountMeta': 'Models: {{count}}',
  'settings.provider.loading': 'Loading',
  'settings.provider.allProviders': 'All Providers',
  'settings.provider.revokeAuthorization': 'Revoke Authorization',
  'settings.provider.dragToSort': 'Drag to sort',
  'settings.codexProvider.fetchModels': 'Fetch Model List',
  'settings.codexProvider.fetchModelsLoading': 'Fetching Model List',
  'settings.codexProvider.fetchModelsUnsupportedTooltip': 'This provider auth mode or request mode does not support model discovery yet.',
  'settings.codexProvider.requestModeUnavailableBadge': 'Coming Soon',
  'settings.codexProvider.requestModeUnavailableTooltip': 'This request mode is not implemented yet, so testing is disabled.',
  'common.add': 'Add',
  'common.cancel': 'Cancel',
  'common.edit': 'Edit',
  'common.delete': 'Delete',
  'settings.codexProvider.runtimeSourceLabel': 'Runtime Source: {{source}}',
  'settings.codexProvider.runtimeSource.managedProvider': 'Managed Provider',
  'settings.codexProvider.runtimeSource.codexLocalConfig': 'Codex Local Config',
  'settings.codexProvider.runtimeSource.sdkDefault': 'SDK Default',
  'settings.codexProvider.runtimeSource.proxyFallback': 'Proxy Fallback',
  'settings.codexProvider.dialog.testProvider': 'Test Provider',
};

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => {
      const template = translations[key];
      if (!template) {
        return key;
      }
      if (!options) {
        return template;
      }
      return Object.entries(options).reduce(
        (result, [token, value]) => result.replace(`{{${token}}}`, value),
        template,
      );
    },
  }),
}));

describe('CodexProviderSection', () => {
  const onAddCodexProvider = vi.fn();
  const onEditCodexProvider = vi.fn();
  const onDeleteCodexProvider = vi.fn();
  const onFetchCodexProviderModels = vi.fn();
  const onTestCodexProvider = vi.fn();
  const onRevokeCodexLocalConfigAuthorization = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  /**
   * 验证未授权时，CLI Login 卡片只保留授权入口，不再暴露运行态切换按钮。
   * 这是入口收敛后的核心约束：设置页只负责授权和配置管理，不负责当前会话切换。
   */
  it('keeps CLI login in authorization-only mode before local access is granted', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN,
            name: 'Virtual CLI Login',
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    expect(screen.getByText('Use local Codex CLI profile')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Authorize' })).toBeTruthy();
    expect(screen.queryByText('Use for requests')).toBeNull();
  });

  /**
   * 验证已授权后，CLI Login 卡片只保留撤销授权，不再提供“用于请求/当前使用中”切换入口。
   * 这样可以防止设置页继续承担运行时 provider 切换职责。
   */
  it('keeps CLI login as a management card after authorization', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN,
            name: 'Virtual CLI Login',
            isActive: true,
          },
        ]}
        codexLocalConfigAuthorized={true}
        codexLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    expect(screen.getByText('Currently used')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Revoke Authorization' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Currently in use' })).toBeNull();
    expect(screen.queryByText('Use for requests')).toBeNull();
  });

  /**
   * 验证撤销授权流程仍然保留 fallback provider 回退逻辑。
   * 本次只收敛入口，不应破坏既有的安全回退路径。
   */
  it('still revokes local authorization with the first managed provider as fallback', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN,
            name: 'Virtual CLI Login',
            isActive: true,
          },
          {
            id: 'provider-1',
            name: 'Provider 1',
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={true}
        codexLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Revoke Authorization' }));
    const dialog = screen.getByText('Revoke Local Codex Config Authorization').closest('div')?.parentElement;
    const confirmButton = dialog?.querySelectorAll('button')[1];
    expect(confirmButton).toBeTruthy();
    fireEvent.click(confirmButton as HTMLButtonElement);

    expect(onRevokeCodexLocalConfigAuthorization).toHaveBeenCalledWith('provider-1');
  });

  /**
   * 验证普通 provider 卡片只保留测试、编辑、删除等管理动作，不再显示“启用”按钮。
   * 这样用户只能在聊天区模型选择入口完成运行时模型决策。
   */
  it('removes enable actions from managed provider cards and keeps management actions', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'provider-meta',
            name: 'MiniMax',
            presetId: 'minimax',
            baseUrl: 'https://api.minimaxi.com/v1',
            models: [{ id: 'MiniMax-M2.5', label: 'MiniMax-M2.5' }],
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        syncingCodexProviderId=""
        testingCodexProviderId=""
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    expect(screen.getByText('Preset: minimax')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Enable' })).toBeNull();
    expect(screen.getAllByTitle('Fetch Model List')).toHaveLength(1);
    expect(screen.getAllByTitle('Test Provider')).toHaveLength(1);
    expect(screen.getByTitle('Edit')).toBeTruthy();
    expect(screen.getByTitle('Delete')).toBeTruthy();
  });

  /**
   * 验证普通 provider 卡片会在测试按钮前新增“获取模型列表”入口，并把当前 provider 透传给回调。
   * 断言意图：确保新增动作没有和既有测试、编辑、删除顺序混淆，且点击后仍以当前卡片的 provider 为唯一上下文。
   */
  it('shows a fetch-models action before the test button and forwards the provider payload', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'provider-fetch',
            name: 'Provider Fetch',
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        syncingCodexProviderId=""
        testingCodexProviderId=""
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    const card = screen.getByText('Provider Fetch').closest('[data-drag-sort-id]');
    const titledButtons = Array.from(card?.querySelectorAll('button[title]') || []).map(
      (button) => button.getAttribute('title')
    );
    expect(titledButtons).toEqual([
      'Fetch Model List',
      'Test Provider',
      'Edit',
      'Delete',
    ]);

    fireEvent.click(screen.getByTitle('Fetch Model List'));
    expect(onFetchCodexProviderModels).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'provider-fetch', name: 'Provider Fetch' })
    );
  });

  /**
   * 验证只有当前正在同步模型的 provider 会进入独立 loading 态，且不会误伤其他卡片的测试按钮状态。
   * 断言意图：新增同步状态必须和测试连接状态分离，避免一个动作触发后把整个动作区都锁死。
   */
  it('shows an isolated loading state for the syncing provider fetch button', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'provider-syncing',
            name: 'Provider Syncing',
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        syncingCodexProviderId="provider-syncing"
        testingCodexProviderId=""
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    const fetchButton = screen.getByTitle('Fetching Model List') as HTMLButtonElement;
    expect(fetchButton.disabled).toBe(true);
    expect(fetchButton.querySelector('.codicon-loading')).toBeTruthy();
    expect(screen.getByTitle('Test Provider')).toBeTruthy();
  });

  /**
   * 验证未落地请求模式只会禁用测试动作，不再附带“启用”按钮的禁用断言。
   * 这条回归保护收口后的新语义：设置页不再负责启用，仅负责配置校验。
   */
  it('disables only the test action for unimplemented request modes', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'legacy-proxy-provider',
            name: 'Legacy Proxy',
            requestMode: 'cc_switch_proxy',
            isActive: false,
            models: [{ id: 'legacy-model', label: 'Legacy Model' }],
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        syncingCodexProviderId=""
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    expect(screen.getByText('Coming Soon')).toBeTruthy();
    const unavailableButtons = screen.getAllByTitle('This request mode is not implemented yet, so testing is disabled.') as HTMLButtonElement[];
    expect(unavailableButtons).toHaveLength(1);
    expect(unavailableButtons[0].disabled).toBe(true);
  });

  /**
   * 验证前端会对当前不支持模型拉取的 authMode 直接禁用新增按钮，并给出统一提示文案。
   * 断言意图：像 oauth/proxy 这类暂不稳定支持 Bearer Token 的配置，应在点击前就阻止进入错误链路。
   */
  it('disables the fetch-models action when auth mode is not supported for discovery', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'provider-oauth',
            name: 'OAuth Provider',
            authMode: 'oauth',
            requestMode: 'codex_sdk',
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        syncingCodexProviderId=""
        testingCodexProviderId=""
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    const fetchButton = screen.getByTitle(
      'This provider auth mode or request mode does not support model discovery yet.'
    ) as HTMLButtonElement;
    expect(fetchButton.disabled).toBe(true);
  });

  /**
   * 验证长备注截断和动作区宽度约束没有因为移除启用按钮而回归。
   * 这是设置页布局回归保护，确保收口后卡片仍然稳定。
   */
  it('keeps long remarks truncated without squeezing the action area', () => {
    const longRemark =
      'https://api.example.com/providers/' + 'very-long-segment/'.repeat(8);

    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'provider-long-remark',
            name: 'xinghuapi',
            remark: longRemark,
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        syncingCodexProviderId=""
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />,
    );

    expect(screen.getByText(longRemark)).toBeTruthy();
    expect(providerListStyles).toMatch(/\.cardInfo\s*\{[\s\S]*min-width:\s*0;/);
    expect(providerListStyles).toMatch(/\.cardActions\s*\{[\s\S]*flex-shrink:\s*0;/);
    expect(providerListStyles).toMatch(
      /\.website\s*\{[\s\S]*overflow:\s*hidden;[\s\S]*text-overflow:\s*ellipsis;[\s\S]*white-space:\s*nowrap;/,
    );
  });
});
