package com.github.claudecodegui.remote.debug;

import com.github.claudecodegui.session.SessionRuntimeFamily;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Tab 会话恢复专项调试日志工具。
 * 该工具用于把 Tab 创建、历史切换、WebView 重建、恢复请求入队与消费等关键节点统一输出为稳定字段，
 * 便于排查“窗口重建后空白”“历史绑定后未显示”“手动 Force Refresh 后丢消息”等问题。
 * <p>
 * 使用约束：
 * 1. 仅承担结构化日志拼装，不参与业务逻辑分支判断。
 * 2. 缺失字段统一输出为 `-`，降低排查时的日志解析成本。
 * 3. `runtimeFamily` 会做归一化，避免同一语义在日志里出现多种写法。
 */
public final class TabSessionRestoreDebugTrace {

    private TabSessionRestoreDebugTrace() {
    }

    /**
     * 输出普通恢复跟踪日志。
     *
     * @param logger 目标日志器
     * @param event 当前事件名
     * @param tabIndex Tab 索引
     * @param sessionId 会话 ID
     * @param displayProvider 展示 provider
     * @param runtimeFamily 运行时家族
     * @param restoreSource 恢复来源
     * @param manualRefreshTriggered 是否由手动刷新触发
     * @param transitionToken 过渡链路标识
     */
    public static void info(
            Logger logger,
            String event,
            int tabIndex,
            @Nullable String sessionId,
            @Nullable String displayProvider,
            @Nullable String runtimeFamily,
            @Nullable String restoreSource,
            boolean manualRefreshTriggered,
            @Nullable String transitionToken
    ) {
        logger.info(buildMessage(event, tabIndex, sessionId, displayProvider, runtimeFamily,
                restoreSource, manualRefreshTriggered, transitionToken));
    }

    /**
     * 输出恢复链路告警日志。
     *
     * @param logger 目标日志器
     * @param event 当前事件名
     * @param tabIndex Tab 索引
     * @param sessionId 会话 ID
     * @param displayProvider 展示 provider
     * @param runtimeFamily 运行时家族
     * @param restoreSource 恢复来源
     * @param manualRefreshTriggered 是否由手动刷新触发
     * @param transitionToken 过渡链路标识
     * @param error 可选错误描述
     */
    public static void warn(
            Logger logger,
            String event,
            int tabIndex,
            @Nullable String sessionId,
            @Nullable String displayProvider,
            @Nullable String runtimeFamily,
            @Nullable String restoreSource,
            boolean manualRefreshTriggered,
            @Nullable String transitionToken,
            @Nullable String error
    ) {
        String message = buildMessage(event, tabIndex, sessionId, displayProvider, runtimeFamily,
                restoreSource, manualRefreshTriggered, transitionToken);
        if (error != null && !error.trim().isEmpty()) {
            message = message + ", error=" + error.trim();
        }
        logger.warn(message);
    }

    /**
     * 构造统一格式的恢复跟踪日志文本。
     *
     * @param event 当前事件名
     * @param tabIndex Tab 索引
     * @param sessionId 会话 ID
     * @param displayProvider 展示 provider
     * @param runtimeFamily 运行时家族
     * @param restoreSource 恢复来源
     * @param manualRefreshTriggered 是否由手动刷新触发
     * @param transitionToken 过渡链路标识
     * @return 可直接写入日志的结构化文本
     */
    public static String buildMessage(
            String event,
            int tabIndex,
            @Nullable String sessionId,
            @Nullable String displayProvider,
            @Nullable String runtimeFamily,
            @Nullable String restoreSource,
            boolean manualRefreshTriggered,
            @Nullable String transitionToken
    ) {
        return "[TAB_SESSION_RESTORE_TRACE] event=" + safe(event)
                + ", tabIndex=" + tabIndex
                + ", sessionId=" + safe(sessionId)
                + ", displayProvider=" + safe(displayProvider)
                + ", runtimeFamily=" + safe(SessionRuntimeFamily.normalize(runtimeFamily))
                + ", restoreSource=" + safe(restoreSource)
                + ", manualRefreshTriggered=" + manualRefreshTriggered
                + ", transitionToken=" + safe(transitionToken);
    }

    private static String safe(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return "-";
        }
        return value.trim();
    }
}
