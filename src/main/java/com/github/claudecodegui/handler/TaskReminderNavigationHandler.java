package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.notifications.CcgTaskNavigator;
import com.github.claudecodegui.notifications.TaskReminderNavigationTarget;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 处理前端任务提醒“打开任务”的桥接消息。
 * popup 等通道统一通过这里回到 Java 侧，再复用同一套导航逻辑。
 */
public class TaskReminderNavigationHandler extends BaseMessageHandler {

    private static final String[] SUPPORTED_TYPES = {"navigate_task_reminder"};

    private final CcgTaskNavigator taskNavigator;

    public TaskReminderNavigationHandler(HandlerContext context, CcgTaskNavigator taskNavigator) {
        super(context);
        this.taskNavigator = taskNavigator != null ? taskNavigator : new CcgTaskNavigator();
    }

    @Override
    public boolean handle(String type, String content) {
        if (!matchesType(type, SUPPORTED_TYPES)) {
            return false;
        }
        JsonObject payload = parsePayload(content);
        String sessionId = readOptionalString(payload, "sessionId");
        String requestId = readOptionalString(payload, "requestId");
        taskNavigator.navigate(new TaskReminderNavigationTarget(context.getProject(), sessionId, requestId));
        return true;
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES.clone();
    }

    private JsonObject parsePayload(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(content).getAsJsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private String readOptionalString(JsonObject payload, String key) {
        if (payload == null || !payload.has(key) || payload.get(key).isJsonNull()) {
            return null;
        }
        String value = payload.get(key).getAsString();
        return value != null && !value.trim().isEmpty() ? value.trim() : null;
    }
}
