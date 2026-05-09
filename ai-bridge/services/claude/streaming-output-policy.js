/**
 * 统一流式 assistant 的完整消息输出策略。
 * 纯文本流式消息只走 delta，带 tool_use 的 assistant 才输出完整 [MESSAGE]，
 * 这样可以减少前端重复渲染，同时保留工具块所需的结构信息。
 *
 * @param {object} msg SDK 返回的消息对象
 * @param {boolean} streamingEnabled 当前 turn 是否启用流式输出
 * @returns {boolean} 是否应该输出完整 [MESSAGE]
 */
export function shouldOutputAssistantMessage(msg, streamingEnabled) {
  if (msg?.type !== 'assistant') {
    return true;
  }

  if (!streamingEnabled) {
    return true;
  }

  const content = msg?.message?.content;
  return Array.isArray(content) && content.some((block) => block?.type === 'tool_use');
}
