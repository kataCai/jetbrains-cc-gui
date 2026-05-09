import { emitAccumulatedUsage, mergeUsage } from '../../utils/usage-utils.js';
import { truncateErrorContent, truncateToolResultBlock } from './message-output-filter.js';
import { normalizeStreamDelta, rememberStreamSnapshot } from './stream-delta-normalizer.js';
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

export function processMessageContent(msg, turnState) {
  if (msg.type !== 'assistant') return;
  const content = msg.message?.content;

  if (Array.isArray(content)) {
    for (let i = 0; i < content.length; i += 1) {
      const block = content[i];
      if (block.type === 'text') {
        const currentText = block.text || '';
        rememberStreamSnapshot(turnState, 'text', i, currentText);
        if (turnState.streamingEnabled && !turnState.hasStreamEvents && currentText.length > turnState.lastAssistantContent.length) {
          const delta = currentText.substring(turnState.lastAssistantContent.length);
          if (delta) {
            process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
          }
          turnState.lastAssistantContent = currentText;
        } else if (turnState.streamingEnabled && turnState.hasStreamEvents) {
          if (currentText.length > turnState.lastAssistantContent.length) {
            turnState.lastAssistantContent = currentText;
          }
        } else if (!turnState.streamingEnabled) {
          console.log('[CONTENT]', truncateErrorContent(currentText));
        }
      } else if (block.type === 'thinking') {
        const thinkingText = block.thinking || block.text || '';
        rememberStreamSnapshot(turnState, 'thinking', i, thinkingText);
        if (turnState.streamingEnabled && !turnState.hasStreamEvents && thinkingText.length > turnState.lastThinkingContent.length) {
          const delta = thinkingText.substring(turnState.lastThinkingContent.length);
          if (delta) {
            process.stdout.write(`[THINKING_DELTA] ${JSON.stringify(delta)}\n`);
          }
          turnState.lastThinkingContent = thinkingText;
        } else if (turnState.streamingEnabled && turnState.hasStreamEvents) {
          if (thinkingText.length > turnState.lastThinkingContent.length) {
            turnState.lastThinkingContent = thinkingText;
          }
        } else if (!turnState.streamingEnabled) {
          console.log('[THINKING]', thinkingText);
        }
      }
    }
  } else if (typeof content === 'string') {
    rememberStreamSnapshot(turnState, 'text', 0, content);
    if (turnState.streamingEnabled && !turnState.hasStreamEvents && content.length > turnState.lastAssistantContent.length) {
      const delta = content.substring(turnState.lastAssistantContent.length);
      if (delta) {
        process.stdout.write(`[CONTENT_DELTA] ${JSON.stringify(delta)}\n`);
      }
      turnState.lastAssistantContent = content;
    } else if (turnState.streamingEnabled && turnState.hasStreamEvents) {
      if (content.length > turnState.lastAssistantContent.length) {
        turnState.lastAssistantContent = content;
      }
    } else if (!turnState.streamingEnabled) {
      console.log('[CONTENT]', truncateErrorContent(content));
    }
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
