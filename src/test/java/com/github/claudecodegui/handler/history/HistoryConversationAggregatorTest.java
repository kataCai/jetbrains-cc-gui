package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.github.claudecodegui.session.LogicalConversationRecord;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HistoryConversationAggregator 测试。
 * 用于验证 Codex 多物理分段历史在返回前会按 logicalConversationId 聚合，
 * 避免前端历史列表继续把同一连续会话拆成多条记录。
 */
public class HistoryConversationAggregatorTest {

    /**
     * 验证同一逻辑会话下的两段 Codex 历史会聚合为单条摘要，
     * 并保留最新活动分段、累计消息数与逻辑会话标题等关键字段。
     */
    @Test
    public void shouldAggregateCodexSegmentsIntoSingleLogicalConversation() throws Exception {
        JsonObject history = new JsonObject();
        history.addProperty("success", true);
        history.addProperty("total", 12);

        JsonArray sessions = new JsonArray();
        sessions.add(createSession("segment-001", "First title", 5, 1719655200000L));
        sessions.add(createSession("segment-002", "Second title", 7, 1719658800000L));
        history.add("sessions", sessions);

        StubSettingsService settingsService = new StubSettingsService();
        settingsService.logicalConversationRecord = new LogicalConversationRecord(
                "logical-001",
                "segment-001",
                "segment-002",
                "Logical Title",
                "codex",
                "codex",
                "gpt-5.4",
                2,
                1719655200000L,
                1719658800000L,
                true,
                1719658800000L
        );
        settingsService.segment001 = new ConversationSegmentRecord(
                "segment-001",
                "logical-001",
                "",
                0,
                "codex",
                "codex",
                "gpt-5.4",
                "medium",
                "initial",
                "none",
                1719655200000L
        );
        settingsService.segment002 = new ConversationSegmentRecord(
                "segment-002",
                "logical-001",
                "segment-001",
                1,
                "codex",
                "codex",
                "gpt-5.4",
                "medium",
                "runtime_switch:model",
                "carryover",
                1719658800000L
        );

        JsonObject aggregated = HistoryConversationAggregator.aggregateCodexHistory(history, settingsService);

        JsonArray aggregatedSessions = aggregated.getAsJsonArray("sessions");
        assertEquals(1, aggregatedSessions.size());

        JsonObject session = aggregatedSessions.get(0).getAsJsonObject();
        assertEquals("logical-001", session.get("logicalConversationId").getAsString());
        assertEquals("segment-002", session.get("activeSegmentSessionId").getAsString());
        assertEquals("Logical Title", session.get("title").getAsString());
        assertEquals(12, session.get("messageCount").getAsInt());
        assertEquals(2, session.get("segmentCount").getAsInt());
        assertEquals("gpt-5.4", session.get("model").getAsString());
        assertTrue(session.get("isFavorited").getAsBoolean());
    }

    /**
     * 构造最小可用的历史摘要对象。
     *
     * @param sessionId 物理分段 sessionId
     * @param title 摘要标题
     * @param messageCount 消息数
     * @param lastTimestamp 最后时间戳
     * @return 历史摘要 JSON
     */
    private static JsonObject createSession(String sessionId, String title, int messageCount, long lastTimestamp) {
        JsonObject session = new JsonObject();
        session.addProperty("sessionId", sessionId);
        session.addProperty("title", title);
        session.addProperty("messageCount", messageCount);
        session.addProperty("lastTimestamp", lastTimestamp);
        session.addProperty("provider", "codex");
        session.addProperty("runtimeFamily", "codex");
        return session;
    }

    /**
     * 逻辑会话聚合测试专用设置服务桩。
     * 只覆写聚合器所需的两个读取入口，避免依赖真实配置文件。
     */
    private static final class StubSettingsService extends CodemossSettingsService {
        private LogicalConversationRecord logicalConversationRecord;
        private ConversationSegmentRecord segment001;
        private ConversationSegmentRecord segment002;

        /**
         * 按 logicalConversationId 返回预置逻辑会话记录。
         *
         * @param logicalConversationId 逻辑会话 id
         * @return 对应逻辑会话记录
         */
        @Override
        public LogicalConversationRecord getLogicalConversationRecord(String logicalConversationId) {
            return logicalConversationRecord != null
                    && logicalConversationRecord.getLogicalConversationId().equals(logicalConversationId)
                    ? logicalConversationRecord
                    : null;
        }

        /**
         * 按物理 sessionId 返回预置分段记录。
         *
         * @param sessionId 物理分段 sessionId
         * @return 对应分段记录
         */
        @Override
        public ConversationSegmentRecord getConversationSegmentRecord(String sessionId) {
            if ("segment-001".equals(sessionId)) {
                return segment001;
            }
            if ("segment-002".equals(sessionId)) {
                return segment002;
            }
            return null;
        }
    }
}
