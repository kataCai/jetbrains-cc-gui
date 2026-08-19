import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ButtonArea } from './ButtonArea';
import {
  getChatExecutionMode,
  getComposerUsageMode,
  resolvePermissionModeFromComposer,
} from './modeViewModel';

vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>();
  const translatedText: Record<string, string> = {
    'chat.composerMode': 'Composer mode',
    'chat.chatMode': 'Chat',
    'chat.planModeLabel': 'Plan',
    'chat.planRequiresClaudeHint': 'Plan requires Claude. Switch to Claude to use it.',
    'modes.default.label': 'Default',
    'modes.acceptEdits.label': 'Accept Edits',
    'modes.bypassPermissions.label': 'Bypass Permissions',
  };
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, options?: { defaultValue?: string; [key: string]: string | number | undefined } | string) => {
        const template = translatedText[key];
        if (template) {
          if (!options || typeof options === 'string') {
            return template;
          }
          return Object.entries(options).reduce((result, [token, value]) => {
            if (token === 'defaultValue' || value === undefined) {
              return result;
            }
            return result.replace(`{{${token}}}`, String(value));
          }, template);
        }
        if (typeof options === 'string') return options;
        return options?.defaultValue ?? key;
      },
    }),
  };
});

describe('ButtonArea', () => {
  it('maps composer and execution modes for plan overlay behavior', () => {
    // 先验证纯 view-model 映射，确保 Chat/Plan 与 execution mode 的组合关系正确。
    expect(getComposerUsageMode('plan')).toBe('plan');
    expect(getComposerUsageMode('acceptEdits')).toBe('chat');
    expect(getChatExecutionMode('plan', 'acceptEdits')).toBe('acceptEdits');
    expect(resolvePermissionModeFromComposer('plan', 'acceptEdits')).toBe('plan');
    expect(resolvePermissionModeFromComposer('chat', 'acceptEdits')).toBe('acceptEdits');
  });

  it('hides Chat / Plan switch for codex', () => {
    render(
      <ButtonArea
        hasInputContent
        selectedModel="claude-sonnet-4-6"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModeSelect={vi.fn()}
      />
    );

    // Codex 当前不支持 plan，输入区不应再渲染易误导的 Chat / Plan 切换入口。
    expect(screen.queryByRole('tablist', { name: /Composer mode/i })).toBeNull();
    expect(screen.queryByText(/switch to claude/i)).toBeNull();
    expect(screen.queryByRole('button', { name: /^Plan$/i })).toBeNull();
  });

  it('restores prior chat execution mode after leaving plan mode', () => {
    const onModeSelect = vi.fn();

    const { rerender } = render(
      <ButtonArea
        hasInputContent
        selectedModel="claude-sonnet-4-6"
        permissionMode="acceptEdits"
        currentProvider="claude"
        onSubmit={() => {}}
        onModeSelect={onModeSelect}
      />
    );

    // 从 acceptEdits 切到 plan，再切回 chat 时，应恢复到用户之前的执行模式选择。
    const modeToggle = screen.getByRole('tablist', { name: /Composer mode/i });
    const planButton = within(modeToggle).getByRole('button', { name: /Plan/i });
    fireEvent.click(planButton);
    expect(onModeSelect).toHaveBeenLastCalledWith('plan');

    rerender(
      <ButtonArea
        hasInputContent
        selectedModel="claude-sonnet-4-6"
        permissionMode="plan"
        currentProvider="claude"
        onSubmit={() => {}}
        onModeSelect={onModeSelect}
      />
    );

    const updatedToggle = screen.getByRole('tablist', { name: /Composer mode/i });
    const updatedChatButton = within(updatedToggle).getByRole('button', { name: /Chat/i });
    fireEvent.click(updatedChatButton);
    expect(onModeSelect).toHaveBeenLastCalledWith('acceptEdits');
  });

  it('does not expose plan option inside execution mode dropdown', () => {
    const { container } = render(
      <ButtonArea
        hasInputContent
        selectedModel="claude-sonnet-4-6"
        permissionMode="default"
        currentProvider="claude"
        onSubmit={() => {}}
        onModeSelect={() => {}}
      />
    );

    // plan 已提升到顶层切换，不应继续出现在底层 execution mode 下拉里。
    const modeButton = screen.getByRole('button', { name: /Default/i });
    expect(modeButton).toBeTruthy();
    fireEvent.click(modeButton);
    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('Default')).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('Accept Edits')).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('Bypass Permissions')).toBeTruthy();
    expect(within(dropdown as HTMLElement).queryByText('Plan')).toBeNull();
  });

  it('keeps non-model selector dropdowns free from model-specific scroll shell', () => {
    const { container } = render(
      <ButtonArea
        hasInputContent
        selectedModel="claude-sonnet-4-6"
        permissionMode="default"
        currentProvider="claude"
        onSubmit={() => {}}
        onModeSelect={() => {}}
      />,
    );

    // 本次改造只应该落在模型下拉，不应把模式下拉也改成模型专用滚动壳。
    const modeButton = screen.getByRole('button', { name: /Default/i });
    fireEvent.click(modeButton);

    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect((dropdown as HTMLElement).classList.contains('selector-dropdown--model')).toBe(false);
    expect(container.querySelector('.selector-dropdown-body')).toBeNull();
    expect(container.querySelector('.selector-dropdown-progress')).toBeNull();
  });

  it('uses codex catalog entries with provider labels before falling back to built-in models', () => {
    /**
     * 验证目标：
     * 聊天区接入统一 catalog 后，应优先渲染后端回推的 catalog，而不是继续拼 active provider models。
     *
     * 断言意图：
     * 1. 下拉展示 catalog 中的模型项；
     * 2. 同一项上可看到 provider 标签；
     * 3. 不再回退显示内置 GPT 列表。
     */
    const { container } = render(
      <ButtonArea
        hasInputContent
        selectedModel="MiniMax-M2.7"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModelSelect={() => {}}
      />
    );

    act(() => {
      window.updateCodexModelCatalog?.(JSON.stringify([
        {
          key: 'minimax-cn::MiniMax-M2.7',
          providerId: 'minimax-cn',
          providerName: 'MiniMax CN',
          modelId: 'MiniMax-M2.7',
          label: 'MiniMax M2.7',
          visible: true,
          runnable: true,
          source: 'managed_provider',
        },
      ]));
    });

    fireEvent.click(screen.getByRole('button', { name: /MiniMax/i }));
    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('MiniMax M2.7')).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('MiniMax CN')).toBeTruthy();
    expect(within(dropdown as HTMLElement).queryByText('GPT-5.4')).toBeNull();
  });

  it('shows config hint when active Codex provider has no models', () => {
    render(
      <ButtonArea
        hasInputContent
        selectedModel="gpt-5.4"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModelSelect={() => {}}
      />
    );

    act(() => {
      window.updateActiveCodexProvider?.(JSON.stringify({
        id: 'managed-empty-provider',
        name: 'Managed Empty Provider',
        models: [],
      }));
    });

    expect(screen.getByText('chat.codexModelConfigRequired')).toBeTruthy();
  });

  /**
   * 验证目标：
   * 当 provider 显式保存 `models: []` 时，聊天区也不能再把历史 `customModels` 当作可用模型回退展示。
   *
   * 前置条件：
   * active Codex provider 同时携带空 `models` 和旧 `customModels`。
   *
   * 断言意图：
   * 1. 选择器仍应显示“未配置模型”提示；
   * 2. 旧 `customModels` 不应出现在当前 provider 的模型按钮里。
   */
  it('ignores legacy customModels when an explicit empty models array exists', () => {
    render(
      <ButtonArea
        hasInputContent
        selectedModel="gpt-5.4"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModelSelect={() => {}}
      />
    );

    act(() => {
      window.updateActiveCodexProvider?.(JSON.stringify({
        id: 'managed-empty-provider',
        name: 'Managed Empty Provider',
        models: [],
        customModels: [{ id: 'legacy-model', label: 'Legacy Model' }],
      }));
    });

    expect(screen.getByText('chat.codexModelConfigRequired')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /legacy-model/i })).toBeNull();
  });

  it('keeps the currently selected codex model visible when catalog does not include it yet', () => {
    /**
     * 验证目标：
     * 当前会话模型可能来自旧状态恢复，而 catalog 回推稍后才到。
     * 这时聊天区不能把当前选中值从按钮和下拉里“吃掉”。
     */
    const { container } = render(
      <ButtonArea
        hasInputContent
        selectedModel="custom-codex-model"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModelSelect={() => {}}
      />
    );

    act(() => {
      window.updateCodexModelCatalog?.(JSON.stringify([
        {
          key: 'minimax-cn::MiniMax-M2.7',
          providerId: 'minimax-cn',
          providerName: 'MiniMax CN',
          modelId: 'MiniMax-M2.7',
          label: 'MiniMax M2.7',
          visible: true,
          runnable: true,
          source: 'managed_provider',
        },
      ]));
      window.updateActiveCodexProvider?.(JSON.stringify({
        id: 'minimax-cn',
        name: 'MiniMax CN',
        models: [{ id: 'MiniMax-M2.7', label: 'MiniMax M2.7', reasoningEffort: 'medium' }],
      }));
    });

    fireEvent.click(screen.getByRole('button', { name: /custom-codex-model/i }));
    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();

    const renderedOptions = Array.from((dropdown as HTMLElement).querySelectorAll('.selector-option'));
    expect(within(dropdown as HTMLElement).getByText('custom-codex-model')).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('MiniMax M2.7')).toBeTruthy();
    expect(renderedOptions[0]?.textContent).toContain('custom-codex-model');
  });

  it('refreshes the dropdown model list after codex catalog switching', () => {
    /**
     * 验证目标：
     * 当用户切换 active Codex provider 后，聊天区模型下拉必须立即切换到新 provider 的模型集合，
     * 不能继续残留旧 provider 的模型项，否则用户看到的可选模型与实际请求 provider 会不一致。
     *
     * 断言意图：
     * 1. 切到 provider A 时，只显示 provider A 的模型；
     * 2. 再切到 provider B 后，下拉应改为显示 provider B 的模型，并移除 provider A 的模型。
     */
    const { container, rerender } = render(
      <ButtonArea
        hasInputContent
        selectedModel="provider-a-model"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModelSelect={() => {}}
      />
    );

    act(() => {
      window.updateCodexModelCatalog?.(JSON.stringify([
        {
          key: 'provider-a::provider-a-model',
          providerId: 'provider-a',
          providerName: 'Provider A',
          modelId: 'provider-a-model',
          label: 'Provider A Model',
          visible: true,
          runnable: true,
          source: 'managed_provider',
        },
      ]));
    });

    fireEvent.click(screen.getByRole('button', { name: /Provider A Model/i }));
    let dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('Provider A Model')).toBeTruthy();
    expect(within(dropdown as HTMLElement).queryByText('Provider B Model')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /Provider A Model/i }));
    expect(container.querySelector('.selector-dropdown')).toBeNull();

    rerender(
      <ButtonArea
        hasInputContent
        selectedModel="provider-b-model"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModelSelect={() => {}}
      />
    );

    act(() => {
      window.updateCodexModelCatalog?.(JSON.stringify([
        {
          key: 'provider-b::provider-b-model',
          providerId: 'provider-b',
          providerName: 'Provider B',
          modelId: 'provider-b-model',
          label: 'Provider B Model',
          visible: true,
          runnable: true,
          source: 'managed_provider',
        },
      ]));
    });

    fireEvent.click(screen.getByRole('button', { name: /Provider B Model/i }));
    dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('Provider B Model')).toBeTruthy();
    expect(within(dropdown as HTMLElement).queryByText('Provider A Model')).toBeNull();
  });
});
