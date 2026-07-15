import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import path from 'node:path';

const cwd = process.cwd();
const distFile = path.resolve(cwd, 'dist/index.html');
const targetFile = path.resolve(cwd, '../src/main/resources/html/claude-chat.html');
const webviewBundleSha256Placeholder = '__CC_GUI_WEBVIEW_BUNDLE_SHA256__';

const main = async () => {
  const html = await readFile(distFile, 'utf-8');
  // 中文注释：这里对包含占位符的模板态 HTML 计算摘要，再把摘要写回最终产物，
  // 这样前端日志与插件资源指纹都基于同一份归一化内容，不会因为“哈希写回自身”而天然失配。
  const webviewBundleSha256 = createHash('sha256').update(html, 'utf-8').digest('hex');
  const stampedHtml = html.replaceAll(webviewBundleSha256Placeholder, webviewBundleSha256);
  await mkdir(path.dirname(targetFile), { recursive: true });
  await writeFile(targetFile, stampedHtml, 'utf-8');
  console.log(`[copy-dist] 已同步 ${distFile} -> ${targetFile}, webviewBundleSha256=${webviewBundleSha256}`);
};

main().catch((error) => {
  console.error('[copy-dist] 复制构建产物失败', error);
  process.exit(1);
});
