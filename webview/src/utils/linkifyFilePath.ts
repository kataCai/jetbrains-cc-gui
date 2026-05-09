const FILE_PATH_PATTERN =
  /(^|[\s([>{'"])((?:(?:[A-Za-z]:[\\/])|(?:\.{1,2}[\\/])|\/)?(?:[\w.-]+[\\/])+[\w.-]+\.[A-Za-z0-9]+(?::\d+(?:-\d+)?)?)(?=$|[\s)\]<'"},;!?])/g;

/**
 * 判断当前文本节点是否位于不允许做文件路径增强的容器里。
 * 这里显式跳过链接、代码块和按钮，避免重复包裹或破坏已有交互结构。
 *
 * @param node 当前待处理文本节点
 * @return 若应跳过增强则返回 true
 */
function shouldSkipNode(node: Node): boolean {
  let current = node.parentElement;
  while (current) {
    const tagName = current.tagName;
    if (tagName === 'A' || tagName === 'CODE' || tagName === 'PRE' || tagName === 'BUTTON') {
      return true;
    }
    current = current.parentElement;
  }
  return false;
}

/**
 * 将单个文本节点中的文件路径替换成锚点。
 * 这里只识别带目录层级的路径，并要求带扩展名，避免把普通单词或类名误判成文件。
 *
 * @param textNode 当前待处理文本节点
 */
function replaceTextNodeWithAnchors(textNode: Text): void {
  const text = textNode.textContent ?? '';
  FILE_PATH_PATTERN.lastIndex = 0;
  if (!FILE_PATH_PATTERN.test(text)) {
    return;
  }

  FILE_PATH_PATTERN.lastIndex = 0;
  const fragment = document.createDocumentFragment();
  let lastIndex = 0;

  text.replace(FILE_PATH_PATTERN, (match, prefix: string, filePath: string, offset: number) => {
    const pathStart = offset + prefix.length;

    if (pathStart > lastIndex) {
      fragment.appendChild(document.createTextNode(text.slice(lastIndex, pathStart)));
    }

    const anchor = document.createElement('a');
    anchor.href = filePath;
    anchor.textContent = filePath;
    fragment.appendChild(anchor);

    lastIndex = pathStart + filePath.length;
    return match;
  });

  if (lastIndex < text.length) {
    fragment.appendChild(document.createTextNode(text.slice(lastIndex)));
  }

  textNode.parentNode?.replaceChild(fragment, textNode);
}

/**
 * 对已生成的 HTML 结果做轻量级文件路径增强。
 * 只在普通正文文本节点里把形如 `src/main/App.tsx:42` 的路径包装成链接，
 * 后续点击时复用现有 open_file 桥接能力，不改动后端协议。
 *
 * @param html 已经过 markdown 解析和安全清洗的 HTML 字符串
 * @return 增强后的 HTML 字符串
 */
export function linkifyFilePathHtml(html: string): string {
  if (!html || typeof DOMParser === 'undefined' || typeof document === 'undefined') {
    return html;
  }

  const doc = new DOMParser().parseFromString(html, 'text/html');
  const walker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  const textNodes: Text[] = [];

  while (walker.nextNode()) {
    const textNode = walker.currentNode as Text;
    if (!textNode.textContent?.trim() || shouldSkipNode(textNode)) {
      continue;
    }
    textNodes.push(textNode);
  }

  textNodes.forEach(replaceTextNodeWithAnchors);
  return doc.body.innerHTML.trim();
}
