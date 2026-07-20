package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Codex provider 远端模型发现服务。
 * 这些测试聚焦设置页“获取模型列表”能力的三个核心约束：
 * 1. 必须按 OpenAI 兼容协议把 baseUrl 归一化到正确的 `/v1/models` 端点。
 * 2. 必须使用解析后的 Bearer Token 发起 GET 请求，并只消费响应里的 `data[].id`。
 * 3. 对当前阶段不支持的 requestMode / authMode 必须明确报错，不能静默失败或继续探测。
 */
public class CodexProviderModelDiscoveryServiceTest {

    /**
     * 验证目标：
     * 当 provider 的 baseUrl 还不包含 `/v1` 后缀时，服务应自动补成 `/v1/models`；
     * 同时必须使用 Bearer 头发起请求，并对重复、空白、缺失 id 的条目做有序过滤。
     *
     * 断言意图：
     * 1. 最终请求 URL 为 `https://provider.example.com/v1/models`。
     * 2. Authorization 头来自解析后的环境变量密钥。
     * 3. 返回结果保留首个有效顺序，只统计真正新增的唯一模型 id。
     */
    @Test
    public void shouldDiscoverModelsViaNormalizedOpenAiModelsEndpoint() throws Exception {
        DiscoverySettingsService settingsService = new DiscoverySettingsService();
        Map<String, String> env = new HashMap<>();
        env.put("DISCOVERY_KEY", "secret-value");
        RecordingTransport transport = new RecordingTransport(
                200,
                "{\"object\":\"list\",\"data\":["
                        + "{\"id\":\"gpt-5.5\"},"
                        + "{\"id\":\" gpt-5.5 \"},"
                        + "{\"id\":\"gpt-5.4-mini\"},"
                        + "{\"id\":\"   \"},"
                        + "{\"object\":\"model\"},"
                        + "\"ignored\""
                        + "]}"
        );
        CodexProviderModelDiscoveryService service = new CodexProviderModelDiscoveryService(
                settingsService,
                env::get,
                transport
        );

        CodexProviderModelDiscoveryService.DiscoveryResult result = service.discoverModels(
                createProvider("provider-a", "https://provider.example.com", "api_key_env", "codex_sdk")
        );

        assertEquals("https://provider.example.com/v1/models", transport.requestUri.toString());
        assertEquals("Bearer secret-value", transport.authorizationHeader);
        assertEquals("application/json", transport.acceptHeader);
        assertEquals(List.of("gpt-5.5", "gpt-5.4-mini"), result.getModelIds());
        assertEquals(1, result.getDuplicateCount());
        assertEquals(3, result.getSkippedCount());
    }

    /**
     * 验证目标：
     * 当 provider baseUrl 已经显式带有 `/v1` 后缀时，服务不能再重复拼接一次 `/v1`，
     * 否则会把合法网关地址错误地变成 `/v1/v1/models` 并造成 404。
     *
     * 断言意图：
     * 只要 baseUrl 已经以 `/v1` 结尾，无论是否带尾部 `/`，都应该直接追加 `/models`。
     */
    @Test
    public void shouldReuseExistingV1SuffixWhenBuildingModelsEndpoint() throws Exception {
        RecordingTransport transport = new RecordingTransport(200, "{\"object\":\"list\",\"data\":[]}");
        CodexProviderModelDiscoveryService service = new CodexProviderModelDiscoveryService(
                new DiscoverySettingsService(),
                key -> "secret-value",
                transport
        );

        service.discoverModels(createProvider("provider-a", "https://provider.example.com/v1/", "api_key", "codex_sdk"));

        assertEquals("https://provider.example.com/v1/models", transport.requestUri.toString());
    }

    /**
     * 验证目标：
     * 当前设置页模型发现链路只支持直接走 OpenAI 兼容 HTTP 的 `codex_sdk` requestMode，
     * 对尚未落地的代理模式必须直接拒绝，避免前端误以为拉取成功只是“没有模型”。
     *
     * 断言意图：
     * 传入 `cc_switch_proxy` 后应抛出明确异常，提示当前 requestMode 暂不支持模型发现。
     */
    @Test
    public void shouldRejectUnsupportedRequestModeForDiscovery() {
        CodexProviderModelDiscoveryService service = new CodexProviderModelDiscoveryService(
                new DiscoverySettingsService(),
                key -> "secret-value",
                new RecordingTransport(200, "{\"object\":\"list\",\"data\":[]}")
        );

        try {
            service.discoverModels(createProvider("provider-a", "https://provider.example.com", "api_key", "cc_switch_proxy"));
        } catch (Exception exception) {
            assertTrue(exception.getMessage().contains("requestMode"));
            return;
        }

        throw new AssertionError("Expected discovery to reject unsupported requestMode");
    }

    /**
     * 验证目标：
     * 对 `proxy` / `oauth` 这类当前无法稳定解析 Bearer Token 的鉴权模式，后端必须明确拒绝探测，
     * 避免发出缺失鉴权头的请求后再把 401/403 误报成“供应商不支持模型发现”。
     *
     * 断言意图：
     * 传入 `authMode=proxy` 时，应直接抛出包含 `authMode` 的错误信息。
     */
    @Test
    public void shouldRejectUnsupportedAuthModeForDiscovery() {
        CodexProviderModelDiscoveryService service = new CodexProviderModelDiscoveryService(
                new DiscoverySettingsService(),
                key -> "secret-value",
                new RecordingTransport(200, "{\"object\":\"list\",\"data\":[]}")
        );

        try {
            service.discoverModels(createProvider("provider-a", "https://provider.example.com", "proxy", "codex_sdk"));
        } catch (Exception exception) {
            assertTrue(exception.getMessage().contains("authMode"));
            return;
        }

        throw new AssertionError("Expected discovery to reject unsupported authMode");
    }

    /**
     * 构造一个最小可解析的托管 provider。
     * 这里强制带上一条种子模型，是因为运行时 profile 解析器需要至少一个模型 id 才能完成请求级配置解析。
     *
     * @param id provider id
     * @param baseUrl provider 基础地址
     * @param authMode 鉴权模式
     * @param requestMode 请求模式
     * @return 满足测试需要的 provider 配置
     */
    private static JsonObject createProvider(String id, String baseUrl, String authMode, String requestMode) {
        JsonObject provider = new JsonObject();
        provider.addProperty("id", id);
        provider.addProperty("name", "Provider " + id);
        provider.addProperty("baseUrl", baseUrl);
        provider.addProperty("authMode", authMode);
        provider.addProperty("requestMode", requestMode);
        provider.addProperty("apiKeyEnv", "DISCOVERY_KEY");
        JsonArray models = new JsonArray();
        JsonObject model = new JsonObject();
        model.addProperty("id", "seed-model");
        model.addProperty("label", "Seed Model");
        models.add(model);
        provider.add("models", models);
        return provider;
    }

    /**
     * 提供给运行时 profile 解析器使用的最小设置服务桩。
     * 该测试只关心 provider 自身字段，不依赖当前激活 provider、选中模型或本地 `~/.codex` 状态，
     * 因此所有读取都返回空对象，确保断言聚焦在 discovery service 本身。
     */
    private static class DiscoverySettingsService extends CodemossSettingsService {

        @Override
        public JsonObject getSelectedCodexModel() {
            return new JsonObject();
        }

        @Override
        public JsonObject getCurrentCodexModelState() {
            return new JsonObject();
        }
    }

    /**
     * 记录请求参数的传输桩。
     * 该桩不做真实网络访问，只保存 discovery service 组装出的 URI 与请求头，
     * 让测试能直接断言 URL 归一化与 Bearer 认证是否符合预期。
     */
    private static class RecordingTransport implements CodexProviderModelDiscoveryService.HttpTransport {
        private final int statusCode;
        private final String responseBody;
        private URI requestUri;
        private String authorizationHeader = "";
        private String acceptHeader = "";

        RecordingTransport(int statusCode, String responseBody) {
            this.statusCode = statusCode;
            this.responseBody = responseBody;
        }

        @Override
        public CodexProviderModelDiscoveryService.TransportResponse get(
                URI uri,
                String authorizationHeader,
                String acceptHeader
        ) throws IOException {
            this.requestUri = uri;
            this.authorizationHeader = authorizationHeader;
            this.acceptHeader = acceptHeader;
            return new CodexProviderModelDiscoveryService.TransportResponse(statusCode, responseBody);
        }
    }
}
