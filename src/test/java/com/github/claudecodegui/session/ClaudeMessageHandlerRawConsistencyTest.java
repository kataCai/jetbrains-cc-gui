package com.github.claudecodegui.session;

import com.github.claudecodegui.permission.PermissionRequest;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.session.ClaudeSession.Message;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ClaudeMessageHandler 流式 raw/content 一致性测试。
 * 用于验证 delta 与完整 assistant snapshot 混合到达时，不会跨 tool_use 边界
 * 把 assistantContent 和 raw block 重复拼接。
 */
public class ClaudeMessageHandlerRawConsistencyTest {

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

        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        handler.onMessage("stream_start", "");
        handler.onMessage("content_delta", "before");
        handler.onMessage("assistant", createAssistantSnapshotWithToolUse("before", "tool-1", "after"));
        handler.onMessage("content_delta", "after");

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
     * 该职责应统一留给 stream_end 或 onComplete 处理，避免 streaming 中途状态抖动。
     */
    @Test
    public void messageEndDoesNotResetLoadingStateDuringStreaming() {
        SessionState state = new SessionState();
        state.setBusy(true);
        state.setLoading(true);

        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);

        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        handler.onMessage("stream_start", "");
        handler.onMessage("message_end", "");

        assertTrue(state.isBusy());
        assertTrue(state.isLoading());
        assertEquals(0, callback.streamEndCount);
        assertTrue(callback.stateChanges.isEmpty());
    }

    /**
     * 验证收到 stream_end 后，再进入 onComplete 时不会重复派发 streamEnd。
     * onComplete 在此场景下只允许做一次幂等状态清理。
     */
    @Test
    public void onCompleteAfterStreamEndDoesNotNotifyStreamEndTwice() {
        SessionState state = new SessionState();
        state.setBusy(true);
        state.setLoading(true);

        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);

        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        handler.onMessage("stream_start", "");
        handler.onMessage("stream_end", "");
        handler.onComplete(SDKResult.success("done"));

        assertEquals(1, callback.streamEndCount);
        assertFalse(state.isBusy());
        assertFalse(state.isLoading());
        assertTrue(callback.stateChanges.contains("false:false:null"));
    }

    /**
     * 验证当 SDK 没有先发 stream_end 时，onComplete 会负责兜底结束 streaming。
     * 这是防止前端永久停留在 responding 状态的最后保护。
     */
    @Test
    public void onCompleteWithoutStreamEndForcesSingleStreamCleanup() {
        SessionState state = new SessionState();
        state.setBusy(true);
        state.setLoading(true);

        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);

        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                null,
                state,
                callbackHandler,
                new MessageParser(),
                new MessageMerger(),
                new Gson()
        );

        handler.onMessage("stream_start", "");
        handler.onMessage("content_delta", "tail");
        handler.onComplete(SDKResult.success("done"));

        assertEquals(1, callback.streamEndCount);
        assertEquals(1, callback.messageUpdates.size());
        assertFalse(state.isBusy());
        assertFalse(state.isLoading());
        assertTrue(callback.stateChanges.contains("false:false:null"));
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
        content.add(createTextBlock(beforeTool));
        content.add(createToolUseBlock(toolId));
        content.add(createTextBlock(afterTool));

        JsonObject message = new JsonObject();
        message.add("content", content);

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        raw.add("message", message);
        return raw.toString();
    }

    /**
     * 构造 text block。
     *
     * @param text 文本内容
     * @return text block JSON
     */
    private JsonObject createTextBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    /**
     * 构造 tool_use block。
     *
     * @param toolId 工具调用 ID
     * @return tool_use block JSON
     */
    private JsonObject createToolUseBlock(String toolId) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", toolId);
        block.addProperty("name", "shell_command");
        return block;
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
