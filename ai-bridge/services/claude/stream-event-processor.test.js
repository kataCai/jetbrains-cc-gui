import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createTurnState,
  processMessageContent,
  processStreamEvent,
  shouldOutputMessage,
} from './stream-event-processor.js';

function createStreamingTurnState() {
  return createTurnState({ streamingEnabled: true }, { sessionId: 'session-1' });
}

test('shouldOutputMessage: streaming pure-text assistant does not emit full [MESSAGE]', () => {
  const state = createStreamingTurnState();
  const message = {
    type: 'assistant',
    message: {
      content: [{ type: 'text', text: 'hello world' }],
    },
  };

  assert.equal(shouldOutputMessage(message, state), false);
});

test('shouldOutputMessage: streaming assistant with tool_use keeps full [MESSAGE]', () => {
  const state = createStreamingTurnState();
  const message = {
    type: 'assistant',
    message: {
      content: [
        { type: 'tool_use', id: 'tool-1', name: 'shell_command' },
        { type: 'text', text: 'running command' },
      ],
    },
  };

  assert.equal(shouldOutputMessage(message, state), true);
});

test('processMessageContent: fallback pure-text snapshot only emits novel suffix', () => {
  const state = createStreamingTurnState();
  const writes = [];
  const originalWrite = process.stdout.write;
  process.stdout.write = ((chunk) => {
    writes.push(String(chunk));
    return true;
  });

  try {
    processMessageContent(
      {
        type: 'assistant',
        message: {
          content: [{ type: 'text', text: 'hello' }],
        },
      },
      state,
    );

    processMessageContent(
      {
        type: 'assistant',
        message: {
          content: [{ type: 'text', text: 'hello world' }],
        },
      },
      state,
    );
  } finally {
    process.stdout.write = originalWrite;
  }

  assert.deepEqual(writes, [
    `[CONTENT_DELTA] ${JSON.stringify('hello')}\n`,
    `[CONTENT_DELTA] ${JSON.stringify(' world')}\n`,
  ]);
});

test('processStreamEvent: stream_event delta followed by corrective snapshot does not emit duplicate delta', () => {
  const state = createStreamingTurnState();
  const writes = [];
  const originalWrite = process.stdout.write;
  process.stdout.write = ((chunk) => {
    writes.push(String(chunk));
    return true;
  });

  try {
    processStreamEvent(
      {
        event: {
          type: 'content_block_delta',
          delta: { type: 'text_delta', text: 'hello' },
        },
      },
      state,
    );

    processMessageContent(
      {
        type: 'assistant',
        message: {
          content: [{ type: 'text', text: 'hello' }],
        },
      },
      state,
    );
  } finally {
    process.stdout.write = originalWrite;
  }

  assert.deepEqual(writes, [
    `[CONTENT_DELTA] ${JSON.stringify('hello')}\n`,
  ]);
});
