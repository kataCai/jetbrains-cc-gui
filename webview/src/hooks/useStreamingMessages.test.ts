import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useStreamingMessages } from './useStreamingMessages';

describe('useStreamingMessages', () => {
  it('buildStreamingBlocks keeps thinking/text phase order around tool_use boundaries', () => {
    const { result } = renderHook(() => useStreamingMessages());

    result.current.streamingThinkingSegmentsRef.current = ['first thinking', 'second thinking'];
    result.current.streamingTextSegmentsRef.current = ['first text', 'second text'];

    const blocks = result.current.buildStreamingBlocks([
      { type: 'thinking', thinking: 'stale thinking' },
      { type: 'text', text: 'stale text' },
      { type: 'tool_use', id: 'tool-1', name: 'shell_command' },
      { type: 'thinking', thinking: 'stale thinking 2' },
      { type: 'text', text: 'stale text 2' },
    ]);

    expect(blocks).toEqual([
      { type: 'thinking', thinking: 'first thinking' },
      { type: 'text', text: 'first text' },
      { type: 'tool_use', id: 'tool-1', name: 'shell_command' },
      { type: 'thinking', thinking: 'second thinking' },
      { type: 'text', text: 'second text' },
    ]);
  });

  it('buildStreamingBlocks merges overlapping text tails to avoid duplicated markdown fence suffixes', () => {
    const { result } = renderHook(() => useStreamingMessages());

    result.current.streamingTextSegmentsRef.current = ['Hello\\n```ts\\nconst a = 1;\\n```'];

    const blocks = result.current.buildStreamingBlocks([
      { type: 'text', text: 'Hello\\n```ts\\n' },
    ]);

    expect(blocks).toEqual([
      { type: 'text', text: 'Hello\\n```ts\\nconst a = 1;\\n```' },
    ]);
  });

  it('buildStreamingBlocks collapses duplicated thinking/text phase replay around tool boundaries', () => {
    const { result } = renderHook(() => useStreamingMessages());

    result.current.streamingThinkingSegmentsRef.current = ['prep', 'prep'];
    result.current.streamingTextSegmentsRef.current = ['run tool', 'run tool'];

    const blocks = result.current.buildStreamingBlocks([
      { type: 'thinking', thinking: 'stale prep' },
      { type: 'text', text: 'stale run tool' },
      { type: 'thinking', thinking: 'stale prep replay' },
      { type: 'text', text: 'stale run tool replay' },
      { type: 'tool_use', id: 'tool-2', name: 'shell_command' },
    ]);

    expect(blocks).toEqual([
      { type: 'thinking', thinking: 'prep' },
      { type: 'text', text: 'run tool' },
      { type: 'tool_use', id: 'tool-2', name: 'shell_command' },
    ]);
  });

  it('patchAssistantForStreaming preserves backend-only trailing blocks outside current streaming range', () => {
    const { result } = renderHook(() => useStreamingMessages());

    result.current.streamingContentRef.current = 'updated intro';
    result.current.streamingTextSegmentsRef.current = ['updated intro'];

    const patched = result.current.patchAssistantForStreaming({
      type: 'assistant',
      content: 'updated intro',
      raw: {
        message: {
          content: [
            { type: 'text', text: 'stale intro' },
            { type: 'tool_use', id: 'tool-3', name: 'shell_command' },
            { type: 'tool_result', tool_use_id: 'tool-3', content: 'ok' },
            { type: 'text', text: 'backend final tail' },
          ],
        },
      },
    });

    expect((patched.raw as any).message.content).toEqual([
      { type: 'text', text: 'updated intro' },
      { type: 'tool_use', id: 'tool-3', name: 'shell_command' },
      { type: 'tool_result', tool_use_id: 'tool-3', content: 'ok' },
      { type: 'text', text: 'backend final tail' },
    ]);
  });
});
