package com.github.claudecodegui.remote.providers.gotify;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Gotify/Web HTTP 客户端是否按后台约定访问健康检查、创建请求和读取结果接口。
 */
public class GotifyWorkspaceClientTest {

    private HttpServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void shouldCallHealthEndpointAndParseHealthyStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/health", exchange -> writeJson(exchange, 200, "{\"status\":\"ok\"}"));
        server.start();

        GotifyWorkspaceClient client = new GotifyWorkspaceClient();
        JsonObject providerConfig = createProviderConfig(serverBaseUrl());

        JsonObject result = client.healthCheck(providerConfig);

        assertEquals("ok", result.get("status").getAsString());
    }

    @Test
    public void shouldCreateWorkspaceRequestAndParseWorkspaceLink() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/review/request", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String requestBody = readBody(exchange);
            assertTrue(requestBody.contains("\"requestId\":\"local-req-1\""));
            assertTrue(requestBody.contains("\"requestType\":\"plan_approval\""));
            writeJson(
                exchange,
                201,
                "{\"requestId\":\"backend-req-1\",\"workspaceLink\":\"http://workspace.local/request/backend-req-1\"}"
            );
        });
        server.start();

        GotifyWorkspaceClient client = new GotifyWorkspaceClient();
        GotifyWorkspaceRequest request = new GotifyWorkspaceRequest(
            "local-req-1",
            "plan_approval",
            "Plan approval",
            "Need remote confirmation",
            "summary"
        );

        GotifyWorkspaceCreateResult result = client.createRequest(createProviderConfig(serverBaseUrl()), request);

        assertEquals("backend-req-1", result.getRequestId());
        assertEquals("http://workspace.local/request/backend-req-1", result.getWorkspaceLink());
    }

    @Test
    public void shouldReadWorkspaceResultAndLatestAction() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/review/request/backend-req-1/result", exchange -> writeJson(
            exchange,
            200,
            "{\"requestId\":\"backend-req-1\",\"status\":\"action_received\",\"actionCount\":1," +
                "\"latestAction\":{\"actionType\":\"approve\",\"payload\":{\"comment\":\"looks good\",\"targetMode\":\"acceptEdits\"}}}"
        ));
        server.start();

        GotifyWorkspaceClient client = new GotifyWorkspaceClient();

        GotifyWorkspacePollResult result = client.getResult(createProviderConfig(serverBaseUrl()), "backend-req-1");

        assertEquals("backend-req-1", result.getRequestId());
        assertEquals("action_received", result.getStatus());
        assertEquals("approve", result.getLatestAction().getActionType());
        assertEquals("acceptEdits", result.getLatestAction().getPayload().get("targetMode").getAsString());
    }

    private JsonObject createProviderConfig(String serverUrl) {
        JsonObject config = new JsonObject();
        config.addProperty("serverUrl", serverUrl);
        config.addProperty("apiToken", "token");
        config.addProperty("workspaceBaseUrl", "http://workspace.local");
        return config;
    }

    private String serverBaseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
