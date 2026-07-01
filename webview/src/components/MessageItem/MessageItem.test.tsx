import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ClaudeContentBlock, ClaudeMessage, ToolResultBlock } from '../../types';
import { extractMarkdownContent } from '../../utils/copyUtils';
import { MessageItem } from './MessageItem';

vi.mock('../MarkdownBlock', () => ({
  default: ({ content }: { content: string }) => <div data-testid="markdown-block">{content}</div>,
}));

vi.mock('../toolBlocks', () => ({
  ReadToolBlock: () => <div data-testid="read-tool-block">read</div>,
  ReadToolGroupBlock: () => <div data-testid="read-tool-group-block">read-group</div>,
  EditToolBlock: () => <div data-testid="edit-tool-block">edit</div>,
  EditToolGroupBlock: () => <div data-testid="edit-tool-group-block">edit-group</div>,
  BashToolBlock: () => <div data-testid="bash-tool-block">bash</div>,
  BashToolGroupBlock: () => <div data-testid="bash-tool-group-block">bash-group</div>,
  SearchToolGroupBlock: () => <div data-testid="search-tool-group-block">search-group</div>,
}));

vi.mock('./ContentBlockRenderer', () => ({
  ContentBlockRenderer: ({ block, t }: { block: ClaudeContentBlock; t: (key: string) => string }) => {
    if (block.type === 'task_notification') {
      const statusLabel = block.status === 'completed' ? t('common.completed') : block.status;
      return (
        <div data-testid="content-block-task_notification">
          <span>{statusLabel}</span>
          <span>{block.summary}</span>
        </div>
      );
    }
    return <div data-testid={`content-block-${block.type}`}>{block.type}</div>;
  },
}));

vi.mock('./ProviderNotConfiguredCard', () => ({
  ProviderNotConfiguredCard: () => <div data-testid="provider-not-configured-card">provider-card</div>,
  isProviderNotConfiguredError: () => false,
}));

const t = ((key: string) => {
  const translations: Record<string, string> = {
    'markdown.copyMessage': '复制消息',
    'markdown.copySuccess': '已复制',
    'chat.streamingConnected': '已连接',
    'chat.totalDuration': '本次耗时',
    'common.completed': '已完成',
  };
  return translations[key] ?? key;
}) as any;

/**
 * 提取消息纯文本内容，供 MessageItem 判断复制按钮显隐。
 *
 * @param message 待渲染的消息对象
 * @return 消息 content 字段，若不存在则返回空串
 */
const getMessageText = (message: ClaudeMessage) => message.content ?? '';

/**
 * 从 raw.content 中提取内容块，模拟前端消息渲染入口的真实读取方式。
 * 本测试只关心 block 级渲染路径，因此优先读取 raw.content 和 raw.message.content。
 *
 * @param message 待渲染的消息对象
 * @return 可供 MessageItem 使用的内容块数组
 */
const getContentBlocks = (message: ClaudeMessage): ClaudeContentBlock[] => {
  const raw = message.raw;
  if (!raw || typeof raw !== 'object') {
    return [];
  }

  const content = Array.isArray(raw.content)
    ? raw.content
    : Array.isArray(raw.message?.content)
      ? raw.message.content
      : [];

  return content as ClaudeContentBlock[];
};

/**
 * 当前这组测试不验证 tool_result 关联，只需要满足 MessageItem 的函数签名。
 *
 * @return 始终返回 null，表示没有匹配到 tool_result
 */
const findToolResult = (_toolId: string | undefined, _messageIndex: number): ToolResultBlock | null => null;

/**
 * 使用统一参数渲染 MessageItem，降低各个用例里的样板代码噪音。
 *
 * @param message 待渲染的消息对象
 * @return testing-library 的 render 结果
 */
function renderMessageItem(message: ClaudeMessage) {
  return render(
    <MessageItem
      message={message}
      messageIndex={0}
      messageKey="message-0"
      isLast={false}
      streamingActive={false}
      isThinking={false}
      t={t}
      getMessageText={getMessageText}
      getContentBlocks={getContentBlocks}
      findToolResult={findToolResult}
      extractMarkdownContent={extractMarkdownContent}
    />
  );
}

describe('MessageItem copy button visibility', () => {
  /**
   * 验证纯工具 assistant 消息不会显示复制按钮。
   * 前置条件：消息只包含 bash 类 tool_use block，没有额外文本 block。
   * 断言意图：工具型消息应只显示工具块，不产生可复制的 assistant 文本。
   */
  it('hides the assistant copy button for tool-only messages', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'Tool: shell_command',
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'tool-1',
            name: 'shell_command',
            input: { cmd: 'git status' },
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('bash-tool-block')).toBeTruthy();
    expect(screen.queryByTestId('content-block-text')).toBeNull();
    expect(screen.queryByRole('button', { name: '复制消息' })).toBeNull();
  });

  /**
   * 验证工具块后仍有文本回复时，复制按钮必须保留。
   * 前置条件：assistant 消息同时包含 tool_use 和 text block。
   * 断言意图：只要消息末尾仍有可复制文本，就不能隐藏复制按钮。
   */
  it('keeps the assistant copy button when tool output is followed by reply text', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'tool-1',
            name: 'shell_command',
            input: { cmd: 'git status' },
          },
          {
            type: 'text',
            text: '提交完成。',
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('bash-tool-block')).toBeTruthy();
    expect(screen.getByTestId('content-block-text')).toBeTruthy();
    expect(screen.getByRole('button', { name: '复制消息' })).toBeTruthy();
  });

  /**
   * 验证连续 exec_command 会按 bash group 统一显示。
   * 前置条件：同一条 assistant 消息内连续出现两个 exec_command。
   * 断言意图：应渲染 bash group，而不是两个独立的 tool_use block。
   */
  it('groups consecutive exec_command blocks into the batch command tool block', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'tool-1',
            name: 'exec_command',
            input: { command: 'git status' },
          },
          {
            type: 'tool_use',
            id: 'tool-2',
            name: 'exec_command',
            input: { command: 'git diff --cached' },
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('bash-tool-group-block')).toBeTruthy();
    expect(screen.queryAllByTestId('content-block-tool_use')).toHaveLength(0);
  });

  /**
   * 验证已完成的 assistant 消息会显示总耗时。
   * 前置条件：消息包含文本内容且 durationMs 为有效整数。
   * 断言意图：消息底部需要显示本地化耗时标签和格式化后的 mm:ss 值。
   */
  it('shows duration after a completed assistant message', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'done',
      durationMs: 65000,
      raw: {
        content: [
          {
            type: 'text',
            text: 'done',
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByText('本次耗时')).toBeTruthy();
    expect(screen.getByText('1:05')).toBeTruthy();
  });

  /**
   * 验证 completed 的 task_notification 会打出更明确的完成标识。
   * 前置条件：消息类型为 task_notification，内容块状态为 completed。
   * 断言意图：在缺少 assistant 自然语言总结时，聊天区仍应明确告诉用户“本轮已完成”。
   */
  it('shows an explicit completed label for completed task notifications', () => {
    const message: ClaudeMessage = {
      type: 'task_notification',
      content: 'fallback',
      raw: {
        content: [
          {
            type: 'task_notification',
            icon: '●',
            summary: '本轮任务已完成，已修改 2 个文件。',
            status: 'completed',
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByText('已完成')).toBeTruthy();
    expect(screen.getByText('本轮任务已完成，已修改 2 个文件。')).toBeTruthy();
  });

  /**
   * 验证 assistant 完成消息在包含 completed task_notification 时，
   * 底部必须额外出现独立 completion footer，而不是只剩“本次耗时”。
   * 前置条件：assistant 消息正文先有工具块，再有 completed task_notification，且耗时已补写。
   * 断言意图：即使没有自然语言总结，用户也能在消息尾部看出任务已完成且会话仍可继续。
   */
  it('shows a dedicated completion footer for assistant messages that contain completed task notifications', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'fallback',
      durationMs: 21000,
      raw: {
        content: [
          {
            type: 'tool_use',
            id: 'tool-1',
            name: 'exec_command',
            input: { command: 'git status' },
          },
          {
            type: 'task_notification',
            icon: '•',
            summary: '本轮任务已完成，会话仍可继续。',
            status: 'completed',
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    const footer = screen.getByTestId('message-completion-footer');
    expect(footer).toBeTruthy();
    expect(within(footer).getByText('已完成')).toBeTruthy();
    expect(within(footer).getByText('本轮任务已完成，会话仍可继续。')).toBeTruthy();
    expect(screen.getByText('本次耗时')).toBeTruthy();
  });

  /**
   * 验证普通 assistant 完成消息不会误显示 completion footer。
   * 前置条件：assistant 消息仅包含普通文本和耗时，没有 completed task_notification。
   * 断言意图：completion footer 必须只由真实完成摘要驱动，不能把任意流结束都误判为“已完成”。
   */
  it('does not show the completion footer for assistant messages without completed task notifications', () => {
    const message: ClaudeMessage = {
      type: 'assistant',
      content: 'regular answer',
      durationMs: 5000,
      raw: {
        content: [
          {
            type: 'text',
            text: 'regular answer',
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.queryByTestId('message-completion-footer')).toBeNull();
    expect(screen.getByText('本次耗时')).toBeTruthy();
  });
  /**
   * 验证当消息只包含 `image_missing` 占位块时，复制按钮仍然保留。
   * 该场景对应历史图片缓存已失效但仍需把降级提示重新回贴到输入框，
   * 因此不能因为没有真实图片块或正文文本就隐藏复制入口。
   */
  it('keeps the copy button for messages that only contain missing history images', () => {
    const message: ClaudeMessage = {
      type: 'user',
      timestamp: '2026-06-29T00:00:00.000Z',
      raw: {
        content: [
          {
            type: 'image_missing',
            fileName: 'lost.png',
            mediaType: 'image/png',
            originalPath: 'C:/cache/lost.png',
            reason: 'cache_missing',
          },
        ],
      } as any,
    };

    renderMessageItem(message);

    expect(screen.getByTestId('content-block-image_missing')).toBeTruthy();
    expect(screen.getByRole('button', { name: t('markdown.copyMessage') })).toBeTruthy();
  });
});
