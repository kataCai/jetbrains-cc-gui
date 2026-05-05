import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ModelSelect } from './ModelSelect';
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

describe('ModelSelect', () => {
  const sonnetModel: ModelInfo = {
    id: 'claude-sonnet-4-6',
    label: 'Sonnet 4.6',
    description: 'Sonnet 4.6 · Use the default model',
  };

  beforeEach(() => {
    localStorage.clear();
  });

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
  it('should render gpt-5.5 with translated built-in label', () => {
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

  it('should render gpt-5.4-mini with translated built-in label', () => {
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

  it('shows codex cli default model badge when session model differs from cli default', () => {
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

  it('shows custom codex base_url warning inside dropdown', () => {
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
});
