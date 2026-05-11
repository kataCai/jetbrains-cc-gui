package com.github.claudecodegui.remote.feishu;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Feishu Open API 最小客户端。
 * 当前只覆盖第一阶段所需的租户鉴权和私聊测试消息发送，后续再继续补事件订阅和卡片交互。
 */
public class FeishuMessageClient {

    private static final String API_BASE = "https://open.feishu.cn/open-apis";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final int RESPONSE_SNIPPET_LIMIT = 200;

    private final String appId;
    private final String appSecret;
    private final HttpTransport transport;

    public FeishuMessageClient(String appId, String appSecret) {
        this(appId, appSecret, new JavaHttpTransport());
    }

    FeishuMessageClient(String appId, String appSecret, HttpTransport transport) {
        this.appId = requireText(appId, "appId");
        this.appSecret = requireText(appSecret, "appSecret");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public JsonObject getTenantAccessToken() throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("app_id", appId);
        body.addProperty("app_secret", appSecret);
        return post("/auth/v3/tenant_access_token/internal", null, body);
    }

    public JsonObject sendTextMessage(String tenantAccessToken, String openId, String text) throws IOException {
        JsonObject content = new JsonObject();
        content.addProperty("text", text);

        JsonObject body = new JsonObject();
        body.addProperty("receive_id", requireText(openId, "openId"));
        body.addProperty("msg_type", "text");
        body.addProperty("content", content.toString());
        return post("/im/v1/messages?receive_id_type=open_id", tenantAccessToken, body);
    }

    private JsonObject post(String path, String bearerToken, JsonObject body) throws IOException {
        String responseBody;
        try {
            responseBody = transport.postJson(API_BASE + path, bearerToken, body == null ? new JsonObject() : body.deepCopy());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Feishu API interrupted: " + path, e);
        }

        try {
            JsonObject response = JsonParser.parseString(responseBody).getAsJsonObject();
            int code = response.has("code") && !response.get("code").isJsonNull()
                ? response.get("code").getAsInt()
                : -1;
            if (code != 0) {
                String message = response.has("msg") && !response.get("msg").isJsonNull()
                    ? response.get("msg").getAsString()
                    : "unknown feishu error";
                throw new IOException("Feishu API " + path + " failed: " + message);
            }
            return response;
        } catch (RuntimeException e) {
            throw new IOException(
                "Invalid Feishu API response for " + path + ": " + summarizeResponse(responseBody),
                e
            );
        }
    }

    private String summarizeResponse(String responseBody) {
        if (responseBody == null) {
            return "<null>";
        }
        String normalized = responseBody
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .trim();
        if (normalized.isEmpty()) {
            return "<empty>";
        }
        if (normalized.length() <= RESPONSE_SNIPPET_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, RESPONSE_SNIPPET_LIMIT) + "...";
    }

    private String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    interface HttpTransport {
        String postJson(String url, String bearerToken, JsonObject body) throws IOException, InterruptedException;
    }

    private static final class JavaHttpTransport implements HttpTransport {
        private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
        private final Gson gson = new Gson();

        @Override
        public String postJson(String url, String bearerToken, JsonObject body) throws IOException, InterruptedException {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json; charset=UTF-8");
            if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                builder.header("Authorization", "Bearer " + bearerToken.trim());
            }
            HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        }
    }
}
