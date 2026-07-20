package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 负责按 OpenAI 兼容协议从远端 Codex provider 发现模型列表。
 * 该服务只聚焦“给定一个 provider，调用其 `/v1/models` 并提取 `data[].id`”这条链路，
 * 不承担 provider 查找、前端提示、配置落盘等编排职责，避免把网络探测逻辑继续塞进操作处理器。
 */
public class CodexProviderModelDiscoveryService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final String ACCEPT_JSON = "application/json";
    private static final String REQUEST_MODE_CODEX_SDK = "codex_sdk";
    private static final String AUTH_MODE_API_KEY = "api_key";
    private static final String AUTH_MODE_API_KEY_ENV = "api_key_env";

    private final CodemossSettingsService settingsService;
    private final Function<String, String> environmentReader;
    private final HttpTransport transport;

    /**
     * 创建默认模型发现服务。
     * 默认实现直接读取当前设置服务、系统环境变量，并通过 Java HttpClient 发起请求。
     *
     * @param settingsService 提供 runtime profile 解析所需的设置访问能力
     */
    public CodexProviderModelDiscoveryService(CodemossSettingsService settingsService) {
        this(settingsService, System::getenv, new JavaHttpTransport());
    }

    /**
     * 创建可注入依赖的模型发现服务。
     * 该入口主要服务于单元测试，允许替换环境变量读取器与 HTTP 传输层。
     *
     * @param settingsService 提供 runtime profile 解析所需的设置访问能力
     * @param environmentReader 读取环境变量值的函数
     * @param transport 发送 HTTP GET 请求的抽象传输层
     */
    public CodexProviderModelDiscoveryService(
            CodemossSettingsService settingsService,
            Function<String, String> environmentReader,
            HttpTransport transport
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.environmentReader = Objects.requireNonNull(environmentReader, "environmentReader");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    /**
     * 调用目标 provider 的远端模型发现接口。
     * 该方法会先复用运行时 profile 解析器拿到已经展开的鉴权信息，再按 OpenAI 兼容协议访问 `/v1/models`。
     *
     * @param provider 目标 provider 配置
     * @return 发现到的唯一模型 id 列表及统计信息
     * @throws IOException 当运行时配置、网络请求或响应解析失败时抛出
     */
    public DiscoveryResult discoverModels(JsonObject provider) throws IOException {
        CodexRuntimeProfile runtimeProfile;
        try {
            runtimeProfile = new CodexRuntimeProfileResolver(settingsService, environmentReader)
                    .resolveForProvider(provider, "", "");
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Failed to resolve provider runtime profile: " + exception.getMessage(), exception);
        }

        validateDiscoverySupport(runtimeProfile);
        URI endpoint = buildModelsEndpoint(runtimeProfile.getBaseUrl());

        TransportResponse response;
        try {
            response = transport.get(
                    endpoint,
                    "Bearer " + runtimeProfile.getApiKey(),
                    ACCEPT_JSON
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Model discovery interrupted", exception);
        }

        if (response.getStatusCode() < 200 || response.getStatusCode() >= 300) {
            throw new IOException(
                    "Model discovery failed with status " + response.getStatusCode()
                            + ": " + summarizeBody(response.getBody())
            );
        }
        return parseDiscoveryResponse(response.getBody());
    }

    /**
     * 校验当前 runtime profile 是否满足第一阶段模型发现能力的约束。
     * 当前仅支持直接使用 Bearer Token 访问 OpenAI 兼容接口的 `codex_sdk` 模式；
     * 其余 requestMode / authMode 必须明确拒绝，避免发出语义不完整的探测请求。
     *
     * @param runtimeProfile 已解析完成的请求级运行时 profile
     */
    private void validateDiscoverySupport(CodexRuntimeProfile runtimeProfile) throws IOException {
        String requestMode = runtimeProfile.getRequestMode();
        if (!REQUEST_MODE_CODEX_SDK.equals(requestMode)) {
            throw new IOException("Unsupported requestMode for model discovery: " + requestMode);
        }

        String authMode = runtimeProfile.getAuthMode();
        if (!AUTH_MODE_API_KEY.equals(authMode) && !AUTH_MODE_API_KEY_ENV.equals(authMode)) {
            throw new IOException("Unsupported authMode for model discovery: " + authMode);
        }

        if (runtimeProfile.getApiKey().isEmpty()) {
            throw new IOException("Codex provider API key is not configured for model discovery");
        }

        if (runtimeProfile.getBaseUrl().isEmpty()) {
            throw new IOException("Codex provider baseUrl is required for model discovery");
        }
    }

    /**
     * 把 provider baseUrl 归一化成 OpenAI 兼容的模型发现端点。
     * 规则与方案文档保持一致：若 baseUrl 已以 `/v1` 结尾，则直接追加 `/models`；
     * 否则补成 `/v1/models`，避免产生重复 `/v1` 或遗漏版本前缀。
     *
     * @param baseUrl provider 基础地址
     * @return 可直接发起 GET 请求的模型发现端点
     */
    private URI buildModelsEndpoint(String baseUrl) {
        String normalized = trimTrailingSlash(baseUrl);
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/models");
        }
        return URI.create(normalized + "/v1/models");
    }

    /**
     * 解析远端 `/v1/models` 响应，只消费 `data[].id`。
     * 该解析器会保留首个出现顺序，同时把空白 id、缺失 id、非对象条目统一计入跳过统计。
     *
     * @param responseBody 远端返回的原始 JSON 文本
     * @return 去重后的模型 id 列表及统计信息
     * @throws IOException 当响应不是合法 JSON 或缺少 `data` 数组时抛出
     */
    private DiscoveryResult parseDiscoveryResponse(String responseBody) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(responseBody == null ? "" : responseBody).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid model discovery response: " + summarizeBody(responseBody), exception);
        }

        if (!root.has("data") || !root.get("data").isJsonArray()) {
            throw new IOException("Invalid model discovery response: missing data array");
        }

        JsonArray data = root.getAsJsonArray("data");
        Set<String> uniqueIds = new LinkedHashSet<>();
        int duplicateCount = 0;
        int skippedCount = 0;
        for (JsonElement element : data) {
            if (element == null || !element.isJsonObject()) {
                skippedCount++;
                continue;
            }
            JsonObject model = element.getAsJsonObject();
            if (!model.has("id") || model.get("id").isJsonNull()) {
                skippedCount++;
                continue;
            }
            String modelId = safeTrim(model.get("id").getAsString());
            if (modelId.isEmpty()) {
                skippedCount++;
                continue;
            }
            if (!uniqueIds.add(modelId)) {
                duplicateCount++;
            }
        }
        return new DiscoveryResult(new ArrayList<>(uniqueIds), duplicateCount, skippedCount);
    }

    /**
     * 生成用于错误提示的响应摘要。
     * 这里只保留有限长度的文本，便于定位 401/404/HTML 错页等异常来源，
     * 同时避免把超长响应全文拼进日志或前端提示。
     *
     * @param responseBody 原始响应文本
     * @return 适合错误提示的短摘要
     */
    private String summarizeBody(String responseBody) {
        if (responseBody == null) {
            return "<null>";
        }
        String normalized = responseBody.replace("\r", "\\r").replace("\n", "\\n").trim();
        if (normalized.isEmpty()) {
            return "<empty>";
        }
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }

    /**
     * 去掉 URL 末尾多余的 `/`，避免后续拼接端点时产生双斜杠。
     *
     * @param value 待归一化的 URL
     * @return 去尾斜杠后的 URL
     */
    private String trimTrailingSlash(String value) {
        String normalized = safeTrim(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 对字符串做空安全 trim。
     *
     * @param value 原始字符串
     * @return 去首尾空白后的字符串；空值时返回空串
     */
    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 远端模型发现的返回值。
     * 该结果对象同时携带唯一模型列表和去重/跳过统计，供后续设置页提示与持久化合并复用。
     */
    public static class DiscoveryResult {
        private final List<String> modelIds;
        private final int duplicateCount;
        private final int skippedCount;

        /**
         * 创建模型发现结果。
         *
         * @param modelIds 去重后的模型 id 列表
         * @param duplicateCount 被判定为重复项的数量
         * @param skippedCount 被判定为无效或不可用项的数量
         */
        public DiscoveryResult(List<String> modelIds, int duplicateCount, int skippedCount) {
            this.modelIds = List.copyOf(modelIds);
            this.duplicateCount = duplicateCount;
            this.skippedCount = skippedCount;
        }

        /**
         * 返回去重后的模型 id 列表。
         *
         * @return 按远端首次出现顺序保留的唯一模型 id
         */
        public List<String> getModelIds() {
            return modelIds;
        }

        /**
         * 返回重复条目数量。
         *
         * @return 远端响应中被判定为重复 id 的项数
         */
        public int getDuplicateCount() {
            return duplicateCount;
        }

        /**
         * 返回跳过条目数量。
         *
         * @return 远端响应中因缺失/空白/非法结构而被跳过的项数
         */
        public int getSkippedCount() {
            return skippedCount;
        }
    }

    /**
     * 抽象模型发现 HTTP 传输层。
     * 该接口用于隔离网络访问细节，让单元测试能在不启动真实 HTTP 服务的前提下校验 URL 与头部拼装。
     */
    public interface HttpTransport {

        /**
         * 发起一次模型发现 GET 请求。
         *
         * @param uri 目标端点
         * @param authorizationHeader Authorization 请求头
         * @param acceptHeader Accept 请求头
         * @return 状态码与响应体
         * @throws IOException 网络或协议错误
         * @throws InterruptedException 线程被中断时抛出
         */
        TransportResponse get(URI uri, String authorizationHeader, String acceptHeader)
                throws IOException, InterruptedException;
    }

    /**
     * 承载 HTTP 响应状态码与响应体的简单值对象。
     * 该对象只服务于 discovery service 内部，不扩展额外协议字段，保持测试桩和默认实现的边界最小化。
     */
    public static class TransportResponse {
        private final int statusCode;
        private final String body;

        /**
         * 创建一份传输响应。
         *
         * @param statusCode HTTP 状态码
         * @param body 响应体文本
         */
        public TransportResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body == null ? "" : body;
        }

        /**
         * 返回 HTTP 状态码。
         *
         * @return 状态码
         */
        public int getStatusCode() {
            return statusCode;
        }

        /**
         * 返回响应体文本。
         *
         * @return 原始响应体；空值时返回空串
         */
        public String getBody() {
            return body;
        }
    }

    /**
     * 基于 Java HttpClient 的默认传输实现。
     * 该实现统一设置连接/请求超时、Accept 头与 UTF-8 字符串响应处理，
     * 让 discovery service 自身只关注协议拼装和结果解析。
     */
    private static class JavaHttpTransport implements HttpTransport {
        private final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        /**
         * 发送远端模型发现 GET 请求。
         *
         * @param uri 目标端点
         * @param authorizationHeader Authorization 请求头
         * @param acceptHeader Accept 请求头
         * @return 响应状态码与响应体
         * @throws IOException 网络错误
         * @throws InterruptedException 请求线程被中断
         */
        @Override
        public TransportResponse get(URI uri, String authorizationHeader, String acceptHeader)
                throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", authorizationHeader)
                    .header("Accept", acceptHeader)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            return new TransportResponse(response.statusCode(), response.body());
        }
    }
}
