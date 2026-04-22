package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TelegramMessageFormatterTest {

    private final TelegramMessageFormatter formatter = new TelegramMessageFormatter();

    @Test
    public void shouldFormatCompletedTaskEventAsMarkdownSummary() {
        TelegramOutgoingMessage message = formatter.formatTaskEvent(
            new RemoteTaskEvent(
                "session-1",
                "E:/workspace/demo-project",
                null,
                "completed",
                "completed",
                "Fix Telegram delivery failure"
            )
        );

        assertNotNull(message);
        assertEquals("Markdown", message.parseMode());
        assertTrue(message.text().contains("*CC GUI Task Update*"));
        assertTrue(message.text().contains("Status: Completed"));
        assertTrue(message.text().contains("demo-project"));
        assertTrue(message.text().contains("Fix Telegram delivery failure"));
        assertNull(message.replyMarkup());
    }

    @Test
    public void shouldAddInlineKeyboardForWaitingConfirmTaskEvent() {
        TelegramOutgoingMessage message = formatter.formatTaskEvent(
            new RemoteTaskEvent(
                "session-2",
                "E:/workspace/demo-project",
                "req-123",
                "waiting_confirm",
                "waiting_confirm",
                "Please confirm whether the task should continue"
            )
        );

        assertNotNull(message.replyMarkup());
        JsonArray rows = message.replyMarkup().getAsJsonArray("inline_keyboard");
        assertEquals(1, rows.size());
        JsonArray firstRow = rows.get(0).getAsJsonArray();
        assertEquals("Continue", firstRow.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tg1:continue:req-123", firstRow.get(0).getAsJsonObject().get("callback_data").getAsString());
        assertEquals("Cancel", firstRow.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("tg1:cancel:req-123", firstRow.get(1).getAsJsonObject().get("callback_data").getAsString());
    }

    @Test
    public void shouldFormatPlanApprovalPendingRequestWithActionButtons() {
        JsonObject payload = new JsonObject();
        payload.addProperty("title", "Review plan");
        payload.addProperty("cwd", "E:/workspace/demo-project");

        TelegramOutgoingMessage message = formatter.formatPendingRequest(
            new RemotePendingRequest(
                "req-plan",
                RemoteRequestType.PLAN_APPROVAL,
                "session-3",
                "E:/workspace/demo-project",
                payload,
                ignored -> {
                }
            )
        );

        assertTrue(message.text().contains("Review plan"));
        assertNotNull(message.replyMarkup());
        JsonArray firstRow = message.replyMarkup().getAsJsonArray("inline_keyboard").get(0).getAsJsonArray();
        assertEquals("Approve", firstRow.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tg1:approve:req-plan", firstRow.get(0).getAsJsonObject().get("callback_data").getAsString());
        assertEquals("Reject", firstRow.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("tg1:reject:req-plan", firstRow.get(1).getAsJsonObject().get("callback_data").getAsString());
    }

    @Test
    public void shouldFormatSingleChoiceAskRequestWithInlineButtons() {
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

        TelegramOutgoingMessage message = formatter.formatPendingRequest(
            new RemotePendingRequest(
                "req-ask",
                RemoteRequestType.ASK_USER_QUESTION,
                "session-4",
                "E:/workspace/demo-project",
                payload,
                ignored -> {
                }
            )
        );

        assertTrue(message.text().contains("Request ID: req-ask"));
        assertNotNull(message.replyMarkup());
        JsonArray firstRow = message.replyMarkup().getAsJsonArray("inline_keyboard").get(0).getAsJsonArray();
        assertEquals("Continue", firstRow.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tg1:choice:req-ask:0:0", firstRow.get(0).getAsJsonObject().get("callback_data").getAsString());
        assertEquals("Cancel", firstRow.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("tg1:choice:req-ask:0:1", firstRow.get(1).getAsJsonObject().get("callback_data").getAsString());
    }

    @Test
    public void shouldFormatFreeTextAskRequestWithForceReply() {
        JsonObject payload = new JsonObject();
        JsonArray questions = new JsonArray();
        JsonObject question = new JsonObject();
        question.addProperty("question", "Please add more details");
        questions.add(question);
        payload.add("questions", questions);

        TelegramOutgoingMessage message = formatter.formatPendingRequest(
            new RemotePendingRequest(
                "req-text",
                RemoteRequestType.ASK_USER_QUESTION,
                "session-5",
                "E:/workspace/demo-project",
                payload,
                ignored -> {
                }
            )
        );

        assertTrue(message.text().contains("Request ID: req-text"));
        assertNotNull(message.replyMarkup());
        assertTrue(message.replyMarkup().get("force_reply").getAsBoolean());
    }
}
