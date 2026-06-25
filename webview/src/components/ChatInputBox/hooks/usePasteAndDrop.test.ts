import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { Attachment } from '../types.js';
import { usePasteAndDrop } from './usePasteAndDrop.js';
import * as selectionUtils from '../utils/selectionUtils.js';

describe('usePasteAndDrop', () => {
  /**
   * 验证 Java 侧派发的图片粘贴事件会被输入框转换成附件。
   * 这条链路用于承接 IDE 粘贴动作派发的 `java-paste-image`，确保历史图文消息回贴时图片不会丢失。
   */
  it('converts java-paste-image events into image attachments', () => {
    let attachments: Attachment[] = [];
    const setInternalAttachments = vi.fn((updater: Attachment[] | ((prev: Attachment[]) => Attachment[])) => {
      attachments = typeof updater === 'function' ? updater(attachments) : updater;
    });

    renderHook(() => usePasteAndDrop({
      editableRef: { current: document.createElement('div') },
      pathMappingRef: { current: new Map<string, string>() },
      getTextContent: () => '',
      adjustHeight: vi.fn(),
      renderFileTags: vi.fn(),
      setHasContent: vi.fn(),
      setInternalAttachments,
      onInput: vi.fn(),
      closeAllCompletions: vi.fn(),
      handleInput: vi.fn(),
      flushInput: vi.fn(),
    }));

    act(() => {
      window.dispatchEvent(new CustomEvent('java-paste-image', {
        detail: {
          base64: 'QUJD',
          mediaType: 'image/png',
        },
      }));
    });

    expect(attachments).toHaveLength(1);
    expect(attachments[0].mediaType).toBe('image/png');
    expect(attachments[0].data).toBe('QUJD');
    expect(attachments[0].fileName.endsWith('.png')).toBe(true);
  });

  /**
   * 验证 Java 侧派发的富剪贴板事件会同时恢复文本和多张图片附件。
   * 这里重点覆盖“历史会话图文消息复制后回贴到输入框”链路，确保文本插入与图片顺序恢复不会互相短路。
   */
  it('restores text and ordered image attachments from java-paste-rich-content events', () => {
    const insertTextAtCursorSpy = vi.spyOn(selectionUtils, 'insertTextAtCursor').mockImplementation(() => true);
    let attachments: Attachment[] = [];
    const setInternalAttachments = vi.fn((updater: Attachment[] | ((prev: Attachment[]) => Attachment[])) => {
      attachments = typeof updater === 'function' ? updater(attachments) : updater;
    });
    const handleInput = vi.fn();
    const flushInput = vi.fn();

    renderHook(() => usePasteAndDrop({
      editableRef: { current: document.createElement('div') },
      pathMappingRef: { current: new Map<string, string>() },
      getTextContent: () => '',
      adjustHeight: vi.fn(),
      renderFileTags: vi.fn(),
      setHasContent: vi.fn(),
      setInternalAttachments,
      onInput: vi.fn(),
      closeAllCompletions: vi.fn(),
      handleInput,
      flushInput,
    }));

    act(() => {
      window.dispatchEvent(new CustomEvent('java-paste-rich-content', {
        detail: {
          text: 'first paragraph\n\nsecond paragraph',
          images: [
            { data: 'QUJD', mediaType: 'image/png', fileName: 'first.png' },
            { data: 'REVG', mediaType: 'image/png', fileName: 'second.png' },
          ],
          orderedBlocks: [
            { type: 'text', text: 'first paragraph' },
            { type: 'image', imageIndex: 0 },
            { type: 'text', text: 'second paragraph' },
            { type: 'image', imageIndex: 1 },
          ],
        },
      }));
    });

    expect(insertTextAtCursorSpy).toHaveBeenCalledWith('first paragraph\n\nsecond paragraph', expect.anything());
    expect(handleInput).toHaveBeenCalledWith(false);
    expect(flushInput).toHaveBeenCalledTimes(1);
    expect(attachments.map((item) => item.fileName)).toEqual(['first.png', 'second.png']);
    expect(attachments.map((item) => item.data)).toEqual(['QUJD', 'REVG']);

    insertTextAtCursorSpy.mockRestore();
  });
});
