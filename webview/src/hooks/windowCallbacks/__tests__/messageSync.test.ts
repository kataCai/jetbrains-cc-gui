import { describe, expect, it } from 'vitest';
import type { MutableRefObject } from 'react';
import type { ClaudeMessage } from '../../../types';
import {
  OPTIMISTIC_MESSAGE_TIME_WINDOW,
  appendOptimisticMessageIfMissing,
  ensureStreamingAssistantInList,
  getStreamEndHandlingMode,
  getRawUuid,
  preserveLastAssistantIdentity,
  preserveMessageIdentity,
  preserveRecentlyEndedStreamingTurn,
  preserveStreamingAssistantContent,
  preserveLatestMessagesOnShrink,
  stripDuplicateTrailingToolMessages,
  stripUuidFromRaw,
} from '../messageSync';

const ref = <T>(value: T): MutableRefObject<T> => ({ current: value });

const findLastAssistantIndex = (msgs: ClaudeMessage[]): number =>
  msgs.reduce((acc, m, i) => (m.type === 'assistant' ? i : acc), -1);

const patchAssistantForStreaming = (msg: ClaudeMessage): ClaudeMessage => ({
  ...msg,
  isStreaming: true,
});

const makeMsg = (
  type: ClaudeMessage['type'],
  content: string,
  extra?: Partial<ClaudeMessage>,
): ClaudeMessage => ({
  type,
  content,
  timestamp: new Date().toISOString(),
  ...extra,
});

const makeUserMsg = (content: string, extra?: Partial<ClaudeMessage>) =>
  makeMsg('user', content, extra);

const makeAssistantMsg = (content: string, extra?: Partial<ClaudeMessage>) =>
  makeMsg('assistant', content, extra);

/**
 * `messageSync` 工具函数回归测试。
 * 目标是覆盖：
 * 1. optimistic message/identity 保护；
 * 2. streaming/raw block 文本保护；
 * 3. stream end 之后的短时 reconcile 保护；
 * 4. Codex 工具尾消息去重与 shrink 保护。
 */
describe('getStreamEndHandlingMode', () => {
  it('uses full finalize when streaming is active', () => {
    expect(getStreamEndHandlingMode('codex', true, 0)).toBe('full');
  });

  it('uses full finalize when a turn id is still present', () => {
    expect(getStreamEndHandlingMode('codex', false, 7)).toBe('full');
  });

  it('uses minimal finalize for Codex when stream start was lost', () => {
    expect(getStreamEndHandlingMode('codex', false, 0)).toBe('minimal');
  });

  it('skips finalize for non-Codex providers when no stream is active', () => {
    expect(getStreamEndHandlingMode('claude', false, 0)).toBe('skip');
  });
});

describe('getRawUuid', () => {
  it('returns undefined when msg is undefined', () => {
    expect(getRawUuid(undefined)).toBeUndefined();
  });

  it('returns undefined when msg has no raw field', () => {
    expect(getRawUuid(makeUserMsg('hello'))).toBeUndefined();
  });

  it('returns undefined when raw is a string', () => {
    const msg: ClaudeMessage = { ...makeUserMsg('hello'), raw: 'plain-string' as any };
    expect(getRawUuid(msg)).toBeUndefined();
  });

  it('returns undefined when raw.uuid is not a string', () => {
    const msg: ClaudeMessage = { ...makeUserMsg('hello'), raw: { uuid: 42 } as any };
    expect(getRawUuid(msg)).toBeUndefined();
  });

  it('returns uuid string when present', () => {
    const msg: ClaudeMessage = { ...makeUserMsg('hello'), raw: { uuid: 'abc-123' } as any };
    expect(getRawUuid(msg)).toBe('abc-123');
  });
});

describe('stripUuidFromRaw', () => {
  it('returns primitive values as-is', () => {
    expect(stripUuidFromRaw(null)).toBeNull();
    expect(stripUuidFromRaw(undefined)).toBeUndefined();
    expect(stripUuidFromRaw('plain')).toBe('plain');
  });

  it('returns object unchanged when uuid is absent', () => {
    const raw = { message: { content: 'hi' } };
    expect(stripUuidFromRaw(raw)).toBe(raw);
  });

  it('removes uuid and keeps other properties', () => {
    const raw = { uuid: 'abc-123', message: 'content', extra: 42 };
    const result = stripUuidFromRaw(raw) as Record<string, unknown>;
    expect(result).not.toHaveProperty('uuid');
    expect(result.message).toBe('content');
    expect(result.extra).toBe(42);
  });
});

describe('preserveMessageIdentity', () => {
  it('returns nextMsg unchanged when prevMsg is undefined', () => {
    const next = makeUserMsg('hello');
    expect(preserveMessageIdentity(undefined, next)).toBe(next);
  });

  it('returns nextMsg unchanged when prevMsg has no timestamp', () => {
    const prev = { ...makeUserMsg('prev'), timestamp: undefined };
    const next = makeUserMsg('next');
    expect(preserveMessageIdentity(prev as ClaudeMessage, next)).toBe(next);
  });

  it('returns nextMsg unchanged when types differ', () => {
    const prev = makeUserMsg('prev');
    const next = makeAssistantMsg('next');
    expect(preserveMessageIdentity(prev, next)).toBe(next);
  });

  it('preserves prev timestamp into nextMsg when they differ', () => {
    const prevTimestamp = '2024-01-01T00:00:00.000Z';
    const prev = makeUserMsg('prev', { timestamp: prevTimestamp });
    const next = makeUserMsg('next', { timestamp: '2024-02-01T00:00:00.000Z' });
    const result = preserveMessageIdentity(prev, next);
    expect(result.timestamp).toBe(prevTimestamp);
    expect(result.content).toBe('next');
  });

  it('strips uuid from next when prev has no uuid but next does', () => {
    const prev = makeUserMsg('prev');
    const next: ClaudeMessage = {
      ...makeUserMsg('next'),
      raw: { uuid: 'should-be-stripped', content: 'data' } as any,
    };
    const result = preserveMessageIdentity(prev, next);
    expect(getRawUuid(result)).toBeUndefined();
    expect((result.raw as any)?.content).toBe('data');
  });

  it('does not strip uuid when prev also has uuid', () => {
    const prev: ClaudeMessage = {
      ...makeUserMsg('prev'),
      raw: { uuid: 'prev-uuid' } as any,
    };
    const next: ClaudeMessage = {
      ...makeUserMsg('next'),
      raw: { uuid: 'next-uuid' } as any,
    };
    const result = preserveMessageIdentity(prev, next);
    expect(getRawUuid(result)).toBe('next-uuid');
  });
});

describe('appendOptimisticMessageIfMissing', () => {
  it('returns nextList unchanged when prev list is empty', () => {
    const next = [makeUserMsg('hi')];
    expect(appendOptimisticMessageIfMissing([], next)).toBe(next);
  });

  it('returns nextList unchanged when last prev is not optimistic', () => {
    const prev = [makeUserMsg('prev')];
    const next = [makeUserMsg('next')];
    expect(appendOptimisticMessageIfMissing(prev, next)).toBe(next);
  });

  it('appends optimistic message when no match in nextList', () => {
    const ts = new Date().toISOString();
    const optimistic = makeUserMsg('hello', { isOptimistic: true, timestamp: ts });
    const prev = [optimistic];
    const next: ClaudeMessage[] = [makeAssistantMsg('different response')];

    const result = appendOptimisticMessageIfMissing(prev, next);
    expect(result).toHaveLength(2);
    expect(result[result.length - 1]).toBe(optimistic);
  });

  it('does not append when optimistic message is matched by content and time', () => {
    const ts = new Date().toISOString();
    const optimistic = makeUserMsg('hello world', { isOptimistic: true, timestamp: ts });
    const backendMsg = makeUserMsg('hello world', { timestamp: ts });
    const result = appendOptimisticMessageIfMissing([optimistic], [backendMsg]);
    expect(result).toHaveLength(1);
    expect(result[0]).toBe(backendMsg);
  });

  it('matches the latest backend user message by content even when confirmation is delayed', () => {
    const oldTs = new Date(Date.now() - OPTIMISTIC_MESSAGE_TIME_WINDOW - 1000).toISOString();
    const newTs = new Date().toISOString();
    const optimistic = makeUserMsg('slow confirmation', { isOptimistic: true, timestamp: oldTs });
    const backendMsg = makeUserMsg('slow confirmation', { timestamp: newTs });

    const result = appendOptimisticMessageIfMissing([optimistic], [backendMsg]);
    expect(result).toHaveLength(1);
    expect(result[0]).toBe(backendMsg);
  });

  it('matches delayed optimistic text against the latest backend user when older history has same content', () => {
    const optimistic = makeUserMsg('repeatable prompt', {
      isOptimistic: true,
      timestamp: new Date(Date.now() - OPTIMISTIC_MESSAGE_TIME_WINDOW - 1000).toISOString(),
    });
    const olderBackend = makeUserMsg('repeatable prompt', { timestamp: '2026-04-26T00:00:00.000Z' });
    const latestBackend = makeUserMsg('repeatable prompt', { timestamp: new Date().toISOString() });

    const result = appendOptimisticMessageIfMissing(
      [olderBackend, optimistic],
      [olderBackend, makeAssistantMsg('old answer'), latestBackend],
    );

    expect(result).toHaveLength(3);
    expect(result[2]).toBe(latestBackend);
  });

  it('merges attachment blocks from optimistic message into matched backend message', () => {
    const ts = new Date().toISOString();
    const attachmentBlock = { type: 'attachment', name: 'file.txt', data: 'base64data' };
    const optimistic = makeUserMsg('hello', {
      isOptimistic: true,
      timestamp: ts,
      raw: {
        message: {
          content: [attachmentBlock, { type: 'text', text: 'hello' }],
        },
      } as any,
    });
    const backendMsg = makeUserMsg('hello', { timestamp: ts });
    const result = appendOptimisticMessageIfMissing([optimistic], [backendMsg]);
    expect(result).toHaveLength(1);
    const raw = result[0].raw as any;
    expect(Array.isArray(raw?.message?.content)).toBe(true);
    expect(raw.message.content.some((b: any) => b.type === 'attachment')).toBe(true);
  });
});

describe('preserveLastAssistantIdentity', () => {
  it('returns nextList unchanged when prevList has no assistant', () => {
    const prev = [makeUserMsg('hello')];
    const next = [makeAssistantMsg('response')];
    const result = preserveLastAssistantIdentity(prev, next, findLastAssistantIndex);
    expect(result).toBe(next);
  });

  it('returns nextList unchanged when nextList has no assistant', () => {
    const prev = [makeAssistantMsg('prev response')];
    const next = [makeUserMsg('follow-up')];
    const result = preserveLastAssistantIdentity(prev, next, findLastAssistantIndex);
    expect(result).toBe(next);
  });

  it('stabilizes the identity of the last assistant message', () => {
    const prevTs = '2024-01-01T10:00:00.000Z';
    const prev = [makeAssistantMsg('first', { timestamp: prevTs })];
    const next = [makeAssistantMsg('updated', { timestamp: '2024-01-01T10:00:01.000Z' })];
    const result = preserveLastAssistantIdentity(prev, next, findLastAssistantIndex);
    expect(result[0].timestamp).toBe(prevTs);
    expect(result[0].content).toBe('updated');
  });

  it('does not merge identity across different turn IDs', () => {
    const prevTs = '2024-01-01T10:00:00.000Z';
    const prev = [makeAssistantMsg('a1', { timestamp: prevTs, __turnId: 1 })];
    const next = [makeAssistantMsg('a2', { timestamp: '2024-01-01T10:00:01.000Z', __turnId: 2 })];
    const result = preserveLastAssistantIdentity(prev, next, findLastAssistantIndex);
    expect(result).toBe(next);
    expect(result[0].timestamp).not.toBe(prevTs);
  });

  it('merges identity when both have same turn ID', () => {
    const prevTs = '2024-01-01T10:00:00.000Z';
    const prev = [makeAssistantMsg('a1', { timestamp: prevTs, __turnId: 1 })];
    const next = [makeAssistantMsg('a1 updated', { timestamp: '2024-01-01T10:00:01.000Z', __turnId: 1 })];
    const result = preserveLastAssistantIdentity(prev, next, findLastAssistantIndex);
    expect(result[0].timestamp).toBe(prevTs);
  });
});

describe('preserveStreamingAssistantContent', () => {
  it('returns nextList unchanged when not streaming', () => {
    const prev = [makeAssistantMsg('streamed long content here')];
    const next = [makeAssistantMsg('short')];

    const result = preserveStreamingAssistantContent(
      prev, next, ref(false), ref('streamed long content here'),
      findLastAssistantIndex, patchAssistantForStreaming,
    );
    expect(result).toBe(next);
  });

  it('returns nextList unchanged when prevList has no assistant', () => {
    const prev = [makeUserMsg('hello')];
    const next = [makeAssistantMsg('response')];
    const result = preserveStreamingAssistantContent(
      prev, next, ref(true), ref(''),
      findLastAssistantIndex, patchAssistantForStreaming,
    );
    expect(result).toBe(next);
  });

  it('replaces next assistant content with streamed content when longer', () => {
    const longStreamed = 'a'.repeat(100);
    const prev = [makeAssistantMsg(longStreamed)];
    const next = [makeAssistantMsg('short stale')];
    const result = preserveStreamingAssistantContent(
      prev, next, ref(true), ref(longStreamed),
      findLastAssistantIndex, patchAssistantForStreaming,
    );
    expect(result).not.toBe(next);
    expect(result[0].content).toBe(longStreamed);
    expect(result[0].isStreaming).toBe(true);
  });

  it('does not merge content across different turn IDs', () => {
    const longContent = 'long content from turn 1';
    const prev = [makeAssistantMsg(longContent, { __turnId: 1 })];
    const next = [makeAssistantMsg('short', { __turnId: 2 })];
    const result = preserveStreamingAssistantContent(
      prev, next, ref(true), ref(longContent),
      findLastAssistantIndex, patchAssistantForStreaming,
    );
    expect(result).toBe(next);
  });

  it('protects raw text blocks from backend regression when content string is also protected', () => {
    const prev = [makeAssistantMsg('ABCDE', {
      isStreaming: true,
      raw: { message: { content: [{ type: 'text', text: 'ABCDE' }] } } as any,
    })];
    const next = [makeAssistantMsg('ABC', {
      raw: { message: { content: [{ type: 'text', text: 'ABC' }] } } as any,
    })];

    const result = preserveStreamingAssistantContent(
      prev, next, ref(true), ref('ABCDE'),
      findLastAssistantIndex, patchAssistantForStreaming,
    );

    expect(result[0].content).toBe('ABCDE');
    expect(((result[0].raw as any)?.message?.content?.[0] as any)?.text).toBe('ABCDE');
  });

  it('protects raw text blocks even when backend content length equals streamed length', () => {
    const prev = [makeAssistantMsg('ABCDE', {
      isStreaming: true,
      raw: { message: { content: [{ type: 'text', text: 'ABCDE' }] } } as any,
    })];
    const next = [makeAssistantMsg('ABCDE', {
      raw: { message: { content: [{ type: 'text', text: 'ABC' }] } } as any,
    })];

    const result = preserveStreamingAssistantContent(
      prev, next, ref(true), ref('ABCDE'),
      findLastAssistantIndex, patchAssistantForStreaming,
    );

    expect(((result[0].raw as any)?.message?.content?.[0] as any)?.text).toBe('ABCDE');
  });

  it('injects new tool_use from backend while keeping streamed text block intact', () => {
    const prev = [makeAssistantMsg('ABCDE', {
      isStreaming: true,
      raw: {
        message: {
          content: [{ type: 'text', text: 'ABCDE' }],
        },
      } as any,
    })];
    const next = [makeAssistantMsg('AB', {
      raw: {
        message: {
          content: [
            { type: 'text', text: 'AB' },
            { type: 'tool_use', id: 'tu-1', name: 'Read', input: { path: '/foo' } },
          ],
        },
      } as any,
    })];

    const result = preserveStreamingAssistantContent(
      prev, next, ref(true), ref('ABCDE'),
      findLastAssistantIndex, patchAssistantForStreaming,
    );

    const blocks = (result[0].raw as any)?.message?.content as any[];
    expect(blocks).toHaveLength(2);
    expect(blocks[0].text).toBe('ABCDE');
    expect(blocks[1].type).toBe('tool_use');
    expect(blocks[1].id).toBe('tu-1');
  });

  it('does not regress thinking block raw content when backend has shorter thinking', () => {
    const longThinking = 'A'.repeat(200);
    const shortThinking = 'A'.repeat(50);
    const prev = [makeAssistantMsg('answer', {
      isStreaming: true,
      raw: {
        message: {
          content: [
            { type: 'thinking', thinking: longThinking },
            { type: 'text', text: 'answer' },
          ],
        },
      } as any,
    })];
    const next = [makeAssistantMsg('ans', {
      raw: {
        message: {
          content: [
            { type: 'thinking', thinking: shortThinking },
            { type: 'text', text: 'ans' },
          ],
        },
      } as any,
    })];

    const result = preserveStreamingAssistantContent(
      prev, next, ref(true), ref('answer'),
      findLastAssistantIndex, patchAssistantForStreaming,
    );

    const blocks = (result[0].raw as any)?.message?.content as any[];
    expect(blocks[0].type).toBe('thinking');
    expect((blocks[0].thinking as string).length).toBe(longThinking.length);
    expect(blocks[1].text).toBe('answer');
  });
});

describe('stripDuplicateTrailingToolMessages', () => {
  it('removes duplicated trailing tool-only messages in Codex snapshots', () => {
    const list = [
      makeAssistantMsg('', {
        raw: { message: { content: [{ type: 'tool_use', id: 'cmd-1', name: 'shell_command', input: { command: 'Get-ChildItem' } }] } } as any,
      }),
      makeUserMsg('', {
        raw: { message: { content: [{ type: 'tool_result', tool_use_id: 'cmd-1', content: 'ok' }] } } as any,
      }),
      makeAssistantMsg('done'),
      makeAssistantMsg('', {
        raw: { message: { content: [{ type: 'tool_use', id: 'cmd-1', name: 'shell_command', input: { command: 'Get-ChildItem' } }] } } as any,
      }),
      makeUserMsg('', {
        raw: { message: { content: [{ type: 'tool_result', tool_use_id: 'cmd-1', content: 'ok' }] } } as any,
      }),
    ];

    const result = stripDuplicateTrailingToolMessages(list, 'codex');
    expect(result).toHaveLength(3);
    expect(result[2].content).toBe('done');
  });
});

describe('ensureStreamingAssistantInList', () => {
  it('returns resultList unchanged when streaming assistant already in resultList', () => {
    const prev = [makeAssistantMsg('streaming', { __turnId: 1, isStreaming: true })];
    const result = [makeAssistantMsg('streaming', { __turnId: 1, isStreaming: true })];

    const { list, streamingIndex } = ensureStreamingAssistantInList(prev, result, true, 1);
    expect(list).toBe(result);
    expect(streamingIndex).toBe(0);
  });

  it('appends streaming assistant from prev when missing from result', () => {
    const streamingMsg = makeAssistantMsg('streaming content', { __turnId: 1, isStreaming: true });
    const prev = [makeUserMsg('q'), streamingMsg];
    const result = [makeUserMsg('q')];

    const { list, streamingIndex } = ensureStreamingAssistantInList(prev, result, true, 1);
    expect(list).toHaveLength(2);
    expect(list[1]).toBe(streamingMsg);
    expect(streamingIndex).toBe(1);
  });

  it('recovers streaming assistant from prevList when refs are already cleared', () => {
    const streamingMsg = makeAssistantMsg('last streamed', { __turnId: 5, isStreaming: true });
    const prev = [makeUserMsg('q'), streamingMsg];
    const result = [makeUserMsg('q')];

    const { list, streamingIndex } = ensureStreamingAssistantInList(prev, result, false, 0);
    expect(list).toHaveLength(2);
    expect(list[1]).toBe(streamingMsg);
    expect(streamingIndex).toBe(1);
  });
});

describe('preserveLatestMessagesOnShrink', () => {
  it('keeps the latest streaming assistant when backend snapshot temporarily shrinks', () => {
    const prev = [
      makeUserMsg('q1'),
      makeAssistantMsg('a1'),
      makeUserMsg('q2'),
      makeAssistantMsg('streaming answer', { isStreaming: true, __turnId: 9 }),
    ];
    const next = [
      makeUserMsg('q1'),
      makeAssistantMsg('a1'),
    ];

    const result = preserveLatestMessagesOnShrink(prev, next, 'codex');
    expect(result).toHaveLength(4);
    expect(result[2].content).toBe('q2');
    expect(result[3].content).toBe('streaming answer');
  });
});

describe('preserveRecentlyEndedStreamingTurn', () => {
  it('keeps the just-ended assistant when backend snapshot briefly shrinks after stream end', () => {
    const previousEndedAt = (window as any).__lastStreamEndedAt;
    const previousEndedTurnId = (window as any).__lastStreamEndedTurnId;
    (window as any).__lastStreamEndedTurnId = 12;
    (window as any).__lastStreamEndedAt = Date.now();

    try {
      const prev = [
        makeUserMsg('q'),
        makeAssistantMsg('final answer with complete tail', {
          __turnId: 12,
          isStreaming: false,
          raw: {
            message: {
              content: [{ type: 'text', text: 'final answer with complete tail' }],
            },
          } as any,
        }),
      ];
      const next = [
        makeUserMsg('q'),
        makeAssistantMsg('final answer', {
          raw: {
            message: {
              content: [{ type: 'text', text: 'final answer' }],
            },
          } as any,
        }),
      ];

      const result = preserveRecentlyEndedStreamingTurn(prev, next, findLastAssistantIndex);
      expect(result[1].content).toBe('final answer with complete tail');
      expect(result[1].__turnId).toBe(12);
    } finally {
      (window as any).__lastStreamEndedTurnId = previousEndedTurnId;
      (window as any).__lastStreamEndedAt = previousEndedAt;
    }
  });

  it('does not preserve old assistant after the guard window expires', () => {
    const previousEndedAt = (window as any).__lastStreamEndedAt;
    const previousEndedTurnId = (window as any).__lastStreamEndedTurnId;
    (window as any).__lastStreamEndedTurnId = 12;
    (window as any).__lastStreamEndedAt = Date.now() - 3000;

    try {
      const prev = [
        makeAssistantMsg('final answer with complete tail', {
          __turnId: 12,
          isStreaming: false,
        }),
      ];
      const next = [makeAssistantMsg('final answer')];

      const result = preserveRecentlyEndedStreamingTurn(prev, next, findLastAssistantIndex);
      expect(result).toBe(next);
    } finally {
      (window as any).__lastStreamEndedTurnId = previousEndedTurnId;
      (window as any).__lastStreamEndedAt = previousEndedAt;
    }
  });
});
