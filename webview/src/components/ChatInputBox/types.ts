/**
 * Input box component type definitions
 * Feature: 004-refactor-input-box
 */

// ============================================================
// Core Entity Types
// ============================================================

/**
 * File tag information for backend context injection (Codex mode)
 */
export interface FileTagInfo {
  /** Display path (as shown in tag) */
  displayPath: string;
  /** Absolute path (for file reading) */
  absolutePath: string;
}

/**
 * File attachment
 */
export interface Attachment {
  /** Unique identifier */
  id: string;
  /** Original filename */
  fileName: string;
  /** MIME type */
  mediaType: string;
  /** Base64 encoded content */
  data: string;
}

/**
 * Code snippet (from editor selection)
 */
export interface CodeSnippet {
  /** Unique identifier */
  id: string;
  /** File path (relative) */
  filePath: string;
  /** Start line number */
  startLine?: number;
  /** End line number */
  endLine?: number;
}

/**
 * Image media type constants
 */
export const IMAGE_MEDIA_TYPES = [
  'image/jpeg',
  'image/png',
  'image/gif',
  'image/webp',
  'image/svg+xml',
] as const;

export type ImageMediaType = (typeof IMAGE_MEDIA_TYPES)[number];

/**
 * Check if attachment is an image
 */
export function isImageAttachment(attachment: Attachment): boolean {
  return IMAGE_MEDIA_TYPES.includes(attachment.mediaType as ImageMediaType);
}

// ============================================================
// Completion System Types
// ============================================================

/**
 * Completion item type
 */
export type CompletionType =
  | 'file'
  | 'directory'
  | 'command'
  | 'agent'
  | 'prompt'
  | 'terminal'
  | 'service'
  | 'info'
  | 'separator'
  | 'section-header';

/**
 * Dropdown menu item data
 */
export interface DropdownItemData {
  /** Unique identifier */
  id: string;
  /** Display text */
  label: string;
  /** Description text */
  description?: string;
  /** Icon class name */
  icon?: string;
  /** Item type */
  type: CompletionType;
  /** Whether selected (for selectors) */
  checked?: boolean;
  /** Associated data */
  data?: Record<string, unknown>;
}

/**
 * File item (returned from Java)
 */
export interface FileItem {
  /** Filename */
  name: string;
  /** Relative path */
  path: string;
  /** Absolute path (optional) */
  absolutePath?: string;
  /** Type */
  type: 'file' | 'directory' | 'terminal' | 'service';
  /** Extension */
  extension?: string;
}

/**
 * Command item (returned from Java)
 */
export interface CommandItem {
  /** Command identifier */
  id: string;
  /** Display name */
  label: string;
  /** Description */
  description?: string;
  /** Category */
  category?: string;
}

/**
 * Dropdown menu position
 */
export interface DropdownPosition {
  /** Top coordinate (px) */
  top: number;
  /** Left coordinate (px) */
  left: number;
  /** Width (px) */
  width: number;
  /** Height (px) */
  height: number;
}

/**
 * Trigger query information
 */
export interface TriggerQuery {
  /** Trigger symbol ('@' or '/' or '#' or '!') */
  trigger: string;
  /** Search keyword */
  query: string;
  /** Character offset position of trigger symbol */
  start: number;
  /** Character offset position of query end */
  end: number;
}

/**
 * Selected agent information
 */
export interface SelectedAgent {
  id: string;
  name: string;
  prompt?: string;
}

// ============================================================
// Mode and Model Types
// ============================================================

/**
 * Permission mode for conversations
 */
export type PermissionMode = 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions';

/**
 * Mode information
 */
export interface ModeInfo {
  id: PermissionMode;
  label: string;
  icon: string;
  disabled?: boolean;
  tooltip?: string;
  description?: string;
}

/**
 * Available permission modes
 */
export const AVAILABLE_MODES: ModeInfo[] = [
  {
    id: 'default',
    label: 'Default Mode',
    icon: 'codicon-comment-discussion',
    tooltip: 'Standard permission behavior',
    description: 'Requires manual confirmation for each operation',
  },
  {
    id: 'plan',
    label: 'Plan Mode',
    icon: 'codicon-tasklist',
    tooltip: 'Plan mode - read-only analysis',
    description: 'Read-only tools only, generates plan for user approval',
  },
  {
    id: 'acceptEdits',
    label: 'Agent Mode',
    icon: 'codicon-robot',
    tooltip: 'Auto-accept file edits',
    description: 'Auto-accept file creation/editing, fewer confirmations',
  },
  {
    id: 'bypassPermissions',
    label: 'Auto Mode',
    icon: 'codicon-zap',
    tooltip: 'Bypass all permission checks',
    description: 'Fully automated, bypasses all permission checks [use with caution]',
  },
];

/**
 * Set of valid permission mode IDs, derived from AVAILABLE_MODES.
 * Use isValidPermissionMode() for validation instead of inline checks.
 */
export const VALID_PERMISSION_MODE_IDS: ReadonlySet<string> = new Set(
  AVAILABLE_MODES.map((m) => m.id),
);

/**
 * Check whether a string is a recognized PermissionMode.
 */
export function isValidPermissionMode(mode: string | undefined | null): mode is PermissionMode {
  return typeof mode === 'string' && VALID_PERMISSION_MODE_IDS.has(mode);
}

/**
 * Model information
 */
export interface ModelInfo {
  id: string;
  label: string;
  description?: string;
}

/**
 * 判断 Claude 模型是否支持 1M context。
 * 当前规则沿用 upstream 约定：除 Haiku 外的 Claude 主模型默认支持。
 *
 * @param modelId 当前模型 ID，允许为空
 * @return 支持时返回 true，否则返回 false
 */
export function modelSupports1MContext(modelId: string | undefined | null): boolean {
  if (!modelId) {
    return false;
  }
  return !modelId.replace(/\[1m\]$/i, '').toLowerCase().includes('haiku');
}

/**
 * 判断模型 ID 是否已携带 `[1m]` 后缀。
 *
 * @param modelId 当前模型 ID
 * @return 已携带后缀时返回 true
 */
export function has1MContextSuffix(modelId: string | undefined | null): boolean {
  if (!modelId) {
    return false;
  }
  return /\[1m\]$/i.test(modelId);
}

/**
 * 根据长上下文开关为模型 ID 应用 `[1m]` 后缀。
 * 如果模型本身不支持 1M context，或者开关关闭，则会返回去除后缀后的基础 ID。
 *
 * @param modelId 原始模型 ID
 * @param enabled 是否启用 1M context
 * @return 处理后的模型 ID
 */
export function apply1MContextSuffix(modelId: string, enabled: boolean): string {
  if (!enabled || !modelSupports1MContext(modelId)) {
    return modelId.replace(/\[1m\]$/i, '');
  }
  const baseId = modelId.replace(/\[1m\]$/i, '');
  return `${baseId}[1m]`;
}

/**
 * 去除模型 ID 上的 `[1m]` 后缀，供展示和持久化使用。
 *
 * @param modelId 原始模型 ID
 * @return 去除后缀后的基础模型 ID
 */
export function strip1MContextSuffix(modelId: string | undefined | null): string {
  if (!modelId) {
    return '';
  }
  return modelId.replace(/\[1m\]$/i, '');
}

const LEGACY_CLAUDE_MODEL_ID_ALIASES: Record<string, string> = {
  'claude-opus-4-6[1m]': 'claude-opus-4-6',
};

/**
 * 规范化 Claude 模型 ID。
 * 这里既会移除 `[1m]` 后缀，也会兼容旧版模型别名，避免历史会话和新 UI 列表不一致。
 *
 * @param modelId 原始模型 ID
 * @return 规范化后的 Claude 模型 ID；为空时回退到默认 Sonnet 4.6
 */
export function normalizeClaudeModelId(modelId: string | undefined | null): string {
  if (!modelId) {
    return 'claude-sonnet-4-6';
  }
  const stripped = strip1MContextSuffix(modelId);
  return LEGACY_CLAUDE_MODEL_ID_ALIASES[stripped] ?? stripped;
}

/**
 * 构造运行时兜底模型项。
 * 当后端或本地配置返回的模型未包含在内置列表时，前端仍需保留并展示当前值，
 * 避免启动后被旧列表静默覆盖。
 *
 * @param modelId 运行时返回的模型 ID
 * @return 可直接用于选择器展示的模型项；空字符串返回 null
 */
export function createRuntimeModelInfo(modelId: string | null | undefined): ModelInfo | null {
  const normalizedModelId = typeof modelId === 'string' ? modelId.trim() : '';
  if (!normalizedModelId) {
    return null;
  }
  return {
    id: normalizedModelId,
    label: normalizedModelId,
  };
}

/**
 * Claude 模型列表。
 * 列表只保留基础 ID，不直接带 `[1m]` 后缀；长上下文由运行时开关动态拼接。
 */
export const CLAUDE_MODELS: ModelInfo[] = [
  {
    id: 'claude-sonnet-4-6',
    label: 'Sonnet 4.6',
    description: 'Sonnet 4.6 路 Use the default model',
  },
  {
    id: 'claude-opus-4-7',
    label: 'Opus 4.7',
    description: 'Opus 4.7 路 Latest and most capable',
  },
  {
    id: 'claude-opus-4-6',
    label: 'Opus 4.6',
    description: 'Opus 4.6 for long sessions',
  },
  {
    id: 'claude-haiku-4-5',
    label: 'Haiku 4.5',
    description: 'Haiku 4.5 路 Fastest for quick answers',
  },
];

/**
 * Codex 模型列表。
 * 这里保留当前主线已验证过的文案风格，同时补齐 upstream 新增模型，避免并轨后遗漏能力入口。
 */
export const CODEX_MODELS: ModelInfo[] = [
  {
    id: 'gpt-5.5',
    label: 'gpt-5.5',
    description: 'Frontier model for complex coding, research, and real-world work.',
  },
  {
    id: 'gpt-5.4',
    label: 'gpt-5.4',
    description: 'Strong model for everyday coding.',
  },
  {
    id: 'gpt-5.2-codex',
    label: 'gpt-5.2-codex',
    description: 'Frontier agentic coding model.',
  },
  {
    id: 'gpt-5.1-codex-max',
    label: 'gpt-5.1-codex-max',
    description: 'Codex-optimized flagship for deep and fast reasoning.',
  },
  {
    id: 'gpt-5.4-mini',
    label: 'gpt-5.4-mini',
    description: 'Small, fast, and cost-efficient model for simpler coding tasks.',
  },
  {
    id: 'gpt-5.3-codex',
    label: 'gpt-5.3-codex',
    description: 'Coding-optimized model.',
  },
  {
    id: 'gpt-5.3-codex-spark',
    label: 'gpt-5.3-codex-spark',
    description: 'Ultra-fast coding model.',
  },
  {
    id: 'gpt-5.2',
    label: 'gpt-5.2',
    description: 'Optimized for professional work and long-running agents.',
  },
  {
    id: 'gpt-5.1-codex-mini',
    label: 'gpt-5.1-codex-mini',
    description: 'Optimized for Codex. Cheaper, faster, but less capable.',
  },
];

/**
 * Available models (backward compatibility)
 */
export const AVAILABLE_MODELS = CLAUDE_MODELS;

/**
 * AI provider information
 */
export interface ProviderInfo {
  id: string;
  label: string;
  icon: string;
  enabled: boolean;
}

/**
 * Available AI providers
 */
export const AVAILABLE_PROVIDERS: ProviderInfo[] = [
  { id: 'claude', label: 'Claude Code', icon: 'codicon-terminal', enabled: true },
  { id: 'codex', label: 'Codex', icon: 'codicon-terminal', enabled: true },
  { id: 'gemini', label: 'Gemini Cli', icon: 'codicon-terminal', enabled: false },
  { id: 'opencode', label: 'OpenCode', icon: 'codicon-terminal', enabled: false },
];

/**
 * Claude models that support adaptive thinking with effort parameter.
 * Based on: https://code.claude.com/docs/en/model-config#adjust-effort-level
 */
export const EFFORT_SUPPORTED_CLAUDE_MODELS = new Set([
  'claude-opus-4-7',
  'claude-opus-4-6',
  'claude-opus-4-6[1m]',
  'claude-sonnet-4-6',
]);

/**
 * Claude models that additionally support the 'xhigh' effort level.
 * Opus 4.7 is currently the only Claude Code model with xhigh support.
 */
export const XHIGH_EFFORT_CLAUDE_MODELS = new Set([
  'claude-opus-4-7',
]);

/**
 * Claude models that support the 'max' effort level.
 */
export const MAX_EFFORT_CLAUDE_MODELS = new Set([
  'claude-opus-4-7',
  'claude-opus-4-6',
  'claude-opus-4-6[1m]',
  'claude-sonnet-4-6',
]);

/**
 * Reasoning Effort (thinking depth)
 * Controls the depth of reasoning for AI models
 * Claude API values: low, medium, high, xhigh, max
 * Codex API values: low, medium, high, xhigh
 */
export type ReasoningEffort = 'low' | 'medium' | 'high' | 'xhigh' | 'max';

/**
 * Reasoning level information
 */
export interface ReasoningInfo {
  id: ReasoningEffort;
  label: string;
  icon: string;
  description?: string;
}

/**
 * Available reasoning levels
 */
export const REASONING_LEVELS: ReasoningInfo[] = [
  {
    id: 'low',
    label: 'Low',
    icon: 'codicon-circle-small',
    description: 'Quick responses with basic reasoning',
  },
  {
    id: 'medium',
    label: 'Medium',
    icon: 'codicon-circle-filled',
    description: 'Balanced thinking with moderate token savings',
  },
  {
    id: 'high',
    label: 'High',
    icon: 'codicon-circle-large-filled',
    description: 'Deep reasoning for complex tasks (default)',
  },
  {
    id: 'xhigh',
    label: 'XHigh',
    icon: 'codicon-flame',
    description: 'Extra deep reasoning for demanding tasks',
  },
  {
    id: 'max',
    label: 'Max',
    icon: 'codicon-rocket',
    description: 'Maximum reasoning depth',
  },
];

// ============================================================
// Usage Types
// ============================================================

/**
 * Usage information
 */
export interface UsageInfo {
  /** Usage percentage (0-100) */
  percentage: number;
  /** Used amount */
  used?: number;
  /** Total amount */
  total?: number;
}

// ============================================================
// Component Ref Handle Types
// ============================================================

/**
 * ChatInputBox imperative API
 * Used for performance optimization - uncontrolled mode with imperative access
 */
export interface ChatInputBoxHandle {
  /** Get current input text content */
  getValue: () => string;
  /** Set input text content */
  setValue: (value: string) => void;
  /** Focus the input element */
  focus: () => void;
  /** Clear input content */
  clear: () => void;
  /** Check if input has content */
  hasContent: () => boolean;
  /** Get file tags from input (for Codex context injection) */
  getFileTags: () => FileTagInfo[];
}

// ============================================================
// Component Props Types
// ============================================================

/**
 * ChatInputBox component props
 */
export interface ChatInputBoxProps {
  /** Whether loading */
  isLoading?: boolean;
  /** Current model */
  selectedModel?: string;
  /** Codex 聊天区显式选中项 key，用于区分同名模型来自哪个 provider */
  selectedCodexSelectionKey?: string;
  /** Codex default model from local CLI config (display only) */
  defaultCodexModelFromConfig?: string | null;
  /** Codex base_url from local CLI config (display only) */
  codexBaseUrl?: string | null;
  /** Whether Codex is currently using a non-official OpenAI base_url */
  codexUsesCustomBaseUrl?: boolean;
  /** Current permission mode */
  permissionMode?: PermissionMode;
  /** Current provider */
  currentProvider?: string;
  /** Usage percentage */
  usagePercentage?: number;
  /** Used context tokens */
  usageUsedTokens?: number;
  /** Maximum context tokens */
  usageMaxTokens?: number;
  /** Whether to show usage */
  showUsage?: boolean;
  /** Whether always thinking is enabled */
  alwaysThinkingEnabled?: boolean;
  /** Attachment list */
  attachments?: Attachment[];
  /** Placeholder text */
  placeholder?: string;
  /** Whether disabled */
  disabled?: boolean;
  /** Controlled mode: input content */
  value?: string;

  /** Current active file */
  activeFile?: string;
  /** Selected lines info (e.g., "L10-20") */
  selectedLines?: string;

  /** Clear context callback */
  onClearContext?: () => void;
  /** Remove code snippet callback */
  onRemoveCodeSnippet?: (id: string) => void;

  /** Submit message */
  onSubmit?: (content: string, attachments?: Attachment[]) => void;
  /** Stop generation */
  onStop?: () => void;
  /** Input change */
  onInput?: (content: string) => void;
  /** Add attachment */
  onAddAttachment?: (files: FileList) => void;
  /** Remove attachment */
  onRemoveAttachment?: (id: string) => void;
  /** Switch mode */
  onModeSelect?: (mode: PermissionMode) => void;
  /** Switch model */
  onModelSelect?: (modelId: string) => void;
  /** Switch provider */
  onProviderSelect?: (providerId: string) => void;
  /** Current reasoning effort */
  reasoningEffort?: ReasoningEffort;
  /** Switch reasoning effort callback */
  onReasoningChange?: (effort: ReasoningEffort) => void;
  /** Toggle thinking mode */
  onToggleThinking?: (enabled: boolean) => void;
  /** Whether streaming is enabled */
  streamingEnabled?: boolean;
  /** Toggle streaming */
  onStreamingEnabledChange?: (enabled: boolean) => void;

  /** Send shortcut setting: 'enter' = Enter sends | 'cmdEnter' = Cmd/Ctrl+Enter sends */
  sendShortcut?: 'enter' | 'cmdEnter';

  /** Currently selected agent */
  selectedAgent?: SelectedAgent | null;
  /** Select agent callback */
  onAgentSelect?: (agent: SelectedAgent | null) => void;
  /** Clear agent callback */
  onClearAgent?: () => void;
  /** Open agent settings callback */
  onOpenAgentSettings?: () => void;
  /** Open prompt settings callback */
  onOpenPromptSettings?: () => void;
  /** Open model settings (navigate to provider management to add models) */
  onOpenModelSettings?: () => void;
  /** Open Codex provider creation flow from model selector */
  onOpenCodexProviderSettings?: () => void;
  /** Open current active Codex provider model management */
  onOpenCodexProviderModelManagement?: () => void;
  /** Open Codex advanced model alias dialog */
  onOpenCodexModelAliasSettings?: () => void;

  /** Whether has messages (for rewind button display) */
  hasMessages?: boolean;
  /** Rewind file callback */
  onRewind?: () => void;

  /** Whether StatusPanel is expanded */
  statusPanelExpanded?: boolean;
  /** Toggle StatusPanel expand/collapse */
  onToggleStatusPanel?: () => void;

  /** SDK installed status (disable input when not installed) */
  sdkInstalled?: boolean;
  /** SDK status loading state */
  sdkStatusLoading?: boolean;
  /** Go to install SDK callback */
  onInstallSdk?: () => void;
  /** Show toast message */
  addToast?: (message: string, type: 'info' | 'success' | 'warning' | 'error') => void;

  /** Message queue items */
  messageQueue?: QueuedMessage[];
  /** Remove message from queue callback */
  onRemoveFromQueue?: (id: string) => void;

  /** Whether auto open file is enabled */
  autoOpenFileEnabled?: boolean;
  /** Toggle auto open file enabled */
  onAutoOpenFileEnabledChange?: (enabled: boolean) => void;
  /** Whether right click can open DevTools */
  rightClickOpenDevToolsEnabled?: boolean;
  /** Toggle right click DevTools access */
  onRightClickOpenDevToolsEnabledChange?: (enabled: boolean) => void;
  /** Whether long context (1M) is enabled */
  longContextEnabled?: boolean;
  /** Toggle long context callback */
  onLongContextChange?: (enabled: boolean) => void;
}

/**
 * ButtonArea component props
 */
export interface ButtonAreaProps {
  /** Whether submit disabled */
  disabled?: boolean;
  /** Whether has input content */
  hasInputContent?: boolean;
  /** Whether in conversation */
  isLoading?: boolean;
  /** Whether enhancing prompt */
  isEnhancing?: boolean;
  /** Current model */
  selectedModel?: string;
  /** Codex 聊天区显式选中项 key，用于区分同名模型来自哪个 provider */
  selectedCodexSelectionKey?: string;
  /** Codex default model from local CLI config (display only) */
  defaultCodexModelFromConfig?: string | null;
  /** Codex base_url from local CLI config (display only) */
  codexBaseUrl?: string | null;
  /** Whether Codex is currently using a non-official OpenAI base_url */
  codexUsesCustomBaseUrl?: boolean;
  /** Current mode */
  permissionMode?: PermissionMode;
  /** Current provider */
  currentProvider?: string;
  /** Current reasoning effort */
  reasoningEffort?: ReasoningEffort;

  /** Submit callback */
  onSubmit?: () => void;
  /** Stop callback */
  onStop?: () => void;
  /** Mode select callback */
  onModeSelect?: (mode: PermissionMode) => void;
  /** Model select callback */
  onModelSelect?: (modelId: string) => void;
  /** Provider select callback */
  onProviderSelect?: (providerId: string) => void;
  /** Switch reasoning effort callback */
  onReasoningChange?: (effort: ReasoningEffort) => void;
  /** Enhance prompt callback */
  onEnhancePrompt?: () => void;
  /** Whether always thinking enabled */
  alwaysThinkingEnabled?: boolean;
  /** Toggle thinking mode */
  onToggleThinking?: (enabled: boolean) => void;
  /** Whether streaming enabled */
  streamingEnabled?: boolean;
  /** Toggle streaming */
  onStreamingEnabledChange?: (enabled: boolean) => void;
  /** Currently selected agent */
  selectedAgent?: SelectedAgent | null;
  /** Agent selection callback */
  onAgentSelect?: (agent: SelectedAgent) => void;
  /** Clear agent callback */
  onClearAgent?: () => void;
  /** Open agent settings callback */
  onOpenAgentSettings?: () => void;
  /** Navigate to model management to add models */
  onAddModel?: () => void;
  /** Open Codex provider creation flow from model selector */
  onOpenCodexProviderSettings?: () => void;
  /** Open current active Codex provider model management */
  onOpenCodexProviderModelManagement?: () => void;
  /** Open Codex advanced model alias dialog */
  onOpenCodexModelAliasSettings?: () => void;
  /** Whether long context (1M) is enabled */
  longContextEnabled?: boolean;
  /** Toggle long context callback */
  onLongContextChange?: (enabled: boolean) => void;
}

/**
 * Dropdown component props
 */
export interface DropdownProps {
  /** Whether visible */
  isVisible: boolean;
  /** Position information */
  position: DropdownPosition | null;
  /** Width */
  width?: number;
  /** Y offset */
  offsetY?: number;
  /** X offset */
  offsetX?: number;
  /** Selected index */
  selectedIndex?: number;
  /** Close callback */
  onClose?: () => void;
  /** Children */
  children: React.ReactNode;
}

/**
 * TokenIndicator component props
 */
export interface TokenIndicatorProps {
  /** Percentage (0-100) */
  percentage: number;
  /** Size */
  size?: number;
  /** Used context tokens */
  usedTokens?: number;
  /** Maximum context tokens */
  maxTokens?: number;
}

/**
 * AttachmentList component props
 */
export interface AttachmentListProps {
  /** Attachment list */
  attachments: Attachment[];
  /** Remove attachment callback */
  onRemove?: (id: string) => void;
  /** Preview image callback */
  onPreview?: (attachment: Attachment) => void;
  /** Whether right click can open DevTools */
  rightClickOpenDevToolsEnabled?: boolean;
}

/**
 * DropdownItem component props
 */
export interface DropdownItemProps {
  /** Item data */
  item: DropdownItemData;
  /** Whether highlighted */
  isActive?: boolean;
  /** Click callback */
  onClick?: () => void;
  /** Mouse enter callback */
  onMouseEnter?: () => void;
}

// ============================================================
// Message Queue Types
// ============================================================

/**
 * Queued message item
 * When AI is processing (loading), new messages are queued here
 */
export interface QueuedMessage {
  /** Unique identifier */
  id: string;
  /** Message content */
  content: string;
  /** Attachments (optional) */
  attachments?: Attachment[];
  /** Timestamp when queued */
  queuedAt: number;
}
