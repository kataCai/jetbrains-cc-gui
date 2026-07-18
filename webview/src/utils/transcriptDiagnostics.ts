import type { ClaudeMessage } from '../types';
import { getMessageKey } from './messageUtils';

const DIAGNOSTIC_CONTENT_PREVIEW_MAX_LENGTH = 96;
const MESSAGE_LIST_VISIBLE_WINDOW_SIZE = 15;

/**
 * 前端 transcript 诊断导出的固定快照类型。
 * 该值用于显式区分“完整 transcript 快照”和外部滚动文本采样，避免后续排查时把两类证据混淆。
 */
export const FULL_TRANSCRIPT_SNAPSHOT_KIND = 'full_transcript_snapshot';

/**
 * 前端 transcript 诊断导出的固定导出类型。
 * 导出的 JSON 文件会携带该字段，便于后续脚本或人工快速识别文件用途。
 */
export const FRONTEND_TRANSCRIPT_DIAGNOSTIC_EXPORT_KIND = 'frontend_transcript_diagnostic';

/**
 * 轻量 transcript dump 中的单条消息摘要。
 * 该结构只保留定位排序、重复、React key 复用所需的最小字段，避免把完整 raw 内容刷入 idea.log。
 */
export interface TranscriptDiagnosticMessageDumpEntry {
  index: number;
  key: string;
  type: ClaudeMessage['type'];
  timestamp: string | null;
  messageIdentityKey: string | null;
  contentPreview: string | null;
}

/**
 * 当前前端完整 transcript 诊断导出的结构。
 * `messages` 保留真实前端 message array，便于在可视滚动文本采样失真时直接核对真实渲染源数据。
 */
export interface FrontendTranscriptDiagnosticSnapshot {
  exportKind: typeof FRONTEND_TRANSCRIPT_DIAGNOSTIC_EXPORT_KIND;
  snapshotKind: typeof FULL_TRANSCRIPT_SNAPSHOT_KIND;
  transcriptSource: 'react_messages_state';
  exportedAt: string;
  provider: string | null;
  sessionId: string | null;
  logicalConversationId: string | null;
  activeSegmentSessionId: string | null;
  messageCount: number;
  messageListWindowInfo: {
    visibleWindowSize: number;
    wouldCollapseEarlierMessages: boolean;
    bypassedForExport: true;
  };
  note: string;
  messages: ClaudeMessage[];
}

/**
 * 提取消息用于日志预览的文本候选。
 * 这里优先读取顶层 `content`，缺失时再回退到 `raw.content` / `raw.message.content` 中的 text block，
 * 以便在 tool_use、历史回放和 sanitize 后的多种消息结构下都能拿到稳定预览。
 *
 * @param message 当前消息对象
 * @return 适合日志摘要的单行文本；若不存在可见文本则返回 null
 */
function buildMessageContentPreview(message: ClaudeMessage): string | null {
  const textCandidates: string[] = [];
  const appendCandidate = (value: unknown) => {
    if (typeof value === 'string' && value.trim().length > 0) {
      textCandidates.push(value);
    }
  };
  const appendArrayTextBlocks = (value: unknown) => {
    if (!Array.isArray(value)) {
      return;
    }
    value.forEach((block) => {
      if (!block || typeof block !== 'object') {
        return;
      }
      const rawBlock = block as Record<string, unknown>;
      if (rawBlock.type === 'text' && typeof rawBlock.text === 'string') {
        textCandidates.push(rawBlock.text);
      }
    });
  };

  appendCandidate(message.content);

  const raw = message.raw;
  if (typeof raw === 'string') {
    appendCandidate(raw);
  } else if (raw && typeof raw === 'object') {
    const rawObject = raw as Record<string, unknown>;
    appendCandidate(rawObject.content);
    appendArrayTextBlocks(rawObject.content);

    const rawMessage = rawObject.message;
    if (rawMessage && typeof rawMessage === 'object' && !Array.isArray(rawMessage)) {
      const nested = rawMessage as Record<string, unknown>;
      appendCandidate(nested.content);
      appendArrayTextBlocks(nested.content);
    }
  }

  const previewSource = textCandidates.find((candidate) => candidate.trim().length > 0);
  if (!previewSource) {
    return null;
  }

  const compact = previewSource.replace(/\s+/g, ' ').trim();
  if (!compact) {
    return null;
  }
  if (compact.length <= DIAGNOSTIC_CONTENT_PREVIEW_MAX_LENGTH) {
    return compact;
  }
  return compact.slice(0, DIAGNOSTIC_CONTENT_PREVIEW_MAX_LENGTH);
}

/**
 * 构建用于 idea.log 的轻量 transcript dump。
 * 该 dump 明确记录每条消息的稳定 key、索引、时间和 messageIdentity，
 * 专门服务于 authoritative replace 后的排序/重复排查，不承担完整归档职责。
 *
 * @param messages 当前待诊断的完整消息数组
 * @return 可直接写入结构化诊断日志的消息摘要数组
 */
export function buildTranscriptDiagnosticMessageDump(
  messages: ClaudeMessage[],
): TranscriptDiagnosticMessageDumpEntry[] {
  return messages.map((message, index) => ({
    index,
    key: getMessageKey(message, index),
    type: message.type,
    timestamp: typeof message.timestamp === 'string' ? message.timestamp : null,
    messageIdentityKey: typeof message.messageIdentity?.key === 'string'
      ? message.messageIdentity.key
      : null,
    contentPreview: buildMessageContentPreview(message),
  }));
}

/**
 * 基于前端当前真实 message array 构建完整 transcript 诊断快照。
 * 导出结果明确声明它来自 React state，而不是 MessageList 的可视窗口，
 * 用于和 `scroll.log` 这类滚动采样证据做区分。
 *
 * @param params 导出上下文，包括当前会话锚点与完整消息数组
 * @return 可直接序列化为 JSON 的完整 transcript 诊断对象
 */
export function buildFrontendTranscriptDiagnosticSnapshot(params: {
  messages: ClaudeMessage[];
  exportedAt: string;
  provider: string | null;
  sessionId: string | null;
  logicalConversationId: string | null;
  activeSegmentSessionId: string | null;
}): FrontendTranscriptDiagnosticSnapshot {
  const {
    messages,
    exportedAt,
    provider,
    sessionId,
    logicalConversationId,
    activeSegmentSessionId,
  } = params;
  return {
    exportKind: FRONTEND_TRANSCRIPT_DIAGNOSTIC_EXPORT_KIND,
    snapshotKind: FULL_TRANSCRIPT_SNAPSHOT_KIND,
    transcriptSource: 'react_messages_state',
    exportedAt,
    provider,
    sessionId,
    logicalConversationId,
    activeSegmentSessionId,
    messageCount: messages.length,
    messageListWindowInfo: {
      visibleWindowSize: MESSAGE_LIST_VISIBLE_WINDOW_SIZE,
      wouldCollapseEarlierMessages: messages.length > MESSAGE_LIST_VISIBLE_WINDOW_SIZE,
      bypassedForExport: true,
    },
    note: 'This snapshot is exported from the frontend React message state and is not limited by the MessageList visible window.',
    messages,
  };
}
