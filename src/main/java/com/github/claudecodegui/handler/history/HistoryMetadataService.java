package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.ui.toolwindow.ClaudeSDKToolWindow;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 历史会话元数据管理服务。
 * 该服务统一承接收藏、标题更新与孤儿标题清理逻辑，并在逻辑会话语义下同步维护聚合元数据。
 */
class HistoryMetadataService {

    private static final Logger LOG = Logger.getInstance(HistoryMetadataService.class);

    /**
     * 串行化标题文件读写，避免 session-titles.json 出现并发读改写覆盖。
     */
    private final ReentrantLock titleFileLock = new ReentrantLock();

    private final HandlerContext context;
    private final NodeJsServiceCaller nodeJsServiceCaller;
    private final HistoryTitleSyncCoordinator titleSyncCoordinator;

    HistoryMetadataService(HandlerContext context, NodeJsServiceCaller nodeJsServiceCaller) {
        this(context, nodeJsServiceCaller, new HistoryTitleSyncCoordinator((sessionId, newTitle, updater) -> {
            if (context.getProject() == null) {
                return;
            }
            ClaudeSDKToolWindow.syncTabTitlesBySessionId(context.getProject(), sessionId, newTitle);
        }));
    }

    HistoryMetadataService(
            HandlerContext context,
            NodeJsServiceCaller nodeJsServiceCaller,
            HistoryTitleSyncCoordinator titleSyncCoordinator
    ) {
        this.context = context;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
        this.titleSyncCoordinator = titleSyncCoordinator;
    }

    /**
     * 切换收藏状态。
     * 当前仍沿用既有 favorites-service 的物理 sessionId 持久化，但当载荷提供 logicalConversationId 时，
     * 还会同步更新逻辑会话索引上的收藏聚合字段，保证历史列表聚合视图与元数据语义一致。
     *
     * @param payload 前端传入的收藏切换载荷，兼容旧的纯 sessionId 与新的逻辑会话 JSON
     */
    void handleToggleFavorite(String payload) {
        CompletableFuture.runAsync(() -> {
            try {
                MetadataTarget target = parseMetadataTarget(payload);
                LOG.info("[HistoryHandler] ========== 切换收藏状态 ==========");
                LOG.info("[HistoryHandler] SessionId: " + target.sessionId
                        + ", LogicalConversationId: " + target.logicalConversationId);

                String result = nodeJsServiceCaller.callNodeJsFavoritesService("toggleFavorite", target.sessionId);
                LOG.info("[HistoryHandler] 收藏状态切换结果: " + result);

                if (!target.logicalConversationId.isEmpty()) {
                    boolean favorited = inferFavoriteStateFromToggleResult(result);
                    context.getSettingsService().updateLogicalConversationMetadata(
                            target.logicalConversationId,
                            null,
                            favorited,
                            favorited ? System.currentTimeMillis() : 0L
                    );
                }
            } catch (Exception e) {
                LOG.error("[HistoryHandler] 切换收藏状态失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 更新会话标题。
     * 除了回写原有 session-titles.json 外，当请求中带有 logicalConversationId 时，
     * 还会同步刷新逻辑会话主记录上的聚合标题，确保历史聚合视图与 Tab/标题文件一致。
     *
     * @param content 前端传入的标题更新载荷
     */
    void handleUpdateTitle(String content) {
        CompletableFuture.runAsync(() -> {
            titleFileLock.lock();
            try {
                LOG.info("[HistoryHandler] ========== 更新会话标题 ==========");

                JsonObject request = new Gson().fromJson(content, JsonObject.class);
                String sessionId = readOptionalString(request, "sessionId");
                String customTitle = readOptionalString(request, "customTitle");
                String logicalConversationId = readOptionalString(request, "logicalConversationId");

                LOG.info("[HistoryHandler] SessionId: " + sessionId);
                LOG.info("[HistoryHandler] LogicalConversationId: " + logicalConversationId);
                LOG.info("[HistoryHandler] CustomTitle: " + customTitle);

                String result = nodeJsServiceCaller.callNodeJsTitlesServiceWithParams("updateTitle", sessionId, customTitle);
                LOG.info("[HistoryHandler] 标题更新结果: " + result);

                JsonObject resultObject = new Gson().fromJson(result, JsonObject.class);
                boolean success = resultObject != null
                        && resultObject.has("success")
                        && !resultObject.get("success").isJsonNull()
                        && resultObject.get("success").getAsBoolean();

                if (success) {
                    LOG.info("[HistoryTitleSync] update_title success, sessionId=" + sessionId
                            + ", customTitle=" + customTitle);
                    if (!logicalConversationId.isEmpty()) {
                        context.getSettingsService().updateLogicalConversationMetadata(
                                logicalConversationId,
                                customTitle,
                                null,
                                null
                        );
                    }
                    dispatchTitleSync(sessionId, customTitle);
                } else {
                    LOG.warn("[HistoryTitleSync] update_title failed, sessionId=" + sessionId
                            + ", customTitle=" + customTitle + ", result=" + result);
                }

                if (!success && resultObject != null && resultObject.has("error")) {
                    String error = resultObject.get("error").getAsString();
                    ApplicationManager.getApplication().invokeLater(() -> {
                        String jsCode = "if (window.addToast) { "
                                + "  window.addToast('更新标题失败: " + context.escapeJs(error) + "', 'error'); "
                                + "}";
                        context.executeJavaScriptOnEDT(jsCode);
                    });
                }
            } catch (Exception e) {
                LOG.error("[HistoryHandler] 更新标题失败: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.addToast) { "
                            + "  window.addToast('更新标题失败: "
                            + context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误")
                            + "', 'error'); "
                            + "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            } finally {
                titleFileLock.unlock();
            }
        });
    }

    /**
     * 删除孤儿标题。
     * 该入口保留原有物理 session 语义，用于旧 sessionId 迁移后的历史清理。
     *
     * @param sessionId 需要清理的物理 sessionId
     */
    void handleDeleteTitle(String sessionId) {
        CompletableFuture.runAsync(() -> {
            titleFileLock.lock();
            try {
                LOG.info("[HistoryHandler] Deleting orphaned title for sessionId: " + sessionId);
                String result = nodeJsServiceCaller.callNodeJsDeleteTitle(sessionId);
                LOG.info("[HistoryHandler] Delete title result: " + result);
            } catch (Exception e) {
                LOG.warn("[HistoryHandler] Failed to delete orphaned title: " + e.getMessage());
            } finally {
                titleFileLock.unlock();
            }
        });
    }

    /**
     * 在合适线程上执行标题同步。
     * 生产环境需要切回 EDT 以安全更新 IDEA UI；单元测试环境可能没有 Application，此时允许直接同步执行。
     *
     * @param sessionId 会话 ID
     * @param customTitle 新标题
     */
    private void dispatchTitleSync(String sessionId, String customTitle) {
        Application application = ApplicationManager.getApplication();
        if (application == null) {
            titleSyncCoordinator.syncTitles(sessionId, customTitle);
            return;
        }
        if (application.isDispatchThread()) {
            titleSyncCoordinator.syncTitles(sessionId, customTitle);
            return;
        }
        application.invokeAndWait(() -> titleSyncCoordinator.syncTitles(sessionId, customTitle));
    }

    /**
     * 解析收藏/标题链路共用的逻辑会话目标。
     *
     * @param payload 前端传入载荷
     * @return 归一化后的目标
     */
    private MetadataTarget parseMetadataTarget(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            return new MetadataTarget("", "");
        }
        try {
            JsonObject request = new Gson().fromJson(payload, JsonObject.class);
            if (request != null) {
                return new MetadataTarget(
                        readOptionalString(request, "sessionId"),
                        readOptionalString(request, "logicalConversationId")
                );
            }
        } catch (Exception ignored) {
            // 兼容旧的纯 sessionId 载荷
        }
        return new MetadataTarget(payload.trim(), "");
    }

    /**
     * 从 favorites-service 返回结果推断最新收藏状态。
     *
     * @param result favorites-service 返回 JSON
     * @return 推断出的收藏状态
     */
    private boolean inferFavoriteStateFromToggleResult(String result) {
        try {
            JsonObject resultObject = new Gson().fromJson(result, JsonObject.class);
            if (resultObject == null) {
                return false;
            }
            if (resultObject.has("isFavorited") && !resultObject.get("isFavorited").isJsonNull()) {
                return resultObject.get("isFavorited").getAsBoolean();
            }
            if (resultObject.has("favorited") && !resultObject.get("favorited").isJsonNull()) {
                return resultObject.get("favorited").getAsBoolean();
            }
        } catch (Exception ignored) {
            // 保持向后兼容，无法解析时按 false 兜底
        }
        return false;
    }

    private String readOptionalString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        String value = object.get(key).getAsString();
        return value == null ? "" : value.trim();
    }

    /**
     * 收藏/标题管理链路的归一化目标。
     */
    private static final class MetadataTarget {
        private final String sessionId;
        private final String logicalConversationId;

        private MetadataTarget(String sessionId, String logicalConversationId) {
            this.sessionId = sessionId == null ? "" : sessionId.trim();
            this.logicalConversationId = logicalConversationId == null ? "" : logicalConversationId.trim();
        }
    }
}
