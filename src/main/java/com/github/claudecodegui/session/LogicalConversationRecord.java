package com.github.claudecodegui.session;

import com.google.gson.JsonObject;

/**
 * 逻辑会话元数据。
 * 该对象用于表达用户视角的一条“连续会话主干”，把历史展示身份与底层物理 session 身份解耦。
 * 它只保存聚合层所需的非敏感字段，不保存 provider 私有运行态，也不直接承载底层 thread 恢复职责。
 */
public class LogicalConversationRecord {

    private final String logicalConversationId;
    private final String rootSessionId;
    private final String latestSessionId;
    private final String title;
    private final String runtimeFamily;
    private final String provider;
    private final String lastModel;
    private final int segmentCount;
    private final long createdAt;
    private final long updatedAt;
    private final boolean favorited;
    private final long favoritedAt;

    /**
     * 创建逻辑会话元数据对象。
     *
     * @param logicalConversationId 逻辑会话唯一标识
     * @param rootSessionId 首个物理分段对应的 sessionId
     * @param latestSessionId 当前最新活动分段对应的 sessionId
     * @param title 逻辑会话标题
     * @param runtimeFamily 当前主干所属运行时家族
     * @param provider 当前最新 provider 标识
     * @param lastModel 当前最新模型标识
     * @param segmentCount 当前逻辑会话下的分段数量
     * @param createdAt 逻辑会话创建时间戳
     * @param updatedAt 逻辑会话最近更新时间戳
     * @param favorited 是否已收藏
     * @param favoritedAt 收藏时间戳；未收藏时通常为 0
     */
    public LogicalConversationRecord(
            String logicalConversationId,
            String rootSessionId,
            String latestSessionId,
            String title,
            String runtimeFamily,
            String provider,
            String lastModel,
            int segmentCount,
            long createdAt,
            long updatedAt,
            boolean favorited,
            long favoritedAt
    ) {
        this.logicalConversationId = safe(logicalConversationId);
        this.rootSessionId = safe(rootSessionId);
        this.latestSessionId = safe(latestSessionId);
        this.title = safe(title);
        this.runtimeFamily = safe(runtimeFamily);
        this.provider = safe(provider);
        this.lastModel = safe(lastModel);
        this.segmentCount = Math.max(0, segmentCount);
        this.createdAt = Math.max(0L, createdAt);
        this.updatedAt = Math.max(0L, updatedAt);
        this.favorited = favorited;
        this.favoritedAt = Math.max(0L, favoritedAt);
    }

    /**
     * 从持久化 JSON 中反序列化逻辑会话元数据。
     *
     * @param json 持久化后的 JSON 对象
     * @return 解析得到的逻辑会话记录；输入为空时返回空语义对象
     */
    public static LogicalConversationRecord fromJson(JsonObject json) {
        if (json == null) {
            return new LogicalConversationRecord("", "", "", "", "", "", "", 0, 0L, 0L, false, 0L);
        }
        return new LogicalConversationRecord(
                readString(json, "logicalConversationId"),
                readString(json, "rootSessionId"),
                readString(json, "latestSessionId"),
                readString(json, "title"),
                readString(json, "runtimeFamily"),
                readString(json, "provider"),
                readString(json, "lastModel"),
                readInt(json, "segmentCount"),
                readLong(json, "createdAt"),
                readLong(json, "updatedAt"),
                readBoolean(json, "isFavorited"),
                readLong(json, "favoritedAt")
        );
    }

    public String getLogicalConversationId() {
        return logicalConversationId;
    }

    public String getRootSessionId() {
        return rootSessionId;
    }

    public String getLatestSessionId() {
        return latestSessionId;
    }

    public String getTitle() {
        return title;
    }

    public String getRuntimeFamily() {
        return runtimeFamily;
    }

    public String getProvider() {
        return provider;
    }

    public String getLastModel() {
        return lastModel;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public boolean isFavorited() {
        return favorited;
    }

    public long getFavoritedAt() {
        return favoritedAt;
    }

    /**
     * 判断记录是否具备最小可用标识。
     *
     * @return 只要逻辑会话 id 非空，就视为可持久化
     */
    public boolean isMeaningful() {
        return !logicalConversationId.isEmpty();
    }

    /**
     * 序列化为可持久化 JSON。
     *
     * @return 仅包含逻辑会话聚合层所需字段的 JSON 对象
     */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("logicalConversationId", logicalConversationId);
        json.addProperty("rootSessionId", rootSessionId);
        json.addProperty("latestSessionId", latestSessionId);
        json.addProperty("title", title);
        json.addProperty("runtimeFamily", runtimeFamily);
        json.addProperty("provider", provider);
        json.addProperty("lastModel", lastModel);
        json.addProperty("segmentCount", segmentCount);
        json.addProperty("createdAt", createdAt);
        json.addProperty("updatedAt", updatedAt);
        json.addProperty("isFavorited", favorited);
        json.addProperty("favoritedAt", favoritedAt);
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

    private static boolean readBoolean(JsonObject json, String key) {
        if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
            return false;
        }
        return json.get(key).getAsBoolean();
    }
}
