package com.github.claudecodegui.session;

import com.github.claudecodegui.permission.PermissionRequest;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.ClaudeSession.Message;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ClaudeMessageHandler 流式 raw/content 一致性测试。
 * 覆盖公开消息事件链路和私有 raw block 修正逻辑，确保完整 assistant snapshot、
 * delta、tool_use 边界和 stream_end/onComplete 生命周期不会互相制造重复内容。
 */
public class ClaudeMessageHandlerRawConsistencyTest {

    private ClaudeMessageHandler handler;

    /**
     * 初始化用于反射级 raw block 测试的 handler。
     * 公开事件链路测试会按需创建独立 handler，避免内部状态互相污染。
     */
    @Before
    public void setUp() {
        SessionState state = new SessionState();
        handler = new ClaudeMessageHandler(
                null,
                state,
                new CallbackHandler(),
                new MessageParser(),
                new MessageMerger(),
                new GsonBuilder().create()
        );
    }

    /**
     * 验证在 streaming 过程中先收到前半段 text delta，再收到包含 tool_use 的完整 assistant snapshot，
     * 最后收到工具后的 text delta 时，不会把 snapshot 里的尾段文本提前并再次追加到 assistantContent。
     */
    @Test
    public void contentDeltaAfterToolUseDoesNotDuplicateSnapshotTextTail() {
        SessionState state = new SessionState();
        state.setProvider("claude");
        state.setModel("claude-sonnet-4-6");

        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);

        ClaudeMessageHandler testHandler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        testHandler.onMessage("stream_start", "");
        testHandler.onMessage("content_delta", "before");
        testHandler.onMessage("assistant", createAssistantSnapshotWithToolUse("before", "tool-1", "after"));
        testHandler.onMessage("content_delta", "after");

        List<Message> messages = state.getMessages();
        assertEquals(1, messages.size());

        Message assistant = messages.get(0);
        assertEquals("beforeafter", assistant.content);
        assertNotNull(assistant.raw);

        JsonArray content = assistant.raw.getAsJsonObject("message").getAsJsonArray("content");
        assertEquals(3, content.size());
        assertEquals("before", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tool_use", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("after", content.get(2).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证 message_end 只负责消息语义边界，不会提前清理 loading/busy。
     */
    @Test
    public void messageEndDoesNotResetLoadingStateDuringStreaming() {
        SessionState state = new SessionState();
        state.setBusy(true);
        state.setLoading(true);

        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);

        ClaudeMessageHandler testHandler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        testHandler.onMessage("stream_start", "");
        testHandler.onMessage("message_end", "");

        assertTrue(state.isBusy());
        assertTrue(state.isLoading());
        assertEquals(0, callback.streamEndCount);
        assertTrue(callback.stateChanges.isEmpty());
    }

    /**
     * 验证收到 stream_end 后，再进入 onComplete 时不会重复派发 streamEnd。
     */
    @Test
    public void onCompleteAfterStreamEndDoesNotNotifyStreamEndTwice() {
        SessionState state = new SessionState();
        state.setBusy(true);
        state.setLoading(true);

        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);

        ClaudeMessageHandler testHandler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        testHandler.onMessage("stream_start", "");
        testHandler.onMessage("stream_end", "");
        testHandler.onComplete(SDKResult.success("done"));

        assertEquals(1, callback.streamEndCount);
        assertFalse(state.isBusy());
        assertFalse(state.isLoading());
        assertTrue(callback.stateChanges.contains("false:false:null"));
    }

    /**
     * 验证当 SDK 没有先发 stream_end 时，onComplete 会负责兜底结束 streaming。
     */
    @Test
    public void onCompleteWithoutStreamEndForcesSingleStreamCleanup() {
        SessionState state = new SessionState();
        state.setBusy(true);
        state.setLoading(true);

        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);

        ClaudeMessageHandler testHandler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        testHandler.onMessage("stream_start", "");
        testHandler.onMessage("content_delta", "tail");
        testHandler.onComplete(SDKResult.success("done"));

        assertEquals(1, callback.streamEndCount);
        assertEquals(1, callback.messageUpdates.size());
        assertFalse(state.isBusy());
        assertFalse(state.isLoading());
        assertTrue(callback.stateChanges.contains("false:false:null"));
    }

    /**
     * 验证 currentAssistantMessage 为空时 raw 修正逻辑保持空操作。
     */
    @Test
    public void doesNothingWhenCurrentAssistantMessageIsNull() throws Exception {
        setAssistantContent("Hello");

        invokeEnsureRawBlocksConsistency();

        assertNull("currentAssistantMessage should stay null", getCurrentAssistantMessage());
    }

    /**
     * 验证累计文本为空时不会覆盖已有 raw text。
     */
    @Test
    public void doesNothingWhenAccumulatedTextIsEmpty() throws Exception {
        Message msg = newAssistantMessage(textBlock("Hello"));
        setCurrentAssistantMessage(msg);
        setAssistantContent("");

        invokeEnsureRawBlocksConsistency();

        assertEquals("Hello", lastTextBlockText(msg));
    }

    /**
     * 验证最后一个 text block 比 accumulator 短时会被补全。
     */
    @Test
    public void fixesLastTextBlockWhenItIsShorterThanAccumulator() throws Exception {
        Message msg = newAssistantMessage(textBlock("Hel"));
        setCurrentAssistantMessage(msg);
        setAssistantContent("Hello world");

        invokeEnsureRawBlocksConsistency();

        assertEquals("Hello world", lastTextBlockText(msg));
    }

    /**
     * 验证多个 text block 被 tool_use 拆分时，只修正最后一个 block。
     */
    @Test
    public void preservesPrecedingTextBlocksWhenFixingLastBlock() throws Exception {
        Message msg = newAssistantMessage(
                textBlock("Hello "),
                toolUseBlock("search"),
                textBlock("wor")
        );
        setCurrentAssistantMessage(msg);
        setAssistantContent("Hello world");

        invokeEnsureRawBlocksConsistency();

        JsonArray content = contentArray(msg);
        assertEquals("text", content.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Hello ", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tool_use", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("text", content.get(2).getAsJsonObject().get("type").getAsString());
        assertEquals("world", content.get(2).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证 accumulator 比前置 block 总长度还短时不做危险修正。
     */
    @Test
    public void leavesBlocksUnchangedWhenAccumulatorShorterThanPrecedingLength() throws Exception {
        Message msg = newAssistantMessage(
                textBlock("Long preceding text"),
                textBlock("tail")
        );
        setCurrentAssistantMessage(msg);
        setAssistantContent("Hi");

        invokeEnsureRawBlocksConsistency();

        JsonArray content = contentArray(msg);
        assertEquals("Long preceding text", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tail", content.get(1).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证没有 text block 时不新增或改写结构。
     */
    @Test
    public void doesNothingWhenNoTextBlocksExist() throws Exception {
        Message msg = newAssistantMessage(toolUseBlock("search"));
        setCurrentAssistantMessage(msg);
        setAssistantContent("Hello");

        invokeEnsureRawBlocksConsistency();

        JsonArray content = contentArray(msg);
        assertEquals(1, content.size());
        assertEquals("tool_use", content.get(0).getAsJsonObject().get("type").getAsString());
    }

    /**
     * 验证 raw block 已经更长时不会被 accumulator 缩短。
     */
    @Test
    public void doesNotShrinkLastTextBlockWhenItIsAlreadyLongerThanExpected() throws Exception {
        Message msg = newAssistantMessage(textBlock("Hello world extra"));
        setCurrentAssistantMessage(msg);
        setAssistantContent("Hello");

        invokeEnsureRawBlocksConsistency();

        assertEquals("Hello world extra", lastTextBlockText(msg));
    }

    /**
     * 构造包含 text + tool_use + text 的完整 assistant snapshot。
     *
     * @param beforeTool 工具前文本
     * @param toolId 工具调用 ID
     * @param afterTool 工具后文本
     * @return JSON 字符串
     */
    private String createAssistantSnapshotWithToolUse(String beforeTool, String toolId, String afterTool) {
        JsonArray content = new JsonArray();
        content.add(textBlock(beforeTool));
        content.add(toolUseBlock(toolId));
        content.add(textBlock(afterTool));

        JsonObject message = new JsonObject();
        message.add("content", content);

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        raw.add("message", message);
        return raw.toString();
    }

    /**
     * 构造 assistant 消息对象。
     *
     * @param blocks raw.message.content 中的 blocks
     * @return assistant message
     */
    private Message newAssistantMessage(JsonObject... blocks) {
        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        JsonObject messageObj = new JsonObject();
        JsonArray content = new JsonArray();
        for (JsonObject block : blocks) {
            content.add(block);
        }
        messageObj.add("content", content);
        raw.add("message", messageObj);
        return new Message(Message.Type.ASSISTANT, "", raw);
    }

    /**
     * 构造 text block。
     *
     * @param text 文本内容
     * @return text block JSON
     */
    private JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    /**
     * 构造 tool_use block。
     *
     * @param toolId 工具调用 ID 或工具名
     * @return tool_use block JSON
     */
    private JsonObject toolUseBlock(String toolId) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", toolId);
        block.addProperty("name", "shell_command");
        return block;
    }

    /**
     * 读取消息 raw content 数组。
     *
     * @param msg assistant message
     * @return raw.message.content 数组
     */
    private JsonArray contentArray(Message msg) {
        return msg.raw.getAsJsonObject("message").getAsJsonArray("content");
    }

    /**
     * 读取最后一个 text block 的文本。
     *
     * @param msg assistant message
     * @return 最后一个 text block 文本，不存在时返回 null
     */
    private String lastTextBlockText(Message msg) {
        JsonArray content = contentArray(msg);
        String last = null;
        for (int i = 0; i < content.size(); i++) {
            JsonObject block = content.get(i).getAsJsonObject();
            if (block.has("type") && "text".equals(block.get("type").getAsString())) {
                last = block.get("text").getAsString();
            }
        }
        return last;
    }

    /**
     * 反射调用 raw block 修正逻辑。
     *
     * @throws Exception 反射失败时抛出
     */
    private void invokeEnsureRawBlocksConsistency() throws Exception {
        Method method = ClaudeMessageHandler.class.getDeclaredMethod("ensureRawBlocksConsistency");
        method.setAccessible(true);
        method.invoke(handler);
    }

    /**
     * 反射写入 assistantContent。
     *
     * @param text 需要写入的累计文本
     * @throws Exception 反射失败时抛出
     */
    private void setAssistantContent(String text) throws Exception {
        Field field = ClaudeMessageHandler.class.getDeclaredField("assistantContent");
        field.setAccessible(true);
        StringBuilder sb = (StringBuilder) field.get(handler);
        sb.setLength(0);
        sb.append(text);
    }

    /**
     * 反射写入当前 assistant message。
     *
     * @param message 当前 assistant message
     * @throws Exception 反射失败时抛出
     */
    private void setCurrentAssistantMessage(Message message) throws Exception {
        Field field = ClaudeMessageHandler.class.getDeclaredField("currentAssistantMessage");
        field.setAccessible(true);
        field.set(handler, message);
    }

    /**
     * 反射读取当前 assistant message。
     *
     * @return 当前 assistant message
     * @throws Exception 反射失败时抛出
     */
    private Message getCurrentAssistantMessage() throws Exception {
        Field field = ClaudeMessageHandler.class.getDeclaredField("currentAssistantMessage");
        field.setAccessible(true);
        return (Message) field.get(handler);
    }

    /**
     * 会话回调录制器。
     * 仅保留本测试需要的最小事件集合，避免引入额外 mocking 依赖。
     */
    private static final class RecordingSessionCallback implements ClaudeSession.SessionCallback {
        private final List<List<Message>> messageUpdates = new ArrayList<>();
        private final List<String> stateChanges = new ArrayList<>();
        private int streamEndCount = 0;

        @Override
        public void onMessageUpdate(List<Message> messages) {
            messageUpdates.add(messages);
        }

        @Override
        public void onStateChange(boolean busy, boolean loading, String error) {
            stateChanges.add(busy + ":" + loading + ":" + error);
        }

        @Override
        public void onSessionIdReceived(String sessionId) {
        }

        @Override
        public void onPermissionRequested(PermissionRequest request) {
        }

        @Override
        public void onThinkingStatusChanged(boolean isThinking) {
        }

        @Override
        public void onSlashCommandsReceived(List<String> slashCommands) {
        }

        @Override
        public void onNodeLog(String log) {
        }

        @Override
        public void onSummaryReceived(String summary) {
        }

        @Override
        public void onStreamEnd() {
            streamEndCount += 1;
        }
    }
}
