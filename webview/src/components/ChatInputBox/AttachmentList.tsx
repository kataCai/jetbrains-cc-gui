import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Attachment, AttachmentListProps } from './types';
import { isImageAttachment } from './types';
import { ImagePreviewDialog } from '../ImagePreviewDialog';
import { ContextMenu } from '../ContextMenu/ContextMenu';
import { setActiveImageTarget } from '../../utils/activeImageTarget';
import { buildCopyableImageDataset, copyImageViaBridge } from '../../utils/imageClipboard';
import { copyImageSelection, useContextMenu } from '../../hooks/useContextMenu';
import { sendToJava } from '../../utils/bridge';

/**
 * 输入区附件列表。
 * 图片附件除了预览外，还补齐焦点激活、快捷键复制和右键复制入口，保证缩略图与消息区图片交互一致。
 */
export const AttachmentList = ({
  attachments,
  onRemove,
  onPreview,
  rightClickOpenDevToolsEnabled = false,
}: AttachmentListProps) => {
  const { t } = useTranslation();
  const [previewImage, setPreviewImage] = useState<Attachment | null>(null);
  const ctxMenu = useContextMenu();

  /**
   * 将输入区附件转换成统一图片复制来源，避免缩略图、预览态和右键逻辑各自拼接 data URL。
   *
   * @param attachment 当前附件
   * @return 统一图片来源
   */
  const buildImageSource = useCallback((attachment: Attachment) => ({
    src: `data:${attachment.mediaType};base64,${attachment.data}`,
    mediaType: attachment.mediaType,
    fileName: attachment.fileName,
    alt: attachment.fileName,
  }), []);

  /**
   * 处理附件点击事件。
   * 图片附件优先同步当前激活目标，再根据外部是否接管预览决定走回调还是内部弹层。
   *
   * @param attachment 当前点击的附件
   */
  const handleClick = useCallback((attachment: Attachment) => {
    if (!isImageAttachment(attachment)) {
      return;
    }

    setActiveImageTarget(buildImageSource(attachment));
    if (onPreview) {
      onPreview(attachment);
      return;
    }
    setPreviewImage(attachment);
  }, [buildImageSource, onPreview]);

  /**
   * 处理附件删除按钮点击。
   * 这里显式阻止冒泡，避免点击关闭按钮时误触发图片预览。
   *
   * @param event 点击事件
   * @param id 待删除附件 ID
   */
  const handleRemove = useCallback((event: React.MouseEvent, id: string) => {
    event.stopPropagation();
    onRemove?.(id);
  }, [onRemove]);

  /**
   * 关闭内部图片预览弹层。
   */
  const closePreview = useCallback(() => {
    setPreviewImage(null);
  }, []);

  /**
   * 根据媒体类型选择文件图标。
   *
   * @param mediaType 附件媒体类型
   * @return 对应 codicon 类名
   */
  const getFileIcon = (mediaType: string): string => {
    if (mediaType.startsWith('text/')) return 'codicon-file-text';
    if (mediaType.includes('json')) return 'codicon-json';
    if (mediaType.includes('javascript') || mediaType.includes('typescript')) return 'codicon-file-code';
    if (mediaType.includes('pdf')) return 'codicon-file-pdf';
    return 'codicon-file';
  };

  /**
   * 提取附件扩展名展示文本。
   *
   * @param fileName 附件文件名
   * @return 大写扩展名；若无扩展名则返回空字符串
   */
  const getExtension = (fileName: string): string => {
    const parts = fileName.split('.');
    return parts.length > 1 ? parts[parts.length - 1].toUpperCase() : '';
  };

  if (attachments.length === 0) {
    return null;
  }

  return (
    <>
      <div className="attachment-list" onContextMenu={ctxMenu.open}>
        {attachments.map((attachment) => {
          const imageSource = isImageAttachment(attachment) ? buildImageSource(attachment) : null;

          return (
            <div
              key={attachment.id}
              className="attachment-item"
              onClick={() => handleClick(attachment)}
              title={attachment.fileName}
            >
              {imageSource ? (
                <img
                  className="attachment-thumbnail"
                  src={imageSource.src}
                  alt={attachment.fileName}
                  tabIndex={0}
                  onFocus={() => setActiveImageTarget(imageSource)}
                  onKeyDown={(event) => {
                    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'c') {
                      event.preventDefault();
                      void copyImageViaBridge(imageSource);
                    }
                  }}
                  {...buildCopyableImageDataset(imageSource)}
                />
              ) : (
                <div className="attachment-file">
                  <span className={`attachment-file-icon codicon ${getFileIcon(attachment.mediaType)}`} />
                  <span className="attachment-file-name">
                    {getExtension(attachment.fileName) || attachment.fileName.slice(0, 6)}
                  </span>
                </div>
              )}

              <button
                className="attachment-remove"
                onClick={(event) => handleRemove(event, attachment.id)}
                title={t('chat.removeAttachment')}
              >
                ×
              </button>
            </div>
          );
        })}
      </div>

      {ctxMenu.visible && (ctxMenu.imageTarget || rightClickOpenDevToolsEnabled) && (
        <ContextMenu
          x={ctxMenu.x}
          y={ctxMenu.y}
          onClose={ctxMenu.close}
          items={[
            ...(ctxMenu.imageTarget ? [{
              label: t('contextMenu.copyImage', '复制图片'),
              action: () => void copyImageSelection(ctxMenu.imageTarget),
            }] : []),
            ...(rightClickOpenDevToolsEnabled
              ? [
                  ...(ctxMenu.imageTarget ? [{ separator: true as const }] : []),
                  {
                    label: t('contextMenu.openDevTools'),
                    action: () => sendToJava('open_devtools', ''),
                  } as const,
                ]
              : []),
          ]}
        />
      )}

      <ImagePreviewDialog
        image={previewImage ? buildImageSource(previewImage) : null}
        onClose={closePreview}
      />
    </>
  );
};

export default AttachmentList;
