import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createTurnState,
  processMessageContent,
  processStreamEvent,
  shouldOutputMessage,
} from './stream-event-processor.js';

/**
 * 创建流式事件处理器测试使用的 turn 状态。
 * 该状态只包含 ai-bridge 层需要的最小字段，避免测试依赖 Java 或 WebView 环境。
 *
 * @param {boolean} streamingEnabled 是否启用流式输出
 * @returns {object} 可传给 stream-event-processor 的 turn 状态
 */
function makeTurnState(streamingEnabled = true) {
  return createTurnState(
    { streamingEnabled, requestedSessionId: 'sess-test' },
    null
  );
}

/**
 * 捕获测试期间写入 stdout 的流式标签。
 * 该 helper 用于验证 `[MESSAGE]`、`[CONTENT_DELTA]`、`[THINKING_DELTA]`
 * 的输出契约，执行后会恢复原始 stdout，避免污染其他测试。
 *
 * @param {Function} fn 需要在捕获环境中执行的测试逻辑
 * @returns {string[]} 捕获到的输出行
 */
function captureStdout(fn) {
  const original = process.stdout.write.bind(process.stdout);
  const captured = [];
  process.stdout.write = (chunk) => {
    const text = typeof chunk === 'string' ? chunk : chunk.toString();
    captured.push(text);
    return true;
  };
  try {
    fn();
  } finally {
    process.stdout.write = original;
  }
  return captured;
}

/**
 * 从 stdout 捕获结果中过滤指定标签。
 *
 * @param {string[]} captured stdout 捕获结果
 * @param {string} tag 需要过滤的标签前缀
 * @returns {string[]} 匹配指定标签的输出行
 */
function tagLines(captured, tag) {
  return captured.filter((line) => line.startsWith(tag));
}

test('shouldOutputMessage: streaming assistant without tool_use returns false', () => {
  const state = makeTurnState(true);
  state.hasStreamEvents = true;
  const msg = {
    type: 'assistant',
    message: { content: [{ type: 'text', text: 'Hello' }] },
  };
  assert.equal(shouldOutputMessage(msg, state), false);
});

test('shouldOutputMessage: streaming assistant with tool_use returns true', () => {
  const state = makeTurnState(true);
  state.hasStreamEvents = true;
  const msg = {
    type: 'assistant',
    message: {
      content: [
        { type: 'text', text: 'Calling tool' },
        { type: 'tool_use', id: 't1', name: 'Read', input: {} },
      ],
    },
  };
  assert.equal(shouldOutputMessage(msg, state), true);
});

test('shouldOutputMessage: streaming assistant with thinking + text but no tool_use returns false', () => {
  const state = makeTurnState(true);
  state.hasStreamEvents = true;
  const msg = {
    type: 'assistant',
    message: {
      content: [
        { type: 'thinking', thinking: 'pondering' },
        { type: 'text', text: 'answer' },
      ],
    },
  };
  assert.equal(shouldOutputMessage(msg, state), false);
});

test('shouldOutputMessage: non-streaming assistant always returns true', () => {
  const state = makeTurnState(false);
  const msg = {
    type: 'assistant',
    message: { content: [{ type: 'text', text: 'X' }] },
  };
  assert.equal(shouldOutputMessage(msg, state), true);
});

test('shouldOutputMessage: non-assistant messages always returned regardless of streaming', () => {
  const state = makeTurnState(true);
  assert.equal(shouldOutputMessage({ type: 'user', message: {} }, state), true);
  assert.equal(shouldOutputMessage({ type: 'system', session_id: 's' }, state), true);
  assert.equal(shouldOutputMessage({ type: 'result', is_error: false }, state), true);
});

test('shouldOutputMessage: streaming assistant with content as plain string returns false', () => {
  const state = makeTurnState(true);
  state.hasStreamEvents = true;
  const msg = {
    type: 'assistant',
    message: { content: 'plain string content' },
  };
  assert.equal(shouldOutputMessage(msg, state), false);
});

test('shouldOutputMessage: streaming assistant with empty/missing content returns false', () => {
  const state = makeTurnState(true);
  state.hasStreamEvents = true;
  assert.equal(shouldOutputMessage({ type: 'assistant', message: {} }, state), false);
  assert.equal(shouldOutputMessage({ type: 'assistant' }, state), false);
  assert.equal(
    shouldOutputMessage({ type: 'assistant', message: { content: [] } }, state),
    false
  );
});

test('shouldOutputMessage: streaming assistant with multiple tool_use blocks returns true', () => {
  const state = makeTurnState(true);
  state.hasStreamEvents = true;
  const msg = {
    type: 'assistant',
    message: {
      content: [
        { type: 'text', text: 'Running tools' },
        { type: 'tool_use', id: 't1', name: 'Read', input: {} },
        { type: 'tool_use', id: 't2', name: 'Write', input: {} },
      ],
    },
  };
  assert.equal(shouldOutputMessage(msg, state), true);
});

test('end-to-end: streaming pure-text response emits no [MESSAGE], no duplicate [CONTENT_DELTA]', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        type: 'stream_event',
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'Hello' } },
      },
      state
    );
    state.hasStreamEvents = true;
    processStreamEvent(
      {
        type: 'stream_event',
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text: ' world' } },
      },
      state
    );

    const assistantMsg = {
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Hello world' }] },
    };

    if (shouldOutputMessage(assistantMsg, state)) {
      process.stdout.write(`[MESSAGE] ${JSON.stringify(assistantMsg)}\n`);
    }
    processMessageContent(assistantMsg, state);
  });

  const messageLines = tagLines(captured, '[MESSAGE]');
  const deltaLines = tagLines(captured, '[CONTENT_DELTA]');

  assert.equal(messageLines.length, 0, 'pure-text streaming must not emit [MESSAGE]');
  assert.equal(deltaLines.length, 2);
  assert.match(deltaLines[0], /"Hello"/);
  assert.match(deltaLines[1], /" world"/);
});

test('end-to-end: streaming with tool_use emits one [MESSAGE] so Java can route the tool call', () => {
  const state = makeTurnState(true);
  state.hasStreamEvents = true;

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          delta: { type: 'text_delta', text: 'Reading file' },
        },
      },
      state
    );

    const assistantMsg = {
      type: 'assistant',
      message: {
        content: [
          { type: 'text', text: 'Reading file' },
          { type: 'tool_use', id: 't1', name: 'Read', input: { path: 'a.txt' } },
        ],
      },
    };

    if (shouldOutputMessage(assistantMsg, state)) {
      process.stdout.write(`[MESSAGE] ${JSON.stringify(assistantMsg)}\n`);
    }
    processMessageContent(assistantMsg, state);
  });

  const messageLines = tagLines(captured, '[MESSAGE]');
  assert.equal(messageLines.length, 1, 'tool_use streaming must emit exactly one [MESSAGE]');
  assert.match(messageLines[0], /"tool_use"/);
});

test('end-to-end: non-streaming pure-text response still emits [MESSAGE]', () => {
  const state = makeTurnState(false);

  const captured = captureStdout(() => {
    const assistantMsg = {
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Hello' }] },
    };
    if (shouldOutputMessage(assistantMsg, state)) {
      process.stdout.write(`[MESSAGE] ${JSON.stringify(assistantMsg)}\n`);
    }
  });

  const messageLines = tagLines(captured, '[MESSAGE]');
  assert.equal(messageLines.length, 1, 'non-streaming must keep emitting [MESSAGE]');
});

test('processMessageContent: fallback pure-text snapshot only emits novel suffix', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processMessageContent(
      {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'hello' }] },
      },
      state
    );

    processMessageContent(
      {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'hello world' }] },
      },
      state
    );
  });

  assert.deepEqual(captured, [
    `[CONTENT_DELTA] ${JSON.stringify('hello')}\n`,
    `[CONTENT_DELTA] ${JSON.stringify(' world')}\n`,
  ]);
});

test('end-to-end: streaming tail-fill snapshot still triggers [CONTENT_DELTA] even when [MESSAGE] suppressed', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        type: 'stream_event',
        event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'Hello' } },
      },
      state
    );
    state.hasStreamEvents = true;

    const assistantMsg = {
      type: 'assistant',
      message: { content: [{ type: 'text', text: 'Hello world' }] },
    };

    if (shouldOutputMessage(assistantMsg, state)) {
      process.stdout.write(`[MESSAGE] ${JSON.stringify(assistantMsg)}\n`);
    }
    processMessageContent(assistantMsg, state);
  });

  const messageLines = tagLines(captured, '[MESSAGE]');
  const deltaLines = tagLines(captured, '[CONTENT_DELTA]');

  assert.equal(messageLines.length, 0, 'pure-text streaming must not emit [MESSAGE]');
  assert.equal(deltaLines.length, 2, 'stream_event delta + tail-fill delta');
  assert.match(deltaLines[0], /"Hello"/);
  assert.match(deltaLines[1], /" world"/);
  assert.equal(state.lastAssistantContent, 'Hello world');
});

test('processStreamEvent: cumulative text deltas only emit the novel suffix', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'text_delta', text: 'Now I need to add' },
        },
      },
      state
    );
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'text_delta', text: 'Now I need to add the handler' },
        },
      },
      state
    );
  });

  const deltaLines = tagLines(captured, '[CONTENT_DELTA]');

  assert.equal(deltaLines.length, 2);
  assert.match(deltaLines[0], /"Now I need to add"/);
  assert.match(deltaLines[1], /" the handler"/);
  assert.equal(state.lastAssistantContent, 'Now I need to add the handler');
});

test('processStreamEvent: cumulative thinking deltas are tracked per block index', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'thinking_delta', thinking: 'Plan step one.' },
        },
      },
      state
    );
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: '2',
          delta: { type: 'thinking_delta', thinking: 'Plan step two.' },
        },
      },
      state
    );
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: '2',
          delta: { type: 'thinking_delta', thinking: 'Plan step two. Continue.' },
        },
      },
      state
    );
  });

  const deltaLines = tagLines(captured, '[THINKING_DELTA]');

  assert.equal(deltaLines.length, 3);
  assert.match(deltaLines[0], /"Plan step one\."/);
  assert.match(deltaLines[1], /"Plan step two\."/);
  assert.match(deltaLines[2], /" Continue\."/);
  assert.equal(state.lastThinkingContent, 'Plan step one.Plan step two. Continue.');
});

test('processStreamEvent: stream_event delta followed by corrective snapshot does not emit duplicate delta', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'text_delta', text: 'hello' },
        },
      },
      state
    );

    processMessageContent(
      {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'hello' }] },
      },
      state
    );
  });

  assert.deepEqual(captured, [
    `[CONTENT_DELTA] ${JSON.stringify('hello')}\n`,
  ]);
});

test('processStreamEvent: snapshot-mode block absorbs corrective rewrites without duplication', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'thinking_delta', thinking: 'Now I can see' },
        },
      },
      state
    );
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'thinking_delta', thinking: 'Now I can see the actual code. Let me implement' },
        },
      },
      state
    );
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: {
            type: 'thinking_delta',
            thinking: 'Now I can see the actuall code. Let me implement the changes.',
          },
        },
      },
      state
    );
  });

  const deltaLines = tagLines(captured, '[THINKING_DELTA]');
  const totalEmitted = deltaLines
    .map((line) => JSON.parse(line.replace(/^\[THINKING_DELTA\]\s+/, '').trim()))
    .join('');

  assert.ok(
    !totalEmitted.includes('Now I can seeNow I can see'),
    'Snapshot rewrite must not double the block content. Emitted: ' + JSON.stringify(totalEmitted),
  );
  assert.ok(
    !totalEmitted.includes('Let me implementNow I can see'),
    'Snapshot rewrite must not splice duplicated content. Emitted: ' + JSON.stringify(totalEmitted),
  );
});

test('processMessageContent: tail-fill snapshot followed by corrective rewrite does not emit duplicate suffix', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processMessageContent(
      {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'Now I can see' }] },
      },
      state
    );

    processMessageContent(
      {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'Now I can see the actual code. Let me implement' }] },
      },
      state
    );

    processMessageContent(
      {
        type: 'assistant',
        message: { content: [{ type: 'text', text: 'Now I can see the actuall code. Let me implement the changes.' }] },
      },
      state
    );
  });

  const deltaLines = tagLines(captured, '[CONTENT_DELTA]');
  const totalEmitted = deltaLines
    .map((line) => JSON.parse(line.replace(/^\[CONTENT_DELTA\]\s+/, '').trim()))
    .join('');

  assert.equal(deltaLines.length, 2);
  assert.match(deltaLines[0], /"Now I can see"/);
  assert.match(deltaLines[1], /" the actual code\. Let me implement"/);
  assert.ok(
    !totalEmitted.includes('Now I can seeNow I can see'),
    'Snapshot corrective rewrite must not replay the whole prefix. Emitted: ' + JSON.stringify(totalEmitted),
  );
  assert.ok(
    !totalEmitted.includes('Let me implementNow I can see'),
    'Snapshot corrective rewrite must not append duplicated rewrite fragments. Emitted: ' + JSON.stringify(totalEmitted),
  );
});

test('processStreamEvent: incremental-mode block keeps appending novel deltas', () => {
  const state = makeTurnState(true);

  const captured = captureStdout(() => {
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'text_delta', text: 'Hello' },
        },
      },
      state
    );
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'text_delta', text: ' world' },
        },
      },
      state
    );
    processStreamEvent(
      {
        type: 'stream_event',
        event: {
          type: 'content_block_delta',
          index: 0,
          delta: { type: 'text_delta', text: '!' },
        },
      },
      state
    );
  });

  const deltaLines = tagLines(captured, '[CONTENT_DELTA]');

  assert.equal(deltaLines.length, 3, 'each incremental fragment should emit');
  assert.match(deltaLines[0], /"Hello"/);
  assert.match(deltaLines[1], /" world"/);
  assert.match(deltaLines[2], /"!"/);
  assert.equal(state.lastAssistantContent, 'Hello world!');
});
