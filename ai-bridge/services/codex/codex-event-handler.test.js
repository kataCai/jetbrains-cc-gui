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
