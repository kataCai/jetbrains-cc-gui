package com.github.claudecodegui.remote.feishu;

import com.github.claudecodegui.remote.RemoteCollabRequestEnvelope;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteTaskEvent;

/**
 * 飞书第一版文本消息格式化器。
 * 当前阶段先用纯文本协议承载 requestId 与命令提示，优先保证联调闭环和可读性，而不是过早依赖卡片交互。
 */
public class FeishuMessageFormatter {

    public String formatTaskEvent(RemoteTaskEvent event) {
        if (event == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder("CC GUI Task Update");
        appendLine(builder, "Status", event.getTaskState());
        appendLine(builder, "Session", event.getSessionId());
        appendLine(builder, "Summary", event.getSummary());
        return builder.toString();
    }

    public String formatPendingRequest(RemotePendingRequest request) {
        if (request == null) {
            return "";
        }
        RemoteCollabRequestEnvelope envelope = RemoteCollabRequestEnvelope.fromPendingRequest(request);
        StringBuilder builder = new StringBuilder("CC GUI Action Required");
        appendLine(builder, "Request ID", request.getRequestId());
        appendLine(builder, "Type", envelope.getRequestType());
        appendLine(builder, "Session", envelope.getSessionId());
        appendLine(builder, "Summary", envelope.getSummary());

        if ("plan_approval".equals(envelope.getRequestType())) {
            appendLine(builder, "Title", envelope.getSummary());
            builder.append("\nCommands:");
            builder.append("\n/cc-approve ").append(request.getRequestId());
            builder.append("\n/cc-reject ").append(request.getRequestId());
            return builder.toString();
        }

        if ("ask_user_question".equals(envelope.getRequestType())) {
            appendLine(builder, "Question", envelope.getSummary());
            builder.append("\nCommands:");
            for (RemoteCollabRequestEnvelope.Action action : envelope.getActions()) {
                if (action == null || isBlank(action.getLabel())) {
                    continue;
                }
                builder.append("\n/cc-choice ")
                    .append(request.getRequestId())
                    .append(' ')
                    .append(action.getLabel());
            }
            builder.append("\n/cc-reply ").append(request.getRequestId()).append(" <your answer>");
        }
        return builder.toString();
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (isBlank(value)) {
            return;
        }
        builder.append('\n').append(label).append(": ").append(value.trim());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
