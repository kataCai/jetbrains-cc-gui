package com.github.claudecodegui.remote;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证远程待处理请求的注册与统一完成能力。
 */
public class RemoteRequestRegistryTest {

    @Test
    public void shouldRegisterAndCompletePendingRequest() throws Exception {
        RemoteRequestRegistry registry = new RemoteRequestRegistry();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        JsonObject payload = new JsonObject();
        payload.addProperty("cwd", "E:/demo");

        registry.register(new RemotePendingRequest(
            "req-1",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-1",
            "E:/demo",
            payload,
            future::complete
        ));

        RemotePendingRequest pendingRequest = registry.get("req-1");
        assertNotNull(pendingRequest);
        assertEquals(RemoteRequestType.ASK_USER_QUESTION, pendingRequest.getRequestType());
        assertEquals("session-1", pendingRequest.getSessionId());
        assertEquals("E:/demo", pendingRequest.getProjectPath());

        JsonObject result = new JsonObject();
        result.addProperty("answer", "42");

        assertTrue(registry.complete("req-1", result));
        assertEquals("42", future.get(1, TimeUnit.SECONDS).get("answer").getAsString());
        assertNull(registry.get("req-1"));
    }
}
