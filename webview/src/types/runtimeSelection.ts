import type { ReasoningEffort } from '../components/ChatInputBox/types';

/**
 * 聊天区运行时选择快照。
 * 同一份结构同时用于“聊天区当前目标选择态”和“当前活动分段真实运行态”，
 * 这样前端在比较两者差异时可以复用同一套字段语义，避免 provider/model/reasoning/codexProviderId
 * 分散在多个 ref 里后再次出现读写不一致。
 */
export interface RuntimeSelectionState {
  provider: string;
  model: string;
  reasoningEffort: ReasoningEffort;
  codexProviderId: string;
}

/**
 * 基于局部输入和兜底快照构造完整运行时选择对象。
 * 该方法统一负责补齐空字段，避免不同调用点各自散落 provider/model/reasoning 的默认值规则。
 *
 * @param partial 本次想覆盖的运行时字段
 * @param fallback 缺省时回退的旧快照
 * @return 补齐后的稳定运行时选择快照
 */
export function buildRuntimeSelectionState(
  partial: Partial<RuntimeSelectionState>,
  fallback?: RuntimeSelectionState | null,
): RuntimeSelectionState {
  return {
    provider: partial.provider?.trim() || fallback?.provider || 'claude',
    model: partial.model?.trim() || fallback?.model || '',
    reasoningEffort: partial.reasoningEffort || fallback?.reasoningEffort || 'high',
    codexProviderId: partial.codexProviderId?.trim() || fallback?.codexProviderId || '',
  };
}
