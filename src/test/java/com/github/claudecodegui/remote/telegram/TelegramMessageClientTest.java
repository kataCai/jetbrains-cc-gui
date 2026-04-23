package com.github.claudecodegui.remote.telegram;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TelegramMessageClientTest {

    @Test
    public void shouldSendMessageWithMarkdownPayload() throws Exception {
        RecordingTransport transport = new RecordingTransport("{\"ok\":true,\"result\":{\"message_id\":123}}");
        TelegramMessageClient client = new TelegramMessageClient("bot-token", transport);
        JsonObject replyMarkup = new JsonObject();
        replyMarkup.add("inline_keyboard", new JsonArray());

        JsonObject response = client.sendMessage("42", "hello", replyMarkup);

        assertEquals("https://api.telegram.org/botbot-token/sendMessage", transport.lastUrl);
        assertEquals("42", transport.lastBody.get("chat_id").getAsString());
        assertEquals("hello", transport.lastBody.get("text").getAsString());
        assertEquals("Markdown", transport.lastBody.get("parse_mode").getAsString());
        assertTrue(transport.lastBody.has("reply_markup"));
        assertEquals(123, response.getAsJsonObject("result").get("message_id").getAsInt());
    }

    @Test
    public void shouldCallGetUpdatesWithOffsetAndAllowedUpdates() throws Exception {
        RecordingTransport transport = new RecordingTransport("{\"ok\":true,\"result\":[]}");
        TelegramMessageClient client = new TelegramMessageClient("bot-token", transport);
        JsonArray allowedUpdates = new JsonArray();
        allowedUpdates.add("message");
        allowedUpdates.add("callback_query");

        client.getUpdates(200L, 30, allowedUpdates);

        assertEquals("https://api.telegram.org/botbot-token/getUpdates", transport.lastUrl);
        assertEquals(200L, transport.lastBody.get("offset").getAsLong());
        assertEquals(30, transport.lastBody.get("timeout").getAsInt());
        assertEquals(2, transport.lastBody.getAsJsonArray("allowed_updates").size());
    }

    @Test
    public void shouldThrowWhenTelegramReturnsErrorResponse() throws Exception {
        RecordingTransport transport =
            new RecordingTransport("{\"ok\":false,\"description\":\"Bad Request: chat not found\"}");
        TelegramMessageClient client = new TelegramMessageClient("bot-token", transport);

        try {
            client.getMe();
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("chat not found"));
        }
    }

    @Test
    public void shouldIncludeResponseSnippetWhenTelegramReturnsMalformedJson() throws Exception {
        RecordingTransport transport = new RecordingTransport("<html>502 Bad Gateway</html>");
        TelegramMessageClient client = new TelegramMessageClient("bot-token", transport);

        try {
            client.getMe();
            fail("expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Invalid Telegram API response for getMe"));
            assertTrue(expected.getMessage().contains("502 Bad Gateway"));
        }
    }

    private static class RecordingTransport implements TelegramMessageClient.HttpTransport {
        private final String responseBody;
        private String lastUrl;
        private JsonObject lastBody;

        private RecordingTransport(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public String postJson(String url, JsonObject body) {
            this.lastUrl = url;
            this.lastBody = body.deepCopy();
            return responseBody;
        }
    }
}
