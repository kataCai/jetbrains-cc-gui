import { useEffect } from 'react';
import { sendBridgeEvent } from '../../utils/bridge';
import {
  CLAUDE_MODELS,
  CODEX_MODELS,
  isValidPermissionMode,
  normalizeClaudeModelId,
  apply1MContextSuffix,
  strip1MContextSuffix,
} from '../../components/ChatInputBox/types';
import type { PermissionMode, ReasoningEffort } from '../../components/ChatInputBox/types';

const STORAGE_KEY = 'model-selection-state';
const REASONING_VALUES = ['low', 'medium', 'high', 'xhigh', 'max'] as const;

const getCustomModels = (key: string): { id: string }[] => {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
};

const isReasoningEffort = (value: unknown): value is ReasoningEffort =>
  typeof value === 'string' && (REASONING_VALUES as readonly string[]).includes(value);

/**
 * 判断给定模型是否存在于内置或自定义列表中。
 * Codex 当前模型可能来自本地 CLI 配置，不一定已经写入前端静态列表，
 * 因此前端恢复状态时需要允许保留未知但非空的模型 ID。
 *
 * @param modelId 待校验模型 ID
 * @param builtInModels 内置模型列表
 * @param customModels 本地自定义模型列表
 * @return 是否可直接视为已注册模型
 */
function isKnownModel(
  modelId: string | null | undefined,
  builtInModels: { id: string }[],
  customModels: { id: string }[],
): boolean {
  const normalizedModelId = typeof modelId === 'string' ? modelId.trim() : '';
  if (!normalizedModelId) {
    return false;
  }
  return builtInModels.some(model => model.id === normalizedModelId)
    || customModels.some(model => model.id === normalizedModelId);
}

/**
 * 解析可用于前端状态恢复的 Codex 模型 ID。
 * 若模型出现在已知列表中则原样返回；否则只要是非空字符串也允许保留，
 * 用于兼容后端同步过来的新模型或用户 CLI 本地配置中的未知模型。
 *
 * @param modelId 原始模型 ID
 * @param builtInModels 内置模型列表
 * @param customModels 自定义模型列表
 * @return 可恢复模型 ID；无效时返回 null
 */
function resolveRestorableCodexModelId(
  modelId: string | null | undefined,
  builtInModels: { id: string }[],
  customModels: { id: string }[],
): string | null {
  const normalizedModelId = typeof modelId === 'string' ? modelId.trim() : '';
  if (!normalizedModelId) {
    return null;
  }
  return isKnownModel(normalizedModelId, builtInModels, customModels)
    ? normalizedModelId
    : normalizedModelId;
}

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
 * Two effects for persisting cross-slice provider/model state to localStorage:
 *  1. On mount: hydrate state from localStorage and sync the restored values
 *     to the backend (retrying until the JCEF bridge is ready).
 *  2. On change: re-save the snapshot to localStorage.
 *
 * Save uses `JSON.stringify` of the seven persisted keys; load applies
 * defensive validation (custom models lookup, permission mode allowlist,
 * reasoning effort allowlist) before invoking the slice setters.
 */
export function useModelStatePersistence(options: UseModelStatePersistenceOptions) {
  const {
    setCurrentProvider,
    setSelectedClaudeModel,
    setSelectedCodexModel,
    setClaudePermissionMode,
    setCodexPermissionMode,
    setPermissionMode,
    setLongContextEnabled,
    setReasoningEffort,
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    claudePermissionMode,
    codexPermissionMode,
    longContextEnabled,
    reasoningEffort,
    onCodexModelHydrated,
  } = options;

  // Hydrate from localStorage and sync to backend (mount only).
  // Setters are stable; deps left empty to ensure single execution.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      let restoredProvider = 'claude';
      let restoredClaudeModel = CLAUDE_MODELS[0].id;
      let restoredCodexModel = CODEX_MODELS[0].id;
      let restoredClaudePermissionMode: PermissionMode = 'bypassPermissions';
      let restoredCodexPermissionMode: PermissionMode = 'default';
      let restoredLongContextEnabled = true;

      if (saved) {
        const state = JSON.parse(saved);

        if (['claude', 'codex'].includes(state.provider)) {
          restoredProvider = state.provider;
          setCurrentProvider(state.provider);
        }

        if (isValidPermissionMode(state.claudePermissionMode)) {
          restoredClaudePermissionMode = state.claudePermissionMode;
        }
        if (isValidPermissionMode(state.codexPermissionMode)) {
          restoredCodexPermissionMode = state.codexPermissionMode === 'plan'
            ? 'default'
            : state.codexPermissionMode;
        }

        if (typeof state.longContextEnabled === 'boolean') {
          restoredLongContextEnabled = state.longContextEnabled;
          setLongContextEnabled(state.longContextEnabled);
        }

        if (isReasoningEffort(state.reasoningEffort)) {
          setReasoningEffort(state.reasoningEffort);
        }

        const savedClaudeCustomModels = getCustomModels('claude-custom-models');
        const strippedClaudeModel = strip1MContextSuffix(state.claudeModel);
        const normalizedClaudeModel = normalizeClaudeModelId(strippedClaudeModel);
        if (
          CLAUDE_MODELS.find(m => m.id === normalizedClaudeModel) ||
          savedClaudeCustomModels.find(m => m.id === normalizedClaudeModel)
        ) {
          restoredClaudeModel = normalizedClaudeModel;
          setSelectedClaudeModel(normalizedClaudeModel);
        }

        const savedCodexCustomModels = getCustomModels('codex-custom-models');
        const restoredCodexModelId = resolveRestorableCodexModelId(
          state.codexModel,
          CODEX_MODELS,
          savedCodexCustomModels,
        );
        if (restoredCodexModelId) {
          restoredCodexModel = restoredCodexModelId;
          setSelectedCodexModel(restoredCodexModelId);
          onCodexModelHydrated?.(restoredCodexModelId);
        }
      }

      const initialPermissionMode: PermissionMode = restoredProvider === 'codex'
        ? restoredCodexPermissionMode
        : restoredClaudePermissionMode;
      setClaudePermissionMode(restoredClaudePermissionMode);
      setCodexPermissionMode(restoredCodexPermissionMode);
      setPermissionMode(initialPermissionMode);

      let syncRetryCount = 0;
      const MAX_SYNC_RETRIES = 30;

      const syncToBackend = () => {
        if (window.sendToJava) {
          sendBridgeEvent('set_provider', restoredProvider);
          const modelToSync = restoredProvider === 'codex'
            ? restoredCodexModel
            : apply1MContextSuffix(restoredClaudeModel, restoredLongContextEnabled);
          sendBridgeEvent('set_model', modelToSync);
          sendBridgeEvent('set_mode', initialPermissionMode);
        } else {
          syncRetryCount++;
          if (syncRetryCount < MAX_SYNC_RETRIES) {
            setTimeout(syncToBackend, 100);
          }
        }
      };
      setTimeout(syncToBackend, 200);
    } catch {
      // Failed to load model selection state — fall back to defaults already
      // set by individual slice hooks.
    }
  }, []);

  // Persist snapshot whenever any of the seven keys change.
  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        provider: currentProvider,
        claudeModel: selectedClaudeModel,
        codexModel: selectedCodexModel,
        claudePermissionMode,
        codexPermissionMode,
        longContextEnabled,
        reasoningEffort,
      }));
    } catch {
      // Failed to save model selection state — non-fatal.
    }
  }, [
    currentProvider,
    selectedClaudeModel,
    selectedCodexModel,
    claudePermissionMode,
    codexPermissionMode,
    longContextEnabled,
    reasoningEffort,
  ]);
}
