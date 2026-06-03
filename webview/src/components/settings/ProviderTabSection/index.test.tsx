import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import ProviderTabSection from './index';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'settings.providers': '供应商管理',
        'settings.providersDesc': '管理 Claude 和 Codex 供应商配置',
        'settings.providerTab.claude': 'Claude Code',
        'settings.providerTab.codex': 'Codex',
        'settings.pluginModels.title': '自定义模型',
        'settings.pluginModels.manage': '管理模型',
        'settings.codexProvider.quickCreateTitle': '新增供应商',
        'settings.codexProvider.quickCreateDescription': '为 Codex 配置可运行的供应商、Base URL、API Key 和模型列表',
        'settings.codexProvider.aliasTitle': '模型别名（高级）',
        'settings.codexProvider.aliasDescription': '只补充模型选择列表展示项，不保存供应商、密钥和请求地址',
        'common.add': '添加',
        'settings.codexProvider.title': 'Codex Provider',
        'settings.codexProvider.description': 'Manage Codex providers',
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
      <button type="button" onClick={onAddCodexProvider}>列表添加</button>
      Codex Provider Section
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
          创建供应商
        </button>
      )}
    </div>
  ) : null),
}));

/**
 * ProviderTabSection 的 Codex 入口回归测试。
 * 这些测试覆盖三件事：
 * 1. 设置页主入口首先暴露“新增供应商”。
 * 2. 模型别名入口被明确降级为高级辅助能力。
 * 3. 历史别名可以直接升级为 provider 草稿，而不是继续停留在 alias 层。
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
  const onRevokeCodexLocalConfigAuthorization = vi.fn();
  const addToast = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  /**
   * 验证 Codex 主入口优先走新增供应商，而不是打开别名弹窗。
   */
  it('应优先展示 Codex 新增供应商主入口并允许直接创建 provider', () => {
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
        onAddCodexProvider={onAddCodexProvider}
        onCreateCodexProviderFromAlias={onCreateCodexProviderFromAlias}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        addToast={addToast}
      />,
    );

    expect(screen.getByText('新增供应商')).toBeTruthy();
    expect(screen.getByText('为 Codex 配置可运行的供应商、Base URL、API Key 和模型列表')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '添加' }));

    expect(onAddCodexProvider).toHaveBeenCalledTimes(1);
  });

  /**
   * 验证 Codex 插件级模型入口被明确标识为“模型别名（高级）”。
   */
  it('应将 Codex 插件级模型入口降级为模型别名高级入口', () => {
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
        onAddCodexProvider={onAddCodexProvider}
        onCreateCodexProviderFromAlias={onCreateCodexProviderFromAlias}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        addToast={addToast}
      />,
    );

    expect(screen.getByText('模型别名（高级）')).toBeTruthy();
    expect(screen.getByText('只补充模型选择列表展示项，不保存供应商、密钥和请求地址')).toBeTruthy();
  });

  /**
   * 验证别名弹窗可以直接把当前模型升级为 provider 草稿。
   * 这里要求上层拿到的是结构化草稿，后续由 provider 表单继续补完 baseUrl 和鉴权信息。
   */
  it('应支持从 Codex 模型别名直接创建供应商草稿', () => {
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
        onAddCodexProvider={onAddCodexProvider}
        onCreateCodexProviderFromAlias={onCreateCodexProviderFromAlias}
        onEditCodexProvider={onEditCodexProvider}
        onDeleteCodexProvider={onDeleteCodexProvider}
        onTestCodexProvider={onTestCodexProvider}
        onSwitchCodexProvider={onSwitchCodexProvider}
        onRevokeCodexLocalConfigAuthorization={onRevokeCodexLocalConfigAuthorization}
        addToast={addToast}
      />,
    );

    fireEvent.click(screen.getByText('模型别名（高级）'));
    fireEvent.click(screen.getByRole('button', { name: '创建供应商' }));

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
