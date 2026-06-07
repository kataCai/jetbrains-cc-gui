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
  'settings.codexProvider.dialog.cliLoginProviderName': '使用本地配置信息',
  'settings.codexProvider.dialog.cliLoginProviderDescription': '显式授权读取：~/.codex/config.toml 和 auth.json',
  'settings.codexProvider.dialog.cliLoginAuthorizeTitle': 'Authorize Local Codex Config Access',
  'settings.codexProvider.dialog.cliLoginAuthorizeMessage': 'Read local Codex config files.',
  'settings.codexProvider.dialog.cliLoginAuthorizeDetail': 'Do not overwrite config.toml or auth.json.',
  'settings.codexProvider.dialog.cliLoginDisableTitle': 'Revoke Local Codex Config Authorization',
  'settings.codexProvider.dialog.cliLoginDisableMessage': 'Stop reading local Codex config files.',
  'settings.codexProvider.cliLogin.authorizationStatus': 'Authorization Status',
  'settings.codexProvider.cliLogin.currentUsageStatus': 'Current Usage',
  'settings.codexProvider.cliLogin.authorized': 'Authorized',
  'settings.codexProvider.cliLogin.notAuthorized': 'Not Authorized',
  'settings.codexProvider.cliLogin.currentlyUsed': 'Currently Used',
  'settings.codexProvider.cliLogin.notInUse': 'Not In Use',
  'settings.codexProvider.cliLogin.authorizeOnly': 'Authorize',
  'settings.codexProvider.cliLogin.useForRequests': 'Use for Requests',
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
  'settings.codexProvider.requestModeUnavailableBadge': '开发中',
  'settings.codexProvider.requestModeUnavailableTooltip': '当前请求模式尚未落地，暂不可测试或启用',
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
   * 验证未授权状态下点击 CLI Login 的授权按钮，只会执行授权确认链路。
   * 断言意图：授权动作不应隐式切换 active provider，避免再次把“授权”和“当前使用”绑在一起。
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

    expect(screen.getByText('使用本地配置信息')).toBeTruthy();
    expect(screen.getByText('显式授权读取：~/.codex/config.toml 和 auth.json')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Authorize' }));

    expect(screen.getByText('Authorize Local Codex Config Access')).toBeTruthy();

    const dialog = screen.getByText('Authorize Local Codex Config Access').closest('div')?.parentElement;
    const confirmButton = dialog?.querySelectorAll('button')[1];
    expect(confirmButton).toBeTruthy();
    fireEvent.click(confirmButton as HTMLButtonElement);

    expect(onSwitchCodexProvider).not.toHaveBeenCalled();
  });

  /**
   * 验证 CLI Login 卡片把授权状态与当前使用状态拆开显示。
   * 断言意图：用户可以清楚区分“已授权但未在用”和“当前请求正走 CLI Login”。
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

    expect(screen.getByText('Authorization Status: Not Authorized')).toBeTruthy();
    expect(screen.getByText('Current Usage: Not In Use')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Authorize' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Use for Requests' })).toBeNull();
  });

  /**
   * 验证已授权且当前正在使用时，卡片会显示“当前使用”状态而不是旧的账号文案。
   * 断言意图：设置页语义从“登录卡片”转为“授权态 + 运行时使用态”。
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
    expect(screen.getByText('Current Usage: Currently Used')).toBeTruthy();
  });

  /**
   * 验证已授权但未启用时，设置页保留“Use for Requests”入口来显式切换当前运行时 provider。
   * 断言意图：授权后仍需二次确认才切 active provider，符合这次语义拆分要求。
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

    fireEvent.click(screen.getByRole('button', { name: 'Use for Requests' }));

    expect(onSwitchCodexProvider).toHaveBeenCalledWith(SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN);
  });

  /**
   * 验证撤销授权仍走原有 revoke 流程，并在 CLI Login 正在使用时带上 fallback provider。
   * 断言意图：本次改造不会破坏已有的撤销授权回退路径。
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
   * 验证长备注仍会按既有样式截断，不挤压操作区。
   * 断言意图：这次新增状态块不能破坏原有 provider 卡片布局边界。
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
   * 验证 provider 元信息摘要仍按原逻辑展示。
   * 断言意图：设置页语义调整不应影响普通 managed provider 的元信息摘要。
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
   * 验证 active managed provider 的运行时来源摘要仍可见。
   * 断言意图：本次改造不回归 runtime source 诊断展示。
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
   * 断言意图：新增 CLI Login 状态块不应破坏普通 provider 的禁用逻辑。
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

    expect(screen.getByText('开发中')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Enable' }) as HTMLButtonElement).disabled).toBe(true);

    const unavailableButtons = screen.getAllByTitle('当前请求模式尚未落地，暂不可测试或启用') as HTMLButtonElement[];
    expect(unavailableButtons).toHaveLength(2);
    unavailableButtons.forEach((button) => {
      expect(button.disabled).toBe(true);
    });
  });
});
