package com.github.claudecodegui.remote.feishu;

import com.github.claudecodegui.remote.RemoteActionRouter;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;

/**
 * 飞书入站文本命令处理器。
 * 第一版负责把绑定后的文本命令重新路由为本地审批或问答结果，并统一处理非法操作者、重复提交和过期请求。
 */
public class FeishuEventSubscriber {

    private final CodemossSettingsService settingsService;
    private final RemoteActionRouter actionRouter;
    private final RemoteRequestRegistry requestRegistry;

    public FeishuEventSubscriber(CodemossSettingsService settingsService, RemoteRequestRegistry requestRegistry) {
        this(settingsService, new RemoteActionRouter(requestRegistry), requestRegistry);
    }

    FeishuEventSubscriber(
        CodemossSettingsService settingsService,
        RemoteActionRouter actionRouter,
        RemoteRequestRegistry requestRegistry
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.actionRouter = Objects.requireNonNull(actionRouter, "actionRouter");
        this.requestRegistry = Objects.requireNonNull(requestRegistry, "requestRegistry");
    }

    public HandleResult handleIncomingMessage(FeishuIncomingMessage message) throws IOException {
        if (message == null || isBlank(message.getText())) {
            return HandleResult.ignored();
        }
        if (!isBoundUser(message.getOpenId())) {
            return HandleResult.handled(false, "This Feishu account is not bound to this IDE.");
        }

        String text = message.getText().trim();
        if (text.startsWith("/cc-approve ")) {
            return completePlanApproval(text.substring("/cc-approve ".length()), true);
        }
        if (text.startsWith("/cc-reject ")) {
            return completePlanApproval(text.substring("/cc-reject ".length()), false);
        }
        if (text.startsWith("/cc-choice ")) {
            return completeChoiceAnswer(text.substring("/cc-choice ".length()));
        }
        if (text.startsWith("/cc-reply ")) {
            return completeTextReply(text.substring("/cc-reply ".length()));
        }
        return HandleResult.ignored();
    }

    private HandleResult completePlanApproval(String requestId, boolean approved) {
        RemotePendingRequest request = requestRegistry.get(normalize(requestId));
        if (request == null) {
            return HandleResult.handled(false, "This request has expired. Please refresh in the IDE.");
        }
        if (request.getRequestType() != RemoteRequestType.PLAN_APPROVAL) {
            return HandleResult.handled(false, "This request does not support approval actions.");
        }

        JsonObject result = new JsonObject();
        result.addProperty("approved", approved);
        result.addProperty("targetMode", "default");
        result.addProperty("message", approved ? "feishu_approve" : "feishu_reject");
        boolean completed = actionRouter.completeRequest(request.getRequestId(), result);
        return HandleResult.handled(completed, completed
            ? (approved ? "Plan approved." : "Plan rejected.")
            : "This request has expired. Please refresh in the IDE.");
    }

    private HandleResult completeChoiceAnswer(String commandBody) {
        String normalized = normalize(commandBody);
        int firstSpaceIndex = normalized.indexOf(' ');
        if (firstSpaceIndex <= 0 || firstSpaceIndex >= normalized.length() - 1) {
            return HandleResult.handled(false, "Choice command is invalid.");
        }
        String requestId = normalized.substring(0, firstSpaceIndex).trim();
        String answer = normalized.substring(firstSpaceIndex + 1).trim();
        RemotePendingRequest request = requestRegistry.get(requestId);
        if (request == null) {
            return HandleResult.handled(false, "This request has expired. Please refresh in the IDE.");
        }
        if (request.getRequestType() != RemoteRequestType.ASK_USER_QUESTION) {
            return HandleResult.handled(false, "This request does not support answer commands.");
        }
        JsonObject response = buildChoiceAnswerResult(request, answer);
        if (response == null) {
            return HandleResult.handled(false, "Choice answer is invalid for this request.");
        }
        boolean completed = actionRouter.completeRequest(request.getRequestId(), response);
        return HandleResult.handled(completed, completed
            ? "Answer received."
            : "This request has expired. Please refresh in the IDE.");
    }

    private HandleResult completeTextReply(String commandBody) {
        String normalized = normalize(commandBody);
        int firstSpaceIndex = normalized.indexOf(' ');
        if (firstSpaceIndex <= 0 || firstSpaceIndex >= normalized.length() - 1) {
            return HandleResult.handled(false, "Reply command is invalid.");
        }
        String requestId = normalized.substring(0, firstSpaceIndex).trim();
        String answer = normalized.substring(firstSpaceIndex + 1).trim();
        RemotePendingRequest request = requestRegistry.get(requestId);
        if (request == null) {
            return HandleResult.handled(false, "This request has expired. Please refresh in the IDE.");
        }
        if (request.getRequestType() != RemoteRequestType.ASK_USER_QUESTION) {
            return HandleResult.handled(false, "This request does not support text replies.");
        }
        JsonObject response = buildTextAnswerResult(request, answer);
        if (response == null) {
            return HandleResult.handled(false, "Text reply is invalid for this request.");
        }
        boolean completed = actionRouter.completeRequest(request.getRequestId(), response);
        return HandleResult.handled(completed, completed
            ? "Answer received."
            : "This request has expired. Please refresh in the IDE.");
    }

    private JsonObject buildChoiceAnswerResult(RemotePendingRequest request, String answer) {
        JsonObject question = extractQuestion(request.getPayload(), 0);
        if (question == null || isBlank(answer)) {
            return null;
        }
        JsonArray options = readOptions(question);
        String questionText = readQuestionText(question);
        for (JsonElement option : options) {
            String label = readOptionLabel(option);
            if (!isBlank(label) && label.equals(answer)) {
                JsonObject result = new JsonObject();
                result.addProperty(questionText, label);
                return result;
            }
        }
        return null;
    }

    private JsonObject buildTextAnswerResult(RemotePendingRequest request, String answer) {
        JsonObject question = extractQuestion(request.getPayload(), 0);
        if (question == null || isBlank(answer)) {
            return null;
        }
        JsonArray questions = getQuestionsArray(request.getPayload());
        if (questions.size() > 1) {
            return null;
        }
        String questionText = readQuestionText(question);
        if (isBlank(questionText)) {
            return null;
        }
        JsonObject result = new JsonObject();
        result.addProperty(questionText, answer);
        return result;
    }

    private boolean isBoundUser(String openId) throws IOException {
        JsonObject config = settingsService.getRemoteCollabProviderConfig("feishu");
        String boundOpenId = readString(config, "boundOpenId");
        return !isBlank(boundOpenId) && boundOpenId.equals(normalize(openId));
    }

    private JsonObject extractQuestion(JsonObject payload, int index) {
        JsonArray questions = getQuestionsArray(payload);
        if (index >= 0 && index < questions.size() && questions.get(index).isJsonObject()) {
            return questions.get(index).getAsJsonObject();
        }
        return null;
    }

    private JsonArray getQuestionsArray(JsonObject payload) {
        if (payload != null && payload.has("questions") && payload.get("questions").isJsonArray()) {
            return payload.getAsJsonArray("questions");
        }
        return new JsonArray();
    }

    private JsonArray readOptions(JsonObject question) {
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

    private String readQuestionText(JsonObject question) {
        String text = readString(question, "question");
        if (!isBlank(text)) {
            return text;
        }
        return readString(question, "text");
    }

    private String readOptionLabel(JsonElement option) {
        if (option == null || option.isJsonNull()) {
            return "";
        }
        if (option.isJsonPrimitive()) {
            return normalize(option.getAsString());
        }
        if (!option.isJsonObject()) {
            return "";
        }
        JsonObject optionObject = option.getAsJsonObject();
        String label = readString(optionObject, "label");
        if (!isBlank(label)) {
            return label;
        }
        return readString(optionObject, "value");
    }

    private String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return normalize(json.get(key).getAsString());
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class HandleResult {
        private final boolean handled;
        private final boolean completed;
        private final String replyText;

        private HandleResult(boolean handled, boolean completed, String replyText) {
            this.handled = handled;
            this.completed = completed;
            this.replyText = replyText == null ? "" : replyText.trim();
        }

        public static HandleResult ignored() {
            return new HandleResult(false, false, "");
        }

        public static HandleResult handled(boolean completed, String replyText) {
            return new HandleResult(true, completed, replyText);
        }

        public boolean isHandled() {
            return handled;
        }

        public boolean isCompleted() {
            return completed;
        }

        public String getReplyText() {
            return replyText;
        }
    }
}
