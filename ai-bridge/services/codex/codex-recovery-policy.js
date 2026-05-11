/**
 * Codex 失败恢复策略与分类工具。
 * 负责把事件流结束后的证据和异常归一化为可配置动作，避免上层只能依赖字符串猜测。
 */

const WINDOWS_TERMINATION_PATTERNS = [
  /Failed to parse item:\s*SUCCESS:\s*The process with PID/i,
  /SUCCESS:\s*The process with PID/i,
  /child process of PID/i,
  /has been terminated/i,
  /Failed to parse item:/i,
  /\bPID\s*\d{2,}\b/i,
  /\(\?\?\?\?\s*PID\s*\d{2,}/i,
];

function looksLikeWindowsTerminationNoise(combinedMessage) {
  if (WINDOWS_TERMINATION_PATTERNS.some((pattern) => pattern.test(combinedMessage))) {
    return true;
  }

  const normalized = String(combinedMessage || '');
  const hasParsePrefix = /Failed to parse item:/i.test(normalized);
  const pidMatches = normalized.match(/\bPID\s*\d{2,}\b/gi) || [];
  const hasChildProcessHint = /child process|\u5b50\u8fdb\u7a0b|\(\?\?\?\?/i.test(normalized);

  // 中文系统下 taskkill 输出常会被错误解码成乱码，但仍会保留多个 PID 片段。
  // 这里退化到“parse item + 多个 PID + 子进程提示”的结构匹配，避免只依赖英文文案。
  return hasParsePrefix && pidMatches.length >= 2 && hasChildProcessHint;
}

const TRANSIENT_NETWORK_PATTERNS = [
  /ECONNREFUSED/i,
  /ETIMEDOUT/i,
  /fetch failed/i,
  /network/i,
  /\b429\b/,
  /\b502\b/,
  /\b503\b/,
  /\b504\b/,
  /rate limit/i,
  /temporary unavailable/i,
  /provider busy/i,
  /upstream unavailable/i,
];

/**
 * 创建发送期的恢复配置。
 * 当前先提供一份可扩展默认值，后续再接 IDE 设置持久化配置。
 *
 * @param {object} rawConfig 原始配置
 * @returns {object} 规范化配置
 */
export function createCodexRecoveryConfig(rawConfig = {}) {
  const maxAttempts = Number.isInteger(rawConfig.maxAttempts) && rawConfig.maxAttempts > 0
    ? rawConfig.maxAttempts
    : 2;
  const initialDelayMs = Number.isInteger(rawConfig.initialDelayMs) && rawConfig.initialDelayMs >= 0
    ? rawConfig.initialDelayMs
    : 1200;
  const backoffMultiplier = typeof rawConfig.backoffMultiplier === 'number' && rawConfig.backoffMultiplier >= 1
    ? rawConfig.backoffMultiplier
    : 2;

  return {
    enabled: rawConfig.enabled !== false,
    recoverCompletedOnParseNoise: rawConfig.recoverCompletedOnParseNoise !== false,
    retryTransientErrors: rawConfig.retryTransientErrors !== false,
    maxAttempts,
    initialDelayMs,
    backoffMultiplier,
  };
}

/**
 * 构造失败证据对象。
 *
 * @param {object} params 采集到的上下文信息
 * @returns {object} 失败证据
 */
export function createFailureEvidence(params = {}) {
  const assistantText = typeof params.assistantText === 'string' ? params.assistantText : '';
  const finalResponse = typeof params.finalResponse === 'string' ? params.finalResponse : '';
  const toolResultCount = Number.isInteger(params.toolResultCount) ? params.toolResultCount : 0;

  return {
    provider: 'codex',
    phase: params.phase || 'send',
    rawErrorMessage: params.rawErrorMessage || '',
    errorName: params.errorName || 'Error',
    lastNodeError: params.lastNodeError || '',
    exitCode: Number.isInteger(params.exitCode) ? params.exitCode : null,
    wasInterrupted: params.wasInterrupted === true,
    hasMessageEnd: params.hasMessageEnd === true,
    hasFinalResponse: finalResponse.trim().length > 0,
    hasAssistantText: assistantText.trim().length > 0,
    hasToolResults: toolResultCount > 0,
    assistantText,
    finalResponse,
    toolResultCount,
    retryAttempt: Number.isInteger(params.retryAttempt) ? params.retryAttempt : 0,
  };
}

/**
 * 基于失败证据分类。
 *
 * @param {object} evidence 失败证据
 * @returns {string} 失败分类
 */
export function classifyCodexFailure(evidence) {
  if (!evidence || typeof evidence !== 'object') {
    return 'non_retryable';
  }

  if (evidence.wasInterrupted) {
    return 'user_interrupted';
  }

  const combinedMessage = `${evidence.rawErrorMessage || ''}\n${evidence.lastNodeError || ''}`;

  if (looksLikeWindowsTerminationNoise(combinedMessage)) {
    if (evidence.hasMessageEnd || evidence.hasFinalResponse || evidence.hasAssistantText || evidence.hasToolResults) {
      return 'runtime_terminated_after_success';
    }
    return 'parse_noise_ignorable';
  }

  if (TRANSIENT_NETWORK_PATTERNS.some((pattern) => pattern.test(combinedMessage))) {
    return 'transient_retryable';
  }

  if (/parse/i.test(combinedMessage)) {
    return 'provider_parse_error';
  }

  return 'non_retryable';
}

/**
 * 根据分类和配置计算恢复动作。
 *
 * @param {object} evidence 失败证据
 * @param {object} config 恢复配置
 * @returns {object} 恢复决策
 */
export function resolveCodexRecoveryAction(evidence, config = createCodexRecoveryConfig()) {
  const normalizedConfig = createCodexRecoveryConfig(config);
  const category = classifyCodexFailure(evidence);

  if (!normalizedConfig.enabled) {
    return {
      category,
      action: 'final_error',
      shouldRetry: false,
      shouldPromoteToCompleted: false,
      delayMs: 0,
    };
  }

  if (category === 'user_interrupted') {
    return {
      category,
      action: 'mark_cancelled',
      shouldRetry: false,
      shouldPromoteToCompleted: false,
      delayMs: 0,
    };
  }

  if (
    normalizedConfig.recoverCompletedOnParseNoise &&
    (category === 'runtime_terminated_after_success' || category === 'parse_noise_ignorable') &&
    (evidence.hasMessageEnd || evidence.hasFinalResponse || evidence.hasAssistantText || evidence.hasToolResults)
  ) {
    return {
      category,
      action: 'promote_to_completed',
      shouldRetry: false,
      shouldPromoteToCompleted: true,
      delayMs: 0,
    };
  }

  if (normalizedConfig.retryTransientErrors && category === 'transient_retryable') {
    const nextAttempt = (evidence.retryAttempt || 0) + 1;
    const retryAllowed = nextAttempt < normalizedConfig.maxAttempts;
    const delayMs = Math.round(
      normalizedConfig.initialDelayMs * Math.pow(normalizedConfig.backoffMultiplier, evidence.retryAttempt || 0)
    );
    return {
      category,
      action: retryAllowed ? 'retry_with_backoff' : 'final_error',
      shouldRetry: retryAllowed,
      shouldPromoteToCompleted: false,
      delayMs: retryAllowed ? delayMs : 0,
    };
  }

  return {
    category,
    action: 'final_error',
    shouldRetry: false,
    shouldPromoteToCompleted: false,
    delayMs: 0,
  };
}

/**
 * 判断本次错误是否应当转为成功完成。
 *
 * @param {object} evidence 失败证据
 * @param {object} config 恢复配置
 * @returns {boolean} 是否转完成
 */
export function shouldPromoteRecoveryToCompleted(evidence, config) {
  return resolveCodexRecoveryAction(evidence, config).shouldPromoteToCompleted;
}

/**
 * 异步等待重试延迟。
 *
 * @param {number} delayMs 延迟毫秒数
 * @returns {Promise<void>} 延迟 Promise
 */
export function waitForRecoveryRetry(delayMs) {
  if (!Number.isFinite(delayMs) || delayMs <= 0) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    setTimeout(resolve, delayMs);
  });
}
