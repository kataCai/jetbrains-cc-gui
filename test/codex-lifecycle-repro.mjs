import { createInitialEventState, processCodexEventStream } from '../ai-bridge/services/codex/codex-event-handler.js';

/**
 * 本地复现脚本：验证 turn.completed 只会产生 usage / result，不会提前制造 STREAM_END。
 * 用法：
 *   node test/codex-lifecycle-repro.mjs
 */
async function main() {
  const emitted = [];
  const state = createInitialEventState((message) => emitted.push(message));
  let streamEndCount = 0;

  async function* events() {
    yield { type: 'thread.started', thread_id: 'repro-thread-1' };
    yield {
      type: 'turn.completed',
      usage: {
        input_tokens: 12,
        output_tokens: 8,
        cached_input_tokens: 2,
      },
    };
  }

  await processCodexEventStream(events(), state, {
    messageId: 'repro-message',
    channelId: 'repro-channel',
    projectPath: process.cwd(),
    initialInput: 'repro',
    currentModel: 'codex-test',
    messageType: 'prompt',
    sessionId: 'repro-session',
    permissionMode: 'default',
    onStreamEnd: () => {
      streamEndCount += 1;
    },
  });

  console.log('[REPRO] emittedMessages=', JSON.stringify(emitted, null, 2));
  console.log('[REPRO] streamEndCount=', streamEndCount);
  console.log('[REPRO] expectation=turn.completed should not trigger outer stream end');
}

main().catch((error) => {
  console.error('[REPRO][ERROR]', error);
  process.exitCode = 1;
});
