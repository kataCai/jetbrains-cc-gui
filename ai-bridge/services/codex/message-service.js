/**
 * Codex Message Service — Slim Coordinator
 *
 * Handles message sending through Codex SDK (@openai/codex-sdk).
 * Provides unified interface that matches Claude's message service.
 *
 * Key Differences from Claude:
 * - Uses threadId instead of sessionId
 * - Permission model: skipGitRepoCheck + sandbox (not permissionMode string)
 * - Events: thread.*, turn.*, item.* (not system/assistant/user/result)
 * - Supports images via local_image type (requires file paths)
 *
 * All event-processing logic lives in codex-event-handler.js.
 * Utility functions are split across codex-utils.js, codex-agents-loader.js,
 * codex-patch-parser.js, and codex-command-utils.js.
 *
 * @author Crafted with geek spirit
 */

import { CodexPermissionMapper } from '../../utils/permission-mapper.js';
import { getMcpServerTools as getMcpServerToolsImpl } from '../claude/mcp-status/index.js';
import {
  logDebug, logInfo, logWarn,
  ensureCodexSdk,
  normalizeCodexPermissionMode,
  resolveSandboxModeOverride,
  resolveApprovalPolicyOverride,
  buildCodexCliEnvironment,
  buildErrorPayload
} from './codex-utils.js';
import { collectAgentsInstructions } from './codex-agents-loader.js';
import { createInitialEventState, processCodexEventStream } from './codex-event-handler.js';
import {
  createCodexRecoveryConfig,
  createFailureEvidence,
  resolveCodexRecoveryAction,
  waitForRecoveryRetry,
} from './codex-recovery-policy.js';
import {
  dispatchCodexRequestByMode,
  sendViaCodexSdk,
  sendViaCcSwitchProxy,
  sendViaCustomAdapter,
} from './request-dispatcher.js';

export const CODEMOSS_MANAGED_PROVIDER_KEY = 'codemoss_managed_provider';

// ---------------------------------------------------------------------------
// sendMessage
// ---------------------------------------------------------------------------

/**
 * 向 Codex 发送消息，并兼容旧的位置参数调用与新的结构化 runtime profile 调用。
 * 关键逻辑：
 * 1. 先把所有入口统一折叠为结构化请求对象，避免 requestMode、endpoint、凭证在多层传递中丢失。
 * 2. 再基于 runtime profile 构造 SDK 选项，显式覆盖 baseUrl/apiKey，并隔离高风险 CODEX_* 环境变量。
 * 3. 最终保持原有线程恢复、权限映射、流式事件处理逻辑不变，降低对既有会话链路的回归风险。
 *
 * @param {object|string} requestOrMessage 结构化请求对象，或兼容旧调用方式的 message
 * @param {string} threadId 旧接口下的线程 ID
 * @param {string} cwd 旧接口下的工作目录
 * @param {string} permissionMode 旧接口下的统一权限模式
 * @param {string} model 旧接口下的模型名
 * @param {string} baseUrl 旧接口下的自定义 endpoint
 * @param {string} apiKey 旧接口下的 API Key
 * @param {string} reasoningEffort 旧接口下的推理强度
 * @param {Array} attachments 旧接口下的本地图片附件列表
 * @param {object} recoveryConfig 恢复策略配置
 * @returns {Promise<void>} 消息发送流程结束后返回
 */
/**
 * 执行当前唯一已落地的 `codex_sdk` 标准发送器。
 * 该方法把现有 SDK 发送细节从 `sendMessage` 主入口中抽离出来，
 * 让上层只负责 requestMode 分发、恢复策略和统一错误包装。
 *
 * @param {object} request 当前结构化请求
 * @param {object} executionContext 发送执行上下文
 * @param {(msg: object) => void} executionContext.emitMessage 统一消息输出函数
 * @param {object} executionContext.state 当前事件流状态对象
 * @param {() => void} executionContext.emitStreamEndOnce 统一的流结束发射器
 * @param {() => void} executionContext.markStreamStarted 标记流已正式开始，避免重复输出 `STREAM_END`
 * @returns {Promise<void>} 发送完成后返回
 */
async function sendViaCodexSdkRuntime(request, executionContext) {
  const {
    emitMessage,
    state,
    emitStreamEndOnce,
    markStreamStarted,
  } = executionContext;
  const {
    message,
    threadId: normalizedThreadId,
    cwd: normalizedCwd,
    permissionMode: normalizedPermissionInput,
    runtimeProfile,
    attachments: normalizedAttachments,
  } = request;

  const normalizedPermissionMode = normalizeCodexPermissionMode(normalizedPermissionInput || 'default');
  console.log('[DEBUG] Codex sendMessage called with params:', {
    threadId: normalizedThreadId,
    cwd: normalizedCwd,
    permissionMode: normalizedPermissionMode,
    providerId: runtimeProfile.providerId,
    authMode: runtimeProfile.authMode,
    model: runtimeProfile.model,
    reasoningEffort: runtimeProfile.reasoningEffort,
    requestMode: runtimeProfile.requestMode,
    credentialSource: runtimeProfile.credentialSource,
    baseUrlSource: runtimeProfile.baseUrlSource,
    effectiveConfigSource: runtimeProfile.effectiveConfigSource,
    fallbackDetected: runtimeProfile.fallbackDetected,
    hasBaseUrl: !!runtimeProfile.baseUrl,
    hasApiKey: !!runtimeProfile.apiKey,
    attachmentsCount: normalizedAttachments?.length || 0,
  });

  const sdk = await ensureCodexSdk();
  const Codex = sdk.Codex || sdk.default || sdk;

  const codexOptions = buildCodexSdkOptions(runtimeProfile, process.env);
  const removedKeys = codexOptions._diagnostics?.removedKeys || [];
  const runtimeDiagnostics = buildCodexRuntimeDiagnostics(runtimeProfile, codexOptions.env, removedKeys);
  logDebug('PERM_DEBUG', 'Codex CLI env isolation:', JSON.stringify({
    removedKeys,
    removedCount: removedKeys.length
  }));
  logInfo('CODEX_RUNTIME', JSON.stringify(runtimeDiagnostics));
  delete codexOptions._diagnostics;

  const codex = new Codex(codexOptions);
  const permissionConfig = CodexPermissionMapper.toProvider(normalizedPermissionMode);

  logDebug('PERM_DEBUG', 'Codex permission config:', JSON.stringify(permissionConfig));
  logDebug('PERM_DEBUG', 'Raw env permission overrides:', JSON.stringify({
    CODEX_SANDBOX_MODE: process.env.CODEX_SANDBOX_MODE || '',
    CODEX_APPROVAL_POLICY: process.env.CODEX_APPROVAL_POLICY || ''
  }));

  const sandboxOverride = resolveSandboxModeOverride();
  if (sandboxOverride) {
    permissionConfig.sandbox = sandboxOverride;
    logDebug('PERM_DEBUG', 'Sandbox override from env CODEX_SANDBOX_MODE:', sandboxOverride);
  }
  const approvalPolicyOverride = resolveApprovalPolicyOverride();
  if (approvalPolicyOverride) {
    permissionConfig.approvalPolicy = approvalPolicyOverride;
    logDebug('PERM_DEBUG', 'Approval override from env CODEX_APPROVAL_POLICY:', approvalPolicyOverride);
  }

  const threadOptions = {
    skipGitRepoCheck: permissionConfig.skipGitRepoCheck,
    maxTurns: 200
  };

  if (runtimeProfile.reasoningEffort && runtimeProfile.reasoningEffort.trim() !== '') {
    threadOptions.modelReasoningEffort = runtimeProfile.reasoningEffort;
    console.log('[DEBUG] Reasoning effort:', runtimeProfile.reasoningEffort);
  }

  if (permissionConfig.approvalPolicy) {
    threadOptions.approvalPolicy = permissionConfig.approvalPolicy;
  }

  const effectiveThreadId = normalizedThreadId;
  if (!effectiveThreadId || effectiveThreadId.trim() === '') {
    if (normalizedCwd && normalizedCwd.trim() !== '') {
      threadOptions.workingDirectory = normalizedCwd;
      console.log('[DEBUG] Working directory:', normalizedCwd);
    }
  } else {
    console.log('[DEBUG] Resuming thread - skipping workingDirectory to allow session lookup');
  }

  if (runtimeProfile.model && runtimeProfile.model.trim() !== '') {
    threadOptions.model = runtimeProfile.model;
    console.log('[DEBUG] Model:', runtimeProfile.model);
  }

  if (permissionConfig.sandbox) {
    threadOptions.sandboxMode = permissionConfig.sandbox;
    console.log('[DEBUG] Sandbox mode:', permissionConfig.sandbox);
  }

  logDebug('PERM_DEBUG', 'Final Codex threadOptions:', JSON.stringify({
    permissionMode: normalizedPermissionMode,
    workingDirectory: threadOptions.workingDirectory,
    sandboxMode: threadOptions.sandboxMode,
    approvalPolicy: threadOptions.approvalPolicy,
    skipGitRepoCheck: threadOptions.skipGitRepoCheck
  }));

  let thread;
  if (effectiveThreadId && effectiveThreadId.trim() !== '') {
    console.log('[DEBUG] Resuming thread:', effectiveThreadId);
    thread = codex.resumeThread(effectiveThreadId, threadOptions);
  } else {
    console.log('[DEBUG] Starting new thread');
    thread = codex.startThread(threadOptions);
  }

  let finalMessage = message;
  if ((!effectiveThreadId || effectiveThreadId.trim() === '') && normalizedCwd) {
    const agentsInstructions = collectAgentsInstructions(normalizedCwd);
    if (agentsInstructions) {
      finalMessage = `<agents-instructions>\n${agentsInstructions}\n</agents-instructions>\n\n${message}`;
      logDebug('AGENTS.md', `Prepended ${agentsInstructions.length} chars of instructions to message`);
    }
  }

  let runInput;
  if (normalizedAttachments && Array.isArray(normalizedAttachments) && normalizedAttachments.length > 0) {
    runInput = [{ type: 'text', text: finalMessage }];
    for (const attachment of normalizedAttachments) {
      if (attachment && attachment.type === 'local_image' && attachment.path) {
        runInput.push({ type: 'local_image', path: attachment.path });
        console.log('[DEBUG] Added local_image attachment:', attachment.path);
      }
    }
    console.log('[DEBUG] Using array input format with', runInput.length, 'entries');
  } else {
    runInput = finalMessage;
    console.log('[DEBUG] Using string input format');
  }

  const turnAbortController = new AbortController();
  const { events } = await thread.runStreamed(runInput, {
    signal: turnAbortController.signal
  });
  console.log('[STREAM_START]');
  markStreamStarted();

  const workingDirectory = normalizedCwd && normalizedCwd.trim() !== '' ? normalizedCwd : undefined;
  const config = {
    cwd: workingDirectory,
    threadId: normalizedThreadId,
    threadOptions,
    normalizedPermissionMode,
    turnAbortController,
    onTurnCompleted: emitStreamEndOnce,
    onTurnFailed: emitStreamEndOnce
  };

  await processCodexEventStream(events, state, config);
  emitStreamEndOnce();

  if (!state.reasoningObserved) {
    console.warn('[THINKING_HINT]', 'Codex did not return reasoning items. If you still cannot see the thinking process, please refer to docs/codex/docs/config.md for hide_agent_reasoning/show_raw_agent_reasoning settings, and ensure your OpenAI account has been verified.');
  }

  if (!state.suppressNoResponseFallback && state.assistantText.length === 0) {
    const noResponseMsg = [
      '\n[WARNING] Codex completed tool executions but did not generate a text response.',
      'This may happen when:',
      '- The task was purely about gathering information',
      '- Codex reached maxTurns limit (200 turns)',
      '- The query required only command execution',
      '\nPlease try:',
      '- Asking a more specific question',
      '- Requesting explicit analysis or explanation',
      '- Checking the command outputs above for your answer'
    ].join('\n');

    emitMessage({
      type: 'assistant',
      message: {
        role: 'assistant',
        content: [{ type: 'text', text: noResponseMsg }]
      }
    });
    state.finalResponse = noResponseMsg;
  }
}

export async function sendMessage(
  requestOrMessage,
  threadId = null,
  cwd = null,
  permissionMode = null,
  model = null,
  baseUrl = null,
  apiKey = null,
  reasoningEffort = 'medium',
  attachments = [],
  recoveryConfig = {}
) {
  const request = buildCodexRuntimeRequest(
    typeof requestOrMessage === 'object' && requestOrMessage !== null
      ? requestOrMessage
      : {
          message: requestOrMessage,
          threadId,
          cwd,
          permissionMode,
          runtimeProfile: {
            model,
            baseUrl,
            apiKey,
            reasoningEffort,
            requestMode: 'codex_sdk',
          },
          attachments,
          recoveryConfig,
        }
  );
  const normalizedRecoveryConfig = createCodexRecoveryConfig(request.recoveryConfig);
  let attempt = 0;

  while (true) {
    let streamStarted = false;
    let streamEnded = false;
    const emitStreamEndOnce = () => {
      if (!streamStarted || streamEnded) {
        return;
      }
      streamEnded = true;
      console.log('[STREAM_END]');
    };
    const markStreamStarted = () => {
      streamStarted = true;
    };

    console.log('[MESSAGE_START]');
    const emitMessage = (msg) => {
      console.log('[MESSAGE]', JSON.stringify(msg));
    };
    const state = createInitialEventState(emitMessage);

    try {
      await dispatchCodexRequestByMode(request, {
        codex_sdk: (payload) => sendViaCodexSdk(payload, (selectedRequest) =>
          sendViaCodexSdkRuntime(selectedRequest, {
            emitMessage,
            state,
            emitStreamEndOnce,
            markStreamStarted,
          })),
        cc_switch_proxy: sendViaCcSwitchProxy,
        custom_adapter: sendViaCustomAdapter,
      });

      state.messageEndObserved = true;
      console.log('[MESSAGE_END]');
      console.log(JSON.stringify({
        success: true,
        threadId: state.currentThreadId,
        result: state.finalResponse
      }));
      return;
    } catch (error) {
      emitStreamEndOnce();
      console.error('[DEBUG] Error:', error.message);
      console.error('[DEBUG] Error stack:', error.stack);

      const evidence = createFailureEvidence({
        phase: 'send',
        rawErrorMessage: error?.message || String(error),
        errorName: error?.name || 'Error',
        hasMessageEnd: state.messageEndObserved,
        assistantText: state.assistantText,
        finalResponse: state.finalResponse,
        toolResultCount: state.emittedToolResultIds?.size || 0,
        retryAttempt: attempt,
      });
      const recovery = resolveCodexRecoveryAction(evidence, normalizedRecoveryConfig);

      if (recovery.shouldPromoteToCompleted) {
        state.messageEndObserved = true;
        console.warn('[RECOVERY]', JSON.stringify({
          provider: 'codex',
          category: recovery.category,
          action: recovery.action,
          retryAttempt: attempt,
        }));
        console.log('[MESSAGE_END]');
        console.log(JSON.stringify({
          success: true,
          threadId: state.currentThreadId,
          recovered: true,
          recoveryCategory: recovery.category,
          result: state.finalResponse || state.assistantText || '',
        }));
        return;
      }

      if (recovery.shouldRetry) {
        console.warn('[RETRYING]', JSON.stringify({
          provider: 'codex',
          category: recovery.category,
          action: recovery.action,
          retryAttempt: attempt + 1,
          delayMs: recovery.delayMs,
        }));
        await waitForRecoveryRetry(recovery.delayMs);
        attempt += 1;
        continue;
      }

      const errorPayload = buildErrorPayload(error, {
        provider: 'codex',
        requestMode: request?.runtimeProfile?.requestMode || 'codex_sdk',
        errorCode: error?.code || null,
        recoveryCategory: recovery.category,
        recoveryAction: recovery.action,
        evidence,
      });
      console.error('[SEND_ERROR]', JSON.stringify(errorPayload));
      console.log(JSON.stringify(errorPayload));
      return;
    }
  }
}

/**
 * 统一归一化 Codex 发送请求，兼容新旧两种调用方式。
 * 这里把运行时 profile 聚合为单对象，避免 requestMode、endpoint、凭证在多层位置参数传递中丢失。
 *
 * @param {object} request 原始请求
 * @returns {object} 归一化后的结构化请求
 */
export function buildCodexRuntimeRequest(request = {}) {
  const runtimeProfile = request.runtimeProfile || {};
  return {
    message: request.message || '',
    threadId: request.threadId || '',
    cwd: request.cwd || '',
    permissionMode: request.permissionMode || '',
    runtimeProfile: {
      providerId: runtimeProfile.providerId || '',
      authMode: runtimeProfile.authMode || '',
      model: runtimeProfile.model || '',
      baseUrl: runtimeProfile.baseUrl || '',
      apiKey: runtimeProfile.apiKey || '',
      reasoningEffort: runtimeProfile.reasoningEffort || 'medium',
      requestMode: runtimeProfile.requestMode || 'codex_sdk',
      credentialSource: runtimeProfile.credentialSource || '',
      baseUrlSource: runtimeProfile.baseUrlSource || (runtimeProfile.baseUrl ? 'provider' : 'sdk_default'),
      effectiveConfigSource: runtimeProfile.effectiveConfigSource || '',
      fallbackDetected: runtimeProfile.fallbackDetected === true,
      localCodexModelProvider: runtimeProfile.localCodexModelProvider || '',
    },
    attachments: Array.isArray(request.attachments) ? request.attachments : [],
    recoveryConfig: request.recoveryConfig || {},
  };
}

/**
 * 根据运行时 profile 构造传给 Codex SDK 的选项。
 * 这里显式注入 baseUrl/apiKey，并隔离高风险 CODEX_* 环境变量，避免错误继承本地 CLI 状态。
 *
 * @param {object} runtimeProfile 运行时 provider profile
 * @param {NodeJS.ProcessEnv|object} baseEnv 当前进程环境变量
 * @returns {object} Codex SDK 构造参数
 */
export function buildCodexSdkOptions(runtimeProfile = {}, baseEnv = process.env) {
  const codexOptions = {};

  if (runtimeProfile.baseUrl) {
    codexOptions.baseUrl = runtimeProfile.baseUrl;
  }
  if (runtimeProfile.apiKey) {
    codexOptions.apiKey = runtimeProfile.apiKey;
  }

  const { cliEnv, removedKeys } = buildCodexCliEnvironment(baseEnv, runtimeProfile);
  codexOptions.env = cliEnv;
  const configOverrides = buildRequestScopedConfigOverrides(runtimeProfile);
  if (configOverrides) {
    codexOptions.config = configOverrides;
  }
  codexOptions._diagnostics = {
    removedKeys,
    forcedModelProvider: configOverrides?.model_provider || '',
    injectedConfigOverrides: configOverrides || null,
  };
  return codexOptions;
}

/**
 * 为托管 provider 生成 request-scoped 的 Codex CLI 配置覆盖。
 * 关键目标是显式切断底层 CLI 对本地 `~/.codex/config.toml.model_provider` 的复用，
 * 改为本次请求独占的 provider key，从而把真实路由锁定到 GUI 当前选中的托管 provider。
 *
 * @param {object} runtimeProfile 当前请求的运行时 profile
 * @returns {object|null} 需要注入给 Codex SDK 的 config overrides；CLI Login 模式下返回 null
 */
export function buildRequestScopedConfigOverrides(runtimeProfile = {}) {
  const effectiveConfigSource = runtimeProfile.effectiveConfigSource || '';
  const isManagedProvider = effectiveConfigSource === 'codemoss_managed_provider';
  if (!isManagedProvider) {
    return null;
  }

  return {
    model_provider: CODEMOSS_MANAGED_PROVIDER_KEY,
    model_providers: {
      [CODEMOSS_MANAGED_PROVIDER_KEY]: {
        name: 'Codemoss Managed Provider',
        base_url: runtimeProfile.baseUrl || '',
        env_key: 'CODEX_API_KEY',
        wire_api: 'responses',
      },
    },
  };
}

/**
 * 构造统一的运行时诊断信息。
 * 这里的输出用于 Java/Node 链路排障，因此只保留来源字段、布尔状态和脱敏后的 endpoint 信息，
 * 明确禁止回传原始 apiKey。
 *
 * @param {object} runtimeProfile 当前请求的运行时 profile
 * @param {object} effectiveEnv 实际传给 Codex SDK 的环境变量
 * @param {string[]} removedKeys 为避免污染而移除的环境变量列表
 * @returns {object} 脱敏后的统一诊断对象
 */
export function buildCodexRuntimeDiagnostics(runtimeProfile = {}, effectiveEnv = {}, removedKeys = []) {
  const injectedConfigOverrides = buildRequestScopedConfigOverrides(runtimeProfile);
  const localCodexModelProvider = runtimeProfile.localCodexModelProvider || '';
  const forcedModelProvider = injectedConfigOverrides?.model_provider || '';
  const finalModelProvider = forcedModelProvider || localCodexModelProvider || '';
  return {
    transport: runtimeProfile.requestMode || 'codex_sdk',
    providerId: runtimeProfile.providerId || '',
    authMode: runtimeProfile.authMode || '',
    model: runtimeProfile.model || '',
    resolvedBaseUrl: runtimeProfile.baseUrl || '',
    endpointSource: runtimeProfile.baseUrlSource || (runtimeProfile.baseUrl ? 'provider' : 'sdk_default'),
    credentialSource: runtimeProfile.credentialSource || '',
    effectiveConfigSource: runtimeProfile.effectiveConfigSource || '',
    fallbackDetected: runtimeProfile.fallbackDetected === true,
    hasApiKey: !!runtimeProfile.apiKey,
    hasBaseUrl: !!runtimeProfile.baseUrl,
    forcedModelProvider,
    localCodexModelProvider,
    finalModelProvider,
    localConfigConflictDetected: !!forcedModelProvider && !!localCodexModelProvider && forcedModelProvider !== localCodexModelProvider,
    injectedConfigOverrides,
    removedEnvKeys: Array.isArray(removedKeys) ? removedKeys : [],
    hasOpenaiBaseUrlEnv: !!effectiveEnv?.OPENAI_BASE_URL,
    hasCodexApiKeyEnv: !!effectiveEnv?.CODEX_API_KEY,
  };
}

// ---------------------------------------------------------------------------
// getMcpServerTools
// ---------------------------------------------------------------------------

/**
 * Gets the tools list for a Codex MCP server.
 * Reuses mcp-status-service probing logic to avoid duplicate handshake implementation.
 *
 * @param {string} serverId
 * @param {Object} rawServerConfig
 */
export async function getMcpServerTools(serverId, rawServerConfig) {
  try {
    if (!serverId) {
      const invalid = {
        success: false,
        serverId: '',
        error: 'Missing serverId',
        tools: []
      };
      console.log('[MCP_SERVER_TOOLS]' + JSON.stringify(invalid));
      console.log(JSON.stringify(invalid));
      return;
    }

    if (!rawServerConfig || typeof rawServerConfig !== 'object') {
      const invalid = {
        success: false,
        serverId,
        error: 'Missing serverConfig',
        tools: []
      };
      console.log('[MCP_SERVER_TOOLS]' + JSON.stringify(invalid));
      console.log(JSON.stringify(invalid));
      return;
    }

    const serverConfig = normalizeCodexMcpConfig(rawServerConfig);
    const toolsResult = await getMcpServerToolsImpl(serverId, serverConfig);
    const tools = Array.isArray(toolsResult?.tools) ? toolsResult.tools : [];
    const hasError = !!toolsResult?.error;

    const result = {
      success: !hasError || tools.length > 0,
      serverId,
      serverName: toolsResult?.name || serverId,
      tools,
      error: toolsResult?.error || null
    };

    const resultJson = JSON.stringify(result);
    console.log('[MCP_SERVER_TOOLS]' + resultJson);
    console.log(resultJson);
  } catch (error) {
    const errorResult = {
      success: false,
      serverId: serverId || '',
      error: error?.message || String(error),
      tools: []
    };
    const resultJson = JSON.stringify(errorResult);
    console.log('[MCP_SERVER_TOOLS]' + resultJson);
    console.log(resultJson);
  }
}

// ---------------------------------------------------------------------------
// normalizeCodexMcpConfig (internal)
// ---------------------------------------------------------------------------

/**
 * Converts Codex config field names to a format recognized by mcp-status-service.
 *
 * @param {Object} raw
 * @returns {Object}
 */
function normalizeCodexMcpConfig(raw) {
  const normalized = { ...raw };
  const type = normalized.type || (normalized.url ? 'http' : 'stdio');
  normalized.type = type;

  // Codex: http_headers -> mcp-status: headers
  if (!normalized.headers && normalized.http_headers && typeof normalized.http_headers === 'object') {
    normalized.headers = { ...normalized.http_headers };
  }

  // Codex: env_http_headers (values are env var names) -> headers (resolved values)
  if (normalized.env_http_headers && typeof normalized.env_http_headers === 'object') {
    const fromEnv = {};
    for (const [headerName, envName] of Object.entries(normalized.env_http_headers)) {
      if (typeof envName === 'string') {
        const envValue = process.env[envName];
        if (envValue) {
          fromEnv[headerName] = envValue;
        }
      }
    }
    normalized.headers = { ...(normalized.headers || {}), ...fromEnv };
  }

  // Codex: bearer_token_env_var -> Authorization header
  if (normalized.bearer_token_env_var && typeof normalized.bearer_token_env_var === 'string') {
    const token = process.env[normalized.bearer_token_env_var];
    if (token && !(normalized.headers && normalized.headers.Authorization)) {
      normalized.headers = { ...(normalized.headers || {}), Authorization: `Bearer ${token}` };
    }
  }

  return normalized;
}
