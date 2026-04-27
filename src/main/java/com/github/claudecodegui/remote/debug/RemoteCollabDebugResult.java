package com.github.claudecodegui.remote.debug;

import com.google.gson.JsonObject;

/**
 * ?????????????
 * ???? provider ??????????????????????????????
 */
public final class RemoteCollabDebugResult {

    private final String providerId;
    private final String actionKey;
    private final boolean success;
    private final String message;
    private final long createdAt;

    private RemoteCollabDebugResult(String providerId, String actionKey, boolean success, String message) {
        this.providerId = providerId == null ? "" : providerId;
        this.actionKey = actionKey == null ? "" : actionKey;
        this.success = success;
        this.message = message == null ? "" : message;
        this.createdAt = System.currentTimeMillis();
    }

    public static RemoteCollabDebugResult success(String providerId, String actionKey, String message) {
        return new RemoteCollabDebugResult(providerId, actionKey, true, message);
    }

    public static RemoteCollabDebugResult failure(String providerId, String actionKey, String message) {
        return new RemoteCollabDebugResult(providerId, actionKey, false, message);
    }

    public JsonObject toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("providerId", providerId);
        result.addProperty("actionKey", actionKey);
        result.addProperty("success", success);
        result.addProperty("message", message);
        result.addProperty("createdAt", createdAt);
        return result;
    }
}
