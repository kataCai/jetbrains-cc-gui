package com.github.claudecodegui.taskstate;

/**
 * 后端聚合后的统一任务状态。
 * 这些状态不是直接暴露给某个单一 provider 的原始事件，
 * 而是给提醒系统、状态栏和前端提示带使用的“跨 provider 中间语义”。
 */
public enum TaskState {
    // 尚未开始发送，或旧会话已清理完毕。
    PENDING("pending"),
    // 请求已经发出，任务正在正常执行。
    RUNNING("running"),
    // 正在等待用户审批或确认，属于需要用户介入的挂起态。
    WAITING_CONFIRM("waiting_confirm"),
    // 任务正在自动重试，通常意味着底层正在恢复而不是彻底失败。
    RETRYING("retrying"),
    // 从异常中恢复并继续执行。
    RECOVERED("recovered"),
    // 最终失败，不再继续推进。
    FINAL_ERROR("final_error"),
    // 正常完成。
    COMPLETED("completed"),
    // 用户主动中断、拒绝审批或重启导致的取消。
    CANCELLED("cancelled");

    private final String value;

    TaskState(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
