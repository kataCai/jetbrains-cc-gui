import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AttachmentList from './AttachmentList';
import type { Attachment } from './types';

const bridgeMocks = vi.hoisted(() => ({
  sendToJava: vi.fn(),
}));

vi.mock('../../utils/bridge', () => ({
  sendToJava: bridgeMocks.sendToJava,
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

const imageAttachment: Attachment = {
  id: 'attachment-1',
  fileName: 'demo.png',
  mediaType: 'image/png',
  data: 'QUJD',
};

describe('AttachmentList', () => {
  /**
   * 验证输入区图片卡片的移除按钮使用稳定的关闭字符。
   * 这个断言用于防止关闭图标再次被乱码字符污染，避免用户在附件区看到异常文案。
   */
  it('renders a stable close glyph for the attachment remove button', () => {
    const onRemove = vi.fn();

    render(
      <AttachmentList
        attachments={[imageAttachment]}
        onRemove={onRemove}
      />,
    );

    const removeButton = screen.getByRole('button');
    expect(removeButton.textContent).toBe('×');

    fireEvent.click(removeButton);
    expect(onRemove).toHaveBeenCalledWith('attachment-1');
  });

  /**
   * 验证输入区图片缩略图已经接入右键菜单宿主。
   * 该用例覆盖需求图 1：右键命中缩略图后应出现“复制图片”菜单项，点击后通过桥接层发起系统剪贴板写入。
   */
  it('shows a copy-image context menu item for image thumbnails and copies on click', async () => {
    render(
      <AttachmentList
        attachments={[imageAttachment]}
      />,
    );

    const thumbnail = screen.getByRole('img', { name: 'demo.png' });
    fireEvent.contextMenu(thumbnail, { clientX: 20, clientY: 24 });

    const copyItem = await screen.findByRole('menuitem', { name: 'contextMenu.copyImage' });
    fireEvent.click(copyItem);

    await waitFor(() => {
      expect(bridgeMocks.sendToJava).toHaveBeenCalledWith(
        'write_clipboard_image',
        expect.objectContaining({
          data: 'QUJD',
          mediaType: 'image/png',
          fileName: 'demo.png',
        }),
      );
    });
  });
});
