package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * Service for exporting session data.
 */
class HistoryExportService {

    private static final Logger LOG = Logger.getInstance(HistoryExportService.class);

    private final HandlerContext context;

    HistoryExportService(HandlerContext context) {
        this.context = context;
    }

    /**
     * Export session data.
     * Reads all messages of the session and returns them to the frontend.
     */
    void handleExportSession(String content, String currentProvider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始导出会话 ==========");

            try {
                // Parse JSON from frontend to extract sessionId and title
                JsonObject exportRequest = new Gson().fromJson(content, JsonObject.class);
                String sessionId = exportRequest.get("sessionId").getAsString();
                String title = exportRequest.get("title").getAsString();
                String logicalConversationId = exportRequest.has("logicalConversationId")
                        && !exportRequest.get("logicalConversationId").isJsonNull()
                        ? exportRequest.get("logicalConversationId").getAsString()
                        : "";

                String projectPath = context.getProject().getBasePath();
                if (projectPath == null) {
                    LOG.warn("[HistoryHandler] Project base path is null");
                    return;
                }
                LOG.info("[HistoryHandler] SessionId: " + sessionId);
                LOG.info("[HistoryHandler] Title: " + title);
                LOG.info("[HistoryHandler] ProjectPath: " + projectPath);
                LOG.info("[HistoryHandler] CurrentProvider: " + currentProvider);

                // Choose a different reader based on the provider
                String messagesJson;
                if ("codex".equals(currentProvider)) {
                    LOG.info("[HistoryHandler] 使用 CodexHistoryReader 读取 Codex 会话消息");
                    messagesJson = exportCodexConversationMessages(sessionId, logicalConversationId);
                } else {
                    LOG.info("[HistoryHandler] 使用 ClaudeHistoryReader 读取 Claude 会话消息");
                    ClaudeHistoryReader historyReader = new ClaudeHistoryReader();
                    messagesJson = historyReader.getSessionMessagesAsJson(projectPath, sessionId);
                }

                // Wrap messages into an object containing sessionId and title
                JsonObject exportData = new JsonObject();
                exportData.addProperty("sessionId", sessionId);
                if (logicalConversationId != null && !logicalConversationId.trim().isEmpty()) {
                    exportData.addProperty("logicalConversationId", logicalConversationId.trim());
                }
                exportData.addProperty("title", title);
                exportData.add("messages", JsonParser.parseString(messagesJson));

                String wrappedJson = new Gson().toJson(exportData);

                LOG.info("[HistoryHandler] 读取到会话消息，准备注入到前端");

                // Use Base64 encoding to avoid JavaScript string escaping issues
                String base64Json = Base64.getEncoder().encodeToString(
                        wrappedJson.getBytes(StandardCharsets.UTF_8));

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "console.log('[Backend->Frontend] Starting to inject export data');" +
                                            "if (window.onExportSessionData) { " +
                                            "  try { " +
                                            "    var base64Str = '" + base64Json + "'; " +
                                            "    var binaryStr = atob(base64Str); " +
                                            "    var bytes = new Uint8Array(binaryStr.length); " +
                                            "    for (var i = 0; i < binaryStr.length; i++) { bytes[i] = binaryStr.charCodeAt(i); } " +
                                            "    var jsonStr = new TextDecoder('utf-8').decode(bytes); " +
                                            "    window.onExportSessionData(jsonStr); " +
                                            "    console.log('[Backend->Frontend] Export data injected successfully'); " +
                                            "  } catch(e) { " +
                                            "    console.error('[Backend->Frontend] Failed to inject export data:', e); " +
                                            "  } " +
                                            "} else { " +
                                            "  console.error('[Backend->Frontend] onExportSessionData not available!'); " +
                                            "}";

                    context.executeJavaScriptOnEDT(jsCode);
                });

                LOG.info("[HistoryHandler] ========== 导出会话完成 ==========");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 导出会话失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.addToast) { " +
                                            "  window.addToast('导出失败: " + context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误") + "', 'error'); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * 以逻辑会话语义导出 Codex 历史消息。
     * 当 logicalConversationId 存在时，按分段顺序拼接整条会话；否则退回旧的单 session 导出语义。
     *
     * @param sessionId 代表分段 sessionId
     * @param logicalConversationId 逻辑会话 id
     * @return 可直接序列化的消息数组 JSON
     */
    private String exportCodexConversationMessages(String sessionId, String logicalConversationId) {
        CodexHistoryReader codexReader = new CodexHistoryReader();
        if (logicalConversationId == null || logicalConversationId.trim().isEmpty()) {
            return codexReader.getSessionMessagesAsJson(sessionId);
        }

        JsonArray combinedMessages = new JsonArray();
        try {
            for (ConversationSegmentRecord segmentRecord
                    : context.getSettingsService().listConversationSegments(logicalConversationId)) {
                if (segmentRecord == null || segmentRecord.getSessionId().isEmpty()) {
                    continue;
                }
                JsonArray segmentMessages = JsonParser
                        .parseString(codexReader.getSessionMessagesAsJson(segmentRecord.getSessionId()))
                        .getAsJsonArray();
                segmentMessages.forEach(combinedMessages::add);
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to export logical Codex conversation, fallback to representative session: "
                    + logicalConversationId + " - " + e.getMessage());
            return codexReader.getSessionMessagesAsJson(sessionId);
        }
        return new Gson().toJson(combinedMessages);
    }
}
