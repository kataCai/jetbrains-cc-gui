package com.github.claudecodegui.session;


import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Session state management.
 * Maintains all state information for a conversation session.
 */
public class SessionState {

    /**
     * Canonical whitelist of valid permission modes.
     * Shared across SessionHandler (payload validation) and ClaudeSession (mode resolution).
     */
    public static final Set<String> VALID_PERMISSION_MODES;
    static {
        Set<String> modes = new HashSet<>();
        modes.add("default");
        modes.add("plan");
        modes.add("acceptEdits");
        modes.add("autoEdit");
        modes.add("bypassPermissions");
        VALID_PERMISSION_MODES = Collections.unmodifiableSet(modes);
    }

    /**
     * Check whether the given mode string is a recognized permission mode.
     */
    public static boolean isValidPermissionMode(String mode) {
        return mode != null && VALID_PERMISSION_MODES.contains(mode.trim());
    }

    // Session identifiers
    private String sessionId;
    private String channelId;
    private volatile String runtimeSessionEpoch = UUID.randomUUID().toString();

    // Session state — accessed only on EDT / single handler thread, no volatile needed.
    private boolean busy = false;
    private boolean loading = false;
    private String error = null;

    // Message history
    private final List<ClaudeSession.Message> messages = new ArrayList<>();

    // Session metadata — cwd is written in handler thread before send(), read inside send();
    // the happens-before from CompletableFuture.runAsync guarantees visibility, so volatile is not required.
    private String summary = null;
    private long lastModifiedTime = System.currentTimeMillis();
    private String cwd = null;

    // Configuration fields below are volatile because set_mode / set_model / set_provider
    // and send_message may execute on different async handler threads with no other
    // happens-before guarantee between them.
    private volatile String permissionMode = "bypassPermissions";
    private volatile String model = "claude-sonnet-4-6";
    private volatile String provider = "claude";
    // Codex reasoning effort (thinking depth)
    private volatile String reasoningEffort = "medium";
    /**
     * Codex 会话绑定元数据。
     * 仅在 provider=codex 的场景下使用，用于把 threadId 与具体 provider/model/requestMode 绑定，
     * 避免继续同一条会话时被当前 active provider 污染。
     */
    private volatile CodexSessionBinding codexSessionBinding = null;
    private volatile boolean lastRecovered = false;
    private volatile String lastRecoveryCategory = null;
    private volatile String lastRecoveryAction = null;

    // Slash commands — volatile for cross-thread visibility (same reason as permissionMode/model/provider)
    private volatile List<String> slashCommands = new ArrayList<>();

    // PSI context collection toggle
    private boolean psiContextEnabled = true;

    // Getters
    public String getSessionId() {
        return sessionId;
    }

    public String getChannelId() {
        return channelId;
    }

    public boolean isBusy() {
        return busy;
    }

    public boolean isLoading() {
        return loading;
    }

    public String getError() {
        return error;
    }

    public List<ClaudeSession.Message> getMessages() {
        return new ArrayList<>(messages);
    }

    public List<ClaudeSession.Message> getMessagesReference() {
        return messages;
    }

    public String getSummary() {
        return summary;
    }

    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public String getCwd() {
        return cwd;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public String getModel() {
        return model;
    }

    public String getProvider() {
        return provider;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    /**
     * 获取当前会话记录的 Codex 绑定元数据。
     *
     * @return 绑定元数据；若当前不是 Codex 会话或尚未建立绑定则返回 null
     */
    public CodexSessionBinding getCodexSessionBinding() {
        return codexSessionBinding;
    }

    /**
     * 返回上一轮 provider 执行是否命中过恢复链路。
     * 该标记只用于一次 send 生命周期的收尾判断，读取后应尽快由上层清理，
     * 避免把上一轮恢复结果误带入下一轮任务状态收口。
     *
     * @return true 表示上一轮执行属于“恢复后完成”
     */
    public boolean isLastRecovered() {
        return lastRecovered;
    }

    /**
     * 返回上一轮恢复链路命中的失败分类。
     *
     * @return 恢复分类；若上一轮未命中恢复链路则可能为 null
     */
    public String getLastRecoveryCategory() {
        return lastRecoveryCategory;
    }

    /**
     * 返回上一轮恢复链路采取的动作。
     *
     * @return 恢复动作；若上一轮未命中恢复链路则可能为 null
     */
    public String getLastRecoveryAction() {
        return lastRecoveryAction;
    }

    public String getRuntimeSessionEpoch() {
        return runtimeSessionEpoch;
    }

    public List<String> getSlashCommands() {
        return new ArrayList<>(slashCommands);
    }



    public boolean isPsiContextEnabled() {
        return psiContextEnabled;
    }

    // Setters
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public void setBusy(boolean busy) {
        this.busy = busy;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public void setError(String error) {
        this.error = error;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public void setPermissionMode(String permissionMode) {
        if (permissionMode != null && !VALID_PERMISSION_MODES.contains(permissionMode.trim())) {
            // Reject unrecognized modes silently to prevent injection of arbitrary strings
            return;
        }
        this.permissionMode = permissionMode;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public void setReasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
    }

    /**
     * 设置当前会话的 Codex 绑定元数据。
     * 该绑定只保存最小非敏感字段，允许在恢复历史会话或继续发送时复用。
     *
     * @param codexSessionBinding 新的绑定元数据；传入 null 表示清空绑定
     */
    public void setCodexSessionBinding(CodexSessionBinding codexSessionBinding) {
        this.codexSessionBinding = codexSessionBinding;
    }

    /**
     * 记录上一轮 send 的恢复结果元信息。
     * 仅保存与任务状态收口直接相关的最小字段，避免在 SessionState 中堆积过多 provider 细节。
     *
     * @param recovered 是否命中过恢复完成路径
     * @param recoveryCategory 恢复分类
     * @param recoveryAction 恢复动作
     */
    public void setLastRecoveryMetadata(boolean recovered, String recoveryCategory, String recoveryAction) {
        this.lastRecovered = recovered;
        this.lastRecoveryCategory = recoveryCategory;
        this.lastRecoveryAction = recoveryAction;
    }

    /**
     * 清理上一轮 send 留下的恢复元信息。
     * 新一轮发送开始前和恢复状态被消费后都应调用，避免跨轮串味。
     */
    public void clearLastRecoveryMetadata() {
        this.lastRecovered = false;
        this.lastRecoveryCategory = null;
        this.lastRecoveryAction = null;
    }

    public void setRuntimeSessionEpoch(String runtimeSessionEpoch) {
        if (runtimeSessionEpoch == null || runtimeSessionEpoch.trim().isEmpty()) {
            this.runtimeSessionEpoch = UUID.randomUUID().toString();
            return;
        }
        this.runtimeSessionEpoch = runtimeSessionEpoch;
    }

    public String rotateRuntimeSessionEpoch() {
        String newEpoch = UUID.randomUUID().toString();
        this.runtimeSessionEpoch = newEpoch;
        return newEpoch;
    }

    public void setSlashCommands(List<String> slashCommands) {
        this.slashCommands = new ArrayList<>(slashCommands);
    }



    public void setPsiContextEnabled(boolean psiContextEnabled) {
        this.psiContextEnabled = psiContextEnabled;
    }

    /**
     * Add a message to the history.
     */
    public void addMessage(ClaudeSession.Message message) {
        messages.add(message);
    }

    /**
     * Clear all messages.
     */
    public void clearMessages() {
        messages.clear();
    }

    /**
     * Update the last modified time to the current time.
     */
    public void updateLastModifiedTime() {
        this.lastModifiedTime = System.currentTimeMillis();
    }
}
