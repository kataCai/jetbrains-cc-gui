package com.github.claudecodegui.session;

import com.google.gson.JsonObject;

/**
 * 运行时分段元数据。
 * 该对象用于表达逻辑会话主干下的单个物理运行段，每个分段对应一个底层 session/thread 与一份稳定 binding。
 * 它只负责描述“这段运行是如何创建和归属的”，不承载消息内容本身。
 */
public class ConversationSegmentRecord {

    private final String sessionId;
    private final String logicalConversationId;
    private final String parentSessionId;
    private final int segmentIndex;
    private final String provider;
    private final String runtimeFamily;
    private final String model;
    private final String reasoningEffort;
    private final String createdBy;
    private final String carryoverMode;
    private final long createdAt;

    /**
     * 创建运行时分段元数据对象。
     *
     * @param sessionId 当前物理分段的 sessionId/threadId
     * @param logicalConversationId 所属逻辑会话 id
     * @param parentSessionId 父分段 sessionId；首段可为空
     * @param segmentIndex 分段序号，从 0 开始递增
     * @param provider 当前分段绑定的 provider 标识
     * @param runtimeFamily 当前分段所属运行时家族
     * @param model 当前分段模型标识
     * @param reasoningEffort 当前分段思考强度
     * @param createdBy 分段创建原因
     * @param carryoverMode 上下文迁移模式
     * @param createdAt 分段创建时间戳
     */
    public ConversationSegmentRecord(
            String sessionId,
            String logicalConversationId,
            String parentSessionId,
            int segmentIndex,
            String provider,
            String runtimeFamily,
            String model,
            String reasoningEffort,
            String createdBy,
            String carryoverMode,
            long createdAt
    ) {
        this.sessionId = safe(sessionId);
        this.logicalConversationId = safe(logicalConversationId);
        this.parentSessionId = safe(parentSessionId);
        this.segmentIndex = Math.max(0, segmentIndex);
        this.provider = safe(provider);
        this.runtimeFamily = safe(runtimeFamily);
        this.model = safe(model);
        this.reasoningEffort = safe(reasoningEffort);
        this.createdBy = safe(createdBy);
        this.carryoverMode = safe(carryoverMode);
        this.createdAt = Math.max(0L, createdAt);
    }

    /**
     * 从持久化 JSON 中反序列化运行时分段元数据。
     *
     * @param json 持久化后的 JSON 对象
     * @return 解析得到的分段记录；输入为空时返回空语义对象
     */
    public static ConversationSegmentRecord fromJson(JsonObject json) {
        if (json == null) {
            return new ConversationSegmentRecord("", "", "", 0, "", "", "", "", "", "", 0L);
        }
        return new ConversationSegmentRecord(
                readString(json, "sessionId"),
                readString(json, "logicalConversationId"),
                readString(json, "parentSessionId"),
                readInt(json, "segmentIndex"),
                readString(json, "provider"),
                readString(json, "runtimeFamily"),
                readString(json, "model"),
                readString(json, "reasoningEffort"),
                readString(json, "createdBy"),
                readString(json, "carryoverMode"),
                readLong(json, "createdAt")
        );
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getLogicalConversationId() {
        return logicalConversationId;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public int getSegmentIndex() {
        return segmentIndex;
    }

    public String getProvider() {
        return provider;
    }

    public String getRuntimeFamily() {
        return runtimeFamily;
    }

    public String getModel() {
        return model;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getCarryoverMode() {
        return carryoverMode;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 判断分段记录是否具备最小可用标识。
     *
     * @return 只要 sessionId 与 logicalConversationId 均非空，就视为可持久化
     */
    public boolean isMeaningful() {
        return !sessionId.isEmpty() && !logicalConversationId.isEmpty();
    }

    /**
     * 序列化为可持久化 JSON。
     *
     * @return 仅包含分段索引所需字段的 JSON 对象
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("sessionId", sessionId);
        json.addProperty("logicalConversationId", logicalConversationId);
        json.addProperty("parentSessionId", parentSessionId);
        json.addProperty("segmentIndex", segmentIndex);
        json.addProperty("provider", provider);
        json.addProperty("runtimeFamily", runtimeFamily);
        json.addProperty("model", model);
        json.addProperty("reasoningEffort", reasoningEffort);
        json.addProperty("createdBy", createdBy);
        json.addProperty("carryoverMode", carryoverMode);
        json.addProperty("createdAt", createdAt);
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

    private static int readInt(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0;
        }
        return Math.max(0, json.get(key).getAsInt());
    }

    private static long readLong(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0L;
        }
        return Math.max(0L, json.get(key).getAsLong());
    }
}
