const FILE_PATH_PATTERN =
  /(^|[\s([>{'"])((?:(?:[A-Za-z]:[\\/])|(?:\.{1,2}[\\/])|\/)?(?:[\w.-]+[\\/])+[\w.-]+\.[A-Za-z0-9]+(?::\d+(?:-\d+)?)?)(?=$|[\s)\]<'"},;!?])/g;
const STANDALONE_FILENAME_PATTERN =
  /(^|[\s([>{'"])([A-Za-z0-9._-]+\.[A-Za-z]{2,})(?=$|[\s)\]<'"},;!?])/g;
const JAVA_FQCN_PATTERN =
  /(^|[\s([>{'"])((?:[a-z_][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_]*)(?=$|[\s)\]<'"},;!?])/g;

const SOURCE_FILE_EXTENSIONS = new Set([
  'adoc', 'asp', 'aspx', 'astro', 'avsc', 'bash', 'bat', 'blade', 'c', 'cc', 'cfg', 'clj', 'cljs', 'cljc',
  'cmd', 'cmake', 'conf', 'config', 'cpp', 'cs', 'css', 'cts', 'cxx', 'dart', 'ddl', 'dml', 'dockerfile',
  'editorconfig', 'ejs', 'env', 'erb', 'ex', 'exs', 'fish', 'fs', 'fsx', 'ftl', 'gitignore', 'go', 'gql',
  'gradle', 'graphql', 'groovy', 'h', 'hbs', 'hcl', 'hpp', 'hrl', 'hs', 'htm', 'html', 'hxx', 'ini', 'java',
  'js', 'json', 'jsp', 'jspx', 'jsx', 'kts', 'kt', 'latex', 'less', 'lock', 'log', 'lua', 'markdown', 'maven',
  'md', 'mk', 'mjs', 'mod', 'mustache', 'mts', 'nim', 'njk', 'php', 'phtml', 'pl', 'pm', 'prefs', 'properties',
  'proto', 'ps1', 'py', 'pyi', 'pyw', 'r', 'R', 'rb', 'rake', 'rc', 'rs', 'rst', 'sass', 'scala', 'scss',
  'sh', 'sol', 'sql', 'sum', 'svg', 'svelte', 'swift', 'tex', 'tf', 'thrift', 'toml', 'ts', 'tsv', 'tsx',
  'twig', 'txt', 'vb', 'vm', 'vue', 'xml', 'yaml', 'yml', 'zig', 'zsh',
]);

const COMMON_LOWERCASE_FILE_NAMES = [
  'readme', 'changelog', 'license', 'contributing', 'todo', 'notes', 'makefile', 'dockerfile', 'vagrantfile',
  'gemfile', 'rakefile', 'procfile', 'bower', 'package', 'tsconfig', 'webpack', 'rollup', 'vite', 'eslint',
  'prettier', 'stylelint', 'babel', 'jest', 'karma', 'mocha', 'nyc', 'istanbul', 'svgo', 'postcss',
];

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
 * 判断一个独立文件名是否适合在正文里被当作可点击源码文件处理。
 * 这里刻意比“任意点号字符串都可点击”更保守，避免把域名、包名片段误判成文件名。
 *
 * @param fileName 当前命中的独立文件名文本
 * @return 若命中白名单且满足命名约束则返回 true
 */
function isStandaloneSourceFileName(fileName: string): boolean {
  if (!fileName || fileName.length < 3) {
    return false;
  }

  const lastDotIndex = fileName.lastIndexOf('.');
  if (lastDotIndex <= 0 || lastDotIndex >= fileName.length - 1) {
    return false;
  }

  const namePart = fileName.slice(0, lastDotIndex);
  const extension = fileName.slice(lastDotIndex + 1).toLowerCase();
  if (!SOURCE_FILE_EXTENSIONS.has(extension)) {
    return false;
  }

  // 对全小写文件名做额外约束，降低 example.com 这类文本被误链化的概率。
  if (/^[a-z][a-z0-9._-]*$/.test(namePart) && !/[A-Z]/.test(namePart)) {
    const lowerName = namePart.toLowerCase();
    if (namePart.includes('_') || namePart.includes('-') || namePart.includes('.')) {
      return true;
    }

    if (COMMON_LOWERCASE_FILE_NAMES.some((item) => lowerName.startsWith(item) || lowerName.endsWith(item))) {
      return true;
    }
  }

  return true;
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
 * 将正文里的独立源码文件名替换成锚点。
 * 这里只处理白名单扩展名，且复用项目根相对路径打开逻辑，不改动现有桥接协议。
 *
 * @param textNode 当前待处理文本节点
 */
function replaceTextNodeWithStandaloneFileAnchors(textNode: Text): void {
  const text = textNode.textContent ?? '';
  STANDALONE_FILENAME_PATTERN.lastIndex = 0;
  if (!STANDALONE_FILENAME_PATTERN.test(text)) {
    return;
  }

  STANDALONE_FILENAME_PATTERN.lastIndex = 0;
  const fragment = document.createDocumentFragment();
  let lastIndex = 0;

  text.replace(STANDALONE_FILENAME_PATTERN, (match, prefix: string, fileName: string, offset: number) => {
    const fileStart = offset + prefix.length;

    if (!isStandaloneSourceFileName(fileName)) {
      return match;
    }

    if (fileStart > lastIndex) {
      fragment.appendChild(document.createTextNode(text.slice(lastIndex, fileStart)));
    }

    const anchor = document.createElement('a');
    anchor.href = fileName;
    anchor.textContent = fileName;
    fragment.appendChild(anchor);

    lastIndex = fileStart + fileName.length;
    return match;
  });

  if (lastIndex === 0) {
    return;
  }

  if (lastIndex < text.length) {
    fragment.appendChild(document.createTextNode(text.slice(lastIndex)));
  }

  textNode.parentNode?.replaceChild(fragment, textNode);
}

/**
 * 将单个文本节点中的 Java FQCN 替换成类跳转锚点。
 * 第一版只接受包名全小写、末段类名首字母大写的 FQCN，
 * 以尽量降低把普通句子误判成类名的概率。
 *
 * @param textNode 当前待处理文本节点
 */
function replaceTextNodeWithClassAnchors(textNode: Text): void {
  const text = textNode.textContent ?? '';
  JAVA_FQCN_PATTERN.lastIndex = 0;
  if (!JAVA_FQCN_PATTERN.test(text)) {
    return;
  }

  JAVA_FQCN_PATTERN.lastIndex = 0;
  const fragment = document.createDocumentFragment();
  let lastIndex = 0;

  text.replace(JAVA_FQCN_PATTERN, (match, prefix: string, className: string, offset: number) => {
    const classStart = offset + prefix.length;

    if (classStart > lastIndex) {
      fragment.appendChild(document.createTextNode(text.slice(lastIndex, classStart)));
    }

    const anchor = document.createElement('a');
    anchor.href = `class:${className}`;
    anchor.textContent = className;
    anchor.setAttribute('data-link-type', 'java-class');
    fragment.appendChild(anchor);

    lastIndex = classStart + className.length;
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
  const standaloneFileTextNodes: Text[] = [];
  const standaloneFileWalker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  while (standaloneFileWalker.nextNode()) {
    const textNode = standaloneFileWalker.currentNode as Text;
    if (!textNode.textContent?.trim() || shouldSkipNode(textNode)) {
      continue;
    }
    standaloneFileTextNodes.push(textNode);
  }
  standaloneFileTextNodes.forEach(replaceTextNodeWithStandaloneFileAnchors);
  const classTextNodes: Text[] = [];
  const classWalker = doc.createTreeWalker(doc.body, NodeFilter.SHOW_TEXT);
  while (classWalker.nextNode()) {
    const textNode = classWalker.currentNode as Text;
    if (!textNode.textContent?.trim() || shouldSkipNode(textNode)) {
      continue;
    }
    classTextNodes.push(textNode);
  }
  classTextNodes.forEach(replaceTextNodeWithClassAnchors);
  return doc.body.innerHTML.trim();
}
