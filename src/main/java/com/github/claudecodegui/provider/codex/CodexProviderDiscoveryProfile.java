package com.github.claudecodegui.provider.codex;

/**
 * Codex provider 模型发现阶段使用的轻量配置对象。
 * 该对象只承载访问 `/v1/models` 所需的最小字段集合，避免直接复用发送消息链路依赖的
 * {@link CodexRuntimeProfile}，从而把“模型发现”与“请求发送”两条链路的约束隔离开。
 *
 * 适用场景：
 * 1. 设置页基于远端 provider 拉取模型列表。
 * 2. 端点连通性或鉴权能力的最小化探测。
 *
 * 边界约束：
 * 1. 不保存 provider.models、选中模型或推理强度等发送链路字段。
 * 2. 不负责 provider 查找、环境变量展开或网络请求执行。
 * 3. apiKey 仅表示已解析完成的发现凭据，调用方仍需负责脱敏和输出控制。
 */
public class CodexProviderDiscoveryProfile {
    private final String providerId;
    private final String name;
    private final String requestMode;
    private final String authMode;
    private final String baseUrl;
    private final String apiKey;
    private final String credentialSource;

    /**
     * 创建一份模型发现阶段使用的轻量 profile。
     * 构造时会对所有字符串做空安全 trim，避免下游在拼接 Base URL、判断鉴权模式或输出诊断信息时
     * 因空白字符和 null 值产生歧义。
     *
     * @param providerId provider 唯一标识；允许传入 null，内部会归一化为空串
     * @param name provider 显示名称；主要用于诊断输出和 UI 展示
     * @param requestMode 请求模式；当前模型发现链路通常期望为 `codex_sdk`
     * @param authMode 鉴权模式；例如 `api_key`、`api_key_env` 或其他上游已归一化值
     * @param baseUrl provider 基础地址；调用方会基于该值继续补全 `/v1/models`
     * @param apiKey 已解析完成的 API Key；这里只保存值，不负责来源展开
     * @param credentialSource 凭据来源说明；例如 `apiKey` 或 `apiKeyEnv:OPENAI_API_KEY`
     */
    public CodexProviderDiscoveryProfile(
            String providerId,
            String name,
            String requestMode,
            String authMode,
            String baseUrl,
            String apiKey,
            String credentialSource
    ) {
        this.providerId = safe(providerId);
        this.name = safe(name);
        this.requestMode = safe(requestMode);
        this.authMode = safe(authMode);
        this.baseUrl = safe(baseUrl);
        this.apiKey = safe(apiKey);
        this.credentialSource = safe(credentialSource);
    }

    /**
     * 返回当前发现 profile 对应的 provider 标识。
     *
     * @return 已做空安全归一化的 provider id；未提供时返回空串
     */
    public String getProviderId() {
        return providerId;
    }

    /**
     * 返回当前 provider 的显示名称。
     *
     * @return 已归一化的 provider 名称；主要用于界面展示和诊断输出
     */
    public String getName() {
        return name;
    }

    /**
     * 返回模型发现链路使用的请求模式。
     *
     * @return 请求模式字符串；典型值为 `codex_sdk`
     */
    public String getRequestMode() {
        return requestMode;
    }

    /**
     * 返回模型发现链路使用的鉴权模式。
     *
     * @return 鉴权模式字符串；典型值为 `api_key` 或 `api_key_env`
     */
    public String getAuthMode() {
        return authMode;
    }

    /**
     * 返回当前 provider 的基础地址。
     *
     * @return 已归一化的 Base URL；调用方可基于该值继续拼接模型发现端点
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 返回当前发现链路要使用的 API Key。
     * 该值通常会被用于构造 Authorization 请求头，因此调用方在日志和 UI 中输出时必须自行脱敏。
     *
     * @return 已归一化的 API Key；未配置时返回空串
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 返回当前 API Key 的来源描述。
     *
     * @return 凭据来源说明字符串；用于诊断和错误提示，不包含额外解析逻辑
     */
    public String getCredentialSource() {
        return credentialSource;
    }

    /**
     * 判断当前发现 profile 是否持有可直接使用的 API Key。
     *
     * @return true 表示可以继续构造 Bearer Token；false 表示当前凭据为空
     */
    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    /**
     * 对输入字符串执行空安全归一化。
     * 该方法统一把 null 转为空串，并移除首尾空白，避免调用方在每个字段上重复处理。
     *
     * @param value 原始字符串值
     * @return 去除首尾空白后的字符串；当入参为 null 时返回空串
     */
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
