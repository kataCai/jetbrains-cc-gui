package com.github.claudecodegui.remote.providers.gotify;

import com.google.gson.JsonObject;

/**
 * Gotify/Web 工作台返回的单次远程动作。
 * 该模型只保留插件完成本地 pending request 所需的最小信息，避免把后台完整响应结构泄漏到上层。
 */
public final class GotifyWorkspaceAction {

    private final String actionType;
    private final JsonObject payload;

    public GotifyWorkspaceAction(String actionType, JsonObject payload) {
        this.actionType = actionType == null ? "" : actionType.trim();
        this.payload = payload == null ? new JsonObject() : payload.deepCopy();
    }

    public String getActionType() {
        return actionType;
    }

    /**
     * 返回动作负载的副本，避免调用方误改内部缓存内容。
     */
    public JsonObject getPayload() {
        return payload.deepCopy();
    }
}
