import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClaudeMessage } from '../types';
import {
  copyMessageToClipboard,
  extractMarkdownContent,
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
   * 验证复制链路也能识别 Java/raw 侧的 provider 图片结构。
   * 当前会话消息在同步回填后，图片块常见格式是 `source.media_type/data`，
   * 如果复制侧不做同等归一化，就会出现“界面能看到图片，但复制时图片丢失”。
   */
  it('extracts images from provider-style raw image blocks', () => {
    const message: ClaudeMessage = {
      type: 'user',
      raw: {
        message: {
          content: [
            { type: 'text', text: 'provider image' },
            {
              type: 'image',
              source: {
                type: 'base64',
                media_type: 'image/png',
                data: 'QUJD',
              },
            },
          ],
        },
      } as any,
    };

    const payload = extractMessageRichContent(message);

    expect(payload).not.toBeNull();
    expect(payload?.text).toBe('provider image');
    expect(payload?.html).toContain('<img');
    expect(payload?.images).toHaveLength(1);
    expect(payload?.images?.[0]).toMatchObject({
      data: 'QUJD',
      mediaType: 'image/png',
    });
    expect(payload?.orderedBlocks).toEqual([
      { type: 'text', text: 'provider image' },
      { type: 'image', imageIndex: 0 },
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

  /**
   * 验证历史图片缓存失效后，复制链路会把缺失图片降级成可见文本提示。
   * 这样即使无法恢复真实附件，用户重新粘贴时也能知道哪一张历史图片已经失效。
   */
  it('serializes missing history images as text fallback blocks', () => {
    const message: ClaudeMessage = {
      type: 'user',
      raw: {
        content: [
          { type: 'text', text: 'before' },
          { type: 'image_missing', fileName: 'lost.png', mediaType: 'image/png' },
          { type: 'text', text: 'after' },
        ],
      } as any,
    };

    const payload = extractMessageRichContent(message);

    expect(payload).not.toBeNull();
    expect(payload?.text).toContain('[历史图片已失效：lost.png]');
    expect(payload?.images).toBeUndefined();
    expect(payload?.orderedBlocks).toEqual([
      { type: 'text', text: 'before' },
      { type: 'text', text: '[历史图片已失效：lost.png]' },
      { type: 'text', text: 'after' },
    ]);
  });

  /**
   * 验证纯文本提取链路也会为失效历史图片生成降级提示文本。
   * 这样 MessageItem 在只包含 `image_missing` 的消息上判断复制按钮显隐时，
   * 不会因为 Markdown 文本为空而错误隐藏复制入口。
   */
  it('extracts markdown fallback text for missing history images', () => {
    const message: ClaudeMessage = {
      type: 'user',
      raw: {
        content: [
          { type: 'image_missing', fileName: 'lost.png', mediaType: 'image/png' },
        ],
      } as any,
    };

    const markdown = extractMarkdownContent(message);

    expect(markdown).toBe('[历史图片已失效：lost.png]');
  });
});
