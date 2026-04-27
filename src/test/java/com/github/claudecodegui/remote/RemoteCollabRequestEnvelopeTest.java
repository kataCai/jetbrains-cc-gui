package com.github.claudecodegui.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 provider 无关的标准远程协作载荷是否能从 RemotePendingRequest 中稳定提取关键信息。
 */
public class RemoteCollabRequestEnvelopeTest {

    @Test
    public void shouldBuildPlanApprovalEnvelopeWithSummaryAndActions() {
        JsonObject payload = new JsonObject();
        payload.addProperty("title", "Review plan");
        payload.addProperty("cwd", "E:/workspace/demo-project");

        RemoteCollabRequestEnvelope envelope = RemoteCollabRequestEnvelope.fromPendingRequest(
            new RemotePendingRequest(
                "req-plan",
                RemoteRequestType.PLAN_APPROVAL,
                "session-1",
                "E:/workspace/demo-project",
                payload,
                ignored -> {
                }
            )
        );

        assertEquals("req-plan", envelope.getRequestId());
        assertEquals("plan_approval", envelope.getRequestType());
        assertEquals("session-1", envelope.getSessionId());
        assertEquals("E:/workspace/demo-project", envelope.getProjectPath());
        assertEquals("Review plan", envelope.getSummary());
        assertEquals(2, envelope.getActions().size());
        assertEquals("approve", envelope.getActions().get(0).getActionId());
        assertEquals("Approve", envelope.getActions().get(0).getLabel());
        assertEquals("reject", envelope.getActions().get(1).getActionId());
        assertEquals("Review plan", envelope.getMetadata().get("title").getAsString());
    }

    @Test
    public void shouldBuildAskUserQuestionEnvelopeWithChoiceActions() {
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

        RemoteCollabRequestEnvelope envelope = RemoteCollabRequestEnvelope.fromPendingRequest(
            new RemotePendingRequest(
                "req-ask",
                RemoteRequestType.ASK_USER_QUESTION,
                "session-2",
                "E:/workspace/demo-project",
                payload,
                ignored -> {
                }
            )
        );

        assertEquals("ask_user_question", envelope.getRequestType());
        assertEquals("Choose how to proceed", envelope.getSummary());
        assertEquals(2, envelope.getActions().size());
        assertEquals("choice_0", envelope.getActions().get(0).getActionId());
        assertEquals("Continue", envelope.getActions().get(0).getLabel());
        assertEquals("choice_1", envelope.getActions().get(1).getActionId());
        assertTrue(envelope.toJson().getAsJsonArray("actions").size() > 0);
    }
}
