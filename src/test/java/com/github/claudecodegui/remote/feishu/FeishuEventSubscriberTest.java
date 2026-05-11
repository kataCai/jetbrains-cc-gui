package com.github.claudecodegui.remote.feishu;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证飞书入站文本命令能否正确回写到本地请求。
 * 第一版走“文本命令等效交互”，因此这里重点覆盖审批、问答、重复点击、过期和非法操作者保护。
 */
public class FeishuEventSubscriberTest {

    @Test
    public void shouldCompletePlanApprovalWhenBoundUserApproves() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("boundOpenId", "ou_owner");
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();
        registry.register(new RemotePendingRequest(
            "req-plan-1",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "/project/demo",
            new JsonObject(),
            results::add
        ));
        FeishuEventSubscriber subscriber = new FeishuEventSubscriber(settingsService, registry);

        FeishuEventSubscriber.HandleResult result = subscriber.handleIncomingMessage(
            new FeishuIncomingMessage("ou_owner", "oc_owner", "/cc-approve req-plan-1")
        );

        assertTrue(result.isHandled());
        assertEquals("Plan approved.", result.getReplyText());
        assertEquals(1, results.size());
        assertTrue(results.get(0).get("approved").getAsBoolean());
        assertEquals(0, registry.size());
    }

    @Test
    public void shouldRejectActionFromUnboundOperator() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("boundOpenId", "ou_owner");
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        registry.register(new RemotePendingRequest(
            "req-plan-2",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "/project/demo",
            new JsonObject(),
            ignored -> {
            }
        ));
        FeishuEventSubscriber subscriber = new FeishuEventSubscriber(settingsService, registry);

        FeishuEventSubscriber.HandleResult result = subscriber.handleIncomingMessage(
            new FeishuIncomingMessage("ou_other", "oc_other", "/cc-approve req-plan-2")
        );

        assertTrue(result.isHandled());
        assertFalse(result.isCompleted());
        assertEquals("This Feishu account is not bound to this IDE.", result.getReplyText());
        assertEquals(1, registry.size());
    }

    @Test
    public void shouldReplyExpiredWhenDuplicateApprovalArrives() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("boundOpenId", "ou_owner");
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        registry.register(new RemotePendingRequest(
            "req-plan-3",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "/project/demo",
            new JsonObject(),
            ignored -> {
            }
        ));
        FeishuEventSubscriber subscriber = new FeishuEventSubscriber(settingsService, registry);

        FeishuEventSubscriber.HandleResult first = subscriber.handleIncomingMessage(
            new FeishuIncomingMessage("ou_owner", "oc_owner", "/cc-approve req-plan-3")
        );
        FeishuEventSubscriber.HandleResult second = subscriber.handleIncomingMessage(
            new FeishuIncomingMessage("ou_owner", "oc_owner", "/cc-approve req-plan-3")
        );

        assertTrue(first.isCompleted());
        assertFalse(second.isCompleted());
        assertEquals("This request has expired. Please refresh in the IDE.", second.getReplyText());
    }

    @Test
    public void shouldCompleteChoiceAnswerForAskUserQuestion() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("boundOpenId", "ou_owner");
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();

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

        registry.register(new RemotePendingRequest(
            "req-ask-1",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-2",
            "/project/demo",
            payload,
            results::add
        ));
        FeishuEventSubscriber subscriber = new FeishuEventSubscriber(settingsService, registry);

        FeishuEventSubscriber.HandleResult result = subscriber.handleIncomingMessage(
            new FeishuIncomingMessage("ou_owner", "oc_owner", "/cc-choice req-ask-1 Cancel")
        );

        assertTrue(result.isCompleted());
        assertEquals("Answer received.", result.getReplyText());
        assertEquals("Cancel", results.get(0).get("Choose how to proceed").getAsString());
    }

    @Test
    public void shouldCompleteFreeTextReplyForAskUserQuestion() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("boundOpenId", "ou_owner");
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        List<JsonObject> results = new ArrayList<>();

        JsonObject payload = new JsonObject();
        JsonArray questions = new JsonArray();
        JsonObject question = new JsonObject();
        question.addProperty("question", "Please add more details");
        questions.add(question);
        payload.add("questions", questions);

        registry.register(new RemotePendingRequest(
            "req-ask-2",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-3",
            "/project/demo",
            payload,
            results::add
        ));
        FeishuEventSubscriber subscriber = new FeishuEventSubscriber(settingsService, registry);

        FeishuEventSubscriber.HandleResult result = subscriber.handleIncomingMessage(
            new FeishuIncomingMessage("ou_owner", "oc_owner", "/cc-reply req-ask-2 Need more time")
        );

        assertTrue(result.isCompleted());
        assertEquals("Need more time", results.get(0).get("Please add more details").getAsString());
    }

    private static final class StubFeishuSettingsService extends CodemossSettingsService {
        private final JsonObject config = new JsonObject();

        private StubFeishuSettingsService() {
            config.addProperty("enabled", true);
            config.addProperty("boundOpenId", "");
            config.addProperty("boundChatId", "");
            config.addProperty("bindingToken", "");
            config.addProperty("bindingTokenExpiresAt", 0L);
            config.addProperty("connectionStatus", "connected");
            config.addProperty("lastError", "");
        }

        @Override
        public JsonObject getRemoteCollabProviderConfig(String providerId) {
            return config.deepCopy();
        }
    }
}
