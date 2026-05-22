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
}));

vi.mock('../utils/bridge', () => ({
  openBrowser: bridgeMocks.openBrowser,
  openClass: bridgeMocks.openClass,
  openFile: bridgeMocks.openFile,
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
   * 验证 streaming 阶段与 final 阶段的链接行为保持一致。
   * 断言意图：避免流式阶段能点、收尾后不能点，或者反过来只在最终态生效。
   */
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
});
