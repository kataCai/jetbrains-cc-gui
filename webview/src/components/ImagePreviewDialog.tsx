import { useEffect, useMemo, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { ContextMenu } from './ContextMenu/ContextMenu';
import { clearActiveImageTarget, setActiveImageTarget } from '../utils/activeImageTarget';
import {
  buildCopyableImageDataset,
  copyImageViaBridge,
  type CopyableImageSource,
} from '../utils/imageClipboard';
import { copyImageSelection, useContextMenu } from '../hooks/useContextMenu';

interface ImagePreviewDialogProps {
  image: CopyableImageSource | null;
  onClose: () => void;
}

/**
 * 统一图片预览弹层。
 * 负责关闭、右键复制、Ctrl/Cmd+C 复制以及激活图片目标同步，避免不同入口各自维护一套图片复制逻辑。
 */
export function ImagePreviewDialog({ image, onClose }: ImagePreviewDialogProps) {
  const { t } = useTranslation();
  const overlayRef = useRef<HTMLDivElement>(null);
  const ctxMenu = useContextMenu();

  useEffect(() => {
    if (!image) {
      clearActiveImageTarget();
      return;
    }

    setActiveImageTarget(image);
    overlayRef.current?.focus();
    return () => {
      clearActiveImageTarget();
    };
  }, [image]);

  const dataset = useMemo(() => (image ? buildCopyableImageDataset(image) : {}), [image]);

  if (!image) {
    return null;
  }

  /**
   * 通过桥接层把当前预览图片写入系统剪贴板。
   * 预览弹层不再展示显式复制按钮，只保留快捷键和右键菜单，避免破坏视觉层次。
   */
  const handleCopy = async () => {
    await copyImageViaBridge(image);
  };

  /**
   * 预览层需要拦截右键事件冒泡，避免消息列表容器再次收到同一次右键事件。
   * 否则历史消息预览图右键时会同时弹出预览层菜单和列表层菜单，导致“复制图片”重复显示。
   */
  const handleContextMenu = (event: React.MouseEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.stopPropagation();
    ctxMenu.open(event);
  };

  return (
    <div
      ref={overlayRef}
      className="image-preview-overlay"
      onClick={onClose}
      onContextMenu={handleContextMenu}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          onClose();
          return;
        }

        if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c') {
          event.preventDefault();
          void handleCopy();
        }
      }}
      tabIndex={0}
      {...dataset}
    >
      <div className="image-preview-shell" onClick={(event) => event.stopPropagation()}>
        <img
          className="image-preview-content"
          src={image.src}
          alt={image.alt ?? image.fileName ?? ''}
          {...dataset}
        />
        <div className="image-preview-actions">
          <button
            type="button"
            className="image-preview-close"
            onClick={onClose}
            title={t('chat.closePreview')}
          >
            ×
          </button>
        </div>
      </div>

      {ctxMenu.visible && ctxMenu.imageTarget && (
        <ContextMenu
          x={ctxMenu.x}
          y={ctxMenu.y}
          onClose={ctxMenu.close}
          items={[
            {
              label: t('contextMenu.copyImage'),
              action: () => void copyImageSelection(ctxMenu.imageTarget),
            },
          ]}
        />
      )}
    </div>
  );
}
