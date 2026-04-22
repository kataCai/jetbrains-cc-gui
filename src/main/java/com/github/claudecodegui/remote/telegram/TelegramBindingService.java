package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * 负责 Telegram 私聊绑定流程。
 * 生成一次性绑定口令，并在收到 `/start <token>` 后写回绑定结果。
 */
public class TelegramBindingService {

    private static final Logger LOG = Logger.getInstance(TelegramBindingService.class);

    private final TokenGenerator tokenGenerator;

    public TelegramBindingService() {
        this(() -> UUID.randomUUID().toString().replace("-", ""));
    }

    TelegramBindingService(TokenGenerator tokenGenerator) {
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
    }

    public JsonObject startBinding(CodemossSettingsService settingsService, TelegramMessageClient client) throws IOException {
        JsonObject me = client.getMe();
        JsonObject result = me.has("result") && me.get("result").isJsonObject()
            ? me.getAsJsonObject("result")
            : null;
        String botUsername = readString(result, "username");
        if (botUsername == null) {
            throw new IOException("Telegram Bot username is missing");
        }

        String bindingToken = tokenGenerator.nextToken();
        if (bindingToken == null || bindingToken.trim().isEmpty()) {
            throw new IOException("Failed to generate Telegram binding token");
        }

        JsonObject telegram = settingsService.getTelegramConfig();
        telegram.addProperty("botUsername", botUsername);
        telegram.addProperty("bindingToken", bindingToken);
        telegram.addProperty("connectionStatus", "connecting");
        telegram.addProperty("lastError", "");
        settingsService.saveTelegramConfig(telegram);

        JsonObject payload = new JsonObject();
        payload.addProperty("botUsername", botUsername);
        payload.addProperty("bindingToken", bindingToken);
        payload.addProperty("bindingUrl", "https://t.me/" + botUsername + "?start=" + bindingToken);
        return payload;
    }

    public boolean handleUpdate(
        JsonObject update,
        CodemossSettingsService settingsService,
        TelegramMessageClient client
    ) throws IOException {
        JsonObject message = update != null && update.has("message") && update.get("message").isJsonObject()
            ? update.getAsJsonObject("message")
            : null;
        if (message == null) {
            return false;
        }

        String bindingToken = readString(settingsService.getTelegramConfig(), "bindingToken");
        String startToken = parseStartToken(readString(message, "text"));
        if (bindingToken == null || !bindingToken.equals(startToken)) {
            return false;
        }

        JsonObject chat = message.has("chat") && message.get("chat").isJsonObject()
            ? message.getAsJsonObject("chat")
            : null;
        JsonObject from = message.has("from") && message.get("from").isJsonObject()
            ? message.getAsJsonObject("from")
            : null;
        String chatId = readString(chat, "id");
        String userId = readString(from, "id");
        if (chatId == null || userId == null) {
            return false;
        }

        JsonObject telegram = settingsService.getTelegramConfig();
        telegram.addProperty("chatId", chatId);
        telegram.addProperty("boundUserId", userId);
        telegram.addProperty("boundUsername", defaultString(readString(from, "username")));
        telegram.addProperty("bindingToken", "");
        telegram.addProperty("connectionStatus", "connected");
        telegram.addProperty("lastError", "");
        settingsService.saveTelegramConfig(telegram);

        try {
            client.sendMessage(chatId, "Telegram binding completed. This chat is now linked to CC GUI.", null);
        } catch (IOException e) {
            LOG.warn("[TelegramBindingService] Failed to send binding success message: " + e.getMessage());
        }
        return true;
    }

    private String parseStartToken(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        if (!normalized.startsWith("/start")) {
            return null;
        }
        String[] parts = normalized.split("\s+", 2);
        if (parts.length < 2) {
            return null;
        }
        String token = parts[1].trim();
        return token.isEmpty() ? null : token;
    }

    private String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        String value = json.get(key).getAsString();
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    interface TokenGenerator {
        String nextToken();
    }
}
