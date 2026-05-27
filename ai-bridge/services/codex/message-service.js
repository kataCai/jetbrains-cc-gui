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

/**
 * 构建 completion fallback 的任务完成摘要。
 * 当本轮真实完成但末尾没有自然语言总结时，统一补一条用户可见的结束说明，
 * 避免界面只剩工具块与“本次耗时”，用户无法判断是否已经结束以及做了什么。
 *
 * @param {ReturnType<typeof createInitialEventState>} state Codex 事件流状态
 * @return {string}
 */
function buildCompletionFallbackSummary(state) {
  const summaryParts = ['本轮任务已完成，会话仍可继续。'];

  if (state.fileChangeCount > 0) {
    summaryParts.push(`已修改 ${state.fileChangeCount} 个文件`);
  }
  if (state.executedCommandCount > 0) {
    summaryParts.push(`执行 ${state.executedCommandCount} 条命令`);
  }

  if (summaryParts.length === 1) {
    summaryParts.push('本轮未生成最终自然语言总结。');
  } else {
    summaryParts.push('本轮未生成最终自然语言总结，以上为系统补充的结束说明。');
  }

  return summaryParts.join(' ');
}

/**
 * 将 completion fallback 作为 task_notification 注入聊天区，
 * 复用现有通知块样式，让用户一眼看出“本轮已完成”。
 *
 * @param {ReturnType<typeof createInitialEventState>} state Codex 事件流状态
 * @param {(msg: unknown) => void} emitMessage 输出消息函数
 * @return {string}
 */
function emitCompletionFallbackSummary(state, emitMessage) {
  const summary = buildCompletionFallbackSummary(state);
  const rawText = `<task-notification><status>completed</status><summary>${summary}</summary></task-notification>`;
  emitMessage({
    type: 'user',
    message: {
      role: 'user',
      content: [{ type: 'text', text: rawText }],
    },
    raw: {
      content: [{ type: 'text', text: rawText }],
      origin: { kind: 'task-notification' },
    },
  });
  state.completionSummaryReady = true;
  state.completionSummarySource = state.fileChangeCount > 0 || state.executedCommandCount > 0
    ? 'generated_from_activity'
    : 'generic_fallback';
  return summary;
}

// ---------------------------------------------------------------------------
// sendMessage
// ---------------------------------------------------------------------------

/**
 * Send message to Codex (with optional thread resumption)
 *
 * @param {string} message - User message to send
 * @param {string} threadId - Thread ID to resume (optional)
 * @param {string} cwd - Working directory (optional)
 * @param {string} permissionMode - Unified permission mode (optional)
 * @param {string} model - Model name (optional)
 * @param {string} baseUrl - API base URL (optional, for custom endpoints)
 * @param {string} apiKey - API key (optional, for custom auth)
 * @param {string} reasoningEffort - Reasoning effort level (optional)
 * @param {Array} attachments - Image attachments in local_image format (optional)
 */
export async function sendMessage(
  message,
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
  const normalizedRecoveryConfig = createCodexRecoveryConfig(recoveryConfig);
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

    const normalizedPermissionMode = normalizeCodexPermissionMode(permissionMode || 'default');
    console.log('[DEBUG] Codex sendMessage called with params:', {
      threadId,
      cwd,
      permissionMode: normalizedPermissionMode,
      model,
      reasoningEffort,
      hasBaseUrl: !!baseUrl,
      hasApiKey: !!apiKey,
      attachmentsCount: attachments?.length || 0,
      retryAttempt: attempt,
    });

    console.log('[MESSAGE_START]');
    const emitMessage = (msg) => {
      console.log('[MESSAGE]', JSON.stringify(msg));
    };
    const state = createInitialEventState(emitMessage);

    try {
      // ============================================================
      // 1. Initialize Codex SDK (dynamic loading)
      // ============================================================
      const sdk = await ensureCodexSdk();
      const Codex = sdk.Codex || sdk.default || sdk;

      const codexOptions = {};

      if (baseUrl) {
        codexOptions.baseUrl = baseUrl;
      }
      if (apiKey) {
        codexOptions.apiKey = apiKey;
      }

      // Pass a sanitized env to the SDK to avoid inherited CODEX_* pollution
      const { cliEnv, removedKeys } = buildCodexCliEnvironment(process.env);
      codexOptions.env = cliEnv;
      logDebug('PERM_DEBUG', 'Codex CLI env isolation:', JSON.stringify({
        removedKeys,
        removedCount: removedKeys.length
      }));

      const codex = new Codex(codexOptions);

      // ============================================================
      // 2. Map Unified Permission Mode to Codex Format
      // ============================================================
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

      // ============================================================
      // 3. Build Thread Options
      // ============================================================
      const threadOptions = {
        skipGitRepoCheck: permissionConfig.skipGitRepoCheck,
        maxTurns: 200
      };

      if (reasoningEffort && reasoningEffort.trim() !== '') {
        threadOptions.modelReasoningEffort = reasoningEffort;
        console.log('[DEBUG] Reasoning effort:', reasoningEffort);
      }

      if (permissionConfig.approvalPolicy) {
        threadOptions.approvalPolicy = permissionConfig.approvalPolicy;
      }

      const isResumingThread = threadId && threadId.trim() !== '';

      if (!isResumingThread) {
        if (cwd && cwd.trim() !== '') {
          threadOptions.workingDirectory = cwd;
          console.log('[DEBUG] Working directory:', cwd);
        }
      } else {
        console.log('[DEBUG] Resuming thread - skipping workingDirectory to allow session lookup');
      }

      if (model && model.trim() !== '') {
        threadOptions.model = model;
        console.log('[DEBUG] Model:', model);
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

      // ============================================================
      // 4. Create or Resume Thread
      // ============================================================
      let thread;
      if (isResumingThread) {
        console.log('[DEBUG] Resuming thread:', threadId);
        thread = codex.resumeThread(threadId, threadOptions);
      } else {
        console.log('[DEBUG] Starting new thread');
        thread = codex.startThread(threadOptions);
      }

      // ============================================================
      // 5. Collect AGENTS.md Instructions (only for new threads)
      // ============================================================
      let finalMessage = message;
      if (!isResumingThread && cwd) {
        const agentsInstructions = collectAgentsInstructions(cwd);
        if (agentsInstructions) {
          finalMessage = `<agents-instructions>\n${agentsInstructions}\n</agents-instructions>\n\n${message}`;
          logDebug('AGENTS.md', `Prepended ${agentsInstructions.length} chars of instructions to message`);
        }
      }

      // ============================================================
      // 6. Build Input and Start Streaming
      // ============================================================
      let runInput;
      if (attachments && Array.isArray(attachments) && attachments.length > 0) {
        runInput = [{ type: 'text', text: finalMessage }];
        for (const attachment of attachments) {
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
      streamStarted = true;

      // ============================================================
      // 7. Delegate Event Processing to codex-event-handler
      // ============================================================
      const workingDirectory = cwd && cwd.trim() !== '' ? cwd : undefined;
      const config = {
        cwd: workingDirectory,
        threadId,
        threadOptions,
        normalizedPermissionMode,
        turnAbortController
      };

      await processCodexEventStream(events, state, config);

      // ============================================================
      // 8. Completion Phase
      // ============================================================
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
        state.completionSummaryReady = true;
        state.completionSummarySource = 'no_response_warning';
      }

      if (
        !state.suppressNoResponseFallback &&
        state.assistantText.length > 0 &&
        !state.hasTrailingAssistantTextSummary
      ) {
        const completionFallbackSummary = emitCompletionFallbackSummary(state, emitMessage);
        // 对外 result 保持用户最终可见的结束说明，而不是中途分析文本，
        // 便于 Java/通知链路和后续日志判断本轮最终展示内容。
        state.finalResponse = completionFallbackSummary;
      }

      state.messageEndObserved = true;
      console.log('[MESSAGE_END]');
      // 这里将 STREAM_END 收窄为“本次 sendMessage 真正收口完成”，
      // 避免 turn.completed 在事件处理中提前制造任务结束语义。
      emitStreamEndOnce();
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
