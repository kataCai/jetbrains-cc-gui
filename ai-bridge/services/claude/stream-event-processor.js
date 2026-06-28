import { emitAccumulatedUsage, mergeUsage } from '../../utils/usage-utils.js';
import { truncateErrorContent, truncateToolResultBlock } from './message-output-filter.js';
import { normalizeStreamDelta, resolveSnapshotDelta } from './stream-delta-normalizer.js';
import { shouldOutputAssistantMessage } from './streaming-output-policy.js';

export function emitUsageTag(msg) {
  if (msg.type === 'assistant' && msg.message?.usage) {
    const {
      input_tokens = 0,
      output_tokens = 0,
      cache_creation_input_tokens = 0,
      cache_read_input_tokens = 0
    } = msg.message.usage;
    console.log('[USAGE]', JSON.stringify({
      input_tokens,
      output_tokens,
      cache_creation_input_tokens,
      cache_read_input_tokens
    }));
  }
}

export function createTurnState(requestContext, runtime) {
  return {
    streamingEnabled: requestContext.streamingEnabled,
    streamStarted: false,
    streamEnded: false,
    hasStreamEvents: false,
    lastAssistantContent: '',
    lastThinkingContent: '',
    textBlockContentByIndex: new Map(),
    thinkingBlockContentByIndex: new Map(),
    finalSessionId: requestContext.requestedSessionId || runtime?.sessionId || '',
    accumulatedUsage: null
  };
}

export function processStreamEvent(msg, turnState) {
  const event = msg.event;
  if (!event) return;

  if (event.type === 'message_start' && event.message?.usage) {
    turnState.accumulatedUsage = mergeUsage(turnState.accumulatedUsage, event.message.usage);
  }

  if (event.type === 'message_delta' && event.usage) {
    turnState.accumulatedUsage = mergeUsage(turnState.accumulatedUsage, event.usage);
    emitAccumulatedUsage(turnState.accumulatedUsage);
  }

  if (event.type === 'content_block_delta' && event.delta) {
    if (event.delta.type === 'text_delta' && event.delta.text) {
      const delta = normalizeStreamDelta(turnState, 'text', event.index, event.delta.text);
      if (delta) {
        process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
        turnState.lastAssistantContent += delta;
      }
    } else if (event.delta.type === 'thinking_delta' && event.delta.thinking) {
      const delta = normalizeStreamDelta(turnState, 'thinking', event.index, event.delta.thinking);
      if (delta) {
        process.stdout.write(`[THINKING_DELTA] ${JSON.stringify(delta)}\n`);
        turnState.lastThinkingContent += delta;
      }
    }
  }
}

/**
 * 处理 assistant 完整快照中的 text/thinking block。
 * 这里故意让 snapshot 路径与 stream_event 共享同一个 normalizer，
 * 这样 corrective rewrite 不会因为长度比较而重复补发。
 *
 * @param {'text'|'thinking'} kind block 类型
 * @param {string} currentText 当前 block 的 snapshot 内容
 * @param {object} turnState 当前 turn 状态
 * @param {number} blockIndex block 索引
 * @returns {void}
 */
function emitSnapshotBlock(kind, currentText, turnState, blockIndex) {
  if (!turnState.streamingEnabled) {
    if (kind === 'text') {
      console.log('[CONTENT]', truncateErrorContent(currentText));
    } else {
      console.log('[THINKING]', currentText);
    }
    return;
  }

  const delta = resolveSnapshotDelta(turnState, kind, blockIndex, currentText);
  if (!delta) {
    return;
  }

  if (kind === 'text') {
    process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
    turnState.lastAssistantContent += delta;
  } else {
    process.stdout.write(`[THINKING_DELTA] ${JSON.stringify(delta)}\n`);
    turnState.lastThinkingContent += delta;
  }
}

export function processMessageContent(msg, turnState) {
  if (msg.type !== 'assistant') return;
  const content = msg.message?.content;

  if (Array.isArray(content)) {
    for (let i = 0; i < content.length; i += 1) {
      const block = content[i];
      if (block.type === 'text') {
        emitSnapshotBlock('text', block.text || '', turnState, i);
      } else if (block.type === 'thinking') {
        emitSnapshotBlock('thinking', block.thinking || block.text || '', turnState, i);
      }
    }
  } else if (typeof content === 'string') {
    emitSnapshotBlock('text', content, turnState, 0);
  }
}

export function processToolResultMessages(msg) {
  if (msg.type !== 'user') return;
  const content = msg.message?.content ?? msg.content;
  if (!Array.isArray(content)) return;
  for (const block of content) {
    if (block.type === 'tool_result') {
      console.log('[TOOL_RESULT]', JSON.stringify(truncateToolResultBlock(block)));
    }
  }
}

export function shouldOutputMessage(msg, turnState) {
  return shouldOutputAssistantMessage(msg, !!turnState.streamingEnabled);
}
