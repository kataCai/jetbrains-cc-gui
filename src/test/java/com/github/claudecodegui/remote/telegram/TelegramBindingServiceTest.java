package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TelegramBindingServiceTest {

    @Test
    public void shouldGenerateBindingLinkAndPersistBindingToken() throws Exception {
        StubSettingsService settingsService = new StubSettingsService(configuredTelegram());
        RecordingTelegramClient client = new RecordingTelegramClient();
        TelegramBindingService bindingService = new TelegramBindingService(() -> "bind-token");

        JsonObject result = bindingService.startBinding(settingsService, client);

        JsonObject saved = settingsService.getTelegramConfig();
        assertEquals("cc_gui_bot", saved.get("botUsername").getAsString());
        assertEquals("bind-token", saved.get("bindingToken").getAsString());
        assertEquals("connecting", saved.get("connectionStatus").getAsString());
        assertEquals("https://t.me/cc_gui_bot?start=bind-token", result.get("bindingUrl").getAsString());
        assertNotEquals("", result.get("bindingToken").getAsString());
    }

    @Test
    public void shouldPersistChatBindingWhenMatchingStartCommandArrives() throws Exception {
        JsonObject telegram = configuredTelegram();
        telegram.addProperty("bindingToken", "bind-token");
        telegram.addProperty("botUsername", "cc_gui_bot");
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient();
        TelegramBindingService bindingService = new TelegramBindingService(() -> "ignored");

        boolean handled = bindingService.handleUpdate(createStartUpdate("bind-token", 42L, 7L, "alice"), settingsService, client);

        assertTrue(handled);
        JsonObject saved = settingsService.getTelegramConfig();
        assertEquals("42", saved.get("chatId").getAsString());
        assertEquals("7", saved.get("boundUserId").getAsString());
        assertEquals("alice", saved.get("boundUsername").getAsString());
        assertEquals("", saved.get("bindingToken").getAsString());
        assertEquals("connected", saved.get("connectionStatus").getAsString());
        assertEquals(1, client.sentMessages.size());
        assertTrue(client.sentMessages.get(0).contains("Telegram"));
    }

    @Test
    public void shouldIgnoreStartCommandWhenTokenDoesNotMatch() throws Exception {
        JsonObject telegram = configuredTelegram();
        telegram.addProperty("bindingToken", "expected-token");
        StubSettingsService settingsService = new StubSettingsService(telegram);
        RecordingTelegramClient client = new RecordingTelegramClient();
        TelegramBindingService bindingService = new TelegramBindingService(() -> "ignored");

        boolean handled = bindingService.handleUpdate(createStartUpdate("wrong-token", 42L, 7L, "alice"), settingsService, client);

        assertFalse(handled);
        JsonObject saved = settingsService.getTelegramConfig();
        assertEquals("expected-token", saved.get("bindingToken").getAsString());
        assertEquals("", saved.get("chatId").getAsString());
        assertEquals(0, client.sentMessages.size());
    }

    private JsonObject configuredTelegram() {
        JsonObject telegram = new JsonObject();
        telegram.addProperty("botToken", "bot-token");
        telegram.addProperty("botUsername", "");
        telegram.addProperty("chatId", "");
        telegram.addProperty("boundUserId", "");
        telegram.addProperty("boundUsername", "");
        telegram.addProperty("bindingToken", "");
        telegram.addProperty("pollingEnabled", true);
        telegram.addProperty("pollIntervalSeconds", 1);
        telegram.addProperty("singleActive", true);
        telegram.addProperty("connectionStatus", "disabled");
        telegram.addProperty("lastError", "");
        return telegram;
    }

    private JsonObject createStartUpdate(String token, long chatId, long userId, String username) {
        JsonObject from = new JsonObject();
        from.addProperty("id", userId);
        from.addProperty("username", username);

        JsonObject chat = new JsonObject();
        chat.addProperty("id", chatId);

        JsonObject message = new JsonObject();
        message.addProperty("text", "/start " + token);
        message.add("chat", chat);
        message.add("from", from);

        JsonObject update = new JsonObject();
        update.addProperty("update_id", 100);
        update.add("message", message);
        return update;
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
        private final List<String> sentMessages = new ArrayList<>();

        private RecordingTelegramClient() {
            super("bot-token", (url, body) -> "{\"ok\":true,\"result\":{}}");
        }

        @Override
        public JsonObject getMe() {
            JsonObject user = new JsonObject();
            user.addProperty("username", "cc_gui_bot");
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            response.add("result", user);
            return response;
        }

        @Override
        public JsonObject sendMessage(String chatId, String text, JsonObject replyMarkup) {
            sentMessages.add(chatId + ":" + text);
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            return response;
        }
    }
}
