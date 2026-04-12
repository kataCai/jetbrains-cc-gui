import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PlanApprovalDialog from './PlanApprovalDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, fallback?: any, values?: Record<string, unknown>) => {
      if (typeof fallback === 'string') {
        return fallback;
      }
      if (typeof fallback === 'object' && fallback !== null && typeof fallback.defaultValue === 'string') {
        return fallback.defaultValue;
      }
      if (typeof values?.seconds === 'number') {
        return `timeout in ${values.seconds}s`;
      }
      return _key;
    },
  }),
}));

vi.mock('./MarkdownBlock', () => ({
  default: ({ content }: { content: string }) => <div data-testid="plan-content">{content}</div>,
}));

vi.mock('../hooks/useDialogResize', () => ({
  useDialogResize: () => ({
    dialogRef: { current: null },
    dialogHeight: null,
    setDialogHeight: vi.fn(),
    handleResizeStart: vi.fn(),
  }),
}));

describe('PlanApprovalDialog', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns selected execution mode when user approves', () => {
    // 用户主动改选执行模式后，批准回调必须带上最新 target mode。
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-1', toolName: 'plan', plan: '## plan' }}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: 'acceptEdits' }));
    fireEvent.click(screen.getByRole('button', { name: '批准并执行' }));

    expect(onApprove).toHaveBeenCalledWith('req-1', 'acceptEdits');
    expect(onReject).not.toHaveBeenCalled();
  });

  it('uses default mode when approving without changing selection', () => {
    // 不改选时应走默认执行模式，避免回调里出现 undefined。
    const onApprove = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-default', toolName: 'plan', plan: '## plan' }}
        onApprove={onApprove}
        onReject={vi.fn()}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: '批准并执行' }));
    expect(onApprove).toHaveBeenCalledWith('req-default', 'default');
  });

  it('rejects immediately when user clicks reject', () => {
    // 点拒绝时只应触发 reject，不应误触 approve。
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-2', toolName: 'plan', plan: 'demo' }}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));
    expect(onReject).toHaveBeenCalledWith('req-2');
    expect(onApprove).not.toHaveBeenCalled();
  });

  it('approves when pressing Enter', () => {
    // Enter 是批准快捷键，便于快速确认。
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-enter', toolName: 'plan', plan: 'demo' }}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    fireEvent.keyDown(window, { key: 'Enter' });
    expect(onApprove).toHaveBeenCalledWith('req-enter', 'default');
    expect(onReject).not.toHaveBeenCalled();
  });

  it('rejects when pressing Escape', () => {
    // Escape 是拒绝/关闭快捷键，需和按钮行为保持一致。
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-escape', toolName: 'plan', plan: 'demo' }}
        onApprove={onApprove}
        onReject={onReject}
      />
    );

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onReject).toHaveBeenCalledWith('req-escape');
    expect(onApprove).not.toHaveBeenCalled();
  });

  it('auto-rejects when countdown reaches timeout', () => {
    // 倒计时结束后必须自动拒绝，避免审批请求无限挂起。
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-timeout', toolName: 'plan', plan: 'demo' }}
        onApprove={vi.fn()}
        onReject={onReject}
      />
    );

    act(() => {
      vi.advanceTimersByTime(300000);
    });

    expect(onReject).toHaveBeenCalledTimes(1);
    expect(onReject).toHaveBeenCalledWith('req-timeout');
  });
});
