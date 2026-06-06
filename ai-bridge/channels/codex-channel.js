/**
 * Codex channel command handler – keeps Codex specific logic separated.
 */
import { sendMessage as codexSendMessage } from '../services/codex/message-service.js';
import { getMcpServerTools as codexGetMcpServerTools } from '../services/codex/message-service.js';

/**
 * 执行 Codex 通道命令，并在入口层完成发送参数的归一化。
 * 这里的核心约束是：Java 侧已经解析好的 runtime profile 不能在 Node 入口层再次丢失，
 * 尤其是 requestMode、baseUrl、apiKey 等决定真实路由与认证的字段。
 *
 * @param {string} command 命令名称
 * @param {string[]} args 命令行参数兜底值
 * @param {object|null} stdinData Java bridge 通过 stdin 传入的结构化数据
 * @returns {Promise<void>} 命令执行完成后返回
 */
export async function handleCodexCommand(command, args, stdinData) {
  switch (command) {
    case 'send': {
      if (stdinData && stdinData.message !== undefined) {
        await codexSendMessage(normalizeCodexSendRequest(stdinData));
      } else {
        await codexSendMessage(normalizeCodexSendRequest({
          message: args[0],
          threadId: args[1],
          cwd: args[2],
          permissionMode: args[3],
          model: args[4],
        }));
      }
      break;
    }

    case 'getMcpServerTools': {
      const serverId = stdinData?.serverId || args[0] || null;
      const serverConfig = stdinData?.serverConfig || null;
      await codexGetMcpServerTools(serverId, serverConfig);
      break;
    }

    default:
      throw new Error(`Unknown Codex command: ${command}`);
  }
}

export function getCodexCommandList() {
  return ['send', 'getMcpServerTools'];
}

/**
 * 统一把 Java 侧传来的 stdin 输入归一化为结构化请求对象。
 * 这样可以确保 requestMode、endpoint、凭证等运行时字段不会在 Node 入口层丢失。
 *
 * @param {object|null|undefined} stdinData Java bridge 透传的原始输入
 * @returns {object} 发送层可直接消费的结构化请求
 */
export function normalizeCodexSendRequest(stdinData) {
  const {
    message,
    threadId,
    cwd,
    permissionMode,
    providerId,
    authMode,
    model,
    baseUrl,
    apiKey,
    reasoningEffort,
    requestMode,
    credentialSource,
    baseUrlSource,
    effectiveConfigSource,
    fallbackDetected,
    localCodexModelProvider,
    attachments,
    recoveryConfig,
  } = stdinData || {};

  return {
    message: message || '',
    threadId: threadId || '',
    cwd: cwd || '',
    permissionMode: permissionMode || '',
    runtimeProfile: {
      providerId: providerId || '',
      authMode: authMode || '',
      model: model || '',
      baseUrl: baseUrl || '',
      apiKey: apiKey || '',
      reasoningEffort: reasoningEffort === 'max' ? 'xhigh' : (reasoningEffort || 'medium'),
      requestMode: requestMode || 'codex_sdk',
      credentialSource: credentialSource || '',
      baseUrlSource: baseUrlSource || (baseUrl ? 'provider' : 'sdk_default'),
      effectiveConfigSource: effectiveConfigSource || '',
      fallbackDetected: fallbackDetected === true,
      localCodexModelProvider: localCodexModelProvider || '',
    },
    attachments: attachments || [],
    recoveryConfig: recoveryConfig || {},
  };
}
