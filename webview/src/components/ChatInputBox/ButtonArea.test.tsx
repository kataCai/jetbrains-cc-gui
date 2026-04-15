import { fireEvent, render, screen, within } from '@testing-library/react';
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
});
