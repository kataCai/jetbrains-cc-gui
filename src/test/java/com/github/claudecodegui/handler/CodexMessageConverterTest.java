package com.github.claudecodegui.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CodexMessageConverterTest {

    // ---- convertFunctionCallOutputToToolResult ----

    @Test
    public void toolResultWithStringOutput() {
        JsonObject payload = new JsonObject();
        payload.addProperty("call_id", "call-1");
        payload.addProperty("output", "command executed successfully");

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, "2026-04-20T00:00:00Z");

        assertEquals("user", result.get("type").getAsString());
        assertEquals("2026-04-20T00:00:00Z", result.get("timestamp").getAsString());

        JsonObject toolResult = extractFirstToolResult(result);
        assertEquals("tool_result", toolResult.get("type").getAsString());
        assertEquals("call-1", toolResult.get("tool_use_id").getAsString());
        assertEquals("command executed successfully", toolResult.get("content").getAsString());
    }

    @Test
    public void toolResultWithJsonObjectOutput() {
        JsonObject structured = new JsonObject();
        structured.addProperty("status", "ok");
        structured.addProperty("code", 200);

        JsonObject payload = new JsonObject();
        payload.addProperty("call_id", "call-2");
        payload.add("output", structured);

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, null);

        assertNull(result.get("timestamp"));

        JsonObject toolResult = extractFirstToolResult(result);
        String content = toolResult.get("content").getAsString();
        assertTrue("Should contain serialized JSON object", content.contains("\"status\":\"ok\""));
        assertTrue("Should contain serialized JSON object", content.contains("\"code\":200"));
    }

    @Test
    public void toolResultWithJsonArrayOutput() {
        JsonArray array = new JsonArray();
        array.add("item1");
        array.add("item2");

        JsonObject payload = new JsonObject();
        payload.addProperty("call_id", "call-3");
        payload.add("output", array);

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, null);

        JsonObject toolResult = extractFirstToolResult(result);
        String content = toolResult.get("content").getAsString();
        assertTrue("Should contain serialized JSON array", content.contains("item1"));
        assertTrue("Should contain serialized JSON array", content.contains("item2"));
    }

    @Test
    public void toolResultWithNullOutput() {
        JsonObject payload = new JsonObject();
        payload.addProperty("call_id", "call-4");
        payload.add("output", JsonNull.INSTANCE);

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, null);

        JsonObject toolResult = extractFirstToolResult(result);
        assertEquals("", toolResult.get("content").getAsString());
    }

    @Test
    public void toolResultWithMissingOutputField() {
        JsonObject payload = new JsonObject();
        payload.addProperty("call_id", "call-5");

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, null);

        JsonObject toolResult = extractFirstToolResult(result);
        assertEquals("", toolResult.get("content").getAsString());
    }

    @Test
    public void toolResultWithMissingCallId() {
        JsonObject payload = new JsonObject();
        payload.addProperty("output", "some output");

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, null);

        JsonObject toolResult = extractFirstToolResult(result);
        assertEquals("unknown", toolResult.get("tool_use_id").getAsString());
    }

    @Test
    public void toolResultTimestampIncludedWhenProvided() {
        JsonObject payload = new JsonObject();
        payload.addProperty("call_id", "call-6");
        payload.addProperty("output", "ok");

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, "2026-01-01T12:00:00Z");
        assertEquals("2026-01-01T12:00:00Z", result.get("timestamp").getAsString());
    }

    @Test
    public void toolResultTimestampOmittedWhenNull() {
        JsonObject payload = new JsonObject();
        payload.addProperty("call_id", "call-7");
        payload.addProperty("output", "ok");

        JsonObject result = CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, null);
        assertNull(result.get("timestamp"));
    }

    @Test
    public void functionCallNormalizesShellCommandToolName() {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", "shell_command");
        payload.addProperty("call_id", "call-shell-1");
        payload.addProperty("arguments", "{\"command\":\"ls src\"}");

        JsonObject result = CodexMessageConverter.convertFunctionCallToToolUse(payload, null);

        assertEquals("assistant", result.get("type").getAsString());
        assertEquals("Tool: glob", result.get("content").getAsString());

        JsonObject toolUse = extractFirstBlock(result);
        assertEquals("tool_use", toolUse.get("type").getAsString());
        assertEquals("call-shell-1", toolUse.get("id").getAsString());
        assertEquals("glob", toolUse.get("name").getAsString());
        assertEquals("ls src", toolUse.getAsJsonObject("input").get("command").getAsString());
    }

    /**
     * 验证 bridge 解析失败噪声不会被转换成前台 assistant 消息。
     * 这类文本只代表桥接层诊断信息，不能进入聊天窗口，否则会和模型真实回答混在一起。
     */
    @Test
    public void codexMessageFiltersBridgeParseDiagnosticNoise() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "output_text");
        block.addProperty("text", "Failed to parse item: {\"type\":\"response_item\"}");
        content.add(block);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertNull(result);
    }

    /**
     * 验证 assistant 正常回复 Windows 进程清理命令时，不会被误判成桥接诊断噪声。
     * 该回归用例直接约束 review 中指出的误杀场景：正文只要是合法 assistant 回复，就不应因为以 taskkill 开头而被整条丢弃。
     */
    @Test
    public void codexMessageKeepsAssistantReplyThatStartsWithTaskkillCommand() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "output_text");
        block.addProperty("text", "taskkill /F /T /PID 12345");
        content.add(block);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertNotNull(result);
        assertEquals("assistant", result.get("type").getAsString());
        assertEquals("taskkill /F /T /PID 12345", result.get("content").getAsString());
    }

    /**
     * 验证 user 消息正文若混入 permissions/skills 内部说明，转换到前端时只保留真实用户问题，
     * 并同步把 raw.content 收敛成净化后的文本块，避免后续前端从 raw 再次渲染出污染内容。
     */
    @Test
    public void codexUserMessageSanitizesMixedInternalPreludeAndKeepsRawContentInSync() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "input_text");
        block.addProperty("text", "继续分析当前问题\n\n"
                + "<permissions instructions>\n"
                + "Filesystem sandboxing defines which files can be read or written.\n"
                + "</permissions instructions>\n\n"
                + "## Skills A skill is a set of local instructions to follow that is stored in a `SKILL.md` file. "
                + "### Skill roots - `r0` = `D:/Users/example/.agents/skills` "
                + "### Available skills - demo (file: r0/demo/SKILL.md) "
                + "### How to use skills - read the skill first.");
        content.add(block);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertEquals("user", result.get("type").getAsString());
        assertEquals("继续分析当前问题", result.get("content").getAsString());
        JsonArray rawContent = result.getAsJsonObject("raw").getAsJsonArray("content");
        assertEquals(1, rawContent.size());
        assertEquals("text", rawContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("继续分析当前问题", rawContent.get(0).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证常规 Codex user 消息即使只有图片 block、没有可见文本，也不会在后端转换阶段被直接过滤。
     * 该场景对应历史恢复中的“纯图片提问”消息；若这里返回 null，前端将完全丢失这条用户消息。
     */
    @Test
    public void codexUserMessageWithOnlyImageBlockShouldBePreserved() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image");
        imageBlock.addProperty("src", "data:image/png;base64,AAAA");
        imageBlock.addProperty("mediaType", "image/png");
        imageBlock.addProperty("alt", "diagram.png");
        content.add(imageBlock);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertNotNull(result);
        assertEquals("user", result.get("type").getAsString());
        JsonArray rawContent = result.getAsJsonObject("raw").getAsJsonArray("content");
        assertEquals(1, rawContent.size());
        assertEquals("image", rawContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("data:image/png;base64,AAAA", rawContent.get(0).getAsJsonObject().get("src").getAsString());
    }

    /**
     * 验证图文混合的常规 Codex user 消息在净化后仍会保留图片 block，
     * 不能因为正文需要清洗就把非文本可见内容一并退化掉。
     */
    @Test
    public void codexUserMessageShouldKeepImageBlockWhenTextIsSanitized() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "user");
        JsonArray content = new JsonArray();

        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image");
        imageBlock.addProperty("src", "data:image/png;base64,BBBB");
        imageBlock.addProperty("mediaType", "image/png");
        imageBlock.addProperty("alt", "chart.png");
        content.add(imageBlock);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "input_text");
        textBlock.addProperty("text", "请继续分析\n\n<permissions instructions>\n"
                + "Filesystem sandboxing defines which files can be read or written.\n"
                + "</permissions instructions>");
        content.add(textBlock);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertNotNull(result);
        assertEquals("请继续分析", result.get("content").getAsString());
        JsonArray rawContent = result.getAsJsonObject("raw").getAsJsonArray("content");
        assertEquals(2, rawContent.size());
        assertEquals("image", rawContent.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("text", rawContent.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("请继续分析", rawContent.get(1).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证 assistant 消息即使混入 runtime 注入的 `input_text`，也只会展示真正的 `output_text`。
     * 该测试直接约束“非 user 分支不再读取 input_text”的核心修复目标，避免 permissions/skills 残留被误拼到前端回答里。
     */
    @Test
    public void codexAssistantMessageShouldIgnoreInjectedInputTextAndKeepOnlyOutputText() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "assistant");
        JsonArray content = new JsonArray();

        JsonObject pollutedInput = new JsonObject();
        pollutedInput.addProperty("type", "input_text");
        pollutedInput.addProperty("text", "<permissions instructions>\n"
                + "Filesystem sandboxing defines which files can be read or written.\n"
                + "</permissions instructions>\n\n"
                + "## Skills\n\n"
                + "### Skill roots\n\n"
                + "### Available skills\n\n"
                + "- demo (file: r0/demo/SKILL.md)\n\n"
                + "### How to use skills");
        content.add(pollutedInput);

        JsonObject visibleOutput = new JsonObject();
        visibleOutput.addProperty("type", "output_text");
        visibleOutput.addProperty("text", "真正展示给用户的回答");
        content.add(visibleOutput);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertEquals("assistant", result.get("type").getAsString());
        assertEquals("真正展示给用户的回答", result.get("content").getAsString());
        JsonArray rawContent = result.getAsJsonObject("raw").getAsJsonArray("content");
        assertEquals(1, rawContent.size());
        assertEquals("真正展示给用户的回答", rawContent.get(0).getAsJsonObject().get("text").getAsString());
    }

    /**
     * 验证 assistant 消息若只剩高置信 permissions/skills 内部残留，则整个前端消息会被直接丢弃。
     * 该断言覆盖真实回归路径：authoritative snapshot 中的非 user 污染消息不能继续作为可见气泡进入聊天区。
     */
    @Test
    public void codexAssistantMessageShouldDropHighConfidenceInternalResidue() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", "<permissions instructions>\n"
                + "Filesystem sandboxing defines which files can be read or written.\n"
                + "</permissions instructions>\n\n"
                + "## Skills\n\n"
                + "### Skill roots\n\n"
                + "### Available skills\n\n"
                + "- demo (file: r0/demo/SKILL.md)\n\n"
                + "### How to use skills\n\n"
                + "Read the skill before doing work.");
        content.add(block);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertNull(result);
    }

    /**
     * 验证 assistant 正常讲解 `AGENTS.md instructions` 结构时，不会因为出现
     * `# AGENTS.md instructions`、`<INSTRUCTIONS>`、`<environment_context>` 这些标签名而被误删。
     * 该场景要求只讨论文档格式，不包含真实运行时环境字段，因此不应视为后台内部注入残留。
     */
    @Test
    public void codexAssistantMessageShouldKeepNormalAgentsInstructionsExplanation() {
        JsonObject payload = new JsonObject();
        payload.addProperty("role", "assistant");
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        String explanation = "# AGENTS.md instructions\n\n"
                + "下面是文档格式示例，不代表真实注入内容。\n\n"
                + "<INSTRUCTIONS>\n"
                + "- 默认使用中文回复。\n"
                + "</INSTRUCTIONS>\n\n"
                + "<environment_context>\n"
                + "- 这里只是说明 environment_context 标签的用途，不包含 cwd、shell、current_date 等运行时字段。\n"
                + "</environment_context>\n\n"
                + "这两个块分别表示规则说明和环境上下文示例。";
        block.addProperty("type", "output_text");
        block.addProperty("text", explanation);
        content.add(block);
        payload.add("content", content);

        JsonObject result = CodexMessageConverter.convertCodexMessageToFrontend(payload, null);

        assertNotNull(result);
        assertEquals("assistant", result.get("type").getAsString());
        assertEquals(explanation, result.get("content").getAsString());
        assertEquals(
                explanation,
                result.getAsJsonObject("raw").getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
        );
    }


    @Test
    public void customToolCallWithStringInput() {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", "apply_patch");
        payload.addProperty("call_id", "custom-1");
        payload.addProperty("input", "some patch content");

        JsonObject result = CodexMessageConverter.convertCustomToolCallToToolUse(payload, null);

        assertEquals("assistant", result.get("type").getAsString());
        assertEquals("Tool: apply_patch", result.get("content").getAsString());

        JsonObject toolUse = extractFirstBlock(result);
        assertEquals("tool_use", toolUse.get("type").getAsString());
        assertEquals("custom-1", toolUse.get("id").getAsString());
        assertEquals("apply_patch", toolUse.get("name").getAsString());
        assertEquals("some patch content", toolUse.getAsJsonObject("input").get("patch").getAsString());
    }

    @Test
    public void customToolCallWithJsonObjectInput() {
        JsonObject structuredInput = new JsonObject();
        structuredInput.addProperty("file", "test.py");
        structuredInput.addProperty("action", "create");

        JsonObject payload = new JsonObject();
        payload.addProperty("name", "mcp_tool");
        payload.addProperty("call_id", "custom-2");
        payload.add("input", structuredInput);

        JsonObject result = CodexMessageConverter.convertCustomToolCallToToolUse(payload, null);

        JsonObject toolUse = extractFirstBlock(result);
        String patchValue = toolUse.getAsJsonObject("input").get("patch").getAsString();
        assertTrue("Should contain serialized JSON", patchValue.contains("test.py"));
    }

    @Test
    public void customToolCallWithMissingInput() {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", "some_tool");
        payload.addProperty("call_id", "custom-3");

        JsonObject result = CodexMessageConverter.convertCustomToolCallToToolUse(payload, null);

        JsonObject toolUse = extractFirstBlock(result);
        assertEquals("", toolUse.getAsJsonObject("input").get("patch").getAsString());
    }

    @Test
    public void customToolCallExtractsFilePathFromApplyPatch() {
        String patchContent = "*** Update File: src/main/App.java\n--- old\n+++ new\n@@ -1 +1 @@\n-old line\n+new line";

        JsonObject payload = new JsonObject();
        payload.addProperty("name", "apply_patch");
        payload.addProperty("call_id", "custom-4");
        payload.addProperty("input", patchContent);

        JsonObject result = CodexMessageConverter.convertCustomToolCallToToolUse(payload, null);

        JsonObject toolUse = extractFirstBlock(result);
        JsonObject input = toolUse.getAsJsonObject("input");
        assertEquals("src/main/App.java", input.get("file_path").getAsString());
    }

    @Test
    public void customToolCallExtractsFilePathFromAddFile() {
        String patchContent = "*** Add File: src/new/File.java\n+new content";

        JsonObject payload = new JsonObject();
        payload.addProperty("name", "apply_patch");
        payload.addProperty("call_id", "custom-5");
        payload.addProperty("input", patchContent);

        JsonObject result = CodexMessageConverter.convertCustomToolCallToToolUse(payload, null);

        JsonObject toolUse = extractFirstBlock(result);
        JsonObject input = toolUse.getAsJsonObject("input");
        assertEquals("src/new/File.java", input.get("file_path").getAsString());
    }

    @Test
    public void customToolCallWithMissingNameAndCallId() {
        JsonObject payload = new JsonObject();
        payload.addProperty("input", "data");

        JsonObject result = CodexMessageConverter.convertCustomToolCallToToolUse(payload, null);

        JsonObject toolUse = extractFirstBlock(result);
        assertEquals("unknown", toolUse.get("name").getAsString());
        assertEquals("unknown", toolUse.get("id").getAsString());
    }

    // ---- helpers ----

    private static JsonObject extractFirstToolResult(JsonObject frontendMsg) {
        return frontendMsg.getAsJsonObject("raw")
                .getAsJsonArray("content")
                .get(0)
                .getAsJsonObject();
    }

    private static JsonObject extractFirstBlock(JsonObject frontendMsg) {
        return frontendMsg.getAsJsonObject("raw")
                .getAsJsonArray("content")
                .get(0)
                .getAsJsonObject();
    }
}
