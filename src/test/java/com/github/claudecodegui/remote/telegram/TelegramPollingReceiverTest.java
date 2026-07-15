package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TelegramPollingReceiverTest {

    @Test
    public void shouldConsumeBindingStartUpdateAndAdvanceOffset() throws Exception {
        JsonObject telegram = new JsonObject();
        telegram.addProperty("botToken", "bot-token");
        telegram.addProperty("bindingToken", "bind-token");
        telegram.addProperty("pollIntervalSeconds", 1);
        telegram.addProperty("chatId", "");
        telegram.addProperty("boundUserId", "");
        telegram.addProperty("boundUsername", "");
        telegram.addProperty("botUsername", "cc_gui_bot");
        telegram.addProperty("connectionStatus", "connecting");
        telegram.addProperty("lastError", "");
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient(createUpdatesResponse(5, "/start bind-token"));
        TelegramBindingService bindingService = new TelegramBindingService(() -> "ignored");
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(settingsService, client, bindingService);

        receiver.pollOnce();

        assertEquals(6L, receiver.getNextOffset());
        assertEquals(1, client.getUpdatesCalls.get());
        JsonObject saved = settingsService.getTelegramConfig();
        assertEquals("42", saved.get("chatId").getAsString());
        assertEquals("7", saved.get("boundUserId").getAsString());
        assertEquals("alice", saved.get("boundUsername").getAsString());
        assertEquals("connected", saved.get("connectionStatus").getAsString());
    }

    @Test
    public void shouldRequestMessageAndCallbackQueryUpdates() throws Exception {
        JsonObject telegram = new JsonObject();
        telegram.addProperty("botToken", "bot-token");
        telegram.addProperty("bindingToken", "");
        telegram.addProperty("pollIntervalSeconds", 1);
        telegram.addProperty("chatId", "");
        telegram.addProperty("boundUserId", "");
        telegram.addProperty("boundUsername", "");
        telegram.addProperty("botUsername", "cc_gui_bot");
        telegram.addProperty("connectionStatus", "disconnected");
        telegram.addProperty("lastError", "");
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient("{\"ok\":true,\"result\":[]}");
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(
            settingsService,
            client,
            new TelegramBindingService(() -> "ignored")
        );

        receiver.pollOnce();

        assertEquals(2, client.lastAllowedUpdates.size());
        assertEquals("message", client.lastAllowedUpdates.get(0).getAsString());
        assertEquals("callback_query", client.lastAllowedUpdates.get(1).getAsString());
        assertTrue(receiver.getNextOffset() >= 0);
    }

    @Test
    public void shouldCompletePlanApprovalWhenApproveCallbackArrives() throws Exception {
        JsonObject telegram = createBoundTelegramConfig();
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient(
            "{\"ok\":true,\"result\":[{\"update_id\":8,\"callback_query\":{\"id\":\"cb-1\","
                + "\"data\":\"tg1:approve:req-plan\",\"from\":{\"id\":7,\"username\":\"alice\"},"
                + "\"message\":{\"message_id\":11,\"chat\":{\"id\":42},\"text\":\"plan\"}}}]}"
        );
        RemoteRequestRegistry requestRegistry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();
        requestRegistry.register(new RemotePendingRequest(
            "req-plan",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "E:/workspace/demo-project",
            new JsonObject(),
            results::add
        ));
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(
            settingsService,
            client,
            new TelegramBindingService(() -> "ignored"),
            requestRegistry
        );

        receiver.pollOnce();

        assertEquals(9L, receiver.getNextOffset());
        assertEquals(1, results.size());
        assertTrue(results.get(0).get("approved").getAsBoolean());
        assertEquals("default", results.get(0).get("targetMode").getAsString());
        assertEquals(1, client.callbackAnswers.size());
        assertTrue(client.callbackAnswers.get(0).text.contains("approved"));
    }

    @Test
    public void shouldCompleteAskQuestionChoiceWhenCallbackArrives() throws Exception {
        JsonObject telegram = createBoundTelegramConfig();
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient(
            "{\"ok\":true,\"result\":[{\"update_id\":9,\"callback_query\":{\"id\":\"cb-2\","
                + "\"data\":\"tg1:choice:req-ask:0:1\",\"from\":{\"id\":7,\"username\":\"alice\"},"
                + "\"message\":{\"message_id\":12,\"chat\":{\"id\":42},\"text\":\"ask\"}}}]}"
        );
        RemoteRequestRegistry requestRegistry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();
        requestRegistry.register(new RemotePendingRequest(
            "req-ask",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-1",
            "E:/workspace/demo-project",
            createSingleChoiceAskPayload(),
            results::add
        ));
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(
            settingsService,
            client,
            new TelegramBindingService(() -> "ignored"),
            requestRegistry
        );

        receiver.pollOnce();

        assertEquals(1, results.size());
        assertEquals("Cancel", results.get(0).get("Choose how to proceed").getAsString());
        assertEquals(1, client.callbackAnswers.size());
        assertTrue(client.callbackAnswers.get(0).text.contains("received"));
    }

    @Test
    public void shouldRejectPlanApprovalWhenCancelCallbackArrives() throws Exception {
        JsonObject telegram = createBoundTelegramConfig();
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient(
            "{\"ok\":true,\"result\":[{\"update_id\":9,\"callback_query\":{\"id\":\"cb-4\","
                + "\"data\":\"tg1:cancel:req-plan\",\"from\":{\"id\":7,\"username\":\"alice\"},"
                + "\"message\":{\"message_id\":14,\"chat\":{\"id\":42},\"text\":\"plan\"}}}]}"
        );
        RemoteRequestRegistry requestRegistry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();
        requestRegistry.register(new RemotePendingRequest(
            "req-plan",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "E:/workspace/demo-project",
            new JsonObject(),
            results::add
        ));
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(
            settingsService,
            client,
            new TelegramBindingService(() -> "ignored"),
            requestRegistry
        );

        receiver.pollOnce();

        assertEquals(1, results.size());
        assertFalse(results.get(0).get("approved").getAsBoolean());
        assertEquals(1, client.callbackAnswers.size());
        assertTrue(client.callbackAnswers.get(0).text.contains("cancelled"));
    }

    @Test
    public void shouldCompleteAskQuestionTextReplyForSinglePendingRequest() throws Exception {
        JsonObject telegram = createBoundTelegramConfig();
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient(createUpdatesResponse(10, "I need more details"));
        RemoteRequestRegistry requestRegistry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();
        requestRegistry.register(new RemotePendingRequest(
            "req-text",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-1",
            "E:/workspace/demo-project",
            createFreeTextAskPayload(),
            results::add
        ));
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(
            settingsService,
            client,
            new TelegramBindingService(() -> "ignored"),
            requestRegistry
        );

        receiver.pollOnce();

        assertEquals(1, results.size());
        assertEquals("I need more details", results.get(0).get("Please add more details").getAsString());
        assertEquals(1, client.sentMessages.size());
        assertTrue(client.sentMessages.get(0).text.contains("received"));
    }

    @Test
    public void shouldReplyFriendlyHintWhenCallbackRequestAlreadyExpired() throws Exception {
        JsonObject telegram = createBoundTelegramConfig();
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient(
            "{\"ok\":true,\"result\":[{\"update_id\":11,\"callback_query\":{\"id\":\"cb-3\","
                + "\"data\":\"tg1:approve:req-missing\",\"from\":{\"id\":7,\"username\":\"alice\"},"
                + "\"message\":{\"message_id\":13,\"chat\":{\"id\":42},\"text\":\"plan\"}}}]}"
        );
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(
            settingsService,
            client,
            new TelegramBindingService(() -> "ignored"),
            new RemoteRequestRegistry()
        );

        receiver.pollOnce();

        assertEquals(1, client.callbackAnswers.size());
        assertTrue(client.callbackAnswers.get(0).text.contains("expired"));
    }

    @Test
    public void shouldTreatDuplicateCallbacksAsExpiredAfterFirstCompletion() throws Exception {
        JsonObject telegram = createBoundTelegramConfig();
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient(
            "{\"ok\":true,\"result\":["
                + "{\"update_id\":20,\"callback_query\":{\"id\":\"cb-20\","
                + "\"data\":\"tg1:approve:req-plan\",\"from\":{\"id\":7,\"username\":\"alice\"},"
                + "\"message\":{\"message_id\":21,\"chat\":{\"id\":42},\"text\":\"plan\"}}},"
                + "{\"update_id\":21,\"callback_query\":{\"id\":\"cb-21\","
                + "\"data\":\"tg1:approve:req-plan\",\"from\":{\"id\":7,\"username\":\"alice\"},"
                + "\"message\":{\"message_id\":22,\"chat\":{\"id\":42},\"text\":\"plan\"}}}"
                + "]}"
        );
        RemoteRequestRegistry requestRegistry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();
        requestRegistry.register(new RemotePendingRequest(
            "req-plan",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "E:/workspace/demo-project",
            new JsonObject(),
            results::add
        ));
        TelegramPollingReceiver receiver = new TelegramPollingReceiver(
            settingsService,
            client,
            new TelegramBindingService(() -> "ignored"),
            requestRegistry
        );

        receiver.pollOnce();

        assertEquals(1, results.size());
        assertEquals(2, client.callbackAnswers.size());
        assertTrue(client.callbackAnswers.get(1).text.contains("expired"));
    }

    private String createUpdatesResponse(int updateId, String messageText) {
        return "{\"ok\":true,\"result\":[{\"update_id\":" + updateId
            + ",\"message\":{\"text\":\"" + messageText + "\",\"chat\":{\"id\":42},\"from\":{\"id\":7,\"username\":\"alice\"}}}]}";
    }

    private JsonObject createBoundTelegramConfig() {
        JsonObject telegram = new JsonObject();
        telegram.addProperty("botToken", "bot-token");
        telegram.addProperty("bindingToken", "");
        telegram.addProperty("pollIntervalSeconds", 1);
        telegram.addProperty("chatId", "42");
        telegram.addProperty("boundUserId", "7");
        telegram.addProperty("boundUsername", "alice");
        telegram.addProperty("botUsername", "cc_gui_bot");
        telegram.addProperty("connectionStatus", "connected");
        telegram.addProperty("lastError", "");
        return telegram;
    }

    private JsonObject createSingleChoiceAskPayload() {
        JsonObject payload = new JsonObject();
        JsonArray questions = new JsonArray();
        JsonObject question = new JsonObject();
        question.addProperty("question", "Choose how to proceed");
        JsonArray options = new JsonArray();
        JsonObject optionA = new JsonObject();
        optionA.addProperty("label", "Continue");
        options.add(optionA);
        JsonObject optionB = new JsonObject();
        optionB.addProperty("label", "Cancel");
        options.add(optionB);
        question.add("options", options);
        questions.add(question);
        payload.add("questions", questions);
        return payload;
    }

    private JsonObject createFreeTextAskPayload() {
        JsonObject payload = new JsonObject();
        JsonArray questions = new JsonArray();
        JsonObject question = new JsonObject();
        question.addProperty("question", "Please add more details");
        questions.add(question);
        payload.add("questions", questions);
        return payload;
    }

    private static class StubSettingsService extends CodemossSettingsService {
        private JsonObject telegram;

        private StubSettingsService(JsonObject telegram) {
            this.telegram = telegram.deepCopy();
        }

        @Override
        public JsonObject getTelegramConfig() {
            return telegram.deepCopy();
        }

        @Override
        public void saveTelegramConfig(JsonObject telegramConfig) {
            this.telegram = telegramConfig.deepCopy();
        }
    }

    private static class RecordingTelegramClient extends TelegramMessageClient {
        private final String updatesResponse;
        private final AtomicInteger getUpdatesCalls = new AtomicInteger();
        private final List<SentMessage> sentMessages = new ArrayList<>();
        private final List<CallbackAnswer> callbackAnswers = new ArrayList<>();
        private JsonArray lastAllowedUpdates = new JsonArray();

        private RecordingTelegramClient(String updatesResponse) {
            super("bot-token", (url, body) -> "{\"ok\":true,\"result\":{}}");
            this.updatesResponse = updatesResponse;
        }

        @Override
        public JsonObject getUpdates(long offset, int timeoutSeconds, JsonArray allowedUpdates) {
            getUpdatesCalls.incrementAndGet();
            lastAllowedUpdates = allowedUpdates == null ? new JsonArray() : allowedUpdates.deepCopy();
            return com.google.gson.JsonParser.parseString(updatesResponse).getAsJsonObject();
        }

        @Override
        public JsonObject sendMessage(String chatId, String text, JsonObject replyMarkup) {
            sentMessages.add(new SentMessage(chatId, text, replyMarkup == null ? null : replyMarkup.deepCopy()));
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            return response;
        }

        @Override
        public JsonObject answerCallbackQuery(String callbackQueryId, String text) {
            callbackAnswers.add(new CallbackAnswer(callbackQueryId, text));
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            return response;
        }
    }

    private record SentMessage(String chatId, String text, JsonObject replyMarkup) {
    }

    private record CallbackAnswer(String callbackQueryId, String text) {
    }
}
