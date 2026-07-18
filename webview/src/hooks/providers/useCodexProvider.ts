import { useCallback, useState } from 'react';
import { CODEX_MODELS } from '../../components/ChatInputBox/types';
import type { PermissionMode, ReasoningEffort } from '../../components/ChatInputBox/types';

/**
 * Codex-specific selectable state. `reasoningEffort` lives here because the
 * value set is a Codex/OpenAI concept (low/medium/high/xhigh/max).
 * 当前阶段该 Hook 只维护聊天区“目标选择态”，不再在选择发生时直接改写后端 live runtime。
 */
export function useCodexProvider() {
  const [selectedCodexModel, setSelectedCodexModel] = useState(CODEX_MODELS[0].id);
  const [selectedCodexSelectionKey, setSelectedCodexSelectionKey] = useState(CODEX_MODELS[0].id);
  const [codexPermissionMode, setCodexPermissionMode] = useState<PermissionMode>('default');
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>('high');

  /**
   * 更新聊天区当前期望的 Codex reasoning effort。
   * 这里故意只改前端选择态，真正的 runtime 应在消息执行前随 `runtimeIntent` 一起解析，
   * 避免旧任务运行时被选择器即时污染。
   *
   * @param effort 用户当前选中的 reasoning effort
   */
  const handleReasoningChange = useCallback((effort: ReasoningEffort) => {
    setReasoningEffort(effort);
  }, []);

  return {
    selectedCodexModel,
    setSelectedCodexModel,
    selectedCodexSelectionKey,
    setSelectedCodexSelectionKey,
    codexPermissionMode,
    setCodexPermissionMode,
    reasoningEffort,
    setReasoningEffort,
    handleReasoningChange,
  };
}

export type UseCodexProviderReturn = ReturnType<typeof useCodexProvider>;
