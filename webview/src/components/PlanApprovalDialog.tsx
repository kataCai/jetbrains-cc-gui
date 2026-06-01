import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useDialogCountdownTimeout } from '../hooks/useDialogCountdownTimeout';
import { useDialogResize } from '../hooks/useDialogResize';
import { sendBridgeEvent } from '../utils/bridge';
import { formatCountdown } from '../utils/helpers';
import { isEditableEventTarget } from '../utils/isEditableEventTarget';
import { DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS } from '../utils/permissionDialogTimeout';
import MarkdownBlock from './MarkdownBlock';
import './PlanApprovalDialog.css';

export interface AllowedPrompt {
  tool: string;
  prompt: string;
}

export interface PlanApprovalRequest {
  requestId: string;
  toolName: string;
  plan?: string;
  allowedPrompts?: AllowedPrompt[];
  timestamp?: string;
}

interface PlanApprovalDialogProps {
  isOpen: boolean;
  request: PlanApprovalRequest | null;
  onApprove: (requestId: string, targetMode: string) => void;
  onReject: (requestId: string) => void;
  timeoutSeconds?: number;
}

const EXECUTION_MODES = [
  { id: 'default', labelKey: 'modes.default.label', descriptionKey: 'modes.default.description' },
  { id: 'acceptEdits', labelKey: 'modes.acceptEdits.label', descriptionKey: 'modes.acceptEdits.description' },
  { id: 'bypassPermissions', labelKey: 'modes.bypassPermissions.label', descriptionKey: 'modes.bypassPermissions.description' },
];

/**
 * 展示 Claude Plan 模式审批弹窗。
 *
 * 该组件负责把计划内容、执行模式、超时倒计时和键盘快捷键统一到一个弹窗中。
 * 并轨时需要同时保留本地主线的 bridge 可见性事件和上游的输入框焦点保护：
 * 前者用于后端感知审批弹窗是否打开，后者避免用户在输入框中按 Enter/Esc 时误触发审批。
 *
 * @param props 计划审批弹窗的打开状态、审批请求、回调函数和可选超时时间。
 * @return React 弹窗节点；未打开或无请求时返回 null。
 */
const PlanApprovalDialog = ({
  isOpen,
  request,
  onApprove,
  onReject,
  timeoutSeconds = DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS,
}: PlanApprovalDialogProps) => {
  const { t } = useTranslation();
  const [selectedMode, setSelectedMode] = useState('default');
  const [isCollapsed, setIsCollapsed] = useState(false);
  const { dialogRef, dialogHeight, setDialogHeight, handleResizeStart } = useDialogResize({ minHeight: 200 });

  const handleTimeout = useCallback(() => {
    if (request) {
      onReject(request.requestId);
    }
  }, [request, onReject]);

  const { remainingSeconds, isTimeWarning, markSubmitted } = useDialogCountdownTimeout({
    isOpen,
    requestKey: request?.requestId,
    timeoutSeconds,
    onTimeout: handleTimeout,
  });

  const handleApprove = useCallback(() => {
    if (!request || !markSubmitted()) return;
    onApprove(request.requestId, selectedMode);
  }, [request, selectedMode, markSubmitted, onApprove]);

  const handleReject = useCallback(() => {
    if (!request || !markSubmitted()) return;
    onReject(request.requestId);
  }, [request, markSubmitted, onReject]);

  useEffect(() => {
    if (isOpen && request) {
      setSelectedMode('default');
      setIsCollapsed(false);
      // 测试 mock 可能不提供完整的 dialog resize API，真实运行时才会执行重置。
      if (typeof setDialogHeight === 'function') {
        setDialogHeight(null);
      }
    }
  }, [isOpen, request?.requestId, setDialogHeight]);

  useEffect(() => {
    if (!isOpen || !request) return undefined;

    sendBridgeEvent('plan_approval_dialog_visibility', JSON.stringify({
      requestId: request.requestId,
      visible: true,
    }));

    return () => {
      sendBridgeEvent('plan_approval_dialog_visibility', JSON.stringify({
        requestId: request.requestId,
        visible: false,
      }));
    };
  }, [isOpen, request?.requestId]);

  useEffect(() => {
    if (!isOpen || !request) return undefined;

    const handleKeyDown = (e: KeyboardEvent) => {
      // 输入控件内的按键应由控件自身处理，避免误触发审批动作。
      if (isEditableEventTarget(e.target)) {
        return;
      }

      if (e.key === 'Escape') {
        handleReject();
      } else if (e.key === 'Enter') {
        handleApprove();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, request, handleApprove, handleReject]);

  if (!isOpen || !request) {
    return null;
  }

  const handleModeChange = (modeId: string) => {
    setSelectedMode(modeId);
  };

  if (isCollapsed) {
    return (
      <div className="permission-dialog-overlay collapsed-mode">
        <div className="plan-approval-dialog-collapsed">
          <div className="collapsed-header">
            <span className="collapsed-title">
              {t('planApproval.title', '计划已准备就绪')}
            </span>
            <span className={`countdown-timer ${isTimeWarning ? 'warning' : ''}`}>
              <span className="codicon codicon-clock" />
              <span className="countdown-time">{formatCountdown(remainingSeconds)}</span>
            </span>
          </div>
          <button
            className="expand-button"
            onClick={() => setIsCollapsed(false)}
            title={t('common.expand', '展开')}
          >
            <span className="codicon codicon-chevron-up" />
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className={`permission-dialog-overlay ${isTimeWarning ? 'warning-mode' : ''}`}>
      <div
        ref={dialogRef}
        className="plan-approval-dialog"
        style={dialogHeight ? { height: dialogHeight, maxHeight: '90vh' } : undefined}
      >
        <div className="plan-approval-resize-handle" onPointerDown={handleResizeStart} />

        {isTimeWarning && (
          <div className="timeout-warning-banner">
            <span className="codicon codicon-warning" />
            <span>
              {t('planApproval.timeoutWarning', '请尽快做出选择，对话框将在 {{seconds}} 秒后自动关闭', {
                seconds: remainingSeconds,
              })}
            </span>
          </div>
        )}

        <div className="plan-approval-dialog-header">
          <div className="header-left">
            <h3 className="plan-approval-dialog-title">
              {t('planApproval.title', '计划已准备就绪')}
            </h3>
            <p className="plan-approval-dialog-subtitle">
              {t('planApproval.subtitle', 'Claude 已完成规划，准备执行。')}
            </p>
          </div>
          <div className="header-right">
            <span className={`countdown-timer ${isTimeWarning ? 'warning' : ''}`}>
              <span className="codicon codicon-clock" />
              <span className="countdown-time">{formatCountdown(remainingSeconds)}</span>
            </span>
            <button
              className="collapse-button"
              onClick={() => setIsCollapsed(true)}
              title={t('common.collapse', '收起')}
            >
              <span className="codicon codicon-chevron-down" />
            </button>
          </div>
        </div>

        {request.plan && (
          <div className="plan-approval-content">
            <MarkdownBlock content={request.plan} isStreaming={false} />
          </div>
        )}

        <div className="plan-approval-mode-section">
          <h4 className="mode-header">
            {t('planApproval.executionMode', '执行模式')}
          </h4>
          <p className="mode-description">
            {t('planApproval.executionModeDescription', '选择 Claude 执行计划的方式：')}
          </p>
          <div className="mode-options">
            {EXECUTION_MODES.map((mode) => (
              <button
                key={mode.id}
                className={`mode-option ${selectedMode === mode.id ? 'selected' : ''}`}
                onClick={() => handleModeChange(mode.id)}
              >
                <div className="mode-radio">
                  <span className={`codicon codicon-${selectedMode === mode.id ? 'circle-filled' : 'circle-outline'}`} />
                </div>
                <div className="mode-content">
                  <div className="mode-label">{t(mode.labelKey, mode.id)}</div>
                  <div className="mode-option-description">{t(mode.descriptionKey, '')}</div>
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="plan-approval-dialog-actions">
          <button className="action-button secondary" onClick={handleReject}>
            {t('planApproval.reject', '拒绝')}
          </button>
          <div className="action-buttons-right">
            <button className="action-button primary" onClick={handleApprove}>
              {t('planApproval.approve', '批准并执行')}
            </button>
          </div>
        </div>

        <div className="plan-approval-hints">
          <span className="hint">
            <kbd>Enter</kbd> {t('planApproval.toApprove', '批准')}
          </span>
          <span className="hint">
            <kbd>Esc</kbd> {t('planApproval.toReject', '拒绝')}
          </span>
        </div>
      </div>
    </div>
  );
};

export default PlanApprovalDialog;
