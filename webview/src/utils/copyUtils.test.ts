import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClaudeMessage } from '../types';
import {
  copyMessageToClipboard,
  extractMessageRichContent,
} from './copyUtils';

const bridgeMocks = vi.hoisted(() => ({
  sendToJava: vi.fn(),
}));

vi.mock('./bridge', async () => {
  const actual = await vi.importActual<typeof import('./bridge')>('./bridge');
  return {
    ...actual,
    sendToJava: bridgeMocks.sendToJava,
  };
});

describe('copyUtils rich clipboard', () => {
  beforeEach(() => {
    bridgeMocks.sendToJava.mockReset();
  });

  /**
   * 验证图文消息会同时生成 text 回退与 html 结构。
   * 该用例确保消息级复制不再像旧实现那样只抽取文本而忽略图片块。
   */
  it('extracts rich content for mixed text and image messages', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      raw: {
        content: [
          { type: 'text', text: 'hello image' },
          { type: 'image', src: 'data:image/png;base64,QUJD', mediaType: 'image/png', alt: 'demo' },
          { type: 'text', text: 'second line' },
          { type: 'image', src: 'data:image/png;base64,REVG', mediaType: 'image/png', alt: 'demo-2' },
        ],
      } as any,
    };

    const payload = extractMessageRichContent(message);

    expect(payload).not.toBeNull();
    expect(payload?.text).toBe('hello image\n\nsecond line');
    expect(payload?.html).toContain('<p>hello image</p>');
    expect(payload?.html).toContain('<img');
    expect(payload?.images).toHaveLength(2);
    expect(payload?.images?.map((item) => item.data)).toEqual(['QUJD', 'REVG']);
    expect(payload?.orderedBlocks).toEqual([
      { type: 'text', text: 'hello image' },
      { type: 'image', imageIndex: 0 },
      { type: 'text', text: 'second line' },
      { type: 'image', imageIndex: 1 },
    ]);
  });

  /**
   * 验证含图片的消息会走 Java 富剪贴板桥接，而不是退回浏览器纯文本复制。
   * 使用最小 PNG data URL，避免测试依赖 canvas 或外部图片加载。
   */
  it('copies image messages through the rich clipboard bridge', async () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      raw: {
        content: [
          { type: 'text', text: 'with image' },
          { type: 'image', src: 'data:image/png;base64,QUJD', mediaType: 'image/png', alt: 'demo' },
        ],
      } as any,
    };

    const success = await copyMessageToClipboard(message);

    expect(success).toBe(true);
    expect(bridgeMocks.sendToJava).toHaveBeenCalledTimes(1);
    expect(bridgeMocks.sendToJava.mock.calls[0][0]).toBe('write_clipboard_rich');
    expect(bridgeMocks.sendToJava.mock.calls[0][1].html).toContain('<img');
    expect(bridgeMocks.sendToJava.mock.calls[0][1].images).toHaveLength(1);
  });
});
