import { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CodexModelCatalogItem,
  CodexModelVisibilityConfig,
  CodexProviderConfig,
  CodexProviderDraftModelsFetchResult,
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
  /**
   * 当前弹窗草稿的异步请求关联 id。
   * 该值不等于持久化 provider id；新增、复制以及重新打开同一 provider 时都必须生成新值，
   * 用于隔离旧请求回包，避免把过期结果写回当前草稿。
   */
  draftRequestId: string;
  /**
   * 草稿级模型发现结果只服务于当前弹窗，不参与 provider 列表刷新或持久化。
   */
  draftModelsResult: CodexProviderDraftModelsFetchResult | null;
  /**
   * 每次草稿级模型发现成功后递增，通知弹窗仅回填 models 子树。
   */
  draftModelsRevision: number;
}

export interface DeleteCodexConfirmState {
  isOpen: boolean;
  provider: CodexProviderConfig | null;
}

/**
 * 统一模型目录删除确认弹窗状态。
 * 删除目录项前必须显式二次确认，避免误删 managed provider 模型或只读来源模型。
 */
export interface DeleteCodexModelCatalogConfirmState {
  isOpen: boolean;
  catalogItem: CodexModelCatalogItem | null;
}

export interface UseCodexProviderManagementOptions {
  onError?: (message: string) => void;
  onSuccess?: (message: string) => void;
}

/**
 * 为当前编辑弹窗生成一次性的草稿请求关联 id。
 * 优先复用浏览器原生 UUID，回退场景再拼接时间戳和随机串，避免新增模块级可变计数器。
 *
 * @return 当前弹窗可安全复用到异步桥接回包匹配的唯一标识
 */
function createCodexProviderDraftRequestId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `codex-draft-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

/**
 * 构建发送到后端桥接层的 provider payload。
 * 这里会统一裁剪字符串字段，并保留显式的空模型数组，避免“允许空模型保存”的改造在桥接层再次丢失。
 *
 * @param providerData 弹窗提交的结构化 provider 数据
 * @return 可直接序列化后发送给后端的最小 payload
 */
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
    models: providerData.models ? providerData.models : undefined,
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

/**
 * 生成供应商复制草稿名称。
 * 对已带“副本”后缀的名称先去掉旧后缀，避免连续复制时出现重复后缀；
 * 该名称仍只是新增弹窗默认值，用户可以在保存前继续修改。
 *
 * @param sourceName 原供应商名称
 * @return 适合作为复制草稿默认名称的文本
 */
function buildCodexProviderDuplicateName(sourceName: string): string {
  const normalizedName = sourceName.trim() || 'Codex Provider';
  return `${normalizedName.replace(/\s+副本(?:\s+\d+)?$/u, '').trim()} 副本`;
}

/**
 * 从已保存 provider 提取可编辑配置，生成新增态复制草稿。
 * 运行状态、持久化 ID、创建时间、掩码凭据和诊断字段均被显式剥离，
 * 防止复制操作把只读运行时信息误当成新的 provider 配置写回后端。
 *
 * @param provider 被复制的已保存 provider
 * @return 可直接交给新增弹窗的 provider 草稿
 */
function buildCodexProviderDuplicateDraft(provider: CodexProviderConfig): Partial<CodexProviderConfig> {
  return {
    name: buildCodexProviderDuplicateName(provider.name),
    remark: provider.remark,
    providerType: provider.providerType,
    presetId: provider.presetId,
    websiteUrl: provider.websiteUrl,
    apiKeyApplyUrl: provider.apiKeyApplyUrl,
    authMode: provider.authMode,
    requestMode: provider.requestMode,
    baseUrl: provider.baseUrl,
    apiKey: provider.apiKey,
    apiKeyEnv: provider.apiKeyEnv,
    models: provider.models?.map((model) => ({ ...model })),
    messageEnvVars: provider.messageEnvVars?.map((entry) => ({ ...entry })),
    mcpEnvVars: provider.mcpEnvVars?.map((entry) => ({ ...entry })),
    ccSwitchProxy: provider.ccSwitchProxy
      ? {
        ...provider.ccSwitchProxy,
        requestHeaders: provider.ccSwitchProxy.requestHeaders
          ? { ...provider.ccSwitchProxy.requestHeaders }
          : undefined,
      }
      : undefined,
    customAdapter: provider.customAdapter
      ? {
        ...provider.customAdapter,
        adapterHeaders: provider.customAdapter.adapterHeaders
          ? { ...provider.customAdapter.adapterHeaders }
          : undefined,
        adapterExtras: provider.customAdapter.adapterExtras
          ? { ...provider.customAdapter.adapterExtras }
          : undefined,
      }
      : undefined,
  };
}

/**
 * 统一创建 Codex provider 弹窗状态。
 * 通过集中收口默认值，避免在打开、关闭、保存和草稿拉模回填时遗漏相关字段。
 *
 * @param overrides 当前场景需要覆盖的状态字段
 * @return 完整的弹窗状态对象
 */
function createCodexProviderDialogState(
  overrides: Partial<CodexProviderDialogState> = {},
): CodexProviderDialogState {
  return {
    isOpen: false,
    provider: null,
    initialProviderData: null,
    draftRequestId: createCodexProviderDraftRequestId(),
    draftModelsResult: null,
    draftModelsRevision: 0,
    ...overrides,
  };
}

/**
 * 管理 Codex provider 列表、弹窗、模型目录以及桥接消息发送。
 * 该 hook 同时负责把“同步模型”结果以独立草稿通道回填到弹窗态，确保只更新 models 字段，不覆盖其它未保存编辑。
 *
 * @param options 页面级成功/失败回调
 * @return Codex 设置页所需的状态、操作方法与少量 setter
 */
export function useCodexProviderManagement(options: UseCodexProviderManagementOptions = {}) {
  const { t } = useTranslation();
  const { onSuccess } = options;

  const [codexProviders, setCodexProviders] = useState<CodexProviderConfig[]>([]);
  const [codexLoading, setCodexLoading] = useState(true);

  const [_codexConfig, setCodexConfig] = useState<any>(null);
  const [_codexConfigLoading, setCodexConfigLoading] = useState(false);
  const [codexModelCatalog, setCodexModelCatalog] = useState<CodexModelCatalogItem[]>([]);
  const [codexModelCatalogLoading, setCodexModelCatalogLoading] = useState(true);

  const [codexProviderDialog, setCodexProviderDialog] = useState<CodexProviderDialogState>(
    createCodexProviderDialogState(),
  );
  const [deleteCodexConfirm, setDeleteCodexConfirm] = useState<DeleteCodexConfirmState>({
    isOpen: false,
    provider: null,
  });
  const [deleteCodexModelCatalogConfirm, setDeleteCodexModelCatalogConfirm] =
    useState<DeleteCodexModelCatalogConfirmState>({
      isOpen: false,
      catalogItem: null,
    });
  const [testingCodexProviderId, setTestingCodexProviderId] = useState('');
  const [syncingCodexProviderId, setSyncingCodexProviderId] = useState('');
  const [syncingCodexProviderDraftId, setSyncingCodexProviderDraftId] = useState('');

  const loadCodexProviders = useCallback(() => {
    setCodexLoading(true);
    sendToJava('get_codex_providers:');
  }, []);

  const updateCodexProviders = useCallback((providersList: CodexProviderConfig[]) => {
    setCodexProviders(providersList);
    setCodexLoading(false);
  }, []);

  const updateActiveCodexProvider = useCallback((activeProvider: CodexProviderConfig) => {
    if (!activeProvider) {
      return;
    }
    setCodexProviders((prev) =>
      prev.map((provider) => (provider.id === activeProvider.id
        ? { ...provider, ...activeProvider, isActive: true }
        : { ...provider, isActive: false })),
    );
  }, []);

  const updateCurrentCodexConfig = useCallback((config: any) => {
    setCodexConfig(config);
    setCodexConfigLoading(false);
  }, []);

  /**
   * 主动加载统一的 Codex 模型目录。
   * 该目录聚合 CLI Login、managed provider 等多种来源，设置页直接消费聚合结果。
   *
   * @return void
   */
  const loadCodexModelCatalog = useCallback(() => {
    setCodexModelCatalogLoading(true);
    sendToJava('get_codex_model_catalog:');
  }, []);

  const updateCodexModelCatalog = useCallback((catalog: CodexModelCatalogItem[]) => {
    setCodexModelCatalog(catalog);
    setCodexModelCatalogLoading(false);
  }, []);

  const saveCodexModelVisibility = useCallback((visibilityConfig: CodexModelVisibilityConfig) => {
    sendToJava(`set_codex_model_visibility:${JSON.stringify(visibilityConfig)}`);
  }, []);

  const handleDeleteCodexModelCatalogItem = useCallback((catalogItem: CodexModelCatalogItem) => {
    setDeleteCodexModelCatalogConfirm({ isOpen: true, catalogItem });
  }, []);

  /**
   * 确认删除统一模型目录中的单个目录项。
   * 目录项的真实删除策略由后端根据来源判断，前端只负责透传关键标识并进入刷新态。
   *
   * @return void
   */
  const confirmDeleteCodexModelCatalogItem = useCallback(() => {
    const catalogItem = deleteCodexModelCatalogConfirm.catalogItem;
    if (!catalogItem) {
      return;
    }
    const payload = {
      key: catalogItem.key,
      providerId: catalogItem.providerId,
      modelId: catalogItem.modelId,
      source: catalogItem.source,
    };
    sendToJava(`delete_codex_model_catalog_item:${JSON.stringify(payload)}`);
    setCodexModelCatalogLoading(true);
    setDeleteCodexModelCatalogConfirm({ isOpen: false, catalogItem: null });
  }, [deleteCodexModelCatalogConfirm.catalogItem]);

  const cancelDeleteCodexModelCatalogItem = useCallback(() => {
    setDeleteCodexModelCatalogConfirm({ isOpen: false, catalogItem: null });
  }, []);

  const handleAddCodexProvider = useCallback(() => {
    setSyncingCodexProviderDraftId('');
    setCodexProviderDialog(createCodexProviderDialogState({
      isOpen: true,
      provider: null,
      initialProviderData: null,
    }));
  }, []);

  /**
   * 以预填草稿的方式打开新增 provider 弹窗。
   * 该入口通常用于把历史模型别名升级为结构化 provider，但仍允许用户继续修改并补齐字段。
   *
   * @param providerDraft 仅用于初始化表单的 provider 草稿
   * @return void
   */
  const handleAddCodexProviderWithDraft = useCallback((providerDraft: Partial<CodexProviderConfig>) => {
    setSyncingCodexProviderDraftId('');
    setCodexProviderDialog(createCodexProviderDialogState({
      isOpen: true,
      provider: null,
      initialProviderData: providerDraft,
    }));
  }, []);

  /**
   * 从现有托管 provider 生成新增态复制草稿并打开编辑弹窗。
   * 复制操作只复用可编辑配置，绝不把原 provider 的 ID、运行状态和诊断字段带入新增请求。
   *
   * @param provider 被复制的已保存 provider
   * @return void
   */
  const handleDuplicateCodexProvider = useCallback((provider: CodexProviderConfig) => {
    handleAddCodexProviderWithDraft(buildCodexProviderDuplicateDraft(provider));
  }, [handleAddCodexProviderWithDraft]);

  const handleEditCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setSyncingCodexProviderDraftId('');
    setCodexProviderDialog(createCodexProviderDialogState({
      isOpen: true,
      provider,
      initialProviderData: null,
    }));
  }, []);

  const handleCloseCodexProviderDialog = useCallback(() => {
    setSyncingCodexProviderDraftId('');
    setCodexProviderDialog(createCodexProviderDialogState());
  }, []);

  /**
   * 保存当前弹窗内的 Codex provider。
   * 新增与编辑都复用同一套 payload 构建逻辑，保存完成后统一关闭弹窗并进入列表刷新态。
   *
   * @param providerData 弹窗提交的完整 provider 数据
   * @return void
   */
  const handleSaveCodexProvider = useCallback(
    (providerData: CodexProviderConfig) => {
      const isAdding = !codexProviderDialog.provider;
      const payload = buildCodexProviderPayload(providerData);
      if (isAdding) {
        sendToJava(`add_codex_provider:${JSON.stringify(payload)}`);
        onSuccess?.(t('toast.providerAdded'));
      } else {
        sendToJava(`update_codex_provider:${JSON.stringify({
          id: providerData.id,
          updates: payload,
        })}`);
        onSuccess?.(t('toast.providerUpdated'));
      }

      setSyncingCodexProviderDraftId('');
      setCodexProviderDialog(createCodexProviderDialogState());
      setCodexLoading(true);
    },
    [codexProviderDialog.provider, onSuccess, t],
  );

  const handleAuthorizeCodexLocalConfig = useCallback(() => {
    sendToJava('authorize_codex_local_config:');
    setCodexLoading(true);
    setCodexConfigLoading(true);
    setCodexModelCatalogLoading(true);
  }, []);

  const handleRevokeCodexLocalConfigAuthorization = useCallback((fallbackProviderId?: string) => {
    sendToJava(`revoke_codex_local_config_authorization:${JSON.stringify({
      fallbackProviderId: fallbackProviderId ?? '',
    })}`);
    setCodexLoading(true);
    setCodexConfigLoading(true);
    setCodexModelCatalogLoading(true);
  }, []);

  const handleTestCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setTestingCodexProviderId(provider.id);
    sendToJava(`test_codex_provider:${JSON.stringify({ id: provider.id })}`);
  }, []);

  /**
   * 触发指定 provider 的远端模型列表拉取。
   * 这里只负责维护按钮级 loading 状态并发送桥接消息，真正的拉取与合并逻辑由后端处理。
   *
   * @param provider 需要同步模型列表的目标 provider
   * @return void
   */
  const handleFetchCodexProviderModels = useCallback((provider: CodexProviderConfig) => {
    setSyncingCodexProviderId(provider.id);
    sendToJava(`fetch_codex_provider_models:${JSON.stringify({ id: provider.id })}`);
  }, []);

  /**
   * 基于编辑弹窗当前草稿发起模型发现。
   * 该桥接消息携带当前表单里的 endpoint、凭据和模式字段，后端不会按 provider id 回读旧配置。
   * `provider.id` 在这条链路里仅作为弹窗级异步关联 id，不要求等于真实持久化 provider id。
   *
   * @param provider 当前弹窗构造出的草稿 provider
   * @return void
   */
  const handleFetchCodexProviderModelsFromDraft = useCallback((provider: CodexProviderConfig) => {
    const payload = {
      providerId: provider.id,
      name: provider.name,
      authMode: provider.authMode,
      requestMode: provider.requestMode,
      baseUrl: provider.baseUrl,
      apiKey: provider.apiKey,
      apiKeyEnv: provider.apiKeyEnv,
      models: provider.models || [],
      messageEnvVars: provider.messageEnvVars || [],
      mcpEnvVars: provider.mcpEnvVars || [],
      ccSwitchProxy: provider.ccSwitchProxy,
      customAdapter: provider.customAdapter,
    };
    setSyncingCodexProviderDraftId(provider.id);
    sendToJava(`fetch_codex_provider_models_from_draft:${JSON.stringify(payload)}`);
  }, []);

  /**
   * 接收草稿级模型发现结果，并仅写入当前弹窗的临时结果通道。
   * 结果必须匹配当前仍在编辑的 provider，避免用户切换弹窗后旧请求回包污染新草稿。
   *
   * @param result 后端返回的模型 ID 与发现统计
   * @return void
   */
  const updateCodexProviderDraftModels = useCallback((result: CodexProviderDraftModelsFetchResult) => {
    setCodexProviderDialog((prev) => {
      if (!prev.isOpen || prev.draftRequestId !== result.providerId) {
        return prev;
      }
      return {
        ...prev,
        draftModelsResult: result,
        draftModelsRevision: prev.draftModelsRevision + 1,
      };
    });
    setSyncingCodexProviderDraftId((currentId) => currentId === result.providerId ? '' : currentId);
  }, []);

  const handleDeleteCodexProvider = useCallback((provider: CodexProviderConfig) => {
    setDeleteCodexConfirm({ isOpen: true, provider });
  }, []);

  const confirmDeleteCodexProvider = useCallback(() => {
    const provider = deleteCodexConfirm.provider;
    if (!provider) {
      return;
    }

    sendToJava(`delete_codex_provider:${JSON.stringify({ id: provider.id })}`);
    onSuccess?.(t('toast.providerDeleted'));
    setCodexLoading(true);
    setDeleteCodexConfirm({ isOpen: false, provider: null });
  }, [deleteCodexConfirm.provider, onSuccess, t]);

  const cancelDeleteCodexProvider = useCallback(() => {
    setDeleteCodexConfirm({ isOpen: false, provider: null });
  }, []);

  return {
    codexProviders,
    codexLoading,
    codexProviderDialog,
    deleteCodexConfirm,
    deleteCodexModelCatalogConfirm,
    syncingCodexProviderId,
    syncingCodexProviderDraftId,
    testingCodexProviderId,
    codexModelCatalog,
    codexModelCatalogLoading,
    loadCodexProviders,
    loadCodexModelCatalog,
    updateCodexProviders,
    updateActiveCodexProvider,
    updateCurrentCodexConfig,
    updateCodexModelCatalog,
    handleAddCodexProvider,
    handleAddCodexProviderWithDraft,
    handleDuplicateCodexProvider,
    handleEditCodexProvider,
    handleCloseCodexProviderDialog,
    handleSaveCodexProvider,
    handleAuthorizeCodexLocalConfig,
    handleFetchCodexProviderModels,
    handleFetchCodexProviderModelsFromDraft,
    updateCodexProviderDraftModels,
    handleDeleteCodexModelCatalogItem,
    confirmDeleteCodexModelCatalogItem,
    cancelDeleteCodexModelCatalogItem,
    handleTestCodexProvider,
    handleRevokeCodexLocalConfigAuthorization,
    handleDeleteCodexProvider,
    confirmDeleteCodexProvider,
    cancelDeleteCodexProvider,
    saveCodexModelVisibility,
    setCodexLoading,
    setCodexConfigLoading,
    setSyncingCodexProviderId,
    setSyncingCodexProviderDraftId,
    setTestingCodexProviderId,
    setCodexModelCatalogLoading,
  };
}

export type UseCodexProviderManagementReturn = ReturnType<typeof useCodexProviderManagement>;
