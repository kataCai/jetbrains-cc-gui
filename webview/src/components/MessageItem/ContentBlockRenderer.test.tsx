import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ClaudeContentBlock } from '../../types';
import { ContentBlockRenderer } from './ContentBlockRenderer';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('ContentBlockRenderer', () => {
  /**
   * 验证消息图片点击后会进入统一预览弹层，且不再展示显式复制按钮。
   * 这个用例用于覆盖聊天窗口里的图片预览入口，确保消息图片与 Markdown 图片保持一致交互。
   */
  it('renders image previews through the shared dialog for message images', () => {
    const block: ClaudeContentBlock = {
      type: 'image',
      src: 'data:image/png;base64,QUJD',
      alt: 'demo',
      mediaType: 'image/png',
    };

    const { container } = render(
      <ContentBlockRenderer
        block={block}
        messageIndex={0}
        messageType="assistant"
        isStreaming={false}
        isThinkingExpanded={false}
        isThinking={false}
        isLastMessage={false}
        t={((key: string) => key) as any}
        onToggleThinking={() => {}}
        findToolResult={() => null}
      />,
    );

    const image = container.querySelector('.message-image-block img');
    expect(image).toBeTruthy();

    fireEvent.click(image!);

    const copyButton = container.ownerDocument.querySelector('.image-preview-copy');
    const closeButton = container.ownerDocument.querySelector('.image-preview-close');

    expect(copyButton).toBeNull();
    expect(closeButton?.textContent).toBe('×');
  });
});
