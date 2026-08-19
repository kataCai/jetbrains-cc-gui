/**
 * Provider configuration type definitions
 */

// ============ Constants ============

/**
 * Special pseudo provider IDs (not stored in config.json providers list)
 * These represent special operational modes, not actual provider configurations.
 */
export const SPECIAL_PROVIDER_IDS = {
  /** Disabled state - no active provider */
  DISABLED: '__disabled__',
  /** Local ~/.claude/settings.json mode */
  LOCAL_SETTINGS: '__local_settings_json__',
  /** CLI login authentication mode */
  CLI_LOGIN: '__cli_login__',
  /** Codex CLI login authentication mode */
  CODEX_CLI_LOGIN: '__codex_cli_login__',
} as const;

/**
 * Check if a provider ID is a special pseudo provider
 * @param id - Provider ID to check
 * @returns Whether this is a special pseudo provider that cannot be updated via update_provider
 */
export function isSpecialProviderId(id: string): boolean {
  return (
    id === SPECIAL_PROVIDER_IDS.DISABLED ||
    id === SPECIAL_PROVIDER_IDS.LOCAL_SETTINGS ||
    id === SPECIAL_PROVIDER_IDS.CLI_LOGIN ||
    id === SPECIAL_PROVIDER_IDS.CODEX_CLI_LOGIN
  );
}

/**
 * localStorage keys for provider-related data
 */
export const STORAGE_KEYS = {
  /** Custom Codex model list */
  CODEX_CUSTOM_MODELS: 'codex-custom-models',
  /** Claude model mapping configuration */
  CLAUDE_MODEL_MAPPING: 'claude-model-mapping',
  /** Custom Claude model list */
  CLAUDE_CUSTOM_MODELS: 'claude-custom-models',
} as const;

/**
 * Claude provider env keys that affect runtime model resolution.
 */
export const CLAUDE_MODEL_MAPPING_ENV_KEYS = [
  'ANTHROPIC_MODEL',
  'ANTHROPIC_SMALL_FAST_MODEL',
  'ANTHROPIC_DEFAULT_HAIKU_MODEL', // legacy – kept for backward compat
  'ANTHROPIC_DEFAULT_SONNET_MODEL',
  'ANTHROPIC_DEFAULT_OPUS_MODEL',
] as const;

/**
 * Model ID validation regular expression
 * Allowed: letters, numbers, hyphens, underscores, dots, slashes, colons
 * Used to validate user-input model ID format
 */
export const MODEL_ID_PATTERN = /^[a-zA-Z0-9._\-/:]+$/;

// ============ Validation Helpers ============

/**
 * Validate whether a model ID format is valid.
 *
 * NOTE: Model ID format is intentionally NOT restricted by regex.
 * Third-party providers use diverse model ID formats that cannot be
 * predicted (e.g., slashes, brackets, CJK characters). Only basic
 * sanity checks (non-empty, length limit) are applied.
 * Do NOT re-add MODEL_ID_PATTERN validation here.
 *
 * @param id - Model ID
 * @returns Whether the ID is valid
 */
export function isValidModelId(id: string): boolean {
  if (!id || typeof id !== 'string') return false;
  const trimmed = id.trim();
  if (trimmed.length === 0 || trimmed.length > 256) return false;
  return true;
}

/**
 * Validate whether a CodexCustomModel object is valid
 * @param model - Object to validate
 * @returns Whether it is a valid CodexCustomModel
 */
export function isValidCodexCustomModel(model: unknown): model is CodexCustomModel {
  if (!model || typeof model !== 'object') return false;
  const obj = model as Record<string, unknown>;

  // id must be a valid model ID
  if (typeof obj.id !== 'string' || !isValidModelId(obj.id)) return false;

  // label must be a string
  if (typeof obj.label !== 'string' || obj.label.trim().length === 0) return false;

  // description is optional, but must be a string if present
  if (obj.description !== undefined && typeof obj.description !== 'string') return false;

  return true;
}

/**
 * Validate and filter a CodexCustomModel array
 * @param models - Array to validate
 * @returns Array of valid CodexCustomModel entries
 */
export function validateCodexCustomModels(models: unknown): CodexCustomModel[] {
  if (!Array.isArray(models)) return [];
  return models.filter(isValidCodexCustomModel);
}

// ============ Types ============

/**
 * Provider configuration (simplified, adapted for current project)
 */
export interface ProviderConfig {
  id: string;
  name: string;
  remark?: string;
  websiteUrl?: string;
  category?: ProviderCategory;
  createdAt?: number;
  isActive?: boolean;
  source?: 'cc-switch' | string;
  isLocalProvider?: boolean;
  isCliLoginProvider?: boolean;
  /** Custom model list (displayed before built-in models in the selector) */
  customModels?: CodexCustomModel[];
  settingsConfig?: {
    env?: {
      ANTHROPIC_AUTH_TOKEN?: string;
      ANTHROPIC_BASE_URL?: string;
      ANTHROPIC_MODEL?: string;
      ANTHROPIC_DEFAULT_SONNET_MODEL?: string;
      ANTHROPIC_DEFAULT_OPUS_MODEL?: string;
      ANTHROPIC_SMALL_FAST_MODEL?: string;
      [key: string]: any;
    };
    alwaysThinkingEnabled?: boolean;
    permissions?: {
      allow?: string[];
      deny?: string[];
    };
  };
}

/**
 * Provider category
 */
export type ProviderCategory =
  | 'official'      // Official
  | 'cn_official'   // Chinese official
  | 'aggregator'    // Aggregator service
  | 'third_party'   // Third-party
  | 'custom';       // Custom

/**
 * Codex custom model configuration
 */
export interface CodexCustomModel {
  /** Model ID (unique identifier) */
  id: string;
  /** Model display name */
  label: string;
  /** Model description */
  description?: string;
  /** Default reasoning effort for this model */
  reasoningEffort?: 'low' | 'medium' | 'high' | 'xhigh' | 'max' | string;
}

export type CodexAuthMode = 'api_key' | 'api_key_env' | 'codex_cli_login' | 'proxy' | 'oauth';

export type CodexRequestMode = 'codex_sdk' | 'cc_switch_proxy' | 'custom_adapter';

/**
 * CC Switch Proxy 模式专属配置。
 * 该结构用于描述“请求先打到本地/远端 cc-switch 代理，再由代理转发到真实 provider”的场景。
 * 当前阶段它主要承担 schema 边界声明和配置持久化职责，真正运行时发送器将在后续任务中消费这些字段。
 */
export interface CodexCcSwitchProxyConfig {
  /** cc-switch 代理入口地址 */
  proxyEndpoint?: string;
  /** 代理层内部的目标 provider 路由名 */
  providerRoute?: string;
  /** 可选的代理请求路径，用于兼容不同代理入口 */
  requestPath?: string;
  /** 需要透传给代理层的额外请求头 */
  requestHeaders?: Record<string, string>;
}

/**
 * Custom Adapter 模式专属配置。
 * 该结构用于描述“先进入自定义 adapter，再由 adapter 自行完成协议转换与上游调用”的场景。
 * 当前阶段先固化字段边界，后续运行时发送器再基于这些字段接入真实请求链路。
 */
export interface CodexCustomAdapterConfig {
  /** adapter 标识符，用于区分不同转换器实现 */
  adapterId?: string;
  /** adapter HTTP 入口地址 */
  adapterEndpoint?: string;
  /** 需要透传给 adapter 的额外请求头 */
  adapterHeaders?: Record<string, string>;
  /** 需要透传给 adapter 的额外 JSON 参数 */
  adapterExtras?: Record<string, unknown>;
}

/**
 * Codex provider 字段分组。
 * 该结构把“所有模式通用字段”和“某个 requestMode 专属字段”显式拆开，供后续 UI 动态渲染与校验规则复用。
 */
export interface CodexProviderModeFieldGroups {
  commonFields: string[];
  modeFields: string[];
}

const CODEX_PROVIDER_COMMON_FIELDS: string[] = [
  'id',
  'name',
  'providerType',
  'presetId',
  'remark',
  'websiteUrl',
  'apiKeyApplyUrl',
  'createdAt',
  'isActive',
  'authMode',
  'requestMode',
  'apiKey',
  'apiKeyEnv',
  'apiKeyMasked',
  'configToml',
  'authJson',
  'customModels',
];

const CODEX_PROVIDER_MODE_FIELDS: Record<CodexRequestMode, string[]> = {
  codex_sdk: ['baseUrl', 'models'],
  cc_switch_proxy: [
    'models',
    'ccSwitchProxy.proxyEndpoint',
    'ccSwitchProxy.providerRoute',
    'ccSwitchProxy.requestPath',
    'ccSwitchProxy.requestHeaders',
  ],
  custom_adapter: [
    'models',
    'customAdapter.adapterId',
    'customAdapter.adapterEndpoint',
    'customAdapter.adapterHeaders',
    'customAdapter.adapterExtras',
  ],
};

const CODEX_PROVIDER_MODEL_FETCH_SUPPORTED_REQUEST_MODES: ReadonlySet<string> = new Set(['codex_sdk']);
const CODEX_PROVIDER_MODEL_FETCH_SUPPORTED_AUTH_MODES: ReadonlySet<string> = new Set(['api_key', 'api_key_env']);

/**
 * 返回指定请求模式的字段分组。
 * 该方法只负责声明 schema 边界，不参与运行时分发；后续 Task 8 会复用它驱动表单动态渲染与校验。
 *
 * @param requestMode 当前 provider 的请求模式
 * @return 通用字段与模式专属字段列表
 */
export function getCodexProviderModeFieldGroups(requestMode: CodexRequestMode): CodexProviderModeFieldGroups {
  return {
    commonFields: [...CODEX_PROVIDER_COMMON_FIELDS],
    modeFields: [...CODEX_PROVIDER_MODE_FIELDS[requestMode]],
  };
}

/**
 * 判断当前请求模式是否已经在现有运行时链路中真正落地。
 * 现阶段只有 `codex_sdk` 已具备完整发送链路；
 * `cc_switch_proxy` 与 `custom_adapter` 仍处于 schema / UI 预留阶段，
 * 因此只能作为历史兼容值保留，不能继续作为可运行能力误导用户。
 *
 * @param requestMode 当前 provider 选择的请求模式
 * @return `true` 表示当前版本已实现；`false` 表示仍为预留模式
 */
export function isCodexRequestModeImplemented(requestMode?: CodexRequestMode | string): boolean {
  return !requestMode || requestMode === 'codex_sdk';
}

/**
 * 判断当前 Codex provider 是否满足“拉取模型列表”入口的前端可用条件。
 * 该判断需要和后端 `CodexRuntimeProfileResolver` 的默认值保持一致：
 * 1. `requestMode` 为空时按 `codex_sdk` 处理；
 * 2. `authMode` 为空时按 `api_key_env` 处理；
 * 3. 当前仅 `codex_sdk + api_key/api_key_env` 这一组合被视为稳定支持。
 * 这样可以避免前端把实际可拉取的 provider 误禁用，也避免把暂不支持的模式提前放行到后端报错。
 *
 * @param provider 待判断的 Codex provider，可为只包含 auth/request mode 的轻量对象
 * @return `true` 表示前端可以展示可点击的模型拉取按钮；`false` 表示应禁用并提示当前模式暂不支持
 */
export function isCodexProviderModelFetchSupported(
  provider?: Pick<CodexProviderConfig, 'authMode' | 'requestMode'> | null
): boolean {
  const normalizedRequestMode = typeof provider?.requestMode === 'string' && provider.requestMode.trim()
    ? provider.requestMode.trim()
    : 'codex_sdk';
  const normalizedAuthMode = typeof provider?.authMode === 'string' && provider.authMode.trim()
    ? provider.authMode.trim()
    : 'api_key_env';
  return CODEX_PROVIDER_MODEL_FETCH_SUPPORTED_REQUEST_MODES.has(normalizedRequestMode)
    && CODEX_PROVIDER_MODEL_FETCH_SUPPORTED_AUTH_MODES.has(normalizedAuthMode);
}

/**
 * 判断当前 Codex provider 是否已经填写模型发现所需的 Base URL 和凭据。
 * 该判断只检查配置完整性，不检查 requestMode/authMode 是否已实现；
 * 调用方应先用 `isCodexProviderModelFetchSupported` 过滤不支持的模式。
 *
 * @param provider 待判断的 Codex provider，可为只包含连接字段的轻量对象
 * @return `true` 表示 Base URL 和 API Key/API Key Env 都已填写
 */
export function hasCodexProviderModelDiscoveryConfig(
  provider?: Pick<CodexProviderConfig, 'baseUrl' | 'apiKey' | 'apiKeyEnv'> | null
): boolean {
  const baseUrl = typeof provider?.baseUrl === 'string' ? provider.baseUrl.trim() : '';
  const apiKey = typeof provider?.apiKey === 'string' ? provider.apiKey.trim() : '';
  const apiKeyEnv = typeof provider?.apiKeyEnv === 'string' ? provider.apiKeyEnv.trim() : '';
  return Boolean(baseUrl && (apiKey || apiKeyEnv));
}

/**
 * 判断当前 Codex provider 是否已经配置了至少一个本地模型。
 * 该判断同时兼容 `models` 与历史 `customModels` 字段，供测试按钮文案和卡片摘要复用。
 *
 * @param provider 待判断的 Codex provider
 * @return `true` 表示本地已有模型；`false` 表示应提示未配置模型
 */
export function hasCodexProviderConfiguredModels(
  provider?: Pick<CodexProviderConfig, 'models' | 'customModels'> | null
): boolean {
  return (provider?.models?.length || 0) > 0 || (provider?.customModels?.length || 0) > 0;
}

/**
 * 统计当前 Codex provider 本地已配置的模型数量。
 * 优先使用 `models`，没有时再回退 `customModels`，避免同一卡片把两套字段相加后重复计数。
 *
 * @param provider 待统计的 Codex provider
 * @return 本地模型数量；没有配置时返回 0
 */
export function getCodexProviderConfiguredModelCount(
  provider?: Pick<CodexProviderConfig, 'models' | 'customModels'> | null
): number {
  if (provider?.models && provider.models.length > 0) {
    return provider.models.length;
  }
  return provider?.customModels?.length || 0;
}

/**
 * 运行时来源标签的稳定枚举。
 * 该值只服务于前端展示层，用于把后端诊断字段折叠成用户能快速理解的四类来源状态。
 */
export type CodexRuntimeSource =
  | 'managedProvider'
  | 'codexLocalConfig'
  | 'sdkDefault'
  | 'proxyFallback';

/**
 * 运行时来源诊断输入。
 * 该结构被 `CodexProviderConfig` 与 `CodexProviderTestResult` 共用，
 * 这样设置页、聊天区和测试结果弹窗可以复用同一套映射逻辑。
 */
export interface CodexRuntimeSourceDiagnosticInput {
  effectiveConfigSource?: string;
  endpointSource?: string;
  fallbackDetected?: boolean;
  forcedModelProvider?: string;
  localCodexModelProvider?: string;
  localConfigConflictDetected?: boolean;
  finalModelProvider?: string;
}

/**
 * 判断当前对象是否已经携带运行时来源诊断字段。
 * 这里不能只看 `fallbackDetected`，因为 `false` 也是一个有意义的显式诊断结果。
 *
 * @param input 候选诊断对象
 * @return `true` 表示可以安全渲染 runtime source；`false` 表示当前对象还没有来源诊断摘要
 */
export function hasCodexRuntimeSourceDiagnostics(input?: CodexRuntimeSourceDiagnosticInput | null): boolean {
  if (!input) {
    return false;
  }
  return typeof input.fallbackDetected === 'boolean'
    || typeof input.effectiveConfigSource === 'string' && input.effectiveConfigSource.trim().length > 0
    || typeof input.endpointSource === 'string' && input.endpointSource.trim().length > 0;
}

/**
 * 将底层诊断字段折叠成稳定的前端 runtime source 分类。
 * 规则优先级说明：
 * 1. 只要后端显式标记发生 fallback，就优先展示为 `proxyFallback`；
 * 2. CLI Login / 本地 Codex 配置读取路径展示为 `codexLocalConfig`；
 * 3. 没有 provider endpoint、直接落到 SDK 默认值时展示为 `sdkDefault`；
 * 4. 其余情况统一视为命中托管 provider。
 *
 * @param input 后端返回的运行时诊断摘要
 * @return 适合前端展示的来源分类
 */
export function resolveCodexRuntimeSource(input?: CodexRuntimeSourceDiagnosticInput | null): CodexRuntimeSource {
  if (input?.fallbackDetected) {
    return 'proxyFallback';
  }
  if (input?.effectiveConfigSource === 'codex_cli_login'
    || input?.endpointSource === 'codex_cli_login'
    || input?.endpointSource === 'codex_local_config') {
    return 'codexLocalConfig';
  }
  if (input?.endpointSource === 'sdk_default') {
    return 'sdkDefault';
  }
  return 'managedProvider';
}

/**
 * 将运行时来源枚举转换为 i18n key 后缀，供不同界面复用。
 *
 * @param source 已归一化的运行时来源分类
 * @return 对应翻译 key 的尾部标识
 */
export function getCodexRuntimeSourceTranslationKey(source: CodexRuntimeSource): string {
  return source;
}

export interface CodexSelectedModel {
  providerId: string;
  modelId: string;
}

/**
 * Codex 模型目录项。
 * 该结构用于把 provider 维度下的可发现模型拍平成前端稳定列表，
 * 便于后续 settings UI、聊天模型下拉和桥接事件共享同一套展示数据。
 */
export interface CodexModelCatalogItem {
  /** 复合主键，固定格式为 providerId::modelId */
  key: string;
  /** 模型所属 provider id */
  providerId: string;
  /** 模型所属 provider 展示名 */
  providerName: string;
  /** 模型 id */
  modelId: string;
  /** 模型展示名 */
  label: string;
  /** 模型说明，可选 */
  description?: string;
  /** 目录项来源，用于后续区分 CLI 默认模型、托管 provider 模型和本地配置兜底模型 */
  source: 'codex_cli_login' | 'managed_provider' | 'plugin_custom' | 'local_config';
  /** 默认推理强度，可用于 settings 与聊天区共享展示 */
  reasoningEffort?: 'low' | 'medium' | 'high' | 'xhigh' | 'max' | string;
  /** 当前模型是否对用户可见 */
  visible: boolean;
  /** 当前模型是否可直接运行；未授权或缺少运行条件时可先展示但禁用 */
  runnable: boolean;
}

/**
 * Codex 模型显示配置。
 * 外层 key 使用复合主键，值用于声明该模型在前端展示层是否可见。
 */
export type CodexModelVisibilityConfig = Record<string, { visible: boolean }>;

/**
 * Codex 模型目录复合 key 分隔符。
 * 前后端必须共享同一个字面量，避免展示配置和目录项关联失败。
 */
export const CODEX_MODEL_CATALOG_KEY_DELIMITER = '::';

/**
 * 构造 provider 维度的模型复合 key。
 * 该 key 会作为 modelDisplay 配置的主键，因此这里统一裁剪空白并要求两段都非空。
 *
 * @param providerId provider 标识
 * @param modelId 模型标识
 * @returns 固定格式的复合 key
 */
export function buildCodexModelCatalogKey(providerId: string, modelId: string): string {
  const normalizedProviderId = providerId.trim();
  const normalizedModelId = modelId.trim();
  if (!normalizedProviderId || !normalizedModelId) {
    throw new Error('Codex model catalog key requires non-empty providerId and modelId');
  }
  return `${normalizedProviderId}${CODEX_MODEL_CATALOG_KEY_DELIMITER}${normalizedModelId}`;
}

/**
 * 解析 provider 维度的模型复合 key。
 * 解析时只按第一个 `::` 拆分，保证模型 id 内继续包含冒号时仍可正常恢复。
 *
 * @param compositeKey 复合 key
 * @returns 成功时返回 providerId/modelId，失败时返回 null
 */
export function parseCodexModelCatalogKey(compositeKey: string): CodexSelectedModel | null {
  if (!compositeKey || typeof compositeKey !== 'string') {
    return null;
  }
  const delimiterIndex = compositeKey.indexOf(CODEX_MODEL_CATALOG_KEY_DELIMITER);
  if (delimiterIndex <= 0) {
    return null;
  }
  const providerId = compositeKey.slice(0, delimiterIndex).trim();
  const modelId = compositeKey.slice(delimiterIndex + CODEX_MODEL_CATALOG_KEY_DELIMITER.length).trim();
  if (!providerId || !modelId) {
    return null;
  }
  return { providerId, modelId };
}

/**
 * 生成聊天区使用的 Codex 选中 key。
 * 当前端同时持有 providerId 与 modelId 时，必须使用复合 key 唯一标识 catalog 选中项；
 * 若当前仅知道 raw modelId，则回退为 modelId 本身，以兼容 runtime fallback 场景。
 *
 * @param providerId 当前选中模型所属 providerId
 * @param modelId 当前选中模型的 raw modelId
 * @returns 优先返回 providerId::modelId；缺少 providerId 时回退为裁剪后的 modelId；都为空时返回空串
 */
export function buildCodexSelectedModelKey(
  providerId: string | null | undefined,
  modelId: string | null | undefined,
): string {
  const normalizedProviderId = typeof providerId === 'string' ? providerId.trim() : '';
  const normalizedModelId = typeof modelId === 'string' ? modelId.trim() : '';
  if (!normalizedModelId) {
    return '';
  }
  if (!normalizedProviderId) {
    return normalizedModelId;
  }
  return buildCodexModelCatalogKey(normalizedProviderId, normalizedModelId);
}

/**
 * Codex 子进程环境变量条目。
 * 设置页使用该结构保存用户显式声明的附加环境变量，后端会按用途分别注入消息发送进程和 MCP 工具发现进程。
 */
export interface EnvVarEntry {
  /** Environment variable name */
  key: string;
  /** Environment variable value */
  value: string;
}

/**
 * Codex 内置环境变量保护名单。
 * 用户自定义变量不能覆盖这些关键变量，避免破坏 SDK 启动、权限隔离、会话绑定或系统路径解析。
 */
export const CODEX_PROTECTED_ENV_KEYS: ReadonlySet<string> = new Set([
  'CODEX_USE_STDIN',
  'CODEX_MODEL',
  'CODEX_SANDBOX_MODE',
  'CODEX_SANDBOX',
  'CODEX_APPROVAL_POLICY',
  'CODEX_CI',
  'CODEX_SANDBOX_NETWORK_DISABLED',
  'CODEX_HOME',
  'CLAUDE_SESSION_ID',
  'CLAUDE_PERMISSION_DIR',
  'HOME',
  'PATH',
  'TMPDIR',
  'TEMP',
  'TMP',
  'IDEA_PROJECT_PATH',
  'PROJECT_PATH',
  'CLAUDE_USE_STDIN',
]);

/**
 * 环境变量值长度上限。
 * 该值需要和后端 `CodexSDKBridge.java` 中的 MAX_ENV_VAR_VALUE_LENGTH 保持一致，
 * 避免子进程启动时触发操作系统环境块长度限制。
 */
export const ENV_VAR_VALUE_MAX_LENGTH = 16 * 1024;

/**
 * 校验环境变量名是否符合跨平台安全子集。
 *
 * @param key 候选环境变量名
 * @return `true` 表示变量名可以保存；`false` 表示格式非法
 */
export function isValidEnvVarKey(key: string): boolean {
  if (!key || typeof key !== 'string') return false;
  return /^[a-zA-Z_][a-zA-Z0-9_]*$/.test(key);
}

/**
 * 判断环境变量名是否命中 Codex 保护名单。
 * 为了兼容 Windows 环境变量大小写不敏感的行为，这里统一按大写比较，
 * 也会拒绝大小写变体形式的内置变量。
 *
 * @param key 候选环境变量名
 * @return `true` 表示该变量不允许由用户覆盖
 */
export function isProtectedEnvVarKey(key: string): boolean {
  return CODEX_PROTECTED_ENV_KEYS.has(key.toUpperCase());
}

export interface EnvVarValidationIssue {
  index: number;
  field: 'key' | 'value';
  reason: 'invalid' | 'protected' | 'duplicate' | 'value_too_long';
  key?: string;
}

/**
 * 校验一组环境变量条目。
 * 空 key 会在保存前被过滤，因此这里跳过空 key；非空 key 需要满足格式、保护名单和重复性约束。
 *
 * @param entries 当前编辑器中的环境变量条目
 * @return 每个问题条目的第一条校验错误，返回空数组表示全部合法
 */
export function validateEnvVarEntries(entries: EnvVarEntry[]): EnvVarValidationIssue[] {
  const issues: EnvVarValidationIssue[] = [];
  const seenKeys = new Set<string>();

  entries.forEach((entry, index) => {
    if (entry.value.length > ENV_VAR_VALUE_MAX_LENGTH) {
      issues.push({ index, field: 'value', reason: 'value_too_long' });
    }

    const key = entry.key.trim();
    if (!key) return;

    if (!isValidEnvVarKey(key)) {
      issues.push({ index, field: 'key', reason: 'invalid', key });
      return;
    }

    if (isProtectedEnvVarKey(key)) {
      issues.push({ index, field: 'key', reason: 'protected', key });
      return;
    }

    const upperKey = key.toUpperCase();
    if (seenKeys.has(upperKey)) {
      issues.push({ index, field: 'key', reason: 'duplicate', key });
      return;
    }
    seenKeys.add(upperKey);
  });

  return issues;
}

/**
 * Codex provider configuration
 */
export interface CodexProviderConfig {
  /** Unique provider ID */
  id: string;
  /** Provider name */
  name: string;
  /** Provider type used to distinguish preset source or custom gateway type */
  providerType?: string;
  /** Preset identifier selected in the structured creation form */
  presetId?: string;
  /** Remark */
  remark?: string;
  /** Provider official website */
  websiteUrl?: string;
  /** API key application page */
  apiKeyApplyUrl?: string;
  /** Creation timestamp (milliseconds) */
  createdAt?: number;
  /** Whether this is the currently active provider */
  isActive?: boolean;
  /** Authentication mode used by request-level runtime resolution */
  authMode?: CodexAuthMode;
  /** Request path used by the runtime bridge */
  requestMode?: CodexRequestMode;
  /** Request-level endpoint for managed providers */
  baseUrl?: string;
  /** Local API key value, shown only as masked text in UI */
  apiKey?: string;
  /** Environment variable name used to resolve API key at send time */
  apiKeyEnv?: string;
  /** Runtime model list owned by this provider */
  models?: CodexCustomModel[];
  /** cc-switch 代理模式专属配置 */
  ccSwitchProxy?: CodexCcSwitchProxyConfig;
  /** 自定义 adapter 模式专属配置 */
  customAdapter?: CodexCustomAdapterConfig;
  /** Optional masked API key preview used by UI */
  apiKeyMasked?: string;
  /** config.toml content (raw string) */
  configToml?: string;
  /** auth.json content (raw string) */
  authJson?: string;
  /** Custom model list */
  customModels?: CodexCustomModel[];
  /** Environment variables for sendMessage subprocess */
  messageEnvVars?: EnvVarEntry[];
  /** Environment variables for getMcpServerTools subprocess */
  mcpEnvVars?: EnvVarEntry[];
  /** 当前运行时实际生效的配置来源，用于界面提示是否命中托管 provider 或本地配置 */
  effectiveConfigSource?: string;
  /** 当前运行时 endpoint 的来源，用于区分 provider、本地配置或 SDK 默认值 */
  endpointSource?: string;
  /** 是否在运行时解析阶段发生了 fallback，用于显式暴露“看起来没走托管配置”的情况 */
  fallbackDetected?: boolean;
  /** 当前请求级强制注入的 model_provider 诊断值 */
  forcedModelProvider?: string;
  /** 本地 ~/.codex/config.toml 中声明的 model_provider */
  localCodexModelProvider?: string;
  /** 当前托管 provider 是否与本地 CLI 默认 provider 存在冲突风险 */
  localConfigConflictDetected?: boolean;
  /** 诊断语义下最终应生效的 model_provider */
  finalModelProvider?: string;
}

/**
 * 编辑态 provider 草稿模型发现结果。
 * 后端只返回远端模型 ID 与发现统计，前端弹窗负责基于当前未保存草稿
 * 追加缺失模型，避免把用户正在编辑的 label、description 或其它字段覆盖掉。
 */
export interface CodexProviderDraftModelsFetchResult {
  /** 当前编辑弹窗对应的草稿关联 id，仅用于异步结果关联，不要求等于持久化 provider id */
  providerId: string;
  /** 远端发现并去重后的模型 ID 列表 */
  modelIds: string[];
  /** 远端响应中重复模型项数量 */
  duplicateCount: number;
  /** 远端响应中因结构无效而跳过的项数量 */
  skippedCount: number;
}

/**
 * Codex provider 连通性测试结果。
 * 该结构用于把后端真实运行时解析结果返回给设置页，便于用户确认实际命中的 provider、endpoint 与凭据来源。
 */
export type CodexProviderTestStage = 'model_discovery' | 'sdk_message' | 'runtime_profile';

export interface CodexProviderTestResult {
  success: boolean;
  providerId: string;
  requestMode: string;
  model: string;
  resolvedBaseUrl: string;
  credentialSource: string;
  transport: string;
  effectiveConfigSource: string;
  fallbackDetected: boolean;
  authMode?: string;
  endpointSource?: string;
  forcedModelProvider?: string;
  localCodexModelProvider?: string;
  localConfigConflictDetected?: boolean;
  finalModelProvider?: string;
  testStage?: CodexProviderTestStage | string;
  failureStage?: string;
  requiresModel?: boolean;
  canFetchModels?: boolean;
  message: string;
}

// ============ Provider Presets ============

/**
 * Provider preset configuration
 */
export interface ProviderPreset {
  /** Unique preset ID */
  id: string;
  /** i18n key for preset name, resolved at render time */
  nameKey: string;
  /** Environment variable configuration */
  env: Record<string, string>;
}

/**
 * Provider preset configuration list
 * Used for quick provider setup
 *
 * nameKey is resolved at render time via t() to the display name for the current language
 */
export const PROVIDER_PRESETS: ProviderPreset[] = [
  {
    id: 'custom',
    nameKey: 'settings.provider.presets.custom',
    env: {},
  },
  {
    id: 'zhipu',
    nameKey: 'settings.provider.presets.zhipu',
    env: {
      ANTHROPIC_BASE_URL: 'https://open.bigmodel.cn/api/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_SMALL_FAST_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'glm-4.7',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'glm-4.7',
    },
  },
  {
    id: 'kimi',
    nameKey: 'settings.provider.presets.kimi',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.moonshot.cn/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_SMALL_FAST_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'kimi-k2.5',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'kimi-k2.5',
    },
  },
  {
    id: 'deepseek',
    nameKey: 'settings.provider.presets.deepseek',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.deepseek.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_SMALL_FAST_MODEL: 'deepseek-v4-flash',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'deepseek-v4-flash',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'deepseek-v4-pro[1m]',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'deepseek-v4-pro[1m]',
      CLAUDE_CODE_EFFORT_LEVEL: 'max',
    },
  },
  {
    id: 'minimax',
    nameKey: 'settings.provider.presets.minimax',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.minimaxi.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      // MiniMax models respond slowly; requires 50-minute timeout (3,000,000ms) to avoid truncating long reasoning requests
      API_TIMEOUT_MS: '3000000',
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC: '1',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'MiniMax-M2.1',
      ANTHROPIC_SMALL_FAST_MODEL: 'MiniMax-M2.1',
    },
  },
  {
    id: 'xiaomi',
    nameKey: 'settings.provider.presets.xiaomi',
    env: {
      ANTHROPIC_BASE_URL: 'https://api.xiaomimimo.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_SMALL_FAST_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
    },
  },
  {
    id: 'xiaomi-plan',
    nameKey: 'settings.provider.presets.xiaomiPlan',
    env: {
      ANTHROPIC_BASE_URL: 'https://token-plan-cn.xiaomimimo.com/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_SMALL_FAST_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_HAIKU_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'mimo-v2.5-pro',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'mimo-v2.5-pro',
    },
  },
  {
    id: 'qwen',
    nameKey: 'settings.provider.presets.qwen',
    env: {
      ANTHROPIC_BASE_URL: 'https://dashscope.aliyuncs.com/apps/anthropic',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_SMALL_FAST_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'qwen3-max',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'qwen3-max',
    },
  },
  {
    id: 'openrouter',
    nameKey: 'settings.provider.presets.openrouter',
    env: {
      ANTHROPIC_BASE_URL: 'https://openrouter.ai/api',
      ANTHROPIC_AUTH_TOKEN: '',
      ANTHROPIC_SMALL_FAST_MODEL: 'anthropic/claude-haiku-4.5',
      ANTHROPIC_DEFAULT_SONNET_MODEL: 'anthropic/claude-sonnet-4.5',
      ANTHROPIC_DEFAULT_OPUS_MODEL: 'anthropic/claude-opus-4.5',
    },
  },
];
