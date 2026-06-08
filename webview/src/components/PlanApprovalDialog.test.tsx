import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import PlanApprovalDialog, { type PlanApprovalRequest } from './PlanApprovalDialog';
import { resetLinkifyCapabilities, setLinkifyCapabilities } from '../utils/linkifyCapabilities';

vi.mock('../hooks/useDialogResize', () => ({
  useDialogResize: () => ({
    // PlanApprovalDialog 已切换到新的拖拽尺寸 hook 返回结构，这里保持测试桩与真实接口一致，
    // 只关心渲染与交互，不在该用例里覆盖拖拽行为本身。
    dialogRef: { current: null },
    dialogHeight: null,
    setDialogHeight: vi.fn(),
    handleResizeStart: vi.fn(),
  }),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, fallback?: unknown, values?: Record<string, unknown>) => {
      if (typeof fallback === 'string') {
        return fallback;
      }
      if (typeof fallback === 'object' && fallback !== null && typeof (fallback as { defaultValue?: unknown }).defaultValue === 'string') {
        return (fallback as { defaultValue: string }).defaultValue;
      }
      if (typeof values?.seconds === 'number') {
        return `timeout in ${values.seconds}s`;
      }
      return _key;
    },
    i18n: { language: 'en' },
  }),
}));

describe('PlanApprovalDialog', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    resetLinkifyCapabilities();
    setLinkifyCapabilities({ classNavigationEnabled: true });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('returns selected execution mode when user approves', () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-1', toolName: 'plan', plan: '## plan' }}
        onApprove={onApprove}
        onReject={onReject}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'acceptEdits' }));
    fireEvent.click(screen.getByRole('button', { name: '批准并执行' }));

    expect(onApprove).toHaveBeenCalledWith('req-1', 'acceptEdits');
    expect(onReject).not.toHaveBeenCalled();
  });

  it('uses default mode when approving without changing selection', () => {
    const onApprove = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-default', toolName: 'plan', plan: '## plan' }}
        onApprove={onApprove}
        onReject={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '批准并执行' }));
    expect(onApprove).toHaveBeenCalledWith('req-default', 'default');
  });

  it('rejects immediately when user clicks reject', () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-2', toolName: 'plan', plan: 'demo' }}
        onApprove={onApprove}
        onReject={onReject}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '拒绝' }));
    expect(onReject).toHaveBeenCalledWith('req-2');
    expect(onApprove).not.toHaveBeenCalled();
  });

  it('approves when pressing Enter', () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-enter', toolName: 'plan', plan: 'demo' }}
        onApprove={onApprove}
        onReject={onReject}
      />,
    );

    fireEvent.keyDown(window, { key: 'Enter' });
    expect(onApprove).toHaveBeenCalledWith('req-enter', 'default');
    expect(onReject).not.toHaveBeenCalled();
  });

  it('rejects when pressing Escape', () => {
    const onApprove = vi.fn();
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-escape', toolName: 'plan', plan: 'demo' }}
        onApprove={onApprove}
        onReject={onReject}
      />,
    );

    fireEvent.keyDown(window, { key: 'Escape' });
    expect(onReject).toHaveBeenCalledWith('req-escape');
    expect(onApprove).not.toHaveBeenCalled();
  });

  it('auto-rejects when countdown reaches timeout', () => {
    const onReject = vi.fn();

    render(
      <PlanApprovalDialog
        isOpen
        request={{ requestId: 'req-timeout', toolName: 'plan', plan: 'demo' }}
        onApprove={vi.fn()}
        onReject={onReject}
      />,
    );

    act(() => {
      vi.advanceTimersByTime(300000);
    });

    expect(onReject).toHaveBeenCalledTimes(1);
    expect(onReject).toHaveBeenCalledWith('req-timeout');
  });

  it('reuses MarkdownBlock linkify inside the dialog content', () => {
    const request: PlanApprovalRequest = {
      requestId: 'req-linkify',
      toolName: 'plan',
      plan: [
        'Review src/components/App.tsx',
        '',
        'Check com.github.claudecodegui.handler.file.OpenFileHandler',
        '',
        'Reference https://example.com/docs',
      ].join('\n'),
    };

    render(
      <PlanApprovalDialog
        isOpen
        request={request}
        onApprove={() => {}}
        onReject={() => {}}
      />,
    );

    expect(screen.getByRole('link', { name: 'src/components/App.tsx' })).toBeTruthy();
    expect(
      screen.getByRole('link', {
        name: 'com.github.claudecodegui.handler.file.OpenFileHandler',
      }),
    ).toBeTruthy();
    expect(screen.getByRole('link', { name: 'https://example.com/docs' })).toBeTruthy();
  });
});
