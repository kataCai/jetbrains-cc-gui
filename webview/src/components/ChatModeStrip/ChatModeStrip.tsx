import type { ComposerUsageMode } from '../ChatInputBox/modeViewModel';
import { getTaskStateLabel, type TaskStripState } from '../StatusPanel/types';
import { useTranslation } from 'react-i18next';

interface ChatModeStripProps {
  usageMode: ComposerUsageMode;
  taskState?: TaskStripState | null;
  currentProvider?: 'claude' | 'codex';
}

/**
 * 展示当前会话的产品层模式和任务状态。
 * 这是位于头部的轻量摘要视图，用来补足状态面板被折叠时的信息缺口。
 */
export function ChatModeStrip({ usageMode, taskState, currentProvider = 'claude' }: ChatModeStripProps) {
  const { t } = useTranslation();
  const isCodex = currentProvider === 'codex';
  // Codex 下不再展示 Chat/Plan 产品层模式摘要；没有任务状态时整条 strip 都不显示。
  if (!taskState && (usageMode === 'chat' || isCodex)) {
    return null;
  }
  const stateLabel = taskState ? getTaskStateLabel(taskState, t) : null;

  return (
    <div className="chat-mode-strip" data-state={taskState ?? 'idle'}>
      {!isCodex && (
        <span className="chat-mode-strip__mode">
          {t('chat.modeStripLabel', { defaultValue: 'Mode: {{mode}}', mode: usageMode === 'plan' ? t('chat.planModeLabel', { defaultValue: 'Plan' }) : t('chat.chatMode', { defaultValue: 'Chat' }) })}
        </span>
      )}
      {stateLabel && <span className="chat-mode-strip__state">{stateLabel}</span>}
    </div>
  );
}
