import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import './TaskReminderDialog.css';

export type TaskReminderState = 'waiting_confirm' | 'final_error';

/**
 * 后端发给前端的 task reminder 弹窗请求。
 * 当前只承载两类强提醒状态：等待确认与最终错误。
 */
export interface TaskReminderDialogRequest {
  state: TaskReminderState;
  message: string;
  // 这两个字段当前主要用于保留上下文，方便后续扩展为“打开指定会话/请求定位”。
  sessionId?: string;
  requestId?: string;
}

interface TaskReminderDialogProps {
  isOpen: boolean;
  request: TaskReminderDialogRequest | null;
  onOpenSession: (request: TaskReminderDialogRequest) => void;
  onDismiss: (request: TaskReminderDialogRequest) => void;
  onRetry: (request: TaskReminderDialogRequest) => void;
}

/**
 * 任务提醒弹窗。
 * 这个组件只负责展示“需要用户注意”的提醒，不承担状态计算逻辑。
 */
const TaskReminderDialog = ({
  isOpen,
  request,
  onOpenSession,
  onDismiss,
  onRetry,
}: TaskReminderDialogProps) => {
  const { t } = useTranslation();

  useEffect(() => {
    if (!isOpen || !request) return;
    // 与权限弹窗保持一致，Esc 可以快速收起提醒，
    // 避免等待确认类提醒阻塞键盘流。
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onDismiss(request);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onDismiss, request]);

  // 关闭态下直接不渲染 DOM，避免遮罩层影响聊天区交互。
  if (!isOpen || !request) {
    return null;
  }

  // final_error 会额外暴露 retry 操作；waiting_confirm 只允许回到会话处理。
  const isFinalError = request.state === 'final_error';

  return (
    <div className="permission-dialog-overlay task-reminder-dialog-overlay">
      {/* 复用权限弹窗的 overlay 视觉层级，保证提醒总能压在聊天区和设置页之上。 */}
      <div className={`task-reminder-dialog task-reminder-dialog--${request.state}`}>
        <div className="task-reminder-dialog__header">
          <h3 className="task-reminder-dialog__title">
            {isFinalError
              ? t('taskReminder.finalErrorTitle', 'Task requires attention')
              : t('taskReminder.waitingConfirmTitle', 'Task is waiting for confirmation')}
          </h3>
        </div>
        <p className="task-reminder-dialog__message">{request.message}</p>
        <div className="task-reminder-dialog__actions">
          <button
            type="button"
            className="task-reminder-dialog__button task-reminder-dialog__button--primary"
            onClick={() => onOpenSession(request)}
            autoFocus
          >
            {t('taskReminder.openSession', 'Open session')}
          </button>

          {isFinalError ? (
            <>
              {/* final_error 才暴露 retry，waiting_confirm 只提供“去会话里处理”或稍后处理。 */}
              <button
                type="button"
                className="task-reminder-dialog__button task-reminder-dialog__button--secondary"
                onClick={() => onRetry(request)}
              >
                {t('taskReminder.retry', 'Retry')}
              </button>
              <button
                type="button"
                className="task-reminder-dialog__button task-reminder-dialog__button--ghost"
                onClick={() => onDismiss(request)}
              >
                {t('taskReminder.close', 'Close')}
              </button>
            </>
          ) : (
            <button
              type="button"
              className="task-reminder-dialog__button task-reminder-dialog__button--ghost"
              onClick={() => onDismiss(request)}
            >
              {t('taskReminder.later', 'Later')}
            </button>
          )}
        </div>
      </div>
    </div>
  );
};

export default TaskReminderDialog;
