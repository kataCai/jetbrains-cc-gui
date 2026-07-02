package com.github.claudecodegui.handler.history;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HistoryDeleteServiceTest {

    @Test
    public void parseSessionIdsAcceptsArrayPayload() {
        assertEquals(
                Arrays.asList("session-one", "session-two"),
                HistoryDeleteService.parseSessionIds("[\"session-one\",\"session-two\"]"));
    }

    @Test
    public void parseSessionIdsAcceptsObjectPayload() {
        assertEquals(
                Arrays.asList("session-one", "session-two"),
                HistoryDeleteService.parseSessionIds("{\"sessionIds\":[\"session-one\",\"session-two\"]}"));
    }

    @Test
    public void parseSessionIdsAcceptsObjectArrayPayload() {
        assertEquals(
                Arrays.asList("session-one", "session-two"),
                HistoryDeleteService.parseSessionIds("[{\"sessionId\":\"session-one\"},{\"sessionId\":\"session-two\"}]"));
    }

    /**
     * 验证单条删除请求在收到逻辑会话载荷时，能够正确解析代表分段 sessionId 与逻辑会话 id。
     * 该用例直接覆盖当前前端已经发送 JSON 对象、而后端仍按纯 sessionId 字符串处理时会被误判为非法 ID 的问题。
     */
    @Test
    public void parseDeleteRequestAcceptsLogicalConversationPayload() {
        HistoryDeleteService.DeleteRequest request = HistoryDeleteService.parseDeleteRequest(
                "{\"sessionId\":\"segment-two\",\"logicalConversationId\":\"logical-one\"}");

        assertEquals("segment-two", request.getSessionId());
        assertEquals("logical-one", request.getLogicalConversationId());
    }

    /**
     * 验证批量删除在收到逻辑会话聚合后的对象数组时，会按 logicalConversationId 去重并保留逻辑会话信息。
     * 这样前端即便把同一逻辑会话下的多个分段都传回后端，后端也只会执行一次逻辑会话级联删除。
     */
    @Test
    public void parseDeleteRequestsDeduplicatesLogicalConversationTargets() {
        java.util.List<HistoryDeleteService.DeleteRequest> requests = HistoryDeleteService.parseDeleteRequests(
                "[" +
                        "{\"sessionId\":\"segment-001\",\"logicalConversationId\":\"logical-001\"}," +
                        "{\"sessionId\":\"segment-002\",\"logicalConversationId\":\"logical-001\"}," +
                        "{\"sessionId\":\"standalone-003\",\"logicalConversationId\":\"\"}" +
                        "]"
        );

        assertEquals(2, requests.size());
        assertEquals("segment-001", requests.get(0).getSessionId());
        assertEquals("logical-001", requests.get(0).getLogicalConversationId());
        assertEquals("standalone-003", requests.get(1).getSessionId());
        assertEquals("", requests.get(1).getLogicalConversationId());
    }

    @Test
    public void parseSessionIdsTrimsAndDeduplicates() {
        assertEquals(
                Arrays.asList("session-one", "session-two"),
                HistoryDeleteService.parseSessionIds("[\" session-one \",\"session-two\",\"session-one\",\"\"]"));
    }

    @Test
    public void parseSessionIdsRejectsMissingPayload() {
        assertEquals(Collections.emptyList(), HistoryDeleteService.parseSessionIds(""));
        assertEquals(Collections.emptyList(), HistoryDeleteService.parseSessionIds(null));
    }

    @Test
    public void parseSessionIdsRejectsMalformedPayload() {
        assertEquals(Collections.emptyList(), HistoryDeleteService.parseSessionIds("["));
    }

    @Test
    public void codexFileMatchAnchorsToHyphenAndExtension() {
        String sessionId = "019b690b-c87f-7350-8f45-bc3dbb59ff77";
        Path matching = Paths.get("/tmp/rollout-2025-12-29T15-38-58-" + sessionId + ".jsonl");
        assertTrue(HistoryDeleteService.isCodexSessionFileMatch(matching, sessionId));
    }

    @Test
    public void codexFileMatchRejectsSubstringWithinNeighbouringSessionId() {
        // Different session whose UUID merely contains the target as a substring
        String target = "abcd1234";
        Path neighbour = Paths.get("/tmp/rollout-2025-12-29T15-38-58-prefix" + target + "suffix.jsonl");
        assertFalse(HistoryDeleteService.isCodexSessionFileMatch(neighbour, target));
    }

    @Test
    public void codexFileMatchRejectsNonJsonlExtension() {
        String sessionId = "019b690b-c87f-7350";
        Path wrongExt = Paths.get("/tmp/rollout-2025-12-29T15-38-58-" + sessionId + ".log");
        assertFalse(HistoryDeleteService.isCodexSessionFileMatch(wrongExt, sessionId));
    }
}
