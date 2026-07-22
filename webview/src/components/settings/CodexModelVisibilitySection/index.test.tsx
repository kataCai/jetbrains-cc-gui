import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CodexModelVisibilitySection from './index';
import type { CodexModelCatalogItem } from '../../../types/provider';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string | number>) => {
      const translations: Record<string, string> = {
        'common.refresh': 'Refresh',
        'common.loading': 'Loading',
        'settings.codexProvider.modelsTitle': 'Models',
        'settings.codexProvider.modelsDescription': 'Control which Codex models appear in the chat model picker.',
        'settings.codexProvider.modelsSearchPlaceholder': 'Search by model or provider',
        'settings.codexProvider.modelsEmpty': 'No matching models',
        'settings.codexProvider.modelsViewAll': 'View All Models',
        'settings.codexProvider.modelsCollapse': 'Collapse',
        'settings.codexProvider.modelsVisibleSectionTitle': 'Visible models',
        'settings.codexProvider.modelsAllSectionTitle': 'All matching models',
        'settings.codexProvider.modelsSource.codex_cli_login': 'CLI Login',
        'settings.codexProvider.modelsSource.managed_provider': 'Managed Provider',
        'settings.codexProvider.modelsSource.plugin_custom': 'Model Alias',
        'settings.codexProvider.modelsSource.local_config': 'Local Config',
        'settings.codexProvider.modelsUnavailable': 'Available after authorization',
        'settings.codexProvider.modelsGroupSelectAll': 'Select All',
        'settings.codexProvider.modelsGroupDeselectAll': 'Deselect All',
        'settings.codexProvider.modelsGroupCount': '{{count}} Models',
        'common.delete': 'Delete',
      };
      const template = translations[key] ?? key;
      if (!options) {
        return template;
      }
      return Object.entries(options).reduce(
        (result, [token, value]) => result.replace(`{{${token}}}`, String(value)),
        template,
      );
    },
  }),
}));

/**
 * 构造模型展示面板使用的测试目录样本。
 * 覆盖场景：
 * 1. `Codex CLI` 分组下有一个默认可见但未授权的模型；
 * 2. `MiniMax` 分组下有一个可见模型和两个默认隐藏模型；
 * 3. `Local Config` 分组下有一个可见模型。
 * 这样可以同时覆盖默认态分组、展开后补齐隐藏模型、组级总开关和搜索子集开关。
 *
 * @return 用于渲染测试的统一模型目录数据。
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
      providerId: 'minimax',
      providerName: 'MiniMax',
      modelId: 'MiniMax-M3',
      label: 'MiniMax-M3',
      description: 'Visible managed provider model',
      source: 'managed_provider',
      visible: true,
      runnable: true,
    },
    {
      key: 'minimax::MiniMax-M3-Preview',
      providerId: 'minimax',
      providerName: 'MiniMax',
      modelId: 'MiniMax-M3-Preview',
      label: 'MiniMax-M3-Preview',
      description: 'Hidden managed provider model',
      source: 'managed_provider',
      visible: false,
      runnable: true,
    },
    {
      key: 'minimax::MiniMax-Reasoner',
      providerId: 'minimax',
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
  ];
}

describe('CodexModelVisibilitySection', () => {
  /**
   * 验证默认折叠态会按供应商分组显示当前可见模型，并在组头展开后补齐同组隐藏模型。
   * 断言意图：
   * 1. 不再使用跨供应商平铺列表；
   * 2. 默认态下 `MiniMax` 分组已经出现，但只展示当前可见模型；
   * 3. 点击组头“查看全部模型”后，同一供应商下的隐藏模型会补齐到该分组中；
   * 4. 页面底部不再出现全局“查看全部模型”按钮。
   */
  it('groups visible models by provider and reveals hidden models inside the provider after expanding the group', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />,
    );

    expect(screen.getByTestId('provider-group:__codex_cli_login__')).toBeTruthy();
    expect(screen.getByTestId('provider-group:minimax')).toBeTruthy();
    expect(screen.getByTestId('provider-group:local')).toBeTruthy();
    expect(screen.getByText('GPT-5.5')).toBeTruthy();
    expect(screen.getByText('MiniMax-M3')).toBeTruthy();
    expect(screen.getByText('GPT-5.4')).toBeTruthy();
    expect(screen.queryByText('MiniMax-M3-Preview')).toBeNull();
    expect(screen.queryByText('MiniMax-Reasoner')).toBeNull();
    expect(screen.queryByRole('button', { name: 'View All Models' })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'toggle-provider-group:minimax' }));

    expect(screen.getByText('MiniMax-M3-Preview')).toBeTruthy();
    expect(screen.getByText('MiniMax-Reasoner')).toBeTruthy();
    expect(screen.getByText('Available after authorization')).toBeTruthy();
  });

  /**
   * 验证目标：即使某个 provider 当前没有任何 visible 模型，默认态也必须展示其分组。
   * 前置条件：构造仅含 hidden 模型的 OPPO 分组。
   * 断言意图：
   * 1. 默认态可看到 OPPO 分组；
   * 2. 折叠预览会回退展示该 provider 前若干模型；
   * 3. 不需要依赖全局“查看全部模型”。
   */
  it('keeps providers without visible models in the default grouped view', () => {
    const catalog = [
      ...createCatalog(),
      {
        key: 'oppo::oppo-a',
        providerId: 'oppo',
        providerName: 'OPPO',
        modelId: 'oppo-a',
        label: 'OPPO-A',
        description: 'Hidden OPPO model A',
        source: 'managed_provider' as const,
        visible: false,
        runnable: true,
      },
      {
        key: 'oppo::oppo-b',
        providerId: 'oppo',
        providerName: 'OPPO',
        modelId: 'oppo-b',
        label: 'OPPO-B',
        description: 'Hidden OPPO model B',
        source: 'managed_provider' as const,
        visible: false,
        runnable: true,
      },
    ];

    render(
      <CodexModelVisibilitySection
        catalog={catalog}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />,
    );

    expect(screen.getByTestId('provider-group:oppo')).toBeTruthy();
    expect(screen.getAllByText('OPPO').length).toBeGreaterThan(0);
    expect(screen.getByText('OPPO-A')).toBeTruthy();
    expect(screen.getByText('OPPO-B')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'View All Models' })).toBeNull();
  });


  /**
   * 验证搜索结果仍按供应商分组展示，而不是退化回单层平铺列表。
   * 断言意图：
   * 1. 搜索供应商关键字后，只保留匹配的供应商分组；
   * 2. 该供应商下所有匹配模型都会展示，包括默认隐藏项。
   */
  it('keeps provider grouping when searching and shows all matching models in the matched provider', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText('Search by model or provider'), {
      target: { value: 'MiniMax' },
    });

    expect(screen.getByTestId('provider-group:minimax')).toBeTruthy();
    expect(screen.getByText('MiniMax-M3')).toBeTruthy();
    expect(screen.getByText('MiniMax-M3-Preview')).toBeTruthy();
    expect(screen.getByText('MiniMax-Reasoner')).toBeTruthy();
    expect(screen.queryByText('GPT-5.5')).toBeNull();
    expect(screen.queryByTestId('provider-group:__codex_cli_login__')).toBeNull();
    expect(screen.queryByTestId('provider-group:local')).toBeNull();
  });

  /**
   * 验证目标：每个供应商分组都应维护独立的展开/收起状态，而不是继续共用全局“查看全部模型”状态。
   * 前置条件：这里额外构造第二个带隐藏模型的供应商分组，避免测试只覆盖单分组场景时误把“全局展开”当成“独立展开”。
   * 断言意图：
   * 1. 仅展开 `MiniMax` 分组时，只补出该分组的隐藏模型；
   * 2. 其他仍处于折叠态的分组不应被一并展开；
   * 3. 重新收起后，当前分组的隐藏模型应再次消失。
   */
  it('expands and collapses provider groups independently', () => {
    const catalog = [
      ...createCatalog(),
      {
        key: 'moonshot::kimi-k2',
        providerId: 'moonshot',
        providerName: 'Moonshot',
        modelId: 'kimi-k2',
        label: 'Kimi K2',
        description: 'Visible Moonshot model',
        source: 'managed_provider' as const,
        visible: true,
        runnable: true,
      },
      {
        key: 'moonshot::kimi-k2-preview',
        providerId: 'moonshot',
        providerName: 'Moonshot',
        modelId: 'kimi-k2-preview',
        label: 'Kimi K2 Preview',
        description: 'Hidden Moonshot preview model',
        source: 'managed_provider' as const,
        visible: false,
        runnable: true,
      },
    ];

    render(
      <CodexModelVisibilitySection
        catalog={catalog}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />,
    );

    expect(screen.queryByText('MiniMax-M3-Preview')).toBeNull();
    expect(screen.queryByText('Kimi K2 Preview')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'toggle-provider-group:minimax' }));

    expect(screen.getByText('MiniMax-M3-Preview')).toBeTruthy();
    expect(screen.getByText('MiniMax-Reasoner')).toBeTruthy();
    expect(screen.queryByText('Kimi K2 Preview')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'toggle-provider-group:minimax' }));

    expect(screen.queryByText('MiniMax-M3-Preview')).toBeNull();
    expect(screen.queryByText('MiniMax-Reasoner')).toBeNull();
  });

  /**
   * 验证目标：供应商组头本身应承担折叠/展开交互，而组内操作区点击必须阻止事件冒泡。
   * 断言意图：
   * 1. 点击组头会展开隐藏模型；
   * 2. 点击组级可见性开关只触发保存，不会误把分组一并展开。
   */
  it('uses the provider header as the expansion trigger and keeps action clicks from bubbling into expansion', () => {
    const onSaveVisibility = vi.fn();

    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={onSaveVisibility}
      />,
    );

    expect(screen.queryByText('MiniMax-M3-Preview')).toBeNull();

    fireEvent.click(screen.getByRole('switch', { name: 'toggle-group:minimax' }));

    expect(onSaveVisibility).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('MiniMax-M3-Preview')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'toggle-provider-group:minimax' }));

    expect(screen.getByText('MiniMax-M3-Preview')).toBeTruthy();
    expect(screen.getByText('MiniMax-Reasoner')).toBeTruthy();
  });

  /**
   * 验证目标：每个供应商组内的模型都应做“visible first”的稳定分区排序。
   * 前置条件：这里故意把同组可见与隐藏模型交错排列，避免测试在原始输入本就有序时误判通过。
   * 断言意图：
   * 1. 所有 visible 模型排在 hidden 模型之前；
   * 2. visible 分区内部仍保持原输入顺序；
   * 3. hidden 分区内部也保持原输入顺序。
   */
  it('sorts provider group items by visible-first while keeping relative order stable inside each partition', () => {
    const catalog: CodexModelCatalogItem[] = [
      {
        key: 'minimax::MiniMax-M3-Preview',
        providerId: 'minimax',
        providerName: 'MiniMax',
        modelId: 'MiniMax-M3-Preview',
        label: 'MiniMax-M3-Preview',
        description: 'Hidden managed provider model',
        source: 'managed_provider',
        visible: false,
        runnable: true,
      },
      {
        key: 'minimax::MiniMax-M3',
        providerId: 'minimax',
        providerName: 'MiniMax',
        modelId: 'MiniMax-M3',
        label: 'MiniMax-M3',
        description: 'Visible managed provider model',
        source: 'managed_provider',
        visible: true,
        runnable: true,
      },
      {
        key: 'minimax::MiniMax-Reasoner',
        providerId: 'minimax',
        providerName: 'MiniMax',
        modelId: 'MiniMax-Reasoner',
        label: 'MiniMax-Reasoner',
        description: 'Hidden reasoner model',
        source: 'managed_provider',
        visible: false,
        runnable: true,
      },
      {
        key: 'minimax::MiniMax-M4',
        providerId: 'minimax',
        providerName: 'MiniMax',
        modelId: 'MiniMax-M4',
        label: 'MiniMax-M4',
        description: 'Another visible managed provider model',
        source: 'managed_provider',
        visible: true,
        runnable: true,
      },
    ];

    render(
      <CodexModelVisibilitySection
        catalog={catalog}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText('Search by model or provider'), {
      target: { value: 'MiniMax' },
    });

    const minimaxGroup = screen.getByTestId('provider-group:minimax');
    const renderedTitles = within(minimaxGroup)
      .getAllByRole('heading', { level: 5 })
      .map((element) => element.textContent);

    expect(renderedTitles).toEqual([
      'MiniMax',
      'MiniMax-M3',
      'MiniMax-M4',
      'MiniMax-M3-Preview',
      'MiniMax-Reasoner',
    ]);
  });

  /**
   * 验证供应商级总开关仍然会回传完整 visibility config，而不是局部 diff。
   * 断言意图：即使是“全选 / 全不选”这种批量交互，也必须继续满足后端全量保存协议。
   */
  // 这里单独补充中文说明，避免默认折叠态下把“当前可见子集”误当成组开关的完整作用范围。
  // 真实语义是：只要同 provider 目录里还有隐藏模型，首次点击总开关就应补齐整组可见性，并继续回传全量 config。
  it('sends the full visibility config and selects the full provider scope on first toggle when hidden models still exist', () => {
    const onSaveVisibility = vi.fn();

    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={onSaveVisibility}
      />,
    );

    fireEvent.click(screen.getByRole('switch', { name: 'toggle-group:minimax' }));

    expect(onSaveVisibility).toHaveBeenCalledWith({
      '__codex_cli_login__::gpt-5.5': { visible: true },
      'minimax::MiniMax-M3': { visible: true },
      'minimax::MiniMax-M3-Preview': { visible: true },
      'minimax::MiniMax-Reasoner': { visible: true },
      'local::gpt-5.4': { visible: true },
    });
  });

  /**
   * 验证搜索态下的供应商总开关只影响当前关键字匹配到的模型，不误伤同供应商的未匹配模型。
   * 断言意图：避免用户搜索一个子模型后点击“全选”，把同供应商下其他未匹配模型也一并改掉。
   */
  it('limits provider-level toggles to the matched subset while searching', () => {
    const onSaveVisibility = vi.fn();

    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={onSaveVisibility}
      />,
    );

    fireEvent.change(screen.getByPlaceholderText('Search by model or provider'), {
      target: { value: 'Preview' },
    });
    fireEvent.click(screen.getByRole('switch', { name: 'toggle-group:minimax' }));

    expect(onSaveVisibility).toHaveBeenCalledWith({
      '__codex_cli_login__::gpt-5.5': { visible: true },
      'minimax::MiniMax-M3': { visible: true },
      'minimax::MiniMax-M3-Preview': { visible: true },
      'minimax::MiniMax-Reasoner': { visible: false },
      'local::gpt-5.4': { visible: true },
    });
  });

  /**
   * 验证不可运行模型仍然保留在目录里，并展示授权提示。
   * 断言意图：用户需要在设置页理解“模型为什么暂时不可用”，而不是直接隐藏目录项。
   */
  it('shows non-runnable catalog items with an authorization hint', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />,
    );

    expect(screen.getByText('GPT-5.5')).toBeTruthy();
    expect(screen.getByText('Available after authorization')).toBeTruthy();
  });

  /**
   * 验证目标：模型行删除按钮会把当前目录项完整回传给上层回调，供前端桥接层继续发送删除请求。
   * 断言意图：
   * 1. 删除动作不应自己拼装不完整 payload；
   * 2. 回调参数里必须保留 provider/source/key 等后端删除分支所需字段。
   */
  it('forwards the full catalog item when the row delete action is clicked', () => {
    const onDeleteCatalogItem = vi.fn();
    const catalog = createCatalog();

    render(
      <CodexModelVisibilitySection
        catalog={catalog}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
        onDeleteCatalogItem={onDeleteCatalogItem}
      />,
    );

    expect(screen.getByRole('button', { name: 'delete:__codex_cli_login__::gpt-5.5' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'delete:minimax::MiniMax-M3' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'delete:local::gpt-5.4' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'delete:minimax::MiniMax-M3' }));

    expect(onDeleteCatalogItem).toHaveBeenCalledWith(
      expect.objectContaining({
        key: 'minimax::MiniMax-M3',
        providerId: 'minimax',
        modelId: 'MiniMax-M3',
        source: 'managed_provider',
      }),
    );
  });

  /**
   * 验证嵌入式展示模式下不会重复渲染外层已经给出的标题和说明。
   * 断言意图：`ProviderTabSection` 统一管理分组标题时，子卡片应只保留工具栏和内容区。
   */
  it('hides the local header when embedded into a higher-level group', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
        showHeader={false}
      />,
    );

    expect(screen.queryByText('Models')).toBeNull();
    expect(screen.queryByText('Control which Codex models appear in the chat model picker.')).toBeNull();
    expect(screen.getByPlaceholderText('Search by model or provider')).toBeTruthy();
    expect(screen.getByTestId('provider-group:minimax')).toBeTruthy();
  });
});
