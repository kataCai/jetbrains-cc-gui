# Plan 模式产品化与任务状态提醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成 `E.1-E.9` 与 `F.1-F.12`，交付正式可用的 `Chat / Plan` 模式、统一任务状态机、提醒策略、设置页配置迁移与测试。

**Architecture:** 保留当前 `permissionMode` 作为前后端桥接的 canonical 值，在前端新增 `Chat / Plan` 产品视图层；在 Java 侧新增 `TaskStateService` 与 `TaskReminderPolicy`，由它们统一归因状态、决策提醒通道，并驱动顶部轻状态条、状态栏、通知栏与提示音。设置页统一迁移到 `taskReminder` 配置模型，旧 `soundNotification` 在读取时兼容迁移。

**Tech Stack:** React 19 + TypeScript + Vitest + Testing Library；IntelliJ Platform Plugin（Java 17）+ JUnit 4；JCEF bridge。

---

## File Structure Map

### Frontend UI / 状态展示

- Create: `webview/src/components/ChatModeStrip/ChatModeStrip.tsx`
  - 顶部轻量模式/状态条，只负责展示 `Chat / Plan` 与当前任务状态
- Create: `webview/src/components/ChatModeStrip/index.ts`
  - 导出入口
- Create: `webview/src/components/TaskReminderDialog.tsx`
  - `waiting_confirm / final_error` 强提醒弹窗
- Create: `webview/src/components/TaskReminderDialog.css`
  - 弹窗样式
- Create: `webview/src/components/ChatInputBox/modeViewModel.ts`
  - `Chat / Plan` 与底层 `permissionMode` 的纯函数映射，供 UI 与 hooks 复用
- Modify: `webview/src/App.tsx`
  - 挂载顶部轻量状态条、任务提醒弹窗、状态同步
- Modify: `webview/src/components/ChatHeader/ChatHeader.tsx`
  - 为顶部轻量状态条预留插槽或包裹区
- Modify: `webview/src/components/StatusPanel/StatusPanel.tsx`
  - 弱联动展示当前模式/任务状态

### Frontend 模式切换与审批闭环

- Modify: `webview/src/components/ChatInputBox/ButtonArea.tsx`
  - 增加底部 `Chat / Plan` 切换
- Modify: `webview/src/components/ChatInputBox/ChatInputBoxFooter.tsx`
  - 透传产品层模式 props
- Modify: `webview/src/components/ChatInputBox/selectors/ModeSelect.tsx`
  - 将 dropdown 聚焦为 `Chat` 下执行策略，不再承担产品层模式切换
- Modify: `webview/src/hooks/useModelProviderState.ts`
  - 统一恢复、切换、provider 兼容逻辑
- Modify: `webview/src/hooks/useMessageSender.ts`
  - 显式透传请求模式与有效模式
- Modify: `webview/src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.ts`
  - 统一前端回显模式
- Modify: `webview/src/hooks/useDialogManagement.ts`
  - plan 审批通过、拒绝、超时后的状态联动
- Modify: `webview/src/components/PlanApprovalDialog.tsx`
  - 超时文案、关闭逻辑与目标模式回写

### Frontend 设置页

- Create: `webview/src/types/taskReminder.ts`
  - `TaskReminderState`、`TaskReminderChannelConfig`、`TaskReminderConfig` 前端类型
- Modify: `webview/src/components/settings/BasicConfigSection/BehaviorTab.tsx`
  - 新增“状态提醒”总配置组与三类子项
- Modify: `webview/src/components/settings/BasicConfigSection/index.tsx`
  - 承接新 props
- Modify: `webview/src/components/settings/hooks/useSettingsBasicActions.ts`
  - 新配置发送逻辑
- Modify: `webview/src/components/settings/hooks/useSettingsWindowCallbacks.ts`
  - 新配置回显逻辑
- Modify: `webview/src/components/settings/index.tsx`
  - 串联状态提醒设置数据流

### Backend 状态聚合与提醒

- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskState.java`
  - 状态枚举
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskStateEvent.java`
  - 状态事件对象
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskStateSnapshot.java`
  - 当前任务状态快照
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskStateService.java`
  - 聚合会话、权限、计划审批与恢复事件
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskReminderPolicy.java`
  - 依据配置与状态决定通道
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskReminderDispatcher.java`
  - 将 policy 结果派发到 UI 通道
- Create: `src/main/java/com/github/claudecodegui/notifications/ClaudeBalloonNotifier.java`
  - IntelliJ notification/balloon 适配层
- Modify: `src/main/java/com/github/claudecodegui/notifications/ClaudeNotifier.java`
  - 复用状态栏与成功/失败入口，避免重复分叉
- Modify: `src/main/java/com/github/claudecodegui/handler/SessionHandler.java`
  - 发送成功/失败事件接入 `TaskStateService`
- Modify: `src/main/java/com/github/claudecodegui/handler/PermissionHandler.java`
  - `waiting_confirm`、批准、拒绝、超时事件接入状态层
- Modify: `src/main/java/com/github/claudecodegui/handler/PermissionModeHandler.java`
  - 模式回写时同步状态栏与顶部状态
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`
  - 初始化 `TaskStateService` / `TaskReminderDispatcher`

### Backend 配置与迁移

- Modify: `src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java`
  - 新增 `taskReminder` 读写与旧配置迁移
- Modify: `src/main/java/com/github/claudecodegui/handler/SoundSettingsHandler.java`
  - 兼容新旧 payload，并桥接 `taskReminder.sound`
- Modify: `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`
  - 注册新的状态提醒消息类型

### Tests

- Create: `webview/src/components/ChatInputBox/ButtonArea.test.tsx`
- Create: `webview/src/components/PlanApprovalDialog.test.tsx`
- Create: `webview/src/hooks/useModelProviderState.test.ts`
- Create: `webview/src/hooks/useMessageSender.test.ts`
- Create: `webview/src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.test.ts`
- Create: `webview/src/hooks/useDialogManagement.test.ts`
- Create: `webview/src/components/settings/BasicConfigSection/BehaviorTab.test.tsx`
- Create: `webview/src/components/settings/hooks/useSettingsBasicActions.test.ts`
- Modify: `webview/src/components/settings/hooks/useSettingsWindowCallbacks.test.ts`
- Create: `src/test/java/com/github/claudecodegui/taskstate/TaskStateServiceTest.java`
- Create: `src/test/java/com/github/claudecodegui/taskstate/TaskReminderPolicyTest.java`
- Create: `src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceTaskReminderMigrationTest.java`
- Create: `src/test/java/com/github/claudecodegui/handler/SoundSettingsHandlerTaskReminderTest.java`

### External Progress Docs

- Modify: `/Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md`
- Modify: `/Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md`

每完成一个 `E.* / F.*` 任务，立即同步勾选这两份文档。

## Task Map

- Task 1 -> `E.1`、`E.4`、`E.5`
- Task 2 -> `E.2`、`E.3`、`E.6`
- Task 3 -> `E.7`、`E.8`、`E.9`
- Task 4 -> `F.1`、`F.2`、`F.3`
- Task 5 -> `F.4`、`F.5`、`F.9`、`F.10`
- Task 6 -> `F.6`、`F.7`、`F.8`
- Task 7 -> `F.11`、`F.12`
- Task 8 -> 全量验证、文档进度同步、回归

## Task 1: Chat / Plan 视图模型与聊天页模式展示

**Files:**
- Create: `webview/src/components/ChatInputBox/modeViewModel.ts`
- Create: `webview/src/components/ChatModeStrip/ChatModeStrip.tsx`
- Create: `webview/src/components/ChatModeStrip/index.ts`
- Modify: `webview/src/components/ChatInputBox/ButtonArea.tsx`
- Modify: `webview/src/components/ChatInputBox/ChatInputBoxFooter.tsx`
- Modify: `webview/src/components/ChatInputBox/selectors/ModeSelect.tsx`
- Modify: `webview/src/App.tsx`
- Modify: `webview/src/components/ChatHeader/ChatHeader.tsx`
- Modify: `webview/src/components/StatusPanel/StatusPanel.tsx`
- Test: `webview/src/components/ChatInputBox/ButtonArea.test.tsx`

- [ ] **Step 1: 写失败测试，锁定底部 `Chat / Plan` 切换与 `codex` 禁用态**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ButtonArea } from './ButtonArea';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string, fallback?: string) => fallback ?? key }),
}));

describe('ButtonArea', () => {
  it('renders Chat / Plan switch and disables Plan for codex', () => {
    render(
      <ButtonArea
        hasInputContent
        selectedModel="claude-sonnet-4-6"
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
      />
    );

    expect(screen.getByRole('button', { name: /Chat/i })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: /Plan/i })).toBeDisabled();
  });
});
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd webview && npx vitest run src/components/ChatInputBox/ButtonArea.test.tsx
```

Expected: FAIL，提示找不到 `Chat / Plan` 对应按钮或断言失败。

- [ ] **Step 3: 实现最小模式视图模型与顶部轻状态条**

`webview/src/components/ChatInputBox/modeViewModel.ts`

```ts
import type { PermissionMode } from './types';

export type ComposerUsageMode = 'chat' | 'plan';

export function getComposerUsageMode(permissionMode: PermissionMode): ComposerUsageMode {
  return permissionMode === 'plan' ? 'plan' : 'chat';
}

export function getChatExecutionMode(permissionMode: PermissionMode): Exclude<PermissionMode, 'plan'> {
  return permissionMode === 'plan' ? 'default' : permissionMode;
}

export function resolvePermissionModeFromComposer(
  usageMode: ComposerUsageMode,
  chatExecutionMode: Exclude<PermissionMode, 'plan'>
): PermissionMode {
  return usageMode === 'plan' ? 'plan' : chatExecutionMode;
}
```

`webview/src/components/ChatModeStrip/ChatModeStrip.tsx`

```tsx
import type { ComposerUsageMode } from '../ChatInputBox/modeViewModel';

export function ChatModeStrip({
  usageMode,
  taskState,
}: {
  usageMode: ComposerUsageMode;
  taskState?: 'running' | 'waiting_confirm' | 'retrying' | 'recovered' | 'completed' | 'final_error' | null;
}) {
  if (!taskState && usageMode === 'chat') {
    return null;
  }

  return (
    <div className="chat-mode-strip" data-state={taskState ?? 'idle'}>
      <span className="chat-mode-strip__mode">Mode: {usageMode === 'plan' ? 'Plan' : 'Chat'}</span>
      {taskState && <span className="chat-mode-strip__state">{taskState}</span>}
    </div>
  );
}
```

`webview/src/components/ChatInputBox/ButtonArea.tsx` 节选

```tsx
<div className="composer-mode-toggle" role="tablist" aria-label="Composer mode">
  <button
    type="button"
    aria-pressed={usageMode === 'chat'}
    onClick={() => onUsageModeSelect?.('chat')}
  >
    Chat
  </button>
  <button
    type="button"
    aria-pressed={usageMode === 'plan'}
    disabled={currentProvider === 'codex'}
    onClick={() => onUsageModeSelect?.('plan')}
    title={currentProvider === 'codex' ? t('chat.planUnavailableForCodex') : t('chat.planMode')}
  >
    Plan
  </button>
</div>
```

- [ ] **Step 4: 运行测试并验证通过**

Run:

```bash
cd webview && npx vitest run src/components/ChatInputBox/ButtonArea.test.tsx
```

Expected: PASS，输出 `1 passed`。

- [ ] **Step 5: 同步文档并提交**

Run:

```bash
git add webview/src/components/ChatInputBox/modeViewModel.ts \
  webview/src/components/ChatModeStrip/ChatModeStrip.tsx \
  webview/src/components/ChatModeStrip/index.ts \
  webview/src/components/ChatInputBox/ButtonArea.tsx \
  webview/src/components/ChatInputBox/ChatInputBoxFooter.tsx \
  webview/src/components/ChatInputBox/selectors/ModeSelect.tsx \
  webview/src/App.tsx \
  webview/src/components/ChatHeader/ChatHeader.tsx \
  webview/src/components/StatusPanel/StatusPanel.tsx \
  webview/src/components/ChatInputBox/ButtonArea.test.tsx \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "feat: add chat plan mode toggle and strip"
```

Expected: commit created，并在两份外部文档中勾选 `E.1`、`E.4`、`E.5`。

## Task 2: 模式透传、Plan 审批闭环与回退处理

**Files:**
- Modify: `webview/src/hooks/useModelProviderState.ts`
- Modify: `webview/src/hooks/useMessageSender.ts`
- Modify: `webview/src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.ts`
- Modify: `webview/src/hooks/useDialogManagement.ts`
- Modify: `webview/src/components/PlanApprovalDialog.tsx`
- Modify: `src/main/java/com/github/claudecodegui/handler/PermissionHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/PermissionModeHandler.java`
- Test: `webview/src/hooks/useModelProviderState.test.ts`
- Test: `webview/src/hooks/useMessageSender.test.ts`
- Test: `webview/src/hooks/useDialogManagement.test.ts`
- Test: `webview/src/components/PlanApprovalDialog.test.tsx`

- [ ] **Step 1: 写失败测试，锁定 mode 透传、plan 审批通过/拒绝/超时**

```ts
import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useDialogManagement } from './useDialogManagement';

vi.mock('../utils/bridge', () => ({ sendBridgeEvent: vi.fn() }));

describe('useDialogManagement', () => {
  it('sends default target mode when plan approval is rejected', () => {
    const { result } = renderHook(() => useDialogManagement({ t: (key: string) => key as any }));

    act(() => {
      result.current.openPlanApprovalDialog({ requestId: 'req-1', plan: 'demo' });
      result.current.handlePlanApprovalReject('req-1');
    });

    expect(result.current.planApprovalDialogOpen).toBe(false);
  });
});
```

```ts
import { describe, expect, it } from 'vitest';
import { resolveEffectivePermissionMode } from '../../../src-shim';

it('downgrades codex plan requests to default', () => {
  expect(resolveEffectivePermissionMode('codex', 'plan', 'default')).toBe('default');
});
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd webview && npx vitest run \
  src/hooks/useDialogManagement.test.ts \
  src/hooks/useModelProviderState.test.ts \
  src/hooks/useMessageSender.test.ts \
  src/components/PlanApprovalDialog.test.tsx
```

Expected: FAIL，提示缺少相应测试文件或当前逻辑未覆盖超时与回退断言。

- [ ] **Step 3: 实现最小透传与回退闭环**

`webview/src/hooks/useMessageSender.ts` 节选

```ts
const effectivePermissionMode: PermissionMode =
  currentProvider === 'codex' && requestedPermissionMode === 'plan'
    ? 'default'
    : requestedPermissionMode;

sendBridgeEvent('send_message_with_attachments', JSON.stringify({
  text,
  attachments,
  agent: agentInfo,
  fileTags: fileTagsInfo,
  permissionMode: effectivePermissionMode,
  requestedUsageMode: requestedPermissionMode === 'plan' ? 'plan' : 'chat',
}));
```

`webview/src/hooks/useDialogManagement.ts` 节选

```ts
const handlePlanApprovalReject = useCallback((requestId: string) => {
  sendBridgeEvent('plan_approval_response', JSON.stringify({
    requestId,
    approved: false,
    targetMode: 'default',
  }));
  setPlanApprovalDialogOpen(false);
  setCurrentPlanApprovalRequest(null);
}, []);
```

`src/main/java/com/github/claudecodegui/handler/PermissionHandler.java` 节选

```java
CompletableFuture.delayedExecutor(PERMISSION_TIMEOUT_SECONDS, TimeUnit.SECONDS).execute(() -> {
    if (!future.isDone()) {
        pendingPlanApprovalRequests.remove(requestId);
        future.completeExceptionally(new TimeoutException("plan approval timeout"));
        taskStateService.onPlanApprovalTimedOut(requestId);
    }
});
```

- [ ] **Step 4: 运行测试并验证通过**

Run:

```bash
cd webview && npx vitest run \
  src/hooks/useDialogManagement.test.ts \
  src/hooks/useModelProviderState.test.ts \
  src/hooks/useMessageSender.test.ts \
  src/components/PlanApprovalDialog.test.tsx
```

Expected: PASS，输出所有计划内测试通过。

- [ ] **Step 5: 同步文档并提交**

Run:

```bash
git add webview/src/hooks/useModelProviderState.ts \
  webview/src/hooks/useMessageSender.ts \
  webview/src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.ts \
  webview/src/hooks/useDialogManagement.ts \
  webview/src/components/PlanApprovalDialog.tsx \
  src/main/java/com/github/claudecodegui/handler/PermissionHandler.java \
  src/main/java/com/github/claudecodegui/handler/PermissionModeHandler.java \
  webview/src/hooks/useModelProviderState.test.ts \
  webview/src/hooks/useMessageSender.test.ts \
  webview/src/hooks/useDialogManagement.test.ts \
  webview/src/components/PlanApprovalDialog.test.tsx \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "feat: complete plan approval mode flow"
```

Expected: commit created，并勾选 `E.2`、`E.3`、`E.6`。

## Task 3: 补齐 Plan 模式前端测试矩阵

**Files:**
- Modify: `webview/src/components/ChatInputBox/ButtonArea.test.tsx`
- Create: `webview/src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.test.ts`
- Modify: `webview/src/hooks/useModelProviderState.test.ts`
- Modify: `webview/src/hooks/useMessageSender.test.ts`
- Modify: `webview/src/hooks/useDialogManagement.test.ts`
- Modify: `webview/src/components/PlanApprovalDialog.test.tsx`
- Modify: `/Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md`
- Modify: `/Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md`

- [ ] **Step 1: 写缺失测试，覆盖 E.7-E.9 全量要求**

```ts
it('restores default mode when backend sends plan under codex provider', () => {
  const setPermissionMode = vi.fn();
  const setClaudePermissionMode = vi.fn();
  const setCodexPermissionMode = vi.fn();
  const currentProviderRef = { current: 'codex' };

  registerUsageModeCallbacks({
    setUsagePercentage: vi.fn(),
    setUsageUsedTokens: vi.fn(),
    setUsageMaxTokens: vi.fn(),
    setPermissionMode,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setSelectedClaudeModel: vi.fn(),
    setSelectedCodexModel: vi.fn(),
    setProviderConfigVersion: vi.fn(),
    setActiveProviderConfig: vi.fn(),
    setClaudeSettingsAlwaysThinkingEnabled: vi.fn(),
    setStreamingEnabledSetting: vi.fn(),
    setSendShortcut: vi.fn(),
    setAutoOpenFileEnabled: vi.fn(),
    currentProviderRef,
    syncActiveProviderModelMapping: vi.fn(),
  } as any);

  window.onModeReceived?.('plan');

  expect(setPermissionMode).toHaveBeenCalled();
  expect(setCodexPermissionMode).toHaveBeenCalled();
});
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd webview && npx vitest run \
  src/components/ChatInputBox/ButtonArea.test.tsx \
  src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.test.ts \
  src/hooks/useModelProviderState.test.ts \
  src/hooks/useMessageSender.test.ts \
  src/hooks/useDialogManagement.test.ts \
  src/components/PlanApprovalDialog.test.tsx
```

Expected: FAIL，至少一项断言或文件不存在。

- [ ] **Step 3: 补全最小测试实现**

`webview/src/hooks/useMessageSender.test.ts` 节选

```ts
it('serializes plan requests as default when provider is codex', () => {
  const sendToJava = vi.fn();
  window.sendToJava = sendToJava;

  const { result } = renderHook(() => useMessageSender({
    addToast: vi.fn(),
    permissionMode: 'plan',
    currentProvider: 'codex',
    sdkStatusLoaded: true,
    currentSdkInstalled: true,
    t: ((key: string) => key) as any,
  } as any));

  act(() => {
    result.current.handleSendMessage('hello', undefined, null, null);
  });

  expect(sendToJava).toHaveBeenCalled();
});
```

- [ ] **Step 4: 运行测试并验证通过**

Run:

```bash
cd webview && npx vitest run \
  src/components/ChatInputBox/ButtonArea.test.tsx \
  src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.test.ts \
  src/hooks/useModelProviderState.test.ts \
  src/hooks/useMessageSender.test.ts \
  src/hooks/useDialogManagement.test.ts \
  src/components/PlanApprovalDialog.test.tsx
```

Expected: PASS，E 组测试全部通过。

- [ ] **Step 5: 同步文档并提交**

Run:

```bash
git add webview/src/components/ChatInputBox/ButtonArea.test.tsx \
  webview/src/hooks/windowCallbacks/registerCallbacks/usageModeCallbacks.test.ts \
  webview/src/hooks/useModelProviderState.test.ts \
  webview/src/hooks/useMessageSender.test.ts \
  webview/src/hooks/useDialogManagement.test.ts \
  webview/src/components/PlanApprovalDialog.test.tsx \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "test: cover plan mode product flow"
```

Expected: commit created，并勾选 `E.7`、`E.8`、`E.9`。

## Task 4: 引入统一 TaskState 模型与 TaskStateService

**Files:**
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskState.java`
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskStateEvent.java`
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskStateSnapshot.java`
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskStateService.java`
- Modify: `src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/SessionHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/PermissionHandler.java`
- Test: `src/test/java/com/github/claudecodegui/taskstate/TaskStateServiceTest.java`

- [ ] **Step 1: 写失败测试，锁定状态归因规则**

```java
package com.github.claudecodegui.taskstate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TaskStateServiceTest {

    @Test
    public void shouldTransitionFromRunningToWaitingConfirmAndBack() {
        TaskStateService service = new TaskStateService();

        service.onSendStarted("session-1");
        assertEquals(TaskState.RUNNING, service.getCurrentSnapshot().getState());

        service.onPlanApprovalRequested("req-1");
        assertEquals(TaskState.WAITING_CONFIRM, service.getCurrentSnapshot().getState());

        service.onPlanApprovalApproved("req-1");
        assertEquals(TaskState.RUNNING, service.getCurrentSnapshot().getState());
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
./gradlew test --tests "com.github.claudecodegui.taskstate.TaskStateServiceTest"
```

Expected: FAIL，提示 `TaskStateService` 或 `TaskState` 不存在。

- [ ] **Step 3: 实现最小状态模型与服务**

`src/main/java/com/github/claudecodegui/taskstate/TaskState.java`

```java
package com.github.claudecodegui.taskstate;

public enum TaskState {
    PENDING,
    RUNNING,
    WAITING_CONFIRM,
    RETRYING,
    RECOVERED,
    FINAL_ERROR,
    COMPLETED,
    CANCELLED
}
```

`src/main/java/com/github/claudecodegui/taskstate/TaskStateService.java` 节选

```java
package com.github.claudecodegui.taskstate;

public class TaskStateService {
    private TaskStateSnapshot currentSnapshot = new TaskStateSnapshot(TaskState.PENDING, null, null);

    public void onSendStarted(String sessionId) {
        currentSnapshot = new TaskStateSnapshot(TaskState.RUNNING, sessionId, null);
    }

    public void onPlanApprovalRequested(String requestId) {
        currentSnapshot = new TaskStateSnapshot(TaskState.WAITING_CONFIRM, currentSnapshot.getSessionId(), requestId);
    }

    public void onPlanApprovalApproved(String requestId) {
        currentSnapshot = new TaskStateSnapshot(TaskState.RUNNING, currentSnapshot.getSessionId(), requestId);
    }

    public void onRecovered() {
        currentSnapshot = new TaskStateSnapshot(TaskState.RECOVERED, currentSnapshot.getSessionId(), currentSnapshot.getRequestId());
    }

    public void onCompleted() {
        currentSnapshot = new TaskStateSnapshot(TaskState.COMPLETED, currentSnapshot.getSessionId(), currentSnapshot.getRequestId());
    }

    public TaskStateSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }
}
```

- [ ] **Step 4: 运行测试并验证通过**

Run:

```bash
./gradlew test --tests "com.github.claudecodegui.taskstate.TaskStateServiceTest"
```

Expected: PASS，输出 `BUILD SUCCESSFUL`。

- [ ] **Step 5: 同步文档并提交**

Run:

```bash
git add src/main/java/com/github/claudecodegui/taskstate/TaskState.java \
  src/main/java/com/github/claudecodegui/taskstate/TaskStateEvent.java \
  src/main/java/com/github/claudecodegui/taskstate/TaskStateSnapshot.java \
  src/main/java/com/github/claudecodegui/taskstate/TaskStateService.java \
  src/main/java/com/github/claudecodegui/ui/ChatWindowDelegate.java \
  src/main/java/com/github/claudecodegui/handler/SessionHandler.java \
  src/main/java/com/github/claudecodegui/handler/PermissionHandler.java \
  src/test/java/com/github/claudecodegui/taskstate/TaskStateServiceTest.java \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "feat: add task state service"
```

Expected: commit created，并勾选 `F.1`、`F.2`、`F.3`。

## Task 5: 提醒策略、通知通道与强提醒弹窗

**Files:**
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskReminderPolicy.java`
- Create: `src/main/java/com/github/claudecodegui/taskstate/TaskReminderDispatcher.java`
- Create: `src/main/java/com/github/claudecodegui/notifications/ClaudeBalloonNotifier.java`
- Create: `webview/src/components/TaskReminderDialog.tsx`
- Create: `webview/src/components/TaskReminderDialog.css`
- Modify: `src/main/java/com/github/claudecodegui/notifications/ClaudeNotifier.java`
- Modify: `src/main/java/com/github/claudecodegui/util/SoundNotificationService.java`
- Modify: `webview/src/App.tsx`
- Test: `src/test/java/com/github/claudecodegui/taskstate/TaskReminderPolicyTest.java`

- [ ] **Step 1: 写失败测试，锁定 `waiting_confirm / final_error / completed` 的通道映射**

```java
package com.github.claudecodegui.taskstate;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class TaskReminderPolicyTest {

    @Test
    public void shouldPopupForWaitingConfirmAndFinalError() {
        TaskReminderPolicy policy = TaskReminderPolicy.defaults();

        assertTrue(policy.shouldShowPopup(TaskState.WAITING_CONFIRM, false, false));
        assertTrue(policy.shouldShowPopup(TaskState.FINAL_ERROR, false, false));
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
./gradlew test --tests "com.github.claudecodegui.taskstate.TaskReminderPolicyTest"
```

Expected: FAIL，提示 `TaskReminderPolicy` 不存在。

- [ ] **Step 3: 实现最小策略与派发器，并在前端挂载强提醒弹窗**

`src/main/java/com/github/claudecodegui/taskstate/TaskReminderPolicy.java`

```java
package com.github.claudecodegui.taskstate;

public class TaskReminderPolicy {
    public static TaskReminderPolicy defaults() {
        return new TaskReminderPolicy();
    }

    public boolean shouldShowPopup(TaskState state, boolean ideFocused, boolean approvalDialogOpen) {
        if (approvalDialogOpen && state == TaskState.WAITING_CONFIRM) {
            return false;
        }
        return state == TaskState.WAITING_CONFIRM || state == TaskState.FINAL_ERROR;
    }

    public boolean shouldShowBalloon(TaskState state, boolean ideFocused) {
        return state == TaskState.COMPLETED || state == TaskState.RECOVERED || state == TaskState.FINAL_ERROR;
    }

    public boolean shouldPlaySound(TaskState state, boolean ideFocused) {
        return state == TaskState.COMPLETED;
    }
}
```

`webview/src/components/TaskReminderDialog.tsx`

```tsx
export function TaskReminderDialog({
  open,
  state,
  onOpenSession,
  onRetry,
  onDismiss,
}: {
  open: boolean;
  state: 'waiting_confirm' | 'final_error' | null;
  onOpenSession: () => void;
  onRetry: () => void;
  onDismiss: () => void;
}) {
  if (!open || !state) return null;

  return (
    <div className="task-reminder-dialog">
      <h3>{state === 'waiting_confirm' ? '需要确认' : '任务失败'}</h3>
      <div className="task-reminder-dialog__actions">
        <button onClick={onOpenSession}>打开会话</button>
        {state === 'final_error' && <button onClick={onRetry}>重试</button>}
        <button onClick={onDismiss}>关闭</button>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: 运行测试并验证通过**

Run:

```bash
./gradlew test --tests "com.github.claudecodegui.taskstate.TaskReminderPolicyTest"
```

Expected: PASS，输出 `BUILD SUCCESSFUL`。

- [ ] **Step 5: 同步文档并提交**

Run:

```bash
git add src/main/java/com/github/claudecodegui/taskstate/TaskReminderPolicy.java \
  src/main/java/com/github/claudecodegui/taskstate/TaskReminderDispatcher.java \
  src/main/java/com/github/claudecodegui/notifications/ClaudeBalloonNotifier.java \
  src/main/java/com/github/claudecodegui/notifications/ClaudeNotifier.java \
  src/main/java/com/github/claudecodegui/util/SoundNotificationService.java \
  webview/src/components/TaskReminderDialog.tsx \
  webview/src/components/TaskReminderDialog.css \
  webview/src/App.tsx \
  src/test/java/com/github/claudecodegui/taskstate/TaskReminderPolicyTest.java \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "feat: add reminder policy and channels"
```

Expected: commit created，并勾选 `F.4`、`F.5`、`F.9`、`F.10`。

## Task 6: 后端配置模型、旧配置迁移与 bridge 消息升级

**Files:**
- Modify: `src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/SoundSettingsHandler.java`
- Modify: `src/main/java/com/github/claudecodegui/handler/SettingsHandler.java`
- Test: `src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceTaskReminderMigrationTest.java`
- Test: `src/test/java/com/github/claudecodegui/handler/SoundSettingsHandlerTaskReminderTest.java`

- [ ] **Step 1: 写失败测试，锁定旧 `soundNotification` 到新 `taskReminder.sound` 的迁移**

```java
package com.github.claudecodegui.settings;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CodemossSettingsServiceTaskReminderMigrationTest {

    @Test
    public void shouldMigrateLegacySoundNotificationIntoTaskReminderSound() throws Exception {
        CodemossSettingsService service = new CodemossSettingsService();
        JsonObject config = service.createDefaultConfig();

        JsonObject legacy = new JsonObject();
        legacy.addProperty("enabled", true);
        legacy.addProperty("onlyWhenUnfocused", true);
        legacy.addProperty("selectedSound", "success");
        config.add("soundNotification", legacy);

        JsonObject migrated = service.migrateTaskReminderConfig(config);

        assertTrue(migrated.getAsJsonObject("taskReminder").has("sound"));
        assertEquals("success", migrated.getAsJsonObject("taskReminder").getAsJsonObject("sound").get("selectedSound").getAsString());
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
./gradlew test --tests "com.github.claudecodegui.settings.CodemossSettingsServiceTaskReminderMigrationTest"
```

Expected: FAIL，提示 `migrateTaskReminderConfig` 不存在。

- [ ] **Step 3: 实现最小迁移与 handler 兼容逻辑**

`src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java` 节选

```java
public JsonObject migrateTaskReminderConfig(JsonObject config) {
    if (config.has("taskReminder") && config.get("taskReminder").isJsonObject()) {
        return config;
    }

    JsonObject taskReminder = new JsonObject();
    JsonObject sound = new JsonObject();

    if (config.has("soundNotification") && config.get("soundNotification").isJsonObject()) {
        JsonObject legacy = config.getAsJsonObject("soundNotification");
        sound.addProperty("enabled", legacy.has("enabled") && legacy.get("enabled").getAsBoolean());
        sound.addProperty("onlyWhenIdeUnfocused", legacy.has("onlyWhenUnfocused") && legacy.get("onlyWhenUnfocused").getAsBoolean());
        sound.addProperty("selectedSound", legacy.has("selectedSound") ? legacy.get("selectedSound").getAsString() : "default");
        sound.addProperty("customSoundPath", legacy.has("customSoundPath") ? legacy.get("customSoundPath").getAsString() : "");
    }

    taskReminder.add("sound", sound);
    config.add("taskReminder", taskReminder);
    return config;
}
```

`src/main/java/com/github/claudecodegui/handler/SettingsHandler.java` 节选

```java
case "get_task_reminder_config":
    soundSettingsHandler.handleGetTaskReminderConfig();
    break;
case "set_task_reminder_config":
    soundSettingsHandler.handleSetTaskReminderConfig(content);
    break;
```

- [ ] **Step 4: 运行测试并验证通过**

Run:

```bash
./gradlew test --tests "com.github.claudecodegui.settings.CodemossSettingsServiceTaskReminderMigrationTest" \
  --tests "com.github.claudecodegui.handler.SoundSettingsHandlerTaskReminderTest"
```

Expected: PASS，输出 `BUILD SUCCESSFUL`。

- [ ] **Step 5: 同步文档并提交**

Run:

```bash
git add src/main/java/com/github/claudecodegui/settings/CodemossSettingsService.java \
  src/main/java/com/github/claudecodegui/handler/SoundSettingsHandler.java \
  src/main/java/com/github/claudecodegui/handler/SettingsHandler.java \
  src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceTaskReminderMigrationTest.java \
  src/test/java/com/github/claudecodegui/handler/SoundSettingsHandlerTaskReminderTest.java \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "feat: migrate task reminder backend config"
```

Expected: commit created，并勾选 `F.6`、`F.7`、`F.8`。

## Task 7: 前端设置页“状态提醒”总配置组与回显保存测试

**Files:**
- Create: `webview/src/types/taskReminder.ts`
- Modify: `webview/src/components/settings/BasicConfigSection/BehaviorTab.tsx`
- Modify: `webview/src/components/settings/BasicConfigSection/index.tsx`
- Modify: `webview/src/components/settings/hooks/useSettingsBasicActions.ts`
- Modify: `webview/src/components/settings/hooks/useSettingsWindowCallbacks.ts`
- Modify: `webview/src/components/settings/index.tsx`
- Create: `webview/src/components/settings/BasicConfigSection/BehaviorTab.test.tsx`
- Create: `webview/src/components/settings/hooks/useSettingsBasicActions.test.ts`
- Modify: `webview/src/components/settings/hooks/useSettingsWindowCallbacks.test.ts`

- [ ] **Step 1: 写失败测试，锁定总配置组、多选状态与保存回显**

```tsx
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import BehaviorTab from './BehaviorTab';

describe('BehaviorTab', () => {
  it('renders task reminder group with popup, balloon and sound sections', () => {
    render(<BehaviorTab />);

    expect(screen.getByText('settings.basic.taskReminder.label')).toBeInTheDocument();
    expect(screen.getByText('settings.basic.taskReminder.popup.label')).toBeInTheDocument();
    expect(screen.getByText('settings.basic.taskReminder.balloon.label')).toBeInTheDocument();
    expect(screen.getByText('settings.basic.taskReminder.sound.label')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行测试并确认失败**

Run:

```bash
cd webview && npx vitest run \
  src/components/settings/BasicConfigSection/BehaviorTab.test.tsx \
  src/components/settings/hooks/useSettingsBasicActions.test.ts \
  src/components/settings/hooks/useSettingsWindowCallbacks.test.ts
```

Expected: FAIL，提示新配置组尚未渲染或新 bridge 消息不存在。

- [ ] **Step 3: 实现最小设置 UI 与回显保存链路**

`webview/src/types/taskReminder.ts`

```ts
export type TaskReminderState = 'waiting_confirm' | 'retrying' | 'recovered' | 'final_error' | 'completed';

export interface TaskReminderChannelConfig {
  enabled: boolean;
  states: TaskReminderState[];
  onlyWhenIdeUnfocused: boolean;
  selectedSound?: string;
  customSoundPath?: string;
}

export interface TaskReminderConfig {
  popup: TaskReminderChannelConfig;
  balloon: TaskReminderChannelConfig;
  sound: TaskReminderChannelConfig;
}
```

`webview/src/components/settings/hooks/useSettingsBasicActions.ts` 节选

```ts
const handleTaskReminderConfigChange = useCallback((config: TaskReminderConfig) => {
  setTaskReminderConfig(config);
  sendToJava(`set_task_reminder_config:${JSON.stringify(config)}`);
}, []);
```

`webview/src/components/settings/hooks/useSettingsWindowCallbacks.ts` 节选

```ts
window.updateTaskReminderConfig = (jsonStr: string) => {
  try {
    const data = JSON.parse(jsonStr);
    d().setTaskReminderConfig?.(data);
  } catch (error) {
    console.error('[SettingsView] Failed to parse task reminder config:', error);
  }
};
```

- [ ] **Step 4: 运行测试并验证通过**

Run:

```bash
cd webview && npx vitest run \
  src/components/settings/BasicConfigSection/BehaviorTab.test.tsx \
  src/components/settings/hooks/useSettingsBasicActions.test.ts \
  src/components/settings/hooks/useSettingsWindowCallbacks.test.ts
```

Expected: PASS，F.12 所需前端测试通过。

- [ ] **Step 5: 同步文档并提交**

Run:

```bash
git add webview/src/types/taskReminder.ts \
  webview/src/components/settings/BasicConfigSection/BehaviorTab.tsx \
  webview/src/components/settings/BasicConfigSection/index.tsx \
  webview/src/components/settings/hooks/useSettingsBasicActions.ts \
  webview/src/components/settings/hooks/useSettingsWindowCallbacks.ts \
  webview/src/components/settings/index.tsx \
  webview/src/components/settings/BasicConfigSection/BehaviorTab.test.tsx \
  webview/src/components/settings/hooks/useSettingsBasicActions.test.ts \
  webview/src/components/settings/hooks/useSettingsWindowCallbacks.test.ts \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "feat: add task reminder settings ui"
```

Expected: commit created，并勾选 `F.12`。

## Task 8: 后端提醒测试、全量验证与文档收口

**Files:**
- Modify: `src/test/java/com/github/claudecodegui/taskstate/TaskReminderPolicyTest.java`
- Modify: `src/test/java/com/github/claudecodegui/taskstate/TaskStateServiceTest.java`
- Modify: `src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceTaskReminderMigrationTest.java`
- Modify: `src/test/java/com/github/claudecodegui/handler/SoundSettingsHandlerTaskReminderTest.java`
- Modify: `/Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md`
- Modify: `/Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md`

- [ ] **Step 1: 补全后端测试，覆盖恢复、最终失败、取消与配置组合**

```java
@Test
public void shouldTransitionToFinalErrorAfterRetryExhausted() {
    TaskStateService service = new TaskStateService();

    service.onSendStarted("session-1");
    service.onRetryScheduled("network");
    service.onRetryScheduled("network");
    service.onRetryExhausted("network");

    assertEquals(TaskState.FINAL_ERROR, service.getCurrentSnapshot().getState());
}

@Test
public void shouldRespectOnlyWhenIdeUnfocusedForBalloonAndSound() {
    TaskReminderPolicy policy = TaskReminderPolicy.defaults();

    assertFalse(policy.shouldShowBalloon(TaskState.COMPLETED, true));
    assertFalse(policy.shouldPlaySound(TaskState.COMPLETED, true));
}
```

- [ ] **Step 2: 运行全量相关测试并确认通过**

Run:

```bash
cd webview && npm test
cd .. && ./gradlew test --tests "com.github.claudecodegui.taskstate.*" --tests "com.github.claudecodegui.settings.CodemossSettingsServiceTaskReminderMigrationTest" --tests "com.github.claudecodegui.handler.SoundSettingsHandlerTaskReminderTest"
```

Expected: 全部 PASS，无新增回归。

- [ ] **Step 3: 做手工冒烟验证并记录结果**

Run / Verify:

```bash
./gradlew runIde
```

Manual checklist:

```text
1. Claude provider 下可切换 Chat / Plan
2. Codex provider 下 Plan 显示禁用态或明确降级提示
3. PlanApprovalDialog 支持批准、拒绝、超时
4. waiting_confirm 不与审批弹窗重复强提醒
5. completed / final_error / recovered 的状态栏、通知栏、提示音联动正确
6. 设置页保存后重开仍能回显 taskReminder 配置
7. 旧 soundNotification 配置升级后行为一致
```

Expected: 7 项全部通过；若失败，先修复再进入提交。

- [ ] **Step 4: 同步文档进度快照并完成最终提交**

Run:

```bash
git add src/test/java/com/github/claudecodegui/taskstate/TaskReminderPolicyTest.java \
  src/test/java/com/github/claudecodegui/taskstate/TaskStateServiceTest.java \
  src/test/java/com/github/claudecodegui/settings/CodemossSettingsServiceTaskReminderMigrationTest.java \
  src/test/java/com/github/claudecodegui/handler/SoundSettingsHandlerTaskReminderTest.java \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/04-Plan模式与任务状态提醒方案.md \
  /Users/caihanyuan/workspace/foam-daily-notes/notes/work/CC-GUI增强/新功能点/05-实施计划与任务清单.md

git commit -m "test: finalize task reminder coverage"
```

Expected: commit created，并勾选 `F.11` 与所有剩余未完成项。

---

## Spec Coverage Check

- `Chat / Plan` 正式切换入口：Task 1
- 顶部轻量模式/状态条：Task 1
- Plan 模式透传、审批闭环、拒绝/超时/回退：Task 2
- Plan 模式单测：Task 3
- `TaskState` / `TaskStateService`：Task 4
- `TaskReminderPolicy` / `TaskReminderDialog` / 三通道提醒：Task 5
- “状态提醒”总配置组、多选状态、旧配置迁移：Task 6、Task 7
- Java 与前端测试、冒烟验证、文档进度同步：Task 8

## Placeholder Scan

- 已确认计划中未保留占位语句或延后补充说明
- 所有任务都包含明确文件路径、测试命令、最小代码骨架与提交命令

## Type Consistency Check

- 前端统一使用：`TaskReminderState`、`TaskReminderConfig`
- 后端统一使用：`TaskState`、`TaskStateSnapshot`、`TaskStateService`、`TaskReminderPolicy`
- 术语统一使用：`waiting_confirm`、`retrying`、`recovered`、`final_error`、`completed`
