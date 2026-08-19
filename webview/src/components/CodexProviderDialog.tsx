import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import type {
  CodexAuthMode,
  CodexCcSwitchProxyConfig,
  CodexCustomAdapterConfig,
  CodexCustomModel,
  EnvVarEntry,
  CodexProviderConfig,
  CodexProviderDraftModelsFetchResult,
  CodexRequestMode,
} from '../types/provider';
import {
  ENV_VAR_VALUE_MAX_LENGTH,
  hasCodexProviderModelDiscoveryConfig,
  isCodexProviderModelFetchSupported,
  isCodexRequestModeImplemented,
  validateCodexCustomModels,
  validateEnvVarEntries,
} from '../types/provider';
import EnvVarEditor from './EnvVarEditor';

const GRID_STYLE: React.CSSProperties = { display: 'grid', gap: '12px' };
const FOOTER_ACTIONS_STYLE: React.CSSProperties = { marginLeft: 'auto', display: 'flex', gap: '8px' };
const INLINE_ACTION_STYLE: React.CSSProperties = { display: 'flex', gap: '8px', alignItems: 'center' };
const MODEL_ROW_STYLE: React.CSSProperties = {
  display: 'grid',
  gap: '8px',
  gridTemplateColumns: 'minmax(0, 1.2fr) minmax(0, 1fr) minmax(0, 1.2fr) auto',
  alignItems: 'start',
};
const MODEL_HEADER_STYLE: React.CSSProperties = { display: 'flex', flexDirection: 'column', gap: '6px' };
const FORM_LINK_STYLE: React.CSSProperties = { fontSize: '12px', color: 'var(--button-primary-background, #0078d4)' };
const MODE_SECTION_STYLE: React.CSSProperties = {
  display: 'grid',
  gap: '12px',
  padding: '12px',
  borderRadius: '8px',
  border: '1px solid var(--vscode-editorWidget-border, rgba(128, 128, 128, 0.35))',
  background: 'var(--vscode-editorWidget-background, rgba(128, 128, 128, 0.06))',
};

const AUTH_MODE_OPTIONS: CodexAuthMode[] = ['api_key', 'api_key_env', 'codex_cli_login', 'proxy', 'oauth'];
const REQUEST_MODE_OPTIONS: CodexRequestMode[] = ['codex_sdk', 'cc_switch_proxy', 'custom_adapter'];
const REQUEST_MODE_WARNING_STYLE: React.CSSProperties = {
  padding: '10px 12px',
  borderRadius: '8px',
  border: '1px solid var(--vscode-inputValidation-warningBorder, #b89500)',
  background: 'var(--vscode-inputValidation-warningBackground, rgba(184, 149, 0, 0.12))',
  color: 'var(--vscode-editor-foreground, inherit)',
};

interface CodexProviderPreset {
  id: string;
  providerType: string;
  providerName: string;
  websiteUrl: string;
  apiKeyApplyUrl: string;
  baseUrl: string;
  requestMode: CodexRequestMode;
  authMode: CodexAuthMode;
  models: CodexCustomModel[];
}

const CODEX_PROVIDER_PRESETS: CodexProviderPreset[] = [
  {
    id: 'custom_gateway',
    providerType: 'custom_gateway',
    providerName: '',
    websiteUrl: '',
    apiKeyApplyUrl: '',
    baseUrl: '',
    requestMode: 'codex_sdk',
    authMode: 'api_key',
    models: [],
  },
  {
    id: 'minimax',
    providerType: 'minimax',
    providerName: 'MiniMax',
    websiteUrl: 'https://platform.minimaxi.com',
    apiKeyApplyUrl: 'https://platform.minimaxi.com/user-center/basic-information/interface-key',
    baseUrl: 'https://api.minimaxi.com/v1',
    requestMode: 'codex_sdk',
    authMode: 'api_key',
    models: [
      {
        id: 'MiniMax-M2.5',
        label: 'MiniMax-M2.5',
      },
    ],
  },
];

function createEmptyModelRow(): CodexCustomModel {
  return {
    id: '',
    label: '',
    description: '',
  };
}

/**
 * 创建空的 cc-switch 代理配置。
 * 该默认值用于新建 provider 或切换到代理模式时初始化表单状态，避免受其它模式遗留字段污染。
 *
 * @return 空白的 cc-switch 代理配置对象
 */
function createEmptyCcSwitchProxyConfig(): CodexCcSwitchProxyConfig {
  return {
    proxyEndpoint: '',
    providerRoute: '',
    requestPath: '',
    requestHeaders: {},
  };
}

/**
 * 创建空的自定义 adapter 配置。
 * 该默认值用于新建 provider 或切换到 adapter 模式时初始化表单状态，确保 adapter 专属字段有稳定的起始值。
 *
 * @return 空白的自定义 adapter 配置对象
 */
function createEmptyCustomAdapterConfig(): CodexCustomAdapterConfig {
  return {
    adapterId: '',
    adapterEndpoint: '',
    adapterHeaders: {},
    adapterExtras: {},
  };
}

/**
 * 将运行时模型定义转换成适合表单编辑的行数据。
 * 当 label 与 id 相同时时，编辑态不重复回填显示名称，避免形成两个完全相同的输入值。
 */
function createEditableModelRow(model: CodexCustomModel): CodexCustomModel {
  return {
    id: model.id || '',
    label: model.label && model.label !== model.id ? model.label : '',
    description: model.description || '',
    reasoningEffort: model.reasoningEffort,
  };
}

/**
 * 判断模型行是否仍然是“纯空白占位行”。
 * 该判断用于阻止用户在尚未填写当前空行时继续无限追加同类空行。
 */
function isEmptyModelRow(model: CodexCustomModel): boolean {
  return !model.id?.trim() && !model.label?.trim() && !model.description?.trim();
}

/**
 * 将远端发现的模型 ID 追加到当前弹窗草稿。
 * 已存在的模型行保持原有 label、description 和 reasoningEffort；
 * 纯空白占位行会在确实有新结果时移除，避免保存后把无效占位项写入 provider。
 *
 * @param currentModels 当前弹窗中的模型行
 * @param fetchedModelIds 后端发现并去重后的模型 ID
 * @return 合并后的模型行列表，至少保留一个可编辑占位行
 */
function mergeFetchedModelIds(
  currentModels: CodexCustomModel[],
  fetchedModelIds: string[] | undefined,
): CodexCustomModel[] {
  const normalizedIds = Array.from(new Set(
    (fetchedModelIds || [])
      .map((modelId) => modelId.trim())
      .filter((modelId) => modelId.length > 0),
  ));
  if (normalizedIds.length === 0) {
    return currentModels;
  }

  const preservedModels = currentModels.filter((model) => !isEmptyModelRow(model));
  const existingIds = new Set(
    preservedModels
      .map((model) => model.id.trim())
      .filter((modelId) => modelId.length > 0),
  );
  const appendedModels = normalizedIds
    .filter((modelId) => !existingIds.has(modelId))
    .map((modelId) => ({
      id: modelId,
      label: modelId,
      description: '',
    }));
  const mergedModels = [...preservedModels, ...appendedModels];
  return mergedModels.length > 0 ? mergedModels : [createEmptyModelRow()];
}

function maskApiKey(value?: string): string {
  const trimmedValue = value?.trim() || '';
  if (trimmedValue.length <= 8) {
    return trimmedValue ? '******' : '';
  }
  return `${trimmedValue.slice(0, 4)}******${trimmedValue.slice(-4)}`;
}

/**
 * 将对象安全序列化为格式化 JSON 文本。
 * 该方法只负责 UI 文本呈现；当对象为空或未定义时统一回退为 `{}`，避免 textarea 出现 `undefined`。
 *
 * @param value 需要展示到表单中的 JSON 对象
 * @return 格式化后的 JSON 文本
 */
function stringifyJsonObject(value?: Record<string, unknown>): string {
  return JSON.stringify(value || {}, null, 2);
}

/**
 * 解析 JSON 文本并确保结果是普通对象。
 * 该方法用于模式专属 JSON 输入框的保存前校验，避免把数组、字符串或非法 JSON 直接写入 provider 配置。
 *
 * @param text 用户在 textarea 中输入的 JSON 文本
 * @return 成功时返回对象；失败时返回 null
 */
function parseJsonObjectText(text: string): Record<string, unknown> | null {
  const trimmedText = text.trim();
  if (!trimmedText) {
    return {};
  }
  try {
    const parsed = JSON.parse(trimmedText);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') {
      return null;
    }
    return parsed as Record<string, unknown>;
  } catch {
    return null;
  }
}

/**
 * 解析“值必须全部是字符串”的 JSON 对象。
 * 该方法用于请求头类字段，避免把数字、布尔值或嵌套对象误写入 `Record<string, string>` 配置。
 *
 * @param text 用户输入的 JSON 文本
 * @return 成功时返回字符串 map；失败时返回 null
 */
function parseStringMapText(text: string): Record<string, string> | null {
  const parsedObject = parseJsonObjectText(text);
  if (parsedObject == null) {
    return null;
  }
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(parsedObject)) {
    if (typeof value !== 'string') {
      return null;
    }
    result[key] = value;
  }
  return result;
}

/**
 * 返回请求模式对应的文案 key。
 * 该方法把模式说明从渲染逻辑中抽离出来，便于测试覆盖，也便于后续补充多语言文案。
 *
 * @param requestMode 当前选中的请求模式
 * @return 模式说明文案 key
 */
export function getCodexProviderModeDescription(requestMode: CodexRequestMode): string {
  return `settings.codexProvider.dialog.modeDescription.${requestMode}`;
}

export interface CodexProviderDraftValidationInput {
  providerName: string;
  authMode: CodexAuthMode;
  requestMode: CodexRequestMode;
  baseUrl: string;
  apiKey: string;
  apiKeyEnv: string;
  normalizedModels: CodexCustomModel[];
  ccSwitchProxy: CodexCcSwitchProxyConfig;
  customAdapter: CodexCustomAdapterConfig;
}

/**
 * 对当前表单草稿执行模式级校验。
 * 该方法只负责“当前模式哪些字段必须存在”的规则判断，不处理 JSON 解析等格式问题；格式错误由保存前的专门解析逻辑兜底。
 *
 * @param draft 当前表单草稿的归一化输入
 * @return 校验失败时返回对应的 i18n key；全部通过时返回 null
 */
export function validateCodexProviderDraft(draft: CodexProviderDraftValidationInput): string | null {
  if (!draft.providerName.trim()) {
    return 'settings.codexProvider.dialog.nameRequired';
  }
  if ((draft.authMode === 'api_key' || draft.authMode === 'api_key_env' || draft.authMode === 'proxy')
    && !draft.apiKey.trim()
    && !draft.apiKeyEnv.trim()) {
    return 'settings.codexProvider.dialog.apiKeyOrEnvRequired';
  }
  if (draft.requestMode === 'codex_sdk' && !draft.baseUrl.trim()) {
    return 'settings.codexProvider.dialog.baseUrlRequired';
  }
  if (draft.requestMode === 'cc_switch_proxy') {
    if (!draft.ccSwitchProxy.proxyEndpoint?.trim()) {
      return 'settings.codexProvider.dialog.proxyEndpointRequired';
    }
    if (!draft.ccSwitchProxy.providerRoute?.trim()) {
      return 'settings.codexProvider.dialog.providerRouteRequired';
    }
  }
  if (draft.requestMode === 'custom_adapter') {
    if (!draft.customAdapter.adapterId?.trim()) {
      return 'settings.codexProvider.dialog.adapterIdRequired';
    }
    if (!draft.customAdapter.adapterEndpoint?.trim()) {
      return 'settings.codexProvider.dialog.adapterEndpointRequired';
    }
  }
  return null;
}

/**
 * 判断当前表单所选请求模式是否属于“仅保留兼容、当前未落地”的状态。
 * 该判断同时用于：
 * 1. 新建场景禁用未实现模式选项；
 * 2. 编辑历史 provider 时展示风险提示；
 * 3. 禁止继续保存或激活未实现模式，避免制造新的假配置。
 *
 * @param mode 当前表单中的请求模式
 * @return `true` 表示当前模式尚未落地
 */
function isUnavailableRequestMode(mode: CodexRequestMode): boolean {
  return !isCodexRequestModeImplemented(mode);
}

interface CodexProviderDialogProps {
  isOpen: boolean;
  provider?: CodexProviderConfig | null;
  initialProviderData?: Partial<CodexProviderConfig> | null;
  /**
   * 当前弹窗草稿对应的异步请求关联 id。
   * 新增、复制和重新打开编辑弹窗时都应由上层重新生成，用于隔离旧请求回包。
   */
  draftRequestId?: string;
  onClose: () => void;
  onSave: (provider: CodexProviderConfig) => void;
  onFetchModels?: (provider: CodexProviderConfig) => void;
  fetchingModels?: boolean;
  fetchedDraftModels?: CodexProviderDraftModelsFetchResult | null;
  fetchedDraftModelsRevision?: number;
  addToast: (message: string, type: 'success' | 'error' | 'info') => void;
}

/**
 * 将当前结构化模型列表序列化为高级 JSON 文本。
 * 这里只同步真正有效的模型条目，避免把空占位行也写入 JSON 编辑器。
 *
 * @param models 当前结构化模型列表
 * @return 供高级 JSON 编辑器展示的格式化文本
 */
function serializeModelsJson(models: CodexCustomModel[]): string {
  return JSON.stringify(
    models
      .map((model) => ({
        ...model,
        id: model.id.trim(),
        label: (model.label || model.id).trim(),
        description: model.description?.trim() || undefined,
      }))
      .filter((model) => model.id.length > 0),
    null,
    2,
  );
}

/**
 * Codex provider 结构化配置弹窗。
 * 该弹窗负责创建和编辑“可运行的 provider 配置”，显式维护 provider/preset/baseUrl/apiKey/models 这一整组运行参数，
 * 不再把模型列表退化成单纯 JSON 文本框，避免用户误以为“添加模型别名”就等于“接入一个新模型供应商”。
 */
export default function CodexProviderDialog({
  isOpen,
  provider,
  initialProviderData,
  draftRequestId,
  onClose,
  onSave,
  onFetchModels,
  fetchingModels = false,
  fetchedDraftModels = null,
  fetchedDraftModelsRevision = 0,
  addToast,
}: CodexProviderDialogProps) {
  const { t } = useTranslation();
  const isAdding = !provider;
  const [providerPreset, setProviderPreset] = useState('custom_gateway');
  const [providerName, setProviderName] = useState('');
  const [remark, setRemark] = useState('');
  const [websiteUrl, setWebsiteUrl] = useState('');
  const [apiKeyApplyUrl, setApiKeyApplyUrl] = useState('');
  const [authMode, setAuthMode] = useState<CodexAuthMode>('api_key');
  const [requestMode, setRequestMode] = useState<CodexRequestMode>('codex_sdk');
  const [baseUrl, setBaseUrl] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [apiKeyEnv, setApiKeyEnv] = useState('');
  const [models, setModels] = useState<CodexCustomModel[]>([createEmptyModelRow()]);
  const [ccSwitchProxy, setCcSwitchProxy] = useState<CodexCcSwitchProxyConfig>(createEmptyCcSwitchProxyConfig());
  const [customAdapter, setCustomAdapter] = useState<CodexCustomAdapterConfig>(createEmptyCustomAdapterConfig());
  const [showApiKey, setShowApiKey] = useState(false);
  const [showAdvancedJsonEditor, setShowAdvancedJsonEditor] = useState(false);
  const [modelsJsonText, setModelsJsonText] = useState('[]');
  const [proxyHeadersJsonText, setProxyHeadersJsonText] = useState('{}');
  const [adapterHeadersJsonText, setAdapterHeadersJsonText] = useState('{}');
  const [adapterExtrasJsonText, setAdapterExtrasJsonText] = useState('{}');
  const [messageEnvVars, setMessageEnvVars] = useState<EnvVarEntry[]>([]);
  const [mcpEnvVars, setMcpEnvVars] = useState<EnvVarEntry[]>([]);
  const lastAppliedDraftModelsRevisionRef = useRef(0);

  const selectedPreset = useMemo(
    () => CODEX_PROVIDER_PRESETS.find((preset) => preset.id === providerPreset),
    [providerPreset],
  );
  const maskedApiKey = useMemo(() => maskApiKey(apiKey), [apiKey]);
  const requestModeUnavailable = useMemo(() => isUnavailableRequestMode(requestMode), [requestMode]);
  const isCodexSdkMode = requestMode === 'codex_sdk';
  const isCcSwitchProxyMode = requestMode === 'cc_switch_proxy';
  const isCustomAdapterMode = requestMode === 'custom_adapter';
  const draftModelFetchSupported = isCodexProviderModelFetchSupported({ authMode, requestMode });
  const draftModelFetchConfigured = hasCodexProviderModelDiscoveryConfig({
    baseUrl,
    apiKey,
    apiKeyEnv,
  });

  /**
   * 将 provider 数据投影到结构化表单。
   * 新增场景默认落到 custom gateway；编辑场景优先回显已有 preset/providerType。
   */
  useEffect(() => {
    if (!isOpen) {
      return;
    }
    // 切换弹窗上下文时重置已应用的结果版本，避免旧请求回包误回填到新的编辑草稿。
    lastAppliedDraftModelsRevisionRef.current = fetchedDraftModelsRevision ?? 0;
    if (provider) {
      setProviderPreset(provider.presetId || provider.providerType || 'custom_gateway');
      setProviderName(provider.name || '');
      setRemark(provider.remark || '');
      setWebsiteUrl(provider.websiteUrl || '');
      setApiKeyApplyUrl(provider.apiKeyApplyUrl || '');
      setAuthMode(provider.authMode || 'api_key');
      setRequestMode(provider.requestMode || 'codex_sdk');
      setBaseUrl(provider.baseUrl || '');
      setApiKey(provider.apiKey || '');
      setApiKeyEnv(provider.apiKeyEnv || '');
      setCcSwitchProxy(provider.ccSwitchProxy || createEmptyCcSwitchProxyConfig());
      setCustomAdapter(provider.customAdapter || createEmptyCustomAdapterConfig());
      setMessageEnvVars(provider.messageEnvVars || []);
      setMcpEnvVars(provider.mcpEnvVars || []);
      setModels(
        provider.models && provider.models.length > 0
          ? provider.models.map(createEditableModelRow)
          : [createEmptyModelRow()],
      );
      setShowApiKey(false);
      setShowAdvancedJsonEditor(false);
      return;
    }
    const nextPreset = initialProviderData?.presetId || initialProviderData?.providerType || 'custom_gateway';
    setProviderPreset(nextPreset);
    setProviderName(initialProviderData?.name || '');
    setRemark(initialProviderData?.remark || '');
    setWebsiteUrl(initialProviderData?.websiteUrl || '');
    setApiKeyApplyUrl(initialProviderData?.apiKeyApplyUrl || '');
    setAuthMode(initialProviderData?.authMode || 'api_key');
    setRequestMode(initialProviderData?.requestMode || 'codex_sdk');
    setBaseUrl(initialProviderData?.baseUrl || '');
    setApiKey(initialProviderData?.apiKey || '');
    setApiKeyEnv(initialProviderData?.apiKeyEnv || '');
    setCcSwitchProxy(initialProviderData?.ccSwitchProxy || createEmptyCcSwitchProxyConfig());
    setCustomAdapter(initialProviderData?.customAdapter || createEmptyCustomAdapterConfig());
    setMessageEnvVars(initialProviderData?.messageEnvVars || []);
    setMcpEnvVars(initialProviderData?.mcpEnvVars || []);
    setModels(
      initialProviderData?.models && initialProviderData.models.length > 0
        ? initialProviderData.models.map(createEditableModelRow)
        : [createEmptyModelRow()],
    );
    setShowApiKey(false);
    setShowAdvancedJsonEditor(false);
  }, [draftRequestId, initialProviderData, isOpen, provider]);

  useEffect(() => {
    setModelsJsonText(serializeModelsJson(models));
  }, [models]);

  useEffect(() => {
    setProxyHeadersJsonText(stringifyJsonObject(ccSwitchProxy.requestHeaders));
  }, [ccSwitchProxy.requestHeaders]);

  useEffect(() => {
    setAdapterHeadersJsonText(stringifyJsonObject(customAdapter.adapterHeaders));
  }, [customAdapter.adapterHeaders]);

  useEffect(() => {
    setAdapterExtrasJsonText(stringifyJsonObject(customAdapter.adapterExtras as Record<string, unknown> | undefined));
  }, [customAdapter.adapterExtras]);

  useEffect(() => {
    if (!isOpen || fetchedDraftModelsRevision <= lastAppliedDraftModelsRevisionRef.current) {
      return;
    }
    // 草稿级拉模结果只追加缺失项，显式保留当前弹窗里的其它未保存字段。
    setModels((currentModels) => mergeFetchedModelIds(currentModels, fetchedDraftModels?.modelIds));
    lastAppliedDraftModelsRevisionRef.current = fetchedDraftModelsRevision;
  }, [fetchedDraftModels, fetchedDraftModelsRevision, isOpen]);

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    const handleEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose();
      }
    };
    window.addEventListener('keydown', handleEscape);
    return () => window.removeEventListener('keydown', handleEscape);
  }, [isOpen, onClose]);

  /**
   * 选择 preset 时自动填充 provider 基础字段。
   * 编辑态不强制覆盖用户已保存的值；新增态则直接按模板初始化，贴近 cc-switch 的 provider-centric 创建体验。
   */
  const handlePresetChange = (presetId: string) => {
    setProviderPreset(presetId);
    const nextPreset = CODEX_PROVIDER_PRESETS.find((preset) => preset.id === presetId);
    if (!nextPreset) {
      return;
    }
    setAuthMode(nextPreset.authMode);
    setRequestMode(nextPreset.requestMode);
    if (provider) {
      return;
    }
    setProviderName(nextPreset.providerName);
    setWebsiteUrl(nextPreset.websiteUrl);
    setApiKeyApplyUrl(nextPreset.apiKeyApplyUrl);
    setBaseUrl(nextPreset.baseUrl);
    setCcSwitchProxy(createEmptyCcSwitchProxyConfig());
    setCustomAdapter(createEmptyCustomAdapterConfig());
    setModels(
      nextPreset.models.length > 0
        ? nextPreset.models.map(createEditableModelRow)
        : [createEmptyModelRow()],
    );
  };

  /**
   * 更新 cc-switch 代理模式下的单个字段。
   * 该方法只负责维护代理模式局部状态，避免把模式专属字段混入通用表单更新逻辑。
   *
   * @param field 需要更新的代理配置字段
   * @param value 新值
   */
  function handleCcSwitchProxyFieldChange(field: keyof CodexCcSwitchProxyConfig, value: string) {
    setCcSwitchProxy((prev) => ({ ...prev, [field]: value }));
  }

  /**
   * 更新自定义 adapter 模式下的单个字段。
   * 该方法用于维护 adapter 模式的专属输入，确保切换模式时不会破坏其它模式的状态。
   *
   * @param field 需要更新的 adapter 配置字段
   * @param value 新值
   */
  function handleCustomAdapterFieldChange(field: keyof CodexCustomAdapterConfig, value: string) {
    setCustomAdapter((prev) => ({ ...prev, [field]: value }));
  }

  /**
   * 更新指定模型行。
   * 这里保留行级结构而不是 JSON 编辑，便于后续继续扩展 reasoning effort、默认模型等配置。
   */
  const handleModelFieldChange = (index: number, field: keyof CodexCustomModel, value: string) => {
    setModels((prev) => prev.map((model, currentIndex) => (
      currentIndex === index
        ? { ...model, [field]: value }
        : model
    )));
  };

  /**
   * 将高级 JSON 编辑器中的模型数组同步回结构化模型列表。
   * 这里只接受合法的 CodexCustomModel 数组，避免把非法配置写入 provider payload。
   */
  const handleApplyModelsJson = () => {
    try {
      const parsedModels = JSON.parse(modelsJsonText);
      if (!Array.isArray(parsedModels)) {
        addToast(t('settings.codexProvider.dialog.modelsJsonInvalid'), 'error');
        return;
      }
      const validModels = validateCodexCustomModels(parsedModels);
      if (validModels.length !== parsedModels.length) {
        addToast(t('settings.codexProvider.dialog.modelsJsonInvalid'), 'error');
        return;
      }
      setModels(validModels.length > 0 ? validModels.map(createEditableModelRow) : [createEmptyModelRow()]);
    } catch {
      addToast(t('settings.codexProvider.dialog.modelsJsonInvalid'), 'error');
    }
  };

  /**
   * 将环境变量校验错误转换成当前弹窗的 toast。
   * 校验逻辑复用类型层工具，弹窗只负责把失败原因映射到用户可理解的多语言文案。
   *
   * @param issue 校验工具返回的第一条错误
   * @param sectionLabel 当前错误所属的环境变量分组名称
   * @return `true` 表示已经展示错误；`false` 表示该错误类型无需展示
   */
  const reportEnvVarIssue = (
    issue: { reason: string; key?: string },
    sectionLabel: string,
  ): boolean => {
    const reasonKey = (() => {
      switch (issue.reason) {
        case 'invalid':
          return 'settings.codexProvider.dialog.envKeyInvalid';
        case 'protected':
          return 'settings.codexProvider.dialog.envKeyProtected';
        case 'duplicate':
          return 'settings.codexProvider.dialog.envKeyDuplicate';
        case 'value_too_long':
          return 'settings.codexProvider.dialog.envValueTooLong';
        default:
          return null;
      }
    })();
    if (!reasonKey) return false;
    addToast(
      `${sectionLabel}: ${t(reasonKey, { key: issue.key, max: ENV_VAR_VALUE_MAX_LENGTH })}`,
      'error',
    );
    return true;
  };

  /**
   * 过滤空模型行，保证提交 payload 只包含真正可运行的模型定义。
   */
  const normalizedModels = useMemo(
    () => models
      .map((model) => ({
        ...model,
        id: model.id.trim(),
        label: (model.label || model.id).trim(),
        description: model.description?.trim() || undefined,
      }))
      .filter((model) => model.id.length > 0),
    [models],
  );

  /**
   * 基于当前弹窗草稿获取远端模型列表。
   * 发送前重新组装当前表单字段，确保用户尚未保存的 endpoint、凭据和请求模式都参与发现。
   * 这里始终使用上层分配的 `draftRequestId` 作为异步关联 id，
   * 这样新增/复制草稿在尚未保存时也能直接拉模，并且旧回包不会误命中新的弹窗会话。
   *
   * @return void
   */
  const handleFetchModels = () => {
    if (!draftRequestId || !onFetchModels) {
      return;
    }
    onFetchModels({
      id: draftRequestId,
      name: providerName.trim(),
      providerType: selectedPreset?.providerType || providerPreset,
      presetId: providerPreset,
      remark: remark.trim() || undefined,
      websiteUrl: websiteUrl.trim() || undefined,
      apiKeyApplyUrl: apiKeyApplyUrl.trim() || undefined,
      createdAt: provider?.createdAt,
      authMode,
      requestMode,
      baseUrl: baseUrl.trim() || undefined,
      apiKey: apiKey.trim() || undefined,
      apiKeyEnv: apiKeyEnv.trim() || undefined,
      models: normalizedModels,
      messageEnvVars,
      mcpEnvVars,
      ccSwitchProxy,
      customAdapter,
    });
  };

  /**
   * 执行当前弹窗的保存。
   * 保存时会统一完成模式级校验、环境变量校验与结构化 payload 组装，然后把结果交给上层 hook 处理。
   *
   * @return void
   */
  const handleSave = () => {
    if (requestModeUnavailable) {
      addToast(t('settings.codexProvider.dialog.requestModeUnavailableHint'), 'error');
      return;
    }

    const parsedProxyHeaders = parseStringMapText(proxyHeadersJsonText);
    if (isCcSwitchProxyMode && parsedProxyHeaders == null) {
      addToast(t('settings.codexProvider.dialog.proxyHeadersInvalid', { defaultValue: 'Proxy headers JSON is invalid' }), 'error');
      return;
    }
    const parsedAdapterHeaders = parseStringMapText(adapterHeadersJsonText);
    if (isCustomAdapterMode && parsedAdapterHeaders == null) {
      addToast(t('settings.codexProvider.dialog.adapterHeadersInvalid', { defaultValue: 'Adapter headers JSON is invalid' }), 'error');
      return;
    }
    const parsedAdapterExtras = parseJsonObjectText(adapterExtrasJsonText);
    if (isCustomAdapterMode && parsedAdapterExtras == null) {
      addToast(t('settings.codexProvider.dialog.adapterExtrasInvalid', { defaultValue: 'Adapter extras JSON is invalid' }), 'error');
      return;
    }

    const validationErrorKey = validateCodexProviderDraft({
      providerName,
      authMode,
      requestMode,
      baseUrl,
      apiKey,
      apiKeyEnv,
      normalizedModels,
      ccSwitchProxy: {
        ...ccSwitchProxy,
        requestHeaders: parsedProxyHeaders || {},
      },
      customAdapter: {
        ...customAdapter,
        adapterHeaders: parsedAdapterHeaders || {},
        adapterExtras: parsedAdapterExtras || {},
      },
    });
    if (validationErrorKey) {
      addToast(t(validationErrorKey), 'error');
      return;
    }

    const messageIssues = validateEnvVarEntries(messageEnvVars);
    if (messageIssues.length > 0) {
      reportEnvVarIssue(messageIssues[0], t('settings.codexProvider.dialog.messageEnvLabel'));
      return;
    }
    const mcpIssues = validateEnvVarEntries(mcpEnvVars);
    if (mcpIssues.length > 0) {
      reportEnvVarIssue(mcpIssues[0], t('settings.codexProvider.dialog.mcpEnvLabel'));
      return;
    }

    const providerData: CodexProviderConfig = {
      id: provider?.id || (crypto.randomUUID ? crypto.randomUUID() : Date.now().toString()),
      name: providerName.trim(),
      providerType: selectedPreset?.providerType || providerPreset,
      presetId: providerPreset,
      remark: remark.trim() || undefined,
      websiteUrl: websiteUrl.trim() || undefined,
      apiKeyApplyUrl: apiKeyApplyUrl.trim() || undefined,
      createdAt: provider?.createdAt,
      authMode,
      requestMode,
      baseUrl: isCodexSdkMode ? baseUrl.trim() || undefined : undefined,
      apiKey: apiKey.trim() || undefined,
      apiKeyEnv: apiKeyEnv.trim() || undefined,
      models: normalizedModels,
      messageEnvVars: messageEnvVars.filter((entry) => entry.key.trim() !== ''),
      mcpEnvVars: mcpEnvVars.filter((entry) => entry.key.trim() !== ''),
      ccSwitchProxy: isCcSwitchProxyMode ? {
        proxyEndpoint: ccSwitchProxy.proxyEndpoint?.trim() || undefined,
        providerRoute: ccSwitchProxy.providerRoute?.trim() || undefined,
        requestPath: ccSwitchProxy.requestPath?.trim() || undefined,
        requestHeaders: parsedProxyHeaders || {},
      } : undefined,
      customAdapter: isCustomAdapterMode ? {
        adapterId: customAdapter.adapterId?.trim() || undefined,
        adapterEndpoint: customAdapter.adapterEndpoint?.trim() || undefined,
        adapterHeaders: parsedAdapterHeaders || {},
        adapterExtras: parsedAdapterExtras || {},
      } : undefined,
    };

    onSave(providerData);
    onClose();
  };

  if (!isOpen) {
    return null;
  }

  return (
    <div className="dialog-overlay">
      <div className="dialog provider-dialog codex-provider-dialog">
        <div className="dialog-header">
          <h3>
            {isAdding
              ? t('settings.codexProvider.dialog.addTitle')
              : t('settings.codexProvider.dialog.editTitle', { name: provider?.name })}
          </h3>
          <button className="close-btn" onClick={onClose}>
            <span className="codicon codicon-close" />
          </button>
        </div>

        <div className="dialog-body">
          <p className="dialog-desc">
            {isAdding
              ? t('settings.codexProvider.dialog.addDescription')
              : t('settings.codexProvider.dialog.editDescription')}
          </p>

          <div style={GRID_STYLE}>
            {requestModeUnavailable && (
              <div className="form-group" style={REQUEST_MODE_WARNING_STYLE}>
                {t('settings.codexProvider.dialog.requestModeUnavailableHint')}
              </div>
            )}

            <div className="form-group">
              <label htmlFor="providerPreset">{t('settings.codexProvider.dialog.providerPreset')}</label>
              <select
                id="providerPreset"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.providerPreset')}
                value={providerPreset}
                onChange={(e) => handlePresetChange(e.target.value)}
              >
                {CODEX_PROVIDER_PRESETS.map((preset) => (
                  <option key={preset.id} value={preset.id}>
                    {preset.id}
                  </option>
                ))}
              </select>
            </div>

            <div className="form-group">
              <label htmlFor="providerName">
                <span>{t('settings.codexProvider.dialog.providerName')}</span>
                <span className="required" aria-hidden="true">{t('settings.provider.dialog.required')}</span>
              </label>
              <input
                id="providerName"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.providerName')}
                placeholder={t('settings.codexProvider.dialog.providerNamePlaceholder')}
                value={providerName}
                onChange={(e) => setProviderName(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="websiteUrl">{t('settings.codexProvider.dialog.websiteUrl')}</label>
              <input
                id="websiteUrl"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.websiteUrl')}
                placeholder={t('settings.codexProvider.dialog.websiteUrlPlaceholder')}
                value={websiteUrl}
                onChange={(e) => setWebsiteUrl(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="apiKeyApplyUrl">{t('settings.codexProvider.dialog.apiKeyApplyUrl')}</label>
              <input
                id="apiKeyApplyUrl"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.apiKeyApplyUrl')}
                placeholder={t('settings.codexProvider.dialog.apiKeyApplyUrlPlaceholder')}
                value={apiKeyApplyUrl}
                onChange={(e) => setApiKeyApplyUrl(e.target.value)}
              />
              {apiKeyApplyUrl.trim() && (
                <a href={apiKeyApplyUrl.trim()} target="_blank" rel="noreferrer" style={FORM_LINK_STYLE}>
                  {apiKeyApplyUrl.trim()}
                </a>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="providerRemark">{t('settings.provider.dialog.remark')}</label>
              <input
                id="providerRemark"
                type="text"
                className="form-input"
                aria-label={t('settings.provider.dialog.remark')}
                placeholder={t('settings.provider.dialog.remarkPlaceholder')}
                value={remark}
                onChange={(e) => setRemark(e.target.value)}
              />
            </div>

            <div className="form-group">
              <label htmlFor="authMode">{t('settings.codexProvider.dialog.authMode')}</label>
              <select
                id="authMode"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.authMode')}
                value={authMode}
                onChange={(e) => setAuthMode(e.target.value as CodexAuthMode)}
              >
                {AUTH_MODE_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {t(`settings.codexProvider.dialog.authModeOptions.${option}`)}
                  </option>
                ))}
              </select>
              <small className="form-hint">{t('settings.codexProvider.dialog.authModeHint')}</small>
            </div>

            <div className="form-group">
              <label htmlFor="requestMode">{t('settings.codexProvider.dialog.requestMode')}</label>
              <select
                id="requestMode"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.requestMode')}
                value={requestMode}
                onChange={(e) => setRequestMode(e.target.value as CodexRequestMode)}
              >
                {REQUEST_MODE_OPTIONS.map((option) => (
                  <option
                    key={option}
                    value={option}
                    disabled={isUnavailableRequestMode(option)}
                  >
                    {isUnavailableRequestMode(option)
                      ? `${t(`settings.codexProvider.dialog.requestModeOptions.${option}`)}${t('settings.codexProvider.dialog.requestModeUnavailableOptionSuffix')}`
                      : t(`settings.codexProvider.dialog.requestModeOptions.${option}`)}
                  </option>
                ))}
              </select>
              <small className="form-hint">
                {requestModeUnavailable
                  ? t('settings.codexProvider.dialog.requestModeUnavailableHint')
                  : t(getCodexProviderModeDescription(requestMode), {
                    defaultValue: t('settings.codexProvider.dialog.requestModeHint'),
                  })}
              </small>
            </div>

            {isCodexSdkMode && (
              <div className="form-group">
                <label htmlFor="baseUrl">{t('settings.codexProvider.dialog.baseUrl')}</label>
                <input
                  id="baseUrl"
                  type="text"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.baseUrl')}
                  placeholder={t('settings.codexProvider.dialog.baseUrlPlaceholder')}
                  value={baseUrl}
                  onChange={(e) => setBaseUrl(e.target.value)}
                />
                <small className="form-hint">{t('settings.codexProvider.dialog.baseUrlHint')}</small>
              </div>
            )}

            {isCcSwitchProxyMode && (
              <div className="form-group" style={MODE_SECTION_STYLE}>
                <label htmlFor="ccSwitchProxyEndpoint">
                  {t('settings.codexProvider.dialog.ccSwitchProxy.proxyEndpoint', {
                    defaultValue: 'CC Switch Proxy Endpoint',
                  })}
                </label>
                <input
                  id="ccSwitchProxyEndpoint"
                  type="text"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.ccSwitchProxy.proxyEndpoint', {
                    defaultValue: 'CC Switch Proxy Endpoint',
                  })}
                  placeholder={t('settings.codexProvider.dialog.ccSwitchProxy.proxyEndpointPlaceholder', {
                    defaultValue: 'For example: http://127.0.0.1:15721',
                  })}
                  value={ccSwitchProxy.proxyEndpoint || ''}
                  onChange={(e) => handleCcSwitchProxyFieldChange('proxyEndpoint', e.target.value)}
                />

                <label htmlFor="ccSwitchProviderRoute">
                  {t('settings.codexProvider.dialog.ccSwitchProxy.providerRoute', {
                    defaultValue: 'Provider Route',
                  })}
                </label>
                <input
                  id="ccSwitchProviderRoute"
                  type="text"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.ccSwitchProxy.providerRoute', {
                    defaultValue: 'Provider Route',
                  })}
                  placeholder={t('settings.codexProvider.dialog.ccSwitchProxy.providerRoutePlaceholder', {
                    defaultValue: 'For example: minimax',
                  })}
                  value={ccSwitchProxy.providerRoute || ''}
                  onChange={(e) => handleCcSwitchProxyFieldChange('providerRoute', e.target.value)}
                />

                <label htmlFor="ccSwitchRequestPath">
                  {t('settings.codexProvider.dialog.ccSwitchProxy.requestPath', {
                    defaultValue: 'Request Path',
                  })}
                </label>
                <input
                  id="ccSwitchRequestPath"
                  type="text"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.ccSwitchProxy.requestPath', {
                    defaultValue: 'Request Path',
                  })}
                  placeholder={t('settings.codexProvider.dialog.ccSwitchProxy.requestPathPlaceholder', {
                    defaultValue: 'For example: /v1/responses',
                  })}
                  value={ccSwitchProxy.requestPath || ''}
                  onChange={(e) => handleCcSwitchProxyFieldChange('requestPath', e.target.value)}
                />

                <label htmlFor="ccSwitchRequestHeaders">
                  {t('settings.codexProvider.dialog.ccSwitchProxy.requestHeaders', {
                    defaultValue: 'Proxy Headers JSON',
                  })}
                </label>
                <textarea
                  id="ccSwitchRequestHeaders"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.ccSwitchProxy.requestHeaders', {
                    defaultValue: 'Proxy Headers JSON',
                  })}
                  rows={4}
                  value={proxyHeadersJsonText}
                  onChange={(e) => setProxyHeadersJsonText(e.target.value)}
                />
              </div>
            )}

            {isCustomAdapterMode && (
              <div className="form-group" style={MODE_SECTION_STYLE}>
                <label htmlFor="customAdapterId">
                  {t('settings.codexProvider.dialog.customAdapter.adapterId', {
                    defaultValue: 'Adapter ID',
                  })}
                </label>
                <input
                  id="customAdapterId"
                  type="text"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.customAdapter.adapterId', {
                    defaultValue: 'Adapter ID',
                  })}
                  placeholder={t('settings.codexProvider.dialog.customAdapter.adapterIdPlaceholder', {
                    defaultValue: 'For example: minimax-adapter',
                  })}
                  value={customAdapter.adapterId || ''}
                  onChange={(e) => handleCustomAdapterFieldChange('adapterId', e.target.value)}
                />

                <label htmlFor="customAdapterEndpoint">
                  {t('settings.codexProvider.dialog.customAdapter.adapterEndpoint', {
                    defaultValue: 'Adapter Endpoint',
                  })}
                </label>
                <input
                  id="customAdapterEndpoint"
                  type="text"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.customAdapter.adapterEndpoint', {
                    defaultValue: 'Adapter Endpoint',
                  })}
                  placeholder={t('settings.codexProvider.dialog.customAdapter.adapterEndpointPlaceholder', {
                    defaultValue: 'For example: http://127.0.0.1:8080/adapter/codex',
                  })}
                  value={customAdapter.adapterEndpoint || ''}
                  onChange={(e) => handleCustomAdapterFieldChange('adapterEndpoint', e.target.value)}
                />

                <label htmlFor="customAdapterHeaders">
                  {t('settings.codexProvider.dialog.customAdapter.adapterHeaders', {
                    defaultValue: 'Adapter Headers JSON',
                  })}
                </label>
                <textarea
                  id="customAdapterHeaders"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.customAdapter.adapterHeaders', {
                    defaultValue: 'Adapter Headers JSON',
                  })}
                  rows={4}
                  value={adapterHeadersJsonText}
                  onChange={(e) => setAdapterHeadersJsonText(e.target.value)}
                />

                <label htmlFor="customAdapterExtras">
                  {t('settings.codexProvider.dialog.customAdapter.adapterExtras', {
                    defaultValue: 'Adapter Extras JSON',
                  })}
                </label>
                <textarea
                  id="customAdapterExtras"
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.customAdapter.adapterExtras', {
                    defaultValue: 'Adapter Extras JSON',
                  })}
                  rows={4}
                  value={adapterExtrasJsonText}
                  onChange={(e) => setAdapterExtrasJsonText(e.target.value)}
                />
              </div>
            )}

            <div className="form-group">
              <label htmlFor="apiKey">{t('settings.codexProvider.dialog.apiKey')}</label>
              <div style={INLINE_ACTION_STYLE}>
                <input
                  id="apiKey"
                  type={showApiKey ? 'text' : 'password'}
                  className="form-input"
                  aria-label={t('settings.codexProvider.dialog.apiKey')}
                  placeholder={t('settings.codexProvider.dialog.apiKeyPlaceholder')}
                  value={apiKey}
                  onChange={(e) => setApiKey(e.target.value)}
                />
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => setShowApiKey((value) => !value)}>
                  {showApiKey ? t('settings.provider.dialog.hideApiKey') : t('settings.provider.dialog.showApiKey')}
                </button>
              </div>
              {!showApiKey && maskedApiKey && (
                <small className="form-hint">{maskedApiKey}</small>
              )}
            </div>

            <div className="form-group">
              <label htmlFor="apiKeyEnv">{t('settings.codexProvider.dialog.apiKeyEnv')}</label>
              <input
                id="apiKeyEnv"
                type="text"
                className="form-input"
                aria-label={t('settings.codexProvider.dialog.apiKeyEnv')}
                placeholder={t('settings.codexProvider.dialog.apiKeyEnvPlaceholder')}
                value={apiKeyEnv}
                onChange={(e) => setApiKeyEnv(e.target.value)}
              />
              <small className="form-hint">{t('settings.codexProvider.dialog.apiKeyEnvHint')}</small>
            </div>

            <div className="form-group">
              <div style={MODEL_HEADER_STYLE}>
                <div style={INLINE_ACTION_STYLE}>
                  <label>{t('settings.codexProvider.dialog.modelList')}</label>
                  {onFetchModels && (
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={handleFetchModels}
                      disabled={
                        fetchingModels
                        || !draftRequestId
                        || !draftModelFetchSupported
                        || !draftModelFetchConfigured
                      }
                      title={fetchingModels
                        ? t('settings.codexProvider.fetchModelsLoading')
                        : !draftModelFetchSupported
                          ? t('settings.codexProvider.fetchModelsUnsupportedTooltip')
                          : !draftModelFetchConfigured
                            ? t('settings.codexProvider.dialog.fetchModelsMissingConfigTooltip', {
                              defaultValue: 'Configure Base URL and API Key before fetching models.',
                            })
                            : t('settings.codexProvider.fetchModels')}
                    >
                      <span className={fetchingModels
                        ? 'codicon codicon-loading codicon-modifier-spin'
                        : 'codicon codicon-refresh'} />
                      {fetchingModels
                        ? t('settings.codexProvider.fetchModelsLoading')
                        : t('settings.codexProvider.fetchModels')}
                    </button>
                  )}
                </div>
                <small className="form-hint">{t('settings.codexProvider.dialog.modelAliasHelp')}</small>
                {models.map((model, index) => (
                  <div key={`${index}-${model.id}`} style={MODEL_ROW_STYLE}>
                    <input
                      type="text"
                      className="form-input"
                      placeholder={t('settings.codexProvider.dialog.modelIdPlaceholder')}
                      value={model.id}
                      onChange={(e) => handleModelFieldChange(index, 'id', e.target.value)}
                    />
                    <input
                      type="text"
                      className="form-input"
                      placeholder={t('settings.codexProvider.dialog.modelLabelPlaceholder')}
                      value={model.label}
                      onChange={(e) => handleModelFieldChange(index, 'label', e.target.value)}
                    />
                    <input
                      type="text"
                      className="form-input"
                      placeholder={t('settings.codexProvider.dialog.modelDescriptionPlaceholder')}
                      value={model.description || ''}
                      onChange={(e) => handleModelFieldChange(index, 'description', e.target.value)}
                    />
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={() => setModels((prev) => prev.length > 1 ? prev.filter((_, currentIndex) => currentIndex !== index) : [createEmptyModelRow()])}
                    >
                      {t('common.delete')}
                    </button>
                  </div>
                ))}
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setModels((prev) => {
                    if (prev.some(isEmptyModelRow)) {
                      return prev;
                    }
                    return [...prev, createEmptyModelRow()];
                  })}
                >
                  {t('settings.codexProvider.dialog.addModelRow')}
                </button>
                <button
                  type="button"
                  className="btn btn-secondary btn-sm"
                  onClick={() => setShowAdvancedJsonEditor((value) => !value)}
                >
                  {t('settings.codexProvider.dialog.advancedJsonToggle', { defaultValue: 'Advanced JSON Editor' })}
                </button>
                {showAdvancedJsonEditor && (
                  <>
                    <small className="form-hint">
                      {t('settings.codexProvider.dialog.advancedJsonHelp', {
                        defaultValue: 'Paste a model JSON array in bulk and sync it back to the structured list.',
                      })}
                    </small>
                    <label htmlFor="modelsJson">
                      {t('settings.codexProvider.dialog.modelsJsonLabel', { defaultValue: 'Model JSON' })}
                    </label>
                    <textarea
                      id="modelsJson"
                      className="form-input"
                      aria-label={t('settings.codexProvider.dialog.modelsJsonLabel', { defaultValue: 'Model JSON' })}
                      rows={8}
                      value={modelsJsonText}
                      onChange={(e) => setModelsJsonText(e.target.value)}
                    />
                    <button
                      type="button"
                      className="btn btn-secondary btn-sm"
                      onClick={handleApplyModelsJson}
                    >
                      {t('settings.codexProvider.dialog.applyModelsJson', { defaultValue: 'Apply JSON' })}
                    </button>
                  </>
                )}
              </div>
            </div>

            <details className="advanced-section">
              <summary className="advanced-toggle">
                <span className="codicon codicon-chevron-right" />
                {t('settings.codexProvider.dialog.envVarsTitle')}
              </summary>

              <div className="form-group" style={{ marginTop: '16px' }}>
                <label>{t('settings.codexProvider.dialog.messageEnvLabel')}</label>
                <small className="form-hint">{t('settings.codexProvider.dialog.messageEnvHint')}</small>
                <EnvVarEditor
                  entries={messageEnvVars}
                  onChange={setMessageEnvVars}
                />
              </div>

              <div className="form-group">
                <label>{t('settings.codexProvider.dialog.mcpEnvLabel')}</label>
                <small className="form-hint">{t('settings.codexProvider.dialog.mcpEnvHint')}</small>
                <EnvVarEditor
                  entries={mcpEnvVars}
                  onChange={setMcpEnvVars}
                />
              </div>
            </details>
          </div>
        </div>

        <div className="dialog-footer">
          <div className="footer-actions" style={FOOTER_ACTIONS_STYLE}>
            <button className="btn btn-secondary" onClick={onClose}>
              <span className="codicon codicon-close" />
              {t('common.cancel')}
            </button>
            <button
              className="btn btn-secondary"
              onClick={handleSave}
              disabled={!providerName.trim() || requestModeUnavailable}
            >
              <span className="codicon codicon-save" />
              {isAdding ? t('settings.provider.dialog.confirmAdd') : t('settings.provider.dialog.saveChanges')}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
