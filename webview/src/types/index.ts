export type ClaudeRole = 'user' | 'assistant' | 'error' | 'task_notification' | 'notification' | 'compact_notification' | string;

export type ToolInput = Record<string, unknown>;

export interface CompactNotificationItem {
  type: 'stdout';
  text: string;
}

/**
 * Metadata for compact summary messages.
 * Contains information about the compaction operation.
 */
export interface CompactSummaryMetadata {
  messagesSummarized?: number;
  direction?: 'up_to' | 'from';
  userContext?: string;
}

/**
 * Type guard for CompactSummaryMetadata.
 */
export function isCompactSummaryMetadata(obj: unknown): obj is CompactSummaryMetadata {
  if (!obj || typeof obj !== 'object') return false;
  const m = obj as Record<string, unknown>;
  if (m.messagesSummarized !== undefined && typeof m.messagesSummarized !== 'number') return false;
  if (m.direction !== undefined && m.direction !== 'up_to' && m.direction !== 'from') return false;
  if (m.userContext !== undefined && typeof m.userContext !== 'string') return false;
  return true;
}

export type ClaudeContentBlock =
  | { type: 'text'; text?: string }
  | { type: 'thinking'; thinking?: string; text?: string }
  | { type: 'tool_use'; id?: string; name?: string; input?: ToolInput }
  | { type: 'image'; src?: string; mediaType?: string; alt?: string }
  | { type: 'image_missing'; fileName?: string; mediaType?: string; originalPath?: string; reason?: string }
  | { type: 'attachment'; fileName?: string; mediaType?: string }
  | { type: 'task_notification'; icon: string; summary: string; status: string }
  | { type: 'compact_notification'; headerText: string; items: CompactNotificationItem[] }
  | { type: 'compact_summary'; title: string; content: string; metadata?: CompactSummaryMetadata };

export interface ToolResultBlock {
  type: 'tool_result';
  tool_use_id?: string;
  content?: string | Array<{ type?: string; text?: string }>;
  is_error?: boolean;
  [key: string]: unknown;
}

export type ClaudeContentOrResultBlock = ClaudeContentBlock | ToolResultBlock;

export interface ClaudeRawMessage {
  content?: string | ClaudeContentOrResultBlock[];
  message?: { content?: string | ClaudeContentOrResultBlock[] };
  type?: string;
  /** Origin indicates message source - used to filter synthetic messages */
  origin?: { kind: string };
  isMeta?: boolean;
  toolUseResult?: unknown;
  isCompactSummary?: boolean;
  [key: string]: unknown;
}

/**
 * 描述一条前端消息在跨快照、跨分段恢复场景下的稳定语义身份。
 * 该字段由后端在历史恢复链路中写入，前端优先据此判断两条消息是否属于同一条逻辑消息，
 * 避免 continued prefix merge、optimistic reconcile 和 authoritative restore 仅依赖 timestamp。
 */
export interface MessageIdentity {
  key: string;
  role?: string;
  sourceId?: string;
  segmentSessionId?: string;
  segmentIndex?: number;
  segmentLocalIndex?: number;
  logicalOrder?: number;
}

/**
 * 标记当前 `prepareHistoryRestoreSnapshot -> updateMessages` 对应的是哪一类历史回放。
 * 其中 `runtime_continue_authoritative` 表示后端已经生成完整 logical conversation 快照，
 * 前端必须直接用该快照接管当前消息列表，而不能再继续套用 continued prefix merge。
 */
export type HistoryRestoreKind =
  | 'single_session'
  | 'logical_conversation'
  | 'runtime_continue_authoritative';

/** Represents a single message in the chat conversation. */
export interface ClaudeMessage {
  type: ClaudeRole;
  content?: string;
  raw?: ClaudeRawMessage | string;
  timestamp?: string;
  isStreaming?: boolean;
  isOptimistic?: boolean;
  /** 前端根据一次完整 assistant 响应耗时补写的展示字段，后端快照当前不会直接返回。 */
  durationMs?: number;
  /**
   * 仅运行时使用的数值 turnId，用于隔离不同 streaming 回合的 assistant 消息。
   * 前端会在 streaming 期间写入该字段；不同 `__turnId` 的消息不能互相合并。
   * 从历史 JSONL 加载出来的消息通常不带该字段。
   */
  __turnId?: number;
  /** 后端提供的稳定语义 identity，供前端跨快照去重与 continued 过渡接管使用。 */
  messageIdentity?: MessageIdentity;
  /** 跨分段聚合后的稳定顺序号；authoritative logical snapshot 渲染时应以该顺序为准。 */
  logicalOrder?: number;
  /** 该消息所属的 continued 物理分段索引。 */
  segmentIndex?: number;
  /** 该消息所属的 continued 物理分段 sessionId。 */
  segmentSessionId?: string;
  /** 该消息在所属物理分段内的稳定局部顺序号。 */
  segmentLocalIndex?: number;
  [key: string]: unknown;
}

export interface TodoItem {
  id?: string;
  content: string;
  status: 'pending' | 'in_progress' | 'completed';
}

export interface HistorySessionSummary {
  sessionId: string;
  logicalConversationId?: string;
  activeSegmentSessionId?: string;
  parentSegmentSessionId?: string;
  continuationSourceSessionId?: string;
  segmentCount?: number;
  continuationPending?: boolean;
  title: string;
  messageCount: number;
  lastTimestamp?: string;
  isFavorited?: boolean;
  favoritedAt?: number;
  provider?: string; // 'claude' or 'codex'
  runtimeFamily?: 'claude' | 'codex';
  fileSize?: number;
}

export interface HistoryData {
  success: boolean;
  error?: string;
  sessions?: HistorySessionSummary[];
  total?: number;
  favorites?: Record<string, { favoritedAt: number }>;
}

// File changes types
export type { FileChangeStatus, EditOperation, FileChangeSummary } from './fileChanges';

// Subagent types
export type { SubagentStatus, SubagentInfo, SubagentHistoryResponse } from './subagent';
