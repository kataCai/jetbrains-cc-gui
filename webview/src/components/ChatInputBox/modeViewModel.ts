import type { PermissionMode } from './types';

export type ComposerUsageMode = 'chat' | 'plan';
export type ChatExecutionMode = Exclude<PermissionMode, 'plan'>;

export function getComposerUsageMode(permissionMode: PermissionMode): ComposerUsageMode {
  // 产品层只关心“当前是普通对话还是计划模式”，
  // 因此把底层 permissionMode 折叠成 chat / plan 两个使用语义。
  return permissionMode === 'plan' ? 'plan' : 'chat';
}

export function getChatExecutionMode(permissionMode: PermissionMode, fallbackMode: ChatExecutionMode = 'default'): ChatExecutionMode {
  // plan 本身不是可执行权限模式，所以回到 chat 维度时需要一个 fallback。
  return permissionMode === 'plan' ? fallbackMode : permissionMode;
}

export function resolvePermissionModeFromComposer(
  usageMode: ComposerUsageMode,
  chatExecutionMode: ChatExecutionMode
): PermissionMode {
  // 所有 UI 选择最终都要重新收敛成单一 permissionMode，
  // 这样发送链路、设置持久化和状态同步仍然沿用旧协议。
  return usageMode === 'plan' ? 'plan' : chatExecutionMode;
}
