import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Attachment } from '../types.js';
import { usePasteAndDrop } from './usePasteAndDrop.js';
import * as selectionUtils from '../utils/selectionUtils.js';

describe('usePasteAndDrop', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

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

  });

  /**
   * 验证富回贴在没有有效选区时，文本仍会降级追加到输入框末尾。
   * 该场景对应历史会话重绑后焦点不在输入框内，旧实现会导致图片恢复成功但文本静默丢失。
   */
  it('falls back to appending text when rich paste has no valid selection', () => {
    const insertTextAtCursorSpy = vi.spyOn(selectionUtils, 'insertTextAtCursor').mockImplementation(() => false);
    let attachments: Attachment[] = [];
    const setInternalAttachments = vi.fn((updater: Attachment[] | ((prev: Attachment[]) => Attachment[])) => {
      attachments = typeof updater === 'function' ? updater(attachments) : updater;
    });
    const handleInput = vi.fn();
    const flushInput = vi.fn();
    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');
    const focusSpy = vi.spyOn(editable, 'focus').mockImplementation(() => {});
    const dispatchEventSpy = vi.spyOn(editable, 'dispatchEvent');

    renderHook(() => usePasteAndDrop({
      editableRef: { current: editable },
      pathMappingRef: { current: new Map<string, string>() },
      getTextContent: () => editable.textContent || '',
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
          text: 'fallback text',
          images: [
            { data: 'QUJD', mediaType: 'image/png', fileName: 'first.png' },
          ],
          orderedBlocks: [
            { type: 'text', text: 'fallback text' },
            { type: 'image', imageIndex: 0 },
          ],
        },
      }));
    });

    expect(insertTextAtCursorSpy).toHaveBeenCalledWith('fallback text', editable);
    expect(focusSpy).toHaveBeenCalled();
    expect(editable.textContent).toBe('fallback text');
    expect(dispatchEventSpy).toHaveBeenCalled();
    expect(handleInput).toHaveBeenCalledWith(false);
    expect(flushInput).toHaveBeenCalledTimes(1);
    expect(attachments.map((item) => item.fileName)).toEqual(['first.png']);
  });

  /**
   * 验证 WebView 原生 paste 在同时存在文本和图片 item 时，不会因为命中图片而直接短路。
   * 该场景对应插件自己写入的系统剪贴板被浏览器原生 paste 事件消费时，
   * 旧实现会只恢复图片附件，文本完全丢失。
   */
  it('restores both text and images from native paste when clipboard contains mixed items', async () => {
    const insertTextAtCursorSpy = vi.spyOn(selectionUtils, 'insertTextAtCursor').mockImplementation(() => true);
    let attachments: Attachment[] = [];
    const setInternalAttachments = vi.fn((updater: Attachment[] | ((prev: Attachment[]) => Attachment[])) => {
      attachments = typeof updater === 'function' ? updater(attachments) : updater;
    });
    const handleInput = vi.fn();
    const flushInput = vi.fn();
    const editable = document.createElement('div');
    editable.setAttribute('contenteditable', 'true');

    const { result } = renderHook(() => usePasteAndDrop({
      editableRef: { current: editable },
      pathMappingRef: { current: new Map<string, string>() },
      getTextContent: () => editable.textContent || '',
      adjustHeight: vi.fn(),
      renderFileTags: vi.fn(),
      setHasContent: vi.fn(),
      setInternalAttachments,
      onInput: vi.fn(),
      closeAllCompletions: vi.fn(),
      handleInput,
      flushInput,
    }));

    const readAsDataURL = vi.fn(function(this: FileReader) {
      Object.defineProperty(this, 'result', {
        configurable: true,
        value: 'data:image/png;base64,QUJD',
      });
      const loadEvent = { target: this, currentTarget: this } as unknown as ProgressEvent<FileReader>;
      this.onload?.call(this, loadEvent);
    });
    vi.spyOn(FileReader.prototype, 'readAsDataURL').mockImplementation(readAsDataURL);

    const file = new File(['image-bytes'], 'mixed.png', { type: 'image/png' });
    const preventDefault = vi.fn();
    const getData = vi.fn((type: string) => {
      if (type === 'text/plain') return 'mixed clipboard text';
      return '';
    });
    const event = {
      preventDefault,
      clipboardData: {
        items: [
          {
            kind: 'string',
            type: 'text/plain',
            getAsFile: () => null,
          },
          {
            kind: 'file',
            type: 'image/png',
            getAsFile: () => file,
          },
        ],
        getData,
      },
    } as unknown as React.ClipboardEvent;

    await act(async () => {
      result.current.handlePaste(event);
      await Promise.resolve();
    });

    expect(preventDefault).toHaveBeenCalled();
    expect(insertTextAtCursorSpy).toHaveBeenCalledWith('mixed clipboard text', editable);
    expect(handleInput).toHaveBeenCalledWith(false);
    expect(flushInput).toHaveBeenCalledTimes(1);
    expect(attachments).toHaveLength(1);
    expect(attachments[0]).toMatchObject({
      fileName: 'mixed.png',
      mediaType: 'image/png',
      data: 'QUJD',
    });
  });
});
