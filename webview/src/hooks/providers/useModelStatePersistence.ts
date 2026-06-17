import { useEffect } from 'react';
import {
  CLAUDE_MODELS,
  isValidPermissionMode,
  normalizeClaudeModelId,
  strip1MContextSuffix,
} from '../../components/ChatInputBox/types';
import type { PermissionMode, ReasoningEffort } from '../../components/ChatInputBox/types';

const STORAGE_KEY = 'model-selection-state';

const getCustomModels = (key: string): { id: string }[] => {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
};

export interface UseModelStatePersistenceOptions {
  // Cross-slice load setters (run once on mount)
  setCurrentProvider: (value: string) => void;
  setSelectedClaudeModel: (value: string) => void;
  setSelectedCodexModel: (value: string) => void;
  setClaudePermissionMode: (value: PermissionMode) => void;
  setCodexPermissionMode: (value: PermissionMode) => void;
  setPermissionMode: (value: PermissionMode) => void;
  setLongContextEnabled: (value: boolean) => void;
  setReasoningEffort: (value: ReasoningEffort) => void;
  // Cross-slice save deps (re-saves on any change)
  currentProvider: string;
  selectedClaudeModel: string;
  selectedCodexModel: string;
  claudePermissionMode: PermissionMode;
  codexPermissionMode: PermissionMode;
  longContextEnabled: boolean;
  reasoningEffort: ReasoningEffort;
  onCodexModelHydrated?: (modelId: string) => void;
}

/**
 * 仅持久化 Claude 侧的前端显示偏好。
 * Codex 的 provider/model/mode/reasoning 属于标签页运行态，必须由 Java 侧
 * `TabStateService` 回放，不能再从共享 localStorage 恢复，否则会污染其他标签页。
 */
export function useModelStatePersistence(options: UseModelStatePersistenceOptions) {
  const {
    setSelectedClaudeModel,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    selectedClaudeModel,
    claudePermissionMode,
    longContextEnabled,
  } = options;

  // Hydrate only Claude-side display preferences from localStorage.
  // Runtime state is restored by the Java tab snapshot.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      let restoredClaudePermissionMode: PermissionMode = 'bypassPermissions';

      if (saved) {
        const state = JSON.parse(saved);

        if (isValidPermissionMode(state.claudePermissionMode)) {
          restoredClaudePermissionMode = state.claudePermissionMode;
        }

        if (typeof state.longContextEnabled === 'boolean') {
          setLongContextEnabled(state.longContextEnabled);
        }

        const savedClaudeCustomModels = getCustomModels('claude-custom-models');
        const strippedClaudeModel = strip1MContextSuffix(state.claudeModel);
        const normalizedClaudeModel = normalizeClaudeModelId(strippedClaudeModel);
        if (
          CLAUDE_MODELS.find(m => m.id === normalizedClaudeModel)
          || savedClaudeCustomModels.find(m => m.id === normalizedClaudeModel)
        ) {
          setSelectedClaudeModel(normalizedClaudeModel);
        }
      }

      setClaudePermissionMode(restoredClaudePermissionMode);
      setCodexPermissionMode('default');
      setPermissionMode(restoredClaudePermissionMode);
    } catch {
      // Failed to load model selection state; fall back to hook defaults.
    }
  }, []);

  // Persist only Claude-side display preferences. Codex runtime state remains
  // tab-scoped on the Java side to avoid cross-tab pollution.
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        claudeModel: selectedClaudeModel,
        claudePermissionMode,
        longContextEnabled,
      }));
    } catch {
      // Failed to save model selection state; non-fatal.
    }
  }, [
    selectedClaudeModel,
    claudePermissionMode,
    longContextEnabled,
  ]);
}
