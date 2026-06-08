package com.github.claudecodegui.session;

import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.google.gson.JsonObject;

/**
 * Codex 会话绑定元数据。
 * 该对象只保存“继续同一条 Codex 会话时必须稳定命中”的最小非敏感字段，
 * 用于把会话 threadId 与 provider/model/requestMode/baseUrlSource 等运行时选择绑定起来，
 * 避免后续切换全局 active provider 后，旧会话继续发送时误命中新的 provider 配置。
 * <p>
 * 约束说明：
 * 1. 这里只保存非敏感字段，明确不落盘 apiKey/baseUrl 实际值。
 * 2. 该绑定既可挂在 SessionState 内存态，也可序列化到 ~/.codemoss/config.json。
 * 3. 当绑定缺失或 provider 已被删除时，上层允许回退到当前 active provider，
 *    但必须把这种回退视为“兼容性兜底”，而不是默认主路径。
 */
public class CodexSessionBinding {

    private final String providerId;
    private final String model;
    private final String requestMode;
    private final String baseUrlSource;
    private final String effectiveConfigSource;

    /**
     * 创建一份 Codex 会话绑定元数据。
     *
     * @param providerId 当前会话绑定的 provider id
     * @param model 当前会话绑定的模型 id
     * @param requestMode 当前会话绑定的请求模式
     * @param baseUrlSource 当前会话绑定的 endpoint 来源
     * @param effectiveConfigSource 当前会话命中的配置来源
     */
    public CodexSessionBinding(
            String providerId,
            String model,
            String requestMode,
            String baseUrlSource,
            String effectiveConfigSource
    ) {
        this.providerId = safe(providerId);
        this.model = safe(model);
        this.requestMode = safe(requestMode);
        this.baseUrlSource = safe(baseUrlSource);
        this.effectiveConfigSource = safe(effectiveConfigSource);
    }

    /**
     * 从请求级 runtime profile 提取可持久化的会话绑定字段。
     *
     * @param runtimeProfile 当前请求真正命中的运行时 profile
     * @return 提取后的会话绑定；若 profile 为空则返回空绑定
     */
    public static CodexSessionBinding fromRuntimeProfile(CodexRuntimeProfile runtimeProfile) {
        if (runtimeProfile == null) {
            return new CodexSessionBinding("", "", "", "", "");
        }
        return new CodexSessionBinding(
                runtimeProfile.getProviderId(),
                runtimeProfile.getModel(),
                runtimeProfile.getRequestMode(),
                runtimeProfile.getBaseUrlSource(),
                runtimeProfile.getEffectiveConfigSource()
        );
    }

    /**
     * 从 JSON 反序列化会话绑定元数据。
     *
     * @param json 持久化后的 JSON 对象
     * @return 解析得到的绑定；输入为空时返回空绑定
     */
    public static CodexSessionBinding fromJson(JsonObject json) {
        if (json == null) {
            return new CodexSessionBinding("", "", "", "", "");
        }
        return new CodexSessionBinding(
                readString(json, "providerId"),
                readString(json, "model"),
                readString(json, "requestMode"),
                readString(json, "baseUrlSource"),
                readString(json, "effectiveConfigSource")
        );
    }

    public String getProviderId() {
        return providerId;
    }

    public String getModel() {
        return model;
    }

    public String getRequestMode() {
        return requestMode;
    }

    public String getBaseUrlSource() {
        return baseUrlSource;
    }

    public String getEffectiveConfigSource() {
        return effectiveConfigSource;
    }

    /**
     * 判断该绑定是否具备最小可用信息。
     *
     * @return 只要 providerId 或 model 至少存在一个，就视为可参与恢复
     */
    public boolean isMeaningful() {
        return !providerId.isEmpty() || !model.isEmpty();
    }

    /**
     * 序列化为可落盘 JSON。
     *
     * @return 仅包含非敏感字段的 JSON 对象
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("providerId", providerId);
        json.addProperty("model", model);
        json.addProperty("requestMode", requestMode);
        json.addProperty("baseUrlSource", baseUrlSource);
        json.addProperty("effectiveConfigSource", effectiveConfigSource);
        return json;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String readString(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return safe(json.get(key).getAsString());
    }
}
