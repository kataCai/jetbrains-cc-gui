package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.github.claudecodegui.session.LogicalConversationRecord;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codex 历史逻辑会话聚合器。
 * 该聚合器只负责把按物理 session/thread 读取出来的历史摘要，
 * 按 logicalConversationId 合并为前端可直接消费的一条逻辑会话记录。
 * 它不负责文件读取、收藏/标题文件写回，也不直接操作 WebView 注入链路。
 */
final class HistoryConversationAggregator {

    private static final Logger LOG = Logger.getInstance(HistoryConversationAggregator.class);

    private HistoryConversationAggregator() {
    }

    /**
     * 将 Codex 历史 JSON 聚合为逻辑会话级摘要。
     *
     * @param historyJson 原始历史 JSON
     * @param settingsService 逻辑会话与分段元数据服务
     * @return 聚合后的历史 JSON；异常时回退原始数据
     */
    static String aggregateCodexHistory(String historyJson, CodemossSettingsService settingsService) {
        try {
            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            if (history == null) {
                return historyJson;
            }
            return new Gson().toJson(aggregateCodexHistory(history, settingsService));
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 聚合 Codex 历史失败，回退原始数据: " + e.getMessage());
            return historyJson;
        }
    }

    /**
     * 将 Codex 历史对象中的物理分段摘要聚合成逻辑会话摘要。
     *
     * @param history 原始历史对象
     * @param settingsService 逻辑会话与分段元数据服务
     * @return 聚合后的历史对象
     * @throws Exception 读取逻辑会话或分段元数据失败时抛出
     */
    static JsonObject aggregateCodexHistory(JsonObject history, CodemossSettingsService settingsService) throws Exception {
        if (history == null || !history.has("sessions") || !history.get("sessions").isJsonArray()) {
            return history;
        }

        JsonArray sessions = history.getAsJsonArray("sessions");
        JsonArray aggregatedSessions = new JsonArray();
        Map<String, List<JsonObject>> groupedSessions = new LinkedHashMap<>();

        for (JsonElement sessionElement : sessions) {
            if (sessionElement == null || !sessionElement.isJsonObject()) {
                continue;
            }
            JsonObject session = sessionElement.getAsJsonObject();
            String sessionId = getOptionalString(session, "sessionId");
            if (sessionId.isEmpty()) {
                continue;
            }

            ConversationSegmentRecord segmentRecord = settingsService != null
                    ? settingsService.getConversationSegmentRecord(sessionId)
                    : null;
            String logicalConversationId = segmentRecord != null ? segmentRecord.getLogicalConversationId() : "";
            if (logicalConversationId.isEmpty()) {
                aggregatedSessions.add(session.deepCopy());
                continue;
            }

            groupedSessions.computeIfAbsent(logicalConversationId, ignored -> new ArrayList<>()).add(session.deepCopy());
        }

        for (Map.Entry<String, List<JsonObject>> entry : groupedSessions.entrySet()) {
            String logicalConversationId = entry.getKey();
            LogicalConversationRecord logicalRecord = settingsService != null
                    ? settingsService.getLogicalConversationRecord(logicalConversationId)
                    : null;
            aggregatedSessions.add(buildAggregatedCodexSession(logicalConversationId, entry.getValue(), logicalRecord));
        }

        List<JsonObject> sortedSessions = new ArrayList<>();
        for (JsonElement sessionElement : aggregatedSessions) {
            if (sessionElement != null && sessionElement.isJsonObject()) {
                sortedSessions.add(sessionElement.getAsJsonObject());
            }
        }
        sortedSessions.sort(Comparator.comparingLong(HistoryConversationAggregator::extractComparableTimestamp).reversed());

        JsonArray sortedArray = new JsonArray();
        for (JsonObject session : sortedSessions) {
            sortedArray.add(session);
        }

        JsonObject result = history.deepCopy();
        result.add("sessions", sortedArray);
        result.addProperty("sessionCount", sortedArray.size());
        return result;
    }

    /**
     * 构造逻辑会话级聚合摘要。
     *
     * @param logicalConversationId 逻辑会话 id
     * @param groupedSessions 同一逻辑会话下的物理分段摘要
     * @param logicalRecord 逻辑会话元数据
     * @return 聚合后的单条历史摘要
     */
    private static JsonObject buildAggregatedCodexSession(
            String logicalConversationId,
            List<JsonObject> groupedSessions,
            LogicalConversationRecord logicalRecord
    ) {
        String representativeSessionId = logicalRecord != null && logicalRecord.isMeaningful()
                ? logicalRecord.getLatestSessionId()
                : "";
        JsonObject representative = findSessionById(groupedSessions, representativeSessionId);
        if (representative == null) {
            representative = groupedSessions.stream()
                    .max(Comparator.comparingLong(HistoryConversationAggregator::extractComparableTimestamp))
                    .orElse(groupedSessions.get(0))
                    .deepCopy();
            representativeSessionId = getOptionalString(representative, "sessionId");
        } else {
            representative = representative.deepCopy();
        }

        int totalMessageCount = 0;
        long latestTimestamp = 0L;
        for (JsonObject session : groupedSessions) {
            totalMessageCount += getOptionalInt(session, "messageCount");
            latestTimestamp = Math.max(latestTimestamp, extractComparableTimestamp(session));
        }

        representative.addProperty("logicalConversationId", logicalConversationId);
        representative.addProperty("activeSegmentSessionId", representativeSessionId);
        representative.addProperty("segmentCount",
                logicalRecord != null && logicalRecord.isMeaningful()
                        ? Math.max(logicalRecord.getSegmentCount(), groupedSessions.size())
                        : groupedSessions.size());
        representative.addProperty("continuationPending", false);
        representative.addProperty("messageCount", totalMessageCount);

        if (logicalRecord != null && logicalRecord.isMeaningful()) {
            if (!logicalRecord.getTitle().isEmpty()) {
                representative.addProperty("title", logicalRecord.getTitle());
            }
            if (!logicalRecord.getProvider().isEmpty()) {
                representative.addProperty("provider", logicalRecord.getProvider());
            }
            if (!logicalRecord.getRuntimeFamily().isEmpty()) {
                representative.addProperty("runtimeFamily", logicalRecord.getRuntimeFamily());
            }
            if (!logicalRecord.getLastModel().isEmpty()) {
                representative.addProperty("model", logicalRecord.getLastModel());
            }
            representative.addProperty("isFavorited", logicalRecord.isFavorited());
            if (logicalRecord.getFavoritedAt() > 0L) {
                representative.addProperty("favoritedAt", logicalRecord.getFavoritedAt());
            }
        }

        if (latestTimestamp > 0L) {
            representative.addProperty("lastTimestamp", latestTimestamp);
        }
        return representative;
    }

    /**
     * 在同组分段中按 sessionId 查找代表摘要。
     *
     * @param groupedSessions 同组摘要
     * @param sessionId 目标 sessionId
     * @return 对应摘要；找不到时返回 null
     */
    private static JsonObject findSessionById(List<JsonObject> groupedSessions, String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        for (JsonObject session : groupedSessions) {
            if (sessionId.equals(getOptionalString(session, "sessionId"))) {
                return session;
            }
        }
        return null;
    }

    /**
     * 将 lastTimestamp 统一转换为可比较的毫秒时间戳。
     *
     * @param session 历史摘要
     * @return 可比较时间戳；无法解析时返回 0
     */
    private static long extractComparableTimestamp(JsonObject session) {
        if (session == null || !session.has("lastTimestamp") || session.get("lastTimestamp").isJsonNull()) {
            return 0L;
        }
        JsonElement timestamp = session.get("lastTimestamp");
        try {
            if (timestamp.isJsonPrimitive() && timestamp.getAsJsonPrimitive().isNumber()) {
                return timestamp.getAsLong();
            }
            return java.time.Instant.parse(timestamp.getAsString()).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /**
     * 读取可选字符串字段。
     *
     * @param object 来源对象
     * @param key 字段名
     * @return 去空白后的值；不存在时返回空串
     */
    private static String getOptionalString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return object.get(key).getAsString().trim();
    }

    /**
     * 读取可选整数字段。
     *
     * @param object 来源对象
     * @param key 字段名
     * @return 非负整数；解析失败时返回 0
     */
    private static int getOptionalInt(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return Math.max(0, object.get(key).getAsInt());
        } catch (Exception ignored) {
            return 0;
        }
    }
}
