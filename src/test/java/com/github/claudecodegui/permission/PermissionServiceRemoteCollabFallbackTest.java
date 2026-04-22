package com.github.claudecodegui.permission;

import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteTaskChannel;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证当本地没有 dialog shower 时，AskUserQuestion / PlanApproval 可以回退到远程协作主链路。
 */
public class PermissionServiceRemoteCollabFallbackTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldFallbackAskUserQuestionToRemoteCollabWhenNoLocalDialogShower() throws Exception {
        Path permissionDir = Files.createTempDirectory("permission-remote-ask");
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        RemoteCollabService remoteCollabService = newRemoteCollabService(registry);
        RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel();
        remoteCollabService.setTaskChannel(channel);
        PermissionService service = new PermissionService(null, "session-ask", permissionDir, remoteCollabService);

        Path requestFile = permissionDir.resolve("ask-user-question-session-ask-req-ask.json");
        Files.writeString(requestFile, "{\"requestId\":\"req-ask\",\"toolName\":\"AskUserQuestion\",\"cwd\":\"E:/demo\",\"question\":\"1+1=?\"}");

        invokeRequestHandler(service, "handleAskUserQuestionRequest", requestFile);

        assertEquals(1, channel.pendingRequests.size());
        assertEquals("req-ask", channel.pendingRequests.get(0).getRequestId());
        assertNotNull(registry.get("req-ask"));

        JsonObject answers = new JsonObject();
        answers.addProperty("answer", "2");
        assertTrue(remoteCollabService.completePendingRequest("req-ask", answers));

        JsonObject response = readJson(permissionDir.resolve("ask-user-question-response-session-ask-req-ask.json"));
        assertEquals("2", response.getAsJsonObject("answers").get("answer").getAsString());
        assertNull(registry.get("req-ask"));
    }

    @Test
    public void shouldFallbackPlanApprovalToRemoteCollabWhenNoLocalDialogShower() throws Exception {
        Path permissionDir = Files.createTempDirectory("permission-remote-plan");
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        RemoteCollabService remoteCollabService = newRemoteCollabService(registry);
        RecordingRemoteTaskChannel channel = new RecordingRemoteTaskChannel();
        remoteCollabService.setTaskChannel(channel);
        PermissionService service = new PermissionService(null, "session-plan", permissionDir, remoteCollabService);

        Path requestFile = permissionDir.resolve("plan-approval-session-plan-req-plan.json");
        Files.writeString(requestFile, "{\"requestId\":\"req-plan\",\"toolName\":\"PlanApproval\",\"cwd\":\"E:/demo\",\"title\":\"Review plan\"}");

        invokeRequestHandler(service, "handlePlanApprovalRequest", requestFile);

        assertEquals(1, channel.pendingRequests.size());
        assertEquals("req-plan", channel.pendingRequests.get(0).getRequestId());
        assertNotNull(registry.get("req-plan"));

        JsonObject decision = new JsonObject();
        decision.addProperty("approved", true);
        decision.addProperty("targetMode", "acceptEdits");
        assertTrue(remoteCollabService.completePendingRequest("req-plan", decision));

        JsonObject response = readJson(permissionDir.resolve("plan-approval-response-session-plan-req-plan.json"));
        assertTrue(response.get("approved").getAsBoolean());
        assertEquals("acceptEdits", response.get("targetMode").getAsString());
        assertNull(registry.get("req-plan"));
    }

    private RemoteCollabService newRemoteCollabService(RemoteRequestRegistry registry) throws Exception {
        Constructor<RemoteCollabService> constructor = RemoteCollabService.class.getDeclaredConstructor(RemoteRequestRegistry.class);
        constructor.setAccessible(true);
        return constructor.newInstance(registry);
    }

    private void invokeRequestHandler(PermissionService service, String methodName, Path requestFile) throws Exception {
        Method method = PermissionService.class.getDeclaredMethod(methodName, Path.class);
        method.setAccessible(true);
        method.invoke(service, requestFile);
    }

    private JsonObject readJson(Path path) throws Exception {
        assertTrue(Files.exists(path));
        return gson.fromJson(Files.readString(path), JsonObject.class);
    }

    private static class RecordingRemoteTaskChannel implements RemoteTaskChannel {
        private final List<RemotePendingRequest> pendingRequests = new ArrayList<>();

        @Override
        public String getChannelId() {
            return "test-channel";
        }

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
            pendingRequests.add(request);
        }
    }
}
