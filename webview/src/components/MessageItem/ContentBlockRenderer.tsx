import { useState, useCallback, memo } from 'react';
import type { TFunction } from 'i18next';
import type { ClaudeContentBlock, ToolResultBlock, CompactSummaryMetadata } from '../../types';

import MarkdownBlock from '../MarkdownBlock';
import CollapsibleTextBlock from '../CollapsibleTextBlock';
import {
  BashToolBlock,
  EditToolBlock,
  GenericToolBlock,
  TaskExecutionBlock,
} from '../toolBlocks';
import { ImagePreviewDialog } from '../ImagePreviewDialog';
import { EDIT_TOOL_NAMES, BASH_TOOL_NAMES, isToolName, isTransientInternalToolName, normalizeToolName } from '../../utils/toolConstants';
import { TASK_STATUS_COLORS } from '../../utils/messageUtils';
import { setActiveImageTarget } from '../../utils/activeImageTarget';
import { buildCopyableImageDataset, copyImageViaBridge } from '../../utils/imageClipboard';

const IMAGE_BLOCK_STYLE: React.CSSProperties = { cursor: 'pointer' };
const THINKING_VISIBLE_STYLE: React.CSSProperties = { display: 'block' };
const THINKING_HIDDEN_STYLE: React.CSSProperties = { display: 'none' };

function getImageStyle(isUser: boolean): React.CSSProperties {
  return {
    maxWidth: isUser ? '200px' : '100%',
    maxHeight: isUser ? '150px' : 'auto',
    borderRadius: '8px',
    objectFit: 'contain',
  };
}

function getFileIcon(mediaType?: string): string {
  if (!mediaType) return 'codicon-file';
  if (mediaType.startsWith('text/')) return 'codicon-file-text';
  if (mediaType.includes('json')) return 'codicon-json';
  if (mediaType.includes('javascript') || mediaType.includes('typescript')) return 'codicon-file-code';
  if (mediaType.includes('pdf')) return 'codicon-file-pdf';
  return 'codicon-file';
}

function getExtension(fileName?: string): string {
  if (!fileName) return '';
  const parts = fileName.split('.');
  return parts.length > 1 ? parts[parts.length - 1].toUpperCase() : '';
}

interface CompactSummaryBlockProps {
  block: {
    type: 'compact_summary';
    title: string;
    content: string;
    metadata?: CompactSummaryMetadata;
  };
  t: TFunction;
}

const CompactSummaryBlock = memo(function CompactSummaryBlock({ block, t }: CompactSummaryBlockProps) {
  const [expanded, setExpanded] = useState(false);
  const toggleExpanded = useCallback(() => setExpanded((value) => !value), []);
  const onKeyDown = useCallback((event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      setExpanded((value) => !value);
    }
  }, []);
  const meta = block.metadata;
  const hasMeta = meta && typeof meta.messagesSummarized === 'number';
  const titleText = t(block.title);
  const toggleLabel = expanded ? t('chat.compactSummary.collapse') : t('chat.compactSummary.expand');

  return (
    <div className="compact-summary-block">
      <div
        className="compact-summary-title"
        role="button"
        tabIndex={0}
        aria-expanded={expanded}
        aria-label={`${titleText} - ${toggleLabel}`}
        onClick={toggleExpanded}
        onKeyDown={onKeyDown}
      >
        <span className="compact-summary-icon" aria-hidden="true">●</span>
        <span className="compact-summary-title-text">{titleText}</span>
        <span className="compact-summary-toggle" aria-hidden="true">{expanded ? '▼' : '▶'}</span>
      </div>
      {hasMeta && (
        <div className="compact-summary-metadata">
          <span className="compact-summary-meta-count">
            {t(
              meta.direction === 'from'
                ? 'chat.compactSummary.messagesFrom'
                : 'chat.compactSummary.messagesUpTo',
              { count: meta.messagesSummarized },
            )}
          </span>
          {meta.userContext && (
            <span className="compact-summary-meta-context">
              {t('chat.compactSummary.userContext', { context: meta.userContext })}
            </span>
          )}
        </div>
      )}
      {expanded && block.content && (
        <div className="compact-summary-content">
          <MarkdownBlock content={block.content} />
        </div>
      )}
    </div>
  );
});

export interface ContentBlockRendererProps {
  block: ClaudeContentBlock;
  messageIndex: number;
  messageType: string;
  isStreaming: boolean;
  isThinkingExpanded: boolean;
  isThinking: boolean;
  isLastMessage: boolean;
  isLastBlock?: boolean;
  t: TFunction;
  onToggleThinking: () => void;
  findToolResult: (toolId: string | undefined, messageIndex: number) => ToolResultBlock | null | undefined;
}

/**
 * 统一渲染消息内容块。
 * 其中图片块额外补齐焦点激活、预览弹层和 Ctrl/Cmd+C 复制能力。
 */
export function ContentBlockRenderer({
  block,
  messageIndex,
  messageType,
  isStreaming,
  isThinkingExpanded,
  isThinking,
  isLastMessage,
  isLastBlock = false,
  t,
  onToggleThinking,
  findToolResult,
}: ContentBlockRendererProps): React.ReactElement | null {
  const [previewImage, setPreviewImage] = useState<{ src: string; mediaType?: string; alt?: string } | null>(null);

  if (block.type === 'text') {
    return messageType === 'user' ? (
      <CollapsibleTextBlock content={block.text ?? ''} />
    ) : (
      <MarkdownBlock
        content={block.text ?? ''}
        isStreaming={isStreaming}
      />
    );
  }

  if (block.type === 'image' && block.src) {
    const imageSource = {
      src: block.src,
      mediaType: block.mediaType,
      alt: block.alt ?? t('chat.userUploadedImage'),
    };

    return (
      <>
        <div
          className={`message-image-block ${messageType === 'user' ? 'user-image' : ''}`}
          onClick={() => {
            setActiveImageTarget(imageSource);
            setPreviewImage(imageSource);
          }}
          style={IMAGE_BLOCK_STYLE}
          title={t('chat.clickToPreview')}
        >
          <img
            src={block.src}
            alt={t('chat.userUploadedImage')}
            style={getImageStyle(messageType === 'user')}
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
        </div>
        <ImagePreviewDialog image={previewImage} onClose={() => setPreviewImage(null)} />
      </>
    );
  }

  if (block.type === 'attachment') {
    const ext = getExtension(block.fileName);
    const displayName = block.fileName || t('chat.unknownFile');
    return (
      <div className="message-attachment-chip" title={displayName}>
        <span className={`message-attachment-chip-icon codicon ${getFileIcon(block.mediaType)}`} />
        {ext && <span className="message-attachment-chip-ext">{ext}</span>}
        <span className="message-attachment-chip-name">{displayName}</span>
      </div>
    );
  }

  if (block.type === 'thinking') {
    return (
      <div className="thinking-block">
        <div
          className="thinking-header"
          onClick={onToggleThinking}
        >
          <span className="thinking-title">
            {isThinking && isLastMessage && isLastBlock
              ? t('common.thinkingProcess')
              : t('common.thinking')}
          </span>
          <span className="thinking-icon">
            {isThinkingExpanded ? '▼' : '▶'}
          </span>
        </div>
        <div
          className="thinking-content"
          style={isThinkingExpanded ? THINKING_VISIBLE_STYLE : THINKING_HIDDEN_STYLE}
        >
          <MarkdownBlock
            content={block.thinking ?? block.text ?? t('chat.noThinkingContent')}
            isStreaming={isStreaming}
          />
        </div>
      </div>
    );
  }

  if (block.type === 'tool_use') {
    const toolName = normalizeToolName(block.name ?? '');

    if (toolName === 'todowrite' || toolName === 'update_plan') {
      return null;
    }

    if (!isStreaming && isTransientInternalToolName(block.name)) {
      return null;
    }

    if (toolName === 'task' || toolName === 'agent' || toolName === 'spawn_agent') {
      return (
        <TaskExecutionBlock
          name={block.name}
          input={block.input}
          result={findToolResult(block.id, messageIndex)}
          toolId={block.id}
          isStreaming={isStreaming}
        />
      );
    }

    if (isToolName(block.name, EDIT_TOOL_NAMES)) {
      return (
        <EditToolBlock
          name={block.name}
          input={block.input}
          result={findToolResult(block.id, messageIndex)}
          toolId={block.id}
        />
      );
    }

    if (isToolName(block.name, BASH_TOOL_NAMES)) {
      return (
        <BashToolBlock
          name={block.name}
          input={block.input}
          result={findToolResult(block.id, messageIndex)}
          toolId={block.id}
        />
      );
    }

    return (
      <GenericToolBlock
        name={block.name}
        input={block.input}
        result={findToolResult(block.id, messageIndex)}
        toolId={block.id}
      />
    );
  }

  if (block.type === 'compact_notification') {
    return (
      <div className="compact-notification-block">
        <div className="compact-notification-header">
          {block.headerText}
        </div>
        {block.items.length > 0 && (
          <div className="compact-notification-items">
            {block.items.map((item, idx) => (
              <div key={idx} className="compact-notification-item">
                <span className="compact-notification-prefix">&gt;</span>
                <span className="compact-notification-text">{item.text}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  if (block.type === 'compact_summary') {
    return <CompactSummaryBlock block={block} t={t} />;
  }

  if (block.type === 'task_notification') {
    const statusColor = TASK_STATUS_COLORS[block.status] || 'text';
    const statusLabel = block.status === 'completed'
      ? t('common.completed')
      : block.status;
    return (
      <div className={`task-notification-block task-notification-${statusColor}`}>
        <span className="task-notification-icon">{block.icon}</span>
        <span className="task-notification-badge">{statusLabel}</span>
        <span className="task-notification-summary">{block.summary}</span>
      </div>
    );
  }

  return null;
}
