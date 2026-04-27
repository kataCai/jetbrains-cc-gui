package com.github.claudecodegui.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * provider 无关的远程协作请求载荷。
 * 该模型只在插件内部使用，用于把 Telegram、Gotify/Web 等不同通道对同一待处理请求的摘要、动作和元数据表达收敛到统一结构，
 * 这样后续扩展飞书或升级后台协议时，可以复用同一套字段语义，避免各 provider 再各自维护一份提取逻辑。
 */
public final class RemoteCollabRequestEnvelope {

    private final String requestId;
    private final String requestType;
    private final String sessionId;
    private final String projectPath;
    private final String summary;
    private final String workspaceLink;
    private final JsonObject metadata;
    private final List<Action> actions;

    private RemoteCollabRequestEnvelope(
        String requestId,
        String requestType,
        String sessionId,
        String projectPath,
        String summary,
        String workspaceLink,
        JsonObject metadata,
        List<Action> actions
    ) {
        this.requestId = normalizeText(requestId);
        this.requestType = normalizeText(requestType);
        this.sessionId = normalizeText(sessionId);
        this.projectPath = normalizeText(projectPath);
        this.summary = normalizeText(summary);
        this.workspaceLink = normalizeText(workspaceLink);
        this.metadata = metadata == null ? new JsonObject() : metadata.deepCopy();
        this.actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * 从现有 RemotePendingRequest 提取统一协作载荷。
     * 当前先覆盖插件内已经落地的计划审批、提问、任务状态动作三类请求，并保留原 payload 作为 metadata 兼容后续扩展。
     */
    public static RemoteCollabRequestEnvelope fromPendingRequest(RemotePendingRequest request) {
        if (request == null) {
            return new RemoteCollabRequestEnvelope("", "review", "", "", "", "", new JsonObject(), List.of());
        }

        JsonObject payload = request.getPayload();
        JsonObject metadata = payload.deepCopy();
        List<Action> actions = new ArrayList<>();
        String requestType = mapRequestType(request.getRequestType());
        String summary = resolveSummary(request, payload, actions);
        return new RemoteCollabRequestEnvelope(
            request.getRequestId(),
            requestType,
            request.getSessionId(),
            request.getProjectPath(),
            summary,
            readString(payload, "workspaceLink"),
            metadata,
            actions
        );
    }

    private static String resolveSummary(RemotePendingRequest request, JsonObject payload, List<Action> actions) {
        if (request == null) {
            return "";
        }

        if (request.getRequestType() == RemoteRequestType.PLAN_APPROVAL) {
            actions.add(new Action("approve", "Approve", payloadOf("approved", true)));
            actions.add(new Action("reject", "Reject", payloadOf("approved", false)));
            return firstNonBlank(
                readString(payload, "title"),
                readString(payload, "summary"),
                readString(payload, "question"),
                request.getRequestId()
            );
        }

        if (request.getRequestType() == RemoteRequestType.ASK_USER_QUESTION) {
            JsonObject question = extractQuestion(payload, 0);
            JsonArray options = readOptions(question);
            boolean multiSelect = readBoolean(question, "multiSelect");
            if (!multiSelect) {
                for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                    String label = readOptionLabel(options.get(optionIndex));
                    if (!isNotBlank(label)) {
                        continue;
                    }
                    actions.add(
                        new Action(
                            "choice_" + optionIndex,
                            label,
                            payloadOf(
                                "questionIndex", 0,
                                "optionIndex", optionIndex,
                                "answer", label
                            )
                        )
                    );
                }
            }
            return firstNonBlank(
                readQuestionText(question, payload),
                readString(payload, "title"),
                request.getRequestId()
            );
        }

        if (request.getRequestType() == RemoteRequestType.TASK_STATE_ACTION) {
            return firstNonBlank(
                readString(payload, "title"),
                readString(payload, "summary"),
                readString(payload, "question"),
                request.getRequestId()
            );
        }

        return firstNonBlank(readString(payload, "summary"), request.getRequestId());
    }

    private static String mapRequestType(RemoteRequestType requestType) {
        if (requestType == null) {
            return "review";
        }
        return switch (requestType) {
            case PLAN_APPROVAL -> "plan_approval";
            case ASK_USER_QUESTION -> "ask_user_question";
            case TASK_STATE_ACTION -> "task_state_action";
        };
    }

    private static JsonObject extractQuestion(JsonObject payload, int questionIndex) {
        if (payload != null && payload.has("questions") && payload.get("questions").isJsonArray()) {
            JsonArray questions = payload.getAsJsonArray("questions");
            if (questionIndex >= 0 && questionIndex < questions.size() && questions.get(questionIndex).isJsonObject()) {
                return questions.get(questionIndex).getAsJsonObject();
            }
        }
        return payload == null ? new JsonObject() : payload;
    }

    private static JsonArray readOptions(JsonObject question) {
        if (question == null) {
            return new JsonArray();
        }
        if (question.has("options") && question.get("options").isJsonArray()) {
            return question.getAsJsonArray("options");
        }
        if (question.has("choices") && question.get("choices").isJsonArray()) {
            return question.getAsJsonArray("choices");
        }
        return new JsonArray();
    }

    private static String readQuestionText(JsonObject question, JsonObject fallbackPayload) {
        return firstNonBlank(
            readString(question, "question"),
            readString(question, "text"),
            readString(fallbackPayload, "question")
        );
    }

    private static String readOptionLabel(JsonElement option) {
        if (option == null || option.isJsonNull()) {
            return null;
        }
        if (option.isJsonPrimitive()) {
            return option.getAsString();
        }
        if (!option.isJsonObject()) {
            return null;
        }
        JsonObject optionObject = option.getAsJsonObject();
        return firstNonBlank(
            readString(optionObject, "label"),
            readString(optionObject, "value")
        );
    }

    private static String readString(JsonObject payload, String key) {
        if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
            return null;
        }
        String value = payload.get(key).getAsString();
        return normalizeText(value);
    }

    private static boolean readBoolean(JsonObject payload, String key) {
        return payload != null
            && payload.has(key)
            && !payload.get(key).isJsonNull()
            && payload.get(key).getAsBoolean();
    }

    private static JsonObject payloadOf(String booleanKey, boolean booleanValue) {
        JsonObject payload = new JsonObject();
        payload.addProperty(booleanKey, booleanValue);
        return payload;
    }

    private static JsonObject payloadOf(String keyA, Number valueA, String keyB, Number valueB, String keyC, String valueC) {
        JsonObject payload = new JsonObject();
        payload.addProperty(keyA, valueA);
        payload.addProperty(keyB, valueB);
        payload.addProperty(keyC, valueC);
        return payload;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getRequestType() {
        return requestType;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getSummary() {
        return summary;
    }

    public String getWorkspaceLink() {
        return workspaceLink;
    }

    public JsonObject getMetadata() {
        return metadata.deepCopy();
    }

    public List<Action> getActions() {
        return Collections.unmodifiableList(actions);
    }

    /**
     * 统一序列化入口，便于后续在调试页、日志或外部协议升级时直接复用同一份结构。
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("requestId", requestId);
        json.addProperty("requestType", requestType);
        json.addProperty("sessionId", sessionId);
        json.addProperty("projectPath", projectPath);
        json.addProperty("summary", summary);
        json.addProperty("workspaceLink", workspaceLink);
        json.add("metadata", metadata.deepCopy());
        JsonArray actionArray = new JsonArray();
        for (Action action : actions) {
            actionArray.add(action.toJson());
        }
        json.add("actions", actionArray);
        return json;
    }

    /**
     * 描述远程端可展示或回写的一项动作。
     * 当前 payload 主要承载选项索引等映射信息，避免 Telegram/Gotify 再重复解析原始 questions 结构。
     */
    public static final class Action {
        private final String actionId;
        private final String label;
        private final JsonObject payload;

        private Action(String actionId, String label, JsonObject payload) {
            this.actionId = normalizeText(actionId);
            this.label = normalizeText(label);
            this.payload = payload == null ? new JsonObject() : payload.deepCopy();
        }

        public String getActionId() {
            return actionId;
        }

        public String getLabel() {
            return label;
        }

        public JsonObject getPayload() {
            return payload.deepCopy();
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("actionId", actionId);
            json.addProperty("label", label);
            json.add("payload", payload.deepCopy());
            return json;
        }
    }
}
