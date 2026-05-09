import { describe, expect, it } from 'vitest';
import { linkifyFilePathHtml } from './linkifyFilePath';

describe('linkifyFilePathHtml', () => {
  it('converts plain file paths with line suffix into clickable anchors', () => {
    const html = linkifyFilePathHtml('<p>See src/main/App.tsx:42 for details.</p>');
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const anchor = doc.querySelector('a');

    expect(anchor?.getAttribute('href')).toBe('src/main/App.tsx:42');
    expect(anchor?.textContent).toBe('src/main/App.tsx:42');
  });

  it('converts windows file paths with line range suffix into clickable anchors', () => {
    const html = linkifyFilePathHtml('<p>Open C:\\workspace\\demo\\App.tsx:12-18 please.</p>');
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const anchor = doc.querySelector('a');

    expect(anchor?.getAttribute('href')).toBe('C:\\workspace\\demo\\App.tsx:12-18');
    expect(anchor?.textContent).toBe('C:\\workspace\\demo\\App.tsx:12-18');
  });

  it('keeps http links untouched instead of re-linkifying path-like text inside hrefs', () => {
    const html = linkifyFilePathHtml(
      '<p><a href="https://example.com/src/main/App.tsx:42">https://example.com/src/main/App.tsx:42</a></p>'
    );
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const anchors = doc.querySelectorAll('a');

    expect(anchors).toHaveLength(1);
    expect(anchors[0]?.getAttribute('href')).toBe('https://example.com/src/main/App.tsx:42');
  });

  it('does not linkify file paths inside pre/code blocks', () => {
    const html = linkifyFilePathHtml('<pre><code>src/main/App.tsx:42</code></pre>');
    const doc = new DOMParser().parseFromString(html, 'text/html');

    expect(doc.querySelector('a')).toBeNull();
    expect(doc.querySelector('code')?.textContent).toBe('src/main/App.tsx:42');
  });
});
