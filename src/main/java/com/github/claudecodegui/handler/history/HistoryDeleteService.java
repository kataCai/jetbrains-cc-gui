package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;

import com.github.claudecodegui.cache.SessionIndexCache;
import com.github.claudecodegui.cache.SessionIndexManager;
import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.github.claudecodegui.util.PathUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Service for deleting session history files and related data.
 */
class HistoryDeleteService {

    private static final Logger LOG = Logger.getInstance(HistoryDeleteService.class);
    private static final Gson GSON = new Gson();

    // Reject anything outside [A-Za-z0-9._-] to defeat path-traversal payloads such as "../foo"
    // before they reach Path.resolve. Session IDs in both providers are alphanumeric/UUID style.
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9._-]+$");

    static boolean isValidSessionId(String sessionId) {
        return sessionId != null && SESSION_ID_PATTERN.matcher(sessionId).matches();
    }

    private final HandlerContext context;
    private final NodeJsServiceCaller nodeJsServiceCaller;
    private final HistoryLoadService historyLoadService;

    HistoryDeleteService(HandlerContext context, NodeJsServiceCaller nodeJsServiceCaller, HistoryLoadService historyLoadService) {
        this.context = context;
        this.nodeJsServiceCaller = nodeJsServiceCaller;
        this.historyLoadService = historyLoadService;
    }

    /**
     * Delete session history files.
     * Deletes the .jsonl file for the specified sessionId and related agent-xxx.jsonl files.
     */
    void handleDeleteSession(String sessionId, String currentProvider) {
        DeleteRequest request = parseDeleteRequest(sessionId);
        if (!isValidSessionId(request.getSessionId())) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return;
        }
        String resolvedSessionId = request.getSessionId();
        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== Delete session start ==========");
                LOG.info("[HistoryHandler] SessionId: " + resolvedSessionId
                        + ", LogicalConversationId: " + request.getLogicalConversationId()
                        + ", Provider: " + currentProvider);

                DeleteResult result = deleteSessionFiles(
                        resolvedSessionId,
                        request.getLogicalConversationId(),
                        currentProvider
                );

                LOG.info("[HistoryHandler] Delete completed - Main file: " + (result.mainDeleted ? "deleted" : "not found") + ", Agent files: " + result.agentFilesDeleted);

                if (result.mainDeleted) {
                    cleanupSessionMetadata(resolvedSessionId, request.getLogicalConversationId());
                }
                cleanupCache(currentProvider);

                LOG.info("[HistoryHandler] Reloading history data...");
                historyLoadService.handleLoadHistoryData(currentProvider);

            } catch (Exception e) {
                LOG.error("[HistoryHandler] Delete session failed: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Batch delete session history files in one backend request.
     */
    void handleDeleteSessions(String content, String currentProvider) {
        List<DeleteRequest> requests = parseDeleteRequests(content);
        if (requests.isEmpty()) {
            LOG.warn("[HistoryHandler] Batch delete failed: empty sessionIds");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                LOG.info("[HistoryHandler] ========== Batch delete sessions start ==========");
                LOG.info("[HistoryHandler] Requests: " + GSON.toJson(requests) + ", Provider: " + currentProvider);

                int mainDeletedCount = 0;
                int agentFilesDeletedCount = 0;

                for (DeleteRequest request : requests) {
                    try {
                        DeleteResult result = deleteSessionFiles(
                                request.getSessionId(),
                                request.getLogicalConversationId(),
                                currentProvider
                        );
                        if (result.mainDeleted) {
                            mainDeletedCount++;
                            cleanupSessionMetadata(request.getSessionId(), request.getLogicalConversationId());
                        }
                        agentFilesDeletedCount += result.agentFilesDeleted;
                    } catch (Exception e) {
                        LOG.error("[HistoryHandler] Batch delete single session failed: "
                                + request.getSessionId() + " - " + e.getMessage(), e);
                    }
                }

                cleanupCache(currentProvider);

                LOG.info("[HistoryHandler] Batch delete completed - Main files: " + mainDeletedCount + "/" + requests.size()
                        + ", Agent files: " + agentFilesDeletedCount);
                LOG.info("[HistoryHandler] Reloading history data...");
                historyLoadService.handleLoadHistoryData(currentProvider);
            } catch (Exception e) {
                LOG.error("[HistoryHandler] Batch delete sessions failed: " + e.getMessage(), e);
            }
        });
    }

    static List<String> parseSessionIds(String content) {
        return parseDeleteRequests(content)
                .stream()
                .map(DeleteRequest::getSessionId)
                .collect(Collectors.toList());
    }

    /**
     * 解析批量删除载荷，并尽量保留每个目标对应的 logicalConversationId。
     * 旧版前端只会传纯 sessionId 数组；新版前端会传对象数组，从而让后端按逻辑会话语义级联删除。
     *
     * @param content 前端传入的批量删除载荷
     * @return 去重且已做基础合法性校验的删除目标列表
     */
    static List<DeleteRequest> parseDeleteRequests(String content) {
        LinkedHashSet<String> dedupKeys = new LinkedHashSet<>();
        List<DeleteRequest> requests = new ArrayList<>();
        LinkedHashSet<String> sessionIds = new LinkedHashSet<>();
        if (content == null || content.trim().isEmpty()) {
            return requests;
        }

        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonArray()) {
                collectDeleteRequests(parsed.getAsJsonArray(), dedupKeys, requests);
            } else if (parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                JsonElement sessionIdsElement = object.get("sessionIds");
                if (sessionIdsElement != null && sessionIdsElement.isJsonArray()) {
                    collectDeleteRequests(sessionIdsElement.getAsJsonArray(), dedupKeys, requests);
                }
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Batch delete sessionIds parse failed: " + e.getMessage());
        }

        return requests;
    }

    /**
     * 解析单条历史删除载荷。
     * 该方法兼容旧版纯 sessionId 字符串和新版 JSON 对象，JSON 对象会额外保留 logicalConversationId，
     * 以便删除链路可以从“单物理分段”升级到“整条逻辑会话”语义。
     *
     * @param content 前端传入的删除载荷
     * @return 归一化后的删除请求；无法解析时返回空请求
     */
    static DeleteRequest parseDeleteRequest(String content) {
        if (content == null || content.trim().isEmpty()) {
            return DeleteRequest.empty();
        }

        String trimmedContent = content.trim();
        try {
            JsonElement parsed = JsonParser.parseString(trimmedContent);
            if (parsed != null && parsed.isJsonObject()) {
                JsonObject object = parsed.getAsJsonObject();
                return new DeleteRequest(
                        readString(object, "sessionId"),
                        readString(object, "logicalConversationId")
                );
            }
        } catch (Exception ignored) {
            // 兼容旧入口：不是 JSON 时直接按物理 sessionId 处理。
        }

        return new DeleteRequest(trimmedContent, "");
    }

    /**
     * 从 JSON 对象中读取可选字符串字段。
     * 该方法只做轻量归一化，不承担业务校验；调用方仍需按 sessionId 与 logicalConversationId 的各自规则校验。
     *
     * @param object 来源 JSON 对象
     * @param key 字段名
     * @return 去除首尾空白后的字段值；不存在或非字符串时返回空串
     */
    private static String readString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return "";
        }
        return value.getAsString() == null ? "" : value.getAsString().trim();
    }

    private static void collectDeleteRequests(
            JsonArray array,
            LinkedHashSet<String> dedupKeys,
            List<DeleteRequest> requests
    ) {
        for (JsonElement element : array) {
            if (element.isJsonObject()) {
                addDeleteRequest(
                        new DeleteRequest(
                                readString(element.getAsJsonObject(), "sessionId"),
                                readString(element.getAsJsonObject(), "logicalConversationId")
                        ),
                        dedupKeys,
                        requests
                );
                continue;
            }

            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                continue;
            }

            addDeleteRequest(new DeleteRequest(element.getAsString().trim(), ""), dedupKeys, requests);
        }
    }

    /**
     * 把单个批量删除目标加入结果集，同时基于 logicalConversationId 优先去重。
     * 对聚合后的 Codex 会话，同一个 logicalConversationId 下可能包含多个物理 sessionId，
     * 批量删除时应视为同一条用户可见会话，避免重复删除同一逻辑会话。
     *
     * @param request 候选删除目标
     * @param dedupKeys 去重键集合
     * @param requests 输出列表
     */
    private static void addDeleteRequest(
            DeleteRequest request,
            LinkedHashSet<String> dedupKeys,
            List<DeleteRequest> requests
    ) {
        if (request == null || !isValidSessionId(request.getSessionId())) {
            LOG.warn("[HistoryHandler] Batch delete ignored invalid sessionId");
            return;
        }
        String dedupKey = hasText(request.getLogicalConversationId())
                ? "logical:" + request.getLogicalConversationId()
                : "session:" + request.getSessionId();
        if (!dedupKeys.add(dedupKey)) {
            return;
        }
        requests.add(request);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private DeleteResult deleteSessionFiles(String sessionId, String logicalConversationId, String currentProvider) throws java.io.IOException {
        if (!isValidSessionId(sessionId)) {
            LOG.warn("[HistoryHandler] Delete session rejected: invalid sessionId");
            return new DeleteResult(false, 0);
        }
        if ("codex".equals(currentProvider)) {
            return deleteCodexLogicalConversation(sessionId, logicalConversationId);
        }

        String projectPath = context.getProject().getBasePath();
        if (projectPath == null) {
            LOG.warn("[HistoryHandler] Project base path is null, cannot delete Claude session");
            return new DeleteResult(false, 0);
        }

        int[] result = deleteClaudeSession(sessionId, projectPath);
        return new DeleteResult(result[0] == 1, result[1]);
    }

    /**
     * 以逻辑会话语义删除 Codex 历史。
     * 当 logicalConversationId 存在时，展开删除该逻辑会话下的全部物理分段；否则退回旧的单 session 删除语义。
     *
     * @param sessionId 当前代表分段 sessionId
     * @param logicalConversationId 目标逻辑会话 id
     * @return 删除结果汇总
     * @throws java.io.IOException 文件删除失败时抛出
     */
    private DeleteResult deleteCodexLogicalConversation(String sessionId, String logicalConversationId) throws java.io.IOException {
        List<String> sessionIdsToDelete = new ArrayList<>();
        if (logicalConversationId != null && !logicalConversationId.trim().isEmpty()) {
            try {
                sessionIdsToDelete.addAll(
                        context.getSettingsService()
                                .listConversationSegments(logicalConversationId)
                                .stream()
                                .map(ConversationSegmentRecord::getSessionId)
                                .filter(HistoryDeleteService::isValidSessionId)
                                .collect(Collectors.toList())
                );
            } catch (Exception e) {
                LOG.warn("[HistoryHandler] Failed to expand logical conversation segments, fallback to representative session: "
                        + logicalConversationId + " - " + e.getMessage());
            }
        }
        if (sessionIdsToDelete.isEmpty()) {
            sessionIdsToDelete.add(sessionId);
        }

        boolean deleted = false;
        for (String sessionIdToDelete : sessionIdsToDelete) {
            deleted = deleteCodexSession(sessionIdToDelete) || deleted;
        }
        return new DeleteResult(deleted, 0);
    }

    private boolean deleteCodexSession(String sessionId) throws java.io.IOException {
        String homeDir = PlatformUtils.getHomeDirectory();
        Path sessionDir = Paths.get(homeDir, ".codex", "sessions");

        if (!Files.exists(sessionDir)) {
            LOG.error("[HistoryHandler] Codex session directory not found: " + sessionDir);
            return false;
        }

        boolean deleted = false;
        try (Stream<Path> paths = Files.walk(sessionDir)) {
            List<Path> sessionFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> isCodexSessionFileMatch(path, sessionId))
                    .collect(Collectors.toList());

            for (Path sessionFile : sessionFiles) {
                try {
                    Files.delete(sessionFile);
                    LOG.info("[HistoryHandler] Deleted Codex session file: " + sessionFile);
                    deleted = true;
                } catch (Exception e) {
                    LOG.error("[HistoryHandler] Failed to delete Codex session file: " + sessionFile + " - " + e.getMessage(), e);
                }
            }
        }
        return deleted;
    }

    /**
     * Match Codex rollout filenames whose UUID suffix equals the session ID.
     * Real format: rollout-{ISO timestamp}-{sessionId}.jsonl, so we anchor to "-{sessionId}.jsonl"
     * to avoid removing neighbouring sessions whose UUIDs share a substring.
     */
    static boolean isCodexSessionFileMatch(Path path, String sessionId) {
        if (path == null || sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        String fileName = path.getFileName().toString();
        return fileName.endsWith("-" + sessionId + ".jsonl");
    }

    /**
     * @return int[2]: [mainDeleted(0/1), agentFilesDeleted]
     */
    private int[] deleteClaudeSession(String sessionId, String projectPath) throws java.io.IOException {
        String homeDir = PlatformUtils.getHomeDirectory();
        Path claudeDir = Paths.get(homeDir, ".claude");
        Path projectsDir = claudeDir.resolve("projects");
        String sanitizedPath = PathUtils.sanitizePath(projectPath);
        Path sessionDir = projectsDir.resolve(sanitizedPath);

        if (!Files.exists(sessionDir)) {
            LOG.error("[HistoryHandler] Claude project directory not found: " + sessionDir);
            return new int[]{0, 0};
        }

        boolean mainDeleted = false;
        int agentFilesDeleted = 0;

        // Delete main session file
        Path mainSessionFile = sessionDir.resolve(sessionId + ".jsonl").normalize();
        if (!mainSessionFile.startsWith(sessionDir.normalize())) {
            LOG.warn("[HistoryHandler] Refused out-of-bounds path: " + mainSessionFile);
            return new int[]{0, 0};
        }
        if (Files.exists(mainSessionFile)) {
            Files.delete(mainSessionFile);
            LOG.info("[HistoryHandler] Deleted main session file: " + mainSessionFile.getFileName());
            mainDeleted = true;
        } else {
            LOG.warn("[HistoryHandler] Main session file not found: " + mainSessionFile.getFileName());
        }

        // Delete related agent files
        try (Stream<Path> stream = Files.list(sessionDir)) {
            List<Path> agentFiles = stream
                    .filter(path -> {
                        String filename = path.getFileName().toString();
                        return filename.startsWith("agent-") && filename.endsWith(".jsonl")
                                && isAgentFileRelatedToSession(path, sessionId);
                    })
                    .collect(Collectors.toList());

            for (Path agentFile : agentFiles) {
                try {
                    Files.delete(agentFile);
                    LOG.info("[HistoryHandler] Deleted related agent file: " + agentFile.getFileName());
                    agentFilesDeleted++;
                } catch (Exception e) {
                    LOG.error("[HistoryHandler] Failed to delete agent file: " + agentFile.getFileName() + " - " + e.getMessage(), e);
                }
            }
        }

        return new int[]{mainDeleted ? 1 : 0, agentFilesDeleted};
    }

    private void cleanupSessionMetadata(String sessionId, String logicalConversationId) {
        try {
            nodeJsServiceCaller.callNodeJsFavoritesService("removeFavorite", sessionId);
            nodeJsServiceCaller.callNodeJsDeleteTitle(sessionId);
            if (logicalConversationId != null && !logicalConversationId.trim().isEmpty()) {
                context.getSettingsService().deleteLogicalConversationCascade(logicalConversationId);
            } else {
                context.getSettingsService().deleteConversationSegmentRecord(sessionId);
                context.getSettingsService().deleteCodexSessionBinding(sessionId);
            }
            LOG.info("[HistoryHandler] Cleaned up session metadata");
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up metadata (does not affect deletion): " + e.getMessage());
        }
    }

    private void cleanupCache(String currentProvider) {
        try {
            String projectPath = context.getProject().getBasePath();
            if ("codex".equals(currentProvider)) {
                SessionIndexCache.getInstance().clearAllCodexCache();
                SessionIndexManager.getInstance().clearAllCodexIndex();
            } else if (projectPath != null) {
                SessionIndexCache.getInstance().clearProject(projectPath);
                SessionIndexManager.getInstance().clearProjectIndex("claude", projectPath);
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to clean up cache (does not affect deletion): " + e.getMessage());
        }
    }

    /**
     * Check if an agent file belongs to the specified session.
     */
    private boolean isAgentFileRelatedToSession(Path agentFilePath, String sessionId) {
        try (BufferedReader reader = Files.newBufferedReader(agentFilePath, StandardCharsets.UTF_8)) {
            String line;
            int lineCount = 0;
            // Only read the first 20 lines for performance
            while ((line = reader.readLine()) != null && lineCount < 20) {
                if (line.contains("\"sessionId\":\"" + sessionId + "\"") ||
                            line.contains("\"parentSessionId\":\"" + sessionId + "\"")) {
                    LOG.debug("[HistoryHandler] Agent file " + agentFilePath.getFileName() + " belongs to session " + sessionId);
                    return true;
                }
                lineCount++;
            }
            LOG.debug("[HistoryHandler] Agent file " + agentFilePath.getFileName() + " does not belong to session " + sessionId);
            return false;
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to read agent file " + agentFilePath.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    private static class DeleteResult {
        private final boolean mainDeleted;
        private final int agentFilesDeleted;

        private DeleteResult(boolean mainDeleted, int agentFilesDeleted) {
            this.mainDeleted = mainDeleted;
            this.agentFilesDeleted = agentFilesDeleted;
        }
    }

    /**
     * 单条历史删除请求的归一化结果。
     * sessionId 代表当前可直接删除的物理分段，logicalConversationId 代表上层希望删除的逻辑会话范围；
     * 后续删除链路会基于这两个字段决定是否展开为多分段清理。
     */
    static final class DeleteRequest {
        private final String sessionId;
        private final String logicalConversationId;

        /**
         * 创建删除请求。
         *
         * @param sessionId 代表物理分段的 sessionId
         * @param logicalConversationId 可选逻辑会话 id
         */
        private DeleteRequest(String sessionId, String logicalConversationId) {
            this.sessionId = sessionId == null ? "" : sessionId.trim();
            this.logicalConversationId = logicalConversationId == null ? "" : logicalConversationId.trim();
        }

        /**
         * 返回空删除请求，用于解析失败或空载荷场景。
         *
         * @return 空请求
         */
        private static DeleteRequest empty() {
            return new DeleteRequest("", "");
        }

        /**
         * 获取代表物理分段的 sessionId。
         *
         * @return 物理分段 sessionId
         */
        String getSessionId() {
            return sessionId;
        }

        /**
         * 获取逻辑会话 id。
         *
         * @return 逻辑会话 id；未提供时为空串
         */
        String getLogicalConversationId() {
            return logicalConversationId;
        }
    }
}
