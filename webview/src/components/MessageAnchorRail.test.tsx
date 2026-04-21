import { render, screen } from '@testing-library/react';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import type { ClaudeMessage } from '../types';
import { MessageAnchorRail } from './MessageAnchorRail';

function makeMessage(
  type: ClaudeMessage['type'],
  content: string,
  raw?: ClaudeMessage['raw'],
): ClaudeMessage {
  return {
    type,
    content,
    raw,
    timestamp: new Date().toISOString(),
  };
}

describe('MessageAnchorRail', () => {
  beforeAll(() => {
    class MockIntersectionObserver {
      observe() {}
      unobserve() {}
      disconnect() {}
      takeRecords() { return []; }
    }

    vi.stubGlobal('IntersectionObserver', MockIntersectionObserver);
  });

  afterAll(() => {
    vi.unstubAllGlobals();
  });

  it('skips tool_result-only user messages when building clickable question anchors', () => {
    const toolResultMessage = makeMessage(
      'user',
      '[tool_result]',
      {
        content: [{ type: 'tool_result', tool_use_id: 'toolu_test' }],
      } as ClaudeMessage['raw'],
    );
    const messages: ClaudeMessage[] = [
      makeMessage('user', 'Question 1', { uuid: 'question-1' } as ClaudeMessage['raw']),
      toolResultMessage,
      makeMessage('user', 'Question 2', { uuid: 'question-2' } as ClaudeMessage['raw']),
    ];

    render(
      <MessageAnchorRail
        messages={messages}
        containerRef={{ current: document.createElement('div') }}
        messageNodeMap={{ current: new Map() }}
      />
    );

    expect(screen.getAllByRole('button')).toHaveLength(2);
  });
});
