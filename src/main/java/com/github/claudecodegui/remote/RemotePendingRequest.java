package com.github.claudecodegui.remote;

import com.google.gson.JsonObject;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 单个远程待处理请求的统一描述。
 * 后续无论来自 Webview 还是 Telegram，都通过同一条 completion 路径收口。
 */
public final class RemotePendingRequest {

    private final String requestId;
    private final RemoteRequestType requestType;
    private final String sessionId;
    private final String projectPath;
    private final JsonObject payload;
    private final Consumer<JsonObject> completer;
    private final long createdAt;

    public RemotePendingRequest(
        String requestId,
        RemoteRequestType requestType,
        String sessionId,
        String projectPath,
        JsonObject payload,
        Consumer<JsonObject> completer
    ) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.requestType = Objects.requireNonNull(requestType, "requestType");
        this.sessionId = sessionId;
        this.projectPath = projectPath;
        this.payload = payload == null ? new JsonObject() : payload.deepCopy();
        this.completer = Objects.requireNonNull(completer, "completer");
        this.createdAt = System.currentTimeMillis();
    }

    public String getRequestId() {
        return requestId;
    }

    public RemoteRequestType getRequestType() {
        return requestType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public JsonObject getPayload() {
        return payload.deepCopy();
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void complete(JsonObject result) {
        completer.accept(result == null ? new JsonObject() : result.deepCopy());
    }
}
