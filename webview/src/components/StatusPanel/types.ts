import type { TFunction } from 'i18next';
import type {
  FileChangeSummary,
  SubagentHistoryResponse,
  SubagentInfo,
  TodoItem,
} from '../../types';
import type { ComposerUsageMode } from '../ChatInputBox/modeViewModel';

export type TabType = 'todo' | 'subagent' | 'files';
export type TaskStripState =
  | 'running'
  | 'waiting_confirm'
  | 'retrying'
  | 'recovered'
  | 'completed'
  | 'final_error';

/**
 * 顶部 mode strip 与 StatusPanel 共用的任务状态集合。
 * 这里只保留产品层明确展示的状态，避免把底层内部状态无节制暴露到 UI。
 */
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

/**
 * 获取任务状态在 UI 中的展示文本。
 * 顶部 strip 与 StatusPanel 都走同一份翻译入口，避免并轨后文案漂移。
 *
 * @param taskState 当前任务状态
 * @param t i18n 翻译函数
 * @return 本地化后的状态文案
 */
export function getTaskStateLabel(taskState: TaskStripState, t: TFunction): string {
  return t(`chat.taskState.${taskState}`, {
    defaultValue: TASK_STATE_FALLBACK_LABELS[taskState],
  });
}

export interface StatusPanelProps {
  todos: TodoItem[];
  fileChanges: FileChangeSummary[];
  subagents: SubagentInfo[];
  subagentHistories?: Record<string, SubagentHistoryResponse>;
  currentSessionId?: string | null;
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
