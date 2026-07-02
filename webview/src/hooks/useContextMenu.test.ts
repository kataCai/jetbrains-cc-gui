vi.mock('../utils/bridge.js', () => ({
  sendToJava: vi.fn(),
}));

vi.mock('../utils/debug.js', () => ({
  emitFrontendDiagnosticLog: vi.fn(),
}));

import { act, renderHook } from '@testing-library/react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { cutSelection, pasteAtCursor, useContextMenu } from './useContextMenu.js';
import { sendToJava } from '../utils/bridge.js';
import { emitFrontendDiagnosticLog } from '../utils/debug.js';

function mockSelection(options?: {
  text?: string;
  rangeCount?: number;
  range?: Range;
}) {
  const selection = {
    toString: vi.fn(() => options?.text ?? ''),
    rangeCount: options?.rangeCount ?? 0,
    getRangeAt: vi.fn(() => options?.range ?? document.createRange()),
    removeAllRanges: vi.fn(),
    addRange: vi.fn(),
  };
  vi.spyOn(window, 'getSelection').mockReturnValue(selection as unknown as Selection);
  return selection;
}

describe('useContextMenu', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('uses file tag path as copy target when right-clicking a file tag', () => {
    mockSelection({ text: '', rangeCount: 0 });
    const fileTag = document.createElement('span');
    fileTag.className = 'file-tag';
    fileTag.setAttribute('data-file-path', 'D:\\Code\\demo.ts#L3-L9');

    const { result } = renderHook(() => useContextMenu());

    act(() => {
      result.current.open({
        preventDefault: vi.fn(),
        clientX: 12,
        clientY: 24,
        target: fileTag,
      } as unknown as ReactMouseEvent);
    });

    expect(result.current.visible).toBe(true);
    expect(result.current.hasSelection).toBe(true);
    expect(result.current.selectedText).toBe('@D:\\Code\\demo.ts#L3-L9');
  });

  it('prefers actual text selection over file tag fallback', () => {
    const range = document.createRange();
    const selection = mockSelection({ text: 'selected text', rangeCount: 1, range });
    const fileTag = document.createElement('span');
    fileTag.className = 'file-tag';
    fileTag.setAttribute('data-file-path', 'D:\\Code\\demo.ts#L3-L9');

    const { result } = renderHook(() => useContextMenu());

    act(() => {
      result.current.open({
        preventDefault: vi.fn(),
        clientX: 1,
        clientY: 2,
        target: fileTag,
      } as unknown as ReactMouseEvent);
    });

    expect(result.current.hasSelection).toBe(true);
    expect(result.current.selectedText).toBe('selected text');
    expect(selection.getRangeAt).toHaveBeenCalledWith(0);
    expect(result.current.savedRange).not.toBeNull();
  });

  it('captures image copy metadata when right-clicking a copyable image node', () => {
    mockSelection({ text: '', rangeCount: 0 });
    const image = document.createElement('img');
    image.setAttribute('data-copy-image-src', 'data:image/png;base64,QUJD');
    image.setAttribute('data-copy-image-media-type', 'image/png');
    image.setAttribute('data-copy-image-file-name', 'demo.png');

    const { result } = renderHook(() => useContextMenu());

    act(() => {
      result.current.open({
        preventDefault: vi.fn(),
        clientX: 5,
        clientY: 6,
        target: image,
      } as unknown as ReactMouseEvent);
    });

    expect(result.current.visible).toBe(true);
    expect(result.current.imageTarget).not.toBeNull();
    expect(result.current.imageTarget?.src).toBe('data:image/png;base64,QUJD');
    expect(result.current.imageTarget?.fileName).toBe('demo.png');
  });

  it('cuts a file tag by copying its path and removing the tag', () => {
    const editable = document.createElement('div');
    const fileTag = document.createElement('span');
    const trailingText = document.createTextNode(' ');
    editable.append(fileTag, trailingText);
    document.body.appendChild(editable);

    fileTag.className = 'file-tag';
    fileTag.setAttribute('data-file-path', 'D:\\Code\\demo.ts#L3-L9');

    const selection = {
      removeAllRanges: vi.fn(),
      addRange: vi.fn(),
    };
    vi.spyOn(window, 'getSelection').mockReturnValue(selection as unknown as Selection);
    const focusSpy = vi.spyOn(editable, 'focus').mockImplementation(() => {});

    cutSelection(null, '@D:\\Code\\demo.ts#L3-L9', editable, fileTag);

    expect(sendToJava).toHaveBeenCalledWith('write_clipboard', '@D:\\Code\\demo.ts#L3-L9');
    expect(editable.querySelector('.file-tag')).toBeNull();
    expect(focusSpy).toHaveBeenCalled();
    expect(selection.removeAllRanges).toHaveBeenCalled();
    expect(selection.addRange).toHaveBeenCalledTimes(1);
  });

  /**
   * 验证右键 Paste 会先恢复保存的选区，再通过 Java 侧 rich 剪贴板协议请求统一粘贴。
   * 该断言用于覆盖“右键菜单只能走纯文本 read_clipboard”这一旧行为，确保后续实现不会回退到文本专用链路。
   */
  it('requests rich clipboard paste after restoring saved range', () => {
    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');
    const focusSpy = vi.spyOn(editable, 'focus').mockImplementation(() => {});
    const savedRange = document.createRange();
    const selection = {
      removeAllRanges: vi.fn(),
      addRange: vi.fn(),
    };
    vi.spyOn(window, 'getSelection').mockReturnValue(selection as unknown as Selection);

    pasteAtCursor(savedRange, editable, vi.fn());

    expect(focusSpy).toHaveBeenCalled();
    expect(selection.removeAllRanges).toHaveBeenCalledTimes(1);
    expect(selection.addRange).toHaveBeenCalledWith(savedRange);
    expect(sendToJava).toHaveBeenCalledWith('read_clipboard_rich', '');
    expect(emitFrontendDiagnosticLog).toHaveBeenCalledWith(
      'RichPaste.ContextMenu',
      'requested rich clipboard paste from context menu',
      {
        hasSavedRange: true,
        activeElementTag: 'DIV',
      },
    );
  });
});
