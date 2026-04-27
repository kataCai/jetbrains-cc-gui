package com.github.claudecodegui.remote.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ?????????
 * ??????????????????????????????????? ring buffer?
 */
public final class RemoteCollabDebugSnapshot {

    private final List<JsonObject> recentRequests;
    private final List<JsonObject> recentErrors;
    private final List<JsonObject> recentActions;

    public RemoteCollabDebugSnapshot(List<JsonObject> recentRequests, List<JsonObject> recentErrors, List<JsonObject> recentActions) {
        this.recentRequests = freeze(recentRequests);
        this.recentErrors = freeze(recentErrors);
        this.recentActions = freeze(recentActions);
    }

    public List<JsonObject> getRecentRequests() {
        return copy(recentRequests);
    }

    public List<JsonObject> getRecentErrors() {
        return copy(recentErrors);
    }

    public List<JsonObject> getRecentActions() {
        return copy(recentActions);
    }

    public JsonObject toJson() {
        JsonObject snapshot = new JsonObject();
        snapshot.add("recentRequests", toJsonArray(recentRequests));
        snapshot.add("recentErrors", toJsonArray(recentErrors));
        snapshot.add("recentActions", toJsonArray(recentActions));
        return snapshot;
    }

    private static List<JsonObject> freeze(List<JsonObject> source) {
        return Collections.unmodifiableList(copy(source));
    }

    private static List<JsonObject> copy(List<JsonObject> source) {
        List<JsonObject> copied = new ArrayList<>();
        if (source == null) {
            return copied;
        }
        for (JsonObject item : source) {
            copied.add(item == null ? new JsonObject() : item.deepCopy());
        }
        return copied;
    }

    private static JsonArray toJsonArray(List<JsonObject> source) {
        JsonArray array = new JsonArray();
        for (JsonObject item : source) {
            array.add(item == null ? new JsonObject() : item.deepCopy());
        }
        return array;
    }
}
