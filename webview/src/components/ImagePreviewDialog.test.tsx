import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { ImagePreviewDialog } from './ImagePreviewDialog';

const contextMenuMocks = vi.hoisted(() => ({
  open: vi.fn(),
  close: vi.fn(),
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, fallback?: string) => fallback ?? key,
  }),
}));

vi.mock('./ContextMenu/ContextMenu', () => ({
  ContextMenu: () => null,
}));

vi.mock('../hooks/useContextMenu', () => ({
  useContextMenu: () => ({
    visible: false,
    x: 0,
    y: 0,
    imageTarget: null,
    open: contextMenuMocks.open,
    close: contextMenuMocks.close,
  }),
  copyImageSelection: vi.fn(),
}));

describe('ImagePreviewDialog', () => {
  beforeEach(() => {
    contextMenuMocks.open.mockReset();
    contextMenuMocks.close.mockReset();
  });

  /**
   * 验证预览层会拦截右键事件冒泡，只打开自己的图片菜单。
   * 这个断言用于防止会话列表级右键菜单再次参与预览层右键事件，避免出现“复制图片”重复两份的问题。
   */
  it('stops context menu bubbling from preview overlay', () => {
    const parentContextMenu = vi.fn();

    render(
      <div onContextMenu={parentContextMenu}>
        <ImagePreviewDialog
          image={{ src: 'data:image/png;base64,QUJD', mediaType: 'image/png', alt: 'demo' }}
          onClose={vi.fn()}
        />
      </div>,
    );

    fireEvent.contextMenu(screen.getByRole('img'));

    expect(contextMenuMocks.open).toHaveBeenCalledTimes(1);
    expect(parentContextMenu).not.toHaveBeenCalled();
  });
});
