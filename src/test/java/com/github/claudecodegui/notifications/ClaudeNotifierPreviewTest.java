package com.github.claudecodegui.notifications;

import com.github.claudecodegui.session.ClaudeSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * 验证 ClaudeNotifier 在任务完成预览上的提取策略。
 * 重点确保系统通知预览优先使用用户最终可见的完成总结，而不是中途分析文本或空壳工具帧。
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
     * 当聊天区最后插入了统一的 task-notification 结束说明时，
     * 系统通知预览应优先复用这条可见结束总结，保证聊天区与系统通知对同一次完成结果的描述一致。
     * 该测试覆盖“assistant 中途有文本，但最终总结来自系统补发完成说明”的场景。
     */
    @Test
    public void shouldPreferCompletionTaskNotificationSummaryWhenPresent() {
        ClaudeSession session = new ClaudeSession(null, null, null);

        ClaudeSession.Message assistant = new ClaudeSession.Message(
            ClaudeSession.Message.Type.ASSISTANT,
            "先检查仓库，再整理结果"
        );
        assistant.raw = buildAssistantRaw("先检查仓库，再整理结果");
        session.getState().addMessage(assistant);

        ClaudeSession.Message completionNotification = new ClaudeSession.Message(
            ClaudeSession.Message.Type.USER,
            "<task-notification><status>completed</status><summary>本轮任务已完成，已修改 2 个文件。</summary></task-notification>"
        );
        completionNotification.raw = buildTaskNotificationRaw(
            "completed",
            "本轮任务已完成，已修改 2 个文件。"
        );
        session.getState().addMessage(completionNotification);

        String preview = ClaudeNotifier.buildPreviewFromSession(session, "fallback");

        assertEquals("本轮任务已完成，已修改 2 个文件。", preview);
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
        JsonArray content = new JsonArray();
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

    /**
     * 构造用于模拟 task-notification 结束说明的最小 raw，兼容当前前端和通知预览链路的解析方式。
     *
     * @param status task-notification 状态
     * @param summary 结束摘要
     * @return 可直接挂到消息上的 raw JSON
     */
    private static JsonObject buildTaskNotificationRaw(String status, String summary) {
        JsonObject raw = new JsonObject();

        JsonObject origin = new JsonObject();
        origin.addProperty("kind", "task-notification");
        raw.add("origin", origin);

        JsonObject message = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty(
            "text",
            "<task-notification><status>" + status + "</status><summary>" + summary + "</summary></task-notification>"
        );
        content.add(block);
        message.add("content", content);
        raw.add("message", message);
        return raw;
    }
}
