import type { ComponentProps } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import EnvironmentTab from './EnvironmentTab';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

/**
 * 构造 EnvironmentTab 的完整默认 props。
 * 这里显式覆盖本轮新增的 Claude CLI path 输入能力，确保测试能覆盖保存按钮、
 * 输入联动以及 loading 状态，不依赖更上层 settings 容器的集成行为。
 *
 * @param overrides 需要局部覆盖的属性
 * @return 实际传入组件的 props，便于对回调进行断言
 */
function renderEnvironmentTab(overrides: Partial<ComponentProps<typeof EnvironmentTab>> = {}) {
  const props = {
    nodePath: '/usr/local/bin/node',
    onNodePathChange: vi.fn(),
    onSaveNodePath: vi.fn(),
    savingNodePath: false,
    nodeVersion: 'v20.0.0',
    minNodeVersion: 18,
    claudeCliPath: '/usr/local/bin/claude',
    onClaudeCliPathChange: vi.fn(),
    onSaveClaudeCliPath: vi.fn(),
    savingClaudeCliPath: false,
    workingDirectory: '/workspace/project',
    onWorkingDirectoryChange: vi.fn(),
    onSaveWorkingDirectory: vi.fn(),
    savingWorkingDirectory: false,
    ...overrides,
  };

  render(<EnvironmentTab {...props} />);
  return props;
}

describe('EnvironmentTab Claude CLI path section', () => {
  it('renders Claude CLI path input and hint text', () => {
    // 验证设置页环境分组已经暴露 Claude CLI path 能力，避免只有底层通信接线而没有用户入口。
    renderEnvironmentTab();

    expect(screen.getByDisplayValue('/usr/local/bin/claude')).toBeTruthy();
    expect(screen.getByText('settings.basic.claudeCliPath.label')).toBeTruthy();
    expect(screen.getByText('settings.basic.claudeCliPath.hint')).toBeTruthy();
  });

  it('propagates Claude CLI path edits and save action', () => {
    // 验证输入变化和保存按钮分别调用对应回调，确保前端状态与 Java 保存入口闭环联通。
    const onClaudeCliPathChange = vi.fn();
    const onSaveClaudeCliPath = vi.fn();
    renderEnvironmentTab({ onClaudeCliPathChange, onSaveClaudeCliPath });

    const input = screen.getByDisplayValue('/usr/local/bin/claude');
    fireEvent.change(input, { target: { value: '/opt/claude/bin/claude' } });

    expect(onClaudeCliPathChange).toHaveBeenCalledWith('/opt/claude/bin/claude');

    const saveButtons = screen.getAllByRole('button', { name: 'common.save' });
    fireEvent.click(saveButtons[1]);

    expect(onSaveClaudeCliPath).toHaveBeenCalledTimes(1);
  });

  it('disables Claude CLI save button while saving', () => {
    // 验证保存中的按钮禁用状态，避免用户重复点击导致并发保存或错误提示闪烁。
    renderEnvironmentTab({ savingClaudeCliPath: true });

    const saveButtons = screen.getAllByRole('button', { name: 'common.save' });
    expect(saveButtons[1].hasAttribute('disabled')).toBe(true);
  });

  /**
   * 验证环境页会渲染 Codex 历史图片缓存配置入口，并暴露浏览与恢复默认操作。
   * 这样用户才能配置图片缓存目录与治理参数，而不是只能依赖后端默认值。
   */
  it('renders codex history image cache controls', () => {
    const onBrowseCodexHistoryImageCacheDir = vi.fn();
    const onResetCodexHistoryImageCacheDir = vi.fn();
    renderEnvironmentTab({
      codexHistoryImageCacheDir: '/tmp/codex-history-images',
      codexHistoryImageCacheResolvedDir: '/tmp/codex-history-images',
      codexHistoryImageCacheRetentionDays: 45,
      codexHistoryImageCacheMaxSizeMb: 2048,
      onBrowseCodexHistoryImageCacheDir,
      onResetCodexHistoryImageCacheDir,
    });

    expect(screen.getByDisplayValue('/tmp/codex-history-images')).toBeTruthy();
    expect(screen.getByDisplayValue('45')).toBeTruthy();
    expect(screen.getByDisplayValue('2048')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'common.browse' }));
    fireEvent.click(screen.getByRole('button', { name: 'common.reset' }));

    expect(onBrowseCodexHistoryImageCacheDir).toHaveBeenCalledTimes(1);
    expect(onResetCodexHistoryImageCacheDir).toHaveBeenCalledTimes(1);
  });
});
