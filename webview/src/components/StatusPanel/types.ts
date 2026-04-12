import type { TodoItem, FileChangeSummary, SubagentInfo } from '../../types';
import type { ComposerUsageMode } from '../ChatInputBox/modeViewModel';
import type { TFunction } from 'i18next';

export type TabType = 'todo' | 'subagent' | 'files';
export type TaskStripState = 'running' | 'waiting_confirm' | 'retrying' | 'recovered' | 'completed' | 'final_error';
// 顶部 mode strip 和状态面板只展示当前产品层真正关心的任务状态，
// 不直接暴露后端所有内部状态，避免 UI 文案和色彩体系被无限扩张。
export const KNOWN_TASK_STATES: ReadonlySet<TaskStripState> = new Set([
  'running',
  'waiting_confirm',
  'retrying',
  'recovered',
  'completed',
  'final_error',
]);

const TASK_STATE_FALLBACK_LABELS: Record<TaskStripState, string> = {
  running: 'Running',
  waiting_confirm: 'Waiting for confirmation',
  retrying: 'Retrying',
  recovered: 'Recovered',
  completed: 'Completed',
  final_error: 'Needs attention',
};

export function getTaskStateLabel(taskState: TaskStripState, t: TFunction): string {
  // 使用统一翻译入口，确保顶部 strip、状态面板和后续其他入口看到同一套状态文案。
  return t(`chat.taskState.${taskState}`, {
    defaultValue: TASK_STATE_FALLBACK_LABELS[taskState],
  });
}

export interface StatusPanelProps {
  todos: TodoItem[];
  fileChanges: FileChangeSummary[];
  subagents: SubagentInfo[];
  /** Whether the panel is expanded */
  expanded?: boolean;
  /** Whether the conversation is currently streaming (active) */
  isStreaming?: boolean;
  /** Callback when a file is successfully undone */
  onUndoFile?: (filePath: string) => void;
  /** Callback when all files are successfully discarded */
  onDiscardAll?: () => void;
  /** Callback when user clicks Keep All (accept changes as new baseline) */
  onKeepAll?: () => void;
  /** Product-layer composer usage mode (chat / plan) */
  usageMode?: ComposerUsageMode;
  /** Task state used by top mode strip and status panel mode hint */
  taskState?: TaskStripState | null;
}

export const statusClassMap: Record<TodoItem['status'], string> = {
  pending: 'status-pending',
  in_progress: 'status-in-progress',
  completed: 'status-completed',
};

export const statusIconMap: Record<TodoItem['status'], string> = {
  pending: 'codicon-circle-outline',
  in_progress: 'codicon-loading',
  completed: 'codicon-check',
};

export const subagentStatusIconMap: Record<SubagentInfo['status'], string> = {
  running: 'codicon-loading',
  completed: 'codicon-check',
  error: 'codicon-error',
};
