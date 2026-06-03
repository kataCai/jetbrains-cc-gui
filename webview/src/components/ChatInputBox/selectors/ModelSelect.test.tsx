import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ModelSelect } from './ModelSelect';
import { CLAUDE_MODELS, CODEX_MODELS } from '../types';
import type { ModelInfo } from '../types';
import { STORAGE_KEYS } from '../../../types/provider';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => (
      options?.model
      ?? options?.defaultModel
      ?? options?.defaultValue
      ?? key
    ),
  }),
}));

/**
 * ModelSelect 回归测试。
 * 这一组测试同时覆盖主线的 Codex CLI 默认模型 / 自定义 base_url 提示，
 * 以及 upstream 带来的 Claude 1M 模型基线和扩展后的 Codex 模型列表。
 */
describe('ModelSelect', () => {
  const sonnetModel: ModelInfo = {
    id: 'claude-sonnet-4-6',
    label: 'Sonnet 4.6',
    description: 'Sonnet 4.6 路 Use the default model',
  };

  beforeEach(() => {
    localStorage.clear();
  });

  /**
   * 验证 rerender 后会重新读取本地 Claude 模型映射，
   * 避免设置页更新映射后选择器仍显示旧名称。
   */
  it('rerender 后应读取最新的 Claude 模型映射', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-4' }),
    );

    const { rerender } = render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4');

    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-5' }),
    );

    rerender(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-5');
  });

  /**
   * 验证没有具体模型映射时，会回退到 main 全局映射，
   * 避免 provider 只配置了 main 时 UI 退化为内置标签。
   */
  it('没有具体映射时应回退到全局 main 映射', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ main: 'glm-4.7' }),
    );

    render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4.7');
  });

  /**
   * 验证 Codex 内置模型标签仍走翻译 key，
   * 避免并轨后直接把原始 ID 渲染成最终标签。
   */
  it('应渲染 gpt-5.5 的翻译标签', () => {
    const model: ModelInfo = {
      id: 'gpt-5.5',
      label: 'gpt-5.5',
      description: 'Frontier model for complex coding, research, and real-world work.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('models.codex.gpt55.label');
  });

  /**
   * 验证新增的 gpt-5.4-mini 仍走翻译标签，
   * 避免扩充模型表后部分新模型缺失翻译映射。
   */
  it('应渲染 gpt-5.4-mini 的翻译标签', () => {
    const model: ModelInfo = {
      id: 'gpt-5.4-mini',
      label: 'gpt-5.4-mini',
      description: 'Small, fast, and cost-efficient model for simpler coding tasks.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('models.codex.gpt54mini.label');
  });

  /**
   * 验证当前会话模型与 CLI 默认模型不一致时，按钮上会保留 CLI 默认提示，
   * 避免用户误以为 CLI 默认模型已经被会话内模型替换。
   */
  it('会在当前模型与 CLI 默认模型不一致时展示默认模型提示', () => {
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Latest frontier model with enhanced capabilities.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
        defaultCodexModelFromConfig="gpt-5.5"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('gpt-5.5');
  });

  /**
   * 验证自定义 Codex base_url 时，下拉框会显示风险提示，
   * 这样用户能明确知道上游不保证所有模型均可用。
   */
  it('会在下拉框中展示自定义 Codex base_url 风险提示', () => {
    const model: ModelInfo = {
      id: 'gpt-5.5',
      label: 'gpt-5.5',
      description: 'Frontier model for complex coding, research, and real-world work.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
        codexBaseUrl="https://rayplus.site"
        codexUsesCustomBaseUrl
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('Custom OpenAI base URL')).toBeTruthy();
    expect(screen.getByText('https://rayplus.site may not support all model selections.')).toBeTruthy();
  });

  /**
   * 验证 Claude 内置模型列表只保留基础 ID，
   * 避免旧版 `[1m]` 变体重新回流到静态模型表中。
   */
  it('Claude 内置模型列表应默认使用不带 [1m] 的 Opus 4.6 ID', () => {
    expect(CLAUDE_MODELS.map((model) => model.id)).toContain('claude-opus-4-6');
    expect(CLAUDE_MODELS.map((model) => model.id)).not.toContain('claude-opus-4-6[1m]');
  });

  /**
   * 验证 Codex 内置模型列表包含第二阶段并轨需要的全部模型，
   * 防止后续重构时把 upstream 新增模型意外删掉。
   */
  it('Codex 内置模型列表应与目标设计一致', () => {
    expect(CODEX_MODELS.map((model) => model.id)).toEqual([
      'gpt-5.5',
      'gpt-5.4',
      'gpt-5.2-codex',
      'gpt-5.1-codex-max',
      'gpt-5.4-mini',
      'gpt-5.3-codex',
      'gpt-5.3-codex-spark',
      'gpt-5.2',
      'gpt-5.1-codex-mini',
    ]);
  });
  /**
   * 验证 Codex 场景下点击“添加模型”后，不再直接触发旧的单一路径回调。
   * 正确行为应先展开动作菜单，让用户区分“新增供应商配置”“管理当前供应商模型”和“添加模型别名（高级）”。
   */
  it('Codex 场景下应先展开模型管理动作菜单而不是直接触发添加回调', () => {
    const onAddModel = vi.fn();
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Strong model for everyday coding.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
        onAddModel={onAddModel}
      />,
    );

    fireEvent.click(screen.getByRole('button'));
    fireEvent.click(screen.getByText('models.addModel'));

    expect(screen.getByText('chat.addCodexProviderAction')).toBeTruthy();
    expect(screen.getByText('chat.manageCurrentCodexProviderModelsAction')).toBeTruthy();
    expect(screen.getByText('chat.addCodexModelAliasAction')).toBeTruthy();
    expect(onAddModel).not.toHaveBeenCalled();
  });
});
