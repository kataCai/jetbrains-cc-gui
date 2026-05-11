package com.github.claudecodegui.remote.providers.feishu;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.remote.feishu.FeishuBindingService;
import com.github.claudecodegui.remote.feishu.FeishuEventSubscriber;
import com.github.claudecodegui.remote.feishu.FeishuIncomingMessage;
import com.github.claudecodegui.remote.feishu.FeishuMessageClient;
import com.github.claudecodegui.remote.feishu.FeishuMessageFormatter;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugActionDescriptor;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Feishu provider 是否先以最小骨架形式接入统一 provider 抽象，
 * 避免飞书联调初期继续把特定逻辑散落在 service 或 handler 中。
 */
public class FeishuRemoteCollabProviderTest {

    @Test
    public void shouldDelegateLifecycleAndPublishOperationsToFeishuDelegate() throws Exception {
        FakeFeishuDelegate delegate = new FakeFeishuDelegate();
        FeishuRemoteCollabProvider provider = new FeishuRemoteCollabProvider(delegate);
        RemotePendingRequest request = new RemotePendingRequest(
            "request-1",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "/project",
            new JsonObject(),
            ignored -> {
            }
        );
        RemoteTaskEvent event = new RemoteTaskEvent("session-1", "/project", "request-1", "completed", "title", "summary");

        provider.initialize();
        provider.publishTaskEvent(event);
        provider.publishPendingRequest(request);
        provider.shutdown();

        assertTrue(delegate.initializeCalled);
        assertSame(event, delegate.lastTaskEvent);
        assertSame(request, delegate.lastPendingRequest);
        assertTrue(delegate.shutdownCalled);
        assertEquals(RemoteConnectionStatus.CONNECTED, provider.getConnectionStatus());
    }

    @Test
    public void shouldDelegateFeishuSpecificOperationsAndExposeDebugActions() throws Exception {
        FakeFeishuDelegate delegate = new FakeFeishuDelegate();
        FeishuRemoteCollabProvider provider = new FeishuRemoteCollabProvider(delegate);
        CodemossSettingsService settingsService = new CodemossSettingsService();
        JsonObject request = new JsonObject();
        request.addProperty("message", "hello feishu");

        JsonObject bindingResult = provider.startBinding(settingsService);
        JsonObject healthResult = provider.executeAction(settingsService, "health_check", new JsonObject());
        JsonObject testResult = provider.executeAction(settingsService, "send_test_message", request);

        assertSame(delegate.bindingResult, bindingResult);
        assertEquals("hello feishu", delegate.lastTestMessage);
        assertEquals("connected", healthResult.get("status").getAsString());
        assertEquals("Feishu 测试消息已发送", testResult.get("message").getAsString());

        Set<RemoteCollabCapability> capabilities = provider.getDescriptor().getCapabilities();
        assertTrue(capabilities.contains(RemoteCollabCapability.BINDING));
        assertTrue(capabilities.contains(RemoteCollabCapability.INLINE_ACTION_CALLBACK));
        assertTrue(capabilities.contains(RemoteCollabCapability.HEALTH_CHECK));
        assertEquals("feishu", provider.getDescriptor().getProviderId());

        List<RemoteCollabDebugActionDescriptor> debugActions = provider.getDebugActions();
        assertEquals(4, debugActions.size());
        assertEquals("start_binding", debugActions.get(0).getActionKey());
        assertEquals("health_check", debugActions.get(1).getActionKey());
        assertEquals("send_test_message", debugActions.get(2).getActionKey());
        assertEquals("handle_inbound_message", debugActions.get(3).getActionKey());
    }

    @Test
    public void shouldPersistConnectedStatusAfterSuccessfulHealthCheck() throws Exception {
        InMemoryFeishuSettingsService settingsService = new InMemoryFeishuSettingsService();
        FeishuRemoteCollabProvider provider = new FeishuRemoteCollabProvider(
            new FeishuRemoteCollabProvider.FeishuRuntimeDelegate() {
                @Override
                public RemoteConnectionStatus getConnectionStatus() {
                    return RemoteConnectionStatus.CONNECTED;
                }

                @Override
                public void initialize() {
                }

                @Override
                public void shutdown() {
                }

                @Override
                public void publishTaskEvent(RemoteTaskEvent event) {
                }

                @Override
                public void publishPendingRequest(RemotePendingRequest request) {
                }

                @Override
                public JsonObject startBinding() {
                    return new JsonObject();
                }

                @Override
                public JsonObject healthCheck() {
                    JsonObject result = new JsonObject();
                    result.addProperty("status", "connected");
                    result.addProperty("tenantAccessTokenReady", true);
                    return result;
                }

                @Override
                public void sendTestMessage(String message) {
                }

                @Override
                public com.github.claudecodegui.remote.feishu.FeishuEventSubscriber.HandleResult handleInboundMessage(
                    com.github.claudecodegui.remote.feishu.FeishuIncomingMessage message
                ) {
                    return com.github.claudecodegui.remote.feishu.FeishuEventSubscriber.HandleResult.ignored();
                }
            }
        );

        JsonObject result = provider.healthCheck(settingsService);

        JsonObject savedConfig = settingsService.getRemoteCollabProviderConfig("feishu");
        assertEquals("connected", result.get("status").getAsString());
        assertEquals("connected", savedConfig.get("connectionStatus").getAsString());
        assertEquals("", savedConfig.get("lastError").getAsString());
    }

    @Test
    public void shouldPersistErrorStatusAfterFailedHealthCheck() throws Exception {
        InMemoryFeishuSettingsService settingsService = new InMemoryFeishuSettingsService();
        FeishuRemoteCollabProvider provider = new FeishuRemoteCollabProvider(
            new FeishuRemoteCollabProvider.FeishuRuntimeDelegate() {
                @Override
                public RemoteConnectionStatus getConnectionStatus() {
                    return RemoteConnectionStatus.ERROR;
                }

                @Override
                public void initialize() {
                }

                @Override
                public void shutdown() {
                }

                @Override
                public void publishTaskEvent(RemoteTaskEvent event) {
                }

                @Override
                public void publishPendingRequest(RemotePendingRequest request) {
                }

                @Override
                public JsonObject startBinding() {
                    return new JsonObject();
                }

                @Override
                public JsonObject healthCheck() throws java.io.IOException {
                    throw new java.io.IOException("invalid app secret");
                }

                @Override
                public void sendTestMessage(String message) {
                }

                @Override
                public com.github.claudecodegui.remote.feishu.FeishuEventSubscriber.HandleResult handleInboundMessage(
                    com.github.claudecodegui.remote.feishu.FeishuIncomingMessage message
                ) {
                    return com.github.claudecodegui.remote.feishu.FeishuEventSubscriber.HandleResult.ignored();
                }
            }
        );

        boolean thrown = false;
        try {
            provider.healthCheck(settingsService);
        } catch (java.io.IOException expected) {
            thrown = true;
        }

        JsonObject savedConfig = settingsService.getRemoteCollabProviderConfig("feishu");
        assertTrue(thrown);
        assertEquals("error", savedConfig.get("connectionStatus").getAsString());
        assertEquals("invalid app secret", savedConfig.get("lastError").getAsString());
    }

    @Test
    public void shouldCompleteBindingThenAllowSendTestMessageAndTaskPush() throws Exception {
        InMemoryFeishuSettingsService settingsService = new InMemoryFeishuSettingsService();
        settingsService.providerConfig.addProperty("boundOpenId", "");
        settingsService.providerConfig.addProperty("boundChatId", "");
        RecordingFeishuClient client = new RecordingFeishuClient("tenant-token-1");
        FeishuRemoteCollabProvider provider = new FeishuRemoteCollabProvider(
            settingsService,
            new FeishuBindingService(),
            new FeishuMessageFormatter(),
            new FeishuEventSubscriber(settingsService, new com.github.claudecodegui.remote.RemoteRequestRegistry()),
            (appId, appSecret) -> client
        );

        JsonObject bindingResult = provider.executeAction(settingsService, "start_binding", new JsonObject());
        String bindingToken = bindingResult.get("bindingToken").getAsString();
        assertFalse(bindingToken.isEmpty());

        JsonObject inboundRequest = new JsonObject();
        inboundRequest.addProperty("openId", "ou_bound");
        inboundRequest.addProperty("chatId", "oc_bound");
        inboundRequest.addProperty("message", "/cc-bind " + bindingToken);
        JsonObject inboundResult = provider.executeAction(settingsService, "handle_inbound_message", inboundRequest);

        JsonObject savedConfig = settingsService.getRemoteCollabProviderConfig("feishu");
        assertTrue(inboundResult.get("handled").getAsBoolean());
        assertTrue(inboundResult.get("completed").getAsBoolean());
        assertEquals("ou_bound", savedConfig.get("boundOpenId").getAsString());
        assertEquals("connected", savedConfig.get("connectionStatus").getAsString());

        provider.sendTestMessage(settingsService, "hello feishu");
        provider.publishTaskEvent(new RemoteTaskEvent("session-1", "/project", "req-1", "running", "Build", "Still running"));

        assertEquals(2, client.sentMessages.size());
        assertEquals("ou_bound", client.sentMessages.get(0).receiveId);
        assertEquals("hello feishu", client.sentMessages.get(0).text);
        assertTrue(client.sentMessages.get(1).text.contains("Status: running"));
    }

    @Test
    public void shouldHandleApprovalAndQuestionRepliesThroughInjectedInboundAction() throws Exception {
        InMemoryFeishuSettingsService settingsService = new InMemoryFeishuSettingsService();
        settingsService.providerConfig.addProperty("boundOpenId", "ou_owner");
        RecordingFeishuClient client = new RecordingFeishuClient("tenant-token-2");
        com.github.claudecodegui.remote.RemoteRequestRegistry registry = new com.github.claudecodegui.remote.RemoteRequestRegistry();
        FeishuRemoteCollabProvider provider = new FeishuRemoteCollabProvider(
            settingsService,
            new FeishuBindingService(),
            new FeishuMessageFormatter(),
            new FeishuEventSubscriber(settingsService, registry),
            (appId, appSecret) -> client
        );

        List<JsonObject> approvalResults = new ArrayList<>();
        RemotePendingRequest approvalRequest = new RemotePendingRequest(
            "req-plan-9",
            RemoteRequestType.PLAN_APPROVAL,
            "session-2",
            "/project",
            new JsonObject(),
            approvalResults::add
        );
        registry.register(approvalRequest);

        provider.publishPendingRequest(approvalRequest);
        assertEquals(1, client.sentMessages.size());
        assertTrue(client.sentMessages.get(0).text.contains("/cc-approve req-plan-9"));

        JsonObject approveInbound = new JsonObject();
        approveInbound.addProperty("openId", "ou_owner");
        approveInbound.addProperty("chatId", "oc_owner");
        approveInbound.addProperty("message", "/cc-approve req-plan-9");
        JsonObject approveResult = provider.executeAction(settingsService, "handle_inbound_message", approveInbound);

        assertTrue(approveResult.get("handled").getAsBoolean());
        assertTrue(approveResult.get("completed").getAsBoolean());
        assertEquals(1, approvalResults.size());
        assertTrue(approvalResults.get(0).get("approved").getAsBoolean());

        List<JsonObject> questionResults = new ArrayList<>();
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
        RemotePendingRequest askRequest = new RemotePendingRequest(
            "req-ask-9",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-3",
            "/project",
            payload,
            questionResults::add
        );
        registry.register(askRequest);

        provider.publishPendingRequest(askRequest);
        assertEquals(2, client.sentMessages.size());
        assertTrue(client.sentMessages.get(1).text.contains("/cc-choice req-ask-9 Cancel"));

        JsonObject answerInbound = new JsonObject();
        answerInbound.addProperty("openId", "ou_owner");
        answerInbound.addProperty("chatId", "oc_owner");
        answerInbound.addProperty("message", "/cc-choice req-ask-9 Cancel");
        JsonObject answerResult = provider.executeAction(settingsService, "handle_inbound_message", answerInbound);

        assertTrue(answerResult.get("handled").getAsBoolean());
        assertTrue(answerResult.get("completed").getAsBoolean());
        assertEquals("Cancel", questionResults.get(0).get("Choose how to proceed").getAsString());
    }

    private static final class FakeFeishuDelegate implements FeishuRemoteCollabProvider.FeishuRuntimeDelegate {
        private final JsonObject bindingResult = new JsonObject();
        private boolean initializeCalled;
        private boolean shutdownCalled;
        private RemoteTaskEvent lastTaskEvent;
        private RemotePendingRequest lastPendingRequest;
        private String lastTestMessage;

        private FakeFeishuDelegate() {
            bindingResult.addProperty("bindingToken", "bind-feishu-token");
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return RemoteConnectionStatus.CONNECTED;
        }

        @Override
        public void initialize() {
            initializeCalled = true;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
            lastTaskEvent = event;
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
            lastPendingRequest = request;
        }

        @Override
        public JsonObject startBinding() {
            return bindingResult;
        }

        @Override
        public JsonObject healthCheck() {
            JsonObject result = new JsonObject();
            result.addProperty("status", "connected");
            return result;
        }

        @Override
        public void sendTestMessage(String message) {
            lastTestMessage = message;
        }

        @Override
        public com.github.claudecodegui.remote.feishu.FeishuEventSubscriber.HandleResult handleInboundMessage(
            com.github.claudecodegui.remote.feishu.FeishuIncomingMessage message
        ) {
            return com.github.claudecodegui.remote.feishu.FeishuEventSubscriber.HandleResult.ignored();
        }
    }

    private static final class InMemoryFeishuSettingsService extends CodemossSettingsService {
        private final JsonObject providerConfig = new JsonObject();

        private InMemoryFeishuSettingsService() {
            providerConfig.addProperty("enabled", true);
            providerConfig.addProperty("appId", "cli_test");
            providerConfig.addProperty("appSecret", "secret_test");
            providerConfig.addProperty("boundOpenId", "ou_test");
            providerConfig.addProperty("connectionStatus", "disabled");
            providerConfig.addProperty("lastError", "");
        }

        @Override
        public JsonObject getRemoteCollabProviderConfig(String providerId) {
            return providerConfig.deepCopy();
        }

        @Override
        public void saveRemoteCollabProviderConfig(String providerId, JsonObject providerConfig) {
            this.providerConfig.entrySet().clear();
            for (String key : providerConfig.keySet()) {
                this.providerConfig.add(key, providerConfig.get(key));
            }
        }
    }

    private static final class RecordingFeishuClient extends FeishuMessageClient {
        private final JsonObject tokenResponse = new JsonObject();
        private final List<SentMessage> sentMessages = new ArrayList<>();

        private RecordingFeishuClient(String tenantAccessToken) {
            super("cli_test", "secret_test");
            tokenResponse.addProperty("code", 0);
            tokenResponse.addProperty("tenant_access_token", tenantAccessToken);
        }

        @Override
        public JsonObject getTenantAccessToken() {
            return tokenResponse.deepCopy();
        }

        @Override
        public JsonObject sendTextMessage(String tenantAccessToken, String openId, String text) {
            sentMessages.add(new SentMessage(tenantAccessToken, openId, text));
            JsonObject response = new JsonObject();
            response.addProperty("code", 0);
            response.addProperty("msg", "success");
            return response;
        }
    }

    private static final class SentMessage {
        private final String tenantAccessToken;
        private final String receiveId;
        private final String text;

        private SentMessage(String tenantAccessToken, String receiveId, String text) {
            this.tenantAccessToken = tenantAccessToken;
            this.receiveId = receiveId;
            this.text = text;
        }
    }

}
