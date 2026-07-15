package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.CodexMessageConverter;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.session.ClaudeSession;
import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.session.LogicalConversationRecord;
import com.github.claudecodegui.session.SessionRuntimeFamily;
import com.github.claudecodegui.session.SessionState;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.JsUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32;

/**
 * Service for loading session messages and injecting them into the frontend.
 * Handles both Claude and Codex session loading.
 */
public class HistoryMessageInjector {

    private static final Logger LOG = Logger.getInstance(HistoryMessageInjector.class);
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";
    private static final String USER_HISTORY_SHAPE_EVENT = "event_msg_user_message";
    private static final String USER_HISTORY_SHAPE_RESPONSE = "response_item_user_message";

    private final HandlerContext context;

    HistoryMessageInjector(HandlerContext context) {
        this.context = context;
    }

    /**
     * Load a history session.
     */
    void handleLoadSession(String sessionId, String currentProvider, HistoryHandler.SessionLoadCallback sessionLoadCallback) {
        SessionLoadRequest request = parseSessionLoadRequest(sessionId, currentProvider);
        String provider = request.getProvider();
        String runtimeFamily = request.getRuntimeFamily();
        String restoreSource = request.getRestoreSource();
        String transitionToken = request.getTransitionToken();
        String resolvedSessionId = request.getRequestedSessionId();

        String projectPath = context.getProject().getBasePath();
        if (projectPath == null) {
            LOG.warn("[HistoryHandler] Project base path is null");
            return;
        }
        String resolvedRuntimeFamily = SessionRuntimeFamily.resolve(
                provider,
                runtimeFamily,
                context.getSession() != null ? context.getSession().getState().getCodexSessionBinding() : null
        );
        LOG.info("[HistoryHandler] Loading history session: " + resolvedSessionId
                + " from project: " + projectPath + ", provider: " + provider
                + ", runtimeFamily=" + resolvedRuntimeFamily
                + ", restoreSource=" + restoreSource
                + ", transitionToken=" + transitionToken
                + ", currentProvider=" + currentProvider);
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.handleLoadSession request="
                + request.toTraceString()
                + ", resolvedRuntimeFamily=" + resolvedRuntimeFamily
                + ", currentProvider=" + firstNonBlank(currentProvider));

        if (SessionRuntimeFamily.CODEX.equals(resolvedRuntimeFamily)) {
            CodexRestorePlan restorePlan = buildCodexRestorePlan(request, context.getSettingsService());
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.handleLoadSession codexRestorePlan="
                    + describeRestorePlanForTrace(restorePlan)
                    + ", restoreSource=" + restoreSource
                    + ", transitionToken=" + transitionToken);
            // 中文注释：单段 Codex 历史恢复交给 SessionLifecycleManager 主链处理，保留其 restore 去重与快照防重能力；
            // 逻辑会话或多分段恢复仍在注入器内完成，避免 callback 形态丢失 continued segment 上下文。
            boolean hasContinuationHints = hasText(request.getLogicalConversationId())
                    || hasText(request.getActiveSegmentSessionId());
            boolean isMultiSegmentRestore = restorePlan != null && restorePlan.getSegmentSessionIds().size() > 1;
            if (sessionLoadCallback != null && !hasContinuationHints && !isMultiSegmentRestore) {
                sessionLoadCallback.onLoadSession(
                        resolvedSessionId,
                        projectPath,
                        provider,
                        resolvedRuntimeFamily,
                        restoreSource,
                        transitionToken
                );
            } else {
                loadCodexSession(restorePlan);
            }
        } else if (sessionLoadCallback != null) {
            sessionLoadCallback.onLoadSession(
                    resolvedSessionId,
                    projectPath,
                    provider,
                    resolvedRuntimeFamily,
                    restoreSource,
                    transitionToken
            );
        } else {
            LOG.warn("[HistoryHandler] WARNING: No session load callback set");
        }
    }

    /**
     * Load a Codex session.
     * Reads session messages directly and injects them into the frontend, while restoring session state.
     */
    private void loadCodexSession(String sessionId) {
        CompletableFuture.runAsync(() -> {
            LOG.info("[HistoryHandler] ========== 开始加载 Codex 会话 ==========");
            LOG.info("[HistoryHandler] SessionId: " + sessionId);

            try {
                CodexHistoryReader codexReader = new CodexHistoryReader();
                String messagesJson = codexReader.getSessionMessagesAsJson(sessionId);
                JsonArray messages = JsonParser.parseString(messagesJson).getAsJsonArray();

                LOG.info("[HistoryHandler] 读取到 " + messages.size() + " 条 Codex 消息");

                // Extract session metadata and restore session state
                String[] sessionMeta = extractSessionMeta(messages);
                String threadIdToUse = sessionMeta[0] != null ? sessionMeta[0] : sessionId;
                String cwd = sessionMeta[1];

                context.getSession().setSessionInfo(threadIdToUse, cwd);
                applyCodexSessionBinding(threadIdToUse);
                restoreCodexMessagesToSessionState(context.getSession().getState(), messages);
                LOG.info("[HistoryHandler] 恢复 Codex 会话状态: threadId=" + threadIdToUse + " (from sessionId=" + sessionId + "), cwd=" + cwd);

                List<JsonObject> frontendMessages = convertCodexMessagesToFrontendBatch(messages);
                injectBatchToFrontend(frontendMessages);

                // Notify frontend that history messages have finished loading, trigger Markdown re-rendering
                ApplicationManager.getApplication().invokeLater(() -> {
                    String jsCode = "if (window.historyLoadComplete) { " +
                                            "  try { " +
                                            "    window.historyLoadComplete(); " +
                                            "  } catch(e) { " +
                                            "    console.error('[HistoryHandler] historyLoadComplete callback failed:', e); " +
                                            "  } " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });

                LOG.info("[HistoryHandler] ========== Codex 会话加载完成 ==========");

            } catch (Exception e) {
                LOG.error("[HistoryHandler] 加载 Codex 会话失败: " + e.getMessage(), e);

                ApplicationManager.getApplication().invokeLater(() -> {
                    String errorMsg = context.escapeJs(e.getMessage() != null ? e.getMessage() : "未知错误");
                    String jsCode = "if (window.addErrorMessage) { " +
                                            "  window.addErrorMessage('加载 Codex 会话失败: " + errorMsg + "'); " +
                                            "}";
                    context.executeJavaScriptOnEDT(jsCode);
                });
            }
        });
    }

    /**
     * Extract Codex session metadata (threadId and cwd).
     *
     * @return String[2]: [0]=actualThreadId, [1]=cwd
     */
    private static String[] extractSessionMeta(JsonArray messages) {
        return extractCodexSessionMeta(messages);
    }

    /**
     * 提取 Codex 历史消息中的会话元信息。
     * 这里统一返回真实 threadId 与 cwd，供历史恢复主链与聚合恢复链路复用，避免多处各自解析导致规则漂移。
     *
     * @param messages Codex 原始历史消息数组
     * @return 长度为 2 的数组：[0] 为实际 threadId，[1] 为 cwd；缺失时对应元素为 null
     */
    public static String[] extractCodexSessionMeta(JsonArray messages) {
        String cwd = null;
        String actualThreadId = null;

        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            if (msg.has("type") && "session_meta".equals(msg.get("type").getAsString())) {
                if (msg.has("payload")) {
                    JsonObject payload = msg.getAsJsonObject("payload");
                    if (payload.has("cwd")) {
                        cwd = payload.get("cwd").getAsString();
                    }
                    if (payload.has("id")) {
                        actualThreadId = payload.get("id").getAsString();
                    }
                    break;
                }
            }
        }

        return new String[]{actualThreadId, cwd};
    }

    /**
     * 将 Codex 历史消息批量转换为前端消息列表。
     * 只统一前端注入协议，不改变 Codex 历史文件格式与标题数据来源。
     */
    public static List<JsonObject> convertCodexMessagesToFrontendBatch(JsonArray messages) {
        List<JsonObject> frontendMessages = new ArrayList<>();
        appendConvertedCodexMessages(frontendMessages, messages, null, null);
        return frontendMessages;
    }

    /**
     * 按逻辑会话分段顺序把多段 Codex 原始历史转换为前端消息列表，并在分段边界插入系统提示。
     * 该入口服务于“跨模型/跨供应商继续”的聚合恢复场景，显式提示用户当前消息已切换到新的运行分段，
     * 避免多段消息无缝拼接后误以为底层一直复用同一个 provider thread。
     *
     * @param segmentMessagesList 按分段顺序排列的原始历史消息数组列表
     * @param segmentRecords 与消息列表一一对应的分段元数据列表
     * @return 供前端直接渲染的聚合消息列表
     */
    public static List<JsonObject> convertCodexMessagesToFrontendBatch(
            List<JsonArray> segmentMessagesList,
            List<ConversationSegmentRecord> segmentRecords
    ) {
        List<JsonObject> frontendMessages = new ArrayList<>();
        if (segmentMessagesList == null || segmentMessagesList.isEmpty()) {
            return frontendMessages;
        }

        for (int segmentIndex = 0; segmentIndex < segmentMessagesList.size(); segmentIndex++) {
            JsonArray segmentMessages = segmentMessagesList.get(segmentIndex);
            if (segmentMessages == null) {
                continue;
            }
            ConversationSegmentRecord currentSegment = segmentRecords != null && segmentIndex < segmentRecords.size()
                    ? segmentRecords.get(segmentIndex)
                    : null;

            if (segmentIndex > 0) {
                JsonObject boundaryMessage = buildContinuationBoundarySystemMessage(currentSegment, frontendMessages.size());
                if (boundaryMessage != null) {
                    frontendMessages.add(boundaryMessage);
                }
            }

            appendConvertedCodexMessages(frontendMessages, segmentMessages, currentSegment, currentSegment);
        }
        return frontendMessages;
    }

    /**
     * 构造 continued segment 边界系统提示消息。
     * 当前提示文本以 provider/model 为核心，后续如需补充 switchReason 或父分段信息，可继续在该方法内扩展。
     *
     * @param segmentRecord 当前即将进入的分段元数据
     * @return 可插入前端消息流的系统消息；缺少有效元数据时返回 null
     */
    private static JsonObject buildContinuationBoundarySystemMessage(
            ConversationSegmentRecord segmentRecord,
            int logicalOrder
    ) {
        if (segmentRecord == null) {
            return null;
        }

        String runtimeFamily = firstNonBlank(segmentRecord.getRuntimeFamily(), segmentRecord.getProvider());
        String provider = resolveContinuationBoundaryProviderDisplay(segmentRecord, runtimeFamily);
        String model = firstNonBlank(segmentRecord.getModel());
        if (!hasText(provider) && !hasText(model)) {
            return null;
        }

        JsonObject systemMessage = new JsonObject();
        systemMessage.addProperty("type", "system");
        systemMessage.addProperty(
                "content",
                "已切换到 " + buildContinuationBoundaryDisplay(runtimeFamily, provider, model)
                        + " 继续当前会话。"
        );
        annotateBoundaryMessageMetadata(systemMessage, segmentRecord, logicalOrder);
        return systemMessage;
    }

    /**
     * 组装 continued 分段边界提示中的核心显示串。
     * 对 Codex 分段优先展示“运行时家族 / 供应商展示名(或 providerId) / 模型”，
     * 对旧数据或非 Codex 分段则回退到既有 provider/model 语义。
     */
    private static String buildContinuationBoundaryDisplay(String runtimeFamily, String provider, String model) {
        String normalizedRuntimeFamily = firstNonBlank(runtimeFamily);
        String normalizedProvider = firstNonBlank(provider);
        String normalizedModel = firstNonBlank(model);
        if (SessionRuntimeFamily.CODEX.equals(normalizedRuntimeFamily) && hasText(normalizedProvider)) {
            if (normalizedRuntimeFamily.equals(normalizedProvider)) {
                return normalizedRuntimeFamily + (hasText(normalizedModel) ? " / " + normalizedModel : "");
            }
            if (hasText(normalizedModel)) {
                return normalizedRuntimeFamily + " / " + normalizedProvider + " / " + normalizedModel;
            }
            return normalizedRuntimeFamily + " / " + normalizedProvider;
        }
        String fallbackProvider = firstNonBlank(normalizedProvider, normalizedRuntimeFamily, "unknown-provider");
        return fallbackProvider + (hasText(normalizedModel) ? " / " + normalizedModel : "");
    }

    /**
     * 解析 continued 分段边界提示中的供应商显示文本。
     * 对 Codex 分段优先走 providerDisplayName，其次回退到 codexProviderId；旧记录缺字段时再回退到历史 provider 字段。
     */
    private static String resolveContinuationBoundaryProviderDisplay(
            ConversationSegmentRecord segmentRecord,
            String runtimeFamily
    ) {
        if (segmentRecord == null) {
            return "";
        }
        if (SessionRuntimeFamily.CODEX.equals(firstNonBlank(runtimeFamily))) {
            return firstNonBlank(
                    segmentRecord.getProviderDisplayName(),
                    segmentRecord.getCodexProviderId(),
                    segmentRecord.getProvider()
            );
        }
        return firstNonBlank(segmentRecord.getProvider(), runtimeFamily);
    }

    /**
     * 为历史恢复后的前端消息快照构造稳定签名。
     * 该签名只关心“界面最终可见语义”，尤其会显式纳入图片块、失效图片占位块与文本块的关键信息，
     * 供前端判断同一 restore key 下的 `updateMessages` 是否只是重复注入同一份历史快照。
     *
     * @param frontendMessages 最终准备注入前端的消息列表
     * @return 稳定的历史快照签名
     */
    public static String buildFrontendSnapshotSignature(List<JsonObject> frontendMessages) {
        CRC32 checksum = new CRC32();
        updateSnapshotChecksum(checksum, "count:" + (frontendMessages != null ? frontendMessages.size() : 0));
        if (frontendMessages == null) {
            return "0-" + Long.toHexString(checksum.getValue());
        }

        for (JsonObject message : frontendMessages) {
            appendFrontendMessageSignature(checksum, message);
        }
        return frontendMessages.size() + "-" + Long.toHexString(checksum.getValue());
    }

    /**
     * 按当前分段上下文把一批 Codex 历史消息转换并追加到前端消息列表。
     * 该入口会在批内统一补齐稳定身份与顺序元数据，并在命中 provider 双录重复时保留已有 logicalOrder，
     * 避免 continued authoritative restore 与普通历史恢复再次只靠 timestamp 识别同一条消息。
     *
     * @param frontendMessages 已累计的前端消息列表
     * @param messages 当前待转换的 Codex 原始历史数组
     * @param segmentRecord 当前分段元数据；单段恢复时允许为空
     * @param identitySegmentRecord identity/顺序注解使用的分段元数据；边界场景下与 segmentRecord 保持同源
     */
    private static void appendConvertedCodexMessages(
            List<JsonObject> frontendMessages,
            JsonArray messages,
            ConversationSegmentRecord segmentRecord,
            ConversationSegmentRecord identitySegmentRecord
    ) {
        if (messages == null) {
            return;
        }
        int nextSegmentLocalIndex = 0;
        for (int i = 0; i < messages.size(); i++) {
            JsonObject msg = messages.get(i).getAsJsonObject();
            JsonObject frontendMsg = convertCodexMessageToFrontend(msg);
            if (frontendMsg == null) {
                continue;
            }
            annotateFrontendMessageMetadata(
                    frontendMsg,
                    msg,
                    identitySegmentRecord,
                    nextSegmentLocalIndex,
                    frontendMessages.size()
            );
            if (addCodexFrontendMessage(frontendMessages, frontendMsg)) {
                nextSegmentLocalIndex++;
            }
        }
    }

    /**
     * 将一条前端消息按“新逻辑消息”或“重复补录消息”两类场景写入列表。
     * 若命中 provider 同轮双录的 user 消息，则会复用已存在消息的稳定身份元数据，只替换为信息更丰富的版本。
     *
     * @param frontendMessages 已累计的前端消息列表
     * @param incoming 当前待写入的前端消息
     * @return true 表示新增了一条逻辑消息；false 表示只是合并到上一条重复消息
     */
    private static boolean addCodexFrontendMessage(List<JsonObject> frontendMessages, JsonObject incoming) {
        if (shouldDropNonUserInternalResidueMessage(incoming)) {
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.dropNonUserInternalResidue"
                    + ", key=" + firstNonBlank(extractMessageIdentityKey(incoming))
                    + ", type=" + firstNonBlank(getStringProperty(incoming, "type"))
                    + ", contentDigest=" + buildContentDigest(getStringProperty(incoming, "content")));
            return false;
        }
        if (frontendMessages.isEmpty()) {
            frontendMessages.add(incoming);
            return true;
        }

        int lastIndex = frontendMessages.size() - 1;
        JsonObject previous = frontendMessages.get(lastIndex);
        if (isDuplicateAdjacentCodexUserMessage(previous, incoming)) {
            JsonObject preferred = preferRicherUserMessage(previous, incoming);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.foldDuplicateUserMessage"
                    + ", previousKey=" + firstNonBlank(extractMessageIdentityKey(previous))
                    + ", incomingKey=" + firstNonBlank(extractMessageIdentityKey(incoming))
                    + ", previousHistorySourceKind=" + firstNonBlank(extractUserHistorySourceKind(previous))
                    + ", incomingHistorySourceKind=" + firstNonBlank(extractUserHistorySourceKind(incoming))
                    + ", previousContentDigest=" + buildContentDigest(getStringProperty(previous, "content"))
                    + ", incomingContentDigest=" + buildContentDigest(getStringProperty(incoming, "content"))
                    + ", keptContentDigest=" + buildContentDigest(getStringProperty(preferred, "content")));
            frontendMessages.set(lastIndex, inheritStableFrontendMetadata(previous, preferred));
            return false;
        }

        frontendMessages.add(incoming);
        return true;
    }

    private static boolean isDuplicateAdjacentCodexUserMessage(JsonObject previous, JsonObject incoming) {
        if (!isUserMessage(previous) || !isUserMessage(incoming)) {
            return false;
        }

        String previousIdentityKey = extractMessageIdentityKey(previous);
        String incomingIdentityKey = extractMessageIdentityKey(incoming);
        if (hasText(previousIdentityKey) && previousIdentityKey.equals(incomingIdentityKey)) {
            return true;
        }

        String previousContent = normalizeDuplicateUserContent(getStringProperty(previous, "content"));
        String incomingContent = normalizeDuplicateUserContent(getStringProperty(incoming, "content"));
        if (previousContent.isEmpty() || !previousContent.equals(incomingContent)) {
            return false;
        }

        // 中文注释：同一轮 Codex user turn 可能同时落成 response_item/message 与 event_msg/user_message 两条相邻记录，
        // 这两条记录的时间戳并不稳定一致，不能继续只依赖 timestamp 判重，否则 authoritative restore 仍会把同一句用户输入展示两次。
        if (isComplementaryDualRecordedCodexUserMessage(previous, incoming)) {
            return true;
        }

        String previousTimestamp = getStringProperty(previous, "timestamp");
        String incomingTimestamp = getStringProperty(incoming, "timestamp");
        if (previousTimestamp == null || !previousTimestamp.equals(incomingTimestamp)) {
            return false;
        }
        return true;
    }

    private static JsonObject preferRicherUserMessage(JsonObject previous, JsonObject incoming) {
        int previousScore = getUserMessageSemanticScore(previous);
        int incomingScore = getUserMessageSemanticScore(incoming);
        if (incomingScore != previousScore) {
            return incomingScore > previousScore ? incoming : previous;
        }
        return getRawContentBlockCount(incoming) > getRawContentBlockCount(previous) ? incoming : previous;
    }

    private static String normalizeDuplicateUserContent(String content) {
        if (content == null) {
            return "";
        }
        return content
            .replaceAll("(?m)^<image[^\\r\\n]*>\\R?", "")
            .replaceAll("(?m)^</image>\\R?", "")
            .trim();
    }

    private static boolean isUserMessage(JsonObject message) {
        return "user".equals(getStringProperty(message, "type"));
    }

    private static String getStringProperty(JsonObject object, String propertyName) {
        if (object == null || !object.has(propertyName) || object.get(propertyName).isJsonNull()) {
            return null;
        }
        return object.get(propertyName).getAsString();
    }

    /**
     * 判断两条相邻 user 消息是否正好来自 Codex 同轮双录的两种历史形态。
     * 这里只接受 response_item/user 与 event_msg/user_message 的互补组合，
     * 避免把用户真实连续发送的两条相同 event_msg 或两条相同 response_item 误折叠。
     *
     * @param previous 已存在的上一条用户消息
     * @param incoming 当前待写入的用户消息
     * @return true 表示命中 Codex 同轮双录互补形态
     */
    private static boolean isComplementaryDualRecordedCodexUserMessage(JsonObject previous, JsonObject incoming) {
        String previousShape = extractUserHistorySourceKind(previous);
        String incomingShape = extractUserHistorySourceKind(incoming);
        return (USER_HISTORY_SHAPE_RESPONSE.equals(previousShape) && USER_HISTORY_SHAPE_EVENT.equals(incomingShape))
                || (USER_HISTORY_SHAPE_EVENT.equals(previousShape) && USER_HISTORY_SHAPE_RESPONSE.equals(incomingShape));
    }

    /**
     * 读取注解到前端消息上的 Codex user 历史来源形态。
     * 该元数据只用于 restore 阶段识别 provider 同轮双录，不参与普通前端展示。
     *
     * @param message 前端消息
     * @return user 历史来源形态；缺失时返回空串
     */
    private static String extractUserHistorySourceKind(JsonObject message) {
        if (message == null || !message.has("messageIdentity") || !message.get("messageIdentity").isJsonObject()) {
            return "";
        }
        return firstNonBlank(getStringProperty(message.getAsJsonObject("messageIdentity"), "historySourceKind"));
    }

    /**
     * 为单条前端消息补齐稳定身份与顺序元数据。
     * 这里优先使用 provider 原始 source id 作为 identity key；缺失时再回退到分段内局部顺序，
     * 保证 authoritative logical snapshot 在前端可以稳定覆盖 prefix merge 产生的同一条消息。
     *
     * @param frontendMsg 待注解的前端消息
     * @param sourceMsg 对应的 Codex 原始历史消息
     * @param segmentRecord 当前分段元数据；单段恢复时可为空
     * @param segmentLocalIndex 该消息在当前物理分段内的稳定局部顺序
     * @param logicalOrder 该消息在最终前端列表中的稳定渲染顺序
     */
    private static void annotateFrontendMessageMetadata(
            JsonObject frontendMsg,
            JsonObject sourceMsg,
            ConversationSegmentRecord segmentRecord,
            int segmentLocalIndex,
            int logicalOrder
    ) {
        if (frontendMsg == null) {
            return;
        }
        String role = firstNonBlank(getStringProperty(frontendMsg, "type"), "unknown");
        String segmentSessionId = segmentRecord != null ? firstNonBlank(segmentRecord.getSessionId()) : "";
        int segmentIndex = segmentRecord != null ? Math.max(0, segmentRecord.getSegmentIndex()) : 0;
        String sourceId = extractCodexStableSourceId(sourceMsg, frontendMsg);
        String identityKey = buildFrontendMessageIdentityKey(
                role,
                sourceId,
                frontendMsg,
                segmentSessionId,
                segmentIndex,
                segmentLocalIndex
        );

        JsonObject messageIdentity = new JsonObject();
        messageIdentity.addProperty("key", identityKey);
        messageIdentity.addProperty("role", role);
        if (hasText(sourceId)) {
            messageIdentity.addProperty("sourceId", sourceId);
        }
        String historySourceKind = extractCodexUserHistoryShape(sourceMsg, role);
        if (hasText(historySourceKind)) {
            messageIdentity.addProperty("historySourceKind", historySourceKind);
        }
        if (hasText(segmentSessionId)) {
            messageIdentity.addProperty("segmentSessionId", segmentSessionId);
        }
        messageIdentity.addProperty("segmentIndex", segmentIndex);
        messageIdentity.addProperty("segmentLocalIndex", segmentLocalIndex);
        messageIdentity.addProperty("logicalOrder", logicalOrder);

        frontendMsg.add("messageIdentity", messageIdentity);
        frontendMsg.addProperty("logicalOrder", logicalOrder);
        frontendMsg.addProperty("segmentIndex", segmentIndex);
        if (hasText(segmentSessionId)) {
            frontendMsg.addProperty("segmentSessionId", segmentSessionId);
        }
        frontendMsg.addProperty("segmentLocalIndex", segmentLocalIndex);
        copyStableMetadataIntoRaw(frontendMsg, messageIdentity, logicalOrder, segmentIndex, segmentSessionId, segmentLocalIndex);
    }

    /**
     * 为 continued 分段边界系统消息补齐稳定 identity 与顺序元数据。
     * 该身份用于前端在多次 authoritative restore 之间识别同一条边界提示，避免重复插入“已切换到...”系统消息。
     *
     * @param boundaryMessage 边界系统消息
     * @param segmentRecord 当前即将进入的分段元数据
     * @param logicalOrder 最终渲染顺序号
     */
    private static void annotateBoundaryMessageMetadata(
            JsonObject boundaryMessage,
            ConversationSegmentRecord segmentRecord,
            int logicalOrder
    ) {
        if (boundaryMessage == null || segmentRecord == null) {
            return;
        }
        String segmentSessionId = firstNonBlank(segmentRecord.getSessionId());
        int segmentIndex = Math.max(0, segmentRecord.getSegmentIndex());
        String provider = firstNonBlank(
                segmentRecord.getProviderDisplayName(),
                segmentRecord.getCodexProviderId(),
                segmentRecord.getProvider(),
                segmentRecord.getRuntimeFamily()
        );
        String model = firstNonBlank(segmentRecord.getModel());
        String identityKey = "system-boundary|segment=" + segmentSessionId
                + "|provider=" + provider
                + "|model=" + model;

        JsonObject messageIdentity = new JsonObject();
        messageIdentity.addProperty("key", identityKey);
        messageIdentity.addProperty("role", "system");
        messageIdentity.addProperty("segmentSessionId", segmentSessionId);
        messageIdentity.addProperty("segmentIndex", segmentIndex);
        messageIdentity.addProperty("logicalOrder", logicalOrder);

        boundaryMessage.add("messageIdentity", messageIdentity);
        boundaryMessage.addProperty("logicalOrder", logicalOrder);
        boundaryMessage.addProperty("segmentIndex", segmentIndex);
        boundaryMessage.addProperty("segmentSessionId", segmentSessionId);
    }

    /**
     * 把稳定身份元数据同步写入 raw，供后续状态回放或调试链路在只拿到 raw 时仍能恢复关键顺序信息。
     *
     * @param frontendMsg 已注解顶层字段的前端消息
     * @param messageIdentity 顶层构造完成的 identity 对象
     * @param logicalOrder 全局稳定顺序
     * @param segmentIndex 物理分段序号
     * @param segmentSessionId 物理分段 sessionId
     * @param segmentLocalIndex 分段内局部顺序
     */
    private static void copyStableMetadataIntoRaw(
            JsonObject frontendMsg,
            JsonObject messageIdentity,
            int logicalOrder,
            int segmentIndex,
            String segmentSessionId,
            int segmentLocalIndex
    ) {
        if (frontendMsg == null || !frontendMsg.has("raw") || !frontendMsg.get("raw").isJsonObject()) {
            return;
        }
        JsonObject raw = frontendMsg.getAsJsonObject("raw");
        raw.add("messageIdentity", messageIdentity.deepCopy());
        raw.addProperty("logicalOrder", logicalOrder);
        raw.addProperty("segmentIndex", segmentIndex);
        if (hasText(segmentSessionId)) {
            raw.addProperty("segmentSessionId", segmentSessionId);
        }
        raw.addProperty("segmentLocalIndex", segmentLocalIndex);
    }

    /**
     * 构造前端消息稳定 identity key。
     * sourceId 可用时优先采用 provider 原始标识；否则回退到“分段 + 局部顺序”的稳定组合键，
     * 既保留 repeated user message 的区分能力，也让 authoritative restore 可以稳定覆盖旧前缀。
     *
     * @param role 前端消息角色
     * @param sourceId provider 原始稳定标识
     * @param frontendMsg 前端消息
     * @param segmentSessionId 所属物理分段 sessionId
     * @param segmentIndex 所属物理分段索引
     * @param segmentLocalIndex 分段内局部顺序
     * @return 稳定 identity key
     */
    private static String buildFrontendMessageIdentityKey(
            String role,
            String sourceId,
            JsonObject frontendMsg,
            String segmentSessionId,
            int segmentIndex,
            int segmentLocalIndex
    ) {
        if (hasText(sourceId)) {
            return role + "|source=" + sourceId;
        }
        String toolIdentity = extractToolIdentityFromFrontendMessage(frontendMsg);
        if (hasText(toolIdentity)) {
            return role + "|tool=" + toolIdentity;
        }
        String normalizedContent = normalizeDuplicateUserContent(firstNonBlank(getStringProperty(frontendMsg, "content")));
        String segmentToken = hasText(segmentSessionId) ? segmentSessionId : "segment-index-" + segmentIndex;
        return role + "|segment=" + segmentToken
                + "|local=" + segmentLocalIndex
                + "|content=" + normalizedContent;
    }

    /**
     * 从 Codex 原始历史消息中提取尽量稳定的 source id。
     * 该逻辑会同时兼容 event_msg、response_item message 与 tool call/result，优先复用 provider 已有 id，
     * 只有在 provider 未提供稳定标识时才回退到上层的分段局部顺序。
     *
     * @param sourceMsg 原始 Codex 历史消息
     * @param frontendMsg 已转换出的前端消息
     * @return 可作为 identity 优先锚点的 source id；缺失时返回空串
     */
    private static String extractCodexStableSourceId(JsonObject sourceMsg, JsonObject frontendMsg) {
        if (sourceMsg == null) {
            return "";
        }
        JsonObject payload = sourceMsg.has("payload") && sourceMsg.get("payload").isJsonObject()
                ? sourceMsg.getAsJsonObject("payload")
                : null;
        if (payload != null) {
            String payloadType = firstNonBlank(getStringProperty(payload, "type"));
            if ("function_call".equals(payloadType) || "custom_tool_call".equals(payloadType)) {
                return firstNonBlank(getStringProperty(payload, "call_id"), getStringProperty(payload, "id"));
            }
            if ("function_call_output".equals(payloadType)) {
                return firstNonBlank(
                        getStringProperty(payload, "call_id"),
                        getStringProperty(payload, "tool_use_id"),
                        getStringProperty(payload, "id")
                );
            }
            String rawId = firstNonBlank(
                    getStringProperty(payload, "uuid"),
                    getStringProperty(payload, "id"),
                    getStringProperty(payload, "message_id"),
                    getStringProperty(payload, "event_id"),
                    getStringProperty(payload, "call_id")
            );
            if (hasText(rawId)) {
                return rawId;
            }
        }
        return extractToolIdentityFromFrontendMessage(frontendMsg);
    }

    /**
     * 根据原始 Codex 历史消息判断 user 消息来源于哪种持久化形态。
     * 该信息仅用于 restore 去重，区分同一轮 user turn 的 response_item 与 event_msg 双记录。
     *
     * @param sourceMsg 原始 Codex 历史消息
     * @param role 前端消息角色
     * @return user 历史来源形态；非 user 或非已知形态时返回空串
     */
    private static String extractCodexUserHistoryShape(JsonObject sourceMsg, String role) {
        if (!"user".equals(role) || sourceMsg == null || !sourceMsg.has("type") || sourceMsg.get("type").isJsonNull()) {
            return "";
        }
        String sourceType = sourceMsg.get("type").getAsString();
        JsonObject payload = sourceMsg.has("payload") && sourceMsg.get("payload").isJsonObject()
                ? sourceMsg.getAsJsonObject("payload")
                : null;
        if (payload == null || !payload.has("type") || payload.get("type").isJsonNull()) {
            return "";
        }
        String payloadType = payload.get("type").getAsString();
        if ("event_msg".equals(sourceType) && "user_message".equals(payloadType)) {
            return USER_HISTORY_SHAPE_EVENT;
        }
        if ("response_item".equals(sourceType) && "message".equals(payloadType)) {
            String payloadRole = getStringProperty(payload, "role");
            return "user".equals(payloadRole) ? USER_HISTORY_SHAPE_RESPONSE : "";
        }
        return "";
    }

    /**
     * 从前端 raw block 中提取 tool_use / tool_result 的稳定摘要。
     * 当 provider 没有单独暴露 message id 时，这个摘要仍可用于识别同一条工具调用消息。
     *
     * @param frontendMsg 已转换好的前端消息
     * @return tool 语义摘要；没有工具块时返回空串
     */
    private static String extractToolIdentityFromFrontendMessage(JsonObject frontendMsg) {
        if (frontendMsg == null || !frontendMsg.has("raw") || !frontendMsg.get("raw").isJsonObject()) {
            return "";
        }
        JsonArray rawBlocks = extractFrontendRawBlocks(frontendMsg.getAsJsonObject("raw"));
        List<String> parts = new ArrayList<>();
        for (JsonElement rawBlockElement : rawBlocks) {
            if (!rawBlockElement.isJsonObject()) {
                continue;
            }
            JsonObject rawBlock = rawBlockElement.getAsJsonObject();
            String type = getStringProperty(rawBlock, "type");
            if ("tool_use".equals(type)) {
                parts.add("tool_use:" + firstNonBlank(getStringProperty(rawBlock, "id"), getStringProperty(rawBlock, "name")));
            } else if ("tool_result".equals(type)) {
                parts.add("tool_result:" + firstNonBlank(getStringProperty(rawBlock, "tool_use_id")));
            }
        }
        return parts.isEmpty() ? "" : String.join("|", parts);
    }

    /**
     * 在双录重复消息之间继承稳定 identity 与顺序元数据。
     * 这样 richer 版本替换 placeholder 版本时，不会把既有 logicalOrder、segmentLocalIndex 和 identity key 改乱。
     *
     * @param previous 已存在列表中的旧消息
     * @param replacement 本轮选中的更丰富消息
     * @return 继承稳定元数据后的替换消息
     */
    private static JsonObject inheritStableFrontendMetadata(JsonObject previous, JsonObject replacement) {
        if (previous == null || replacement == null) {
            return replacement;
        }
        if (previous.has("messageIdentity")) {
            replacement.add("messageIdentity", previous.get("messageIdentity").deepCopy());
        }
        copyNumericProperty(previous, replacement, "logicalOrder");
        copyNumericProperty(previous, replacement, "segmentIndex");
        copyNumericProperty(previous, replacement, "segmentLocalIndex");
        copyStringProperty(previous, replacement, "segmentSessionId");
        if (replacement.has("raw") && replacement.get("raw").isJsonObject()) {
            JsonObject raw = replacement.getAsJsonObject("raw");
            if (previous.has("messageIdentity")) {
                raw.add("messageIdentity", previous.get("messageIdentity").deepCopy());
            }
            copyNumericProperty(previous, raw, "logicalOrder");
            copyNumericProperty(previous, raw, "segmentIndex");
            copyNumericProperty(previous, raw, "segmentLocalIndex");
            copyStringProperty(previous, raw, "segmentSessionId");
        }
        return replacement;
    }

    /**
     * 读取顶层 `messageIdentity.key`。
     *
     * @param message 前端消息
     * @return identity key；缺失时返回空串
     */
    private static String extractMessageIdentityKey(JsonObject message) {
        if (message == null || !message.has("messageIdentity") || !message.get("messageIdentity").isJsonObject()) {
            return "";
        }
        return firstNonBlank(getStringProperty(message.getAsJsonObject("messageIdentity"), "key"));
    }

    /**
     * 复制整数字段，避免重复消息替换时丢失稳定顺序元数据。
     *
     * @param from 源对象
     * @param to 目标对象
     * @param propertyName 字段名
     */
    private static void copyNumericProperty(JsonObject from, JsonObject to, String propertyName) {
        if (from == null || to == null || propertyName == null || !from.has(propertyName) || from.get(propertyName).isJsonNull()) {
            return;
        }
        to.addProperty(propertyName, from.get(propertyName).getAsInt());
    }

    /**
     * 复制字符串字段，避免重复消息替换时丢失所属分段 sessionId。
     *
     * @param from 源对象
     * @param to 目标对象
     * @param propertyName 字段名
     */
    private static void copyStringProperty(JsonObject from, JsonObject to, String propertyName) {
        String value = getStringProperty(from, propertyName);
        if (!hasText(value) || to == null) {
            return;
        }
        to.addProperty(propertyName, value);
    }

    /**
     * 把单条前端消息的稳定语义追加到快照校验和。
     *
     * @param checksum 当前累计校验和
     * @param message 单条前端消息
     */
    private static void appendFrontendMessageSignature(CRC32 checksum, JsonObject message) {
        if (message == null) {
            updateSnapshotChecksum(checksum, "message:null");
            return;
        }

        updateSnapshotChecksum(checksum, "type:" + getStringProperty(message, "type"));
        updateSnapshotChecksum(checksum, "timestamp:" + getStringProperty(message, "timestamp"));
        updateSnapshotChecksum(checksum, "content:" + getStringProperty(message, "content"));

        JsonObject raw = message.has("raw") && message.get("raw").isJsonObject()
                ? message.getAsJsonObject("raw")
                : null;
        JsonArray rawBlocks = extractFrontendRawBlocks(raw);
        updateSnapshotChecksum(checksum, "rawBlockCount:" + rawBlocks.size());
        for (JsonElement blockElement : rawBlocks) {
            appendFrontendRawBlockSignature(checksum, blockElement);
        }
    }

    /**
     * 提取前端消息中的 raw block 数组。
     * 兼容 `raw.message.content` 与 `raw.content` 两种结构，避免签名逻辑与具体来源过度耦合。
     *
     * @param raw 前端消息 raw 对象
     * @return 可遍历的 raw block 数组；没有内容时返回空数组
     */
    private static JsonArray extractFrontendRawBlocks(JsonObject raw) {
        if (raw == null) {
            return new JsonArray();
        }
        if (raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject innerMessage = raw.getAsJsonObject("message");
            if (innerMessage.has("content") && innerMessage.get("content").isJsonArray()) {
                return innerMessage.getAsJsonArray("content");
            }
        }
        if (raw.has("content") && raw.get("content").isJsonArray()) {
            return raw.getAsJsonArray("content");
        }
        return new JsonArray();
    }

    /**
     * 把单个 raw block 的关键语义追加到快照校验和。
     * 对图片类 block 会显式纳入 `src/mediaType/alt` 或失效占位原因，确保图片恢复结果变化时签名同步变化。
     *
     * @param checksum 当前累计校验和
     * @param blockElement 单个 raw block
     */
    private static void appendFrontendRawBlockSignature(CRC32 checksum, JsonElement blockElement) {
        if (blockElement == null || !blockElement.isJsonObject()) {
            updateSnapshotChecksum(checksum, "block:" + String.valueOf(blockElement));
            return;
        }

        JsonObject block = blockElement.getAsJsonObject();
        String type = getStringProperty(block, "type");
        updateSnapshotChecksum(checksum, "blockType:" + type);
        if ("text".equals(type)) {
            updateSnapshotChecksum(checksum, "text:" + getStringProperty(block, "text"));
            return;
        }
        if ("image".equals(type)) {
            updateSnapshotChecksum(checksum, "imageSrc:" + getStringProperty(block, "src"));
            updateSnapshotChecksum(checksum, "imageMediaType:" + getStringProperty(block, "mediaType"));
            updateSnapshotChecksum(checksum, "imageAlt:" + getStringProperty(block, "alt"));
            return;
        }
        if ("image_missing".equals(type)) {
            updateSnapshotChecksum(checksum, "missingFileName:" + getStringProperty(block, "fileName"));
            updateSnapshotChecksum(checksum, "missingMediaType:" + getStringProperty(block, "mediaType"));
            updateSnapshotChecksum(checksum, "missingPath:" + getStringProperty(block, "originalPath"));
            updateSnapshotChecksum(checksum, "missingReason:" + getStringProperty(block, "reason"));
            return;
        }
        updateSnapshotChecksum(checksum, block.toString());
    }

    /**
     * 以 UTF-8 字节流方式累加快照校验和。
     * 这里统一把 `null` 转成显式字面量，避免不同空值分支生成不一致签名。
     *
     * @param checksum 当前累计校验和
     * @param value 待累加字符串
     */
    private static void updateSnapshotChecksum(CRC32 checksum, String value) {
        byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        checksum.update(bytes, 0, bytes.length);
        checksum.update('\n');
    }

    private static int getRawContentBlockCount(JsonObject message) {
        if (message == null || !message.has("raw") || !message.get("raw").isJsonObject()) {
            return 0;
        }

        JsonObject raw = message.getAsJsonObject("raw");
        if (raw.has("content") && raw.get("content").isJsonArray()) {
            return raw.getAsJsonArray("content").size();
        }
        if (raw.has("message") && raw.get("message").isJsonObject()) {
            JsonObject rawMessage = raw.getAsJsonObject("message");
            if (rawMessage.has("content") && rawMessage.get("content").isJsonArray()) {
                return rawMessage.getAsJsonArray("content").size();
            }
        }
        return 0;
    }

    /**
     * 计算用户消息的语义丰富度。
     * 对 Codex 双记录场景，显式声明过 `local_images` 的 event_msg 应优先于只剩占位文案的副本。
     *
     * @param message 候选用户消息
     * @return 语义丰富度分数，越高越应该被保留
     */
    private static int getUserMessageSemanticScore(JsonObject message) {
        if (message == null) {
            return 0;
        }
        int score = scoreVisibleUserContent(message);
        if (!message.has("raw") || !message.get("raw").isJsonObject()) {
            return score;
        }
        JsonObject raw = message.getAsJsonObject("raw");
        score += getRawContentBlockCount(message);
        if (raw.has("__hasDeclaredLocalImages") && !raw.get("__hasDeclaredLocalImages").isJsonNull()
                && raw.get("__hasDeclaredLocalImages").getAsBoolean()) {
            score += 1000;
        }
        if (raw.has("__declaredLocalImageCount") && !raw.get("__declaredLocalImageCount").isJsonNull()) {
            score += raw.get("__declaredLocalImageCount").getAsInt() * 100;
        }
        if (raw.has("__missingLocalImageCount") && !raw.get("__missingLocalImageCount").isJsonNull()) {
            score += raw.get("__missingLocalImageCount").getAsInt() * 10;
        }
        return score;
    }

    /**
     * 根据用户可见文本对候选 user 消息做基础排序。
     * 真实用户文本越长越优先；若仍残留 AGENTS、skills 或 continuation 痕迹，则施加强惩罚，
     * 用于双录冲突时尽量保留干净副本。
     *
     * @param message 候选用户消息
     * @return 基于可见文本的排序分数
     */
    private static int scoreVisibleUserContent(JsonObject message) {
        String content = normalizeDuplicateUserContent(getStringProperty(message, "content"));
        if (content.isEmpty()) {
            return 0;
        }
        int score = Math.min(content.length(), 200);
        return containsInternalPromptResidue(content) ? score - 10000 : score;
    }

    /**
     * 检测文本中是否仍保留明显的内部 prompt 残留特征。
     * 这里只匹配高置信固定前缀，避免把用户普通讨论误判成污染文本。
     *
     * @param content 用户可见文本
     * @return true 表示仍疑似存在内部注入残留
     */
    private static boolean containsInternalPromptResidue(String content) {
        return CodexMessageConverter.containsHighConfidenceInternalResidue(content);
    }

    /**
     * 判断非 user 可见消息是否仍明显带有内部 prompt 残留。
     * 这里专门作为历史恢复末端兜底，防止上游角色感知提取或 raw 块约束漏网时，污染消息继续混入前端快照。
     *
     * @param message 已转换完成的前端消息
     * @return true 表示该消息应在进入前端数组前直接丢弃
     */
    private static boolean shouldDropNonUserInternalResidueMessage(JsonObject message) {
        if (message == null || isUserMessage(message)) {
            return false;
        }
        return containsInternalPromptResidue(getStringProperty(message, "content"));
    }

    /**
     * 统计快照里仍残留的“相邻同内容 user 消息”数量，供 authoritative restore 诊断输出。
     *
     * @param frontendMessages 待检查的前端消息列表
     * @return 相邻重复 user 对的数量
     */
    public static int countAdjacentDuplicateVisibleUserMessages(List<JsonObject> frontendMessages) {
        if (frontendMessages == null || frontendMessages.size() < 2) {
            return 0;
        }
        int duplicateCount = 0;
        for (int i = 1; i < frontendMessages.size(); i++) {
            JsonObject previous = frontendMessages.get(i - 1);
            JsonObject current = frontendMessages.get(i);
            if (!isUserMessage(previous) || !isUserMessage(current)) {
                continue;
            }
            String previousContent = normalizeDuplicateUserContent(getStringProperty(previous, "content"));
            String currentContent = normalizeDuplicateUserContent(getStringProperty(current, "content"));
            if (!previousContent.isEmpty() && previousContent.equals(currentContent)) {
                duplicateCount++;
            }
        }
        return duplicateCount;
    }

    /**
     * 构建 user 消息的紧凑诊断摘要，便于在日志里快速观察 authoritative snapshot 的身份来源与文本指纹。
     *
     * @param frontendMessages 待检查的前端消息列表
     * @return 截断后的 user 消息摘要
     */
    public static String buildUserMessageTraceSummary(List<JsonObject> frontendMessages) {
        if (frontendMessages == null || frontendMessages.isEmpty()) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        int userCount = 0;
        for (JsonObject message : frontendMessages) {
            if (!isUserMessage(message)) {
                continue;
            }
            userCount++;
            if (parts.size() >= 8) {
                continue;
            }
            parts.add("{key=" + firstNonBlank(extractMessageIdentityKey(message))
                    + ",historySourceKind=" + firstNonBlank(extractUserHistorySourceKind(message))
                    + ",contentDigest=" + buildContentDigest(getStringProperty(message, "content"))
                    + "}");
        }
        if (userCount > parts.size()) {
            parts.add("...+" + (userCount - parts.size()) + " more");
        }
        return parts.toString();
    }

    /**
     * 统计快照中仍保留的非 user 内部残留消息数量。
     * 该计数专门服务 authoritative snapshot 诊断输出，用于验证“非 user 污染是否已在进入前端前被清零”。
     *
     * @param frontendMessages 当前快照中的前端消息列表
     * @return 命中高置信内部残留的非 user 消息数
     */
    public static int countInternalResidueVisibleMessages(List<JsonObject> frontendMessages) {
        if (frontendMessages == null || frontendMessages.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (JsonObject message : frontendMessages) {
            if (message == null || isUserMessage(message)) {
                continue;
            }
            if (containsInternalPromptResidue(getStringProperty(message, "content"))) {
                count++;
            }
        }
        return count;
    }

    /**
     * 构造非 user 消息的快照摘要，显式标记每条消息是否命中内部残留。
     * 这样日志里即使只剩 assistant/system/notification，也能快速定位是谁仍在把后台上下文推到前端。
     *
     * @param frontendMessages 当前快照中的前端消息列表
     * @return 截断后的非 user 消息摘要
     */
    public static String buildNonUserMessageTraceSummary(List<JsonObject> frontendMessages) {
        if (frontendMessages == null || frontendMessages.isEmpty()) {
            return "[]";
        }
        List<String> parts = new ArrayList<>();
        int nonUserCount = 0;
        for (JsonObject message : frontendMessages) {
            if (message == null || isUserMessage(message)) {
                continue;
            }
            nonUserCount++;
            if (parts.size() >= 8) {
                continue;
            }
            boolean internalResidue = containsInternalPromptResidue(getStringProperty(message, "content"));
            parts.add("{key=" + firstNonBlank(extractMessageIdentityKey(message))
                    + ",role=" + firstNonBlank(getStringProperty(message, "type"))
                    + ",contentDigest=" + buildContentDigest(getStringProperty(message, "content"))
                    + ",internalResidue=" + internalResidue
                    + "}");
        }
        if (nonUserCount > parts.size()) {
            parts.add("...+" + (nonUserCount - parts.size()) + " more");
        }
        return parts.toString();
    }

    /**
     * 为日志构建稳定的文本指纹，避免直接输出完整用户文本。
     *
     * @param content 待摘要的文本
     * @return `len-hexCrc32` 形式的内容摘要
     */
    private static String buildContentDigest(String content) {
        String normalized = normalizeDuplicateUserContent(content);
        CRC32 checksum = new CRC32();
        byte[] bytes = normalized.getBytes(StandardCharsets.UTF_8);
        checksum.update(bytes, 0, bytes.length);
        return normalized.length() + "-" + Long.toHexString(checksum.getValue());
    }

    /**
     * 将 Codex 历史消息恢复到后端 SessionState，保证历史加载后继续发送时，
     * 后端内存态与前端显示态使用同一份消息基线。
     */
    public static void restoreCodexMessagesToSessionState(SessionState state, JsonArray messages) {
        List<JsonObject> frontendMessages = convertCodexMessagesToFrontendBatch(messages);
        restoreCodexMessagesToSessionState(state, frontendMessages);
    }

    /**
     * 将已转换好的前端消息列表回写到 SessionState。
     * 该重载服务于逻辑会话聚合恢复场景，允许把跨分段边界系统消息一并写回会话内存态。
     *
     * @param state 当前会话状态
     * @param frontendMessages 已按前端协议转换完成的消息列表
     */
    public static void restoreCodexMessagesToSessionState(SessionState state, List<JsonObject> frontendMessages) {
        state.clearMessages();
        if (frontendMessages == null) {
            return;
        }
        for (JsonObject frontendMsg : frontendMessages) {
            ClaudeSession.Message restoredMessage = toSessionMessage(frontendMsg);
            if (restoredMessage != null) {
                state.addMessage(restoredMessage);
            }
        }
    }

    /**
     * 将前端统一消息结构恢复为会话内存消息结构。
     */
    private static ClaudeSession.Message toSessionMessage(JsonObject frontendMsg) {
        if (frontendMsg == null || !frontendMsg.has("type")) {
            return null;
        }

        String type = frontendMsg.get("type").getAsString();
        ClaudeSession.Message.Type messageType;
        switch (type) {
            case "user":
                messageType = ClaudeSession.Message.Type.USER;
                break;
            case "assistant":
                messageType = ClaudeSession.Message.Type.ASSISTANT;
                break;
            case "system":
                messageType = ClaudeSession.Message.Type.SYSTEM;
                break;
            case "error":
                messageType = ClaudeSession.Message.Type.ERROR;
                break;
            default:
                return null;
        }

        String content = frontendMsg.has("content") ? frontendMsg.get("content").getAsString() : "";
        JsonObject raw = frontendMsg.has("raw") && frontendMsg.get("raw").isJsonObject()
            ? frontendMsg.getAsJsonObject("raw")
            : null;
        return raw != null
            ? new ClaudeSession.Message(messageType, content, raw.deepCopy())
            : new ClaudeSession.Message(messageType, content);
    }

    /**
     * 将单条 Codex 历史消息转换为前端消息。
     * Handles both event_msg (user messages) and response_item (assistant/tool messages).
     */
    public static JsonObject convertCodexMessageToFrontend(JsonObject msg) {
        if (!msg.has("type")) {
            return null;
        }

        String type = msg.get("type").getAsString();
        JsonObject payload = msg.has("payload") && msg.get("payload").isJsonObject()
                ? msg.getAsJsonObject("payload") : null;
        if (payload == null) {
            return null;
        }

        String timestamp = msg.has("timestamp") ? msg.get("timestamp").getAsString() : null;

        // Handle event_msg containing user_message
        if ("event_msg".equals(type)) {
            return convertEventMsgToFrontend(payload, timestamp);
        }

        // Handle response_item (assistant messages, function calls, etc.)
        if ("response_item".equals(type)) {
            if (!payload.has("type")) {
                return null;
            }
            String payloadType = payload.get("type").getAsString();

            if ("message".equals(payloadType)) {
                return CodexMessageConverter.convertCodexMessageToFrontend(payload, timestamp);
            }
            if ("function_call".equals(payloadType)) {
                return CodexMessageConverter.convertFunctionCallToToolUse(payload, timestamp);
            }
            if ("function_call_output".equals(payloadType)) {
                return CodexMessageConverter.convertFunctionCallOutputToToolResult(payload, timestamp);
            }
            if ("custom_tool_call".equals(payloadType)) {
                return CodexMessageConverter.convertCustomToolCallToToolUse(payload, timestamp);
            }
        }

        return null;
    }

    /**
     * Convert event_msg with user_message payload to frontend format.
     */
    private static JsonObject convertEventMsgToFrontend(JsonObject payload, String timestamp) {
        if (!payload.has("type") || !"user_message".equals(payload.get("type").getAsString())) {
            return null;
        }
        boolean hasLocalImages = hasLocalImages(payload);
        if (!payload.has("message") || payload.get("message").isJsonNull()) {
            if (!hasLocalImages) {
                return null;
            }
        }

        String content = "";
        if (payload.has("message") && !payload.get("message").isJsonNull()) {
            content = CodexMessageConverter.stripSystemTags(payload.get("message").getAsString());
        }
        if ((content == null || content.isBlank()) && !hasLocalImages) {
            return null;
        }
        if (content == null) {
            content = "";
        }

        JsonObject frontendMsg = new JsonObject();
        frontendMsg.addProperty("type", "user");
        frontendMsg.addProperty("content", content);

        // Build raw structure compatible with MessageParser
        JsonObject rawObj = new JsonObject();
        JsonArray contentBlocks = buildUserMessageContentBlocks(payload, content);
        rawObj.add("content", contentBlocks);
        rawObj.addProperty("role", "user");
        rawObj.addProperty("__hasDeclaredLocalImages", hasLocalImages);
        rawObj.addProperty("__declaredLocalImageCount", getDeclaredLocalImageCount(payload));
        rawObj.addProperty("__missingLocalImageCount", countMissingLocalImages(contentBlocks));
        frontendMsg.add("raw", rawObj);

        if (timestamp != null) {
            frontendMsg.addProperty("timestamp", timestamp);
        }

        return frontendMsg;
    }

    private static JsonArray buildUserMessageContentBlocks(JsonObject payload, String content) {
        JsonArray contentBlocks = new JsonArray();
        appendLocalImageBlocks(payload, contentBlocks);

        if (content != null && !content.isBlank()) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", content);
            contentBlocks.add(textBlock);
        }
        return contentBlocks;
    }

    private static boolean hasLocalImages(JsonObject payload) {
        return payload.has("local_images")
            && payload.get("local_images").isJsonArray()
            && payload.getAsJsonArray("local_images").size() > 0;
    }

    private static int getDeclaredLocalImageCount(JsonObject payload) {
        return hasLocalImages(payload) ? payload.getAsJsonArray("local_images").size() : 0;
    }

    private static void appendLocalImageBlocks(JsonObject payload, JsonArray contentBlocks) {
        if (!payload.has("local_images") || !payload.get("local_images").isJsonArray()) {
            return;
        }

        JsonArray localImages = payload.getAsJsonArray("local_images");
        for (JsonElement imageElement : localImages) {
            if (!imageElement.isJsonPrimitive()) {
                continue;
            }
            String imagePath = imageElement.getAsString();
            JsonObject imageBlock = createLocalImageBlock(imagePath);
            if (imageBlock != null) {
                contentBlocks.add(imageBlock);
            }
        }
    }

    private static JsonObject createLocalImageBlock(String imagePath) {
        if (imagePath == null || imagePath.trim().isEmpty()) {
            return null;
        }

        try {
            Path path = Path.of(imagePath);
            if (!Files.isRegularFile(path)) {
                LOG.debug("[HistoryMessageInjector] Skip missing local image: " + imagePath);
                return createMissingLocalImageBlock(path, "cache_missing");
            }

            String mediaType = Files.probeContentType(path);
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = guessImageMediaType(path);
            }
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = "image/png";
            }

            String base64Data = Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            JsonObject imageBlock = new JsonObject();
            imageBlock.addProperty("type", "image");
            imageBlock.addProperty("src", "data:" + mediaType + ";base64," + base64Data);
            imageBlock.addProperty("mediaType", mediaType);
            imageBlock.addProperty("alt", path.getFileName() != null ? path.getFileName().toString() : "image");
            return imageBlock;
        } catch (Exception e) {
            LOG.warn("[HistoryMessageInjector] Failed to restore local image from Codex history: " + imagePath, e);
            return createMissingLocalImageBlock(Path.of(imagePath), "cache_unreadable");
        }
    }

    /**
     * 构造图片缓存失效占位块。
     * 即使真实图片字节已经不可恢复，也要保留图片的文件名、原路径和失效原因，供界面与复制链路兜底。
     *
     * @param path 原始图片路径
     * @param reason 失效原因
     * @return 结构化的图片失效占位块
     */
    private static JsonObject createMissingLocalImageBlock(Path path, String reason) {
        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image_missing");
        imageBlock.addProperty("fileName", path.getFileName() != null ? path.getFileName().toString() : "image");
        imageBlock.addProperty("mediaType", guessImageMediaType(path));
        imageBlock.addProperty("originalPath", path.toAbsolutePath().normalize().toString());
        imageBlock.addProperty("reason", reason);
        return imageBlock;
    }

    private static int countMissingLocalImages(JsonArray contentBlocks) {
        int missingCount = 0;
        for (JsonElement blockElement : contentBlocks) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            JsonObject block = blockElement.getAsJsonObject();
            if (block.has("type") && "image_missing".equals(block.get("type").getAsString())) {
                missingCount++;
            }
        }
        return missingCount;
    }

    private static String guessImageMediaType(Path path) {
        String fileName = path.getFileName() != null ? path.getFileName().toString().toLowerCase() : "";
        if (fileName.endsWith(".png")) {
            return "image/png";
        }
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (fileName.endsWith(".gif")) {
            return "image/gif";
        }
        if (fileName.endsWith(".webp")) {
            return "image/webp";
        }
        if (fileName.endsWith(".bmp")) {
            return "image/bmp";
        }
        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "image/png";
    }

    /**
     * 批量注入前端消息，复用 updateMessages 链路，避免长历史逐条追加导致最新消息显示滞后。
     */
    private void applyCodexSessionBinding(String threadIdToUse) {
        if (threadIdToUse == null || threadIdToUse.trim().isEmpty() || context.getSession() == null) {
            return;
        }
        try {
            CodexSessionBinding binding = context.getSettingsService().getCodexSessionBinding(threadIdToUse);
            if (binding == null || !binding.isMeaningful()) {
                LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.applyCodexSessionBinding skip threadId="
                        + threadIdToUse + ", reason=missing_binding");
                return;
            }
            context.getSession().setProvider("codex");
            if (binding.getModel() != null && !binding.getModel().trim().isEmpty()) {
                context.getSession().setModel(binding.getModel());
            }
            context.getSession().getState().setCodexSessionBinding(binding);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.applyCodexSessionBinding restored threadId="
                    + threadIdToUse + ", binding=" + describeBinding(binding));
            LOG.info("[HistoryHandler] Restored Codex session binding for threadId=" + threadIdToUse
                    + ", providerId=" + binding.getProviderId()
                    + ", model=" + binding.getModel());
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to restore Codex session binding: " + e.getMessage(), e);
        }
    }

    /**
     * 统一格式化历史恢复链路中的 Codex binding 诊断字段。
     * 这里只输出非敏感元数据，便于和 SessionLifecycleManager/SessionSendService 的 trace 日志串联比对。
     *
     * @param binding 当前历史会话恢复出的 Codex binding
     * @return 稳定的诊断文本；为空时返回 "(null)"
     */
    private String describeBinding(CodexSessionBinding binding) {
        if (binding == null) {
            return "(null)";
        }
        return "{providerId=" + binding.getProviderId()
                + ", model=" + binding.getModel()
                + ", requestMode=" + binding.getRequestMode()
                + ", baseUrlSource=" + binding.getBaseUrlSource()
                + ", effectiveConfigSource=" + binding.getEffectiveConfigSource()
                + "}";
    }

    private void injectBatchToFrontend(List<JsonObject> frontendMessages) {
        String messagesJson = new Gson().toJson(frontendMessages);
        String escapedMessagesJson = JsUtils.escapeJs(messagesJson);

        ApplicationManager.getApplication().invokeLater(() -> {
            String jsCode = "if (window.clearMessages) { window.clearMessages(); } " +
                                    "if (window.updateMessages) { window.updateMessages('" + escapedMessagesJson + "'); } " +
                                    "if (window.historyLoadComplete) { window.historyLoadComplete(); }";
            context.executeJavaScriptOnEDT(jsCode);
        });
    }

    /**
     * 按逻辑会话恢复计划加载 Codex 历史。
     * 与旧的单物理 sessionId 恢复相比，该入口会优先恢复最新活动分段，并顺序拼接整条逻辑会话的所有分段消息。
     *
     * @param restorePlan 已解析的 Codex 恢复计划
     */
    private void loadCodexSession(CodexRestorePlan restorePlan) {
        if (restorePlan == null || !hasText(restorePlan.getRequestedSessionId())) {
            LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.loadCodexSession restorePlan invalid, fallback=single_session");
            loadCodexSession("");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.loadCodexSession start restorePlan="
                        + describeRestorePlanForTrace(restorePlan));
                CodexSegmentBundle segmentBundle = loadCodexSegmentBundle(restorePlan);
                JsonArray messages = flattenSegmentMessages(segmentBundle.getSegmentMessagesList());
                String[] sessionMeta = extractSessionMeta(segmentBundle, restorePlan.getActiveSegmentSessionId());
                String threadIdToUse = firstNonBlank(
                        restorePlan.getActiveSegmentSessionId(),
                        sessionMeta[0],
                        restorePlan.getRequestedSessionId()
                );
                String cwd = sessionMeta[1];

                context.getSession().setSessionInfo(threadIdToUse, cwd);
                applyCodexSessionBinding(threadIdToUse);
                applyCodexContinuationState(context.getSession().getState(), restorePlan);
                List<JsonObject> frontendMessages = convertCodexMessagesToFrontendBatch(
                        segmentBundle.getSegmentMessagesList(),
                        segmentBundle.getSegmentRecords()
                );
                restoreCodexMessagesToSessionState(context.getSession().getState(), frontendMessages);
                LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.loadCodexSession restored threadId="
                        + threadIdToUse
                        + ", cwd=" + firstNonBlank(cwd)
                        + ", messageCount=" + messages.size()
                        + ", restorePlan=" + describeRestorePlanForTrace(restorePlan));

                injectBatchToFrontend(frontendMessages);
            } catch (Exception e) {
                LOG.warn("[HistoryHandler] Failed logical Codex restore, fallback to requested session: "
                        + restorePlan.getRequestedSessionId(), e);
                LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " HistoryMessageInjector.loadCodexSession fallback requestedSessionId="
                        + restorePlan.getRequestedSessionId()
                        + ", error=" + e.getMessage()
                        + ", restorePlan=" + describeRestorePlanForTrace(restorePlan), e);
                loadCodexSession(restorePlan.getRequestedSessionId());
            }
        });
    }

    /**
     * 解析前端传入的历史恢复 payload。
     * 兼容旧的纯 sessionId 字符串与新的逻辑会话 JSON 结构。
     *
     * @param payloadString 前端传入的原始 payload
     * @param currentProvider 当前 provider
     * @return 归一化后的恢复请求
     */
    static SessionLoadRequest parseSessionLoadRequest(String payloadString, String currentProvider) {
        String provider = currentProvider;
        String runtimeFamily = null;
        String restoreSource = "history_switch";
        String transitionToken = null;
        String resolvedSessionId = payloadString;
        String logicalConversationId = null;
        String activeSegmentSessionId = null;

        try {
            JsonObject payload = new Gson().fromJson(payloadString, JsonObject.class);
            if (payload != null) {
                if (payload.has("sessionId") && !payload.get("sessionId").isJsonNull()) {
                    resolvedSessionId = payload.get("sessionId").getAsString();
                }
                if (payload.has("logicalConversationId") && !payload.get("logicalConversationId").isJsonNull()) {
                    logicalConversationId = payload.get("logicalConversationId").getAsString();
                }
                if (payload.has("activeSegmentSessionId") && !payload.get("activeSegmentSessionId").isJsonNull()) {
                    activeSegmentSessionId = payload.get("activeSegmentSessionId").getAsString();
                }
                if (payload.has("provider") && !payload.get("provider").isJsonNull()) {
                    provider = payload.get("provider").getAsString();
                }
                if (payload.has("runtimeFamily") && !payload.get("runtimeFamily").isJsonNull()) {
                    runtimeFamily = payload.get("runtimeFamily").getAsString();
                }
                if (payload.has("restoreSource") && !payload.get("restoreSource").isJsonNull()) {
                    restoreSource = payload.get("restoreSource").getAsString();
                }
                if (payload.has("transitionToken") && !payload.get("transitionToken").isJsonNull()) {
                    transitionToken = payload.get("transitionToken").getAsString();
                }
            }
        } catch (Exception ignored) {
            // 兼容旧 payload：直接把字符串当作物理 sessionId。
        }

        return new SessionLoadRequest(
                firstNonBlank(resolvedSessionId),
                firstNonBlank(logicalConversationId),
                firstNonBlank(activeSegmentSessionId),
                firstNonBlank(provider, currentProvider),
                firstNonBlank(runtimeFamily),
                firstNonBlank(restoreSource, "history_switch"),
                firstNonBlank(transitionToken)
        );
    }

    /**
     * 基于逻辑会话元数据构造 Codex 恢复计划。
     * 若 payload 仍是旧的物理 sessionId，也会先回溯到所属逻辑会话，再恢复最新活动分段。
     *
     * @param request 恢复请求
     * @param settingsService 元数据读取服务
     * @return 供历史恢复与继续发送共用的恢复计划
     */
    static CodexRestorePlan buildCodexRestorePlan(
            SessionLoadRequest request,
            CodemossSettingsService settingsService
    ) {
        if (request == null) {
            return CodexRestorePlan.forSingleSession("");
        }

        String requestedSessionId = firstNonBlank(request.getRequestedSessionId());
        String logicalConversationId = firstNonBlank(request.getLogicalConversationId());
        String activeSegmentSessionId = firstNonBlank(request.getActiveSegmentSessionId());
        String parentSegmentSessionId = "";
        List<String> segmentSessionIds = new ArrayList<>();

        try {
            if (!hasText(logicalConversationId) && hasText(requestedSessionId) && settingsService != null) {
                ConversationSegmentRecord requestedSegment = settingsService.getConversationSegmentRecord(requestedSessionId);
                if (requestedSegment != null && requestedSegment.isMeaningful()) {
                    logicalConversationId = firstNonBlank(requestedSegment.getLogicalConversationId());
                }
            }

            if (hasText(logicalConversationId) && settingsService != null) {
                List<ConversationSegmentRecord> segments = new ArrayList<>(settingsService.listConversationSegments(logicalConversationId));
                segments.sort(Comparator.comparingInt(ConversationSegmentRecord::getSegmentIndex));
                for (ConversationSegmentRecord segment : segments) {
                    if (segment != null && hasText(segment.getSessionId())) {
                        segmentSessionIds.add(segment.getSessionId());
                    }
                }

                LogicalConversationRecord logicalRecord = settingsService.getLogicalConversationRecord(logicalConversationId);
                if (!hasText(activeSegmentSessionId) && logicalRecord != null && logicalRecord.isMeaningful()) {
                    activeSegmentSessionId = firstNonBlank(logicalRecord.getLatestSessionId());
                }
                if (!hasText(activeSegmentSessionId) && !segmentSessionIds.isEmpty()) {
                    activeSegmentSessionId = segmentSessionIds.get(segmentSessionIds.size() - 1);
                }
                if (hasText(activeSegmentSessionId)) {
                    ConversationSegmentRecord activeSegment = settingsService.getConversationSegmentRecord(activeSegmentSessionId);
                    if (activeSegment != null && activeSegment.isMeaningful()) {
                        parentSegmentSessionId = firstNonBlank(activeSegment.getParentSessionId());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warn("[HistoryHandler] Failed to build Codex restore plan: " + e.getMessage(), e);
        }

        if (segmentSessionIds.isEmpty() && hasText(requestedSessionId)) {
            segmentSessionIds.add(requestedSessionId);
        }
        if (!hasText(activeSegmentSessionId)) {
            activeSegmentSessionId = firstNonBlank(requestedSessionId);
        }
        if (hasText(activeSegmentSessionId) && !segmentSessionIds.contains(activeSegmentSessionId)) {
            segmentSessionIds.add(activeSegmentSessionId);
        }

        return new CodexRestorePlan(
                requestedSessionId,
                logicalConversationId,
                activeSegmentSessionId,
                parentSegmentSessionId,
                segmentSessionIds
        );
    }

    /**
     * 按逻辑会话维度构建 continued/runtime restore 共用的前端聚合消息批次。
     * 该入口复用历史恢复链路的“恢复计划 -> 分段读取 -> 边界提示注入”语义，
     * 避免运行时 continued 再复制一份独立的拼装逻辑，导致展示语义继续分叉。
     *
     * @param requestedSessionId 当前恢复入口感知到的 sessionId，通常为最新活动分段
     * @param logicalConversationId 所属逻辑会话 id
     * @param activeSegmentSessionId 当前活动分段 sessionId
     * @param settingsService 分段元数据读取服务
     * @param codexReader Codex 历史读取器
     * @return 可直接回刷到前端的聚合消息列表
     */
    public static List<JsonObject> buildCodexLogicalConversationFrontendBatch(
            String requestedSessionId,
            String logicalConversationId,
            String activeSegmentSessionId,
            CodemossSettingsService settingsService,
            CodexHistoryReader codexReader
    ) {
        if (settingsService == null || codexReader == null) {
            return new ArrayList<>();
        }
        SessionLoadRequest request = new SessionLoadRequest(
                firstNonBlank(requestedSessionId),
                firstNonBlank(logicalConversationId),
                firstNonBlank(activeSegmentSessionId),
                "",
                "",
                "runtime_continue",
                ""
        );
        CodexRestorePlan restorePlan = buildCodexRestorePlan(request, settingsService);
        CodexSegmentBundle segmentBundle = loadCodexSegmentBundle(restorePlan, settingsService, codexReader);
        return convertCodexMessagesToFrontendBatch(
                segmentBundle.getSegmentMessagesList(),
                segmentBundle.getSegmentRecords()
        );
    }

    /**
     * 统一输出逻辑会话恢复计划的 trace 摘要。
     * 该摘要只保留用于串联日志的稳定字段，避免把整段消息或大对象直接写入日志。
     *
     * @param restorePlan 当前恢复计划
     * @return 适合直接输出到 trace 日志中的固定摘要
     */
    static String describeRestorePlanForTrace(CodexRestorePlan restorePlan) {
        if (restorePlan == null) {
            return "(null)";
        }
        return "{requestedSessionId=" + restorePlan.getRequestedSessionId()
                + ", logicalConversationId=" + restorePlan.getLogicalConversationId()
                + ", activeSegmentSessionId=" + restorePlan.getActiveSegmentSessionId()
                + ", parentSegmentSessionId=" + restorePlan.getParentSegmentSessionId()
                + ", segmentCount=" + restorePlan.getSegmentSessionIds().size()
                + ", segmentSessionIds=" + restorePlan.getSegmentSessionIds()
                + "}";
    }

    /**
     * 顺序读取恢复计划中的所有物理分段消息，并拼接成单个消息数组。
     *
     * @param restorePlan 当前逻辑会话恢复计划
     * @return 拼接后的 Codex 原始历史消息
     */
    private CodexSegmentBundle loadCodexSegmentBundle(CodexRestorePlan restorePlan) {
        return loadCodexSegmentBundle(restorePlan, context.getSettingsService(), new CodexHistoryReader());
    }

    /**
     * 顺序读取恢复计划中的全部物理分段消息，并携带分段元数据一并返回。
     * 该静态入口同时服务于历史恢复与运行时 continued 聚合回刷，避免两条链路再维护两套读取顺序。
     *
     * @param restorePlan 当前逻辑会话恢复计划
     * @param settingsService 分段元数据读取服务
     * @param codexReader Codex 历史读取器
     * @return 包含分段消息与分段记录的聚合载荷
     */
    private static CodexSegmentBundle loadCodexSegmentBundle(
            CodexRestorePlan restorePlan,
            CodemossSettingsService settingsService,
            CodexHistoryReader codexReader
    ) {
        List<JsonArray> segmentMessagesList = new ArrayList<>();
        List<ConversationSegmentRecord> segmentRecords = new ArrayList<>();
        for (String segmentSessionId : restorePlan.getSegmentSessionIds()) {
            if (!hasText(segmentSessionId)) {
                continue;
            }
            String messagesJson = codexReader.getSessionMessagesAsJson(segmentSessionId);
            JsonArray segmentMessages = JsonParser.parseString(messagesJson).getAsJsonArray();
            segmentMessagesList.add(segmentMessages);
            segmentRecords.add(resolveSegmentRecord(settingsService, segmentSessionId));
        }
        return new CodexSegmentBundle(
                segmentMessagesList,
                segmentRecords,
                restorePlan.getLogicalConversationId(),
                restorePlan.getActiveSegmentSessionId(),
                restorePlan.getParentSegmentSessionId()
        );
    }

    /**
     * 将分段消息列表按原始顺序拍平，供日志统计和旧的 state 恢复逻辑复用。
     *
     * @param segmentMessagesList 分段消息列表
     * @return 拍平后的原始消息数组
     */
    private JsonArray flattenSegmentMessages(List<JsonArray> segmentMessagesList) {
        JsonArray combinedMessages = new JsonArray();
        if (segmentMessagesList == null) {
            return combinedMessages;
        }
        for (JsonArray segmentMessages : segmentMessagesList) {
            if (segmentMessages == null) {
                continue;
            }
            for (JsonElement message : segmentMessages) {
                combinedMessages.add(message);
            }
        }
        return combinedMessages;
    }

    /**
     * 读取指定物理分段对应的元数据记录。
     * 若配置里尚未找到记录，则返回空语义记录，避免恢复流程因单条缺失元数据而中断。
     *
     * @param sessionId 物理分段 sessionId
     * @return 对应分段元数据；缺失时返回空语义记录
     */
    private ConversationSegmentRecord resolveSegmentRecord(String sessionId) {
        return resolveSegmentRecord(context.getSettingsService(), sessionId);
    }

    /**
     * 读取指定物理分段对应的元数据记录。
     * 静态版本用于运行时 continued 聚合回刷，实例版本继续服务于历史恢复链路。
     *
     * @param settingsService 分段元数据读取服务
     * @param sessionId 物理分段 sessionId
     * @return 对应分段元数据；缺失时返回空语义记录
     */
    private static ConversationSegmentRecord resolveSegmentRecord(
            CodemossSettingsService settingsService,
            String sessionId
    ) {
        try {
            ConversationSegmentRecord record = settingsService != null
                    ? settingsService.getConversationSegmentRecord(sessionId)
                    : null;
            if (record != null) {
                return record;
            }
        } catch (Exception e) {
            LOG.debug(CODEX_RUNTIME_TRACE_PREFIX + " resolveSegmentRecord failed: " + e.getMessage());
        }
        return new ConversationSegmentRecord(firstNonBlank(sessionId), "", "", 0, "", "", "", "", "", "", 0L);
    }

    /**
     * 把逻辑会话 continuation 运行态写回当前 SessionState。
     * 历史恢复后继续发送仍然依赖这些字段，否则会退回旧分段或旧供应商绑定。
     *
     * @param state 当前会话状态
     * @param restorePlan 已完成解析的恢复计划
     */
    protected static void applyCodexContinuationState(SessionState state, CodexRestorePlan restorePlan) {
        if (state == null || restorePlan == null) {
            return;
        }
        state.setLogicalConversationId(emptyToNull(restorePlan.getLogicalConversationId()));
        state.setActiveSegmentSessionId(emptyToNull(restorePlan.getActiveSegmentSessionId()));
        state.setParentSegmentSessionId(emptyToNull(restorePlan.getParentSegmentSessionId()));
        state.setContinuationPending(false);
        state.setContinuationSourceSessionId(null);
    }

    /**
     * 按活动分段优先级提取恢复所需的 session_meta。
     * 先在活动分段中查找 threadId/cwd，再按分段顺序向前回退，避免跨分段恢复时误用首段 cwd。
     *
     * @param segmentBundle 分段消息与元数据集合
     * @param preferredSegmentSessionId 期望优先读取 meta 的活动分段 sessionId
     * @return String[2]: [0]=actualThreadId, [1]=cwd
     */
    static String[] extractSessionMeta(CodexSegmentBundle segmentBundle, String preferredSegmentSessionId) {
        if (segmentBundle == null || segmentBundle.getSegmentMessagesList().isEmpty()) {
            return new String[]{null, null};
        }

        int preferredIndex = -1;
        if (hasText(preferredSegmentSessionId)) {
            for (int i = 0; i < segmentBundle.getSegmentRecords().size(); i++) {
                ConversationSegmentRecord record = segmentBundle.getSegmentRecords().get(i);
                if (record != null && preferredSegmentSessionId.equals(firstNonBlank(record.getSessionId()))) {
                    preferredIndex = i;
                    break;
                }
            }
        }

        if (preferredIndex >= 0) {
            String[] preferredMeta = extractSessionMeta(segmentBundle.getSegmentMessagesList().get(preferredIndex));
            if (preferredMeta[0] != null || preferredMeta[1] != null) {
                return preferredMeta;
            }
        }

        for (JsonArray segmentMessages : segmentBundle.getSegmentMessagesList()) {
            String[] meta = extractSessionMeta(segmentMessages);
            if (meta[0] != null || meta[1] != null) {
                return meta;
            }
        }
        return new String[]{null, null};
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String emptyToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 历史恢复载荷的归一化结果。
     * 避免后续链路重复解析前端传入的 JSON 字符串。
     */
    static final class SessionLoadRequest {
        private final String requestedSessionId;
        private final String logicalConversationId;
        private final String activeSegmentSessionId;
        private final String provider;
        private final String runtimeFamily;
        private final String restoreSource;
        private final String transitionToken;

        /**
         * 创建归一化后的历史恢复请求。
         *
         * @param requestedSessionId 前端显式传入的 sessionId
         * @param logicalConversationId 逻辑会话 id
         * @param activeSegmentSessionId 活动分段 sessionId
         * @param provider 历史项 provider
         * @param runtimeFamily 历史项运行时家族
         * @param restoreSource 恢复来源标记
         * @param transitionToken 恢复链路 token
         */
        SessionLoadRequest(
                String requestedSessionId,
                String logicalConversationId,
                String activeSegmentSessionId,
                String provider,
                String runtimeFamily,
                String restoreSource,
                String transitionToken
        ) {
            this.requestedSessionId = firstNonBlank(requestedSessionId);
            this.logicalConversationId = firstNonBlank(logicalConversationId);
            this.activeSegmentSessionId = firstNonBlank(activeSegmentSessionId);
            this.provider = firstNonBlank(provider);
            this.runtimeFamily = firstNonBlank(runtimeFamily);
            this.restoreSource = firstNonBlank(restoreSource, "history_switch");
            this.transitionToken = firstNonBlank(transitionToken);
        }

        public String getRequestedSessionId() {
            return requestedSessionId;
        }

        public String getLogicalConversationId() {
            return logicalConversationId;
        }

        public String getActiveSegmentSessionId() {
            return activeSegmentSessionId;
        }

        public String getProvider() {
            return provider;
        }

        public String getRuntimeFamily() {
            return runtimeFamily;
        }

        public String getRestoreSource() {
            return restoreSource;
        }

        public String getTransitionToken() {
            return transitionToken;
        }

        /**
         * 输出前端历史恢复请求在后端归一化后的稳定摘要。
         *
         * @return 适合运行时 trace 的请求摘要
         */
        public String toTraceString() {
            return "{requestedSessionId=" + requestedSessionId
                    + ", logicalConversationId=" + logicalConversationId
                    + ", activeSegmentSessionId=" + activeSegmentSessionId
                    + ", provider=" + provider
                    + ", runtimeFamily=" + runtimeFamily
                    + ", restoreSource=" + restoreSource
                    + ", transitionToken=" + transitionToken
                    + "}";
        }
    }

    /**
     * Codex 逻辑会话恢复计划。
     * 该对象统一描述本次恢复应使用的最新活动分段、父分段以及需要拼接的物理分段列表。
     */
    static final class CodexRestorePlan {
        private final String requestedSessionId;
        private final String logicalConversationId;
        private final String activeSegmentSessionId;
        private final String parentSegmentSessionId;
        private final List<String> segmentSessionIds;

        /**
         * 创建 Codex 恢复计划。
         *
         * @param requestedSessionId 恢复入口传入的 sessionId
         * @param logicalConversationId 逻辑会话 id
         * @param activeSegmentSessionId 最新活动分段 sessionId
         * @param parentSegmentSessionId 最新活动分段的父分段 sessionId
         * @param segmentSessionIds 需要顺序恢复的全部物理分段 sessionId
         */
        CodexRestorePlan(
                String requestedSessionId,
                String logicalConversationId,
                String activeSegmentSessionId,
                String parentSegmentSessionId,
                List<String> segmentSessionIds
        ) {
            this.requestedSessionId = firstNonBlank(requestedSessionId);
            this.logicalConversationId = firstNonBlank(logicalConversationId);
            this.activeSegmentSessionId = firstNonBlank(activeSegmentSessionId);
            this.parentSegmentSessionId = firstNonBlank(parentSegmentSessionId);
            this.segmentSessionIds = Collections.unmodifiableList(new ArrayList<>(segmentSessionIds));
        }

        static CodexRestorePlan forSingleSession(String sessionId) {
            List<String> segmentSessionIds = new ArrayList<>();
            if (hasText(sessionId)) {
                segmentSessionIds.add(sessionId.trim());
            }
            return new CodexRestorePlan(sessionId, "", sessionId, "", segmentSessionIds);
        }

        public String getRequestedSessionId() {
            return requestedSessionId;
        }

        public String getLogicalConversationId() {
            return logicalConversationId;
        }

        public String getActiveSegmentSessionId() {
            return activeSegmentSessionId;
        }

        public String getParentSegmentSessionId() {
            return parentSegmentSessionId;
        }

        public List<String> getSegmentSessionIds() {
            return segmentSessionIds;
        }
    }

    /**
     * Codex 逻辑会话的分段消息与元数据集合。
     * 该对象保留“按分段组织”的历史结构，供恢复链路同时完成活动分段 meta 选择与边界系统消息注入。
     */
    static final class CodexSegmentBundle {
        private final List<JsonArray> segmentMessagesList;
        private final List<ConversationSegmentRecord> segmentRecords;
        private final String logicalConversationId;
        private final String activeSegmentSessionId;
        private final String parentSegmentSessionId;

        /**
         * 创建分段消息集合。
         *
         * @param segmentMessagesList 按分段顺序排列的消息数组
         * @param segmentRecords 与消息数组一一对应的分段元数据
         * @param logicalConversationId 所属逻辑会话 id
         * @param activeSegmentSessionId 当前活动分段 sessionId
         * @param parentSegmentSessionId 当前活动分段父分段 sessionId
         */
        CodexSegmentBundle(
                List<JsonArray> segmentMessagesList,
                List<ConversationSegmentRecord> segmentRecords,
                String logicalConversationId,
                String activeSegmentSessionId,
                String parentSegmentSessionId
        ) {
            this.segmentMessagesList = Collections.unmodifiableList(new ArrayList<>(segmentMessagesList));
            this.segmentRecords = Collections.unmodifiableList(new ArrayList<>(segmentRecords));
            this.logicalConversationId = firstNonBlank(logicalConversationId);
            this.activeSegmentSessionId = firstNonBlank(activeSegmentSessionId);
            this.parentSegmentSessionId = firstNonBlank(parentSegmentSessionId);
        }

        public List<JsonArray> getSegmentMessagesList() {
            return segmentMessagesList;
        }

        public List<ConversationSegmentRecord> getSegmentRecords() {
            return segmentRecords;
        }

        public String getLogicalConversationId() {
            return logicalConversationId;
        }

        public String getActiveSegmentSessionId() {
            return activeSegmentSessionId;
        }

        public String getParentSegmentSessionId() {
            return parentSegmentSessionId;
        }
    }
}
