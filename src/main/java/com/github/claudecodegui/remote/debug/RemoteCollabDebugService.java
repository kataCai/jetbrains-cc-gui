package com.github.claudecodegui.remote.debug;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * ????????????
 * ????? ring buffer ??????????????????????????????????????
 */
public final class RemoteCollabDebugService {

    private static final int DEFAULT_REQUEST_CAPACITY = 20;
    private static final int DEFAULT_ERROR_CAPACITY = 20;
    private static final int DEFAULT_ACTION_CAPACITY = 20;

    private final int requestCapacity;
    private final int errorCapacity;
    private final int actionCapacity;
    private final Deque<JsonObject> recentRequests = new ArrayDeque<>();
    private final Deque<JsonObject> recentErrors = new ArrayDeque<>();
    private final Deque<JsonObject> recentActions = new ArrayDeque<>();

    public RemoteCollabDebugService() {
        this(DEFAULT_REQUEST_CAPACITY, DEFAULT_ERROR_CAPACITY, DEFAULT_ACTION_CAPACITY);
    }

    RemoteCollabDebugService(int requestCapacity, int errorCapacity, int actionCapacity) {
        this.requestCapacity = Math.max(1, requestCapacity);
        this.errorCapacity = Math.max(1, errorCapacity);
        this.actionCapacity = Math.max(1, actionCapacity);
    }

    public synchronized void recordTaskEvent(String providerId, RemoteTaskEvent event) {
        if (event == null) {
            return;
        }
        JsonObject record = createBaseRecord(providerId);
        record.addProperty("category", "task_event");
        record.addProperty("requestId", safe(event.getRequestId()));
        record.addProperty("sessionId", safe(event.getSessionId()));
        record.addProperty("projectPath", safe(event.getProjectPath()));
        record.addProperty("taskState", safe(event.getTaskState()));
        record.addProperty("title", safe(event.getTitle()));
        record.addProperty("summary", safe(event.getSummary()));
        append(recentRequests, requestCapacity, record);
    }

    public synchronized void recordPendingRequest(String providerId, RemotePendingRequest request) {
        if (request == null) {
            return;
        }
        JsonObject record = createBaseRecord(providerId);
        record.addProperty("category", "pending_request");
        record.addProperty("requestId", safe(request.getRequestId()));
        record.addProperty("requestType", request.getRequestType() == null ? "" : request.getRequestType().name());
        record.addProperty("sessionId", safe(request.getSessionId()));
        record.addProperty("projectPath", safe(request.getProjectPath()));
        record.addProperty("createdAt", request.getCreatedAt());
        append(recentRequests, requestCapacity, record);
    }

    public synchronized void recordError(String providerId, String phase, String message) {
        JsonObject record = createBaseRecord(providerId);
        record.addProperty("phase", safe(phase));
        record.addProperty("message", safe(message));
        append(recentErrors, errorCapacity, record);
    }

    public synchronized void recordDebugAction(RemoteCollabDebugResult result) {
        if (result == null) {
            return;
        }
        append(recentActions, actionCapacity, result.toJson());
    }

    public synchronized RemoteCollabDebugSnapshot getSnapshot() {
        return new RemoteCollabDebugSnapshot(copy(recentRequests), copy(recentErrors), copy(recentActions));
    }

    private JsonObject createBaseRecord(String providerId) {
        JsonObject record = new JsonObject();
        record.addProperty("providerId", safe(providerId));
        record.addProperty("createdAt", System.currentTimeMillis());
        return record;
    }

    private void append(Deque<JsonObject> buffer, int capacity, JsonObject item) {
        while (buffer.size() >= capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(item == null ? new JsonObject() : item.deepCopy());
    }

    private List<JsonObject> copy(Deque<JsonObject> source) {
        List<JsonObject> copied = new ArrayList<>();
        for (JsonObject item : source) {
            copied.add(item == null ? new JsonObject() : item.deepCopy());
        }
        return copied;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
