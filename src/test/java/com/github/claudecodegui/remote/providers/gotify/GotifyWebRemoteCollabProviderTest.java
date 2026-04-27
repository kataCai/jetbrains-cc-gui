package com.github.claudecodegui.remote.providers.gotify;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugActionDescriptor;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Gotify/Web provider 能否完成最小请求投递、调试动作和结果轮询闭环。
 */
public class GotifyWebRemoteCollabProviderTest {

    @Test
    public void shouldPublishPendingRequestAndCompletePlanApprovalAfterPolling() throws Exception {
        StubSettingsService settingsService = new StubSettingsService();
        FakeWorkspaceClient client = new FakeWorkspaceClient();
        GotifyResultPoller poller = new GotifyResultPoller(client);
        GotifyWebRemoteCollabProvider provider = new GotifyWebRemoteCollabProvider(settingsService, client, poller);
        AtomicReference<JsonObject> completedResult = new AtomicReference<>();

        client.nextCreateResult = new GotifyWorkspaceCreateResult(
            "backend-req-1",
            "http://workspace.local/request/backend-req-1"
        );
        client.resultByRequestId.put(
            "backend-req-1",
            new GotifyWorkspacePollResult(
                "backend-req-1",
                "action_received",
                1,
                new GotifyWorkspaceAction(
                    "approve",
                    payloadOf("comment", "同意执行", "targetMode", "acceptEdits")
                )
            )
        );

        RemotePendingRequest request = new RemotePendingRequest(
            "local-req-1",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "E:/demo",
            payloadOf("cwd", "E:/demo", "title", "Review plan"),
            completedResult::set
        );

        provider.publishPendingRequest(request);
        JsonObject pollResult = provider.executeAction(settingsService, "poll_results_once", new JsonObject());

        assertNotNull(client.lastCreatedRequest);
        assertEquals("local-req-1", client.lastCreatedRequest.getRequestId());
        assertEquals("plan_approval", client.lastCreatedRequest.getRequestType());
        assertEquals("Review plan", client.lastCreatedRequest.getSummaryMarkdown());
        assertNotNull(completedResult.get());
        assertTrue(completedResult.get().get("approved").getAsBoolean());
        assertEquals("acceptEdits", completedResult.get().get("targetMode").getAsString());
        assertEquals("backend-req-1", pollResult.getAsJsonArray("completedRequestIds").get(0).getAsString());
        assertEquals("http://workspace.local/request/backend-req-1", provider.getLastWorkspaceLink());
    }

    @Test
    public void shouldIncludeEnvelopeMetadataAndActionsInWorkspaceRequest() throws Exception {
        StubSettingsService settingsService = new StubSettingsService();
        FakeWorkspaceClient client = new FakeWorkspaceClient();
        GotifyResultPoller poller = new GotifyResultPoller(client);
        GotifyWebRemoteCollabProvider provider = new GotifyWebRemoteCollabProvider(settingsService, client, poller);

        client.nextCreateResult = new GotifyWorkspaceCreateResult(
            "backend-req-meta-1",
            "http://workspace.local/request/backend-req-meta-1"
        );

        JsonObject payload = payloadOf("cwd", "E:/demo", "title", "Review plan", "workspaceLink", "http://workspace/preset");
        provider.publishPendingRequest(new RemotePendingRequest(
            "local-meta-1",
            RemoteRequestType.PLAN_APPROVAL,
            "session-meta-1",
            "E:/demo",
            payload,
            ignored -> {
            }
        ));

        JsonObject requestJson = client.lastCreatedRequest.toJson();
        assertTrue(requestJson.has("metadata"));
        assertEquals("Review plan", requestJson.getAsJsonObject("metadata").get("title").getAsString());
        assertTrue(requestJson.has("actions"));
        assertEquals(2, requestJson.getAsJsonArray("actions").size());
        assertEquals("approve", requestJson.getAsJsonArray("actions").get(0).getAsJsonObject().get("actionId").getAsString());
        assertEquals("http://workspace/preset", requestJson.get("workspaceLink").getAsString());
    }

    @Test
    public void shouldPublishTaskEventAndExposeHealthDebugActions() throws Exception {
        StubSettingsService settingsService = new StubSettingsService();
        FakeWorkspaceClient client = new FakeWorkspaceClient();
        GotifyResultPoller poller = new GotifyResultPoller(client);
        GotifyWebRemoteCollabProvider provider = new GotifyWebRemoteCollabProvider(settingsService, client, poller);

        client.nextHealthResult = payloadOf("status", "ok");
        client.nextCreateResult = new GotifyWorkspaceCreateResult(
            "backend-event-1",
            "http://workspace.local/request/backend-event-1"
        );

        provider.initialize();
        provider.publishTaskEvent(new RemoteTaskEvent(
            "session-1",
            "E:/demo",
            "event-1",
            "waiting_confirm",
            "waiting_confirm",
            "Need attention"
        ));
        JsonObject healthResult = provider.executeAction(settingsService, "health_check", new JsonObject());

        assertEquals(RemoteConnectionStatus.CONNECTED, provider.getConnectionStatus());
        assertNotNull(client.lastCreatedRequest);
        assertEquals("task_event", client.lastCreatedRequest.getRequestType());
        assertEquals("waiting_confirm", client.lastCreatedRequest.getTitle());
        assertEquals("ok", healthResult.get("status").getAsString());

        List<RemoteCollabDebugActionDescriptor> debugActions = provider.getDebugActions();
        assertEquals(4, debugActions.size());
        assertEquals("health_check", debugActions.get(0).getActionKey());
        assertEquals("send_test_event", debugActions.get(1).getActionKey());
        assertEquals("send_test_pending_request", debugActions.get(2).getActionKey());
        assertEquals("poll_results_once", debugActions.get(3).getActionKey());
        assertTrue(provider.getDescriptor().getCapabilities().contains(RemoteCollabCapability.RESULT_POLLING));
    }

    @Test
    public void shouldMarkProviderErrorAndKeepPendingRequestRetryableWhenPollingFails() throws Exception {
        StubSettingsService settingsService = new StubSettingsService();
        FakeWorkspaceClient client = new FakeWorkspaceClient();
        GotifyResultPoller poller = new GotifyResultPoller(client);
        GotifyWebRemoteCollabProvider provider = new GotifyWebRemoteCollabProvider(settingsService, client, poller);
        AtomicReference<JsonObject> completedResult = new AtomicReference<>();

        client.nextCreateResult = new GotifyWorkspaceCreateResult(
            "backend-req-2",
            "http://workspace.local/request/backend-req-2"
        );
        client.pollException = new IOException("poll timeout");

        provider.publishPendingRequest(new RemotePendingRequest(
            "local-req-2",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-2",
            "E:/demo",
            payloadOf("question", "Need answer"),
            completedResult::set
        ));

        try {
            provider.executeAction(settingsService, "poll_results_once", new JsonObject());
            fail("Expected poll_results_once to report polling failure");
        } catch (IllegalStateException expected) {
            assertEquals("poll timeout", expected.getCause().getMessage());
        }
        assertEquals(RemoteConnectionStatus.ERROR, provider.getConnectionStatus());
        assertEquals(null, completedResult.get());

        client.pollException = null;
        client.resultByRequestId.put(
            "backend-req-2",
            new GotifyWorkspacePollResult(
                "backend-req-2",
                "action_received",
                1,
                new GotifyWorkspaceAction("reply", payloadOf("answer", "done"))
            )
        );

        JsonObject pollResult = provider.executeAction(settingsService, "poll_results_once", new JsonObject());

        assertEquals(RemoteConnectionStatus.CONNECTED, provider.getConnectionStatus());
        assertEquals("done", completedResult.get().get("answer").getAsString());
        assertEquals("backend-req-2", pollResult.getAsJsonArray("completedRequestIds").get(0).getAsString());
    }

    @Test
    public void shouldSetErrorStatusWhenHealthCheckFailsDuringInitialize() {
        StubSettingsService settingsService = new StubSettingsService();
        FakeWorkspaceClient client = new FakeWorkspaceClient();
        client.healthException = new IOException("service unavailable");
        GotifyResultPoller poller = new GotifyResultPoller(client);
        GotifyWebRemoteCollabProvider provider = new GotifyWebRemoteCollabProvider(settingsService, client, poller);

        try {
            provider.initialize();
            fail("Expected initialize to fail when backend health check is unavailable");
        } catch (IllegalStateException expected) {
            assertEquals("service unavailable", expected.getCause().getMessage());
        }
        assertEquals(RemoteConnectionStatus.ERROR, provider.getConnectionStatus());
    }

    private static JsonObject payloadOf(String... kvPairs) {
        JsonObject json = new JsonObject();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            json.addProperty(kvPairs[i], kvPairs[i + 1]);
        }
        return json;
    }

    private static final class StubSettingsService extends CodemossSettingsService {
        @Override
        public JsonObject getRemoteCollabProviderConfig(String providerId) {
            JsonObject config = new JsonObject();
            config.addProperty("enabled", true);
            config.addProperty("serverUrl", "http://127.0.0.1:18081");
            config.addProperty("apiToken", "token");
            config.addProperty("workspaceBaseUrl", "http://workspace.local");
            config.addProperty("resultPollIntervalSeconds", 3);
            config.addProperty("connectionStatus", "disabled");
            config.addProperty("lastError", "");
            return config;
        }
    }

    private static final class FakeWorkspaceClient extends GotifyWorkspaceClient {
        private final Map<String, GotifyWorkspacePollResult> resultByRequestId = new HashMap<>();
        private JsonObject nextHealthResult = payloadOf("status", "ok");
        private GotifyWorkspaceCreateResult nextCreateResult;
        private GotifyWorkspaceRequest lastCreatedRequest;
        private IOException healthException;
        private IOException pollException;

        @Override
        public JsonObject healthCheck(JsonObject providerConfig) throws IOException {
            if (healthException != null) {
                throw healthException;
            }
            return nextHealthResult.deepCopy();
        }

        @Override
        public GotifyWorkspaceCreateResult createRequest(JsonObject providerConfig, GotifyWorkspaceRequest request) {
            lastCreatedRequest = request;
            return nextCreateResult;
        }

        @Override
        public GotifyWorkspacePollResult getResult(JsonObject providerConfig, String requestId) throws IOException {
            if (pollException != null) {
                throw pollException;
            }
            return resultByRequestId.get(requestId);
        }
    }
}
