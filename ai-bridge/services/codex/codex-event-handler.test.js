import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createInitialEventState,
  processCodexEventStream,
} from './codex-event-handler.js';

async function* eventsFrom(items) {
  for (const item of items) {
    yield item;
  }
}

async function captureStdout(fn) {
  const original = process.stdout.write.bind(process.stdout);
  const captured = [];
  process.stdout.write = (chunk, ...rest) => {
    const text = typeof chunk === 'string' ? chunk : chunk.toString();
    captured.push(text);
    return true;
  };
  try {
    await fn();
  } finally {
    process.stdout.write = original;
  }
  return captured;
}

function tagLines(captured, tag) {
  return captured.filter((line) => line.startsWith(tag));
}

function makeConfig() {
  return {
    cwd: undefined,
    threadId: null,
    threadOptions: {},
    normalizedPermissionMode: 'default',
    turnAbortController: new AbortController(),
  };
}

test('Codex item.updated agent_message emits incremental content deltas before completion', async () => {
  const emittedMessages = [];
  const state = createInitialEventState((message) => emittedMessages.push(message));

  const captured = await captureStdout(async () => {
    await processCodexEventStream(
      eventsFrom([
        {
          type: 'item.updated',
          item: { id: 'msg-1', type: 'agent_message', text: 'Hel' },
        },
        {
          type: 'item.updated',
          item: { id: 'msg-1', type: 'agent_message', text: 'Hello' },
        },
        {
          type: 'item.completed',
          item: { id: 'msg-1', type: 'agent_message', text: 'Hello' },
        },
      ]),
      state,
      makeConfig(),
    );
  });

  const deltaLines = tagLines(captured, '[CONTENT_DELTA]');

  assert.equal(deltaLines.length, 2);
  assert.match(deltaLines[0], /"Hel"/);
  assert.match(deltaLines[1], /"lo"/);
  assert.equal(state.assistantText, 'Hello');
  assert.equal(emittedMessages.length, 1);
  assert.deepEqual(emittedMessages[0], {
    type: 'assistant',
    message: {
      role: 'assistant',
      content: [{ type: 'text', text: 'Hello' }],
    },
  });
});

test('Codex turn.completed keeps usage emission but must not trigger outer stream-end callback', async () => {
  const emittedMessages = [];
  const state = createInitialEventState((message) => emittedMessages.push(message));
  let turnCompletedCallbackCount = 0;

  await processCodexEventStream(
    eventsFrom([
      {
        type: 'thread.started',
        thread_id: 'thread-1',
      },
      {
        type: 'turn.completed',
        usage: {
          input_tokens: 11,
          output_tokens: 7,
          cached_input_tokens: 3,
        },
      },
    ]),
    state,
    {
      ...makeConfig(),
      onTurnCompleted: () => {
        turnCompletedCallbackCount += 1;
      },
    },
  );

  assert.equal(turnCompletedCallbackCount, 0);
  assert.equal(emittedMessages.length, 1);
  assert.equal(emittedMessages[0].type, 'result');
  assert.deepEqual(emittedMessages[0].usage, {
    input_tokens: 11,
    output_tokens: 7,
    cache_creation_input_tokens: 0,
    cache_read_input_tokens: 3,
  });
});

test('Codex turn.failed must throw without triggering outer stream-end callback early', async () => {
  const state = createInitialEventState(() => {});
  let turnFailedCallbackCount = 0;

  await assert.rejects(
    processCodexEventStream(
      eventsFrom([
        {
          type: 'turn.failed',
          error: { message: 'simulated failure' },
        },
      ]),
      state,
      {
        ...makeConfig(),
        onTurnFailed: () => {
          turnFailedCallbackCount += 1;
        },
      },
    ),
    /simulated failure/,
  );

  assert.equal(turnFailedCallbackCount, 0);
});

test('Codex completion summary state distinguishes mid-turn prose from final visible summary', async () => {
  const emittedMessages = [];
  const state = createInitialEventState((message) => emittedMessages.push(message));

  await processCodexEventStream(
    eventsFrom([
      {
        type: 'item.updated',
        item: { id: 'msg-1', type: 'agent_message', text: 'I am checking the repository structure first.' },
      },
      {
        type: 'item.completed',
        item: { id: 'msg-1', type: 'agent_message', text: 'I am checking the repository structure first.' },
      },
      {
        type: 'item.started',
        item: { id: 'cmd-1', type: 'command_execution', command: 'git status' },
      },
      {
        type: 'item.completed',
        item: {
          id: 'cmd-1',
          type: 'command_execution',
          command: 'git status',
          aggregated_output: 'On branch main',
          exit_code: 0,
        },
      },
      {
        type: 'turn.completed',
      },
    ]),
    state,
    makeConfig(),
  );

  // 中途虽然出现过 assistant 文本，但最后一个可见结构已经是 tool_use/tool_result，
  // 因此不能把“本轮已有任何文本”误判为“末尾已有最终总结”。
  assert.equal(state.assistantText.includes('repository structure'), true);
  assert.equal(state.hasVisibleAssistantText, true);
  assert.equal(state.hasTrailingAssistantTextSummary, false);
  assert.equal(state.executedCommandCount, 1);
  assert.equal(state.fileChangeCount, 0);
  assert.equal(emittedMessages.some((message) => message?.message?.content?.[0]?.type === 'tool_use'), true);
});

test('Codex completion summary state remains ready when final assistant text is the last visible content', async () => {
  const emittedMessages = [];
  const state = createInitialEventState((message) => emittedMessages.push(message));

  await processCodexEventStream(
    eventsFrom([
      {
        type: 'item.started',
        item: { id: 'cmd-1', type: 'command_execution', command: 'git status' },
      },
      {
        type: 'item.completed',
        item: {
          id: 'cmd-1',
          type: 'command_execution',
          command: 'git status',
          aggregated_output: 'On branch main',
          exit_code: 0,
        },
      },
      {
        type: 'item.completed',
        item: { id: 'msg-2', type: 'agent_message', text: 'I have finished checking the repository.' },
      },
      {
        type: 'turn.completed',
      },
    ]),
    state,
    makeConfig(),
  );

  assert.equal(state.executedCommandCount, 1);
  assert.equal(state.hasTrailingAssistantTextSummary, true);
  assert.equal(state.trailingAssistantText, 'I have finished checking the repository.');
  assert.equal(state.lastVisibleContentKind, 'assistant_text');
  assert.equal(emittedMessages.at(-1)?.message?.content?.[0]?.text, 'I have finished checking the repository.');
});
