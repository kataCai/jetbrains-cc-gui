package com.github.claudecodegui.remote.provider;

/**
 * 远程协作方案能力枚举。
 * 用于描述某个 provider 当前支持哪些能力，避免调用方把 Telegram / GotifyWeb 的特性写死在主链路里。
 */
public enum RemoteCollabCapability {
    TASK_EVENT_PUSH,
    PENDING_REQUEST_PUSH,
    BINDING,
    INLINE_ACTION_CALLBACK,
    RESULT_POLLING,
    HEALTH_CHECK,
    WORKSPACE_LINK
}
