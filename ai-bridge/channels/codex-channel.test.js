import test from 'node:test';
import assert from 'node:assert/strict';

import { normalizeCodexSendRequest } from './codex-channel.js';

test('应把 stdin 中的 requestMode 与 provider runtime profile 透传给发送层', () => {
  const request = normalizeCodexSendRequest({
    message: 'hello',
    threadId: 'thread-1',
    cwd: 'E:/workspace',
    permissionMode: 'acceptEdits',
    providerId: 'minimax-cn',
    authMode: 'api_key_env',
    model: 'gpt-5.5',
    baseUrl: 'https://example.com/v1',
    apiKey: 'secret-key',
    reasoningEffort: 'max',
    requestMode: 'codex_sdk',
    credentialSource: 'apiKeyEnv:MINIMAX_API_KEY',
    baseUrlSource: 'provider',
    effectiveConfigSource: 'codemoss_managed_provider',
    fallbackDetected: false,
    attachments: [{ type: 'local_image', path: 'a.png' }],
    recoveryConfig: { maxAttempts: 2 },
  });

  assert.equal(request.message, 'hello');
  assert.equal(request.threadId, 'thread-1');
  assert.equal(request.cwd, 'E:/workspace');
  assert.equal(request.permissionMode, 'acceptEdits');
  assert.equal(request.runtimeProfile.providerId, 'minimax-cn');
  assert.equal(request.runtimeProfile.authMode, 'api_key_env');
  assert.equal(request.runtimeProfile.model, 'gpt-5.5');
  assert.equal(request.runtimeProfile.baseUrl, 'https://example.com/v1');
  assert.equal(request.runtimeProfile.apiKey, 'secret-key');
  assert.equal(request.runtimeProfile.reasoningEffort, 'xhigh');
  assert.equal(request.runtimeProfile.requestMode, 'codex_sdk');
  assert.equal(request.runtimeProfile.credentialSource, 'apiKeyEnv:MINIMAX_API_KEY');
  assert.equal(request.runtimeProfile.baseUrlSource, 'provider');
  assert.equal(request.runtimeProfile.effectiveConfigSource, 'codemoss_managed_provider');
  assert.equal(request.runtimeProfile.fallbackDetected, false);
  assert.deepEqual(request.attachments, [{ type: 'local_image', path: 'a.png' }]);
  assert.deepEqual(request.recoveryConfig, { maxAttempts: 2 });
});
