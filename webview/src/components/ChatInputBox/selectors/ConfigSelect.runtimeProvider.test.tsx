import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ConfigSelect } from './ConfigSelect';

vi.mock('antd', () => ({
  Switch: ({ checked, onClick }: { checked?: boolean; onClick?: (checked: boolean, e: { stopPropagation: () => void }) => void }) => (
    <button type="button" aria-pressed={checked} onClick={() => onClick?.(!checked, { stopPropagation: vi.fn() })} />
  ),
}));

vi.mock('../providers/agentProvider', () => ({
  CREATE_NEW_AGENT_ID: '__create__',
  EMPTY_STATE_ID: '__empty__',
  agentProvider: vi.fn(async () => []),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => ({
      'settings.configure': 'Configure',
      'settings.agent.title': 'Agent',
      'settings.basic.streaming.label': 'Streaming',
      'common.thinking': 'Thinking',
    } as Record<string, string>)[key] ?? key,
  }),
}));

describe('ConfigSelect runtime provider cleanup', () => {
  beforeEach(() => {
    (globalThis as typeof globalThis & { window: Window }).window.sendToJava = vi.fn();
  });

  /**
   * 验证聊天区配置菜单已经移除“切换当前供应商”平行入口。
   * 这个断言直接覆盖本次入口收敛目标，确保用户只能在底部模型应用选择中切换模型或供应商。
   */
  it('does not render the runtime provider submenu entry anymore', () => {
    render(<ConfigSelect />);

    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));

    expect(screen.queryByText('Switch provider')).toBeNull();
    expect(screen.getByText('Agent')).toBeTruthy();
    expect(screen.getByText('Streaming')).toBeTruthy();
    expect(screen.getByText('Thinking')).toBeTruthy();
  });

  /**
   * 验证移除平行入口后，原有的流式传输和思考开关仍然正常工作。
   * 这里保留最直接的交互回归，防止菜单裁剪时误伤其它配置项的点击行为。
   */
  it('keeps streaming and thinking toggles functional after removing the provider entry', () => {
    const onStreamingEnabledChange = vi.fn();
    const onToggleThinking = vi.fn();

    render(
      <ConfigSelect
        streamingEnabled={true}
        onStreamingEnabledChange={onStreamingEnabledChange}
        alwaysThinkingEnabled={false}
        onToggleThinking={onToggleThinking}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: /Configure/i }));
    const toggleButtons = screen.getAllByRole('switch');
    fireEvent.click(toggleButtons[0]);
    fireEvent.click(toggleButtons[1]);

    expect(onStreamingEnabledChange).toHaveBeenCalledWith(false);
    expect(onToggleThinking).toHaveBeenCalledWith(true);
  });
});
