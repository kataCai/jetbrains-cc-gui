package com.github.claudecodegui.taskstate;

/**
 * 把后端离散生命周期事件聚合成单一任务快照。
 * 前端和提醒系统只关心“当前是什么状态、最后一次为什么变化”，
 * 不需要理解 send / approval / retry 等各类原始事件细节。
 */
public class TaskStateService {

    private TaskStateSnapshot currentSnapshot;

    /**
     * 初始化为 PENDING 状态，表示服务已准备好，但当前还没有活动任务。
     */
    public TaskStateService() {
        currentSnapshot = createSnapshot(TaskState.PENDING, null, null, "initialized");
    }

    /**
     * 返回当前聚合后的状态快照。
     * 所有调用方都只应读取快照，而不是自行保存可变状态。
     */
    public synchronized TaskStateSnapshot getCurrentSnapshot() {
        return currentSnapshot;
    }

    /**
     * 标记一次发送开始。
     */
    public synchronized void onSendStarted(String sessionId) {
        transition(TaskState.RUNNING, sessionId, null, "send_started");
    }

    /**
     * 标记一次发送正常完成。
     */
    public synchronized void onSendCompleted(String sessionId) {
        transition(TaskState.COMPLETED, sessionId, null, "send_completed");
    }

    /**
     * 标记一次发送最终失败。
     */
    public synchronized void onSendFailed(String sessionId, String reason) {
        transition(TaskState.FINAL_ERROR, sessionId, null, defaultReason(reason, "send_failed"));
    }

    /**
     * 进入等待审批状态。
     */
    public synchronized void onPlanApprovalRequested(String requestId) {
        transition(TaskState.WAITING_CONFIRM, null, requestId, "plan_approval_requested");
    }

    /**
     * 当前审批请求已被批准，任务恢复执行。
     */
    public synchronized void onPlanApprovalApproved(String requestId) {
        if (!isCurrentRequest(requestId)) {
            return;
        }
        // 审批完成后 request 生命周期结束，后续再回到 RUNNING 时不应继续挂着旧 requestId。
        transition(TaskState.RUNNING, null, null, "plan_approval_approved");
    }

    /**
     * 当前审批请求被拒绝，任务被取消。
     */
    public synchronized void onPlanApprovalRejected(String requestId, String reason) {
        if (!isCurrentRequest(requestId)) {
            return;
        }
        transition(TaskState.CANCELLED, null, requestId, defaultReason(reason, "plan_approval_rejected"));
    }

    /**
     * 当前审批请求超时，任务进入最终错误。
     */
    public synchronized void onPlanApprovalTimedOut(String requestId) {
        if (!isCurrentRequest(requestId)) {
            return;
        }
        transition(TaskState.FINAL_ERROR, null, requestId, "plan_approval_timed_out");
    }

    /**
     * 标记底层正在重试。
     */
    public synchronized void onRetrying(String sessionId, String reason) {
        transition(TaskState.RETRYING, sessionId, null, defaultReason(reason, "retrying"));
    }

    /**
     * 标记任务已经从错误态恢复。
     */
    public synchronized void onRecovered(String sessionId, String reason) {
        transition(TaskState.RECOVERED, sessionId, null, defaultReason(reason, "recovered"));
    }

    /**
     * 标记任务被取消。
     */
    public synchronized void onCancelled(String sessionId, String reason) {
        transition(TaskState.CANCELLED, sessionId, null, defaultReason(reason, "cancelled"));
    }

    public synchronized void onSessionCleared(String sessionId) {
        // 新会话建立或旧会话被清空时，显式回到 PENDING，
        // 避免上一轮错误/等待确认状态污染下一轮提醒。
        currentSnapshot = createSnapshot(TaskState.PENDING, sessionId, null, "session_cleared");
    }

    /**
     * 判断某个 requestId 是否仍然对应当前审批上下文。
     * 过期 request 的回调不应该再影响当前状态。
     */
    private boolean isCurrentRequest(String requestId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return false;
        }
        String currentRequestId = currentSnapshot.getRequestId();
        return currentRequestId == null || requestId.equals(currentRequestId);
    }

    /**
     * 统一执行状态迁移，并补齐 sessionId / requestId 的继承和清理规则。
     */
    private void transition(TaskState state, String sessionId, String requestId, String reason) {
        String effectiveSessionId = sessionId != null ? sessionId : currentSnapshot.getSessionId();
        String effectiveRequestId = requestId;

        if (effectiveRequestId == null && state == TaskState.WAITING_CONFIRM) {
            // WAITING_CONFIRM 如果没有显式传入新的 requestId，就尽量沿用当前 request，
            // 这样审批中的后续提醒仍然能定位到同一个请求。
            effectiveRequestId = currentSnapshot.getRequestId();
        }
        if (state == TaskState.RUNNING || state == TaskState.COMPLETED || state == TaskState.RETRYING
            || state == TaskState.RECOVERED || state == TaskState.PENDING) {
            // 这些状态都表示“不再卡在某个审批请求上”，因此主动清空 requestId，
            // 避免后续误把旧审批请求当成当前任务上下文。
            effectiveRequestId = null;
        }

        currentSnapshot = createSnapshot(state, effectiveSessionId, effectiveRequestId, reason);
    }

    /**
     * 基于当前信息创建新的不可变快照。
     */
    private TaskStateSnapshot createSnapshot(TaskState state, String sessionId, String requestId, String reason) {
        TaskStateEvent event = new TaskStateEvent(state, sessionId, requestId, reason, System.currentTimeMillis());
        return new TaskStateSnapshot(state, sessionId, requestId, event);
    }

    /**
     * 如果外部没有提供 reason，则使用统一 fallback，保证状态变化总有可读原因。
     */
    private String defaultReason(String reason, String fallback) {
        if (reason == null || reason.trim().isEmpty()) {
            return fallback;
        }
        return reason;
    }
}
