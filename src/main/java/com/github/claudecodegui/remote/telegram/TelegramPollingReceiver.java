package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轮询 Telegram Bot 更新，并把按钮点击、文本回复重新路由回本地待处理请求。
 * 当前版本只处理 message/callback_query 两类更新，避免一次性把 polling 逻辑做得过重。
 */
public class TelegramPollingReceiver {

    private static final Logger LOG = Logger.getInstance(TelegramPollingReceiver.class);
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("(?:Request ID|requestId)[:：]\\s*([A-Za-z0-9_-]+)");

    private final CodemossSettingsService settingsService;
    private final TelegramMessageClient client;
    private final TelegramBindingService bindingService;
    private final RemoteRequestRegistry requestRegistry;
    private final JsonArray allowedUpdates;

    private volatile long nextOffset;
    private volatile boolean running;
    private volatile ExecutorService executorService;
    private volatile Future<?> pollingFuture;

    public TelegramPollingReceiver(
        CodemossSettingsService settingsService,
        TelegramMessageClient client,
        TelegramBindingService bindingService
    ) {
        this(settingsService, client, bindingService, RemoteRequestRegistry.getGlobalInstance());
    }

    TelegramPollingReceiver(
        CodemossSettingsService settingsService,
        TelegramMessageClient client,
        TelegramBindingService bindingService,
        RemoteRequestRegistry requestRegistry
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.client = Objects.requireNonNull(client, "client");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.requestRegistry = Objects.requireNonNull(requestRegistry, "requestRegistry");
        this.allowedUpdates = new JsonArray();
        this.allowedUpdates.add("message");
        this.allowedUpdates.add("callback_query");
    }

    /**
     * 启动单线程 polling 循环。
     * 这里显式使用 daemon 线程，避免测试或 IDE 关闭时因为后台线程未退出而阻塞进程。
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        executorService = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telegram-polling-receiver");
            thread.setDaemon(true);
            return thread;
        });
        pollingFuture = executorService.submit(this::runLoop);
    }

    /**
     * 停止 polling，并尽量中断当前阻塞中的 getUpdates 调用。
     */
    public synchronized void stop() {
        running = false;
        if (pollingFuture != null) {
            pollingFuture.cancel(true);
            pollingFuture = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    long getNextOffset() {
        return nextOffset;
    }

    void pollOnce() throws IOException {
        JsonObject response = client.getUpdates(nextOffset, resolvePollTimeoutSeconds(), allowedUpdates);
        if (response == null || !response.has("result") || !response.get("result").isJsonArray()) {
            return;
        }
        for (JsonElement element : response.getAsJsonArray("result")) {
            if (!element.isJsonObject()) {
                continue;
            }
            processUpdate(element.getAsJsonObject());
        }
    }

    /**
     * 主轮询循环。
     * 单次更新处理失败不会直接杀掉整个 receiver，而是记录日志后短暂休眠，避免瞬时异常导致通道永久失效。
     */
    private void runLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (IOException e) {
                LOG.warn("[TelegramPollingReceiver] Polling failed: " + e.getMessage());
                sleepQuietly();
            } catch (Exception e) {
                LOG.warn("[TelegramPollingReceiver] Unexpected polling failure: " + e.getMessage());
                sleepQuietly();
            }
        }
    }

    /**
     * 依次处理单条 Telegram update。
     * 绑定消息优先级最高，其次是 callback，再其次是文本回复，避免 reply 文本误伤绑定流程。
     */
    private void processUpdate(JsonObject update) throws IOException {
        if (update.has("update_id") && !update.get("update_id").isJsonNull()) {
            long updateId = update.get("update_id").getAsLong();
            nextOffset = Math.max(nextOffset, updateId + 1);
        }
        if (bindingService.handleUpdate(update, settingsService, client)) {
            return;
        }
        if (handleCallbackQuery(update)) {
            return;
        }
        handleTextReply(update);
    }

    private int resolvePollTimeoutSeconds() throws IOException {
        JsonObject telegram = settingsService.getTelegramConfig();
        if (telegram.has("pollIntervalSeconds") && !telegram.get("pollIntervalSeconds").isJsonNull()) {
            return Math.max(1, telegram.get("pollIntervalSeconds").getAsInt());
        }
        return 1;
    }

    private boolean handleCallbackQuery(JsonObject update) throws IOException {
        JsonObject callbackQuery = getObject(update, "callback_query");
        if (callbackQuery == null) {
            return false;
        }

        String callbackQueryId = readString(callbackQuery, "id");
        JsonObject from = getObject(callbackQuery, "from");
        if (!isBoundUser(from)) {
            answerCallback(callbackQueryId, "This Telegram account is not bound to this IDE.");
            return true;
        }

        TelegramAction action = parseCallbackData(readString(callbackQuery, "data"));
        if (action == null) {
            answerCallback(callbackQueryId, "This action is not supported.");
            return true;
        }

        RemotePendingRequest request = requestRegistry.get(action.requestId());
        if (request == null) {
            answerCallback(callbackQueryId, "This request has expired. Please refresh in the IDE.");
            return true;
        }

        JsonObject response = buildCallbackResponse(action, request);
        if (response == null) {
            answerCallback(callbackQueryId, "This request does not support that action.");
            return true;
        }

        boolean completed = requestRegistry.complete(action.requestId(), response);
        answerCallback(
            callbackQueryId,
            completed ? action.successMessage() : "This request has expired. Please refresh in the IDE."
        );
        return true;
    }

    /**
     * 处理自由文本回复。
     * 目前只接 AskUserQuestion 的文本回答，其他类型文本消息直接忽略，避免把聊天噪音误判成业务输入。
     */
    private boolean handleTextReply(JsonObject update) throws IOException {
        JsonObject message = getObject(update, "message");
        if (message == null) {
            return false;
        }

        JsonObject from = getObject(message, "from");
        if (!isBoundUser(from)) {
            return false;
        }

        String text = readString(message, "text");
        if (!isNotBlank(text) || text.startsWith("/")) {
            return false;
        }

        RemotePendingRequest request = resolveAskRequestForTextReply(message);
        String chatId = readString(getObject(message, "chat"), "id");
        if (!isNotBlank(chatId)) {
            return false;
        }
        if (request == null) {
            client.sendMessage(chatId, "There is no active question to reply to right now.", null);
            return true;
        }

        JsonObject response = buildTextAnswerResponse(request, text);
        if (response == null) {
            client.sendMessage(chatId, "Only single-question text replies are supported for this request.", null);
            return true;
        }

        boolean completed = requestRegistry.complete(request.getRequestId(), response);
        client.sendMessage(
            chatId,
            completed ? "Reply received. The IDE will continue processing." : "This request has expired. Please refresh in the IDE.",
            null
        );
        return true;
    }

    /**
     * 当用户直接回复 Telegram 消息时，优先从 reply_to_message 中提取 requestId；
     * 提取失败时再尝试回退到“当前唯一的 AskUserQuestion 请求”，减少误匹配风险。
     */
    private RemotePendingRequest resolveAskRequestForTextReply(JsonObject message) {
        String replyRequestId = extractReplyRequestId(getObject(message, "reply_to_message"));
        if (isNotBlank(replyRequestId)) {
            RemotePendingRequest request = requestRegistry.get(replyRequestId);
            return request != null && request.getRequestType() == RemoteRequestType.ASK_USER_QUESTION ? request : null;
        }

        Collection<RemotePendingRequest> pendingRequests = requestRegistry.getAll();
        List<RemotePendingRequest> askRequests = new ArrayList<>();
        for (RemotePendingRequest pendingRequest : pendingRequests) {
            if (pendingRequest.getRequestType() == RemoteRequestType.ASK_USER_QUESTION) {
                askRequests.add(pendingRequest);
            }
        }
        return askRequests.size() == 1 ? askRequests.get(0) : null;
    }

    private String extractReplyRequestId(JsonObject replyToMessage) {
        String text = readString(replyToMessage, "text");
        if (!isNotBlank(text)) {
            return null;
        }
        Matcher matcher = REQUEST_ID_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private JsonObject buildCallbackResponse(TelegramAction action, RemotePendingRequest request) {
        if (action == null || request == null) {
            return null;
        }
        String actionCode = action.actionCode();
        if ("approve".equals(actionCode) || "continue".equals(actionCode)) {
            if (request.getRequestType() == RemoteRequestType.PLAN_APPROVAL) {
                return createPlanApprovalResult(true, "telegram_" + actionCode);
            }
            if (request.getRequestType() == RemoteRequestType.TASK_STATE_ACTION) {
                return createTaskActionResult(actionCode);
            }
            return null;
        }
        if ("reject".equals(actionCode) || "cancel".equals(actionCode)) {
            if (request.getRequestType() == RemoteRequestType.PLAN_APPROVAL) {
                return createPlanApprovalResult(false, "telegram_" + actionCode);
            }
            if (request.getRequestType() == RemoteRequestType.TASK_STATE_ACTION) {
                return createTaskActionResult(actionCode);
            }
            return null;
        }
        if ("retry".equals(actionCode)) {
            return request.getRequestType() == RemoteRequestType.TASK_STATE_ACTION
                ? createTaskActionResult(actionCode)
                : null;
        }
        if ("choice".equals(actionCode)) {
            return request.getRequestType() == RemoteRequestType.ASK_USER_QUESTION
                ? createChoiceAnswerResult(request, action.questionIndex(), action.optionIndex())
                : null;
        }
        return null;
    }

    private JsonObject buildTextAnswerResponse(RemotePendingRequest request, String text) {
        JsonObject question = extractQuestion(request.getPayload(), 0);
        if (question == null) {
            return null;
        }
        JsonArray allQuestions = getQuestionsArray(request.getPayload());
        if (allQuestions.size() > 1) {
            return null;
        }
        String questionText = readQuestionText(question);
        if (!isNotBlank(questionText)) {
            return null;
        }
        JsonObject answers = new JsonObject();
        answers.addProperty(questionText, text.trim());
        return answers;
    }

    private JsonObject createChoiceAnswerResult(RemotePendingRequest request, Integer questionIndex, Integer optionIndex) {
        if (questionIndex == null || optionIndex == null) {
            return null;
        }
        JsonObject question = extractQuestion(request.getPayload(), questionIndex);
        if (question == null) {
            return null;
        }
        JsonArray options = readOptions(question);
        if (optionIndex < 0 || optionIndex >= options.size()) {
            return null;
        }
        String questionText = readQuestionText(question);
        String optionLabel = readOptionLabel(options.get(optionIndex));
        if (!isNotBlank(questionText) || !isNotBlank(optionLabel)) {
            return null;
        }
        JsonObject answers = new JsonObject();
        answers.addProperty(questionText, optionLabel);
        return answers;
    }

    private JsonObject createPlanApprovalResult(boolean approved, String message) {
        JsonObject result = new JsonObject();
        result.addProperty("approved", approved);
        result.addProperty("targetMode", "default");
        result.addProperty("message", message);
        return result;
    }

    private JsonObject createTaskActionResult(String action) {
        JsonObject result = new JsonObject();
        result.addProperty("action", action);
        return result;
    }

    private TelegramAction parseCallbackData(String data) {
        if (!isNotBlank(data)) {
            return null;
        }
        String[] parts = data.split(":");
        if (parts.length < 3 || !"tg1".equals(parts[0])) {
            return null;
        }
        String actionCode = parts[1];
        String requestId = parts[2];
        Integer questionIndex = null;
        Integer optionIndex = null;
        if ("choice".equals(actionCode) && parts.length >= 5) {
            questionIndex = parseInteger(parts[3]);
            optionIndex = parseInteger(parts[4]);
        }
        return new TelegramAction(actionCode, requestId, questionIndex, optionIndex, resolveSuccessMessage(actionCode));
    }

    private String resolveSuccessMessage(String actionCode) {
        return switch (actionCode) {
            case "approve" -> "Plan approved.";
            case "reject" -> "Plan rejected.";
            case "continue" -> "Task resumed.";
            case "cancel" -> "Request cancelled.";
            case "retry" -> "Retry requested.";
            case "choice" -> "Answer received.";
            default -> "Action received.";
        };
    }

    private Integer parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
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
        String questionText = readString(question, "question");
        if (isNotBlank(questionText)) {
            return questionText;
        }
        return readString(question, "text");
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
        JsonObject optionObject = option.getAsJsonObject();
        String label = readString(optionObject, "label");
        if (isNotBlank(label)) {
            return label;
        }
        return readString(optionObject, "value");
    }

    private boolean isBoundUser(JsonObject from) throws IOException {
        JsonObject telegram = settingsService.getTelegramConfig();
        String boundUserId = readString(telegram, "boundUserId");
        String userId = readString(from, "id");
        return isNotBlank(boundUserId) && isNotBlank(userId) && boundUserId.equals(userId);
    }

    private JsonObject getObject(JsonObject parent, String key) {
        if (parent == null || !parent.has(key) || !parent.get(key).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(key);
    }

    private String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString();
        return isNotBlank(value) ? value.trim() : null;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void answerCallback(String callbackQueryId, String text) throws IOException {
        if (isNotBlank(callbackQueryId)) {
            client.answerCallbackQuery(callbackQueryId, text);
        }
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record TelegramAction(
        String actionCode,
        String requestId,
        Integer questionIndex,
        Integer optionIndex,
        String successMessage
    ) {
    }
}
