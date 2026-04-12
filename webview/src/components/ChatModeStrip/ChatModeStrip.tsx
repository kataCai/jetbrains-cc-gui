import type { ComposerUsageMode } from '../ChatInputBox/modeViewModel';
import { getTaskStateLabel, type TaskStripState } from '../StatusPanel/types';
import { useTranslation } from 'react-i18next';

interface ChatModeStripProps {
  usageMode: ComposerUsageMode;
  taskState?: TaskStripState | null;
}

/**
 * 展示当前会话的产品层模式和任务状态。
 * 这是位于头部的轻量摘要视图，用来补足状态面板被折叠时的信息缺口。
 */
export function ChatModeStrip({ usageMode, taskState }: ChatModeStripProps) {
  const { t } = useTranslation();
  // 普通 chat 且没有任务状态时不占空间，避免头部始终出现空条带。
  if (!taskState && usageMode === 'chat') {
    return null;
  }
  const stateLabel = taskState ? getTaskStateLabel(taskState, t) : null;

  return (
    <div className="chat-mode-strip" data-state={taskState ?? 'idle'}>
      <span className="chat-mode-strip__mode">
        {t('chat.modeStripLabel', { defaultValue: 'Mode: {{mode}}', mode: usageMode === 'plan' ? t('chat.planModeLabel', { defaultValue: 'Plan' }) : t('chat.chatMode', { defaultValue: 'Chat' }) })}
      </span>
      {stateLabel && <span className="chat-mode-strip__state">{stateLabel}</span>}
    </div>
  );
}
