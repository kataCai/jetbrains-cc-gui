import type { ReasoningEffort } from '../components/ChatInputBox/types';
import type { RuntimeSelectionState } from './runtimeSelection';

/**
 * 普通聊天、锁定任务和系统消息在发送链路中的来源分类。
 * 该字段会随 `runtimeIntent` 一起传给后端，用于区分“跟随当前聊天区最终选择”
 * 与“入队时已经锁定模型”的两类语义，避免后端把所有消息都当成同一类切段请求。
 */
export type RuntimeIntentSourceKind = 'chat' | 'locked_task' | 'system';

/**
 * `runtimeIntent` 的解析时机。
 * `dynamic_at_execution` 表示普通聊天消息在真正执行时读取最新 desired selection；
 * `locked_at_enqueue` 表示任务在入队时已经锁定目标 runtime，后续聊天区切换不应污染它。
 */
export type RuntimeIntentResolutionPolicy = 'dynamic_at_execution' | 'locked_at_enqueue';

/**
 * 锁定任务的来源标签。
 * 当前前端普通聊天还不会主动写入这些值，但提前保留枚举可以让后续计划任务/子代理任务
 * 复用同一条发送协议，而不再引入第二套 target model 传参。
 */
export type LockedRuntimeIntentSource =
  | 'plan_subtask'
  | 'subagent_dispatch'
  | 'explicit_user_request'
  | 'system_scheduler';

/**
 * 发送给后端的运行时意图。
 * 与 `RuntimeSelectionState` 的区别在于：这里显式带上消息来源和解析策略，
 * 让后端能在发送瞬间判断是否需要静默切段，而不是依赖前端提前改写 live runtime。
 */
export interface RuntimeIntent {
  sourceKind: RuntimeIntentSourceKind;
  resolutionPolicy: RuntimeIntentResolutionPolicy;
  targetProvider: string;
  targetRuntimeFamily: string;
  targetModel: string;
  targetReasoningEffort: ReasoningEffort;
  targetCodexProviderId: string;
  targetModelTier?: string;
  lockedBy?: LockedRuntimeIntentSource;
}

/**
 * 发送链路的消息来源描述。
 * 当前普通聊天统一走 `chat`；后续如有锁定任务消息，可直接复用 `locked_task` 分支。
 */
export type RuntimeIntentMessageSource =
  | { kind: 'chat' }
  | { kind: 'system'; lockedRuntimeIntent?: RuntimeIntent }
  | {
    kind: 'locked_task';
    lockedBy: LockedRuntimeIntentSource;
    lockedRuntimeIntent: RuntimeIntent;
  };

/**
 * 执行时 runtime intent 解析结果。
 * 除最终要下发给后端的 `runtimeIntent` 外，还额外保留 desired/active 两份快照和切换原因，
 * 便于前端日志验证“为什么这条消息会触发静默切段”。
 */
export interface ResolveRuntimeIntentResult {
  runtimeIntent: RuntimeIntent;
  desiredSelection: RuntimeSelectionState;
  activeSelection: RuntimeSelectionState;
  willSwitchRuntime: boolean;
  switchReason: 'provider' | 'model' | 'reasoning' | 'codex_provider' | null;
}

/**
 * 根据当前选择快照构造稳定的 `runtimeIntent`。
 *
 * @param selection 当前目标 runtime 选择
 * @param sourceKind 消息来源类型
 * @param resolutionPolicy 解析策略
 * @param lockedBy 锁定任务来源；普通聊天为空
 * @return 可直接写入 send payload 的 `runtimeIntent`
 */
export function buildRuntimeIntentFromSelection(
  selection: RuntimeSelectionState,
  sourceKind: RuntimeIntentSourceKind,
  resolutionPolicy: RuntimeIntentResolutionPolicy,
  lockedBy?: LockedRuntimeIntentSource,
): RuntimeIntent {
  return {
    sourceKind,
    resolutionPolicy,
    targetProvider: selection.provider,
    targetRuntimeFamily: selection.provider === 'codex' ? 'codex' : 'claude',
    targetModel: selection.model,
    targetReasoningEffort: selection.reasoningEffort,
    targetCodexProviderId: selection.codexProviderId,
    ...(lockedBy ? { lockedBy } : {}),
  };
}

/**
 * 比较 desired selection 与当前 active runtime snapshot 是否已经分叉，
 * 并给出最先命中的切换原因，便于调试发送时静默切段决策。
 *
 * @param desiredSelection 聊天区当前目标选择
 * @param activeSelection 当前活动物理 session 的真实 runtime
 * @return 首个命中的切换原因；完全一致时返回 null
 */
export function getRuntimeSwitchReason(
  desiredSelection: RuntimeSelectionState,
  activeSelection: RuntimeSelectionState,
): 'provider' | 'model' | 'reasoning' | 'codex_provider' | null {
  if (desiredSelection.provider !== activeSelection.provider) {
    return 'provider';
  }
  if (desiredSelection.model !== activeSelection.model) {
    return 'model';
  }
  if (desiredSelection.reasoningEffort !== activeSelection.reasoningEffort) {
    return 'reasoning';
  }
  if (desiredSelection.provider === 'codex' && desiredSelection.codexProviderId !== activeSelection.codexProviderId) {
    return 'codex_provider';
  }
  return null;
}

/**
 * 在真正执行发送前解析本条消息应使用的 `runtimeIntent`。
 * 普通聊天消息始终读取执行时最新 desired selection；
 * 锁定任务消息则复用入队时已经写死的 intent，避免排队期间聊天区切模型污染任务。
 *
 * @param source 本条消息的来源描述
 * @param desiredSelection 聊天区当前目标选择
 * @param activeSelection 当前活动 runtime 快照
 * @return 包含最终 `runtimeIntent`、切换判断和调试快照的解析结果
 */
export function resolveRuntimeIntentForMessage(
  source: RuntimeIntentMessageSource,
  desiredSelection: RuntimeSelectionState,
  activeSelection: RuntimeSelectionState,
): ResolveRuntimeIntentResult {
  let resolvedRuntimeIntent: RuntimeIntent;
  if (source.kind === 'locked_task') {
    resolvedRuntimeIntent = source.lockedRuntimeIntent;
  } else if (source.kind === 'system' && source.lockedRuntimeIntent) {
    resolvedRuntimeIntent = source.lockedRuntimeIntent;
  } else {
    resolvedRuntimeIntent = buildRuntimeIntentFromSelection(
      desiredSelection,
      source.kind,
      'dynamic_at_execution',
    );
  }
  const switchReason = getRuntimeSwitchReason(desiredSelection, activeSelection);
  return {
    runtimeIntent: resolvedRuntimeIntent,
    desiredSelection,
    activeSelection,
    willSwitchRuntime: switchReason !== null,
    switchReason,
  };
}
