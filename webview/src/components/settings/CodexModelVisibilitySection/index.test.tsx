import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CodexModelVisibilitySection from './index';
import type { CodexModelCatalogItem } from '../../../types/provider';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
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
      };
      return translations[key] ?? key;
    },
  }),
}));

/**
 * 构造模型展示面板使用的最小目录样本。
 * 该样本同时覆盖：
 * 1. 默认可见且未授权的 CLI Login 模型；
 * 2. 默认隐藏的托管 Provider 模型；
 * 3. 默认可见的本地配置模型。
 * 这样可以在同一组测试里覆盖折叠视图、展开视图、搜索和可见性切换。
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

describe('CodexModelVisibilitySection', () => {
  /**
   * 验证默认折叠视图只展示“当前可见模型”子集。
   * 断言意图：设置页初始态优先回答“聊天区现在能看到什么”，
   * 而不是把完整目录直接铺满整张卡片。
   */
  it('shows visible models first and reveals the full catalog after clicking view all', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />
    );

    expect(screen.getByText('Visible models')).toBeTruthy();
    expect(screen.getByText('GPT-5.5')).toBeTruthy();
    expect(screen.getByText('GPT-5.4')).toBeTruthy();
    expect(screen.queryByText('MiniMax-M3')).toBeNull();
    expect(screen.getByRole('button', { name: 'View All Models' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'View All Models' }));

    expect(screen.getByText('All matching models')).toBeTruthy();
    expect(screen.getByText('MiniMax-M3')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Collapse' })).toBeTruthy();
  });

  /**
   * 验证搜索会自动切换到匹配结果视图。
   * 断言意图：一旦用户已经给出明确关键词，隐藏模型也应直接出现，
   * 不应继续受到默认折叠规则限制。
   */
  it('switches to matching results when searching for a hidden model', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />
    );

    fireEvent.change(screen.getByPlaceholderText('Search by model or provider'), {
      target: { value: 'MiniMax' },
    });

    expect(screen.getByText('All matching models')).toBeTruthy();
    expect(screen.getByText('MiniMax-M3')).toBeTruthy();
    expect(screen.queryByText('GPT-5.5')).toBeNull();
  });

  /**
   * 验证切换模型可见性时仍会回传完整 visibility config。
   * 断言意图：即使 UI 从文本按钮升级成正式开关卡片，
   * 也不能破坏后端要求的“提交全量映射”协议。
   */
  it('sends the full visibility config when toggling a model switch', () => {
    const onSaveVisibility = vi.fn();

    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={onSaveVisibility}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'View All Models' }));
    fireEvent.click(screen.getByRole('switch', { name: 'toggle:minimax::MiniMax-M3' }));

    expect(onSaveVisibility).toHaveBeenCalledWith({
      '__codex_cli_login__::gpt-5.5': { visible: true },
      'minimax::MiniMax-M3': { visible: true },
      'local::gpt-5.4': { visible: true },
    });
  });

  /**
   * 验证不可运行模型仍保留在目录里，并展示授权提示。
   * 断言意图：用户需要在设置页理解“模型为什么暂时不可用”，
   * 而不是把对应目录项直接隐藏掉。
   */
  it('shows non-runnable catalog items with an authorization hint', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />
    );

    expect(screen.getByText('GPT-5.5')).toBeTruthy();
    expect(screen.getByText('Available after authorization')).toBeTruthy();
  });

  /**
   * 验证嵌入式展示模式下不会重复渲染外层已经给出的标题和说明。
   * 断言意图：ProviderTabSection 统一管理分组标题时，子卡片应只保留工具栏和内容区。
   */
  it('hides the local header when embedded into a higher-level group', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
        showHeader={false}
      />
    );

    expect(screen.queryByText('Models')).toBeNull();
    expect(screen.queryByText('Control which Codex models appear in the chat model picker.')).toBeNull();
    expect(screen.getByPlaceholderText('Search by model or provider')).toBeTruthy();
  });
});
