package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.file.Path;

/**
 * 负责把统一任务事件和待处理请求转换成 Telegram Bot API 可发送的消息。
 * 这里约束“远程展示文案”的唯一出口，避免不同调用方各自拼 Markdown 导致按钮协议和文本格式不一致。
 */
public class TelegramMessageFormatter {

    private static final String PARSE_MODE_MARKDOWN = "Markdown";

    /**
     * 格式化普通任务状态事件。
     * 这类消息以“当前进行到哪一步”为主，不携带可点击操作按钮。
     */
    public TelegramOutgoingMessage formatTaskEvent(RemoteTaskEvent event) {
        if (event == null) {
            return null;
        }

        StringBuilder text = new StringBuilder("*CC GUI Task Update*");
        appendLine(text, "Status", resolveTaskStateLabel(event.getTaskState()));
        appendLine(text, "Project", extractProjectName(event.getProjectPath()));
        appendLine(text, "Session", event.getSessionId());
        appendLine(text, "Summary", event.getSummary());

        JsonObject replyMarkup = null;
        if ("waiting_confirm".equals(event.getTaskState()) && isNotBlank(event.getRequestId())) {
            replyMarkup = createInlineKeyboard(
                button("Continue", "tg1:continue:" + event.getRequestId()),
                button("Cancel", "tg1:cancel:" + event.getRequestId())
            );
        }
        return new TelegramOutgoingMessage(text.toString(), PARSE_MODE_MARKDOWN, replyMarkup);
    }

    /**
     * 格式化待处理请求。
     * 对 PLAN_APPROVAL / ASK_USER_QUESTION 会自动补齐 inline keyboard，保证手机端可直接操作。
     */
    public TelegramOutgoingMessage formatPendingRequest(RemotePendingRequest request) {
        if (request == null) {
            return null;
        }

        JsonObject payload = request.getPayload();
        StringBuilder text = new StringBuilder("*CC GUI Action Required*");
        appendLine(text, "Type", resolveRequestTypeLabel(request.getRequestType()));
        appendLine(text, "Project", extractProjectName(request.getProjectPath()));
        appendLine(text, "Session", request.getSessionId());

        JsonObject replyMarkup = null;
        if (request.getRequestType() == RemoteRequestType.PLAN_APPROVAL) {
            appendLine(text, "Title", readString(payload, "title"));
            replyMarkup = createInlineKeyboard(
                button("Approve", "tg1:approve:" + request.getRequestId()),
                button("Reject", "tg1:reject:" + request.getRequestId())
            );
        } else if (request.getRequestType() == RemoteRequestType.ASK_USER_QUESTION) {
            JsonObject question = extractSingleQuestion(payload);
            appendLine(text, "Request ID", request.getRequestId());
            appendLine(text, "Question", readQuestionText(question, payload));
            replyMarkup = createAskReplyMarkup(request.getRequestId(), question);
        } else {
            appendLine(text, "Request ID", request.getRequestId());
        }
        return new TelegramOutgoingMessage(text.toString(), PARSE_MODE_MARKDOWN, replyMarkup);
    }

    private String resolveTaskStateLabel(String taskState) {
        return switch (taskState == null ? "" : taskState) {
            case "completed" -> "Completed";
            case "final_error" -> "Failed";
            case "waiting_confirm" -> "Waiting for confirmation";
            case "cancelled" -> "Cancelled";
            default -> "Updated";
        };
    }

    private String resolveRequestTypeLabel(RemoteRequestType requestType) {
        if (requestType == RemoteRequestType.PLAN_APPROVAL) {
            return "Plan approval";
        }
        if (requestType == RemoteRequestType.ASK_USER_QUESTION) {
            return "Question";
        }
        return "Remote action";
    }

    private String extractProjectName(String projectPath) {
        if (!isNotBlank(projectPath)) {
            return null;
        }
        try {
            Path path = Path.of(projectPath);
            Path fileName = path.getFileName();
            return fileName != null ? fileName.toString() : projectPath;
        } catch (Exception ignored) {
            return projectPath;
        }
    }

    private String readString(JsonObject payload, String key) {
        if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
            return null;
        }
        String value = payload.get(key).getAsString();
        return isNotBlank(value) ? value : null;
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (!isNotBlank(value)) {
            return;
        }
        builder.append('\n').append(label).append(": ").append(escapeMarkdown(value));
    }

    private JsonObject createInlineKeyboard(JsonObject... buttons) {
        JsonArray row = new JsonArray();
        for (JsonObject button : buttons) {
            row.add(button);
        }
        JsonArray rows = new JsonArray();
        rows.add(row);
        JsonObject replyMarkup = new JsonObject();
        replyMarkup.add("inline_keyboard", rows);
        return replyMarkup;
    }

    private JsonObject createAskReplyMarkup(String requestId, JsonObject question) {
        JsonArray options = readOptions(question);
        boolean multiSelect = question != null
            && question.has("multiSelect")
            && !question.get("multiSelect").isJsonNull()
            && question.get("multiSelect").getAsBoolean();
        if (options.size() > 0 && !multiSelect) {
            JsonArray row = new JsonArray();
            for (int optionIndex = 0; optionIndex < options.size(); optionIndex++) {
                String label = readOptionLabel(options.get(optionIndex));
                if (!isNotBlank(label)) {
                    continue;
                }
                row.add(button(label, "tg1:choice:" + requestId + ":0:" + optionIndex));
            }
            if (row.size() > 0) {
                JsonArray rows = new JsonArray();
                rows.add(row);
                JsonObject replyMarkup = new JsonObject();
                replyMarkup.add("inline_keyboard", rows);
                return replyMarkup;
            }
        }

        JsonObject forceReply = new JsonObject();
        forceReply.addProperty("force_reply", true);
        forceReply.addProperty("input_field_placeholder", "Reply to this message");
        return forceReply;
    }

    private JsonObject button(String text, String callbackData) {
        JsonObject button = new JsonObject();
        button.addProperty("text", text);
        button.addProperty("callback_data", callbackData);
        return button;
    }

    private JsonObject extractSingleQuestion(JsonObject payload) {
        if (payload != null && payload.has("questions") && payload.get("questions").isJsonArray()) {
            JsonArray questions = payload.getAsJsonArray("questions");
            if (questions.size() > 0 && questions.get(0).isJsonObject()) {
                return questions.get(0).getAsJsonObject();
            }
        }
        return payload;
    }

    private String readQuestionText(JsonObject question, JsonObject fallbackPayload) {
        String value = readString(question, "question");
        if (isNotBlank(value)) {
            return value;
        }
        value = readString(question, "text");
        if (isNotBlank(value)) {
            return value;
        }
        return readString(fallbackPayload, "question");
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

    private String readOptionLabel(JsonElement option) {
        if (option == null || option.isJsonNull()) {
            return null;
        }
        if (option.isJsonPrimitive()) {
            return option.getAsString();
        }
        if (!option.isJsonObject()) {
            return null;
        }
        JsonObject jsonObject = option.getAsJsonObject();
        String label = readString(jsonObject, "label");
        if (isNotBlank(label)) {
            return label;
        }
        return readString(jsonObject, "value");
    }

    private String escapeMarkdown(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("_", "\\_")
            .replace("*", "\\*")
            .replace("[", "\\[")
            .replace("`", "\\`");
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

final class TelegramOutgoingMessage {
    private final String text;
    private final String parseMode;
    private final JsonObject replyMarkup;

    TelegramOutgoingMessage(String text, String parseMode, JsonObject replyMarkup) {
        this.text = text == null ? "" : text;
        this.parseMode = parseMode;
        this.replyMarkup = replyMarkup == null ? null : replyMarkup.deepCopy();
    }

    String text() {
        return text;
    }

    String parseMode() {
        return parseMode;
    }

    JsonObject replyMarkup() {
        return replyMarkup == null ? null : replyMarkup.deepCopy();
    }
}
