import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Attachment } from '../types.js';
import { usePasteAndDrop } from './usePasteAndDrop.js';
import * as selectionUtils from '../utils/selectionUtils.js';
import { sendToJava } from '../../../utils/bridge.js';

vi.mock('../../../utils/bridge.js', () => ({
  sendToJava: vi.fn(),
}));

describe('usePasteAndDrop', () => {
  afterEach(() => {
    vi.mocked(sendToJava).mockReset();
    vi.useRealTimers();
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
   * 验证 WebView 键盘 paste 在同时存在文本和图片 item 时，会优先切到 Java rich bridge。
   * 该场景对应插件自己写入的系统剪贴板被浏览器原生 paste 事件消费时，
   * 新实现不应再立刻执行 native 单图恢复，否则仍会重现“只保留首图”。
   */
  it('requests rich bridge before restoring mixed native paste content', async () => {
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
    expect(sendToJava).toHaveBeenCalledTimes(1);
    expect(sendToJava).toHaveBeenCalledWith(
      'read_clipboard_rich',
      expect.objectContaining({
        trigger: 'native-paste',
        nativeItemTypes: ['text/plain', 'image/png'],
      }),
    );
    expect(insertTextAtCursorSpy).not.toHaveBeenCalled();
    expect(handleInput).not.toHaveBeenCalled();
    expect(flushInput).not.toHaveBeenCalled();
    expect(attachments).toHaveLength(0);
  });

  /**
   * 验证键盘 `Ctrl+V` 等待 bridge 响应后，可以恢复多图附件和文本。
   * 该用例要求前端根据 requestId 识别本次键盘粘贴请求，避免把任意 rich paste 事件都当作当前 pending 请求。
   */
  it('restores text and all attachments after keyboard paste rich bridge response arrives', async () => {
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

    const file = new File(['image-bytes'], 'mixed.png', { type: 'image/png' });
    const getData = vi.fn((type: string) => {
      if (type === 'text/plain') return 'mixed clipboard text';
      return '';
    });
    const event = {
      preventDefault: vi.fn(),
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

    const pendingRequestId = vi.mocked(sendToJava).mock.calls[0]?.[1]?.requestId;
    expect(typeof pendingRequestId).toBe('string');
    expect(pendingRequestId).toBeTruthy();

    act(() => {
      window.dispatchEvent(new CustomEvent('java-paste-rich-content', {
        detail: {
          requestId: pendingRequestId,
          source: 'rich-json',
          text: 'first paragraph\n\nsecond paragraph',
          images: [
            { data: 'QUJD', mediaType: 'image/png', fileName: 'first.png' },
            { data: 'REVG', mediaType: 'image/png', fileName: 'second.png' },
            { data: 'R0hJ', mediaType: 'image/png', fileName: 'third.png' },
          ],
          orderedBlocks: [
            { type: 'text', text: 'first paragraph' },
            { type: 'image', imageIndex: 0 },
            { type: 'text', text: 'second paragraph' },
            { type: 'image', imageIndex: 1 },
            { type: 'image', imageIndex: 2 },
          ],
        },
      }));
    });

    expect(insertTextAtCursorSpy).toHaveBeenCalledWith('first paragraph\n\nsecond paragraph', editable);
    expect(handleInput).toHaveBeenCalledWith(false);
    expect(flushInput).toHaveBeenCalledTimes(1);
    expect(attachments.map((item) => item.fileName)).toEqual(['first.png', 'second.png', 'third.png']);
    expect(attachments.map((item) => item.data)).toEqual(['QUJD', 'REVG', 'R0hJ']);
  });

  /**
   * 验证 bridge 超时时仍会回退到当前 native 单图/文本恢复能力。
   * 该兜底用于 Java bridge 不可用、响应异常或被限流时，避免把现有可用粘贴能力一起打坏。
   */
  it('falls back to native mixed paste when rich clipboard bridge does not respond in time', async () => {
    vi.useFakeTimers();
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
    const event = {
      preventDefault: vi.fn(),
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
        getData: (type: string) => (type === 'text/plain' ? 'mixed clipboard text' : ''),
      },
    } as unknown as React.ClipboardEvent;

    await act(async () => {
      result.current.handlePaste(event);
      await vi.runAllTimersAsync();
      await Promise.resolve();
    });

    expect(sendToJava).toHaveBeenCalledWith(
      'read_clipboard_rich',
      expect.objectContaining({ trigger: 'native-paste' }),
    );
    expect(insertTextAtCursorSpy).toHaveBeenCalledWith('mixed clipboard text', editable);
    expect(handleInput).toHaveBeenCalledWith(false);
    expect(flushInput).toHaveBeenCalledTimes(1);
    expect(attachments).toHaveLength(1);
    expect(attachments[0]).toMatchObject({
      fileName: 'mixed.png',
      mediaType: 'image/png',
      data: 'QUJD',
    });

    vi.useRealTimers();
  });
});
