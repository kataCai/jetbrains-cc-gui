package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.github.claudecodegui.session.LogicalConversationRecord;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HistoryMessageInjectorTest {

    /**
     * 验证 `load_session` 在收到逻辑会话载荷时，能够正确提取跨分段恢复所需的关键字段。
     * 该测试覆盖前端历史列表按逻辑会话键回传 JSON payload 的场景，避免后端继续只按旧的单个物理 sessionId 处理。
     */
    @Test
    public void parseSessionLoadRequestShouldCaptureLogicalConversationFields() {
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", "segment-001");
        payload.addProperty("logicalConversationId", "logical-001");
        payload.addProperty("activeSegmentSessionId", "segment-002");
        payload.addProperty("provider", "codex");
        payload.addProperty("runtimeFamily", "codex");
        payload.addProperty("restoreSource", "history_restore");
        payload.addProperty("transitionToken", "transition-001");

        HistoryMessageInjector.SessionLoadRequest request =
                HistoryMessageInjector.parseSessionLoadRequest(payload.toString(), "claude");

        assertEquals("segment-001", request.getRequestedSessionId());
        assertEquals("logical-001", request.getLogicalConversationId());
        assertEquals("segment-002", request.getActiveSegmentSessionId());
        assertEquals("codex", request.getProvider());
        assertEquals("codex", request.getRuntimeFamily());
        assertEquals("history_restore", request.getRestoreSource());
        assertEquals("transition-001", request.getTransitionToken());
    }

    /**
     * 验证逻辑会话恢复计划会优先回到最新活动分段，并按分段顺序保留整条会话的物理 session 列表。
     * 这是跨模型/跨供应商继续执行的基础，否则后续发送仍会绑定到旧分段。
     */
    @Test
    public void buildCodexRestorePlanShouldUseLatestSegmentForLogicalConversation() throws Exception {
        StubConversationSettingsService settingsService = createConversationSettingsService();
        JsonObject payload = new JsonObject();
        payload.addProperty("sessionId", "segment-001");
        payload.addProperty("logicalConversationId", "logical-001");
        payload.addProperty("activeSegmentSessionId", "segment-002");
        payload.addProperty("provider", "codex");
        payload.addProperty("runtimeFamily", "codex");

        HistoryMessageInjector.SessionLoadRequest request =
                HistoryMessageInjector.parseSessionLoadRequest(payload.toString(), "codex");
        HistoryMessageInjector.CodexRestorePlan plan =
                HistoryMessageInjector.buildCodexRestorePlan(request, settingsService);

        assertEquals("logical-001", plan.getLogicalConversationId());
        assertEquals("segment-002", plan.getActiveSegmentSessionId());
        assertEquals("segment-001", plan.getParentSegmentSessionId());
        assertEquals(List.of("segment-001", "segment-002"), plan.getSegmentSessionIds());
    }

    /**
     * 验证旧的单物理 sessionId 载荷在命中分段元数据后，也会回溯并恢复整条逻辑会话。
     * 该场景对应历史记录、旧标签页或其他仍传老格式 payload 的入口，要求兼容恢复整条上下文而不是只打开旧分段。
     */
    @Test
    public void buildCodexRestorePlanShouldInferLogicalConversationFromLegacySessionId() throws Exception {
        StubConversationSettingsService settingsService = createConversationSettingsService();

        HistoryMessageInjector.SessionLoadRequest request =
                HistoryMessageInjector.parseSessionLoadRequest("segment-001", "codex");
        HistoryMessageInjector.CodexRestorePlan plan =
                HistoryMessageInjector.buildCodexRestorePlan(request, settingsService);

        assertEquals("logical-001", plan.getLogicalConversationId());
        assertEquals("segment-002", plan.getActiveSegmentSessionId());
        assertEquals(List.of("segment-001", "segment-002"), plan.getSegmentSessionIds());
    }

    /**
     * 验证把恢复计划写回 SessionState 时，会把当前活动分段切换到最新分段，并清理 continuation 过渡态。
     * 该断言直接约束“加载历史后继续发送应落在新供应商/新模型对应的最新分段”这一核心行为。
     */
    @Test
    public void applyCodexContinuationStateShouldSwitchSessionStateToLatestSegment() {
        HistoryMessageInjector.CodexRestorePlan restorePlan = new HistoryMessageInjector.CodexRestorePlan(
                "segment-001",
                "logical-001",
                "segment-002",
                "segment-001",
                List.of("segment-001", "segment-002")
        );
        TestableHistoryMessageInjector injector = new TestableHistoryMessageInjector();
        com.github.claudecodegui.session.SessionState state = new com.github.claudecodegui.session.SessionState();
        state.setContinuationPending(true);
        state.setContinuationSourceSessionId("segment-001");

        injector.applyContinuationState(state, restorePlan);

        assertEquals("logical-001", state.getLogicalConversationId());
        assertEquals("segment-002", state.getActiveSegmentSessionId());
        assertEquals("segment-001", state.getParentSegmentSessionId());
        assertEquals(false, state.isContinuationPending());
        assertEquals(null, state.getContinuationSourceSessionId());
    }

    /**
     * 验证逻辑会话恢复计划的 trace 摘要会稳定输出关键串联字段。
     * 该摘要用于把前端 history restore、后端 restore plan、continued segment 与后续首次发送日志串成一条链，
     * 因此必须包含 requestedSessionId、logicalConversationId、activeSegmentSessionId、parentSegmentSessionId 与 segment 数量。
     */
    @Test
    public void describeRestorePlanForTraceShouldExposeContinuationRoutingFields() {
        HistoryMessageInjector.CodexRestorePlan restorePlan = new HistoryMessageInjector.CodexRestorePlan(
                "segment-001",
                "logical-001",
                "segment-002",
                "segment-001",
                List.of("segment-001", "segment-002")
        );

        String description = HistoryMessageInjector.describeRestorePlanForTrace(restorePlan);

        assertTrue(description.contains("requestedSessionId=segment-001"));
        assertTrue(description.contains("logicalConversationId=logical-001"));
        assertTrue(description.contains("activeSegmentSessionId=segment-002"));
        assertTrue(description.contains("parentSegmentSessionId=segment-001"));
        assertTrue(description.contains("segmentCount=2"));
    }

    @Test
    public void convertCodexMessagesDeduplicatesDualRecordedUserMessage() {
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", "hello"));
        messages.add(eventUserMessage("2026-04-30T09:40:26.701Z", "hello"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("user", result.get(0).get("type").getAsString());
        assertEquals("hello", result.get(0).get("content").getAsString());
    }

    @Test
    public void convertCodexMessagesKeepsRepeatedUserMessagesWithDifferentTimestamps() {
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", "hello"));
        messages.add(eventUserMessage("2026-04-30T09:40:27.701Z", "hello"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(2, result.size());
    }

    /**
     * 验证恢复逻辑会话时，会在跨分段边界处插入系统提示消息。
     * 该提示用于向用户明确说明当前上下文已经从旧 provider/model 继续到了新的运行分段，避免误以为底层 thread 从未变化。
     */
    @Test
    public void convertCodexMessagesShouldInsertContinuationBoundarySystemMessage() {
        JsonArray firstSegment = new JsonArray();
        firstSegment.add(eventUserMessage("2026-04-30T09:40:26.701Z", "first user"));
        JsonArray secondSegment = new JsonArray();
        secondSegment.add(eventUserMessage("2026-04-30T09:41:26.701Z", "second user"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(
                List.of(firstSegment, secondSegment),
                List.of(
                        new ConversationSegmentRecord("segment-001", "logical-001", "", 0, "codex-cli-login", "codex", "gpt-5.4", "medium", "initial", "none", 1714460426701L),
                        new ConversationSegmentRecord("segment-002", "logical-001", "segment-001", 1, "buycode", "codex", "gpt-5.4", "medium", "runtime_switch:provider", "session_summary", 1714460486701L)
                )
        );

        assertEquals(3, result.size());
        assertEquals("user", result.get(0).get("type").getAsString());
        assertEquals("system", result.get(1).get("type").getAsString());
        assertTrue(result.get(1).get("content").getAsString().contains("buycode"));
        assertEquals("user", result.get(2).get("type").getAsString());
    }

    /**
     * 验证逻辑会话按分段恢复时，会优先采用活动分段自己的 session_meta 作为 cwd 与 threadId 来源。
     * 该测试覆盖“旧段 cwd 与最新活动分段 cwd 不同”时的恢复场景，避免继续发送误落到首段目录。
     */
    @Test
    public void codexSegmentBundleShouldResolveSessionMetaFromActiveSegment() {
        JsonArray firstSegment = new JsonArray();
        firstSegment.add(sessionMeta("segment-001", "E:/workspace/legacy-root"));
        firstSegment.add(eventUserMessage("2026-04-30T09:40:26.701Z", "first user"));
        JsonArray secondSegment = new JsonArray();
        secondSegment.add(sessionMeta("segment-002", "E:/workspace/continued-target"));
        secondSegment.add(eventUserMessage("2026-04-30T09:41:26.701Z", "second user"));

        HistoryMessageInjector.CodexSegmentBundle bundle = new HistoryMessageInjector.CodexSegmentBundle(
                List.of(firstSegment, secondSegment),
                List.of(
                        new ConversationSegmentRecord("segment-001", "logical-001", "", 0, "claude", "claude", "claude-sonnet-4-6", "", "initial", "none", 1714460426701L),
                        new ConversationSegmentRecord("segment-002", "logical-001", "segment-001", 1, "buycode", "codex", "gpt-5.4", "medium", "runtime_switch:provider", "session_summary", 1714460486701L)
                ),
                "logical-001",
                "segment-002",
                "segment-001"
        );

        String[] meta = HistoryMessageInjector.extractSessionMeta(bundle, "segment-002");

        assertEquals("segment-002", meta[0]);
        assertEquals("E:/workspace/continued-target", meta[1]);
    }

    @Test
    public void convertCodexMessagesDeduplicatesImageWrappedDualRecordedUserMessage() {
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", "<image name=[Image #1]>\n</image>\nhello"));
        messages.add(eventUserMessage("2026-04-30T09:40:26.701Z", "hello"));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("<image name=[Image #1]>\n</image>\nhello", result.get(0).get("content").getAsString());
    }

    @Test
    public void convertCodexMessagesStripsAgentsInstructionsFromDuplicatedUserMessage() {
        String text = "<agents-instructions>\n"
                + "# Global Instructions\n\n"
                + "请默认使用中文（简体）回复。\n"
                + "</agents-instructions>\n\n"
                + "hello";
        JsonArray messages = new JsonArray();
        messages.add(responseItemUserMessage("2026-04-30T09:40:26.701Z", text));
        messages.add(eventUserMessage("2026-04-30T09:40:26.701Z", text));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("hello", result.get(0).get("content").getAsString());
        assertEquals("hello", result.get(0)
                .getAsJsonObject("raw")
                .getAsJsonArray("content")
                .get(0)
                .getAsJsonObject()
                .get("text")
                .getAsString());
    }

    @Test
    public void convertCodexMessagesRestoresLocalImagesFromEventMessage() throws Exception {
        Path imagePath = Files.createTempFile("codex-history-image", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonArray messages = new JsonArray();
            messages.add(eventUserMessage("2026-05-11T09:02:20.861Z", "hello", imagePath.toString()));

            List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

            assertEquals(1, result.size());
            JsonArray contentBlocks = result.get(0).getAsJsonObject("raw").getAsJsonArray("content");
            assertEquals(2, contentBlocks.size());
            assertEquals("image", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("image/png", contentBlocks.get(0).getAsJsonObject().get("mediaType").getAsString());
            assertTrue(contentBlocks.get(0).getAsJsonObject().get("src").getAsString().startsWith("data:image/png;base64,"));
            assertEquals("text", contentBlocks.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("hello", contentBlocks.get(1).getAsJsonObject().get("text").getAsString());
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void convertCodexMessagesKeepsImageOnlyEventMessage() throws Exception {
        Path imagePath = Files.createTempFile("codex-history-image-only", ".png");
        try {
            Files.write(imagePath, "png-bytes".getBytes(StandardCharsets.UTF_8));

            JsonArray messages = new JsonArray();
            messages.add(eventUserMessage("2026-05-11T09:03:20.861Z", "", imagePath.toString()));

            List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

            assertEquals(1, result.size());
            assertEquals("", result.get(0).get("content").getAsString());
            JsonArray contentBlocks = result.get(0).getAsJsonObject("raw").getAsJsonArray("content");
            assertEquals(1, contentBlocks.size());
            assertEquals("image", contentBlocks.get(0).getAsJsonObject().get("type").getAsString());
            assertTrue(contentBlocks.get(0).getAsJsonObject().get("src").getAsString().startsWith("data:image/png;base64,"));
        } finally {
            Files.deleteIfExists(imagePath);
        }
    }

    @Test
    public void convertCodexMessagesStripsAppendedProjectModulesContext() {
        JsonArray messages = new JsonArray();
        messages.add(eventUserMessage(
                "2026-05-11T09:03:20.861Z",
                "只保留用户输入\n\n## Project Modules\n\nThis project contains multiple modules:\n- `idea-claude-code-gui`\n"
        ));

        List<JsonObject> result = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);

        assertEquals(1, result.size());
        assertEquals("只保留用户输入", result.get(0).get("content").getAsString());
        JsonArray contentBlocks = result.get(0).getAsJsonObject("raw").getAsJsonArray("content");
        assertEquals(1, contentBlocks.size());
        assertEquals("只保留用户输入", contentBlocks.get(0).getAsJsonObject().get("text").getAsString());
    }

    private static JsonObject responseItemUserMessage(String timestamp, String text) {
        JsonObject line = new JsonObject();
        line.addProperty("timestamp", timestamp);
        line.addProperty("type", "response_item");

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "message");
        payload.addProperty("role", "user");

        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "input_text");
        block.addProperty("text", text);
        content.add(block);

        payload.add("content", content);
        line.add("payload", payload);
        return line;
    }

    private static JsonObject eventUserMessage(String timestamp, String text) {
        JsonObject line = new JsonObject();
        line.addProperty("timestamp", timestamp);
        line.addProperty("type", "event_msg");

        JsonObject payload = new JsonObject();
        payload.addProperty("type", "user_message");
        payload.addProperty("message", text);
        line.add("payload", payload);
        return line;
    }

    private static JsonObject eventUserMessage(String timestamp, String text, String localImagePath) {
        JsonObject line = eventUserMessage(timestamp, text);
        JsonArray localImages = new JsonArray();
        localImages.add(localImagePath);
        line.getAsJsonObject("payload").add("local_images", localImages);
        return line;
    }

    /**
     * 构造最小可用的 Codex session_meta 事件，用于模拟每个物理分段独立记录的工作目录与真实 threadId。
     *
     * @param threadId 分段真实 sessionId/threadId
     * @param cwd 分段对应工作目录
     * @return session_meta 原始事件
     */
    private static JsonObject sessionMeta(String threadId, String cwd) {
        JsonObject line = new JsonObject();
        line.addProperty("type", "session_meta");
        JsonObject payload = new JsonObject();
        payload.addProperty("id", threadId);
        payload.addProperty("cwd", cwd);
        line.add("payload", payload);
        return line;
    }

    /**
     * 构造一组两段式逻辑会话元数据，模拟从旧供应商切到新供应商或新模型继续执行后的状态。
     *
     * @return 仅覆盖逻辑会话恢复所需接口的测试设置服务桩
     */
    private static StubConversationSettingsService createConversationSettingsService() {
        StubConversationSettingsService settingsService = new StubConversationSettingsService();
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
                false,
                0L
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
                "buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "runtime_switch:model_provider",
                "carryover",
                1719658800000L
        );
        return settingsService;
    }

    /**
     * 逻辑会话恢复测试专用的设置服务桩。
     * 该桩只实现 `HistoryMessageInjector` 在恢复逻辑会话时依赖的三个读取入口，避免真实配置文件读写影响单测稳定性。
     */
    private static final class StubConversationSettingsService extends CodemossSettingsService {
        private LogicalConversationRecord logicalConversationRecord;
        private ConversationSegmentRecord segment001;
        private ConversationSegmentRecord segment002;

        /**
         * 根据逻辑会话 id 返回预置主记录。
         *
         * @param logicalConversationId 逻辑会话 id
         * @return 匹配到的逻辑会话记录；未命中时返回 null
         */
        @Override
        public LogicalConversationRecord getLogicalConversationRecord(String logicalConversationId) {
            return logicalConversationRecord != null
                    && logicalConversationRecord.getLogicalConversationId().equals(logicalConversationId)
                    ? logicalConversationRecord
                    : null;
        }

        /**
         * 根据物理 sessionId 返回预置分段记录。
         *
         * @param sessionId 物理分段 sessionId
         * @return 对应的分段记录；未命中时返回 null
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

        /**
         * 返回同一逻辑会话下的分段列表，并保持按分段序号升序排列。
         *
         * @param logicalConversationId 逻辑会话 id
         * @return 当前逻辑会话的全部分段
         */
        @Override
        public List<ConversationSegmentRecord> listConversationSegments(String logicalConversationId) {
            if (!"logical-001".equals(logicalConversationId)) {
                return List.of();
            }
            return List.of(segment001, segment002);
        }
    }

    /**
     * 暴露 protected continuation 状态写回入口的测试替身。
     * 仅用于验证恢复计划对 SessionState 的影响，不参与真实历史文件读取。
     */
    private static final class TestableHistoryMessageInjector extends HistoryMessageInjector {

        /**
         * 使用最小 HandlerContext 初始化注入器，避免真实 IDE/Browser 依赖。
         */
        TestableHistoryMessageInjector() {
            super(new com.github.claudecodegui.handler.core.HandlerContext(
                    null,
                    null,
                    null,
                    null,
                    new com.github.claudecodegui.handler.core.HandlerContext.JsCallback() {
                        @Override
                        public void callJavaScript(String functionName, String... args) {
                        }

                        @Override
                        public String escapeJs(String str) {
                            return str;
                        }
                    }
            ));
        }

        /**
         * 把恢复计划写回指定 SessionState，供单测直接断言。
         *
         * @param state 目标会话状态
         * @param restorePlan 逻辑会话恢复计划
         */
        void applyContinuationState(
                com.github.claudecodegui.session.SessionState state,
                HistoryMessageInjector.CodexRestorePlan restorePlan
        ) {
            applyCodexContinuationState(state, restorePlan);
        }
    }
}
