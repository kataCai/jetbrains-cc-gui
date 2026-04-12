import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { useDialogManagement } from './useDialogManagement';
import { sendBridgeEvent } from '../utils/bridge';

vi.mock('../utils/bridge', () => ({
  sendBridgeEvent: vi.fn(),
}));

describe('useDialogManagement', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('sends default target mode when plan approval is rejected', () => {
    // 拒绝时也要带上 default targetMode，便于后端统一解析响应结构。
    const { result } = renderHook(() => useDialogManagement({ t: ((k: string) => k) as any }));

    act(() => {
      result.current.openPlanApprovalDialog({ requestId: 'req-1', toolName: 'plan', plan: 'demo' });
      result.current.handlePlanApprovalReject('req-1');
    });

    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'plan_approval_response',
      JSON.stringify({ requestId: 'req-1', approved: false, targetMode: 'default' })
    );
    expect(result.current.planApprovalDialogOpen).toBe(false);
    expect(result.current.currentPlanApprovalRequest).toBeNull();
  });

  it('sends selected target mode when plan approval is approved', () => {
    // 批准时应把用户选中的 targetMode 一并发回后端。
    const { result } = renderHook(() => useDialogManagement({ t: ((k: string) => k) as any }));

    act(() => {
      result.current.openPlanApprovalDialog({ requestId: 'req-2', toolName: 'plan', plan: 'demo' });
      result.current.handlePlanApprovalApprove('req-2', 'acceptEdits');
    });

    expect(sendBridgeEvent).toHaveBeenCalledWith(
      'plan_approval_response',
      JSON.stringify({ requestId: 'req-2', approved: true, targetMode: 'acceptEdits' })
    );
    expect(result.current.planApprovalDialogOpen).toBe(false);
    expect(result.current.currentPlanApprovalRequest).toBeNull();
  });

  it('queues next plan approval request after rejecting current one', () => {
    // 多个审批请求到来时，hook 需要维持顺序队列，当前一个关闭后再弹出下一个。
    const { result } = renderHook(() => useDialogManagement({ t: ((k: string) => k) as any }));

    act(() => {
      result.current.openPlanApprovalDialog({ requestId: 'req-queue-1', toolName: 'plan', plan: 'first' });
      result.current.openPlanApprovalDialog({ requestId: 'req-queue-2', toolName: 'plan', plan: 'second' });
    });

    expect(result.current.currentPlanApprovalRequest?.requestId).toBe('req-queue-1');

    act(() => {
      result.current.handlePlanApprovalReject('req-queue-1');
    });

    expect(result.current.planApprovalDialogOpen).toBe(true);
    expect(result.current.currentPlanApprovalRequest?.requestId).toBe('req-queue-2');
  });
});
