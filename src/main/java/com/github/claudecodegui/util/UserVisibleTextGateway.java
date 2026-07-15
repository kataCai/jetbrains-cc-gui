package com.github.claudecodegui.util;

/**
 * 统一管理“请求原文 -> 用户可见文本”的收敛入口。
 * 该网关负责把发送给模型时拼接进去的 continuation、权限说明、skills 说明等内部上下文，
 * 统一转换为前端、历史回放、标题/摘要提取和 continued carryover 可安全复用的可见文本，
 * 避免各调用点各自直接操作 {@link UserMessageSanitizer} 而再次出现净化语义漂移。
 */
public final class UserVisibleTextGateway {

    private UserVisibleTextGateway() {
    }

    /**
     * 将一段原始请求文本收敛为允许展示给用户的文本。
     * 该入口保留 `null` 语义，便于上层在净化后为空时直接丢弃消息。
     *
     * @param rawText 可能混入内部上下文的原始请求文本
     * @return 净化后的用户可见文本；若净化后为空则返回 null
     */
    public static String toVisibleUserText(String rawText) {
        return UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(rawText);
    }

    /**
     * 将一段原始请求文本收敛为允许展示给用户的文本，并把空结果折叠为空串。
     * 该入口适用于标题、摘要、内容块重写等不方便透传 null 的链路。
     *
     * @param rawText 可能混入内部上下文的原始请求文本
     * @return 净化后的用户可见文本；若净化后为空则返回空串
     */
    public static String toVisibleUserTextOrEmpty(String rawText) {
        String sanitized = toVisibleUserText(rawText);
        return sanitized == null ? "" : sanitized;
    }

    /**
     * 将原始请求文本归一化为适合摘要、carryover 或标题候选的单行可见文本。
     * 这里会先执行统一净化，再压平换行和冗余空白，避免内部注入文本或异常换行污染摘要语义。
     *
     * @param rawText 原始请求文本
     * @return 单行用户可见文本；若净化后为空则返回空串
     */
    public static String toSingleLineVisibleSummary(String rawText) {
        String sanitized = toVisibleUserText(rawText);
        if (sanitized == null || sanitized.trim().isEmpty()) {
            return "";
        }
        return sanitized.replace("\r", " ")
                .replace("\n", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }
}
