package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.session.SessionRuntimeFamily;
import org.jetbrains.annotations.Nullable;

/**
 * Tab 会话恢复状态。
 * 负责维护当前 Tab 待执行的会话恢复请求，统一服务于启动自动恢复与手动强制刷新后的补恢复逻辑。
 * 该类只关注“是否应该发起一次恢复”以及“恢复请求是否已经被消费”，不直接执行任何 UI 或会话加载操作。
 */
public final class TabSessionRestoreState {

    public static final String RESTORE_SOURCE_STARTUP = "startup";
    public static final String RESTORE_SOURCE_HISTORY_SWITCH = "history_switch";
    public static final String RESTORE_SOURCE_MANUAL_REFRESH = "manual_refresh";

    /**
     * 显式恢复状态。
     */
    public enum RestoreLifecycleStatus {
        IDLE,
        PENDING,
        RESTORING,
        RESTORED,
        FAILED,
        CANCELLED
    }

    private RestoreRequest pendingRestoreRequest;
    private String persistedRestoreSessionId;
    private boolean restoreInProgress;
    private RestoreLifecycleStatus restoreLifecycleStatus = RestoreLifecycleStatus.IDLE;
    private String lastRestoreSource;
    private String lastRestoreRuntimeFamily;
    private String lastRestoreSessionId;
    private String lastTransitionToken;

    /**
     * 在真正执行恢复前，先根据已持久化的 sessionId 激活保护。
     * 用于拦截窗口初始化早期、restore 尚未开始前的空快照覆盖。
     *
     * @param sessionId 持久化层中已有的 sessionId
     */
    public void primePersistedRestoreProtection(@Nullable String sessionId) {
        if (!isNonEmpty(sessionId)) {
            return;
        }
        persistedRestoreSessionId = sessionId.trim();
        restoreInProgress = false;
    }

    /**
     * 根据持久化状态登记一次自动恢复请求。
     * 当 sessionId 为空时忽略，避免为无效会话创建恢复任务。
     *
     * @param sessionId 最近一次绑定的会话 ID
     * @param projectPath 会话对应的工作目录或项目路径
     */
    public void schedulePersistedRestore(@Nullable String sessionId, @Nullable String projectPath) {
        schedulePersistedRestore(sessionId, projectPath, null, null);
    }

    /**
     * 根据持久化状态登记一次自动恢复请求，并显式记录目标展示 provider 与运行时家族。
     * 该重载用于让启动恢复链路与手动历史切换链路共享同一份恢复描述，不再依赖窗口当前 UI provider。
     *
     * @param sessionId 最近一次绑定的会话 ID
     * @param projectPath 会话对应的工作目录或项目路径
     * @param displayProvider 历史展示 provider
     * @param runtimeFamily 目标运行时家族
     */
    public void schedulePersistedRestore(
            @Nullable String sessionId,
            @Nullable String projectPath,
            @Nullable String displayProvider,
            @Nullable String runtimeFamily
    ) {
        if (!isNonEmpty(sessionId)) {
            return;
        }
        persistedRestoreSessionId = sessionId.trim();
        restoreInProgress = false;
        restoreLifecycleStatus = RestoreLifecycleStatus.PENDING;
        lastRestoreSource = RESTORE_SOURCE_STARTUP;
        lastRestoreRuntimeFamily = SessionRuntimeFamily.normalize(runtimeFamily);
        lastRestoreSessionId = persistedRestoreSessionId;
        lastTransitionToken = buildTransitionToken(RESTORE_SOURCE_STARTUP, persistedRestoreSessionId);
        pendingRestoreRequest = new RestoreRequest(
                persistedRestoreSessionId,
                normalizePath(projectPath),
                normalizeValue(displayProvider),
                SessionRuntimeFamily.normalize(runtimeFamily),
                RESTORE_SOURCE_STARTUP,
                lastTransitionToken,
                false
        );
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
        scheduleManualRestoreIfNeeded(sessionId, projectPath, null, null, hasMessages);
    }

    /**
     * 在手动强制刷新后按需登记一次恢复请求，并保留当前 Tab 的展示 provider 与运行时家族。
     *
     * @param sessionId 当前 Tab 绑定的会话 ID
     * @param projectPath 当前会话对应工作目录
     * @param displayProvider 当前展示 provider
     * @param runtimeFamily 当前运行时家族
     * @param hasMessages 当前窗口是否已经持有消息
     */
    public void scheduleManualRestoreIfNeeded(
            @Nullable String sessionId,
            @Nullable String projectPath,
            @Nullable String displayProvider,
            @Nullable String runtimeFamily,
            boolean hasMessages
    ) {
        if (!isNonEmpty(sessionId) || hasMessages) {
            return;
        }
        restoreLifecycleStatus = RestoreLifecycleStatus.PENDING;
        lastRestoreSource = RESTORE_SOURCE_MANUAL_REFRESH;
        lastRestoreRuntimeFamily = SessionRuntimeFamily.normalize(runtimeFamily);
        lastRestoreSessionId = sessionId.trim();
        lastTransitionToken = buildTransitionToken(RESTORE_SOURCE_MANUAL_REFRESH, sessionId.trim());
        pendingRestoreRequest = new RestoreRequest(
                sessionId.trim(),
                normalizePath(projectPath),
                normalizeValue(displayProvider),
                SessionRuntimeFamily.normalize(runtimeFamily),
                RESTORE_SOURCE_MANUAL_REFRESH,
                lastTransitionToken,
                true
        );
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
     * 标记一次持久化恢复任务已经正式开始执行。
     * 该状态用于在恢复完成前阻止空 sessionId 快照覆盖之前保存的有效绑定。
     */
    public void markRestoreStarted() {
        if (isNonEmpty(persistedRestoreSessionId)) {
            restoreInProgress = true;
        }
        restoreLifecycleStatus = RestoreLifecycleStatus.RESTORING;
    }

    /**
     * 标记恢复任务已经完成。
     * 仅当完成结果与原恢复目标一致时才解除保护，避免无关回调误清状态。
     *
     * @param restoredSessionId 实际恢复完成后的 sessionId
     */
    public void markRestoreFinished(@Nullable String restoredSessionId) {
        restoreLifecycleStatus = RestoreLifecycleStatus.RESTORED;
        lastRestoreSessionId = normalizeValue(restoredSessionId);
        if (isNonEmpty(restoredSessionId) && restoredSessionId.trim().equals(persistedRestoreSessionId)) {
            restoreInProgress = false;
            persistedRestoreSessionId = null;
        }
    }

    /**
     * 标记恢复失败。
     */
    public void markRestoreFailed() {
        restoreInProgress = false;
        restoreLifecycleStatus = RestoreLifecycleStatus.FAILED;
    }

    /**
     * 标记恢复取消。
     */
    public void markRestoreCancelled() {
        restoreInProgress = false;
        restoreLifecycleStatus = RestoreLifecycleStatus.CANCELLED;
    }

    public RestoreLifecycleStatus getRestoreLifecycleStatus() {
        return restoreLifecycleStatus;
    }

    @Nullable
    public String getLastTransitionToken() {
        return lastTransitionToken;
    }

    @Nullable
    public String getLastRestoreSource() {
        return lastRestoreSource;
    }

    @Nullable
    public String getLastRestoreRuntimeFamily() {
        return lastRestoreRuntimeFamily;
    }

    @Nullable
    public String getLastRestoreSessionId() {
        return lastRestoreSessionId;
    }

    /**
     * 判断当前是否应阻止空 sessionId 快照覆盖旧绑定。
     * 仅在“存在持久化旧绑定 + 恢复进行中 + 新快照 sessionId 为空”时返回 true。
     *
     * @param persistedSessionId 当前持久化层已有的 sessionId
     * @param currentSessionId 当前准备写回的新快照 sessionId
     * @return 需要阻止覆盖时返回 true
     */
    public boolean shouldBlockEmptySessionSnapshotOverwrite(
            @Nullable String persistedSessionId,
            @Nullable String currentSessionId
    ) {
        return isNonEmpty(persistedRestoreSessionId)
                && isNonEmpty(persistedSessionId)
                && !isNonEmpty(currentSessionId);
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

    @Nullable
    private String normalizeValue(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private String buildTransitionToken(@Nullable String restoreSource, @Nullable String sessionId) {
        String normalizedSource = normalizeValue(restoreSource);
        String normalizedSessionId = normalizeValue(sessionId);
        return (normalizedSource != null ? normalizedSource : "restore")
                + "-" + System.currentTimeMillis()
                + "-" + (normalizedSessionId != null ? Integer.toHexString(normalizedSessionId.hashCode()) : "nosession");
    }

    /**
     * 恢复请求快照。
     * 用于在前端 ready 或 WebView 重建完成后触发一次明确的历史会话加载动作。
     */
    public static final class RestoreRequest {
        private final String sessionId;
        private final String projectPath;
        private final String displayProvider;
        private final String runtimeFamily;
        private final String restoreSource;
        private final String transitionToken;
        private final boolean manualRefreshTriggered;

        /**
         * 创建恢复请求快照。
         *
         * @param sessionId 待恢复的会话 ID
         * @param projectPath 会话对应的项目路径
         * @param manualRefreshTriggered 是否由手动强制刷新触发
         */
        private RestoreRequest(
                String sessionId,
                @Nullable String projectPath,
                @Nullable String displayProvider,
                @Nullable String runtimeFamily,
                @Nullable String restoreSource,
                @Nullable String transitionToken,
                boolean manualRefreshTriggered
        ) {
            this.sessionId = sessionId;
            this.projectPath = projectPath;
            this.displayProvider = displayProvider;
            this.runtimeFamily = runtimeFamily;
            this.restoreSource = restoreSource;
            this.transitionToken = transitionToken;
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
         * 获取恢复目标的展示 provider。
         *
         * @return 展示 provider；若未知则返回 null
         */
        @Nullable
        public String getDisplayProvider() {
            return displayProvider;
        }

        /**
         * 获取恢复目标的运行时家族。
         *
         * @return 运行时家族；若未知则返回 null
         */
        @Nullable
        public String getRuntimeFamily() {
            return runtimeFamily;
        }

        /**
         * 获取恢复来源。
         *
         * @return 恢复来源字符串
         */
        @Nullable
        public String getRestoreSource() {
            return restoreSource;
        }

        @Nullable
        public String getTransitionToken() {
            return transitionToken;
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
