package com.github.claudecodegui.remote.feishu;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证飞书第一版文本协议格式。
 * 当前不直接依赖卡片交互，先用可读的纯文本协议承载 requestId 和命令提示，确保手机端可人工完成闭环。
 */
public class FeishuMessageFormatterTest {

    @Test
    public void shouldFormatPlanApprovalRequestWithTextCommands() {
        FeishuMessageFormatter formatter = new FeishuMessageFormatter();

        JsonObject payload = new JsonObject();
        payload.addProperty("title", "Review plan");
        String text = formatter.formatPendingRequest(new RemotePendingRequest(
            "req-plan-1",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "/project/demo",
            payload,
            ignored -> {
            }
        ));

        assertTrue(text.contains("Request ID: req-plan-1"));
        assertTrue(text.contains("Type: plan_approval"));
        assertTrue(text.contains("/cc-approve req-plan-1"));
        assertTrue(text.contains("/cc-reject req-plan-1"));
    }

    @Test
    public void shouldFormatAskUserQuestionRequestWithReplyHint() {
        FeishuMessageFormatter formatter = new FeishuMessageFormatter();

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

        String text = formatter.formatPendingRequest(new RemotePendingRequest(
            "req-ask-1",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-2",
            "/project/demo",
            payload,
            ignored -> {
            }
        ));

        assertTrue(text.contains("Request ID: req-ask-1"));
        assertTrue(text.contains("Question: Choose how to proceed"));
        assertTrue(text.contains("/cc-choice req-ask-1 Continue"));
        assertTrue(text.contains("/cc-choice req-ask-1 Cancel"));
        assertTrue(text.contains("/cc-reply req-ask-1 <your answer>"));
    }

    @Test
    public void shouldFormatTaskEventAsCompactStatusSummary() {
        FeishuMessageFormatter formatter = new FeishuMessageFormatter();

        String text = formatter.formatTaskEvent(new RemoteTaskEvent(
            "session-3",
            "/project/demo",
            "req-event-1",
            "waiting_confirm",
            "Build",
            "Need attention"
        ));

        assertTrue(text.contains("CC GUI Task Update"));
        assertTrue(text.contains("Status: waiting_confirm"));
        assertTrue(text.contains("Summary: Need attention"));
        assertEquals(-1, text.indexOf("/cc-approve"));
    }
}
