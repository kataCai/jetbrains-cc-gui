import { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CodexModelCatalogItem,
  CodexModelVisibilityConfig,
  CodexProviderConfig,
} from '../../../types/provider';

const sendToJava = (message: string) => {
  if (window.sendToJava) {
    window.sendToJava(message);
  }
  // Silently ignore when sendToJava is unavailable to avoid log pollution in production
};

export interface CodexProviderDialogState {
  isOpen: boolean;
  provider: CodexProviderConfig | null;
  initialProviderData?: Partial<CodexProviderConfig> | null;
}

export interface DeleteCodexConfirmState {
  isOpen: boolean;
  provider: CodexProviderConfig | null;
}

export interface UseCodexProviderManagementOptions {
  onError?: (message: string) => void;
  onSuccess?: (message: string) => void;
}

function buildCodexProviderPayload(providerData: CodexProviderConfig) {
  const trimmedApiKey = providerData.apiKey?.trim() || '';
  const payload: Partial<CodexProviderConfig> & { id: string; name: string } = {
    id: providerData.id,
    name: providerData.name,
    remark: providerData.remark?.trim() || undefined,
    authMode: providerData.authMode || 'api_key_env',
    requestMode: providerData.requestMode || 'codex_sdk',
    baseUrl: providerData.baseUrl?.trim() || undefined,
    apiKey: trimmedApiKey || undefined,
    apiKeyEnv: providerData.apiKeyEnv?.trim() || undefined,
    providerType: providerData.providerType?.trim() || undefined,
    presetId: providerData.presetId?.trim() || undefined,
    websiteUrl: providerData.websiteUrl?.trim() || undefined,
    apiKeyApplyUrl: providerData.apiKeyApplyUrl?.trim() || undefined,
    models: providerData.models && providerData.models.length > 0 ? providerData.models : undefined,
    ccSwitchProxy: providerData.ccSwitchProxy || undefined,
    customAdapter: providerData.customAdapter || undefined,
  };
  if (providerData.messageEnvVars && providerData.messageEnvVars.length > 0) {
    payload.messageEnvVars = providerData.messageEnvVars;
  }
  if (providerData.mcpEnvVars && providerData.mcpEnvVars.length > 0) {
    payload.mcpEnvVars = providerData.mcpEnvVars;
  }
  return payload;
}

export function useCodexProviderManagement(options: UseCodexProviderManagementOptions = {}) {
  const { t } = useTranslation();
  const { onSuccess } = options;

  // Codex provider list state
  const [codexProviders, setCodexProviders] = useState<CodexProviderConfig[]>([]);
  // 设置页挂载后会立即请求 provider 列表，初始值保持 loading 可避免入口意图在列表回传前被误消费。
  const [codexLoading, setCodexLoading] = useState(true);

  // Codex configuration (reserved for future display)
  const [_codexConfig, setCodexConfig] = useState<any>(null);
  const [_codexConfigLoading, setCodexConfigLoading] = useState(false);
  // 统一模型目录状态，供设置页 Models 面板直接消费。
  // 当前聊天区后续也会复用这份目录，避免继续从 active provider 临时拼接模型列表。
  const [codexModelCatalog, setCodexModelCatalog] = useState<CodexModelCatalogItem[]>([]);
  const [codexModelCatalogLoading, setCodexModelCatalogLoading] = useState(true);

  // Codex provider dialog state
  const [codexProviderDialog, setCodexProviderDialog] = useState<CodexProviderDialogState>({
    isOpen: false,
    provider: null,
    initialProviderData: null,
  });

  // Codex provider delete confirmation state
  const [deleteCodexConfirm, setDeleteCodexConfirm] = useState<DeleteCodexConfirmState>({
    isOpen: false,
    provider: null,
  });
  // 当前正在执行“测试连接”的 provider id。
  // 该状态只服务于设置页按钮级反馈，避免用户误判“点击后没有反应”。
  const [testingCodexProviderId, setTestingCodexProviderId] = useState('');

  // Load Codex provider list
  const loadCodexProviders = useCallback(() => {
    setCodexLoading(true);
    sendToJava('get_codex_providers:');
  }, []);

  // Update Codex provider list (used by window callback)
  const updateCodexProviders = useCallback((providersList: CodexProviderConfig[]) => {
    setCodexProviders(providersList);
    setCodexLoading(false);
  }, []);

  // Update active Codex provider (used by window callback)
  const updateActiveCodexProvider = useCallback((activeProvider: CodexProviderConfig) => {
    if (activeProvider) {
      setCodexProviders((prev) =>
        prev.map((p) => (p.id === activeProvider.id
          ? { ...p, ...activeProvider, isActive: true }
          : { ...p, isActive: false }))
      );
      // Custom models are now plugin-level, managed by PluginCustomModels in ProviderTabSection.
      // No longer sync provider-level customModels to localStorage.
    }
  }, []);

  // Update Codex configuration (used by window callback)
  const updateCurrentCodexConfig = useCallback((config: any) => {
    setCodexConfig(config);
    setCodexConfigLoading(false);
  }, []);

  /**
   * 主动拉取统一的 Codex 模型目录。
   * 该目录由后端聚合 CLI Login、managed provider 等多个来源，设置页只消费聚合结果。
   */
  const loadCodexModelCatalog = useCallback(() => {
    setCodexModelCatalogLoading(true);
    sendToJava('get_codex_model_catalog:');
  }, []);

  /**
   * 用后端返回的统一模型目录刷新本地状态。
   * @param catalog 后端返回的完整目录数组
   */
  const updateCodexModelCatalog = useCallback((catalog: CodexModelCatalogItem[]) => {
    setCodexModelCatalog(catalog);
    setCodexModelCatalogLoading(false);
  }, []);

  /**
   * 保存模型显示开关配置。
   * @param visibilityConfig 以 composite key 为主键的可见性配置
   */
  const saveCodexModelVisibility = useCallback((visibilityConfig: CodexModelVisibilityConfig) => {
    sendToJava(`set_codex_model_visibility:${JSON.stringify(visibilityConfig)}`);
  }, []);

  // Open add Codex provider dialog
  const handleAddCodexProvider = useCallback(() => {
    setCodexProviderDialog({ isOpen: true, provider: null, initialProviderData: null });
  }, []);

  /**
   * 使用模型别名预填一个新的 Codex provider 草稿。
   * 该入口用于把历史“模型别名”升级为真正可运行的 provider 配置，
   * 但仍然要求用户补齐 Base URL、鉴权等关键字段，避免做错误的自动迁移。
   *
   * @param providerDraft 仅用于初始化表单的 provider 草稿
   */
  const handleAddCodexProviderWithDraft = useCallback((providerDraft: Partial<CodexProviderConfig>) => {
    setCodexProviderDialog({
      isOpen: true,
      provider: null,
      initialProviderData: providerDraft,
    });
  }, []);

  // Open edit Codex provider dialog
  const handleEditCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setCodexProviderDialog({ isOpen: true, provider, initialProviderData: null });
  }, []);

  // Close Codex provider dialog
  const handleCloseCodexProviderDialog = useCallback(() => {
    setCodexProviderDialog({ isOpen: false, provider: null, initialProviderData: null });
  }, []);

  // Save Codex provider
  const handleSaveCodexProvider = useCallback(
    (providerData: CodexProviderConfig) => {
      const isAdding = !codexProviderDialog.provider;
      const payload = buildCodexProviderPayload(providerData);
      const shouldAutoActivate = providerData.autoActivate === true;

      if (isAdding) {
        sendToJava(`add_codex_provider:${JSON.stringify(payload)}`);
        if (shouldAutoActivate) {
          sendToJava(`switch_codex_provider:${JSON.stringify({ id: providerData.id })}`);
        }
        onSuccess?.(t('toast.providerAdded'));
      } else {
        const updateData = {
          id: providerData.id,
          updates: payload,
        };
        sendToJava(`update_codex_provider:${JSON.stringify(updateData)}`);
        if (shouldAutoActivate) {
          sendToJava(`switch_codex_provider:${JSON.stringify({ id: providerData.id })}`);
        }
        onSuccess?.(t('toast.providerUpdated'));
      }

      // Custom models are now plugin-level, managed by PluginCustomModels in ProviderTabSection.
      // No longer sync provider-level customModels to localStorage.

      setCodexProviderDialog({ isOpen: false, provider: null, initialProviderData: null });
      setCodexLoading(true);
    },
    [codexProviderDialog.provider, onSuccess, t]
  );

  // Switch Codex provider
  const handleSwitchCodexProvider = useCallback((id: string) => {
    const data = { id };
    sendToJava(`switch_codex_provider:${JSON.stringify(data)}`);
    setCodexLoading(true);
  }, []);

  /**
   * 仅授权读取本地 Codex 配置，不直接切换当前运行时 provider。
   * 当前前端先按计划约定的事件名发出请求，等待后端补齐独立授权桥接。
   */
  const handleAuthorizeCodexLocalConfig = useCallback(() => {
    sendToJava('authorize_codex_local_config:');
    setCodexLoading(true);
    setCodexConfigLoading(true);
    setCodexModelCatalogLoading(true);
  }, []);

  const handleRevokeCodexLocalConfigAuthorization = useCallback((fallbackProviderId?: string) => {
    const data = {
      fallbackProviderId: fallbackProviderId ?? '',
    };
    sendToJava(`revoke_codex_local_config_authorization:${JSON.stringify(data)}`);
    setCodexLoading(true);
    setCodexConfigLoading(true);
    setCodexModelCatalogLoading(true);
  }, []);

  const handleTestCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setTestingCodexProviderId(provider.id);
    sendToJava(`test_codex_provider:${JSON.stringify({ id: provider.id })}`);
  }, []);

  // Delete Codex provider
  const handleDeleteCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setDeleteCodexConfirm({ isOpen: true, provider });
  }, []);

  // Confirm Codex provider deletion
  const confirmDeleteCodexProvider = useCallback(() => {
    const provider = deleteCodexConfirm.provider;
    if (!provider) return;

    const data = { id: provider.id };
    sendToJava(`delete_codex_provider:${JSON.stringify(data)}`);
    onSuccess?.(t('toast.providerDeleted'));
    setCodexLoading(true);
    setDeleteCodexConfirm({ isOpen: false, provider: null });
  }, [deleteCodexConfirm.provider, onSuccess]);

  // Cancel Codex provider deletion
  const cancelDeleteCodexProvider = useCallback(() => {
    setDeleteCodexConfirm({ isOpen: false, provider: null });
  }, []);

  return {
    // State
    codexProviders,
    codexLoading,
    codexProviderDialog,
    deleteCodexConfirm,
    testingCodexProviderId,
    codexModelCatalog,
    codexModelCatalogLoading,
    // Methods
    loadCodexProviders,
    loadCodexModelCatalog,
    updateCodexProviders,
    updateActiveCodexProvider,
    updateCurrentCodexConfig,
    updateCodexModelCatalog,
    handleAddCodexProvider,
    handleAddCodexProviderWithDraft,
    handleEditCodexProvider,
    handleCloseCodexProviderDialog,
    handleSaveCodexProvider,
    handleAuthorizeCodexLocalConfig,
    handleSwitchCodexProvider,
    handleTestCodexProvider,
    handleRevokeCodexLocalConfigAuthorization,
    handleDeleteCodexProvider,
    confirmDeleteCodexProvider,
    cancelDeleteCodexProvider,
    saveCodexModelVisibility,
    // Setter
    setCodexLoading,
    setCodexConfigLoading,
    setTestingCodexProviderId,
    setCodexModelCatalogLoading,
  };
}

export type UseCodexProviderManagementReturn = ReturnType<typeof useCodexProviderManagement>;
