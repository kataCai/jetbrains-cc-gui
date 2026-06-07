import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CodexModelCatalogItem } from '../../../types/provider';
import ProviderTabSection from './index';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.providers': 'Providers',
        'settings.providersDesc': 'Manage Claude and Codex providers',
        'settings.providerTab.claude': 'Claude Code',
        'settings.providerTab.codex': 'Codex',
        'settings.pluginModels.title': 'Custom Models',
        'settings.pluginModels.manage': 'Manage Models',
        'settings.codexProvider.quickCreateTitle': 'Add Provider',
        'settings.codexProvider.quickCreateDescription': 'Create a runnable Codex provider with Base URL, API Key, and models',
        'settings.codexProvider.aliasTitle': 'Model Aliases (Advanced)',
        'settings.codexProvider.aliasDescription': 'Only affect model picker display entries',
        'common.add': 'Add',
      };
      return translations[key] ?? key;
    },
  }),
}));

vi.mock('../ProviderManageSection', () => ({
  default: () => <div data-testid="claude-provider-manage-section">Claude Provider Section</div>,
}));

vi.mock('../CodexProviderSection', () => ({
  default: ({ onAddCodexProvider }: { onAddCodexProvider: () => void }) => (
    <div data-testid="codex-provider-section">
      <button type="button" onClick={onAddCodexProvider}>List Add</button>
      Codex Provider Section
    </div>
  ),
}));

vi.mock('../CodexModelVisibilitySection', () => ({
  default: () => <div data-testid="codex-model-visibility-section">Codex Model Visibility Section</div>,
}));

vi.mock('../CustomModelDialog', () => ({
  default: ({
    isOpen,
    title,
    description,
    onCreateProviderFromModel,
  }: {
    isOpen: boolean;
    title?: string;
    description?: string;
    onCreateProviderFromModel?: (model: { id: string; label: string; description?: string }) => void;
  }) => (isOpen ? (
    <div data-testid="custom-model-dialog">
      <span>{title}</span>
      <span>{description}</span>
      {onCreateProviderFromModel && (
        <button
          type="button"
          onClick={() => onCreateProviderFromModel({
            id: 'alias-model',
            label: 'Alias Model',
            description: 'Alias Desc',
          })}
        >
          Create Provider
        </button>
      )}
    </div>
  ) : null),
}));

const emptyCatalog: CodexModelCatalogItem[] = [];

/**
 * ProviderTabSection 的 Codex 入口回归测试。
 * 这些测试覆盖新增 provider 入口、模型别名高级入口，以及从别名直接生成 provider 草稿的链路。
 */
describe('ProviderTabSection', () => {
  const onAddProvider = vi.fn();
  const onEditProvider = vi.fn();
  const onDeleteProvider = vi.fn();
  const onSwitchProvider = vi.fn();
  const onAddCodexProvider = vi.fn();
  const onCreateCodexProviderFromAlias = vi.fn();
  const onEditCodexProvider = vi.fn();
  const onDeleteCodexProvider = vi.fn();
  const onTestCodexProvider = vi.fn();
  const onSwitchCodexProvider = vi.fn();
  const onAuthorizeCodexLocalConfig = vi.fn();
  const onRevokeCodexLocalConfigAuthorization = vi.fn();
  const onRefreshCodexModelCatalog = vi.fn();
  const onSaveCodexModelVisibility = vi.fn();
  const addToast = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  /**
   * 验证 Codex 主入口优先暴露新增 provider，而不是直接打开模型别名对话框。
   */
  it('shows Codex quick-create entry and allows direct provider creation', () => {
    render(
      <ProviderTabSection
        currentProvider="codex"
        providers={[]}
        loading={false}
        onAddProvider={onAddProvider}
        onEditProvider={onEditProvider}
        onDeleteProvider={onDeleteProvider}
        onSwitchProvider={onSwitchProvider}
        codexProviders={[]}
        codexLoading={false}
        codexModelCatalog={emptyCatalog}
        codexModelCatalogLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onCreateCodexProviderFromAlias={onCreateCodexProviderFromAlias}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        addToast={addToast}
      />,
    );

    expect(screen.getByText('Add Provider')).toBeTruthy();
    expect(screen.getByText('Create a runnable Codex provider with Base URL, API Key, and models')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Add' }));

    expect(onAddCodexProvider).toHaveBeenCalledTimes(1);
  });

  /**
   * 验证模型别名入口被明确标记为高级能力，并仍保留独立入口。
   */
  it('keeps model alias management as an advanced Codex entry', () => {
    render(
      <ProviderTabSection
        currentProvider="codex"
        providers={[]}
        loading={false}
        onAddProvider={onAddProvider}
        onEditProvider={onEditProvider}
        onDeleteProvider={onDeleteProvider}
        onSwitchProvider={onSwitchProvider}
        codexProviders={[]}
        codexLoading={false}
        codexModelCatalog={emptyCatalog}
        codexModelCatalogLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onCreateCodexProviderFromAlias={onCreateCodexProviderFromAlias}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        addToast={addToast}
      />,
    );

    expect(screen.getByText('Model Aliases (Advanced)')).toBeTruthy();
    expect(screen.getByText('Only affect model picker display entries')).toBeTruthy();
  });

  /**
   * 验证从 Codex 模型别名入口可以直接生成 provider 草稿，供上层继续补全鉴权和地址配置。
   */
  it('creates a provider draft directly from a Codex model alias', () => {
    render(
      <ProviderTabSection
        currentProvider="codex"
        providers={[]}
        loading={false}
        onAddProvider={onAddProvider}
        onEditProvider={onEditProvider}
        onDeleteProvider={onDeleteProvider}
        onSwitchProvider={onSwitchProvider}
        codexProviders={[]}
        codexLoading={false}
        codexModelCatalog={emptyCatalog}
        codexModelCatalogLoading={false}
        onAddCodexProvider={onAddCodexProvider}
        onCreateCodexProviderFromAlias={onCreateCodexProviderFromAlias}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        addToast={addToast}
      />,
    );

    fireEvent.click(screen.getByText('Model Aliases (Advanced)'));
    fireEvent.click(screen.getByRole('button', { name: 'Create Provider' }));

    expect(onCreateCodexProviderFromAlias).toHaveBeenCalledWith({
      name: 'Alias Model',
      providerType: 'custom_gateway',
      presetId: 'custom_gateway',
      authMode: 'api_key',
      requestMode: 'codex_sdk',
      models: [
        {
          id: 'alias-model',
          label: 'Alias Model',
          description: 'Alias Desc',
          reasoningEffort: undefined,
        },
      ],
    });
  });
});
