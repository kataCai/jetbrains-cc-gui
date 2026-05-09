import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import MarkdownBlock from './MarkdownBlock';

const openBrowserMock = vi.fn();
const openFileMock = vi.fn();

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
    i18n: { language: 'zh' },
  }),
}));

vi.mock('../utils/bridge', () => ({
  openBrowser: (...args: unknown[]) => openBrowserMock(...args),
  openFile: (...args: unknown[]) => openFileMock(...args),
}));

describe('MarkdownBlock', () => {
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
});
