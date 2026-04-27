package com.github.claudecodegui.remote.providers.gotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 插件发送给 Gotify/Web 后端的最小请求模型。
 * 当前除了基础摘要字段，还会附带 metadata/actions/workspaceLink，
 * 这样远程工作台才能完整还原审批与问答交互，而不是只看到一段只读文本。
 */
public final class GotifyWorkspaceRequest {

    private final String requestId;
    private final String requestType;
    private final String title;
    private final String sourceQuestion;
    private final String summaryMarkdown;
    private final String workspaceLink;
    private final JsonObject metadata;
    private final JsonArray actions;

    public GotifyWorkspaceRequest(
        String requestId,
        String requestType,
        String title,
        String sourceQuestion,
        String summaryMarkdown
    ) {
        this(requestId, requestType, title, sourceQuestion, summaryMarkdown, "", new JsonObject(), new JsonArray());
    }

    /**
     * 完整请求构造器。
     * metadata/actions 会深拷贝，避免后续 provider 或轮询逻辑误改原始 payload。
     */
    public GotifyWorkspaceRequest(
        String requestId,
        String requestType,
        String title,
        String sourceQuestion,
        String summaryMarkdown,
        String workspaceLink,
        JsonObject metadata,
        JsonArray actions
    ) {
        this.requestId = requireText(requestId, "requestId");
        this.requestType = requireText(requestType, "requestType");
        this.title = requireText(title, "title");
        this.sourceQuestion = sourceQuestion == null ? "" : sourceQuestion.trim();
        this.summaryMarkdown = summaryMarkdown == null ? "" : summaryMarkdown.trim();
        this.workspaceLink = workspaceLink == null ? "" : workspaceLink.trim();
        this.metadata = metadata == null ? new JsonObject() : metadata.deepCopy();
        this.actions = actions == null ? new JsonArray() : actions.deepCopy();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getRequestType() {
        return requestType;
    }

    public String getTitle() {
        return title;
    }

    public String getSourceQuestion() {
        return sourceQuestion;
    }

    public String getSummaryMarkdown() {
        return summaryMarkdown;
    }

    public String getWorkspaceLink() {
        return workspaceLink;
    }

    public JsonObject getMetadata() {
        return metadata.deepCopy();
    }

    public JsonArray getActions() {
        return actions.deepCopy();
    }

    /**
     * 序列化为后端约定的 JSON 结构，避免 provider 层手工拼字段。
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("requestId", requestId);
        json.addProperty("requestType", requestType);
        json.addProperty("title", title);
        json.addProperty("sourceQuestion", sourceQuestion);
        json.addProperty("summaryMarkdown", summaryMarkdown);
        json.addProperty("workspaceLink", workspaceLink);
        json.add("metadata", metadata.deepCopy());
        json.add("actions", actions.deepCopy());
        return json;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
