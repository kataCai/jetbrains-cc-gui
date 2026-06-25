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

    if (text.trim()) {
      insertTextAtCursor(text, editableRef.current);
      handleInput(false);
      flushInput();
    }

    if (imagePayloads.length === 0) {
      return;
    }

    const attachments = imagePayloads
      .map((item, index) => buildAttachmentFromClipboardPayload(item, index))
      .filter((item): item is Attachment => Boolean(item));
    if (attachments.length === 0) {
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
  }, [buildAttachmentFromClipboardPayload, editableRef, flushInput, handleInput, setInternalAttachments]);

  /**
   * Handle paste event - detect images and plain text
   */
  const handlePaste = useCallback(
    (e: React.ClipboardEvent) => {
      const items = e.clipboardData?.items;

      if (!items) {
        return;
      }

      // Check if there's a real image (type is image/*)
      let hasImage = false;
      for (let i = 0; i < items.length; i++) {
        const item = items[i];

        // Only process real image types (type starts with image/)
        if (item.type.startsWith('image/')) {
          hasImage = true;
          e.preventDefault();

          const blob = item.getAsFile();

          if (blob) {
            // Read image as Base64
            const reader = new FileReader();
            reader.onload = () => {
              const base64 = (reader.result as string).split(',')[1];
              const mediaType = blob.type || item.type || 'image/png';
              const ext = (() => {
                if (mediaType && mediaType.includes('/')) {
                  return mediaType.split('/')[1];
                }
                const name = blob.name || '';
                const m = name.match(/\.([a-zA-Z0-9]+)$/);
                return m ? m[1] : 'png';
              })();
              const attachment: Attachment = {
                id: generateId(),
                fileName: `pasted-image-${Date.now()}.${ext}`,
                mediaType,
                data: base64,
              };

              setInternalAttachments((prev) => [...prev, attachment]);
            };
            reader.readAsDataURL(blob);
          }

          return;
        }
      }

      // If no image, try to get text or file path
      if (!hasImage) {
        e.preventDefault();

        // Try multiple ways to get text
        let text =
          e.clipboardData.getData('text/plain') ||
          e.clipboardData.getData('text/uri-list') ||
          e.clipboardData.getData('text/html');

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

          // Use modern Selection API to insert plain text (maintains cursor position)
          insertTextAtCursor(text, editableRef.current);
          timer.mark('insertText');

          // Trigger input event to update state
          // Pass false to bypass IME guard (isComposingRef may be stale after recent compositionEnd)
          handleInput(false);
          timer.mark('handleInput');

          // Immediately sync parent state without waiting for debounce
          flushInput();

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
    [setInternalAttachments, handleInput, flushInput]
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
