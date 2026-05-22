package com.github.claudecodegui.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

        JsonObject existingRaw = assistantMessage(
                textBlock("before"),
                toolUseBlock("tool-1", "shell_command"),
                textBlock("after-old")
        );
        JsonObject incomingRaw = assistantMessage(
                textBlock("before"),
                toolUseBlock("tool-1", "shell_command"),
                textBlock("after-new")
        );

        JsonObject merged = merger.mergeAssistantMessage(existingRaw, incomingRaw);
        JsonArray content = merged.getAsJsonObject("message").getAsJsonArray("content");

        assertEquals(3, content.size());
        assertEquals("before", content.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("tool_use", content.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("after-new", content.get(2).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证累计 snapshot 增长时不会重复追加已有文本。
     */
    @Test
    public void mergeAssistantMessageDoesNotDuplicateExistingTextWhenIncomingSnapshotIsCumulative() {
        MessageMerger merger = new MessageMerger();

        JsonObject existingRaw = assistantMessage(
                textBlock("让我先获取未提交的更改文件列表。"),
                toolUseBlock("bash-1", "run_command")
        );

        JsonObject newRaw = assistantMessage(
                textBlock("让我先获取未提交的更改文件列表。"),
                toolUseBlock("bash-1", "run_command"),
                textBlock("只有一个文件有更改。让我查看具体的 diff 和完整文件内容。"),
                toolUseBlock("read-1", "read_file")
        );

        JsonArray mergedContent = merger.mergeAssistantMessage(existingRaw, newRaw)
                .getAsJsonObject("message")
                .getAsJsonArray("content");

        assertEquals(4, mergedContent.size());
        assertEquals("让我先获取未提交的更改文件列表。", mergedContent.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("bash-1", mergedContent.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("只有一个文件有更改。让我查看具体的 diff 和完整文件内容。", mergedContent.get(2).getAsJsonObject().get("text").getAsString());
        assertEquals("read-1", mergedContent.get(3).getAsJsonObject().get("id").getAsString());
    }

    /**
     * 验证已有 text block 较短时，incoming snapshot 可以补全内容。
     */
    @Test
    public void mergeAssistantMessageKeepsMoreCompleteMatchingTextBlock() {
        MessageMerger merger = new MessageMerger();

        JsonObject existingRaw = assistantMessage(
                textBlock("让我获取未提交的更改文"),
                toolUseBlock("bash-1", "run_command")
        );

        JsonObject newRaw = assistantMessage(
                textBlock("让我获取未提交的更改文件列表。"),
                toolUseBlock("bash-1", "run_command")
        );

        JsonArray mergedContent = merger.mergeAssistantMessage(existingRaw, newRaw)
                .getAsJsonObject("message")
                .getAsJsonArray("content");

        assertEquals(2, mergedContent.size());
        assertEquals("让我获取未提交的更改文件列表。", mergedContent.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("bash-1", mergedContent.get(1).getAsJsonObject().get("id").getAsString());
    }

    /**
     * 验证只有 tool_use 到达时不会丢掉已有文本。
     */
    @Test
    public void mergeAssistantMessagePreservesExistingTextWhenIncomingSnapshotContainsOnlyToolUse() {
        MessageMerger merger = new MessageMerger();

        JsonObject existingRaw = assistantMessage(
                textBlock("让我先获取未提交的更改文件列表。")
        );

        JsonObject newRaw = assistantMessage(
                toolUseBlock("bash-1", "run_command")
        );

        JsonArray mergedContent = merger.mergeAssistantMessage(existingRaw, newRaw)
                .getAsJsonObject("message")
                .getAsJsonArray("content");

        assertEquals(2, mergedContent.size());
        assertEquals("让我先获取未提交的更改文件列表。", mergedContent.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("bash-1", mergedContent.get(1).getAsJsonObject().get("id").getAsString());
    }

    /**
     * 验证 thinking block 不会在完整 snapshot 回放时重复。
     */
    @Test
    public void mergeAssistantMessageDoesNotDuplicateThinkingBlocks() {
        MessageMerger merger = new MessageMerger();

        JsonObject existingRaw = assistantMessage(
                thinkingBlock("Let me analyze this code carefully."),
                textBlock("这段代码有问题。")
        );

        JsonObject newRaw = assistantMessage(
                thinkingBlock("Let me analyze this code carefully."),
                textBlock("这段代码有问题。"),
                toolUseBlock("bash-1", "run_command")
        );

        JsonArray mergedContent = merger.mergeAssistantMessage(existingRaw, newRaw)
                .getAsJsonObject("message")
                .getAsJsonArray("content");

        assertEquals(3, mergedContent.size());
        assertEquals("thinking", mergedContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Let me analyze this code carefully.", mergedContent.get(0).getAsJsonObject().get("thinking").getAsString());
        assertEquals("这段代码有问题。", mergedContent.get(1).getAsJsonObject().get("text").getAsString());
        assertEquals("bash-1", mergedContent.get(2).getAsJsonObject().get("id").getAsString());
    }

    /**
     * 验证 thinking 的 text mirror 会同步使用更完整内容。
     */
    @Test
    public void mergeAssistantMessageKeepsMoreCompleteThinkingBlockTextMirror() {
        MessageMerger merger = new MessageMerger();

        JsonObject existingRaw = assistantMessage(
                thinkingBlock("Let me analyze"),
                textBlock("分析结果如下。")
        );

        JsonObject newRaw = assistantMessage(
                thinkingBlock("Let me analyze this code carefully."),
                textBlock("分析结果如下。")
        );

        JsonArray mergedContent = merger.mergeAssistantMessage(existingRaw, newRaw)
                .getAsJsonObject("message")
                .getAsJsonArray("content");

        assertEquals(2, mergedContent.size());
        assertEquals("thinking", mergedContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("Let me analyze this code carefully.", mergedContent.get(0).getAsJsonObject().get("thinking").getAsString());
        assertEquals("Let me analyze this code carefully.", mergedContent.get(0).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证空 thinking snapshot 不会覆盖已有非空 thinking 内容。
     */
    @Test
    public void mergeAssistantMessageDoesNotOverwriteThinkingWithEmptyContent() {
        MessageMerger merger = new MessageMerger();

        JsonObject existingRaw = assistantMessage(
                thinkingBlock("Deep analysis of the problem."),
                textBlock("结论。")
        );

        JsonObject newRaw = assistantMessage(
                thinkingBlock(""),
                textBlock("结论。")
        );

        JsonArray mergedContent = merger.mergeAssistantMessage(existingRaw, newRaw)
                .getAsJsonObject("message")
                .getAsJsonArray("content");

        boolean hasNonEmptyThinking = false;
        for (int i = 0; i < mergedContent.size(); i++) {
            JsonObject block = mergedContent.get(i).getAsJsonObject();
            if ("thinking".equals(block.get("type").getAsString())) {
                String thinking = block.has("thinking") && !block.get("thinking").isJsonNull()
                        ? block.get("thinking").getAsString() : "";
                if (!thinking.isEmpty()) {
                    hasNonEmptyThinking = true;
                    assertEquals("Deep analysis of the problem.", thinking);
                }
            }
        }
        assertTrue("Should preserve non-empty thinking content", hasNonEmptyThinking);
    }

    /**
     * 构造 assistant 原始消息，保持测试输入贴近 SDK 返回结构。
     *
     * @param blocks 按顺序写入 message.content 的 block
     * @return assistant raw JSON
     */
    private static JsonObject assistantMessage(JsonObject... blocks) {
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
    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    /**
     * 构造 tool_use block。
     *
     * @param id 工具调用 ID
     * @param name 工具名
     * @return tool_use block JSON
     */
    private static JsonObject toolUseBlock(String id, String name) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "tool_use");
        block.addProperty("id", id);
        block.addProperty("name", name);
        return block;
    }

    /**
     * 构造 thinking block，并同步 text mirror。
     *
     * @param thinking thinking 内容
     * @return thinking block JSON
     */
    private static JsonObject thinkingBlock(String thinking) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "thinking");
        block.addProperty("thinking", thinking);
        block.addProperty("text", thinking);
        return block;
    }
}
