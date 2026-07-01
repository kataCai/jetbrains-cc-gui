import { useCallback, useEffect } from 'react';
import type { Attachment } from '../types.js';
import { generateId } from '../utils/generateId.js';
import { insertTextAtCursor } from '../utils/selectionUtils.js';
import { perfTimer } from '../../../utils/debug.js';
import type { ClipboardImagePayload, ClipboardRichBlock } from '../../../utils/imageClipboard.js';

declare global {
  interface Window {
    getClipboardFilePath?: () => Promise<string>;
  }
}

interface UsePasteAndDropOptions {
  editableRef: React.RefObject<HTMLDivElement | null>;
  pathMappingRef: React.MutableRefObject<Map<string, string>>;
  getTextContent: () => string;
  adjustHeight: () => void;
  renderFileTags: () => void;
  setHasContent: (hasContent: boolean) => void;
  setInternalAttachments: React.Dispatch<React.SetStateAction<Attachment[]>>;
  onInput?: (content: string) => void;
  closeAllCompletions: () => void;
  handleInput: (isComposingFromEvent?: boolean) => void;
  /** Immediately flush pending debounced onInput to sync parent state */
  flushInput: () => void;
}

interface UsePasteAndDropReturn {
  /** Handle paste event - detect images and plain text */
  handlePaste: (e: React.ClipboardEvent) => void;
  /** Handle drag over event */
  handleDragOver: (e: React.DragEvent) => void;
  /** Handle drop event - detect images and file paths */
  handleDrop: (e: React.DragEvent) => void;
}

interface JavaRichClipboardPayload {
  text?: string;
  image?: ClipboardImagePayload;
  images?: ClipboardImagePayload[];
  orderedBlocks?: ClipboardRichBlock[];
}

const RICH_PASTE_APPLY_LOG_PREFIX = '[RichPaste][Apply]';
const RICH_PASTE_NATIVE_LOG_PREFIX = '[RichPaste][Native]';

/**
 * 输出富回贴恢复链路的结构摘要。
 * 这里只记录文本长度、图片数量和选区状态，避免把用户正文原样打印到控制台。
 *
 * @param message 日志说明
 * @param details 附加结构化上下文
 * @return 无返回值
 */
function logRichPasteApply(message: string, details?: Record<string, unknown>): void {
  // 这里改为 info，是为了让 JCEF console 日志在默认 IDEA 日志级别下可见，便于定位 rich paste 是否真正落地。
  console.info(RICH_PASTE_APPLY_LOG_PREFIX, message, details ?? {});
}

/**
 * 输出浏览器原生 paste 分支日志。
 * 重点记录 clipboard item 类型、分支选择和文本/图片是否同时存在，便于区分是否绕过了 Java rich paste。
 *
 * @param message 日志说明
 * @param details 附加结构化上下文
 * @return 无返回值
 */
function logNativePaste(message: string, details?: Record<string, unknown>): void {
  // 原生日志同样提升到 info，避免 Ctrl+V 失败时只在前端 debug 通道里丢失关键证据。
  console.info(RICH_PASTE_NATIVE_LOG_PREFIX, message, details ?? {});
}

/**
 * 把输入框光标移动到末尾，作为 rich paste 文本恢复的兜底选区。
 * 历史会话重绑后，焦点常常不在输入框内；若不主动补选区，`insertTextAtCursor` 会直接失败。
 *
 * @param editable 输入框节点
 * @return 是否成功创建末尾选区
 */
function ensureEditableEndSelection(editable: HTMLDivElement | null): boolean {
  if (!editable) {
    return false;
  }

  editable.focus();
  const selection = window.getSelection();
  if (!selection) {
    return false;
  }

  const range = document.createRange();
  range.selectNodeContents(editable);
  range.collapse(false);
  selection.removeAllRanges();
  selection.addRange(range);
  return true;
}

/**
 * 当常规选区插入失败时，把文本直接追加到输入框末尾。
 * 这里同时补发 `input` 事件和末尾选区，保证输入状态与后续继续输入行为保持一致。
 *
 * @param text 待恢复文本
 * @param editable 输入框节点
 * @return 是否已成功降级恢复文本
 */
function appendTextToEditableFallback(text: string, editable: HTMLDivElement | null): boolean {
  if (!editable) {
    return false;
  }

  const fragment = document.createDocumentFragment();
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (i > 0) {
      fragment.appendChild(document.createElement('br'));
    }
    if (lines[i]) {
      fragment.appendChild(document.createTextNode(lines[i]));
    }
  }

  editable.appendChild(fragment);
  ensureEditableEndSelection(editable);
  editable.dispatchEvent(new InputEvent('input', {
    bubbles: true,
    cancelable: true,
    inputType: 'insertText',
    data: text,
  }));
  return true;
}

/**
 * 恢复原生 paste 提供的纯文本内容。
 * 该逻辑与 rich paste 文本恢复保持一致：优先走当前选区，失败后再降级到末尾追加。
 *
 * @param text 原生粘贴文本
 * @param editable 输入框节点
 * @param handleInput 输入同步回调
 * @param flushInput 立即同步父状态
 * @return 是否成功恢复文本
 */
function restoreNativePasteText(
  text: string,
  editable: HTMLDivElement | null,
  handleInput: (isComposingFromEvent?: boolean) => void,
  flushInput: () => void,
): boolean {
  if (!text.trim()) {
    return false;
  }

  const selection = window.getSelection();
  const hadSelectionInsideEditable = Boolean(
    editable
    && selection
    && selection.rangeCount > 0
    && editable.contains(selection.getRangeAt(0).commonAncestorContainer)
  );
  if (!hadSelectionInsideEditable) {
    ensureEditableEndSelection(editable);
  }

  const inserted = insertTextAtCursor(text, editable);
  const restored = inserted || appendTextToEditableFallback(text, editable);
  logNativePaste('restored native paste text', {
    inserted,
    restored,
    hadSelectionInsideEditable,
    textLength: text.length,
  });

  if (restored) {
    handleInput(false);
    flushInput();
  }
  return restored;
}

/**
 * usePasteAndDrop - Handle paste and drag-drop operations
 *
 * Features:
 * - Paste images as attachments (Base64 encoded)
 * - Paste text including file paths
 * - Drag and drop files/images
 * - Auto-create file references from dropped paths
 */
export function usePasteAndDrop({
  editableRef,
  pathMappingRef,
  getTextContent,
  adjustHeight,
  renderFileTags,
  setHasContent,
  setInternalAttachments,
  onInput,
  closeAllCompletions,
  handleInput,
  flushInput,
}: UsePasteAndDropOptions): UsePasteAndDropReturn {
  /**
   * 将 Java 侧图片负载转换为输入框附件。
   * 保留原始文件名，缺失时再按媒体类型兜底扩展名，避免历史会话回贴后附件列表变得难以辨认。
   *
   * @param payload Java 侧派发的单张图片负载
   * @param index 当前图片在富剪贴板中的顺序索引
   * @return 对应的输入框附件；若负载无效则返回 null
   */
  const buildAttachmentFromClipboardPayload = useCallback((payload: ClipboardImagePayload | undefined, index: number): Attachment | null => {
    if (!payload?.data) {
      return null;
    }
    const mediaType = payload.mediaType || 'image/png';
    const ext = mediaType.split('/')[1] || 'png';
    return {
      id: generateId(),
      fileName: payload.fileName || `pasted-image-${Date.now()}-${index}.${ext}`,
      mediaType,
      data: payload.data,
    };
  }, []);

  /**
   * 恢复 Java 富剪贴板事件中的文本与多图附件。
   * 输入框当前不支持图片内联，因此这里采用“文本照常插入 + 附件区按历史顺序追加图片”的策略恢复会话复制结果。
   *
   * @param payload Java 侧派发的富剪贴板负载
   */
  const applyRichClipboardPayload = useCallback((payload: JavaRichClipboardPayload) => {
    const text = payload.text ?? '';
    const imagePayloads = payload.images?.length
      ? payload.images
      : (payload.image ? [payload.image] : []);

    logRichPasteApply('received rich clipboard payload', {
      hasText: Boolean(text.trim()),
      textLength: text.length,
      imageCount: imagePayloads.length,
      orderedBlockCount: payload.orderedBlocks?.length ?? 0,
    });

    if (text.trim()) {
      const editable = editableRef.current;
      const selection = window.getSelection();
      const hadSelectionInsideEditable = Boolean(
        editable
        && selection
        && selection.rangeCount > 0
        && editable.contains(selection.getRangeAt(0).commonAncestorContainer)
      );
      if (!hadSelectionInsideEditable) {
        ensureEditableEndSelection(editable);
      }

      const inserted = insertTextAtCursor(text, editable);
      logRichPasteApply('attempted rich clipboard text insertion', {
        inserted,
        hadSelectionInsideEditable,
        activeElementTag: document.activeElement?.tagName ?? null,
        selectionRangeCount: window.getSelection()?.rangeCount ?? 0,
      });

      const textRestored = inserted || appendTextToEditableFallback(text, editable);
      if (!inserted) {
        logRichPasteApply('rich clipboard text fallback result', {
          fallbackSucceeded: textRestored,
          activeElementTag: document.activeElement?.tagName ?? null,
          hasSelection: Boolean(window.getSelection()),
        });
      }

      if (textRestored) {
        handleInput(false);
        flushInput();
      }
    }

    if (imagePayloads.length === 0) {
      logRichPasteApply('rich clipboard payload contains no images to restore');
      return;
    }

    const attachments = imagePayloads
      .map((item, index) => buildAttachmentFromClipboardPayload(item, index))
      .filter((item): item is Attachment => Boolean(item));
    if (attachments.length === 0) {
      logRichPasteApply('skip attachment restore because no valid image payloads were converted');
      return;
    }

    /**
     * 当前附件区天然是线性列表，因此这里优先恢复图片之间的相对顺序。
     * orderedBlocks 仍会继续透传，后续如果输入框支持更细粒度图文混排，可直接在此扩展恢复逻辑。
     */
    const orderedAttachments = payload.orderedBlocks?.length
      ? payload.orderedBlocks
          .filter((block): block is Extract<ClipboardRichBlock, { type: 'image' }> => block.type === 'image')
          .map((block) => attachments[block.imageIndex])
          .filter((item): item is Attachment => Boolean(item))
      : attachments;

    setInternalAttachments((prev) => [...prev, ...orderedAttachments]);
    logRichPasteApply('restored attachments from rich clipboard payload', {
      attachmentCount: orderedAttachments.length,
      fileNames: orderedAttachments.map((item) => item.fileName),
    });
  }, [buildAttachmentFromClipboardPayload, editableRef, flushInput, handleInput, setInternalAttachments]);

  /**
   * 处理浏览器原生 paste 事件里的图片 item。
   * 当系统剪贴板同时存在文本和图片时，不再直接短路，而是把图片恢复成附件后继续让文本分支执行。
   *
   * @param items Clipboard items
   * @return Promise<void> 用于统一等待 FileReader 完成
   */
  const restoreNativePasteImages = useCallback(async (items: DataTransferItemList | DataTransferItem[]) => {
    const imageItems = Array.from(items).filter((item) => item.type.startsWith('image/'));
    if (imageItems.length === 0) {
      return;
    }

    const restoredAttachments = await Promise.all(imageItems.map((item, index) => {
      const blob = item.getAsFile();
      if (!blob) {
        return Promise.resolve<Attachment | null>(null);
      }

      return new Promise<Attachment | null>((resolve) => {
        const reader = new FileReader();
        reader.onload = () => {
          const result = typeof reader.result === 'string' ? reader.result : '';
          const base64 = result.includes(',') ? result.split(',')[1] : '';
          if (!base64) {
            resolve(null);
            return;
          }
          const mediaType = blob.type || item.type || 'image/png';
          const ext = (() => {
            if (mediaType && mediaType.includes('/')) {
              return mediaType.split('/')[1];
            }
            const name = blob.name || '';
            const m = name.match(/\.([a-zA-Z0-9]+)$/);
            return m ? m[1] : 'png';
          })();
          resolve({
            id: generateId(),
            fileName: blob.name || `pasted-image-${Date.now()}-${index}.${ext}`,
            mediaType,
            data: base64,
          });
        };
        reader.onerror = () => resolve(null);
        reader.readAsDataURL(blob);
      });
    }));

    const attachments = restoredAttachments.filter((item): item is Attachment => Boolean(item));
    if (attachments.length > 0) {
      setInternalAttachments((prev) => [...prev, ...attachments]);
    }
    logNativePaste('restored native paste images', {
      requestedImageItemCount: imageItems.length,
      restoredAttachmentCount: attachments.length,
      fileNames: attachments.map((item) => item.fileName),
    });
  }, [setInternalAttachments]);

  /**
   * Handle paste event - detect images and plain text
   */
  const handlePaste = useCallback(
    (e: React.ClipboardEvent) => {
      const items = e.clipboardData?.items;

      if (!items) {
        return;
      }

      const itemTypes = Array.from(items).map((item) => item.type || `${item.kind}:unknown`);
      const hasImage = Array.from(items).some((item) => item.type.startsWith('image/'));
      const text =
        e.clipboardData.getData('text/plain') ||
        e.clipboardData.getData('text/uri-list') ||
        e.clipboardData.getData('text/html');
      const hasText = Boolean(text && text.trim());

      logNativePaste('received native paste event', {
        itemTypes,
        hasImage,
        hasText,
      });

      if (hasImage) {
        e.preventDefault();
        void restoreNativePasteImages(items);

        if (hasText) {
          restoreNativePasteText(text, editableRef.current, handleInput, flushInput);
        }
        return;
      }

      // If no image, try to get text or file path
      if (!hasImage) {
        e.preventDefault();

        // If still no text, try to get filename/path from file type item
        if (!text) {
          // Check if there's a file type item
          let hasFileItem = false;
          for (let i = 0; i < items.length; i++) {
            const item = items[i];
            if (item.kind === 'file') {
              hasFileItem = true;
              break;
            }
          }

          // If there's a file type item, try to get full path via Java side
          if (hasFileItem && window.getClipboardFilePath) {
            window
              .getClipboardFilePath()
              .then((fullPath: string) => {
                if (fullPath && fullPath.trim()) {
                  // Insert full path using modern Selection API
                  insertTextAtCursor(fullPath, editableRef.current);
                  // Bypass IME guard (isComposingRef may be stale after recent compositionEnd)
                  handleInput(false);
                  // Immediately sync parent state without waiting for debounce
                  flushInput();
                }
              })
              .catch(() => {
                // Ignore errors
              });
            return;
          }
        }

        if (text && text.trim()) {
          const timer = perfTimer('handlePaste-text');
          timer.mark(`text-length:${text.length}`);

          restoreNativePasteText(text, editableRef.current, handleInput, flushInput);
          timer.mark('insertText');
          timer.mark('handleInput');

          // Scroll to make cursor visible after paste
          // Use requestAnimationFrame to ensure DOM updates are complete
          requestAnimationFrame(() => {
            // Get the wrapper element that has overflow scroll
            const wrapper = editableRef.current?.parentElement;
            if (wrapper && editableRef.current) {
              // Scroll wrapper to bottom to show pasted content
              wrapper.scrollTop = wrapper.scrollHeight;
            }
          });

          timer.end();
        }
      }
    },
    [editableRef, flushInput, handleInput, restoreNativePasteImages]
  );

  /**
   * Handle drag over event
   */
  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    // Set drop effect to copy
    e.dataTransfer.dropEffect = 'copy';
  }, []);

  /**
   * Handle drop event - detect images and file paths
   */
  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();

      // First get text content (file path)
      const text = e.dataTransfer?.getData('text/plain');

      // Then check file objects
      const files = e.dataTransfer?.files;

      // Check if there are actual image file objects
      let hasImageFile = false;
      if (files && files.length > 0) {
        for (let i = 0; i < files.length; i++) {
          const file = files[i];

          // Only process image files
          if (file.type.startsWith('image/')) {
            hasImageFile = true;
            const reader = new FileReader();
            reader.onload = () => {
              const base64 = (reader.result as string).split(',')[1];
              const ext = (() => {
                if (file.type && file.type.includes('/')) {
                  return file.type.split('/')[1];
                }
                const m = file.name.match(/\.([a-zA-Z0-9]+)$/);
                return m ? m[1] : 'png';
              })();
              const attachment: Attachment = {
                id: generateId(),
                fileName: file.name || `dropped-image-${Date.now()}.${ext}`,
                mediaType: file.type || 'image/png',
                data: base64,
              };

              setInternalAttachments((prev) => [...prev, attachment]);
            };
            reader.readAsDataURL(file);
          }
        }
      }

      // If there are image files, don't process text
      if (hasImageFile) {
        return;
      }

      // No image files, process text (file path or other text)
      if (text && text.trim()) {
        // Extract file path and add to path mapping
        const filePath = text.trim();
        const fileName = filePath.split(/[/\\]/).pop() || filePath;

        // Add path to pathMappingRef to make it a "valid reference"
        pathMappingRef.current.set(fileName, filePath);
        pathMappingRef.current.set(filePath, filePath);

        // Auto-add @ prefix (if not already present), and add space to trigger rendering
        const textToInsert = (text.startsWith('@') ? text : `@${text}`) + ' ';

        // Get current cursor position
        const selection = window.getSelection();
        if (selection && selection.rangeCount > 0 && editableRef.current) {
          // Ensure cursor is inside input box
          if (editableRef.current.contains(selection.anchorNode)) {
            // Use modern API to insert text
            const range = selection.getRangeAt(0);
            range.deleteContents();
            const textNode = document.createTextNode(textToInsert);
            range.insertNode(textNode);

            // Move cursor after inserted text
            range.setStartAfter(textNode);
            range.collapse(true);
            selection.removeAllRanges();
            selection.addRange(range);
          } else {
            // Cursor not inside input box, append to end
            // Use appendChild instead of innerText to avoid breaking existing file tags
            const textNode = document.createTextNode(textToInsert);
            editableRef.current.appendChild(textNode);

            // Move cursor to end
            const range = document.createRange();
            range.setStartAfter(textNode);
            range.collapse(true);
            selection.removeAllRanges();
            selection.addRange(range);
          }
        } else {
          // No selection, append to end
          if (editableRef.current) {
            const textNode = document.createTextNode(textToInsert);
            editableRef.current.appendChild(textNode);
          }
        }

        // Close all completion menus
        closeAllCompletions();

        // Directly trigger state update, don't call handleInput (avoid re-detecting completion)
        const newText = getTextContent();
        setHasContent(!!newText.trim());
        adjustHeight();
        onInput?.(newText);

        // Immediately render file tags (don't wait for space)
        setTimeout(() => {
          renderFileTags();
        }, 50);
      }
    },
    [
      editableRef,
      pathMappingRef,
      getTextContent,
      adjustHeight,
      renderFileTags,
      setHasContent,
      setInternalAttachments,
      onInput,
      closeAllCompletions,
    ]
  );

  // Listen for image paste events dispatched from Java side (when clipboard has image but no text)
  useEffect(() => {
    const onJavaPasteImage = (e: Event) => {
      const { base64, mediaType } = (e as CustomEvent).detail;
      if (!base64) return;
      const ext = mediaType?.split('/')[1] || 'png';
      const attachment: Attachment = {
        id: generateId(),
        fileName: `pasted-image-${Date.now()}.${ext}`,
        mediaType: mediaType || 'image/png',
        data: base64,
      };
      setInternalAttachments((prev) => [...prev, attachment]);
    };
    window.addEventListener('java-paste-image', onJavaPasteImage);
    return () => window.removeEventListener('java-paste-image', onJavaPasteImage);
  }, [setInternalAttachments]);

  /**
   * 监听 Java 侧统一派发的富剪贴板事件。
   * 该事件用于承接“历史会话图文消息整体复制后再粘贴回输入框”的场景，避免旧协议只能恢复首图。
   */
  useEffect(() => {
    const onJavaPasteRichContent = (event: Event) => {
      applyRichClipboardPayload((event as CustomEvent<JavaRichClipboardPayload>).detail ?? {});
    };
    window.addEventListener('java-paste-rich-content', onJavaPasteRichContent);
    return () => window.removeEventListener('java-paste-rich-content', onJavaPasteRichContent);
  }, [applyRichClipboardPayload]);

  return {
    handlePaste,
    handleDragOver,
    handleDrop,
  };
}
