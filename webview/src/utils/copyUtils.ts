import type { ClaudeMessage, ClaudeContentBlock, ClaudeRawMessage } from '../types';
import type {
  ClipboardImagePayload,
  ClipboardRichBlock,
  ClipboardRichPayload,
  CopyableImageSource,
} from './imageClipboard';
import {
  hasCommandMessageTag,
  formatCommandForDisplay,
  formatCommandForResubmit,
  hasTaskNotificationTag,
  formatTaskNotificationForDisplay,
  isSyntheticToolMessageContent,
} from './messageUtils';
import {
  buildClipboardImagePayload,
  copyRichClipboardViaBridge,
  escapeHtml,
  extractImagePayloadFromDataUrl,
} from './imageClipboard';

/**
 * Normalize raw message blocks to ClaudeContentBlock array
 */
function normalizeBlocks(raw: ClaudeRawMessage | string | undefined): ClaudeContentBlock[] | null {
  if (!raw) return null;

  if (typeof raw === 'string') {
    return [{ type: 'text', text: raw }];
  }

  // Check raw.content
  // Note: task_notification blocks only exist after messageUtils.normalizeBlocks processing,
  // not in raw SDK data. Raw text blocks with <task-notification> tags are handled by formatTextForCopy.
  if (Array.isArray(raw.content)) {
    return raw.content.filter(
      (block): block is ClaudeContentBlock =>
        block.type === 'text' || block.type === 'thinking' || block.type === 'tool_use' || block.type === 'image'
    );
  }

  if (typeof raw.content === 'string') {
    return [{ type: 'text', text: raw.content }];
  }

  // Check raw.message?.content
  const msgContent = raw.message?.content;
  if (Array.isArray(msgContent)) {
    return msgContent.filter(
      (block): block is ClaudeContentBlock =>
        block.type === 'text' || block.type === 'thinking' || block.type === 'tool_use' || block.type === 'image'
    );
  }

  if (typeof msgContent === 'string') {
    return [{ type: 'text', text: msgContent }];
  }

  return null;
}

/**
 * Format text content for copy/export, converting XML tags to readable format
 */
function formatTextForCopy(text: string): string {
  if (!text) return text;

  // Format command messages: use formatCommandForResubmit for copy (uses <command-name>)
  // which already contains the / prefix, matching CLI's textForResubmit behavior
  if (hasCommandMessageTag(text)) {
    const resubmitContent = formatCommandForResubmit(text);
    if (resubmitContent) {
      return resubmitContent;
    }
    // Fallback to display format if no command-name tag
    const displayContent = formatCommandForDisplay(text);
    if (displayContent) {
      return displayContent;
    }
  }

  // Format task-notification for copy
  if (hasTaskNotificationTag(text)) {
    const notification = formatTaskNotificationForDisplay(text);
    if (notification) {
      return `${notification.icon} ${notification.summary}`;
    }
  }

  return text;
}

/**
 * Extract Markdown content from a message for copying
 * @param message - The ClaudeMessage to extract content from
 * @param includeThinking - Whether to include thinking blocks (default: false)
 * @returns The extracted Markdown content as a string
 */
export function extractMarkdownContent(message: ClaudeMessage, includeThinking = false): string {
  const rawBlocks = normalizeBlocks(message.raw);
  const parts: string[] = [];

  if (rawBlocks && rawBlocks.length > 0) {
    for (const block of rawBlocks) {
      if (block.type === 'text' && block.text) {
        // Format command/notification messages for copy
        parts.push(formatTextForCopy(block.text));
      } else if (includeThinking && block.type === 'thinking') {
        const thinkingText = (block as { thinking?: string; text?: string }).thinking ||
                            (block as { thinking?: string; text?: string }).text;
        if (thinkingText) {
          parts.push(`<thinking>\n${thinkingText}\n</thinking>`);
        }
      }
      // tool_use blocks are not included in copy - they contain internal tool calls
    }
  }

  // Fallback to message.content if no text blocks found
  if (
    parts.length === 0 &&
    message.content &&
    message.content.trim() &&
    !isSyntheticToolMessageContent(message.content, rawBlocks)
  ) {
    parts.push(formatTextForCopy(message.content));
  }

  return parts.join('\n\n');
}

/**
 * 将消息中的图片块收敛为统一来源结构，便于按钮复制、快捷键复制和富消息复制共用。
 *
 * @param block 消息中的图片块
 * @return 可复制图片来源；若块信息不完整则返回 null
 */
export function extractCopyableImageSource(block: ClaudeContentBlock): CopyableImageSource | null {
  if (block.type !== 'image' || !block.src) {
    return null;
  }

  return {
    src: block.src,
    mediaType: block.mediaType,
    alt: block.alt,
  };
}

/**
 * 提取消息的富复制负载。
 * 规则：
 * 1. 纯文本消息只返回 text；
 * 2. 图文消息返回 text + html；
 * 3. 单图或多图消息额外附带首图，兼容更擅长 imageFlavor 的目标应用。
 *
 * @param message 待复制消息
 * @param includeThinking 是否包含 thinking 内容
 * @return 富复制负载；若消息无可复制内容则返回 null
 */
export function extractMessageRichContent(
  message: ClaudeMessage,
  includeThinking = false,
): ClipboardRichPayload | null {
  const rawBlocks = normalizeBlocks(message.raw);
  if (!rawBlocks || rawBlocks.length === 0) {
    const fallbackText = message.content?.trim();
    if (!fallbackText || isSyntheticToolMessageContent(message.content, rawBlocks)) {
      return null;
    }
    return {
      text: formatTextForCopy(fallbackText),
      html: `<p>${escapeHtml(formatTextForCopy(fallbackText))}</p>`,
    };
  }

  const textParts: string[] = [];
  const htmlParts: string[] = [];
  const images: CopyableImageSource[] = [];
  const clipboardImages: ClipboardImagePayload[] = [];
  const orderedBlocks: ClipboardRichBlock[] = [];

  for (const block of rawBlocks) {
    if (block.type === 'text' && block.text) {
      const formatted = formatTextForCopy(block.text);
      textParts.push(formatted);
      htmlParts.push(`<p>${escapeHtml(formatted).replace(/\n/g, '<br/>')}</p>`);
      orderedBlocks.push({ type: 'text', text: formatted });
      continue;
    }

    if (includeThinking && block.type === 'thinking') {
      const thinkingText = (block as { thinking?: string; text?: string }).thinking ||
        (block as { thinking?: string; text?: string }).text;
      if (thinkingText) {
        const formattedThinking = `<thinking>\n${thinkingText}\n</thinking>`;
        textParts.push(formattedThinking);
        htmlParts.push(`<pre>${escapeHtml(thinkingText)}</pre>`);
        orderedBlocks.push({ type: 'text', text: formattedThinking });
      }
      continue;
    }

    const imageSource = extractCopyableImageSource(block);
    if (imageSource) {
      const imageIndex = images.length;
      images.push(imageSource);
      const alt = escapeHtml(imageSource.alt || imageSource.fileName || 'image');
      htmlParts.push(`<img src="${escapeHtml(imageSource.src)}" alt="${alt}" />`);
      orderedBlocks.push({ type: 'image', imageIndex });
      const directPayload = extractImagePayloadFromDataUrl(imageSource.src, imageSource.mediaType);
      if (directPayload) {
        clipboardImages.push({
          ...directPayload,
          fileName: imageSource.fileName,
        });
      }
    }
  }

  if (textParts.length === 0 && htmlParts.length === 0 && images.length === 0) {
    return null;
  }

  return {
    text: textParts.join('\n\n'),
    html: htmlParts.join(''),
    images: clipboardImages.length > 0 ? clipboardImages : undefined,
    orderedBlocks: orderedBlocks.length > 0 ? orderedBlocks : undefined,
    image: images[0]
      ? {
        ...(clipboardImages[0] ?? {
          data: '',
          mediaType: images[0].mediaType ?? 'image/png',
          fileName: images[0].fileName,
        }),
      }
      : undefined,
  };
}

/**
 * 根据消息内容结构选择合适的复制策略。
 * 富内容复制经 Java 桥接写入系统剪贴板；纯文本仍沿用浏览器文本复制链路作为兜底。
 *
 * @param message 待复制消息
 * @param includeThinking 是否包含 thinking 内容
 * @return Promise<boolean> 表示是否复制成功
 */
export async function copyMessageToClipboard(
  message: ClaudeMessage,
  includeThinking = false,
): Promise<boolean> {
  const richContent = extractMessageRichContent(message, includeThinking);
  if (!richContent) {
    return false;
  }

  const hasImage = richContent.html.includes('<img ');
  if (hasImage) {
    const imageSources = normalizeBlocks(message.raw)
      ?.map(extractCopyableImageSource)
      .filter((item): item is CopyableImageSource => Boolean(item)) ?? [];
    if (imageSources.length === 0) {
      return copyRichClipboardViaBridge({
        text: richContent.text,
        html: richContent.html,
      });
    }

    /**
     * 富复制桥接需要把所有图片都归一化为可回贴的 payload，才能在聊天输入框中完整恢复附件顺序。
     * 同时继续保留首图到 `image` 字段，用于兼容依赖原生 imageFlavor 的外部目标应用。
     */
    const imagePayloads = await Promise.all(imageSources.map((source) => buildClipboardImagePayload(source)));

    return copyRichClipboardViaBridge({
      text: richContent.text,
      html: richContent.html,
      image: imagePayloads[0],
      images: imagePayloads,
      orderedBlocks: richContent.orderedBlocks,
    });
  }

  if (richContent.text.trim()) {
    return copyToClipboard(richContent.text);
  }

  return false;
}

/**
 * Copy text to clipboard with fallback for older browsers
 * @param text - The text to copy
 * @returns Promise<boolean> - Whether the copy was successful
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (err) {
    // Fallback method for environments where navigator.clipboard is not available
    try {
      const textarea = document.createElement('textarea');
      textarea.value = text;
      textarea.style.position = 'fixed';
      textarea.style.left = '-9999px';
      textarea.style.top = '0';
      document.body.appendChild(textarea);
      textarea.focus();
      textarea.select();
      const successful = document.execCommand('copy');
      document.body.removeChild(textarea);
      return successful;
    } catch (e) {
      console.error('Copy failed:', e);
      return false;
    }
  }
}
