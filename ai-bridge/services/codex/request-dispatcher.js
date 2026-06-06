/**
 * 创建统一的“请求模式未实现”错误对象。
 * 该错误会在 Node 层保留明确错误码和 requestMode，便于上层区分“链路异常”和“模式未落地”。
 *
 * @param {string} requestMode 当前请求模式
 * @returns {Error} 带结构化字段的错误对象
 */
export function createUnsupportedRequestModeError(requestMode) {
  const mode = requestMode || 'unknown';
  const error = new Error(`Codex request mode "${mode}" is not implemented yet.`);
  error.code = 'CODEX_REQUEST_MODE_UNSUPPORTED';
  error.provider = 'codex';
  error.requestMode = mode;
  return error;
}

/**
 * `codex_sdk` 标准发送器。
 * 当前版本的真实可运行链路仍然只有这一种模式，因此这里显式要求传入执行器，
 * 由调用方把现有 SDK 发送细节挂接到统一 dispatcher 上，避免再次退化为隐式直连。
 *
 * @param {object} request 当前结构化请求
 * @param {(request: object) => Promise<any>} executor 实际的 SDK 发送执行器
 * @returns {Promise<any>} SDK 发送结果
 */
export async function sendViaCodexSdk(request, executor) {
  if (typeof executor !== 'function') {
    throw new Error('Codex SDK sender requires an executor function.');
  }
  return executor(request);
}

/**
 * `cc_switch_proxy` 的预留发送器。
 * 当前版本先显式抛出未实现错误，防止该模式静默回退到 `codex_sdk` 造成误判。
 *
 * @param {object} request 当前结构化请求
 * @returns {Promise<never>} 始终抛出未实现错误
 */
export async function sendViaCcSwitchProxy(request) {
  throw createUnsupportedRequestModeError(request?.runtimeProfile?.requestMode || 'cc_switch_proxy');
}

/**
 * `custom_adapter` 的预留发送器。
 * 当前版本先显式抛出未实现错误，后续实现真实 adapter 分发时可以直接替换该函数体。
 *
 * @param {object} request 当前结构化请求
 * @returns {Promise<never>} 始终抛出未实现错误
 */
export async function sendViaCustomAdapter(request) {
  throw createUnsupportedRequestModeError(request?.runtimeProfile?.requestMode || 'custom_adapter');
}

/**
 * 根据 `requestMode` 选择对应的发送器。
 * 这是 Codex 多链路能力的统一入口，要求每种模式都必须显式注册，禁止隐式回退。
 *
 * @param {object} request 当前结构化请求
 * @param {Record<string, Function>} handlers 各请求模式对应的发送器
 * @returns {Promise<any>} 具体发送器的执行结果
 */
export async function dispatchCodexRequestByMode(request, handlers = {}) {
  const requestMode = request?.runtimeProfile?.requestMode || 'codex_sdk';
  const handler = handlers[requestMode];

  if (typeof handler !== 'function') {
    throw createUnsupportedRequestModeError(requestMode);
  }

  return handler(request);
}
