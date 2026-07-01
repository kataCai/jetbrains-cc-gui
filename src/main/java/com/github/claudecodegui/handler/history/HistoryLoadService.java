package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.claude.ClaudeHistoryReader;
import com.github.claudecodegui.provider.codex.CodexHistoryImageCacheService;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

/**
 * 历史列表加载服务。
 * <p>
 * 该服务负责按 provider 读取历史会话列表，并在返回前补充收藏信息、自定义标题等前端渲染所需字段。
 * 对 Codex 历史列表，还会在低频入口顺带触发一次节流后的图片缓存清理检查，避免缓存治理只能依赖发送链路。
 */
class HistoryLoadService {

    private static final Logger LOG = Logger.getInstance(HistoryLoadService.class);
    private static final CodexHistoryImageCacheService CODEX_HISTORY_IMAGE_CACHE_SERVICE =
            new CodexHistoryImageCacheService();

    private final HandlerContext context;
    private final NodeJsServiceCaller nodeJsServiceCaller;

    /**
     * 构造历史列表加载服务。
     *
     * @param context 当前处理上下文，用于访问项目与前端桥接能力
     * @param nodeJsServiceCaller Node.js 服务调用器，用于读取收藏与标题等扩展信息
     */
    HistoryLoadService(HandlerContext context, NodeJsServiceCaller nodeJsServiceCaller) {
        this.context = context;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
    }

    /**
     * 加载指定 provider 的历史列表，并注入到前端。
     * <p>
     * 关键逻辑：
     * 1. 根据 provider 选择 Claude 或 Codex 的历史读取器；
     * 2. 对 Codex 历史页补一次节流后的缓存清理触发；
     * 3. 合并收藏信息与自定义标题；
     * 4. 通过 Base64 传输 JSON，避免脚本注入与转义问题。
     *
     * @param provider provider 标识，支持 `claude` 与 `codex`
     */
    void handleLoadHistoryData(String provider) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始加载历史数据 ========== provider=" + provider);

            try {
                if ("codex".equals(provider)) {
                    // Codex 历史页属于低频入口，这里补一次节流后的缓存清理触发，避免只在发送时才执行治理。
                    CODEX_HISTORY_IMAGE_CACHE_SERVICE.triggerHistoryAccessCleanup();
                }

                String projectPath = context.getProject().getBasePath();
                if (projectPath == null) {
                    LOG.warn("[HistoryHandler] Project base path is null");
                    return;
                }

                String historyJson;
                if ("codex".equals(provider)) {
                    LOG.info("[HistoryHandler] 使用 CodexHistoryReader 读取 Codex 会话，projectPath=" + projectPath);
                    CodexHistoryReader codexReader = new CodexHistoryReader();
                    historyJson = codexReader.getSessionsForProjectAsJson(projectPath);
                    LOG.info("[HistoryHandler] CodexHistoryReader 返回 JSON 长度: " + historyJson.length());
                } else {
                    LOG.info("[HistoryHandler] 使用 ClaudeHistoryReader 读取 Claude 会话");
                    ClaudeHistoryReader historyReader = new ClaudeHistoryReader();
                    historyJson = historyReader.getProjectDataAsJson(projectPath);
                }

                String enhancedJson = enhanceHistoryWithFavorites(historyJson, provider);
                LOG.info("[HistoryHandler] enhanceHistoryWithFavorites 完成，JSON 长度: " + enhancedJson.length());

                String finalJson = enhanceHistoryWithTitles(enhancedJson);
                LOG.info("[HistoryHandler] enhanceHistoryWithTitles 完成，JSON 长度: " + finalJson.length());

                String base64Json = Base64.getEncoder().encodeToString(finalJson.getBytes(StandardCharsets.UTF_8));
                LOG.info("[HistoryHandler] Base64 编码完成，长度: " + base64Json.length());

                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "console.log('[Backend->Frontend] Starting to inject history data');" +
                            "if (window.setHistoryData) { " +
                            "  try { " +
                            "    var base64Str = '" + base64Json + "'; " +
                            "    console.log('[Backend->Frontend] Base64 length:', base64Str.length); " +
                            "    var binaryStr = atob(base64Str); " +
                            "    var bytes = new Uint8Array(binaryStr.length); " +
                            "    for (var i = 0; i < binaryStr.length; i++) { bytes[i] = binaryStr.charCodeAt(i); } " +
                            "    var jsonStr = new TextDecoder('utf-8').decode(bytes); " +
                            "    console.log('[Backend->Frontend] Decoded JSON length:', jsonStr.length); " +
                            "    var data = JSON.parse(jsonStr); " +
                            "    console.log('[Backend->Frontend] Parsed data, sessions:', data.sessions ? data.sessions.length : 0); " +
                            "    window.setHistoryData(data); " +
                            "    console.log('[Backend->Frontend] setHistoryData called successfully'); " +
                            "  } catch(e) { " +
                            "    console.error('[Backend->Frontend] Failed to parse/set history data:', e); " +
                            "    window.setHistoryData({ success: false, error: '解析历史数据失败: ' + e.message }); " +
                            "  } " +
                            "} else { " +
                            "  console.error('[Backend->Frontend] setHistoryData not available!'); " +
                            "}";

                    context.executeJavaScriptOnEDT(jsCode);
                    LOG.info("[HistoryHandler] JavaScript 历史列表注入已提交");
                });
            } catch (Exception e) {
                LOG.error("[HistoryHandler] 加载历史数据失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误");
                    String jsCode = "if (window.setHistoryData) { " +
                            "  window.setHistoryData({ success: false, error: '" + errorMsg + "' }); " +
                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * 执行深度搜索历史列表。
     * <p>
     * 该入口会先清理会话索引与缓存，再复用常规历史列表加载逻辑完成一次全量刷新。
     *
     * @param provider provider 标识，支持 `claude` 与 `codex`
     */
    void handleDeepSearchHistory(String provider) {
        String projectPath = context.getProject().getBasePath();
        LOG.info("[HistoryHandler] ========== 开始深度搜索历史 ========== provider=" + provider);

        try {
            if ("codex".equals(provider)) {
                SessionIndexCache.getInstance().clearAllCodexCache();
                SessionIndexManager.getInstance().clearAllCodexIndex();
            } else if (projectPath != null) {
                SessionIndexCache.getInstance().clearProject(projectPath);
                SessionIndexManager.getInstance().clearProjectIndex("claude", projectPath);
            }

            LOG.info("[HistoryHandler] 历史索引缓存清理完成，开始重新加载历史数据");
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 清理历史索引缓存时出错，继续加载: " + e.getMessage());
        }

        handleLoadHistoryData(provider);
    }

    /**
     * 为历史列表补充收藏信息。
     * <p>
     * 该增强为每个 session 写入 `isFavorited`、`favoritedAt` 与 `provider` 字段，
     * 同时把完整 favorites 映射挂回响应体，供前端历史页直接消费。
     *
     * @param historyJson 原始历史列表 JSON
     * @param currentProvider 当前 provider 标识
     * @return 合并收藏状态后的历史列表 JSON；增强失败时回退原始 JSON
     */
    private String enhanceHistoryWithFavorites(String historyJson, String currentProvider) {
        try {
            String favoritesJson = nodeJsServiceCaller.callNodeJsFavoritesService("loadFavorites", "");

            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            JsonObject favorites = new Gson().fromJson(favoritesJson, JsonObject.class);

            if (history.has("sessions") && history.get("sessions").isJsonArray()) {
                JsonArray sessions = history.getAsJsonArray("sessions");
                for (int i = 0; i < sessions.size(); i++) {
                    JsonObject session = sessions.get(i).getAsJsonObject();
                    String sessionId = session.get("sessionId").getAsString();

                    session.addProperty("provider", currentProvider);

                    if (favorites.has(sessionId)) {
                        JsonObject favoriteInfo = favorites.getAsJsonObject(sessionId);
                        session.addProperty("isFavorited", true);
                        session.addProperty("favoritedAt", favoriteInfo.get("favoritedAt").getAsLong());
                    } else {
                        session.addProperty("isFavorited", false);
                    }
                }
            }

            history.add("favorites", favorites);
            return new Gson().toJson(history);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 增强收藏信息失败，回退原始数据: " + e.getMessage());
            return historyJson;
        }
    }

    /**
     * 为历史列表补充自定义标题。
     * <p>
     * 当标题映射中存在对应 session 的 `customTitle` 时，使用自定义标题覆盖原始标题，
     * 并写入 `hasCustomTitle=true` 供前端区分展示。
     *
     * @param historyJson 已包含基础历史信息的 JSON
     * @return 合并自定义标题后的历史列表 JSON；增强失败时回退原始 JSON
     */
    private String enhanceHistoryWithTitles(String historyJson) {
        try {
            String titlesJson = nodeJsServiceCaller.callNodeJsTitlesService("loadTitles");

            JsonObject history = new Gson().fromJson(historyJson, JsonObject.class);
            JsonObject titles = new Gson().fromJson(titlesJson, JsonObject.class);

            if (history.has("sessions") && history.get("sessions").isJsonArray()) {
                JsonArray sessions = history.getAsJsonArray("sessions");
                for (int i = 0; i < sessions.size(); i++) {
                    JsonObject session = sessions.get(i).getAsJsonObject();
                    String sessionId = session.get("sessionId").getAsString();

                    if (titles.has(sessionId)) {
                        JsonObject titleInfo = titles.getAsJsonObject(sessionId);
                        if (titleInfo.has("customTitle")) {
                            String customTitle = titleInfo.get("customTitle").getAsString();
                            session.addProperty("title", customTitle);
                            session.addProperty("hasCustomTitle", true);
                        }
                    }
                }
            }

            return new Gson().toJson(history);
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] 增强自定义标题失败，回退原始数据: " + e.getMessage());
            return historyJson;
        }
    }
}
