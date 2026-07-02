package com.github.claudecodegui.session;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfileResolver;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Owns message-send orchestration while ClaudeSession remains the public session facade.
 */
public class SessionSendService {

    private static final Logger LOG = Logger.getInstance(SessionSendService.class);
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";

    private final Project project;
    private final SessionState state;
    private final SessionCallbackFacade callbackFacade;
    private final MessageParser messageParser;
    private final MessageMerger messageMerger;
    private final Gson gson;
    private final ClaudeSDKBridge claudeSDKBridge;
    private final CodexSDKBridge codexSDKBridge;
    private final SessionContextService contextService;

    public SessionSendService(
            Project project,
            SessionState state,
            SessionCallbackFacade callbackFacade,
            MessageParser messageParser,
            MessageMerger messageMerger,
            Gson gson,
            ClaudeSDKBridge claudeSDKBridge,
            CodexSDKBridge codexSDKBridge,
            SessionContextService contextService
    ) {
        this.project = project;
        this.state = state;
        this.callbackFacade = callbackFacade;
        this.messageParser = messageParser;
        this.messageMerger = messageMerger;
        this.gson = gson;
        this.claudeSDKBridge = claudeSDKBridge;
        this.codexSDKBridge = codexSDKBridge;
        this.contextService = contextService;
    }

    public void prepareContextCollector(EditorContextCollector contextCollector) {
        contextCollector.setPsiContextEnabled(state.isPsiContextEnabled());
        contextCollector.setAutoOpenFileEnabled(readAutoOpenFileEnabled());
    }

    public void updateSessionStateForSend(ClaudeSession.Message userMessage, String normalizedInput) {
        state.addMessage(userMessage);
        callbackFacade.notifyMessageUpdate(state.getMessages());

        if (state.getSummary() == null) {
            String baseSummary = (userMessage.content != null && !userMessage.content.isEmpty())
                    ? userMessage.content
                    : normalizedInput;
            String newSummary = baseSummary.length() > 45 ? baseSummary.substring(0, 45) + "..." : baseSummary;
            state.setSummary(newSummary);
            callbackFacade.notifySummaryReceived(newSummary);
        }

        state.updateLastModifiedTime();
        state.setError(null);
        state.setBusy(true);
        state.setLoading(true);
        state.clearLastRecoveryMetadata();
        ClaudeNotifier.setWaiting(project);
        callbackFacade.notifyStateChange(state.isBusy(), state.isLoading(), state.getError());
    }

    public CompletableFuture<Void> sendMessageToProvider(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String externalAgentPrompt,
            List<String> fileTagPaths,
            String requestedPermissionMode
    ) {
        String agentPrompt = externalAgentPrompt;
        if (agentPrompt == null) {
            agentPrompt = getAgentPrompt();
            LOG.info("[Agent] Using agent from global setting (fallback)");
        } else {
            LOG.info("[Agent] Using agent from message (per-tab selection)");
        }

        String currentProvider = state.getProvider();
        String sessionModeBeforeSend = state.getPermissionMode();
        String normalizedRequestedMode = normalizeRequestedPermissionMode(requestedPermissionMode);
        String effectivePermissionMode = resolveEffectivePermissionMode(
                currentProvider,
                normalizedRequestedMode,
                sessionModeBeforeSend
        );

        LOG.info(
                "[ModeSync][Backend] provider=" + currentProvider
                        + ", requested=" + (normalizedRequestedMode != null ? normalizedRequestedMode : "(none)")
                        + ", session=" + (sessionModeBeforeSend != null ? sessionModeBeforeSend : "(none)")
                        + ", effective=" + effectivePermissionMode
        );

        if ("codex".equals(currentProvider)) {
            return sendToCodex(
                    channelId,
                    input,
                    attachments,
                    openedFilesJson,
                    agentPrompt,
                    fileTagPaths,
                    effectivePermissionMode
            );
        }

        return sendToClaude(channelId, input, attachments, openedFilesJson, agentPrompt, effectivePermissionMode);
    }

    public static String normalizeRequestedPermissionMode(String mode) {
        if (mode == null) {
            return null;
        }
        String trimmed = mode.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (SessionState.isValidPermissionMode(trimmed)) {
            return trimmed;
        }
        LOG.warn("[ModeSync][Backend] Invalid requested permissionMode ignored: " + mode);
        return null;
    }

    public static String resolveEffectivePermissionMode(String provider, String requestedMode, String sessionMode) {
        String resolvedMode = requestedMode;
        if (resolvedMode == null) {
            resolvedMode = normalizeRequestedPermissionMode(sessionMode);
        }
        if (resolvedMode == null) {
            resolvedMode = "default";
        }

        if ("codex".equals(provider) && "plan".equals(resolvedMode)) {
            return "default";
        }
        return resolvedMode;
    }

    public static String getCodexRuntimeAccessError(String accessMode) {
        if (CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED.equals(accessMode)
                || CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN.equals(accessMode)) {
            return null;
        }
        return ClaudeCodeGuiBundle.message("error.codexLocalAccessNotAuthorized");
    }

    /**
     * 为 continued segment 的首条 Codex 输入构造上下文延续前缀。
     * 当前实现只注入最小且稳定的会话延续信息，避免依赖尚未建立的新 sessionId，也避免把整段历史重复拼接进 prompt。
     *
     * @param state 当前会话状态
     * @return 需要前置到用户输入前的 carryover 文本；若当前并非 continued segment 首发，则返回空串
     */
    public static String buildContinuationCarryoverPrefix(SessionState state) {
        if (state == null || !state.isContinuationPending()) {
            return "";
        }

        String logicalConversationId = safeTrim(state.getLogicalConversationId());
        String sourceSessionId = safeTrim(state.getContinuationSourceSessionId());
        String summary = safeTrim(state.getSummary());
        if (logicalConversationId.isEmpty() && sourceSessionId.isEmpty() && summary.isEmpty()) {
            return "";
        }

        StringBuilder prefix = new StringBuilder();
        prefix.append("## Conversation Continuation\n")
                .append("You are continuing an existing conversation in a new runtime segment.\n");
        if (!logicalConversationId.isEmpty()) {
            prefix.append("Logical conversation id: ").append(logicalConversationId).append("\n");
        }
        if (!sourceSessionId.isEmpty()) {
            prefix.append("Previous segment session id: ").append(sourceSessionId).append("\n");
        }
        if (!summary.isEmpty()) {
            prefix.append("Previous conversation summary: ").append(summary).append("\n");
        }
        prefix.append("Preserve the user's intent and continue from that context unless the latest request overrides it.\n\n");
        return prefix.toString();
    }

    /**
     * 保持旧方法名兼容 Codex 专用调用点。
     *
     * @param state 当前会话状态
     * @return 需要前置到输入前的 carryover 文本
     */
    public static String buildCodexContinuationCarryoverPrefix(SessionState state) {
        return buildContinuationCarryoverPrefix(state);
    }

    /**
     * 统一格式化 Codex session binding 的诊断信息。
     * 该方法专门服务于运行时跟踪日志与测试断言，确保不同链路输出的字段名和顺序一致，
     * 便于在 debug 包日志里串联“选择 provider/model -> 新建 session -> 首次发送 -> provider 命中结果”的完整路径。
     *
     * @param binding 当前 session 绑定的 Codex provider/model 元数据，允许为 null
     * @return 可直接输出到日志中的稳定字符串；当 binding 为空时返回 "(null)"
     */
    public static String describeCodexBindingForTrace(CodexSessionBinding binding) {
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

    /**
     * 归一化判定当前运行时 profile 的来源，用于日志与测试共享同一套语义。
     * 当 session binding 中存在有效 providerId，且解析后的 profile 也命中同一 provider 时，
     * 视为“命中 session_binding”；其余场景统一视为“回退 active_provider”。
     *
     * @param binding 当前 session 挂载的 Codex binding
     * @param runtimeProfile 本次发送实际解析出的运行时 profile
     * @return `session_binding` 或 `active_provider_fallback`
     */
    public static String determineCodexRuntimeProfileTraceSource(
            CodexSessionBinding binding,
            CodexRuntimeProfile runtimeProfile
    ) {
        if (binding != null
                && binding.isMeaningful()
                && binding.getProviderId() != null
                && !binding.getProviderId().trim().isEmpty()
                && runtimeProfile != null
                && binding.getProviderId().trim().equals(runtimeProfile.getProviderId())) {
            return "session_binding";
        }
        return "active_provider_fallback";
    }

    /**
     * 向 Codex provider 发送消息。
     * 关键约束是发送前必须重新解析运行时 profile，但不能再先走一遍“当前 active provider”旧解析，
     * 否则历史会话已经绑定的 provider/model 会再次被污染。
     *
     * @param channelId 通道 ID
     * @param input 用户输入
     * @param attachments 附件列表
     * @param openedFilesJson 当前打开文件上下文
     * @param agentPrompt 代理提示词
     * @param fileTagPaths 文件标签路径
     * @param effectivePermissionMode 当前请求生效的权限模式
     * @return 发送完成 future
     */
    private CompletableFuture<Void> sendToCodex(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            List<String> fileTagPaths,
            String effectivePermissionMode
    ) {
        CodexMessageHandler handler = new CodexMessageHandler(state, callbackFacade.getCallbackHandler());
        String accessMode = CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE;
        try {
            accessMode = new CodemossSettingsService().getCodexRuntimeAccessMode();
        } catch (Exception e) {
            LOG.warn("[Codex] Failed to resolve runtime access mode: " + e.getMessage());
        }

        String accessError = getCodexRuntimeAccessError(accessMode);
        if (accessError != null) {
            handler.onError(accessError);
            return CompletableFuture.completedFuture(null);
        }

        String contextAppend = contextService.buildCodexContextAppend(openedFilesJson, fileTagPaths);
        String continuationCarryoverPrefix = buildContinuationCarryoverPrefix(state);
        String finalInput = continuationCarryoverPrefix + (input != null ? input : "") + contextAppend;
        CodexRuntimeProfile runtimeProfile;
        try {
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " sendToCodex start sessionId="
                    + safe(state.getSessionId())
                    + ", provider=" + safe(state.getProvider())
                    + ", model=" + safe(state.getModel())
                    + ", reasoningEffort=" + safe(state.getReasoningEffort())
                    + ", permissionMode=" + safe(effectivePermissionMode)
                    + ", binding=" + describeCodexBindingForTrace(state.getCodexSessionBinding()));
            // 每次发送前都重新解析运行时 profile，避免切换 provider 后继续复用旧配置。
            // 如果当前会话已经绑定过特定 Codex provider，则优先命中该绑定。
            runtimeProfile = resolveCodexRuntimeProfile();
            state.setCodexSessionBinding(CodexSessionBinding.fromRuntimeProfile(runtimeProfile));
            persistCodexSessionBindingIfPossible(state.getSessionId(), state.getCodexSessionBinding());
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " sendToCodex resolved runtimeProfile="
                    + runtimeProfile.toDiagnosticJson()
                    + ", traceSource=" + determineCodexRuntimeProfileTraceSource(state.getCodexSessionBinding(), runtimeProfile)
                    + ", persistedBinding=" + describeCodexBindingForTrace(state.getCodexSessionBinding()));
        } catch (Exception e) {
            LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " sendToCodex resolve failed sessionId="
                    + safe(state.getSessionId())
                    + ", binding=" + describeCodexBindingForTrace(state.getCodexSessionBinding())
                    + ", error=" + e.getMessage(), e);
            handler.onError(e.getMessage());
            return CompletableFuture.completedFuture(null);
        }

        return codexSDKBridge.sendMessage(
                channelId,
                finalInput,
                state.getSessionId(),
                state.getCwd(),
                attachments,
                effectivePermissionMode,
                runtimeProfile.getModel(),
                agentPrompt,
                runtimeProfile.getReasoningEffort(),
                runtimeProfile,
                handler
        ).thenCompose(result -> {
            if (result != null) {
                state.setLastRecoveryMetadata(
                        result.recovered,
                        result.recoveryCategory,
                        result.recoveryAction
                );
                persistCodexSessionBindingIfPossible(state.getSessionId(), state.getCodexSessionBinding());
                LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " sendToCodex finished sessionId="
                        + safe(state.getSessionId())
                        + ", success=" + result.success
                        + ", recovered=" + result.recovered
                        + ", recoveryCategory=" + safe(result.recoveryCategory)
                        + ", recoveryAction=" + safe(result.recoveryAction)
                        + ", binding=" + describeCodexBindingForTrace(state.getCodexSessionBinding()));
            }

            // Codex 旧链路里 callback.onError 不一定会让 future 异常结束，
            // 因此这里必须显式根据 result.success 再做一次兜底判断，
            // 避免上层把“实际失败/取消”误当成正常完成。
            if (result != null && result.success) {
                return CompletableFuture.completedFuture(null);
            }

            String errorMessage = (result != null && result.error != null && !result.error.trim().isEmpty())
                    ? result.error
                    : "codex_send_failed";
            if (result != null && result.recoveryAction != null && !result.recoveryAction.trim().isEmpty()) {
                errorMessage = errorMessage
                        + " | recoveryAction=" + result.recoveryAction
                        + " | recoveryCategory=" + (result.recoveryCategory != null ? result.recoveryCategory : "")
                        + " | recovered=" + result.recovered;
            }
            CompletableFuture<Void> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException(errorMessage));
            return failedFuture;
        });
    }

    /**
     * 解析当前发送真正应使用的 Codex runtime profile。
     * 如果当前会话已经绑定过某个 Codex provider，则继续发送时优先命中该绑定，
     * 避免历史会话恢复后又被当前 active provider 污染。
     *
     * @return 当前发送真正应使用的运行时 profile
     * @throws Exception provider 读取或 profile 解析失败时抛出
     */
    private CodexRuntimeProfile resolveCodexRuntimeProfile() throws Exception {
        CodemossSettingsService settingsService = new CodemossSettingsService();
        CodexRuntimeProfileResolver resolver = new CodexRuntimeProfileResolver(settingsService, System::getenv);
        CodexSessionBinding binding = state.getCodexSessionBinding();
        if (binding == null || !binding.isMeaningful() || binding.getProviderId().isEmpty()) {
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " resolveCodexRuntimeProfile fallback=active_provider"
                    + ", reason=missing_binding"
                    + ", sessionId=" + safe(state.getSessionId())
                    + ", provider=" + safe(state.getProvider())
                    + ", model=" + safe(state.getModel())
                    + ", binding=" + describeCodexBindingForTrace(binding));
            return resolver.resolve(state.getModel(), state.getReasoningEffort());
        }

        JsonObject boundProvider = settingsService.getCodexProviderById(binding.getProviderId());
        if (boundProvider == null || boundProvider.size() == 0) {
            LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " resolveCodexRuntimeProfile fallback=active_provider"
                    + ", reason=provider_missing"
                    + ", sessionId=" + safe(state.getSessionId())
                    + ", binding=" + describeCodexBindingForTrace(binding));
            return resolver.resolve(state.getModel(), state.getReasoningEffort());
        }

        String boundModel = binding.getModel() != null && !binding.getModel().trim().isEmpty()
                ? binding.getModel().trim()
                : state.getModel();
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " resolveCodexRuntimeProfile source=session_binding"
                + ", sessionId=" + safe(state.getSessionId())
                + ", boundModel=" + safe(boundModel)
                + ", reasoningEffort=" + safe(state.getReasoningEffort())
                + ", binding=" + describeCodexBindingForTrace(binding));
        return resolver.resolveForProvider(boundProvider, boundModel, state.getReasoningEffort());
    }

    /**
     * 在拿到稳定 sessionId/threadId 后，把当前 Codex 会话绑定元数据同步到本地配置。
     * 首次发送前 threadId 尚未建立，因此空 sessionId 会直接跳过，等待底层返回 threadId 后再落盘。
     *
     * @param sessionId 当前会话 threadId
     * @param binding 待持久化的绑定元数据
     */
    private void persistCodexSessionBindingIfPossible(String sessionId, CodexSessionBinding binding) {
        if (binding == null || !binding.isMeaningful()) {
            return;
        }
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }
        try {
            new CodemossSettingsService().saveCodexSessionBinding(sessionId, binding);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " persistCodexSessionBindingIfPossible sessionId="
                    + safe(sessionId)
                    + ", binding=" + describeCodexBindingForTrace(binding));
        } catch (Exception e) {
            LOG.warn("[CODEX_RUNTIME] Failed to persist Codex session binding: " + e.getMessage(), e);
        }
    }

    /**
     * 统一规整日志中的可空字符串字段，避免 trace 输出出现 null 并影响排查可读性。
     *
     * @param value 原始字段值
     * @return 去首尾空白后的字段值；为空时返回空串
     */
    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private CompletableFuture<Void> sendToClaude(
            String channelId,
            String input,
            List<ClaudeSession.Attachment> attachments,
            JsonObject openedFilesJson,
            String agentPrompt,
            String effectivePermissionMode
    ) {
        ClaudeMessageHandler handler = new ClaudeMessageHandler(
                project,
                state,
                callbackFacade.getCallbackHandler(),
                messageParser,
                messageMerger,
                gson
        );

        Boolean streaming = readStreamingEnabled();
        final String runtimeSessionEpoch = state.getRuntimeSessionEpoch();
        final String currentModel = state.getModel();
        LOG.info("[Lifecycle] sendToClaude sessionId=" + (state.getSessionId() != null ? state.getSessionId() : "(new)")
                + ", epoch=" + runtimeSessionEpoch
                + ", cwd=" + state.getCwd()
                + ", model=" + currentModel);

        String continuationCarryoverPrefix = buildContinuationCarryoverPrefix(state);
        String finalInput = continuationCarryoverPrefix + (input != null ? input : "");

        return claudeSDKBridge.sendMessage(
                channelId,
                finalInput,
                state.getSessionId(),
                runtimeSessionEpoch,
                state.getCwd(),
                attachments,
                effectivePermissionMode,
                currentModel,
                openedFilesJson,
                agentPrompt,
                streaming,
                false,
                state.getReasoningEffort(),
                handler
        ).thenApply(result -> null);
    }

    private boolean readAutoOpenFileEnabled() {
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = new CodemossSettingsService();
                boolean autoOpenFileEnabled = settingsService.getAutoOpenFileEnabled(projectPath);
                LOG.info("[EditorContext] Auto open file enabled: " + autoOpenFileEnabled);
                return autoOpenFileEnabled;
            }
        } catch (Exception e) {
            LOG.warn("[EditorContext] Failed to read autoOpenFileEnabled setting: " + e.getMessage());
        }
        return false;
    }

    private Boolean readStreamingEnabled() {
        Boolean streaming = null;
        try {
            String projectPath = project.getBasePath();
            if (projectPath != null) {
                CodemossSettingsService settingsService = new CodemossSettingsService();
                streaming = settingsService.getStreamingEnabled(projectPath);
                LOG.info("[Streaming] Read streaming config: " + streaming);
            }
        } catch (Exception e) {
            LOG.warn("[Streaming] Failed to read streaming config: " + e.getMessage());
        }
        return streaming;
    }

    private String getAgentPrompt() {
        try {
            CodemossSettingsService settingsService = new CodemossSettingsService();
            String selectedAgentId = settingsService.getSelectedAgentId();
            LOG.info("[Agent] Checking selected agent ID: " + (selectedAgentId != null ? selectedAgentId : "null"));

            if (selectedAgentId != null && !selectedAgentId.isEmpty()) {
                JsonObject agent = settingsService.getAgent(selectedAgentId);
                if (agent != null && agent.has("prompt") && !agent.get("prompt").isJsonNull()) {
                    String agentPrompt = agent.get("prompt").getAsString();
                    String agentName = agent.has("name") ? agent.get("name").getAsString() : "Unknown";
                    LOG.info("[Agent] Found agent: " + agentName);
                    LOG.info("[Agent] Prompt length: " + agentPrompt.length() + " chars");
                    LOG.info("[Agent] Prompt preview: "
                            + (agentPrompt.length() > 100 ? agentPrompt.substring(0, 100) + "..." : agentPrompt));
                    return agentPrompt;
                }
                LOG.info("[Agent] Agent found but no prompt configured");
            } else {
                LOG.info("[Agent] No agent selected");
            }
        } catch (Exception e) {
            LOG.warn("[Agent] Failed to get agent prompt: " + e.getMessage());
        }
        return null;
    }
}
