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
        String finalInput = (input != null ? input : "") + contextAppend;
        CodexRuntimeProfile runtimeProfile;
        try {
            // 每次发送前都重新解析运行时 profile，避免切换 provider 后继续复用旧配置。
            // 如果当前会话已经绑定过特定 Codex provider，则优先命中该绑定。
            runtimeProfile = resolveCodexRuntimeProfile();
            state.setCodexSessionBinding(CodexSessionBinding.fromRuntimeProfile(runtimeProfile));
            persistCodexSessionBindingIfPossible(state.getSessionId(), state.getCodexSessionBinding());
            LOG.info("[CODEX_RUNTIME] Session resolved runtime profile: " + runtimeProfile.toDiagnosticJson());
        } catch (Exception e) {
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
            return resolver.resolve(state.getModel(), state.getReasoningEffort());
        }

        JsonObject boundProvider = settingsService.getCodexProviderById(binding.getProviderId());
        if (boundProvider == null || boundProvider.size() == 0) {
            LOG.warn("[CODEX_RUNTIME] Bound provider no longer exists, fallback to current active provider. providerId="
                    + binding.getProviderId());
            return resolver.resolve(state.getModel(), state.getReasoningEffort());
        }

        String boundModel = binding.getModel() != null && !binding.getModel().trim().isEmpty()
                ? binding.getModel().trim()
                : state.getModel();
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
        } catch (Exception e) {
            LOG.warn("[CODEX_RUNTIME] Failed to persist Codex session binding: " + e.getMessage(), e);
        }
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

        return claudeSDKBridge.sendMessage(
                channelId,
                input,
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
