package com.github.claudecodegui.notifications;

import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 验证 ClaudeNotifier 在任务完成预览上的提取策略。
 * 重点确保通知预览优先使用最终 assistant 文本块，而不是工具调用前的中间说明。
 */
public class ClaudeNotifierPreviewTest {

    /**
     * 当一条 assistant raw 消息同时包含前置说明和最终结论时，
     * 完成通知应优先选择最后一个 text block，避免把中间 tool-call 说明误当作最终总结。
     */
    @Test
    public void shouldPreferLastAssistantTextBlockForCompletionPreview() {
        ClaudeSession session = new ClaudeSession(null, null, null);
        ClaudeSession.Message assistant = new ClaudeSession.Message(
            ClaudeSession.Message.Type.ASSISTANT,
            "先检查仓库，再给出最终结论"
        );
        assistant.raw = buildAssistantRaw(
            "先检查仓库",
            "最终结论：已完成真实 completed 收口改造"
        );
        session.getState().addMessage(assistant);

        String preview = ClaudeNotifier.buildPreviewFromSession(session, "fallback");

        assertEquals("最终结论：已完成真实 completed 收口改造", preview);
    }

    /**
     * 当最后一条 assistant 只是空的 tool-call 外壳时，
     * 预览应跳过它，回退到更早的可见 assistant 文本，避免通知正文为空或退化成通用文案。
     */
    @Test
    public void shouldSkipEmptyAssistantFramesWhenBuildingPreview() {
        ClaudeSession session = new ClaudeSession(null, null, null);

        ClaudeSession.Message visibleAssistant = new ClaudeSession.Message(
            ClaudeSession.Message.Type.ASSISTANT,
            "真正的最终总结"
        );
        visibleAssistant.raw = buildAssistantRaw("真正的最终总结");
        session.getState().addMessage(visibleAssistant);

        ClaudeSession.Message emptyAssistant = new ClaudeSession.Message(
            ClaudeSession.Message.Type.ASSISTANT,
            ""
        );
        emptyAssistant.raw = new JsonObject();
        session.getState().addMessage(emptyAssistant);

        String preview = ClaudeNotifier.buildPreviewFromSession(session, "fallback");

        assertEquals("真正的最终总结", preview);
    }

    /**
     * 构造最小 assistant raw 内容块，复用 ClaudeNotifier 当前的 raw 解析逻辑。
     *
     * @param texts 顺序写入的 text block 文本
     * @return 可直接挂到 assistant message 上的 raw JSON
     */
    private static JsonObject buildAssistantRaw(String... texts) {
        JsonObject raw = new JsonObject();
        JsonObject message = new JsonObject();
        com.google.gson.JsonArray content = new com.google.gson.JsonArray();
        for (String text : texts) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", text);
            content.add(block);
        }
        message.add("content", content);
        raw.add("message", message);
        return raw;
    }
}
