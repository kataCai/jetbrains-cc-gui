import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createUnsupportedRequestModeError,
  dispatchCodexRequestByMode,
} from './request-dispatcher.js';

/**
 * 验证 dispatcher 会把已落地的 `codex_sdk` 请求模式分发给对应发送器。
 * 这是 `Task 6` 的最小可运行能力，确保后续不会继续把所有模式都硬编码走同一条隐式链路。
 */
test('应把 codex_sdk 请求模式分发给标准发送器', async () => {
  const handledPayloads = [];

  const result = await dispatchCodexRequestByMode(
    {
      runtimeProfile: {
        requestMode: 'codex_sdk',
      },
    },
    {
      codex_sdk: async (payload) => {
        handledPayloads.push(payload);
        return { transport: 'codex_sdk', success: true };
      },
    },
  );

  assert.equal(handledPayloads.length, 1);
  assert.equal(handledPayloads[0].runtimeProfile.requestMode, 'codex_sdk');
  assert.deepEqual(result, { transport: 'codex_sdk', success: true });
});

/**
 * 验证未落地的请求模式不会再静默回退到 `codex_sdk`。
 * 当前阶段必须显式抛出结构化错误，避免用户误以为 `cc_switch_proxy` / `custom_adapter` 已具备独立运行能力。
 */
test('应在未实现的请求模式下抛出结构化错误而不是静默回退', async () => {
  await assert.rejects(
    () =>
      dispatchCodexRequestByMode(
        {
          runtimeProfile: {
            requestMode: 'cc_switch_proxy',
          },
        },
        {
          codex_sdk: async () => ({ success: true }),
        },
      ),
    (error) => {
      assert.equal(error.code, 'CODEX_REQUEST_MODE_UNSUPPORTED');
      assert.equal(error.requestMode, 'cc_switch_proxy');
      assert.equal(error.provider, 'codex');
      return true;
    },
  );
});

/**
 * 验证结构化错误会保留统一的错误码和请求模式信息。
 * 这样 Java / 前端层在后续接入真实多链路能力前，也能稳定识别“模式未实现”这一类错误。
 */
test('应生成包含错误码与 requestMode 的未实现模式错误对象', () => {
  const error = createUnsupportedRequestModeError('custom_adapter');

  assert.equal(error.code, 'CODEX_REQUEST_MODE_UNSUPPORTED');
  assert.equal(error.requestMode, 'custom_adapter');
  assert.equal(error.provider, 'codex');
  assert.match(error.message, /custom_adapter/);
});
