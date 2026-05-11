import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createCodexRecoveryConfig,
  createFailureEvidence,
  resolveCodexRecoveryAction,
} from './codex-recovery-policy.js';

test('应把已有有效结果后的 Windows 终止噪音转为完成', () => {
  const config = createCodexRecoveryConfig();
  const evidence = createFailureEvidence({
    rawErrorMessage: 'Failed to parse item: SUCCESS: The process with PID 12592 (child process of PID 8096) has been terminated.',
    assistantText: '任务已经完成',
    finalResponse: '任务已经完成',
    hasMessageEnd: true,
    toolResultCount: 1,
  });

  const decision = resolveCodexRecoveryAction(evidence, config);
  assert.equal(decision.category, 'runtime_terminated_after_success');
  assert.equal(decision.action, 'promote_to_completed');
  assert.equal(decision.shouldPromoteToCompleted, true);
});

test('应把中文 Windows 环境下乱码的终止噪音转为完成', () => {
  const config = createCodexRecoveryConfig();
  const evidence = createFailureEvidence({
    rawErrorMessage: 'Failed to parse item: �J�: ���� PID 36456 (���� PID 16776 ��ɲ���ϵͳ�Լ���)',
    assistantText: '3',
    finalResponse: '3',
    hasMessageEnd: true,
    toolResultCount: 1,
  });

  const decision = resolveCodexRecoveryAction(evidence, config);
  assert.equal(decision.category, 'runtime_terminated_after_success');
  assert.equal(decision.action, 'promote_to_completed');
  assert.equal(decision.shouldPromoteToCompleted, true);
});

test('应把瞬时网络错误标记为可重试', () => {
  const config = createCodexRecoveryConfig({
    maxAttempts: 3,
    initialDelayMs: 1000,
    backoffMultiplier: 2,
  });
  const evidence = createFailureEvidence({
    rawErrorMessage: 'fetch failed with ETIMEDOUT while contacting provider',
    retryAttempt: 0,
  });

  const decision = resolveCodexRecoveryAction(evidence, config);
  assert.equal(decision.category, 'transient_retryable');
  assert.equal(decision.action, 'retry_with_backoff');
  assert.equal(decision.shouldRetry, true);
  assert.equal(decision.delayMs, 1000);
});

test('超过重试次数后应转最终失败', () => {
  const config = createCodexRecoveryConfig({
    maxAttempts: 2,
    initialDelayMs: 500,
  });
  const evidence = createFailureEvidence({
    rawErrorMessage: 'temporary unavailable',
    retryAttempt: 1,
  });

  const decision = resolveCodexRecoveryAction(evidence, config);
  assert.equal(decision.category, 'transient_retryable');
  assert.equal(decision.action, 'final_error');
  assert.equal(decision.shouldRetry, false);
});
