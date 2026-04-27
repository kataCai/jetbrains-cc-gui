package com.github.claudecodegui.remote.providers.gotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Gotify/Web 后台 HTTP 客户端。
 * 负责统一封装健康检查、创建请求和轮询结果三个最小接口，避免 provider 层散落 URL、鉴权和 JSON 解析逻辑。
 */
public class GotifyWorkspaceClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final Gson gson;

    public GotifyWorkspaceClient() {
        this(HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(), new Gson());
    }

    GotifyWorkspaceClient(HttpClient httpClient, Gson gson) {
        this.httpClient = httpClient;
        this.gson = gson;
    }

    /**
     * 检查后台服务是否可达，并返回后台原始健康信息。
     */
    public JsonObject healthCheck(JsonObject providerConfig) throws IOException, InterruptedException {
        JsonObject response = sendRequest(providerConfig, "/health", "GET", null, 200);
        return response == null ? new JsonObject() : response;
    }

    /**
     * 向后台创建一个工作台请求，并优先使用后台回传的工作台链接。
     */
    public GotifyWorkspaceCreateResult createRequest(JsonObject providerConfig, GotifyWorkspaceRequest request)
        throws IOException, InterruptedException {
        JsonObject response = sendRequest(
            providerConfig,
            "/api/review/request",
            "POST",
            request == null ? new JsonObject() : request.toJson(),
            201
        );
        String backendRequestId = readString(response, "requestId");
        String workspaceLink = readString(response, "workspaceLink");
        if (workspaceLink.isEmpty()) {
            workspaceLink = buildWorkspaceLink(providerConfig, backendRequestId);
        }
        return new GotifyWorkspaceCreateResult(backendRequestId, workspaceLink);
    }

    /**
     * 拉取指定请求的最新处理结果，供轮询器决定是否回写本地请求。
     */
    public GotifyWorkspacePollResult getResult(JsonObject providerConfig, String requestId)
        throws IOException, InterruptedException {
        JsonObject response = sendRequest(
            providerConfig,
            "/api/review/request/" + encodePath(requestId) + "/result",
            "GET",
            null,
            200
        );
        JsonObject latestActionJson = response != null && response.has("latestAction") && response.get("latestAction").isJsonObject()
            ? response.getAsJsonObject("latestAction")
            : null;
        GotifyWorkspaceAction latestAction = latestActionJson == null
            ? null
            : new GotifyWorkspaceAction(
                readString(latestActionJson, "actionType"),
                latestActionJson.has("payload") && latestActionJson.get("payload").isJsonObject()
                    ? latestActionJson.getAsJsonObject("payload")
                    : new JsonObject()
            );
        return new GotifyWorkspacePollResult(
            readString(response, "requestId"),
            readString(response, "status"),
            readInt(response, "actionCount", 0),
            latestAction
        );
    }

    /**
     * 统一构造并发送后台请求，集中处理鉴权、超时、状态码和 JSON 解析。
     */
    private JsonObject sendRequest(
        JsonObject providerConfig,
        String relativePath,
        String method,
        JsonObject body,
        int expectedStatus
    ) throws IOException, InterruptedException {
        String serverUrl = readRequiredConfig(providerConfig, "serverUrl");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(trimTrailingSlash(serverUrl) + relativePath))
            .timeout(DEFAULT_TIMEOUT)
            .header("Accept", "application/json");
        String apiToken = readString(providerConfig, "apiToken");
        if (!apiToken.isEmpty()) {
            builder.header("Authorization", "Bearer " + apiToken);
        }
        if ("POST".equals(method)) {
            String payload = gson.toJson(body == null ? new JsonObject() : body);
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != expectedStatus) {
            throw new IOException("Unexpected backend status: " + response.statusCode());
        }
        if (response.body() == null || response.body().trim().isEmpty()) {
            return new JsonObject();
        }
        JsonObject json = gson.fromJson(response.body(), JsonObject.class);
        return json == null ? new JsonObject() : json;
    }

    /**
     * 当后台暂未显式返回工作台链接时，按约定规则兜底拼装链接，避免调试页无法跳转。
     */
    private String buildWorkspaceLink(JsonObject providerConfig, String requestId) {
        String baseUrl = readString(providerConfig, "workspaceBaseUrl");
        if (baseUrl.isEmpty() || requestId == null || requestId.trim().isEmpty()) {
            return "";
        }
        return trimTrailingSlash(baseUrl) + "/request/" + requestId.trim();
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value == null ? "" : value.trim(), StandardCharsets.UTF_8);
    }

    private static String readRequiredConfig(JsonObject config, String key) {
        String value = readString(config, key);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        return value;
    }

    private static String readString(JsonObject source, String key) {
        if (source == null || key == null || !source.has(key) || source.get(key).isJsonNull()) {
            return "";
        }
        String value = source.get(key).getAsString();
        return value == null ? "" : value.trim();
    }

    private static int readInt(JsonObject source, String key, int fallback) {
        if (source == null || key == null || !source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return source.get(key).getAsInt();
        } catch (RuntimeException ignore) {
            return fallback;
        }
    }

    private static String trimTrailingSlash(String url) {
        String normalized = url == null ? "" : url.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}