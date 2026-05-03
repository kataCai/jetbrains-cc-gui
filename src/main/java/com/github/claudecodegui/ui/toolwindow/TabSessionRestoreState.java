package com.github.claudecodegui.ui.toolwindow;

import org.jetbrains.annotations.Nullable;

/**
 * Tab 会话恢复状态。
 * 负责维护当前 Tab 待执行的会话恢复请求，统一服务于启动自动恢复与手动强制刷新后的补恢复逻辑。
 * 该类只关注“是否应该发起一次恢复”以及“恢复请求是否已经被消费”，不直接执行任何 UI 或会话加载操作。
 */
public final class TabSessionRestoreState {

    private RestoreRequest pendingRestoreRequest;

    /**
     * 根据持久化状态登记一次自动恢复请求。
     * 当 sessionId 为空时忽略，避免为无效会话创建恢复任务。
     *
     * @param sessionId 最近一次绑定的会话 ID
     * @param projectPath 会话对应的工作目录或项目路径
     */
    public void schedulePersistedRestore(@Nullable String sessionId, @Nullable String projectPath) {
        if (!isNonEmpty(sessionId)) {
            return;
        }
        pendingRestoreRequest = new RestoreRequest(sessionId.trim(), normalizePath(projectPath), false);
    }

    /**
     * 在手动强制刷新后按需登记一次恢复请求。
     * 仅当当前 Tab 仍然绑定有效会话且窗口内没有任何消息时，才补触发历史恢复，
     * 避免对已在内存中的消息做重复加载。
     *
     * @param sessionId 当前 Tab 绑定的会话 ID
     * @param projectPath 当前会话对应的工作目录或项目路径
     * @param hasMessages 当前窗口是否已经持有消息
     */
    public void scheduleManualRestoreIfNeeded(
            @Nullable String sessionId,
            @Nullable String projectPath,
            boolean hasMessages
    ) {
        if (!isNonEmpty(sessionId) || hasMessages) {
            return;
        }
        pendingRestoreRequest = new RestoreRequest(sessionId.trim(), normalizePath(projectPath), true);
    }

    /**
     * 判断当前是否存在待消费的恢复请求。
     *
     * @return 存在待恢复请求时返回 true
     */
    public boolean hasPendingRestoreRequest() {
        return pendingRestoreRequest != null;
    }

    /**
     * 消费当前待恢复请求。
     * 每次消费后都会清空内部状态，避免前端多次 ready 时重复恢复同一会话。
     *
     * @return 当前待恢复请求；若无请求则返回 null
     */
    @Nullable
    public RestoreRequest consumePendingRestoreRequest() {
        RestoreRequest request = pendingRestoreRequest;
        pendingRestoreRequest = null;
        return request;
    }

    /**
     * 判断字符串是否为非空白文本。
     *
     * @param value 待检查值
     * @return 有效非空白文本时返回 true
     */
    private boolean isNonEmpty(@Nullable String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 规范化项目路径。
     * 空字符串会被折叠为 null，避免向后续恢复链路传播无意义路径值。
     *
     * @param projectPath 原始项目路径
     * @return 规范化后的路径；若为空则返回 null
     */
    @Nullable
    private String normalizePath(@Nullable String projectPath) {
        if (projectPath == null || projectPath.trim().isEmpty()) {
            return null;
        }
        return projectPath.trim();
    }

    /**
     * 恢复请求快照。
     * 用于在前端 ready 或 WebView 重建完成后触发一次明确的历史会话加载动作。
     */
    public static final class RestoreRequest {
        private final String sessionId;
        private final String projectPath;
        private final boolean manualRefreshTriggered;

        /**
         * 创建恢复请求快照。
         *
         * @param sessionId 待恢复的会话 ID
         * @param projectPath 会话对应的项目路径
         * @param manualRefreshTriggered 是否由手动强制刷新触发
         */
        private RestoreRequest(String sessionId, @Nullable String projectPath, boolean manualRefreshTriggered) {
            this.sessionId = sessionId;
            this.projectPath = projectPath;
            this.manualRefreshTriggered = manualRefreshTriggered;
        }

        /**
         * 获取待恢复的会话 ID。
         *
         * @return 会话 ID
         */
        public String getSessionId() {
            return sessionId;
        }

        /**
         * 获取会话对应的项目路径。
         *
         * @return 项目路径；若无可用路径则返回 null
         */
        @Nullable
        public String getProjectPath() {
            return projectPath;
        }

        /**
         * 判断该恢复请求是否由手动强制刷新触发。
         *
         * @return 手动强制刷新触发时返回 true
         */
        public boolean isManualRefreshTriggered() {
            return manualRefreshTriggered;
        }
    }
}
