import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildCodexRuntimeRequest,
  buildCodexSdkOptions,
  buildCodexRuntimeDiagnostics,
  sendMessage,
} from './message-service.js';

/**
 * 验证结构化 runtime profile 会被折叠为统一请求对象。
 * 该测试覆盖默认字段补齐和关键来源字段透传，防止 requestMode、凭据来源、
 * endpoint 来源等关键信息在 Node 入口层丢失。
 */
test('应将 runtime profile 归一化为统一请求对象', () => {
  const request = buildCodexRuntimeRequest({
    message: 'ping',
    threadId: 'thread-1',
    cwd: 'E:/workspace',
    permissionMode: 'default',
    runtimeProfile: {
      model: 'gpt-5.5',
      baseUrl: 'https://example.com/v1',
      apiKey: 'secret-key',
      reasoningEffort: 'medium',
      requestMode: 'codex_sdk',
    },
    attachments: [],
    recoveryConfig: { maxAttempts: 2 },
  });

  assert.equal(request.runtimeProfile.model, 'gpt-5.5');
  assert.equal(request.runtimeProfile.baseUrl, 'https://example.com/v1');
  assert.equal(request.runtimeProfile.apiKey, 'secret-key');
  assert.equal(request.runtimeProfile.requestMode, 'codex_sdk');
  assert.equal(request.runtimeProfile.reasoningEffort, 'medium');
  assert.equal(request.runtimeProfile.providerId, '');
  assert.equal(request.runtimeProfile.authMode, '');
  assert.equal(request.runtimeProfile.credentialSource, '');
  assert.equal(request.runtimeProfile.baseUrlSource, 'provider');
  assert.equal(request.runtimeProfile.effectiveConfigSource, '');
  assert.equal(request.runtimeProfile.fallbackDetected, false);
  assert.deepEqual(request.recoveryConfig, { maxAttempts: 2 });
});

/**
 * 验证托管 provider 模式下会剔除可能把请求带回本地代理的环境变量。
 * 这是 Node 侧的最后一道防线，用于确保显式传入的 `baseUrl/apiKey`
 * 不会再被父进程中的 `OPENAI_*` / `CODEX_*` 变量污染。
 */
test('应在托管 provider 下剔除本地 OpenAI 与 Codex 污染变量', () => {
  const sdkOptions = buildCodexSdkOptions(
    {
      providerId: 'minimax-cn',
      authMode: 'api_key_env',
      model: 'gpt-5.5',
      baseUrl: 'https://example.com/v1',
      apiKey: 'secret-key',
      reasoningEffort: 'medium',
      requestMode: 'codex_sdk',
      effectiveConfigSource: 'codemoss_managed_provider',
    },
    {
      PATH: 'C:/Windows/System32',
      CODEX_SANDBOX_MODE: 'danger-full-access',
      CODEX_APPROVAL_POLICY: 'never',
      OPENAI_BASE_URL: 'https://local-config.invalid/v1',
      OPENAI_API_KEY: 'local-secret',
      CODEX_API_KEY: 'legacy-secret',
    },
  );

  assert.equal(sdkOptions.baseUrl, 'https://example.com/v1');
  assert.equal(sdkOptions.apiKey, 'secret-key');
  assert.equal(sdkOptions.env.PATH, 'C:/Windows/System32');
  assert.equal('OPENAI_BASE_URL' in sdkOptions.env, false);
  assert.equal('OPENAI_API_KEY' in sdkOptions.env, false);
  assert.equal(sdkOptions.env.CODEX_API_KEY, 'secret-key');
  assert.equal('CODEX_SANDBOX_MODE' in sdkOptions.env, false);
  assert.equal('CODEX_APPROVAL_POLICY' in sdkOptions.env, false);
});

/**
 * 验证托管 provider 模式不仅要清理环境变量，还必须显式注入 request-scoped provider 覆盖。
 * 这个测试直接覆盖本次 MiniMax 回退到本地 `~/.codex/config.toml` 的根因：
 * 如果没有同时覆盖 `model_provider` 和新 provider key，底层 CLI 仍可能继续命中用户本地默认 provider。
 */
test('应在托管 provider 模式下注入 request-scoped model_provider 覆盖', () => {
  const sdkOptions = buildCodexSdkOptions(
    {
      providerId: 'minimax-cn',
      authMode: 'api_key_env',
      model: 'MiniMax-M3',
      baseUrl: 'https://api.minimaxi.com/v1',
      apiKey: 'secret-key',
      reasoningEffort: 'medium',
      requestMode: 'codex_sdk',
      effectiveConfigSource: 'codemoss_managed_provider',
    },
    {
      PATH: 'C:/Windows/System32',
    },
  );

  assert.equal(sdkOptions.config.model_provider, 'codemoss_managed_provider');
  assert.equal(
    sdkOptions.config.model_providers.codemoss_managed_provider.name,
    'Codemoss Managed Provider',
  );
  assert.equal(
    sdkOptions.config.model_providers.codemoss_managed_provider.base_url,
    'https://api.minimaxi.com/v1',
  );
  assert.equal(
    sdkOptions.config.model_providers.codemoss_managed_provider.env_key,
    'CODEX_API_KEY',
  );
  assert.equal(
    sdkOptions.config.model_providers.codemoss_managed_provider.wire_api,
    'responses',
  );
  assert.equal(
    sdkOptions.env.CODEX_API_KEY,
    'secret-key',
  );
});

/**
 * 验证托管 provider 场景下，当前请求自己的 `CODEX_API_KEY` 必须与 request-scoped
 * `model_provider.env_key` 成对出现。
 * 这是本次 MiniMax 报 `Missing environment variable: CODEX_API_KEY` 的直接回归保护：
 * 如果 override 声明了 `env_key=CODEX_API_KEY`，但 `sdkOptions.env` 中没有同步注入，
 * 底层 Codex SDK 就会在已命中正确 provider 的情况下仍然因缺少环境变量而失败。
 */
test('应在托管 provider 模式下将当前请求的 CODEX_API_KEY 注入到 request-scoped env', () => {
  const sdkOptions = buildCodexSdkOptions(
    {
      providerId: 'minimax-cn',
      authMode: 'api_key_env',
      model: 'MiniMax-M3',
      baseUrl: 'https://api.minimaxi.com/v1',
      apiKey: 'request-secret',
      reasoningEffort: 'medium',
      requestMode: 'codex_sdk',
      effectiveConfigSource: 'codemoss_managed_provider',
    },
    {
      PATH: 'C:/Windows/System32',
      CODEX_API_KEY: 'stale-parent-secret',
      OPENAI_API_KEY: 'local-secret',
    },
  );

  assert.equal(
    sdkOptions.config.model_providers.codemoss_managed_provider.env_key,
    'CODEX_API_KEY',
  );
  assert.equal(
    sdkOptions.env.CODEX_API_KEY,
    'request-secret',
  );
  assert.equal(
    sdkOptions.apiKey,
    'request-secret',
  );
});

/**
 * 验证 CLI Login 模式仍然允许继承本地 OpenAI 环境变量。
 * 该模式本来就是为了复用用户本地 Codex 登录态，因此这里只移除插件自身注入的控制变量，
 * 不切断本地 CLI 所依赖的认证与 endpoint 环境。
 */
test('应在 CLI Login 模式下保留本地 OpenAI 环境变量', () => {
  const sdkOptions = buildCodexSdkOptions(
    {
      providerId: 'codex-cli-login',
      authMode: 'codex_cli_login',
      requestMode: 'codex_sdk',
      effectiveConfigSource: 'codex_cli_login',
    },
    {
      PATH: 'C:/Windows/System32',
      OPENAI_BASE_URL: 'https://local-config.invalid/v1',
      OPENAI_API_KEY: 'local-secret',
      CODEX_SANDBOX_MODE: 'danger-full-access',
    },
  );

  assert.equal(sdkOptions.env.OPENAI_BASE_URL, 'https://local-config.invalid/v1');
  assert.equal(sdkOptions.env.OPENAI_API_KEY, 'local-secret');
  assert.equal('CODEX_SANDBOX_MODE' in sdkOptions.env, false);
  assert.equal('config' in sdkOptions, false);
});

/**
 * 验证运行时诊断对象只输出脱敏后的来源信息。
 * 该测试用于防止诊断输出泄露明文密钥，同时确认“是否仍残留 OpenAI 环境变量”
 * 这类排障字段在过滤后仍能准确反映最终生效状态。
 */
test('应生成统一且脱敏的运行时诊断信息', () => {
  const diagnostics = buildCodexRuntimeDiagnostics(
    {
      providerId: 'minimax-cn',
      authMode: 'api_key_env',
      model: 'gpt-5.5',
      baseUrl: 'https://example.com/v1',
      apiKey: 'secret-key',
      reasoningEffort: 'medium',
      requestMode: 'codex_sdk',
      credentialSource: 'apiKeyEnv:MINIMAX_API_KEY',
      baseUrlSource: 'provider',
      effectiveConfigSource: 'codemoss_managed_provider',
      fallbackDetected: false,
      localCodexModelProvider: 'OpenAI',
    },
    {
      PATH: 'C:/Windows/System32',
      CODEX_API_KEY: 'request-secret',
    },
    ['CODEX_SANDBOX_MODE', 'OPENAI_BASE_URL'],
  );

  assert.equal(diagnostics.transport, 'codex_sdk');
  assert.equal(diagnostics.providerId, 'minimax-cn');
  assert.equal(diagnostics.authMode, 'api_key_env');
  assert.equal(diagnostics.model, 'gpt-5.5');
  assert.equal(diagnostics.resolvedBaseUrl, 'https://example.com/v1');
  assert.equal(diagnostics.endpointSource, 'provider');
  assert.equal(diagnostics.credentialSource, 'apiKeyEnv:MINIMAX_API_KEY');
  assert.equal(diagnostics.effectiveConfigSource, 'codemoss_managed_provider');
  assert.equal(diagnostics.fallbackDetected, false);
  assert.equal(diagnostics.hasApiKey, true);
  assert.equal(diagnostics.forcedModelProvider, 'codemoss_managed_provider');
  assert.equal(diagnostics.localCodexModelProvider, 'OpenAI');
  assert.equal(diagnostics.finalModelProvider, 'codemoss_managed_provider');
  assert.equal(diagnostics.localConfigConflictDetected, true);
  assert.equal(
    diagnostics.injectedConfigOverrides.model_providers.codemoss_managed_provider.env_key,
    'CODEX_API_KEY',
  );
  assert.equal(diagnostics.removedEnvKeys.length, 2);
  assert.equal(diagnostics.hasOpenaiBaseUrlEnv, false);
  assert.equal(diagnostics.hasCodexApiKeyEnv, true);
  assert.equal(diagnostics.requestScopedEnvInjected, true);
  assert.equal(diagnostics.requestScopedEnvKey, 'CODEX_API_KEY');
  assert.equal(diagnostics.hasDirectApiKeyOption, true);
  assert.equal('apiKey' in diagnostics, false);
});

/**
 * 验证未实现的请求模式会进入统一错误包装链路，而不是把异常直接抛给外层。
 * 这样 Java bridge 和前端都能拿到结构化错误对象，清楚地区分“链路异常”和“模式未实现”。
 */
test('应在未实现请求模式下输出结构化错误结果', async () => {
  const originalLog = console.log;
  const originalError = console.error;
  const logEntries = [];

  console.log = (...args) => {
    logEntries.push(args.map((item) => String(item)).join(' '));
  };
  console.error = (...args) => {
    logEntries.push(args.map((item) => String(item)).join(' '));
  };

  try {
    await sendMessage({
      message: 'ping',
      runtimeProfile: {
        requestMode: 'custom_adapter',
      },
    });
  } finally {
    console.log = originalLog;
    console.error = originalError;
  }

  const payloadLine = [...logEntries].reverse().find((entry) => entry.startsWith('{') && entry.includes('"success":false'));
  assert.ok(payloadLine, '应输出结构化错误 JSON');

  const payload = JSON.parse(payloadLine);
  assert.equal(payload.success, false);
  assert.equal(payload.details.provider, 'codex');
  assert.equal(payload.details.errorCode, 'CODEX_REQUEST_MODE_UNSUPPORTED');
  assert.equal(payload.details.requestMode, 'custom_adapter');
  assert.match(payload.error, /custom_adapter/);
});
