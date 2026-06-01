import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MarkdownBlock from './MarkdownBlock';
import {
  resetLinkifyCapabilities,
  setLinkifyCapabilities,
} from '../utils/linkifyCapabilities';

const bridgeMocks = vi.hoisted(() => ({
  openBrowser: vi.fn(),
  openClass: vi.fn(),
  openFile: vi.fn(),
  resolveFilePathWithCallback: vi.fn(),
}));

vi.mock('../utils/bridge', () => ({
  openBrowser: bridgeMocks.openBrowser,
  openClass: bridgeMocks.openClass,
  openFile: bridgeMocks.openFile,
  resolveFilePathWithCallback: bridgeMocks.resolveFilePathWithCallback,
}));

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'zh' },
  }),
}));

describe('MarkdownBlock linkify integration', () => {
  beforeEach(() => {
    resetLinkifyCapabilities();
    bridgeMocks.openBrowser.mockReset();
    bridgeMocks.openClass.mockReset();
    bridgeMocks.openFile.mockReset();
    bridgeMocks.resolveFilePathWithCallback.mockReset();
    document.querySelectorAll('.file-link-tooltip').forEach((element) => element.remove());
  });

  /**
   * 验证正文和行内代码中的路径都会被 linkify，而 fenced code 不会。
   * 前置条件：同一段 markdown 同时包含普通路径、行内 code 路径和代码块路径。
   * 断言意图：只增强可交互正文，避免污染代码块展示。
   */
  it('linkifies inline code content but not code fence blocks', () => {
    render(
      <MarkdownBlock
        content={[
          'Open src/components/App.tsx',
          '',
          '`src/inline-code.ts` should be linkified',
          '',
          '```ts',
          'src/ignored-block.ts',
          '```',
        ].join('\n')}
      />,
    );

    const fileLink = screen.getByRole('link', { name: 'src/components/App.tsx' });
    expect(fileLink.getAttribute('data-linkify')).toBe('file');

    const inlineCodeLink = screen.getByRole('link', { name: 'src/inline-code.ts' });
    expect(inlineCodeLink.getAttribute('data-linkify')).toBe('file');
    expect(inlineCodeLink.closest('code')).toBeTruthy();

    const fencedCode = document.querySelector('pre code');
    expect(fencedCode?.textContent).toContain('src/ignored-block.ts');
    expect(fencedCode?.querySelector('a')).toBeNull();
  });

  /**
   * 验证 Java FQCN 只有在能力开启时才会被识别成类跳转链接。
   * 前置条件：同一 FQCN 在关闭和开启 classNavigationEnabled 两种状态下分别渲染。
   * 断言意图：确保类跳转能力遵循 capability 开关，不会默认误识别普通文本。
   */
  it('renders Java class links only when capability is enabled', () => {
    const fqcn = 'com.github.claudecodegui.handler.file.OpenFileHandler';

    const disabledRender = render(<MarkdownBlock content={fqcn} />);
    expect(screen.queryByRole('link', { name: fqcn })).toBeNull();
    disabledRender.unmount();

    setLinkifyCapabilities({ classNavigationEnabled: true });
    render(<MarkdownBlock content={fqcn} />);

    const classLink = screen.getByRole('link', { name: fqcn });
    expect(classLink.classList.contains('class-link')).toBe(true);
    expect(classLink.getAttribute('data-linkify')).toBe('class');
  });

  /**
   * 验证纯 URL 和 markdown 链接都会统一打上 url-link 样式。
   * 断言意图：保持 plain url 与已有 markdown anchor 的展示和点击分发一致。
   */
  it('adds url-link styling to plain URLs and markdown links', () => {
    render(
      <MarkdownBlock content={'Visit https://example.com/docs and [guide](https://example.com/guide)'} />,
    );

    const rawUrlLink = screen.getByRole('link', { name: 'https://example.com/docs' });
    const markdownLink = screen.getByRole('link', { name: 'guide' });

    expect(rawUrlLink.classList.contains('url-link')).toBe(true);
    expect(markdownLink.classList.contains('url-link')).toBe(true);
  });

  /**
   * 验证不安全协议会在 sanitize 阶段被剥离。
   * 断言意图：防止 markdown 中的 javascript: 链接进入最终 DOM。
   */
  it('strips unsafe markdown link protocols during sanitization', () => {
    render(<MarkdownBlock content={'[bad](javascript:alert(1)) and [good](https://example.com/docs)'} />);

    expect(screen.queryByRole('link', { name: 'bad' })).toBeNull();
    expect(screen.getByRole('link', { name: 'good' }).getAttribute('href')).toBe('https://example.com/docs');
  });

  /**
   * 验证 file: 协议不会被保留下来，也不会误分发给 openFile。
   * 断言意图：只允许白名单协议与本地路径语法，避免 file URI 绕过桥接规则。
   */
  it('strips file protocol links and does not route them to openFile', () => {
    render(
      <MarkdownBlock
        content={'[click](https://example.com/docs) and [local](file:///tmp/demo.txt)'}
      />,
    );

    expect(screen.queryByRole('link', { name: 'local' })).toBeNull();

    const httpsLink = screen.getByRole('link', { name: 'click' });
    expect(httpsLink.getAttribute('href')).toBe('https://example.com/docs');

    fireEvent.click(httpsLink);
    expect(bridgeMocks.openBrowser).toHaveBeenCalledWith('https://example.com/docs');
  });

  /**
   * 验证 Windows、POSIX 与显式相对路径都会被识别为文件链接。
   * 断言意图：覆盖当前主线已支持的多种路径语法，避免并轨后回退。
   */
  it('renders windows, posix, and explicit relative paths as file links', () => {
    render(
      <MarkdownBlock
        content={[
          'Windows C:\\repo\\src\\Main.java',
          '',
          'POSIX /home/user/project/src/main.ts',
          '',
          'Relative ./foo.ts and ../shared/utils.ts',
        ].join('\n')}
      />,
    );

    expect(screen.getByRole('link', { name: 'C:\\repo\\src\\Main.java' }).getAttribute('data-linkify')).toBe('file');
    expect(screen.getByRole('link', { name: '/home/user/project/src/main.ts' }).getAttribute('data-linkify')).toBe('file');
    expect(screen.getByRole('link', { name: './foo.ts' }).getAttribute('data-linkify')).toBe('file');
    expect(screen.getByRole('link', { name: '../shared/utils.ts' }).getAttribute('data-linkify')).toBe('file');
  });

  /**
   * 验证点击分发会按 linkType 调用正确 bridge helper。
   * 前置条件：同时渲染文件路径、Java FQCN 和普通 URL。
   * 断言意图：openFile / openClass / openBrowser 三条分支都不能并轨后退化。
   */
  it('dispatches clicks to the correct bridge helpers', () => {
    setLinkifyCapabilities({ classNavigationEnabled: true });

    render(
      <MarkdownBlock
        content={[
          'Open src/components/App.tsx',
          '',
          'See com.github.claudecodegui.handler.file.OpenFileHandler',
          '',
          'Visit https://example.com/docs',
        ].join('\n')}
      />
    );

    fireEvent.click(screen.getByRole('link', { name: 'src/components/App.tsx' }));
    fireEvent.click(
      screen.getByRole('link', {
        name: 'com.github.claudecodegui.handler.file.OpenFileHandler',
      }),
    );
    fireEvent.click(screen.getByRole('link', { name: 'https://example.com/docs' }));

    expect(bridgeMocks.openFile).toHaveBeenCalledWith('src/components/App.tsx');
    expect(bridgeMocks.openClass).toHaveBeenCalledWith(
      'com.github.claudecodegui.handler.file.OpenFileHandler',
    );
    expect(bridgeMocks.openBrowser).toHaveBeenCalledWith('https://example.com/docs');
  });

  /**
   * 验证包含中文的文件路径点击时，会把原始未编码路径传给 openFile。
   * 断言意图：避免 DOM/JCEF 将 href 规范化为百分号编码后，桥接层收到错误路径。
   */
  it('dispatches raw unicode file paths instead of encoded href values', () => {
    const rawPath = '/Users/demo/notes/2026-05-23-Hermes使用教程与飞书接入指南.md';

    render(<MarkdownBlock content={`Open ${rawPath}`} />);

    const fileLink = screen.getByRole('link', { name: rawPath });
    expect(fileLink.getAttribute('data-linkify')).toBe('file');

    fireEvent.click(fileLink);

    expect(bridgeMocks.openFile).toHaveBeenCalledWith(rawPath);
  });

  /**
   * 验证 streaming 阶段与 final 阶段的链接行为保持一致。
   * 断言意图：避免流式阶段能点、收尾后不能点，或者反过来只在最终态生效。
   */
  it('strips system-internal XML tags (context, commit_analysis, etc.)', () => {
    render(
      <MarkdownBlock
        content={
          'Before\n\n<context>internal system data\nshould be removed</context>\n\nAfter'
        }
      />,
    );

    const container = document.querySelector('.markdown-content')!;
    expect(container.textContent).toContain('Before');
    expect(container.textContent).toContain('After');
    expect(container.textContent).not.toContain('internal system data');
    expect(container.innerHTML).not.toContain('<context>');
  });

  it('escapes unknown XML tags as literal text', () => {
    render(
      <MarkdownBlock
        content={'Analysis:\n\n<thinking>this should be literal</thinking>\n\nDone'}
      />,
    );

    const container = document.querySelector('.markdown-content')!;
    expect(container.textContent).toContain('<thinking>');
    expect(container.textContent).toContain('this should be literal');
    expect(container.textContent).toContain('</thinking>');
    // The tag should NOT exist as a DOM element
    expect(container.querySelector('thinking')).toBeNull();
  });

  it('preserves XML tags inside code fences', () => {
    render(
      <MarkdownBlock
        content={'Example:\n\n```xml\n<context>keep this</context>\n```\n\nOutside'}
      />,
    );

    const codeBlock = document.querySelector('pre code')!;
    expect(codeBlock.textContent).toContain('<context>keep this</context>');
  });

  it('escapes self-closing XML tags', () => {
    render(<MarkdownBlock content={'Use <br/> or <item attr="val"/> here'} />);

    const container = document.querySelector('.markdown-content')!;
    expect(container.querySelector('br')).toBeNull();
    expect(container.querySelector('item')).toBeNull();
  });

  it('shows links during streaming and keeps final rendering consistent', () => {
    setLinkifyCapabilities({ classNavigationEnabled: true });

    const content = [
      'Reading src/App.tsx',
      '',
      'Class com.github.claudecodegui.handler.file.OpenFileHandler',
      '',
      'Docs https://example.com/docs',
    ].join('\n');

    const { rerender } = render(<MarkdownBlock content={content} isStreaming />);

    expect(screen.getByRole('link', { name: 'src/App.tsx' })).toBeTruthy();
    expect(
      screen.getByRole('link', {
        name: 'com.github.claudecodegui.handler.file.OpenFileHandler',
      }),
    ).toBeTruthy();
    expect(screen.getByRole('link', { name: 'https://example.com/docs' })).toBeTruthy();

    rerender(<MarkdownBlock content={content} isStreaming={false} />);

    expect(screen.getByRole('link', { name: 'src/App.tsx' })).toBeTruthy();
    expect(
      screen.getByRole('link', {
        name: 'com.github.claudecodegui.handler.file.OpenFileHandler',
      }),
    ).toBeTruthy();
    expect(screen.getByRole('link', { name: 'https://example.com/docs' })).toBeTruthy();
  });

  it('falls back to the link href when tooltip resolution returns null', () => {
    // Backend cannot produce a display path (e.g. no project root,
    // canonicalization failure). The tooltip should still show the user where
    // the link points — fall back to the raw href text.
    const absolutePath = '/home/user/project/src/main.ts';
    bridgeMocks.resolveFilePathWithCallback.mockImplementation((_path: string, callback: (result: string | null) => void) => {
      callback(null);
    });

    render(<MarkdownBlock content={`Open ${absolutePath}`} />);

    fireEvent.mouseOver(screen.getByRole('link', { name: absolutePath }), {
      clientX: 10,
      clientY: 10,
    });

    expect(document.querySelector('.file-link-tooltip')?.textContent).toBe(absolutePath);
  });

  it('shows the resolved tooltip text when backend returns a path', () => {
    bridgeMocks.resolveFilePathWithCallback.mockImplementation((_path: string, callback: (result: string | null) => void) => {
      callback('src/main.ts');
    });

    render(<MarkdownBlock content={'Open /home/user/project/src/main.ts'} />);

    fireEvent.mouseOver(screen.getByRole('link', { name: '/home/user/project/src/main.ts' }), {
      clientX: 10,
      clientY: 10,
    });

    expect(document.querySelector('.file-link-tooltip')?.textContent).toBe('src/main.ts');
  });

  it('renders inline code with XML tags consistently across streaming and non-streaming', () => {
    const content = 'Use `<div>` and `<custom-tag>` here';

    // Test streaming path
    const { rerender } = render(<MarkdownBlock content={content} isStreaming />);

    const streamingCodeElements = document.querySelectorAll('code');
    expect(streamingCodeElements.length).toBe(2);

    // Both should display the tag as literal text <div> and <custom-tag>
    expect(streamingCodeElements[0].textContent).toBe('<div>');
    expect(streamingCodeElements[1].textContent).toBe('<custom-tag>');

    // No actual DOM elements should exist for these tags
    expect(document.querySelector('div.custom-tag')).toBeNull();
    expect(document.querySelector('custom-tag')).toBeNull();

    // Test non-streaming path
    rerender(<MarkdownBlock content={content} isStreaming={false} />);

    const nonStreamingCodeElements = document.querySelectorAll('code');
    expect(nonStreamingCodeElements.length).toBe(2);

    // Should match streaming output exactly
    expect(nonStreamingCodeElements[0].textContent).toBe('<div>');
    expect(nonStreamingCodeElements[1].textContent).toBe('<custom-tag>');
  });

  // Based on real conversation from session JSONL
  it('renders multi-paragraph content with code blocks correctly', () => {
    const realContent = [
      '这是一个很好的调查方向。让我深入分析这个问题，同时涉及 Claude CLI 源码和插件的处理逻辑。',
      '',
      '先并行调查几个关键区域：',
    ].join('\n');

    render(<MarkdownBlock content={realContent} />);

    const container = document.querySelector('.markdown-content')!;
    expect(container.textContent).toContain('这是一个很好的调查方向');
    expect(container.textContent).toContain('先并行调查几个关键区域');
  });

  it('strips command-message XML tags from skill prompts', () => {
    // Real message format from JSONL: command-message wrapper
    const content = [
      'Before text',
      '',
      '<command-message>opsx:explore</command-message>',
      '<command-name>/opsx:explore</command-name>',
      '<command-args>investigate the bug</command-args>',
      '',
      'After text',
    ].join('\n');

    render(<MarkdownBlock content={content} />);

    const container = document.querySelector('.markdown-content')!;
    // Command tags should be escaped as literal text, not parsed as DOM
    expect(container.querySelector('command-message')).toBeNull();
    expect(container.querySelector('command-name')).toBeNull();
  });

  it('handles inline code with file paths and preserves content during streaming transition', () => {
    // Real content pattern from JSONL: file paths in inline code
    const content = [
      'The file `E:/project/ClaudeCodeRev/src/commands/compact/compact.ts` contains the handler.',
      '',
      'Also see `src/services/compact/compact.ts` for the service.',
    ].join('\n');

    const { rerender } = render(<MarkdownBlock content={content} isStreaming />);

    // Streaming: inline code should contain the full path
    const streamingCodes = document.querySelectorAll('code');
    expect(streamingCodes[0].textContent).toBe('E:/project/ClaudeCodeRev/src/commands/compact/compact.ts');
    expect(streamingCodes[1].textContent).toBe('src/services/compact/compact.ts');

    // Transition to non-streaming
    rerender(<MarkdownBlock content={content} isStreaming={false} />);

    const finalCodes = document.querySelectorAll('code');
    expect(finalCodes[0].textContent).toBe('E:/project/ClaudeCodeRev/src/commands/compact/compact.ts');
    expect(finalCodes[1].textContent).toBe('src/services/compact/compact.ts');
  });

  it('handles complex markdown with nested structures', () => {
    // Complex content from real conversation: headings, lists, code blocks
    const complexContent = [
      '## Detailed Report: `/compact` Command Implementation',
      '',
      '### 1. Command Definition and Entry Points',
      '',
      '**Command Registration:**',
      '- `E:/project/ClaudeCodeRev/src/commands/compact/index.ts`',
      '- Defines the command metadata',
      '',
      '**Command Handler:**',
      '- `compact.ts` contains the `call` function',
      '',
      '```typescript',
      'const command = {',
      '  type: "local",',
      '  name: "compact",',
      '  supportsNonInteractive: true',
      '};',
      '```',
    ].join('\n');

    render(<MarkdownBlock content={complexContent} />);

    // Heading should be rendered
    expect(document.querySelector('h2')).toBeTruthy();
    expect(document.querySelector('h3')).toBeTruthy();

    // List items should be present
    const listItems = document.querySelectorAll('li');
    expect(listItems.length).toBeGreaterThan(0);

    // Code block should preserve content
    const codeBlock = document.querySelector('pre code');
    expect(codeBlock?.textContent).toContain('const command');
    expect(codeBlock?.textContent).toContain('type: "local"');
  });

  it('handles incremental streaming with partial XML tags without DOM corruption', () => {
    // Simulates SSE incremental updates where XML tags arrive in fragments
    // Critical: during streaming, content may pause mid-tag (e.g. "<command-")
    // and the renderer must not parse incomplete tags as real DOM.
    const chunks = [
      'Here is the code: `<command-',
      'Here is the code: `<command-name>',
      'Here is the code: `<command-name>/compact</command-name>',
      'Here is the code: `<command-name>/compact</command-name>` for compacting.',
    ];

    const { rerender } = render(<MarkdownBlock content={chunks[0]} isStreaming />);

    // Each chunk should render without throwing or creating phantom DOM elements
    chunks.forEach((chunk) => {
      rerender(<MarkdownBlock content={chunk} isStreaming />);

      // No phantom <command-name> DOM element should ever appear
      expect(document.querySelector('command-name')).toBeNull();
    });

    // Final transition to non-streaming should match the last streaming render
    rerender(<MarkdownBlock content={chunks[chunks.length - 1]} isStreaming={false} />);

    const finalCode = document.querySelector('code');
    expect(finalCode?.textContent).toBe('<command-name>/compact</command-name>');
    expect(document.querySelector('command-name')).toBeNull();
  });

  it('preserves inline code with angle brackets from real error messages', () => {
    // Real pattern: error messages with type parameters like <T>
    const content = 'Use `Array<T>` or `Map<string, number>` for generic types.';

    const { rerender } = render(<MarkdownBlock content={content} isStreaming />);

    const streamingCodes = document.querySelectorAll('code');
    expect(streamingCodes[0].textContent).toBe('Array<T>');
    expect(streamingCodes[1].textContent).toBe('Map<string, number>');

    // No actual DOM elements for T or string
    expect(document.querySelector('T')).toBeNull();

    rerender(<MarkdownBlock content={content} isStreaming={false} />);

    const finalCodes = document.querySelectorAll('code');
    expect(finalCodes[0].textContent).toBe('Array<T>');
    expect(finalCodes[1].textContent).toBe('Map<string, number>');
  });
});
