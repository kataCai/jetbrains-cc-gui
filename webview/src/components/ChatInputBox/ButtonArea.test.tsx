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
      t: (key: string, options?: { defaultValue?: string } | string) => {
        if (translatedText[key]) return translatedText[key];
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

  it('uses active Codex provider models before built-in models', () => {
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
      window.updateActiveCodexProvider?.(JSON.stringify({
        id: 'minimax-cn',
        name: 'MiniMax CN',
        models: [{ id: 'MiniMax-M2.7', label: 'MiniMax M2.7', reasoningEffort: 'medium' }],
      }));
    });

    fireEvent.click(screen.getByRole('button', { name: /MiniMax/i }));
    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('MiniMax M2.7')).toBeTruthy();
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

  it('merges plugin-level custom Codex models before active provider models', () => {
    /**
     * 验证目标：
     * 插件级自定义模型已经迁移到 localStorage 统一管理后，即使当前激活的是带有 models 的托管 provider，
     * 聊天区模型下拉也必须把这些自定义模型显示出来，并保持它们排在 provider 模型前面。
     *
     * 前置条件：
     * 1. localStorage 中已有一个 Codex 自定义模型；
     * 2. 当前激活的 Codex provider 也返回了一组 provider-owned models。
     *
     * 断言意图：
     * 1. 下拉列表中同时能看到自定义模型和 provider 模型；
     * 2. 自定义模型位置在 provider 模型之前，避免设置页新增模型后聊天区“看不到”的回归。
     */
    localStorage.setItem('codex-custom-models', JSON.stringify([
      { id: 'custom-codex-model', label: 'Custom Codex Model' },
    ]));

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
      window.updateActiveCodexProvider?.(JSON.stringify({
        id: 'minimax-cn',
        name: 'MiniMax CN',
        models: [{ id: 'MiniMax-M2.7', label: 'MiniMax M2.7', reasoningEffort: 'medium' }],
      }));
    });

    fireEvent.click(screen.getByRole('button', { name: /Custom Codex Model/i }));
    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();

    const renderedOptions = Array.from((dropdown as HTMLElement).querySelectorAll('.selector-option'));
    expect(within(dropdown as HTMLElement).getByText('Custom Codex Model')).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('MiniMax M2.7')).toBeTruthy();
    expect(renderedOptions[0]?.textContent).toContain('Custom Codex Model');
  });

  it('refreshes the dropdown model list after active Codex provider switching', () => {
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
      window.updateActiveCodexProvider?.(JSON.stringify({
        id: 'provider-a',
        name: 'Provider A',
        models: [{ id: 'provider-a-model', label: 'Provider A Model' }],
      }));
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
      window.updateActiveCodexProvider?.(JSON.stringify({
        id: 'provider-b',
        name: 'Provider B',
        models: [{ id: 'provider-b-model', label: 'Provider B Model' }],
      }));
    });

    fireEvent.click(screen.getByRole('button', { name: /Provider B Model/i }));
    dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('Provider B Model')).toBeTruthy();
    expect(within(dropdown as HTMLElement).queryByText('Provider A Model')).toBeNull();
  });
});
