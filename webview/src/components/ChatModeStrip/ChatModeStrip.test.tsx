import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ChatModeStrip } from './ChatModeStrip';

vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>();
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, options?: Record<string, unknown> | string) => {
        if (typeof options === 'string') return options;
        if (key === 'chat.modeStripLabel') {
          return options?.mode ? `Mode: ${String(options.mode)}` : 'Mode';
        }
        if (key === 'chat.planRequiresClaudeHint') {
          return 'Plan requires Claude';
        }
        return typeof options?.defaultValue === 'string' ? options.defaultValue : key;
      },
    }),
  };
});

describe('ChatModeStrip', () => {
  it('does not render in chat mode without task state', () => {
    // 普通 chat 且没有状态时不应占据头部空间。
    const { container } = render(<ChatModeStrip usageMode="chat" taskState={null} />);
    expect(container.firstChild).toBe(null);
  });

  it('renders mode and task state when plan is active', () => {
    // plan 模式下即使只是 running，也应该明确展示模式与状态。
    render(<ChatModeStrip usageMode="plan" taskState="running" />);

    expect(screen.getByText('Mode: Plan')).toBeTruthy();
    expect(screen.getByText('Running')).toBeTruthy();
  });

  it('renders friendly task labels instead of internal state values', () => {
    // UI 应展示用户友好的状态文案，而不是底层枚举值。
    render(<ChatModeStrip usageMode="chat" taskState="waiting_confirm" />);

    expect(screen.getByText('Waiting for confirmation')).toBeTruthy();
  });

  it('does not render codex mode strip without task state', () => {
    const { container } = render(<ChatModeStrip usageMode="chat" taskState={null} currentProvider="codex" />);

    expect(container.firstChild).toBe(null);
  });
});
