import { sendToJava } from './bridge';

export interface ClipboardImagePayload {
  data: string;
  mediaType: string;
  fileName?: string;
}

export interface ClipboardRichPayload {
  text: string;
  html: string;
  image?: ClipboardImagePayload;
  images?: ClipboardImagePayload[];
  orderedBlocks?: ClipboardRichBlock[];
}

export type ClipboardRichBlock =
  | { type: 'text'; text: string }
  | { type: 'image'; imageIndex: number };

export interface CopyableImageSource {
  src: string;
  mediaType?: string;
  fileName?: string;
  alt?: string;
}

const DATA_URL_PATTERN = /^data:([^;,]+)?(;base64)?,(.*)$/i;
const PNG_MEDIA_TYPE = 'image/png';
const RICH_COPY_BRIDGE_LOG_PREFIX = '[RichCopy][BridgeWrite]';

/**
 * 输出图片剪贴板桥接日志。
 * 仅记录媒体类型、分支选择和统计信息，避免打印图片正文。
 *
 * @param message 日志说明
 * @param details 附加结构化上下文
 * @return 无返回值
 */
function logRichClipboardBridge(message: string, details?: Record<string, unknown>): void {
  console.debug(RICH_COPY_BRIDGE_LOG_PREFIX, message, details ?? {});
}

/**
 * 将 HTML 文本中的特殊字符转义为安全实体，避免拼接富剪贴板 HTML 时引入非法标签。
 *
 * @param value 原始文本
 * @return 已转义的安全文本
 */
export function escapeHtml(value: string): string {
  return value.replace(/[&<>"']/g, (char) => {
    switch (char) {
      case '&':
        return '&amp;';
      case '<':
        return '&lt;';
      case '>':
        return '&gt;';
      case '"':
        return '&quot;';
      case '\'':
        return '&#39;';
      default:
        return char;
    }
  });
}

/**
 * 将图片来源序列化到 DOM dataset，便于右键菜单和键盘复制从任意图片节点恢复复制上下文。
 *
 * @param source 可复制图片来源
 * @return 适合写入 data-* 的属性字典
 */
export function buildCopyableImageDataset(source: CopyableImageSource): Record<string, string> {
  return {
    'data-copy-image-src': source.src,
    'data-copy-image-media-type': source.mediaType ?? '',
    'data-copy-image-file-name': source.fileName ?? '',
    'data-copy-image-alt': source.alt ?? '',
  };
}

/**
 * 从 DOM 元素及其祖先节点中恢复图片复制来源。
 *
 * @param target 事件目标节点
 * @return 若节点携带图片复制元数据则返回来源，否则返回 null
 */
export function getCopyableImageSourceFromElement(target: HTMLElement | null): CopyableImageSource | null {
  const element = target?.closest('[data-copy-image-src]') as HTMLElement | null;
  if (!element) {
    return null;
  }

  const src = element.getAttribute('data-copy-image-src')?.trim();
  if (!src) {
    return null;
  }

  return {
    src,
    mediaType: element.getAttribute('data-copy-image-media-type')?.trim() || undefined,
    fileName: element.getAttribute('data-copy-image-file-name')?.trim() || undefined,
    alt: element.getAttribute('data-copy-image-alt')?.trim() || undefined,
  };
}

/**
 * 针对 data URL 直接提取图片负载。
 * 已是 PNG 时直接复用，其他格式先保留给后续归一化逻辑处理。
 *
 * @param src 图片地址
 * @param fallbackMediaType 调用方提供的兜底媒体类型
 * @return 可直接复用的图片负载；若非可识别 data URL 则返回 null
 */
export function extractImagePayloadFromDataUrl(
  src: string,
  fallbackMediaType?: string,
): ClipboardImagePayload | null {
  const match = DATA_URL_PATTERN.exec(src);
  if (!match) {
    return null;
  }

  const mediaType = (match[1] || fallbackMediaType || PNG_MEDIA_TYPE).trim().toLowerCase();
  const isBase64 = Boolean(match[2]);
  const rawData = match[3] ?? '';
  if (!rawData) {
    return null;
  }

  const data = isBase64
    ? rawData
    : btoa(unescape(encodeURIComponent(decodeURIComponentSafe(rawData))));
  return {
    data,
    mediaType,
  };
}

function decodeURIComponentSafe(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.crossOrigin = 'anonymous';
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(`Failed to load image: ${src}`));
    image.src = src;
  });
}

function canvasToDataUrl(canvas: HTMLCanvasElement): Promise<string> {
  return new Promise((resolve, reject) => {
    if (typeof canvas.toBlob === 'function') {
      canvas.toBlob((blob) => {
        if (!blob) {
          reject(new Error('Canvas toBlob returned null'));
          return;
        }
        const reader = new FileReader();
        reader.onload = () => {
          if (typeof reader.result !== 'string') {
            reject(new Error('Failed to read canvas blob'));
            return;
          }
          resolve(reader.result);
        };
        reader.onerror = () => reject(reader.error ?? new Error('Failed to read canvas blob'));
        reader.readAsDataURL(blob);
      }, PNG_MEDIA_TYPE);
      return;
    }

    try {
      resolve(canvas.toDataURL(PNG_MEDIA_TYPE));
    } catch (error) {
      reject(error instanceof Error ? error : new Error('Failed to serialize canvas'));
    }
  });
}

async function normalizeImageSourceToPngDataUrl(src: string): Promise<string> {
  const image = await loadImage(src);
  const canvas = document.createElement('canvas');
  canvas.width = Math.max(1, image.naturalWidth || image.width || 1);
  canvas.height = Math.max(1, image.naturalHeight || image.height || 1);
  const context = canvas.getContext('2d');
  if (!context) {
    throw new Error('Canvas 2D context is unavailable');
  }
  context.drawImage(image, 0, 0);
  return canvasToDataUrl(canvas);
}

/**
 * 构建发送给 Java 侧的单图剪贴板负载。
 * 为避免 AWT/ImageIO 面对 SVG、WebP 等格式时解码不稳定，这里统一尽量转成 PNG。
 *
 * @param source 图片来源
 * @return 供桥接层写入系统剪贴板的单图负载
 */
export async function buildClipboardImagePayload(source: CopyableImageSource): Promise<ClipboardImagePayload> {
  const directPayload = extractImagePayloadFromDataUrl(source.src, source.mediaType);
  if (directPayload?.mediaType === PNG_MEDIA_TYPE) {
    logRichClipboardBridge('reuse direct PNG data url for clipboard image payload', {
      mediaType: directPayload.mediaType,
      hasFileName: Boolean(source.fileName),
    });
    return {
      ...directPayload,
      fileName: source.fileName,
    };
  }

  logRichClipboardBridge('normalize image source to PNG for clipboard payload', {
    sourceMediaType: source.mediaType ?? 'unknown',
    hasDirectPayload: Boolean(directPayload),
    srcKind: source.src.startsWith('data:') ? 'data-url' : 'external',
  });
  const normalizedDataUrl = directPayload?.mediaType?.startsWith('image/')
    ? await normalizeImageSourceToPngDataUrl(source.src)
    : await normalizeImageSourceToPngDataUrl(source.src);
  const normalizedPayload = extractImagePayloadFromDataUrl(normalizedDataUrl, PNG_MEDIA_TYPE);
  if (!normalizedPayload) {
    logRichClipboardBridge('failed to normalize image payload to PNG', {
      sourceMediaType: source.mediaType ?? 'unknown',
    });
    throw new Error('Failed to normalize image payload');
  }

  return {
    ...normalizedPayload,
    mediaType: PNG_MEDIA_TYPE,
    fileName: source.fileName,
  };
}

/**
 * 通过 Java 桥接复制单张图片。
 *
 * @param source 图片来源
 * @return 复制是否已成功发起
 */
export async function copyImageViaBridge(source: CopyableImageSource): Promise<boolean> {
  const payload = await buildClipboardImagePayload(source);
  sendToJava('write_clipboard_image', payload);
  return true;
}

/**
 * 通过 Java 桥接复制富内容消息。
 *
 * @param payload 富内容负载
 * @return 是否已成功发起复制
 */
export function copyRichClipboardViaBridge(payload: ClipboardRichPayload): boolean {
  if (!payload.text.trim() && !payload.html.trim() && !payload.image && !payload.images?.length) {
    logRichClipboardBridge('skip rich clipboard write because payload is empty');
    return false;
  }
  logRichClipboardBridge('send rich clipboard payload to Java bridge', {
    textLength: payload.text.length,
    htmlLength: payload.html.length,
    imageCount: payload.images?.length ?? (payload.image ? 1 : 0),
    orderedBlockCount: payload.orderedBlocks?.length ?? 0,
  });
  sendToJava('write_clipboard_rich', payload);
  return true;
}
