package com.github.claudecodegui.remote.debug;

import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ??????????? ring buffer ????????
 */
public class RemoteCollabDebugServiceTest {

    @Test
    public void shouldKeepMostRecentItemsWithinRingBufferCapacity() {
        RemoteCollabDebugService service = new RemoteCollabDebugService(2, 2, 2);

        service.recordTaskEvent("telegram", new RemoteTaskEvent("s1", "/p1", "r1", "running", "title-1", "summary-1"));
        service.recordTaskEvent("telegram", new RemoteTaskEvent("s2", "/p2", "r2", "waiting", "title-2", "summary-2"));
        service.recordPendingRequest("gotify_web", new RemotePendingRequest(
            "r3",
            RemoteRequestType.ASK_USER_QUESTION,
            "s3",
            "/p3",
            new JsonObject(),
            ignored -> {
            }
        ));
        service.recordError("telegram", "publishPendingRequest", "timeout");
        service.recordError("gotify_web", "publishTaskEvent", "server unavailable");
        service.recordDebugAction(RemoteCollabDebugResult.success("telegram", "refresh_status", "ok"));
        service.recordDebugAction(RemoteCollabDebugResult.failure("gotify_web", "poll_results_once", "empty response"));
        service.recordDebugAction(RemoteCollabDebugResult.success("telegram", "send_test_message", "queued"));

        RemoteCollabDebugSnapshot snapshot = service.getSnapshot();

        assertEquals(2, snapshot.getRecentRequests().size());
        assertEquals("task_event", snapshot.getRecentRequests().get(0).get("category").getAsString());
        assertEquals("pending_request", snapshot.getRecentRequests().get(1).get("category").getAsString());
        assertEquals("r2", snapshot.getRecentRequests().get(0).get("requestId").getAsString());
        assertEquals("r3", snapshot.getRecentRequests().get(1).get("requestId").getAsString());

        assertEquals(2, snapshot.getRecentErrors().size());
        assertEquals("telegram", snapshot.getRecentErrors().get(0).get("providerId").getAsString());
        assertEquals("gotify_web", snapshot.getRecentErrors().get(1).get("providerId").getAsString());

        assertEquals(2, snapshot.getRecentActions().size());
        assertEquals("poll_results_once", snapshot.getRecentActions().get(0).get("actionKey").getAsString());
        assertEquals("send_test_message", snapshot.getRecentActions().get(1).get("actionKey").getAsString());
    }

    @Test
    public void shouldReturnDefensiveCopiesInSnapshot() {
        RemoteCollabDebugService service = new RemoteCollabDebugService();
        service.recordError("telegram", "health_check", "bot token invalid");

        RemoteCollabDebugSnapshot snapshot = service.getSnapshot();
        snapshot.getRecentErrors().get(0).addProperty("providerId", "tampered");

        RemoteCollabDebugSnapshot freshSnapshot = service.getSnapshot();
        assertEquals("telegram", freshSnapshot.getRecentErrors().get(0).get("providerId").getAsString());
        assertTrue(freshSnapshot.toJson().has("recentErrors"));
    }
}
