import { readFileSync } from 'node:fs';
import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CodexProviderSection from './index';
import { SPECIAL_PROVIDER_IDS } from '../../../types/provider';

const providerListStyles = readFileSync(
  'src/components/settings/ProviderList/style.module.less',
  'utf8'
);

const translations: Record<string, string> = {
  'settings.codexProvider.title': 'Codex Provider Management',
  'settings.codexProvider.description': 'Manage Codex providers',
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
  'settings.codexProvider.cliLogin.useForRequests': 'Use for requests',
  'settings.codexProvider.cliLogin.currentlyUsingAction': 'Currently in use',
  'settings.codexProvider.providerTypeMeta': 'Preset: {{type}}',
  'settings.codexProvider.baseUrlMeta': 'Base URL: {{baseUrl}}',
  'settings.codexProvider.modelCountMeta': 'Models: {{count}}',
  'settings.provider.loading': 'Loading',
  'settings.provider.allProviders': 'All Providers',
  'settings.provider.authorizeAndEnable': 'Authorize and Enable',
  'settings.provider.revokeAuthorization': 'Revoke Authorization',
  'settings.provider.enable': 'Enable',
  'settings.provider.inUse': 'In Use',
  'settings.provider.dragToSort': 'Drag to sort',
  'settings.codexProvider.requestModeUnavailableBadge': 'Coming Soon',
  'settings.codexProvider.requestModeUnavailableTooltip': 'This request mode is not implemented yet, so testing and enabling are disabled.',
  'common.add': 'Add',
  'common.cancel': 'Cancel',
  'common.edit': 'Edit',
  'common.delete': 'Delete',
  'settings.codexProvider.runtimeSourceLabel': 'Runtime Source: {{source}}',
  'settings.codexProvider.runtimeSource.managedProvider': 'Managed Provider',
  'settings.codexProvider.runtimeSource.codexLocalConfig': 'Codex Local Config',
  'settings.codexProvider.runtimeSource.sdkDefault': 'SDK Default',
  'settings.codexProvider.runtimeSource.proxyFallback': 'Proxy Fallback',
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
        template
      );
    },
  }),
}));

describe('CodexProviderSection', () => {
  const onAddCodexProvider = vi.fn();
  const onEditCodexProvider = vi.fn();
  const onDeleteCodexProvider = vi.fn();
  const onTestCodexProvider = vi.fn();
  const onSwitchCodexProvider = vi.fn();
  const onRevokeCodexLocalConfigAuthorization = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  /**
   * 验证未授权状态下的 CLI Login 卡片采用“摘要说明 + 只读提示 + 授权动作”结构。
   * 断言意图：用户先看到读取范围和只读边界，再决定是否进入授权确认链路。
   */
  it('renders translated CLI login copy and confirms before authorization only', () => {
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
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    expect(screen.getByText('Use local Codex CLI profile')).toBeTruthy();
    expect(screen.getByText('Read your existing Codex CLI login and default settings so you can use them in CC-GUI.')).toBeTruthy();
    expect(screen.getByText('Read-only access. The plugin never overwrites ~/.codex/config.toml or auth.json.')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Authorize' }));

    expect(screen.getByText('Authorize Local Codex Config Access')).toBeTruthy();

    const dialog = screen.getByText('Authorize Local Codex Config Access').closest('div')?.parentElement;
    const confirmButton = dialog?.querySelectorAll('button')[1];
    expect(confirmButton).toBeTruthy();
    fireEvent.click(confirmButton as HTMLButtonElement);

    expect(onSwitchCodexProvider).not.toHaveBeenCalled();
  });

  /**
   * 验证 CLI Login 卡片会拆开展示授权状态和当前请求来源。
   * 断言意图：用户需要清楚区分“是否已授权读取”与“当前请求是否正在使用”。
   */
  it('separates authorization status from current usage status for CLI login', () => {
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
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    expect(screen.getByText('Authorization')).toBeTruthy();
    expect(screen.getByText('Request Source')).toBeTruthy();
    expect(screen.getByText('Not authorized')).toBeTruthy();
    expect(screen.getByText('Not in use')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Authorize' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Use for requests' })).toBeNull();
  });

  /**
   * 验证已授权且正在使用时，动作区会进入“当前使用中”状态。
   * 断言意图：这里是授权入口卡片，不再展示旧的账号摘要，而是显式锁定当前使用态。
   */
  it('does not show account info when CLI login is active', () => {
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
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    expect(screen.queryByText('Logged in as: Nicole Fox')).toBeNull();
    expect(screen.getByRole('button', { name: 'Revoke Authorization' })).toBeTruthy();
    expect(screen.getByText('Currently used')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Currently in use' })).toBeTruthy();
  });

  /**
   * 验证已授权但未启用时，卡片仍保留“用于请求”入口。
   * 断言意图：授权和切换当前请求来源必须是两个独立动作。
   */
  it('allows switching CLI login into current usage only after authorization', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN,
            name: 'Virtual CLI Login',
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={true}
        codexLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Use for requests' }));

    expect(onSwitchCodexProvider).toHaveBeenCalledWith(SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN);
  });

  /**
   * 验证撤销授权仍走既有 revoke 流程，并带上 fallback provider。
   * 断言意图：本次 UI 重构不能破坏已存在的撤销授权回退逻辑。
   */
  it('revokes local authorization instead of switching directly when CLI login is active', () => {
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
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'Revoke Authorization' }));

    const dialog = screen.getByText('Revoke Local Codex Config Authorization').closest('div')?.parentElement;
    const confirmButton = dialog?.querySelectorAll('button')[1];
    expect(confirmButton).toBeTruthy();
    fireEvent.click(confirmButton as HTMLButtonElement);

    expect(onRevokeCodexLocalConfigAuthorization).toHaveBeenCalledWith('provider-1');
    expect(onSwitchCodexProvider).not.toHaveBeenCalled();
  });

  /**
   * 验证长备注仍按既有样式截断，不挤压动作区。
   * 断言意图：新增摘要卡片结构后，普通 provider 卡片的宽度约束不能回归。
   */
  it('allows long remarks to truncate instead of squeezing the action area', () => {
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
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    expect(screen.getByText(longRemark)).toBeTruthy();
    expect(providerListStyles).toMatch(/\.cardInfo\s*\{[\s\S]*min-width:\s*0;/);
    expect(providerListStyles).toMatch(/\.cardActions\s*\{[\s\S]*flex-shrink:\s*0;/);
    expect(providerListStyles).toMatch(
      /\.website\s*\{[\s\S]*overflow:\s*hidden;[\s\S]*text-overflow:\s*ellipsis;[\s\S]*white-space:\s*nowrap;/
    );
  });

  /**
   * 验证普通 provider 元信息摘要仍按原逻辑展示。
   * 断言意图：CLI Login 卡片重构不能影响 managed provider 的摘要能力。
   */
  it('shows structured provider metadata in the provider card summary', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'provider-meta',
            name: 'MiniMax',
            presetId: 'minimax',
            baseUrl: 'https://api.minimaxi.com/v1',
            models: [
              { id: 'MiniMax-M2.5', label: 'MiniMax-M2.5' },
              { id: 'MiniMax-Text-01', label: 'MiniMax-Text-01' },
            ],
            isActive: false,
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    expect(screen.getByText('Preset: minimax')).toBeTruthy();
    expect(screen.getByText('Base URL: https://api.minimaxi.com/v1')).toBeTruthy();
    expect(screen.getByText('Models: 2')).toBeTruthy();
  });

  /**
   * 验证 active managed provider 的 runtime source 摘要仍可见。
   * 断言意图：本次改造不能回归运行时来源诊断展示。
   */
  it('shows runtime source summary for the active managed provider card', () => {
    render(
      <CodexProviderSection
        codexProviders={[
          {
            id: 'provider-runtime-source',
            name: 'MiniMax',
            isActive: true,
            effectiveConfigSource: 'codemoss_managed_provider',
            fallbackDetected: false,
            endpointSource: 'provider',
          },
        ]}
        codexLocalConfigAuthorized={false}
        codexLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    expect(screen.getByText('Runtime Source: Managed Provider')).toBeTruthy();
  });

  /**
   * 验证未落地请求模式仍会禁用启用和测试动作。
   * 断言意图：新增 CLI Login 摘要卡片后，普通 provider 的禁用逻辑不能回归。
   */
  it('disables test and enable actions for providers using unimplemented request modes', () => {
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
        onAddCodexProvider={onAddCodexProvider}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
      />
    );

    expect(screen.getByText('Coming Soon')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Enable' }) as HTMLButtonElement).disabled).toBe(true);

    const unavailableButtons = screen.getAllByTitle('This request mode is not implemented yet, so testing and enabling are disabled.') as HTMLButtonElement[];
    expect(unavailableButtons).toHaveLength(2);
    unavailableButtons.forEach((button) => {
      expect(button.disabled).toBe(true);
    });
  });
});
