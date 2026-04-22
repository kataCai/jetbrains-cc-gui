package com.github.claudecodegui.remote.telegram;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Telegram Bot API 最小客户端。
 * 当前先覆盖出站 MVP 需要的方法，便于后续继续补 polling 与回调闭环。
 */
public class TelegramMessageClient {

    private static final String API_BASE = "https://api.telegram.org";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String PARSE_MODE_MARKDOWN = "Markdown";

    private final String botToken;
    private final HttpTransport transport;

    public TelegramMessageClient(String botToken) {
        this(botToken, new JavaHttpTransport());
    }

    TelegramMessageClient(String botToken, HttpTransport transport) {
        this.botToken = Objects.requireNonNull(botToken, "botToken");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public JsonObject getMe() throws IOException {
        return post("getMe", new JsonObject());
    }

    public JsonObject sendMessage(String chatId, String text, JsonObject replyMarkup) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text);
        body.addProperty("parse_mode", PARSE_MODE_MARKDOWN);
        if (replyMarkup != null) {
            body.add("reply_markup", replyMarkup.deepCopy());
        }
        return post("sendMessage", body);
    }

    public JsonObject editMessageText(String chatId, long messageId, String text, JsonObject replyMarkup) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("message_id", messageId);
        body.addProperty("text", text);
        body.addProperty("parse_mode", PARSE_MODE_MARKDOWN);
        if (replyMarkup != null) {
            body.add("reply_markup", replyMarkup.deepCopy());
        }
        return post("editMessageText", body);
    }

    public JsonObject answerCallbackQuery(String callbackQueryId, String text) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("callback_query_id", callbackQueryId);
        if (text != null && !text.trim().isEmpty()) {
            body.addProperty("text", text);
        }
        return post("answerCallbackQuery", body);
    }

    public JsonObject getUpdates(long offset, int timeoutSeconds, JsonArray allowedUpdates) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("offset", offset);
        body.addProperty("timeout", Math.max(0, timeoutSeconds));
        if (allowedUpdates != null) {
            body.add("allowed_updates", allowedUpdates.deepCopy());
        }
        return post("getUpdates", body);
    }

    public JsonObject deleteWebhook(boolean dropPendingUpdates) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("drop_pending_updates", dropPendingUpdates);
        return post("deleteWebhook", body);
    }

    private JsonObject post(String method, JsonObject body) throws IOException {
        String responseBody;
        try {
            responseBody = transport.postJson(buildUrl(method), body == null ? new JsonObject() : body.deepCopy());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Telegram API interrupted: " + method, e);
        }

        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
                String description = response.has("description") && !response.get("description").isJsonNull()
                    ? response.get("description").getAsString()
                    : "unknown telegram error";
                throw new IOException("Telegram API " + method + " failed: " + description);
            }
            return response;
        } catch (IllegalStateException | ClassCastException e) {
            throw new IOException("Invalid Telegram API response for " + method, e);
        }
    }

    private String buildUrl(String method) {
        return API_BASE + "/bot" + botToken + "/" + method;
    }

    interface HttpTransport {
        String postJson(String url, JsonObject body) throws IOException, InterruptedException;
    }

    private static final class JavaHttpTransport implements HttpTransport {
        private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        private final Gson gson = new Gson();

        @Override
        public String postJson(String url, JsonObject body) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        }
    }
}
