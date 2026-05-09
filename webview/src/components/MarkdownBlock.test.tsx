import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MarkdownBlock from './MarkdownBlock';

const openBrowserMock = vi.fn();
const openFileMock = vi.fn();
const openClassMock = vi.fn();

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'zh' },
  }),
}));

vi.mock('../utils/bridge', () => ({
  openBrowser: (...args: unknown[]) => openBrowserMock(...args),
  openFile: (...args: unknown[]) => openFileMock(...args),
  openClass: (...args: unknown[]) => openClassMock(...args),
}));

describe('MarkdownBlock', () => {
  beforeEach(() => {
    openBrowserMock.mockReset();
    openFileMock.mockReset();
    openClassMock.mockReset();
  });

  it('opens local file paths rendered from markdown prose', () => {
    render(<MarkdownBlock content="请查看 src/main/App.tsx:42" />);

    const anchor = screen.getByRole('link', { name: 'src/main/App.tsx:42' });
    fireEvent.click(anchor);

    expect(openFileMock).toHaveBeenCalledWith('src/main/App.tsx:42');
    expect(openBrowserMock).not.toHaveBeenCalled();
  });

  it('does not convert file paths inside fenced code blocks into links', () => {
    render(<MarkdownBlock content={'```ts\nsrc/main/App.tsx:42\n```'} />);

    expect(screen.queryByRole('link', { name: 'src/main/App.tsx:42' })).toBeNull();
    expect(document.querySelector('pre code')?.textContent).toContain('src/main/App.tsx:42');
  });

  it('opens java fqcn rendered from markdown prose via openClass', () => {
    render(<MarkdownBlock content="请查看 com.github.claudecodegui.handler.file.OpenFileHandler" />);

    const anchor = screen.getByRole('link', {
      name: 'com.github.claudecodegui.handler.file.OpenFileHandler',
    });
    fireEvent.click(anchor);

    expect(openClassMock).toHaveBeenCalledWith(
      'com.github.claudecodegui.handler.file.OpenFileHandler'
    );
    expect(openFileMock).not.toHaveBeenCalled();
    expect(openBrowserMock).not.toHaveBeenCalled();
  });

  it('does not convert ordinary dotted prose into class links', () => {
    render(<MarkdownBlock content="this.is.just.a.normal.sentence without Java class meaning" />);

    expect(screen.queryByRole('link', { name: 'this.is.just.a.normal.sentence' })).toBeNull();
  });
});
