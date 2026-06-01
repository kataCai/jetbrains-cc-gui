import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useMessageProcessing } from './useMessageProcessing.js';
import type { ClaudeMessage } from '../types/index.js';

const t = ((key: string) => key) as any;

const makeMessage = (
  type: ClaudeMessage['type'],
  content: string,
  extra?: Partial<ClaudeMessage>,
): ClaudeMessage => ({
  type,
  content,
  timestamp: new Date().toISOString(),
  ...extra,
});

/**
 * 渲染 useMessageProcessing，并固定 session 与翻译函数，便于各测试只关注消息输入和输出。
 *
 * @param messages 待处理的原始消息数组
 * @return Hook 渲染结果
 */
const renderProcessing = (messages: ClaudeMessage[]) => renderHook(() =>
  useMessageProcessing({ messages, currentSessionId: 'session-1', t }),
);

describe('useMessageProcessing', () => {
  it('keeps assistant turns separate when a hidden message sits between them', () => {
    // 隐藏 user 命令只能从界面中过滤，不能把前后两个 assistant turn 合并。
    const messages: ClaudeMessage[] = [
      makeMessage('assistant', 'First assistant reply', {
        __turnId: 1,
        raw: { content: [{ type: 'text', text: 'First assistant reply' }] } as any,
        timestamp: '2026-04-01T10:00:00.000Z',
      }),
      makeMessage('user', '', {
        raw: '<command-name>/aimax:auto</command-name>\n<command-args>follow up</command-args>' as any,
        timestamp: '2026-04-01T10:00:01.000Z',
      }),
      makeMessage('assistant', 'Second assistant reply', {
        __turnId: 2,
        raw: { content: [{ type: 'text', text: 'Second assistant reply' }] } as any,
        timestamp: '2026-04-01T10:00:02.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(2);
    expect(result.current.mergedMessages.map((message) => message.content)).toEqual([
      'First assistant reply',
      'Second assistant reply',
    ]);
    expect(result.current.mergedMessages.map((message) => message.__turnId)).toEqual([1, 2]);
  });

  it('converts completion fallback task notifications into task_notification messages', () => {
    // 完成态通知可能以 fallback user 消息进入前端，必须转换为 task_notification 展示。
    const notificationXml =
      '<task-notification><status>completed</status><summary>本轮任务已完成，可以继续追问。</summary></task-notification>';
    const messages: ClaudeMessage[] = [
      makeMessage('user', notificationXml, {
        raw: {
          content: [
            {
              type: 'text',
              text: notificationXml,
            },
          ],
          origin: { kind: 'task-notification' },
        } as any,
        timestamp: '2026-05-26T10:00:00.000Z',
      }),
    ];

    const { result } = renderHook(() =>
      useMessageProcessing({
        messages,
        currentSessionId: 'session-completion',
        t,
      }),
    );

    expect(result.current.mergedMessages).toHaveLength(1);
    expect(result.current.mergedMessages[0].type).toBe('task_notification');
  });

  it('renders /compact as user message with summary notification', () => {
    // /compact 命令应作为用户消息展示，stdout 隐藏，summary 独立转为通知。
    const messages: ClaudeMessage[] = [
      makeMessage('user', '', {
        raw: { message: { content: '<command-name>/compact</command-name><command-message>compact</command-message>' } },
        timestamp: '2026-01-01T10:00:00.000Z',
      }),
      makeMessage('user', '', {
        raw: { message: { content: '<local-command-stdout>Compacted Tip: test</local-command-stdout>' } },
        timestamp: '2026-01-01T10:00:01.000Z',
      }),
      makeMessage('user', '', {
        raw: { isCompactSummary: true, summarizeMetadata: { messagesSummarized: 10 }, message: { content: 'This session is being continued...' } },
        timestamp: '2026-01-01T10:00:02.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(2);
    expect(result.current.mergedMessages[0].type).toBe('user');
    expect(result.current.mergedMessages[1].type).toBe('notification');
  });

  it('shows auto-compact summary as notification', () => {
    // 自动压缩摘要不是用户文本，应作为通知展示并保留后续 assistant 消息。
    const messages: ClaudeMessage[] = [
      makeMessage('user', '', {
        raw: { isCompactSummary: true, message: { content: 'This session is being continued from a previous conversation...' } },
        timestamp: '2026-01-01T10:00:00.000Z',
      }),
      makeMessage('assistant', 'Hello', {
        raw: { content: [{ type: 'text', text: 'Hello' }] } as any,
        timestamp: '2026-01-01T10:00:01.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(2);
    expect(result.current.mergedMessages[0].type).toBe('notification');
    expect(result.current.mergedMessages[1].type).toBe('assistant');
  });

  it('keeps standalone stdout hidden', () => {
    // 单独 stdout 是 CLI 内部输出，不应污染聊天消息。
    const messages: ClaudeMessage[] = [
      makeMessage('user', 'hello', { timestamp: '2026-01-01T10:00:00.000Z' }),
      makeMessage('user', '', {
        raw: { message: { content: '<local-command-stdout>orphan output</local-command-stdout>' } },
        timestamp: '2026-01-01T10:00:01.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(1);
    expect(result.current.mergedMessages[0].content).toBe('hello');
  });

  it('swaps order when isCompactSummary precedes /compact', () => {
    // 真实 JSONL 中 summary 可能先于 /compact 写入，前端需要调整展示顺序。
    const messages: ClaudeMessage[] = [
      makeMessage('user', '', {
        raw: { isCompactSummary: true, summarizeMetadata: { messagesSummarized: 10 }, message: { content: 'Summary...' } },
        timestamp: '2026-01-01T10:00:02.000Z',
      }),
      makeMessage('user', '', {
        raw: { message: { content: '<command-name>/compact</command-name><command-message>compact</command-message>' } },
        timestamp: '2026-01-01T10:00:00.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(2);
    expect(result.current.mergedMessages[0].type).toBe('user');
    expect(result.current.mergedMessages[1].type).toBe('notification');
  });

  it('non-compact command with stdout remains hidden', () => {
    // 非 compact 命令的 stdout 仍然隐藏，只保留用户命令消息。
    const messages: ClaudeMessage[] = [
      makeMessage('user', '', {
        raw: { message: { content: '<command-name>/aimax:auto</command-name><command-message>aimax:auto</command-message>' } },
        timestamp: '2026-01-01T10:00:00.000Z',
      }),
      makeMessage('user', '', {
        raw: { message: { content: '<local-command-stdout>some output</local-command-stdout>' } },
        timestamp: '2026-01-01T10:00:01.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(1);
    expect(result.current.mergedMessages[0].type).toBe('user');
  });

  it('shows new user message once after compact command during streaming', () => {
    // compact 后的新用户消息只能出现一次，避免 XML 命令和 stdout 处理造成重复渲染。
    const messages: ClaudeMessage[] = [
      makeMessage('user', '', {
        raw: { message: { content: '<command-name>/compact</command-name><command-message>compact</command-message>' } },
        timestamp: '2026-01-01T10:00:00.000Z',
      }),
      makeMessage('user', '', {
        raw: { message: { content: '<local-command-stdout>Compacted Tip: ...</local-command-stdout>' } },
        timestamp: '2026-01-01T10:00:01.000Z',
      }),
      makeMessage('user', 'hello', {
        raw: { message: { content: [{ type: 'text', text: 'hello' }] } },
        timestamp: '2026-01-01T10:00:02.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(2);
    expect(result.current.mergedMessages[0].type).toBe('user');
    expect(result.current.mergedMessages[1].type).toBe('user');
    expect(result.current.mergedMessages[1].content).toBe('hello');
  });

  it('renders optimistic /compact as user message during streaming (no XML tags yet)', () => {
    // optimistic 消息还没有后端 XML 标签，应当按普通用户消息展示。
    const messages: ClaudeMessage[] = [
      makeMessage('user', '/compact', {
        raw: { message: { content: [{ type: 'text', text: '/compact' }] } },
        isOptimistic: true,
        timestamp: '2026-01-01T10:00:00.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(1);
    expect(result.current.mergedMessages[0].type).toBe('user');
    expect(result.current.mergedMessages[0].content).toBe('/compact');
  });

  it('does not group /compact followed by another slash command', () => {
    // /compact 后跟其它 slash command 时不能错误归组。
    const messages: ClaudeMessage[] = [
      makeMessage('user', '', {
        raw: { message: { content: '<command-name>/compact</command-name><command-message>compact</command-message>' } },
        timestamp: '2026-01-01T10:00:00.000Z',
      }),
      makeMessage('user', '/help', {
        raw: { message: { content: [{ type: 'text', text: '/help' }] } },
        timestamp: '2026-01-01T10:00:01.000Z',
      }),
    ];

    const { result } = renderProcessing(messages);

    expect(result.current.mergedMessages).toHaveLength(2);
    expect(result.current.mergedMessages[0].type).toBe('user');
    expect(result.current.mergedMessages[1].type).toBe('user');
    expect(result.current.mergedMessages[1].content).toBe('/help');
  });
});
