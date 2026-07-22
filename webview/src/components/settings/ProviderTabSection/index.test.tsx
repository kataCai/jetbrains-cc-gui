import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { CodexModelCatalogItem } from '../../../types/provider';
import ProviderTabSection from './index';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.providers': 'Providers',
        'settings.providersDesc': 'Manage Claude and Codex provider configurations. Apply runtime provider or model changes from the chat model picker.',
        'settings.providerTab.claude': 'Claude Code',
        'settings.providerTab.codex': 'Codex',
        'settings.pluginModels.title': 'Custom Models',
        'settings.pluginModels.manage': 'Manage Models',
        'settings.codexProvider.quickCreateTitle': 'Add Provider',
        'settings.codexProvider.quickCreateDescription': 'Create a runnable Codex provider with Base URL, API Key, and models',
        'settings.codexProvider.aliasTitle': 'Model Aliases (Advanced)',
        'settings.codexProvider.aliasDescription': 'Only affect model picker display entries',
        'settings.codexProvider.description': 'Manage CC-GUI Codex runtime profiles here. Use the chat model picker to apply them per tab.',
        'settings.provider.allProviders': 'All Providers',
        'settings.codexProvider.modelsTitle': 'Models',
        'settings.codexProvider.modelsDescription': 'Control which Codex models appear in the chat model picker.',
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
  default: ({
    onAddCodexProvider,
    showProviderListHeader,
  }: {
    onAddCodexProvider: () => void;
    showProviderListHeader?: boolean;
  }) => (
    <div data-testid="codex-provider-section">
      <span>{showProviderListHeader === false ? 'Provider Header Hidden' : 'Provider Header Visible'}</span>
      <button type="button" onClick={onAddCodexProvider}>List Add</button>
      Codex Provider Section
    </div>
  ),
}));

vi.mock('../CodexModelVisibilitySection', () => ({
  default: ({ showHeader }: { showHeader?: boolean }) => (
    <div data-testid="codex-model-visibility-section">
      <span>{showHeader === false ? 'Models Header Hidden' : 'Models Header Visible'}</span>
      Codex Model Visibility Section
    </div>
  ),
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
  const onFetchCodexProviderModels = vi.fn();
  const onTestCodexProvider = vi.fn();
  const onAuthorizeCodexLocalConfig = vi.fn();
  const onRevokeCodexLocalConfigAuthorization = vi.fn();
  const onRefreshCodexModelCatalog = vi.fn();
  const onSaveCodexModelVisibility = vi.fn();
  const onDeleteCodexModelCatalogItem = vi.fn();
  const addToast = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  /**
   * 验证设置页整体文案明确表达“这里只做配置管理”，而不是运行时切换入口。
   * 断言意图：供应商和模型的即时应用应继续走聊天区模型选择器，避免设置页重新承担会话切换职责。
   */
  it('frames provider settings as configuration management rather than a runtime switch entry', () => {
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
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        onDeleteCodexModelCatalogItem={onDeleteCodexModelCatalogItem}
        addToast={addToast}
      />,
    );

    expect(screen.getByText('Manage Claude and Codex provider configurations. Apply runtime provider or model changes from the chat model picker.')).toBeTruthy();
    expect(screen.getByText('Manage CC-GUI Codex runtime profiles here. Use the chat model picker to apply them per tab.')).toBeTruthy();
    expect(screen.queryByText('Switch provider')).toBeNull();
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
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        onDeleteCodexModelCatalogItem={onDeleteCodexModelCatalogItem}
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
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        onDeleteCodexModelCatalogItem={onDeleteCodexModelCatalogItem}
        addToast={addToast}
      />,
    );

    expect(screen.getByText('Model Aliases (Advanced)')).toBeTruthy();
    expect(screen.getByText('Only affect model picker display entries')).toBeTruthy();
  });

  /**
   * 验证 Codex 页签已经拆成入口区、Provider 区和 Models 区三个层次。
   * 断言意图：本轮收口需要明确分组节奏，而不是把所有内容继续平铺在同一段里。
   */
  it('groups Codex entries, provider management, and model visibility into separate sections', () => {
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
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        onDeleteCodexModelCatalogItem={onDeleteCodexModelCatalogItem}
        addToast={addToast}
      />
    );

    expect(screen.getAllByText('Codex')).toHaveLength(3);
    expect(screen.getByText('All Providers')).toBeTruthy();
    expect(screen.getByText('Manage CC-GUI Codex runtime profiles here. Use the chat model picker to apply them per tab.')).toBeTruthy();
    expect(screen.getByText('Models')).toBeTruthy();
    expect(screen.getByText('Control which Codex models appear in the chat model picker.')).toBeTruthy();
    expect(screen.getByText('Provider Header Hidden')).toBeTruthy();
    expect(screen.getByText('Models Header Hidden')).toBeTruthy();
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
        onFetchCodexProviderModels={onFetchCodexProviderModels}
        onTestCodexProvider={onTestCodexProvider}
        onAuthorizeCodexLocalConfig={onAuthorizeCodexLocalConfig}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        onRefreshCodexModelCatalog={onRefreshCodexModelCatalog}
        onSaveCodexModelVisibility={onSaveCodexModelVisibility}
        onDeleteCodexModelCatalogItem={onDeleteCodexModelCatalogItem}
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
