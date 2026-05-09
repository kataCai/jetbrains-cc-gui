package com.github.claudecodegui.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * MessageMerger 合并行为测试。
 * 用于验证完整 assistant snapshot 回放时，未带唯一 key 的 text/thinking block
 * 不会因为简单 append 而在 tool_use 边界前后产生重复结构。
 */
public class MessageMergerTest {

    /**
     * 验证在 tool_use 前后都存在 text block 时，新的完整快照会按位置替换旧 text block，
     * 而不是把相同 phase 的 text 追加到数组尾部。
     */
    @Test
    public void mergeAssistantMessageReplacesPlainTextBlocksAroundToolBoundaries() {
        MessageMerger merger = new MessageMerger();

        JsonObject existingRaw = createAssistantRaw(
                createTextBlock("before"),
                createToolUseBlock("tool-1"),
                createTextBlock("after-old")
        );
        JsonObject incomingRaw = createAssistantRaw(
                createTextBlock("before"),
                createToolUseBlock("tool-1"),
                createTextBlock("after-new")
        );

        JsonObject merged = merger.mergeAssistantMessage(existingRaw, incomingRaw);
        JsonArray content = merged.getAsJsonObject("message").getAsJsonArray("content");

        assertEquals(3, content.size());
        assertEquals("before", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tool_use", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("after-new", content.get(2).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 构造 assistant 原始消息，保持测试输入尽量贴近 SDK 返回结构。
     *
     * @param blocks 按顺序写入 message.content 的 block
     * @return assistant raw JSON
     */
    private JsonObject createAssistantRaw(JsonObject... blocks) {
        JsonArray content = new JsonArray();
        for (JsonObject block : blocks) {
            content.add(block);
        }

        JsonObject message = new JsonObject();
        message.add("content", content);

        JsonObject raw = new JsonObject();
        raw.addProperty("type", "assistant");
        raw.add("message", message);
        return raw;
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
     * @param id 工具调用 ID
     * @return tool_use block JSON
     */
    private JsonObject createToolUseBlock(String id) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", id);
        block.addProperty("name", "shell_command");
        return block;
    }
}
