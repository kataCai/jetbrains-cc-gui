import type { CopyableImageSource } from './imageClipboard';
import { copyImageViaBridge } from './imageClipboard';

let activeImageTarget: CopyableImageSource | null = null;

/**
 * 记录当前获得焦点或最近被用户操作的图片目标，供 Ctrl/Cmd+C 和预览弹层复用。
 *
 * @param source 当前激活的图片来源
 */
export function setActiveImageTarget(source: CopyableImageSource | null): void {
  activeImageTarget = source;
}

/**
 * 返回当前激活的图片目标。
 *
 * @return 当前图片来源；若不存在则返回 null
 */
export function getActiveImageTarget(): CopyableImageSource | null {
  return activeImageTarget;
}

/**
 * 清理当前激活图片目标，避免预览关闭后仍误命中旧图片。
 */
export function clearActiveImageTarget(): void {
  activeImageTarget = null;
}

/**
 * 尝试复制当前激活图片。
 *
 * @return 是否已发起复制
 */
export async function copyActiveImageTarget(): Promise<boolean> {
  if (!activeImageTarget) {
    return false;
  }
  return copyImageViaBridge(activeImageTarget);
}
