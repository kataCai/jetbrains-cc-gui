# 聊天图片右键复制与预览弹层收敛分析

## 1. 需求背景

本次需求来自两个明确的交互问题：

1. 聊天窗口中的图片缩略图需要支持右键复制图片。
2. 点击缩略图进入预览弹层后，不再展示“复制图片”按钮，改为仅保留右键复制图片能力。

结合当前 worktree `feat/chat-image-copy-20260624` 的未提交实现，这不是一个从零开始的需求，而是对“图片复制能力”第一轮实现的交互收口：

- 当前分支已经补齐了图片复制桥接能力。
- 当前分支已经让消息图片、Markdown 图片、输入区附件图片具备一定的复制元数据。
- 但不同入口的交互方式还没有完全统一，尤其是“输入区附件缩略图右键复制”和“预览弹层复制按钮去除”这两个点仍未闭环。

## 2. 分析范围

本次分析只围绕前端聊天图片相关入口，不扩展到无关功能。

重点阅读与判断的文件如下：

- `webview/src/components/ChatInputBox/AttachmentList.tsx`
- `webview/src/components/ImagePreviewDialog.tsx`
- `webview/src/components/MessageList.tsx`
- `webview/src/hooks/useContextMenu.ts`
- `webview/src/utils/imageClipboard.ts`
- `webview/src/components/MarkdownBlock.tsx`
- `webview/src/components/MessageItem/ContentBlockRenderer.tsx`
- `webview/src/components/ContextMenu/ContextMenu.tsx`
- 对应测试文件 `AttachmentList.test.tsx`、`MarkdownBlock.test.tsx`、`useContextMenu.test.ts`

## 3. 现状分析

### 3.1 当前复制链路已经具备的能力

从 `webview/src/utils/imageClipboard.ts` 可以看出，这个分支已经完成了图片复制的基础设施建设：

- 已定义 `CopyableImageSource`，用于统一抽象图片来源。
- 已定义 `buildCopyableImageDataset()`，用于把图片来源写入 DOM 的 `data-*` 属性。
- 已定义 `getCopyableImageSourceFromElement()`，用于从任意命中的图片节点恢复复制上下文。
- 已定义 `copyImageViaBridge()`，最终通过 `sendToJava('write_clipboard_image', payload)` 走 Java 桥接写入系统剪贴板。

这说明当前分支的核心问题已经不再是“能不能复制图片”，而是“哪些入口能触发复制”“复制入口是否一致”“交互是否符合预期”。

### 3.2 聊天消息区域已经支持右键图片复制

`webview/src/hooks/useContextMenu.ts` 当前会在 `open()` 时执行：

- 从事件目标上调用 `getCopyableImageSourceFromElement()`。
- 如果命中图片节点，则把 `imageTarget` 存入上下文菜单状态。

`webview/src/components/MessageList.tsx` 会根据 `ctxMenu.imageTarget` 动态插入菜单项：

- 文案为 `contextMenu.copyImage`
- 动作为 `copyImageSelection(ctxMenu.imageTarget)`

再结合：

- `MarkdownBlock.tsx` 会给渲染后的 Markdown 图片补 `data-copy-image-*`
- `ContentBlockRenderer.tsx` 会给消息图片块补 `data-copy-image-*`

可以确认：

- 聊天消息列表中的 Markdown 图片
- 聊天消息中的独立图片块

这两类图片已经接入右键复制链路。

### 3.3 输入区附件缩略图没有接入右键菜单作用域

`webview/src/components/ChatInputBox/AttachmentList.tsx` 当前已经做了两件事：

- 给缩略图补上了 `buildCopyableImageDataset(imageSource)`
- 支持聚焦后用 `Ctrl/Cmd+C` 复制图片

但是它没有做第三件关键事情：

- 没有接入 `useContextMenu()`
- 没有在自己的 DOM 作用域挂 `onContextMenu`
- 没有渲染 `ContextMenu`

而 `MessageList.tsx` 的右键菜单只包裹消息列表自身的容器：

```tsx
<div onContextMenu={handleMessageContextMenu}>
```

这意味着虽然附件缩略图节点上已经有 `data-copy-image-*`，但右键事件不会被消息列表容器捕获，因此图 1 的“输入区图片缩略图右键复制图片”当前实际上还没真正打通。

这个结论非常关键：

- 现有实现不是“完全没做”。
- 而是“只做到了图片元数据和快捷键复制，没把输入区附件接进右键菜单宿主”。

### 3.4 统一预览弹层与需求冲突

`webview/src/components/ImagePreviewDialog.tsx` 当前是一个统一弹层组件，已经被多个入口复用：

- `AttachmentList.tsx` 的输入区附件预览
- `MarkdownBlock.tsx` 的 Markdown 图片预览
- `ContentBlockRenderer.tsx` 的消息图片预览

当前弹层会始终渲染：

- 一颗 `image-preview-copy` 按钮
- 一颗 `image-preview-close` 按钮

这与需求 2 冲突：

- 需求要求点击缩略图进入预览后，不要展示“复制图片”按钮。
- 复制操作应保留，但通过右键菜单完成，而不是按钮显式展示。

因此当前问题不是某一个入口单独渲染错了，而是统一组件 `ImagePreviewDialog` 的交互策略本身需要调整。

### 3.5 当前预览弹层已经具备“无按钮复制”的技术前提

虽然 UI 还不符合要求，但技术上其实已经具备改造条件：

- `ImagePreviewDialog.tsx` 会在弹层打开时 `setActiveImageTarget(image)`
- 弹层根节点和图片节点都有 `data-copy-image-*`
- 弹层已经支持 `Ctrl/Cmd+C` 调用 `copyImageViaBridge(image)`

这说明去掉“复制图片”按钮后，不会导致图片复制能力彻底消失。只要再把右键菜单作用域扩展到预览弹层，就能满足：

- 不展示复制按钮
- 仍支持右键复制图片

### 3.6 现有测试与目标行为不一致

当前测试里已经固化了旧行为：

- `MarkdownBlock.test.tsx` 断言预览弹层里存在 `.image-preview-copy`
- 测试名称也在描述“with copy and close actions”

这与新需求直接矛盾。也就是说，如果按需求调整实现，测试必须同步修改，否则会形成“代码符合需求、测试却锁死旧交互”的回归阻力。

### 3.7 代码编码污染是并行风险，不应忽略

当前 worktree 中可见到部分新文件存在编码污染痕迹，尤其体现在：

- 部分中文注释显示异常
- 个别文案/断言历史上出现过错误字符
- 某些测试当前还保留着错误字符相关断言痕迹

这不是本需求的主线，但它会影响本轮修改质量，原因是：

- 这次需要新增或修改中文注释与测试断言。
- 如果继续在编码不稳定的文件里直接补逻辑，容易把界面文本和测试预期继续污染。

因此应把“保持 UTF-8、避免把终端显示乱码误写回文件”列为本次改造的约束条件。

## 4. 根因归纳

综合上面的代码阅读，可以把问题根因归纳为两类。

### 4.1 右键菜单宿主范围不一致

当前右键复制图片能力依赖于两步：

1. 图片节点具备 `data-copy-image-*`
2. 所在区域挂载 `useContextMenu + ContextMenu`

消息列表满足这两步，输入区附件只满足第一步，不满足第二步，所以图 1 场景失效。

### 4.2 统一预览组件过度暴露显式复制入口

`ImagePreviewDialog` 被设计成“任何预览入口都显示复制按钮”，但本次产品要求是：

- 预览负责查看和关闭
- 复制通过右键菜单完成

因此统一组件需要从“固定展示复制按钮”改为“默认不展示复制按钮，复制能力转由右键菜单承接”。

## 5. 修改方案

### 5.1 总体方案选择

推荐采用“保留统一图片复制基础设施，补齐两个独立右键菜单宿主，并移除预览弹层复制按钮”的方案。

原因如下：

- 现有 `imageClipboard.ts`、`useContextMenu.ts`、`data-copy-image-*` 已经形成可复用基础设施，不需要推倒重来。
- 输入区附件和预览弹层本质上都只缺“右键菜单承载层”，不是缺底层复制能力。
- 统一收敛到右键复制后，交互模型更稳定，不会出现“某些图片有复制按钮、某些图片只有右键菜单”的割裂。

### 5.2 具体改法

#### 方案 A：让 `AttachmentList` 自己托管右键菜单

在 `AttachmentList.tsx` 内：

- 引入 `useContextMenu`
- 引入 `ContextMenu`
- 在附件列表容器或缩略图节点上挂 `onContextMenu`
- 当 `ctxMenu.imageTarget` 存在时，展示“复制图片”菜单项

优点：

- 作用域清晰，输入区附件与消息列表互不耦合
- 改动面小，不需要把消息区上下文菜单逻辑抬升到更高层
- 适合当前仓库结构

缺点：

- `MessageList` 和 `AttachmentList` 会各自持有一套 `useContextMenu + ContextMenu` 组合，存在轻微重复

这个缺点当前可接受，因为两者分属不同 UI 区域，生命周期和命中节点都不同。

#### 方案 B：让 `ImagePreviewDialog` 支持可配置的右键菜单宿主

在 `ImagePreviewDialog.tsx` 内：

- 去掉 `image-preview-copy` 按钮及其文案
- 保留 `Esc` 关闭、点击遮罩关闭、`Ctrl/Cmd+C` 复制
- 新增右键菜单支持：对弹层根节点或图片节点使用 `useContextMenu`
- 菜单仅展示“复制图片”和必要的关闭逻辑，不再渲染显式复制按钮

优点：

- 所有进入预览的图片都自动具备统一右键复制能力
- 满足图 2 的视觉要求
- 不需要每个调用方单独处理预览态右键复制

缺点：

- 需要谨慎处理弹层点击关闭与右键菜单弹出的事件竞争

这个风险可控，因为现有 `ContextMenu` 本身已经使用 portal，且依赖 `mousedown` 外部点击关闭，不需要额外重写组件结构。

### 5.3 不推荐的方案

#### 不推荐方案 1：保留复制按钮，同时再加右键菜单

不推荐原因：

- 与用户明确要求冲突
- 弹层视觉噪音仍然存在
- 两套复制入口并存，增加维护成本

#### 不推荐方案 2：只删按钮，不补预览态右键菜单

不推荐原因：

- 会让图 2 看起来满足需求，但实际上预览态复制能力被弱化
- 用户进入预览后如果没有意识到还能 `Ctrl/Cmd+C`，会误认为复制功能被删掉

#### 不推荐方案 3：把所有右键菜单提升到聊天页根组件统一处理

不推荐原因：

- 这会扩大本次改动范围
- 需要同时梳理消息列表、输入区、弹层三类命中区域的坐标和生命周期
- 对当前需求而言过重

## 6. 建议实施步骤

### Step 1：补齐输入区附件缩略图的右键菜单

目标：满足图 1。

实施要点：

- 在 `AttachmentList.tsx` 接入 `useContextMenu`
- 引入 `ContextMenu` 组件
- 右键命中图片缩略图时展示“复制图片”
- 对非图片附件不展示该菜单项

### Step 2：移除预览弹层的复制按钮

目标：满足图 2 的视觉要求。

实施要点：

- 删除 `ImagePreviewDialog.tsx` 中 `.image-preview-copy`
- 保留关闭按钮与关闭逻辑
- 保留 `Ctrl/Cmd+C` 快捷键复制能力

### Step 3：给预览弹层补右键复制图片

目标：避免去掉按钮后预览态失去显式复制入口。

实施要点：

- 在 `ImagePreviewDialog.tsx` 内接入 `useContextMenu`
- 对当前预览图片展示“复制图片”菜单项
- 验证右键菜单不会触发遮罩关闭

### Step 4：更新测试，删除对旧复制按钮的依赖

目标：让测试和产品行为保持一致。

实施要点：

- `MarkdownBlock.test.tsx` 从“存在复制按钮”改为“预览弹层不存在复制按钮，但可进入统一预览”
- 新增 `AttachmentList.test.tsx` 对输入区图片缩略图右键菜单的覆盖
- 必要时新增 `ImagePreviewDialog` 级别测试，覆盖“无复制按钮 + 右键菜单复制”

## 7. 风险与边界

### 7.1 右键菜单与弹层关闭事件冲突

风险：

- 右键时如果遮罩层点击逻辑误触发，菜单可能刚弹出就关闭或预览先消失。

应对：

- 明确使用 `onContextMenu` 拦截默认行为
- 确保关闭逻辑只响应左键点击遮罩或明确的关闭动作

### 7.2 多菜单实例并存

风险：

- `MessageList`、`AttachmentList`、`ImagePreviewDialog` 都可能拥有自己的 `ContextMenu`

应对：

- 由于三者作用域分离，短期内允许局部重复
- 本轮先以低风险闭环为目标，不做过早抽象

### 7.3 编码污染继续扩散

风险：

- 这批文件中已经有中文注释与测试文本受到终端编码影响

应对：

- 修改前后都用 UTF-8 方式读取关键文件确认
- 不把终端展示乱码当作源码真实内容盲目回写
- 修改可见文案和测试断言时优先关注最终文件内容而非终端渲染效果

## 8. 结论

当前 worktree 已经完成了“图片复制基础设施”这一步，但还没完成“入口交互统一”这一步。

对本次需求而言，最合适的收口方式是：

- 给输入区附件缩略图补右键菜单宿主
- 去掉统一预览弹层中的复制按钮
- 把预览态图片复制能力统一收敛到右键菜单

这样可以最小代价复用已有桥接实现，同时让图 1、图 2 两个交互点都满足要求。

---

## 9. 修改方案摘要

- 修改 `webview/src/components/ChatInputBox/AttachmentList.tsx`
  - 接入 `useContextMenu` 与 `ContextMenu`
  - 让输入区图片缩略图支持右键复制图片
- 修改 `webview/src/components/ImagePreviewDialog.tsx`
  - 删除显式“复制图片”按钮
  - 保留关闭按钮、`Esc`、遮罩关闭、`Ctrl/Cmd+C`
  - 增加预览态右键复制图片
- 修改测试文件
  - 更新 `MarkdownBlock.test.tsx`
  - 扩充 `AttachmentList.test.tsx`
  - 必要时补 `ImagePreviewDialog` 或上下文菜单相关测试

---

## 10. 改造清单

- [x] 在 `AttachmentList.tsx` 中引入 `useContextMenu` 与 `ContextMenu`
- [x] 给输入区图片缩略图所在容器补 `onContextMenu` 事件处理
- [x] 让输入区缩略图右键命中后展示“复制图片”菜单项
- [x] 确认非图片附件不出现“复制图片”菜单项
- [x] 删除 `ImagePreviewDialog.tsx` 中的 `.image-preview-copy` 按钮与相关文案
- [x] 在 `ImagePreviewDialog.tsx` 中补充右键菜单逻辑
- [x] 确认预览弹层右键时不会误触发关闭
- [x] 确认预览弹层仍支持 `Ctrl/Cmd+C` 复制图片
- [x] 更新 `MarkdownBlock.test.tsx`，去掉对预览复制按钮存在性的断言
- [x] 扩充 `AttachmentList.test.tsx`，覆盖输入区缩略图右键复制图片场景
- [ ] 如有必要，补充 `ImagePreviewDialog` 级别测试，覆盖“无复制按钮 + 右键复制”行为
- [ ] 对本轮涉及文件做 UTF-8 编码核对，避免继续引入乱码文本

---

## 11. 文档落点说明

本次需求要求“保存到文档末尾”。经检查，当前 worktree 的 `docs/` 下没有与“聊天图片复制/预览交互”直接对应、且适合安全追加的现成专题文档；现有最近文档主要是其他功能主题的计划与设计文档，直接追加会污染无关上下文。

因此本次采用“新建专用分析文档”的方式保存，避免把本需求内容追加到无关文档末尾。
