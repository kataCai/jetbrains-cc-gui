import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import CodexModelVisibilitySection from './index';
import type { CodexModelCatalogItem } from '../../../types/provider';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => {
      const translations: Record<string, string> = {
        'common.refresh': '刷新',
        'settings.codexProvider.modelsTitle': 'Codex Models',
        'settings.codexProvider.modelsDescription': '控制聊天区模型下拉中显示哪些 Codex 模型',
        'settings.codexProvider.modelsSearchPlaceholder': '搜索模型或 provider',
        'settings.codexProvider.modelsEmpty': '暂无模型目录',
        'settings.codexProvider.modelsSource.codex_cli_login': 'CLI Login',
        'settings.codexProvider.modelsSource.managed_provider': '托管 Provider',
        'settings.codexProvider.modelsSource.plugin_custom': '插件别名',
        'settings.codexProvider.modelsSource.local_config': '本地配置',
        'settings.codexProvider.modelsUnavailable': '授权后可用',
      };
      return translations[key] ?? key;
    },
  }),
}));

/**
 * 构造设置页模型显示面板使用的最小目录样本。
 * 这里同时覆盖可运行模型与未授权模型，便于验证搜索、来源展示和可见性切换。
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
  ];
}

describe('CodexModelVisibilitySection', () => {
  /**
   * 验证模型搜索只过滤展示层，不修改原始目录。
   * 断言意图：输入关键字后，仅保留匹配 provider/model 的行，避免在大型目录下难以定位目标模型。
   */
  it('filters catalog rows by model and provider keywords', () => {
    render(
      <CodexModelVisibilitySection
        catalog={createCatalog()}
        loading={false}
        onRefresh={vi.fn()}
        onSaveVisibility={vi.fn()}
      />
    );

    fireEvent.change(screen.getByPlaceholderText('搜索模型或 provider'), {
      target: { value: 'MiniMax' },
    });

    expect(screen.getByText('MiniMax-M3')).toBeTruthy();
    expect(screen.queryByText('GPT-5.5')).toBeNull();
  });

  /**
   * 验证切换某一行的可见性时，会回传完整 visibility config。
   * 断言意图：后端保存接口需要全量 key->visible 映射，前端不能只发当前被点击的单条记录。
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

    fireEvent.click(screen.getByLabelText('toggle:minimax::MiniMax-M3'));

    expect(onSaveVisibility).toHaveBeenCalledWith({
      '__codex_cli_login__::gpt-5.5': { visible: true },
      'minimax::MiniMax-M3': { visible: true },
    });
  });

  /**
   * 验证不可运行模型会暴露原因提示，但仍允许用户看到该模型项。
   * 断言意图：CLI Login 未授权时，模型应继续出现在设置页里，以便用户理解为什么聊天区里暂不可用。
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
    expect(screen.getByText('授权后可用')).toBeTruthy();
  });
});
