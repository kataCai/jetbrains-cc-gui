package com.github.claudecodegui.session;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.model.SessionTemplate;
import com.github.claudecodegui.remote.debug.TabSessionRestoreDebugTrace;
import com.github.claudecodegui.settings.CodexProviderManager;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.SettingsHandler;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.skill.SlashCommandRegistry;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages session lifecycle operations: creation, history loading,
 * working directory resolution, slash commands, and permission mode sync.
 */
public class SessionLifecycleManager {

    private static final Logger LOG = Logger.getInstance(SessionLifecycleManager.class);
    private static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";
    private static final String CONTINUATION_CARRYOVER_MODE = "session_summary";

    /**
     * Host interface providing access to window-level dependencies.
     */
    public interface SessionHost {
        Project getProject();

        ClaudeSDKBridge getClaudeSDKBridge();

        CodexSDKBridge getCodexSDKBridge();

        ClaudeSession getSession();

        void setSession(ClaudeSession session);

        HandlerContext getHandlerContext();

        StreamMessageCoalescer getStreamCoalescer();

        void clearPendingPermissionRequests();

        void clearPermissionDecisionMemory();

        void callJavaScript(String functionName, String... args);

        boolean isDisposed();

        JBCefBrowser getBrowser();

        void setupSessionCallbacks();

        void invalidateSessionCallbacks();

        void setSlashCommandsFetched(boolean fetched);

        void setFetchedSlashCommandsCount(int count);
    }

    private final SessionHost host;

    public SessionLifecycleManager(SessionHost host) {
        this.host = host;
    }

    /**
     * Create a new session, interrupting the old one first.
     */
    public void createNewSession() {
        LOG.info("Creating new session...");

        ClaudeSession oldSession = host.getSession();
        ClaudeSession defaultSession = createDefaultSession();
        String previousPermissionMode = (oldSession != null) ? oldSession.getPermissionMode() : defaultSession.getPermissionMode();
        String previousProvider = (oldSession != null) ? oldSession.getProvider() : defaultSession.getProvider();
        String previousModel = (oldSession != null) ? oldSession.getModel() : defaultSession.getModel();
        CodexSessionBinding previousCodexBinding = oldSession != null
                ? oldSession.getState().getCodexSessionBinding()
                : null;
        LOG.info("Preserving session state: mode=" + previousPermissionMode
                         + ", provider=" + previousProvider + ", model=" + previousModel);
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " createNewSession preserve oldSession provider="
                + previousProvider + ", model=" + previousModel
                + ", binding=" + describeBinding(previousCodexBinding));

        host.invalidateSessionCallbacks();
        host.getStreamCoalescer().resetStreamState();
        host.callJavaScript("clearMessages");

        CompletableFuture<Void> interruptFuture = oldSession != null
                                                          ? oldSession.interrupt()
                                                          : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            if (oldSession != null) {
                host.getClaudeSDKBridge().resetPersistentRuntime(oldSession.getRuntimeSessionEpoch());
                LOG.info("[Lifecycle] Requested daemon runtime reset for old epoch=" + oldSession.getRuntimeSessionEpoch());
            }
            LOG.info("Old session interrupted, creating new session");

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("onStreamEnd");
                host.callJavaScript("showLoading", "false");
            });

            ClaudeSession newSession = createDefaultSession();
            newSession.setPermissionMode(previousPermissionMode);
            newSession.setProvider(previousProvider);
            newSession.setModel(previousModel);
            copyCodexSessionBindingIfPresent(oldSession, newSession);
            LOG.info("Restored session state to new session: mode=" + previousPermissionMode
                             + ", provider=" + previousProvider + ", model=" + previousModel);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " createNewSession restored newSession provider="
                    + newSession.getProvider() + ", model=" + newSession.getModel()
                    + ", binding=" + describeBinding(newSession.getState().getCodexSessionBinding()));

            completeNewSessionBootstrap(newSession, determineWorkingDirectory(),
                    "New session created successfully, working directory: ");
        }).exceptionally(ex -> {
            LOG.error("Failed to create new session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs("Failed to create new session: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * 在当前逻辑会话下创建一个新的继续分段，并切换到新的运行时配置。
     * 第一阶段只负责切断旧物理 session、创建新的运行态和打上 continuation pending 标记；
     * 待底层 provider 返回新的真实 sessionId 后，再由完成钩子补齐逻辑会话和分段索引。
     *
     * @param payloadJson 前端传入的继续分段请求 JSON
     */
    public void createContinuedSessionWithRuntimeSwitch(String payloadJson) {
        ContinuedSegmentRequest request = ContinuedSegmentRequest.fromJson(payloadJson);
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " createContinuedSessionWithRuntimeSwitch request=" + request.toLogString());

        ClaudeSession oldSession = host.getSession();
        if (oldSession == null) {
            LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " createContinuedSessionWithRuntimeSwitch fallback=createNewSession because oldSession is null");
            createNewSession();
            return;
        }

        String previousPermissionMode = oldSession.getPermissionMode();
        String workingDirectory = determineWorkingDirectory();

        host.invalidateSessionCallbacks();
        host.getStreamCoalescer().resetStreamState();

        oldSession.interrupt().thenRun(() -> {
            host.getClaudeSDKBridge().resetPersistentRuntime(oldSession.getRuntimeSessionEpoch());

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("onStreamEnd");
                host.callJavaScript("showLoading", "false");
            });

            ClaudeSession newSession = createDefaultSession();
            newSession.setPermissionMode(previousPermissionMode);
            newSession.setProvider(resolveTargetProviderForRuntime(request));
            if (hasText(request.targetModel)) {
                newSession.setModel(request.targetModel);
            }
            if (hasText(request.targetReasoningEffort)) {
                newSession.setReasoningEffort(request.targetReasoningEffort);
            }

            CodexSessionBinding targetBinding = buildContinuedSegmentCodexBinding(request);
            newSession.getState().setCodexSessionBinding(targetBinding);
            primeContinuationMetadata(oldSession, newSession, request);

            host.clearPendingPermissionRequests();
            host.clearPermissionDecisionMemory();
            host.setSession(newSession);
            host.getHandlerContext().setSession(newSession);
            syncHandlerRuntimeState(newSession);
            host.setupSessionCallbacks();

            newSession.setSessionInfo(null, workingDirectory);
            fetchSlashCommandsOnStartup();

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs(ClaudeCodeGuiBundle.message("toast.newSessionCreatedReady")));
                resetTokenUsage();
            });
        }).exceptionally(ex -> {
            LOG.error("Failed to create continued session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs("Failed to continue session: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Create a new session from a template, interrupting the old one first.
     */
    public void createNewSessionFromTemplate(SessionTemplate template) {
        LOG.info("Creating new session from template: " + template.getName());

        ClaudeSession oldSession = host.getSession();
        CodexSessionBinding previousCodexBinding = oldSession != null
                ? oldSession.getState().getCodexSessionBinding()
                : null;
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " createNewSessionFromTemplate preserve oldSession provider="
                + (oldSession != null ? oldSession.getProvider() : "(none)")
                + ", model=" + (oldSession != null ? oldSession.getModel() : "(none)")
                + ", binding=" + describeBinding(previousCodexBinding));

        host.invalidateSessionCallbacks();
        host.getStreamCoalescer().resetStreamState();
        host.callJavaScript("clearMessages");

        CompletableFuture<Void> interruptFuture = oldSession != null
                ? oldSession.interrupt()
                : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            if (oldSession != null) {
                host.getClaudeSDKBridge().resetPersistentRuntime(oldSession.getRuntimeSessionEpoch());
                LOG.info("[Lifecycle] Requested daemon runtime reset for old epoch=" + oldSession.getRuntimeSessionEpoch());
            }
            LOG.info("Old session interrupted, creating new session from template");

            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("onStreamEnd");
                host.callJavaScript("showLoading", "false");
            });

            ClaudeSession newSession = createDefaultSession();

            // Apply template settings
            if (template.getPermissionMode() != null) {
                newSession.setPermissionMode(template.getPermissionMode());
            }
            if (template.getProvider() != null) {
                newSession.setProvider(template.getProvider());
            }
            if (template.getModel() != null) {
                newSession.setModel(template.getModel());
            }
            if (template.getReasoningEffort() != null) {
                newSession.setReasoningEffort(template.getReasoningEffort());
            }
            newSession.getState().setPsiContextEnabled(template.isPsiContextEnabled());
            copyCodexSessionBindingIfPresent(oldSession, newSession);

            LOG.info("Applied template settings to new session: provider=" + template.getProvider()
                    + ", model=" + template.getModel() + ", mode=" + template.getPermissionMode());
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " createNewSessionFromTemplate restored newSession provider="
                    + newSession.getProvider() + ", model=" + newSession.getModel()
                    + ", binding=" + describeBinding(newSession.getState().getCodexSessionBinding()));

            String workingDirectory = template.getCwd() != null && !template.getCwd().trim().isEmpty()
                    ? template.getCwd() : determineWorkingDirectory();
            completeNewSessionBootstrap(newSession, workingDirectory,
                    "New session created from template successfully, working directory: ");
        }).exceptionally(ex -> {
            LOG.error("Failed to create new session from template: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("updateStatus",
                        JsUtils.escapeJs("Failed to create new session from template: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Load a history session by ID.
     */
    public void loadHistorySession(String sessionId, String projectPath) {
        loadHistorySession(sessionId, projectPath, null, null, null, null);
    }

    /**
     * Load a history session by ID and provider.
     */
    public void loadHistorySession(String sessionId, String projectPath, String provider) {
        loadHistorySession(sessionId, projectPath, provider, null, null, null);
    }

    /**
     * 按指定展示 provider 与运行时家族加载历史会话。
     * 该入口用于统一承接启动恢复、历史切换与手动刷新后的补恢复请求，避免不同入口分别推断恢复链路。
     *
     * @param sessionId 目标会话 ID
     * @param projectPath 会话对应工作目录
     * @param provider 展示 provider，可为空
     * @param runtimeFamily 显式运行时家族，可为空；为空时按兼容规则推断
     */
    public void loadHistorySession(String sessionId, String projectPath, String provider, String runtimeFamily) {
        loadHistorySession(sessionId, projectPath, provider, runtimeFamily, "history_switch", null);
    }

    public void loadHistorySession(
            String sessionId,
            String projectPath,
            String provider,
            String runtimeFamily,
            String restoreSource,
            String transitionToken
    ) {
        LOG.info("Loading history session: " + sessionId + " from project: " + projectPath);
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " loadHistorySession start sessionId="
                + firstNonBlank(sessionId)
                + ", projectPath=" + firstNonBlank(projectPath)
                + ", provider=" + firstNonBlank(provider)
                + ", runtimeFamily=" + firstNonBlank(runtimeFamily)
                + ", restoreSource=" + firstNonBlank(restoreSource)
                + ", transitionToken=" + firstNonBlank(transitionToken));

        ClaudeSession oldSession = host.getSession();
        String previousPermissionMode;
        String previousProvider;
        String previousModel;

        if (oldSession != null) {
            previousPermissionMode = oldSession.getPermissionMode();
            previousProvider = oldSession.getProvider();
            previousModel = oldSession.getModel();
        } else {
            PropertiesComponent props = PropertiesComponent.getInstance();
            String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
            ClaudeSession defaultSession = createDefaultSession();
            previousPermissionMode = (savedMode != null && !savedMode.trim().isEmpty())
                                             ? savedMode.trim() : defaultSession.getPermissionMode();
            previousProvider = defaultSession.getProvider();
            previousModel = defaultSession.getModel();
        }
        LOG.info("Preserving session state when loading history: mode=" + previousPermissionMode
                         + ", provider=" + previousProvider + ", model=" + previousModel);

        host.invalidateSessionCallbacks();
        host.getStreamCoalescer().resetStreamState();
        host.callJavaScript("clearMessages");
        host.clearPendingPermissionRequests();
        host.clearPermissionDecisionMemory();

        CompletableFuture<Void> interruptFuture = oldSession != null
                ? oldSession.interrupt()
                : CompletableFuture.completedFuture(null);

        interruptFuture.thenRun(() -> {
            if (oldSession != null) {
                host.getClaudeSDKBridge().resetPersistentRuntime(oldSession.getRuntimeSessionEpoch());
                LOG.info("[Lifecycle] Requested daemon runtime reset before history load for old epoch="
                        + oldSession.getRuntimeSessionEpoch());
            }

            ClaudeSession newSession = new ClaudeSession(
                    host.getProject(), host.getClaudeSDKBridge(), host.getCodexSDKBridge());
            newSession.setPermissionMode(previousPermissionMode);
            String resolvedProvider = provider != null && !provider.trim().isEmpty() ? provider : previousProvider;
            String resolvedRuntimeFamily = SessionRuntimeFamily.resolve(
                    resolvedProvider,
                    runtimeFamily,
                    oldSession != null ? oldSession.getState().getCodexSessionBinding() : null
            );
            if (SessionRuntimeFamily.CODEX.equals(resolvedRuntimeFamily)) {
                newSession.setProvider(SessionRuntimeFamily.CODEX);
            } else {
                newSession.setProvider(resolvedProvider);
            }
            newSession.setModel(previousModel);
            LOG.info("Restored session state to loaded session: mode=" + previousPermissionMode
                             + ", provider=" + newSession.getProvider() + ", model=" + previousModel
                             + ", runtimeFamily=" + resolvedRuntimeFamily);
            LOG.info(TabSessionRestoreDebugTrace.buildMessage(
                    "history_restore_session_initialized",
                    -1,
                    sessionId,
                    resolvedProvider,
                    resolvedRuntimeFamily,
                    restoreSource,
                    false,
                    transitionToken
            ));

            host.setSession(newSession);
            host.getHandlerContext().setSession(newSession);
            host.setupSessionCallbacks();

            String workingDir = (projectPath != null && new File(projectPath).exists())
                                    ? projectPath : determineWorkingDirectory();
            newSession.setSessionInfo(sessionId, workingDir);
            restoreCodexSessionBindingIfPresent(newSession, sessionId);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " loadHistorySession prepared newSession sessionId="
                    + firstNonBlank(sessionId)
                    + ", resolvedProvider=" + firstNonBlank(resolvedProvider)
                    + ", resolvedRuntimeFamily=" + firstNonBlank(resolvedRuntimeFamily)
                    + ", model=" + firstNonBlank(newSession.getModel())
                    + ", permissionMode=" + firstNonBlank(newSession.getPermissionMode())
                    + ", binding=" + describeBinding(newSession.getState().getCodexSessionBinding())
                    + ", restoreSource=" + firstNonBlank(restoreSource)
                    + ", transitionToken=" + firstNonBlank(transitionToken));

            // Prewarm daemon runtime for the historical session so /context and first message are fast
            host.getClaudeSDKBridge().prewarmDaemonAsync(workingDir, newSession.getRuntimeSessionEpoch(), sessionId);

            newSession.loadFromServer().thenRun(() -> ApplicationManager.getApplication().invokeLater(() -> {
                replayRestoredSessionTitle(sessionId);
                LOG.info(TabSessionRestoreDebugTrace.buildMessage(
                        "history_restore_session_completed",
                        -1,
                        sessionId,
                        resolvedProvider,
                        resolvedRuntimeFamily,
                        restoreSource,
                        false,
                        transitionToken
                ));
                host.callJavaScript("historyLoadComplete");
            })).exceptionally(ex -> {
                ApplicationManager.getApplication().invokeLater(() -> {
                    LOG.warn(TabSessionRestoreDebugTrace.buildMessage(
                            "history_restore_session_failed",
                            -1,
                            sessionId,
                            resolvedProvider,
                            resolvedRuntimeFamily,
                            restoreSource,
                            false,
                            transitionToken
                    ) + ", error=" + ex.getMessage());
                    // Release transition guard so the frontend is not permanently stuck
                    host.callJavaScript("historyLoadComplete");
                    host.callJavaScript("addErrorMessage",
                            JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
                });
                return null;
            });
        }).exceptionally(ex -> {
            LOG.error("Failed to load history session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                LOG.warn(TabSessionRestoreDebugTrace.buildMessage(
                        "history_restore_bootstrap_failed",
                        -1,
                        sessionId,
                        provider,
                        runtimeFamily,
                        restoreSource,
                        false,
                        transitionToken
                ) + ", error=" + ex.getMessage());
                host.callJavaScript("historyLoadComplete");
                host.callJavaScript("addErrorMessage",
                        JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
            });
            return null;
        });
    }

    /**
     * Determine the working directory for the session.
     */
    public String determineWorkingDirectory() {
        String projectPath = host.getProject().getBasePath();

        if (projectPath == null || !new File(projectPath).exists()) {
            String userHome = PlatformUtils.getHomeDirectory();
            LOG.warn("Using user home directory as fallback: " + userHome);
            return userHome;
        }

        try {
            CodemossSettingsService settingsService = new CodemossSettingsService();
            String customWorkingDir = settingsService.getCustomWorkingDirectory(projectPath);

            if (customWorkingDir != null && !customWorkingDir.isEmpty()) {
                File workingDirFile = new File(customWorkingDir);
                if (!workingDirFile.isAbsolute()) {
                    workingDirFile = new File(projectPath, customWorkingDir);
                }
                if (workingDirFile.exists() && workingDirFile.isDirectory()) {
                    String resolvedPath = workingDirFile.getAbsolutePath();
                    LOG.info("Using custom working directory: " + resolvedPath);
                    return resolvedPath;
                } else {
                    LOG.warn("Custom working directory does not exist: "
                                     + workingDirFile.getAbsolutePath() + ", falling back to project root");
                }
            }
        } catch (Exception e) {
            LOG.warn("Failed to read custom working directory: " + e.getMessage());
        }

        return projectPath;
    }

    /**
     * 恢复 Codex 会话绑定元数据。
     * 该恢复只依赖插件侧持久化的非敏感绑定字段，不改写 Codex 原生历史文件，
     * 目的是在历史会话继续发送时仍优先命中原来的 provider/model。
     *
     * @param session 待恢复绑定的会话对象
     * @param sessionId 当前加载的会话 ID
     */
    protected void restoreCodexSessionBindingIfPresent(ClaudeSession session, String sessionId) {
        if (session == null || sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }

        try {
            CodexSessionBinding binding = createSettingsService().getCodexSessionBinding(sessionId);
            if (binding == null || !binding.isMeaningful()) {
                return;
            }

            session.setProvider("codex");
            if (binding.getModel() != null && !binding.getModel().trim().isEmpty()) {
                session.setModel(binding.getModel());
            }
            session.getState().setCodexSessionBinding(binding);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " restoreCodexSessionBindingIfPresent sessionId="
                    + sessionId + ", binding=" + describeBinding(binding));
            LOG.info("[CODEX_RUNTIME] Restored Codex session binding during history load. sessionId="
                    + sessionId + ", providerId=" + binding.getProviderId() + ", model=" + binding.getModel());
        } catch (Exception e) {
            LOG.warn("[CODEX_RUNTIME] Failed to restore Codex session binding during history load: "
                    + e.getMessage(), e);
        }
    }

    /**
     * 复制旧 session 上的 Codex tab 级 binding 到新 session。
     * 仅在旧 session 已经存在有效 binding 时执行复制，避免普通 Claude 会话被误注入 Codex 运行态。
     *
     * @param oldSession 作为 binding 来源的旧会话
     * @param newSession 待写入 binding 的新会话
     */
    protected void copyCodexSessionBindingIfPresent(ClaudeSession oldSession, ClaudeSession newSession) {
        if (oldSession == null || newSession == null) {
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " skip binding copy because oldSession or newSession is null");
            return;
        }

        CodexSessionBinding previousBinding = oldSession.getState().getCodexSessionBinding();
        if (previousBinding == null || !previousBinding.isMeaningful()) {
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " skip binding copy because oldSession has no meaningful binding");
            return;
        }

        CodexSessionBinding copiedBinding = new CodexSessionBinding(
                previousBinding.getProviderId(),
                previousBinding.getModel(),
                previousBinding.getRequestMode(),
                previousBinding.getBaseUrlSource(),
                previousBinding.getEffectiveConfigSource()
        );
        newSession.getState().setCodexSessionBinding(copiedBinding);
        if (!copiedBinding.getProviderId().isEmpty() || "codex".equalsIgnoreCase(oldSession.getProvider())) {
            newSession.setProvider("codex");
        }
        if (!copiedBinding.getModel().isEmpty()) {
            newSession.setModel(copiedBinding.getModel());
        }
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " copied binding from oldSession to newSession. oldBinding="
                + describeBinding(previousBinding) + ", newBinding=" + describeBinding(copiedBinding));
    }

    /**
     * 创建设置服务实例。
     * 之所以保留单独工厂方法，是为了让单元测试能够稳定替换配置来源，而不影响生产逻辑。
     *
     * @return 设置服务实例
     */
    protected CodemossSettingsService createSettingsService() {
        return new CodemossSettingsService();
    }

    /**
     * 在新分段拿到真实 sessionId 后，补齐逻辑会话与分段索引并清除 continuation pending。
     * 该方法会在会话 id 回调和单元测试桩中共用，因此需要保证幂等和兼容空元数据输入。
     *
     * @param sourceSessionId 来源分段 sessionId
     * @param newSessionId 新分段真实 sessionId
     * @param targetCodexProviderId 目标 Codex provider id
     * @param targetProvider 新分段 provider
     * @param targetRuntimeFamily 新分段 runtime family
     * @param targetModel 新分段 model
     * @param targetReasoningEffort 新分段 reasoning effort
     * @param switchReason 切换原因
     */
    protected void completeContinuedSegment(
            String sourceSessionId,
            String newSessionId,
            String targetCodexProviderId,
            String targetProvider,
            String targetRuntimeFamily,
            String targetModel,
            String targetReasoningEffort,
            String switchReason
    ) {
        if (!hasText(newSessionId)) {
            return;
        }
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " completeContinuedSegment start sourceSessionId="
                + firstNonBlank(sourceSessionId)
                + ", newSessionId=" + firstNonBlank(newSessionId)
                + ", targetCodexProviderId=" + firstNonBlank(targetCodexProviderId)
                + ", targetProvider=" + firstNonBlank(targetProvider)
                + ", targetRuntimeFamily=" + firstNonBlank(targetRuntimeFamily)
                + ", targetModel=" + firstNonBlank(targetModel)
                + ", targetReasoningEffort=" + firstNonBlank(targetReasoningEffort)
                + ", switchReason=" + firstNonBlank(switchReason));

        ClaudeSession session = host.getSession();
        if (session == null) {
            return;
        }

        SessionState state = session.getState();
        String logicalConversationId = firstNonBlank(
                state.getLogicalConversationId(),
                resolveLogicalConversationIdBySessionId(sourceSessionId),
                "logical-" + sourceSessionId
        );

        try {
            CodemossSettingsService settingsService = createSettingsService();
            List<ConversationSegmentRecord> existingSegments = settingsService.listConversationSegments(logicalConversationId);
            int nextSegmentIndex = existingSegments.size();
            long now = System.currentTimeMillis();

            LogicalConversationRecord previousLogicalRecord = settingsService.getLogicalConversationRecord(logicalConversationId);
            String rootSessionId = previousLogicalRecord != null && previousLogicalRecord.isMeaningful()
                    ? firstNonBlank(previousLogicalRecord.getRootSessionId(), sourceSessionId, newSessionId)
                    : firstNonBlank(sourceSessionId, newSessionId);
            String title = previousLogicalRecord != null && previousLogicalRecord.isMeaningful()
                    ? previousLogicalRecord.getTitle()
                    : session.getSummary();
            long createdAt = previousLogicalRecord != null && previousLogicalRecord.isMeaningful()
                    ? previousLogicalRecord.getCreatedAt()
                    : now;
            boolean favorited = previousLogicalRecord != null && previousLogicalRecord.isMeaningful() && previousLogicalRecord.isFavorited();
            long favoritedAt = previousLogicalRecord != null && previousLogicalRecord.isMeaningful()
                    ? previousLogicalRecord.getFavoritedAt()
                    : 0L;
            SourceSegmentRuntimeMetadata sourceMetadata = resolveSourceSegmentRuntimeMetadata(
                    settingsService,
                    sourceSessionId,
                    previousLogicalRecord,
                    session
            );

            if (existingSegments.isEmpty() && hasText(sourceSessionId)) {
                settingsService.saveConversationSegmentRecord(new ConversationSegmentRecord(
                        sourceSessionId,
                        logicalConversationId,
                        "",
                        0,
                        sourceMetadata.provider,
                        sourceMetadata.runtimeFamily,
                        sourceMetadata.model,
                        sourceMetadata.reasoningEffort,
                        "backfill_source_segment",
                        CONTINUATION_CARRYOVER_MODE,
                        previousLogicalRecord != null && previousLogicalRecord.isMeaningful()
                                ? previousLogicalRecord.getCreatedAt()
                                : now
                ));
                existingSegments = settingsService.listConversationSegments(logicalConversationId);
                nextSegmentIndex = existingSegments.size();
            }

            settingsService.saveConversationSegmentRecord(new ConversationSegmentRecord(
                    newSessionId,
                    logicalConversationId,
                    firstNonBlank(sourceSessionId),
                    nextSegmentIndex,
                    firstNonBlank(targetProvider, session.getProvider()),
                    firstNonBlank(targetRuntimeFamily, SessionRuntimeFamily.resolve(session.getProvider(), null, session.getState().getCodexSessionBinding())),
                    firstNonBlank(targetModel, session.getModel()),
                    firstNonBlank(targetReasoningEffort, session.getReasoningEffort()),
                    "runtime_switch:" + firstNonBlank(switchReason, "unknown"),
                    CONTINUATION_CARRYOVER_MODE,
                    now
            ));

            settingsService.saveLogicalConversationRecord(new LogicalConversationRecord(
                    logicalConversationId,
                    rootSessionId,
                    newSessionId,
                    firstNonBlank(title),
                    firstNonBlank(targetRuntimeFamily, SessionRuntimeFamily.resolve(session.getProvider(), null, session.getState().getCodexSessionBinding())),
                    firstNonBlank(targetProvider, session.getProvider()),
                    firstNonBlank(targetModel, session.getModel()),
                    nextSegmentIndex + 1,
                    createdAt,
                    now,
                    favorited,
                    favoritedAt
            ));
        } catch (Exception e) {
            LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " completeContinuedSegment failed to persist metadata: " + e.getMessage(), e);
        }

        state.setLogicalConversationId(logicalConversationId);
        state.setActiveSegmentSessionId(newSessionId);
        state.setParentSegmentSessionId(firstNonBlank(sourceSessionId));
        state.setContinuationPending(false);
        state.setContinuationSourceSessionId(null);

        if (hasText(targetProvider)) {
            session.setProvider(targetProvider);
        }
        if (hasText(targetModel)) {
            session.setModel(targetModel);
        }
        if (hasText(targetReasoningEffort)) {
            session.setReasoningEffort(targetReasoningEffort);
        }
        if (SessionRuntimeFamily.CODEX.equals(targetRuntimeFamily) || hasText(targetCodexProviderId)) {
            session.getState().setCodexSessionBinding(buildCodexBindingFromProvider(targetCodexProviderId, targetModel));
        }
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " completeContinuedSegment applied logicalConversationId="
                + firstNonBlank(state.getLogicalConversationId())
                + ", activeSegmentSessionId=" + firstNonBlank(state.getActiveSegmentSessionId())
                + ", parentSegmentSessionId=" + firstNonBlank(state.getParentSegmentSessionId())
                + ", continuationPending=" + state.isContinuationPending()
                + ", provider=" + firstNonBlank(session.getProvider())
                + ", runtimeFamily=" + SessionRuntimeFamily.resolve(session.getProvider(), null, state.getCodexSessionBinding())
                + ", model=" + firstNonBlank(session.getModel())
                + ", binding=" + describeBinding(state.getCodexSessionBinding()));

        syncHandlerRuntimeState(session);
    }

    /**
     * 推断回填源分段时应写入的运行时元数据。
     * 优先使用已持久化的源分段记录；若 legacy 会话尚无分段索引，则回退到逻辑会话主记录；
     * 只有在两者都缺失时，才使用当前会话上的字段兜底。
     *
     * @param settingsService 配置服务
     * @param sourceSessionId 源分段 sessionId
     * @param logicalRecord 逻辑会话主记录
     * @param currentSession 当前会话对象
     * @return 源分段回填所需的最可信运行时元数据
     */
    private SourceSegmentRuntimeMetadata resolveSourceSegmentRuntimeMetadata(
            CodemossSettingsService settingsService,
            String sourceSessionId,
            LogicalConversationRecord logicalRecord,
            ClaudeSession currentSession
    ) {
        try {
            ConversationSegmentRecord sourceSegmentRecord = settingsService.getConversationSegmentRecord(sourceSessionId);
            if (sourceSegmentRecord != null && sourceSegmentRecord.isMeaningful()) {
                return new SourceSegmentRuntimeMetadata(
                        firstNonBlank(sourceSegmentRecord.getProvider()),
                        firstNonBlank(sourceSegmentRecord.getRuntimeFamily()),
                        firstNonBlank(sourceSegmentRecord.getModel()),
                        firstNonBlank(sourceSegmentRecord.getReasoningEffort())
                );
            }
        } catch (Exception e) {
            LOG.debug(CODEX_RUNTIME_TRACE_PREFIX + " resolveSourceSegmentRuntimeMetadata segment lookup failed: " + e.getMessage());
        }

        if (logicalRecord != null && logicalRecord.isMeaningful()) {
            return new SourceSegmentRuntimeMetadata(
                    firstNonBlank(logicalRecord.getProvider(), currentSession != null ? currentSession.getProvider() : ""),
                    firstNonBlank(logicalRecord.getRuntimeFamily(),
                            currentSession != null
                                    ? SessionRuntimeFamily.resolve(
                                    currentSession.getProvider(),
                                    null,
                                    currentSession.getState().getCodexSessionBinding()
                            )
                                    : ""),
                    firstNonBlank(logicalRecord.getLastModel(), currentSession != null ? currentSession.getModel() : ""),
                    currentSession != null ? firstNonBlank(currentSession.getReasoningEffort()) : ""
            );
        }

        if (currentSession == null) {
            return new SourceSegmentRuntimeMetadata("", "", "", "");
        }
        return new SourceSegmentRuntimeMetadata(
                firstNonBlank(currentSession.getProvider()),
                firstNonBlank(SessionRuntimeFamily.resolve(
                        currentSession.getProvider(),
                        null,
                        currentSession.getState().getCodexSessionBinding()
                )),
                firstNonBlank(currentSession.getModel()),
                firstNonBlank(currentSession.getReasoningEffort())
        );
    }

    /**
     * Fetch slash commands using local registry (no SDK/API call needed).
     * Merges built-in commands with skill-derived commands per provider.
     */
    public void fetchSlashCommandsOnStartup() {
        ClaudeSession currentSession = host.getSession();
        String cwd = currentSession != null ? currentSession.getCwd() : null;
        if (cwd == null) {
            cwd = host.getProject().getBasePath();
        }

        // Determine current provider
        String provider = "claude";
        if (currentSession != null && currentSession.getProvider() != null) {
            provider = currentSession.getProvider();
        }

        LOG.info("Fetching slash commands locally, provider=" + provider + ", cwd=" + cwd);

        String currentFilePath = getCurrentEditorFilePath();
        var commands = SlashCommandRegistry.getCommands(provider, cwd, currentFilePath);
        String commandsJson = SlashCommandRegistry.toJson(commands);

        host.setFetchedSlashCommandsCount(commands.size());
        host.setSlashCommandsFetched(true);
        LOG.info("Slash commands resolved locally: " + commands.size() + " commands");

        // Pre-compute Codex skills outside EDT to avoid file I/O on UI thread
        final List<SlashCommandRegistry.SlashCommand> codexSkills;
        final String codexSkillsJson;
        if ("codex".equalsIgnoreCase(provider)) {
            codexSkills = SlashCommandRegistry.getCodexSkills(cwd);
            codexSkillsJson = SlashCommandRegistry.toJson(codexSkills);
            LOG.info("Codex skills resolved: " + codexSkills.size() + " skills");
        } else {
            codexSkills = null;
            codexSkillsJson = null;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                host.callJavaScript("updateSlashCommands", JsUtils.escapeJs(commandsJson));

                // Push Codex skills as separate channel for $ autocomplete
                if (codexSkillsJson != null) {
                    host.callJavaScript("window.updateDollarCommands", JsUtils.escapeJs(codexSkillsJson));
                }
            } catch (Exception e) {
                LOG.warn("Failed to send slash commands to frontend: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Send current permission mode to the frontend.
     */
    public void sendCurrentPermissionMode() {
        try {
            String currentMode = "bypassPermissions";

            ClaudeSession currentSession = host.getSession();
            if (currentSession != null) {
                String sessionMode = currentSession.getPermissionMode();
                if (sessionMode != null && !sessionMode.trim().isEmpty()) {
                    currentMode = sessionMode;
                }
            }

            final String modeToSend = currentMode;

            ApplicationManager.getApplication().invokeLater(() -> {
                if (!host.isDisposed() && host.getBrowser() != null) {
                    host.callJavaScript("window.onModeReceived", JsUtils.escapeJs(modeToSend));
                }
            });
        } catch (Exception e) {
            LOG.error("Failed to send current permission mode: " + e.getMessage(), e);
        }
    }

    /**
     * Reset token usage statistics in the frontend (used after new session creation).
     */
    private void resetTokenUsage() {
        int maxTokens = SettingsHandler.getModelContextLimit(host.getHandlerContext().getCurrentModel());
        JsonObject usageUpdate = new JsonObject();
        usageUpdate.addProperty("percentage", 0);
        usageUpdate.addProperty("totalTokens", 0);
        usageUpdate.addProperty("limit", maxTokens);
        usageUpdate.addProperty("usedTokens", 0);
        usageUpdate.addProperty("maxTokens", maxTokens);

        String usageJson = new Gson().toJson(usageUpdate);

        JBCefBrowser browser = host.getBrowser();
        if (browser != null && !host.isDisposed()) {
            String js = "(function() {" +
                                "  if (typeof window.onUsageUpdate === 'function') {" +
                                "    window.onUsageUpdate('" + JsUtils.escapeJs(usageJson) + "');" +
                                "    console.log('[Backend->Frontend] Usage reset for new session');" +
                                "  } else {" +
                                "    console.warn('[Backend->Frontend] window.onUsageUpdate not found');" +
                                "  }" +
                                "})();";
            browser.getCefBrowser().executeJavaScript(js, browser.getCefBrowser().getURL(), 0);
        }
    }

    private String getCurrentEditorFilePath() {
        return com.github.claudecodegui.util.EditorFileUtils.getCurrentEditorFilePath(this.host.getProject());
    }

    /**
     * 在历史会话恢复成功后，把持久化的自定义标题重新回放到前端。
     * Tab 标题会在工具窗口初始化阶段按 TabState 恢复，但聊天页头部标题依赖前端 customSessionTitle；
     * 因此这里必须按 sessionId 再补一次回放，避免前端退回到首条消息摘要。
     *
     * @param sessionId 已恢复的会话 ID
     */
    private void replayRestoredSessionTitle(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return;
        }

        try {
            String titlesJson = loadPersistedSessionTitlesJson();
            if (titlesJson == null || titlesJson.trim().isEmpty()) {
                return;
            }
            JsonObject titles = new Gson().fromJson(titlesJson, JsonObject.class);
            if (titles == null || !titles.has(sessionId) || !titles.get(sessionId).isJsonObject()) {
                return;
            }

            JsonObject titleInfo = titles.getAsJsonObject(sessionId);
            if (!titleInfo.has("customTitle") || titleInfo.get("customTitle").isJsonNull()) {
                return;
            }

            String customTitle = titleInfo.get("customTitle").getAsString();
            if (customTitle == null || customTitle.trim().isEmpty()) {
                return;
            }

            // 统一按 (sessionId, title) 两参形式回放，匹配前端新的标题同步契约；
            // 避免恢复历史会话时因为旧的一参签名被覆盖而导致标题静默丢失。
            host.callJavaScript(
                    "updateSessionTitle",
                    JsUtils.escapeJs(sessionId),
                    JsUtils.escapeJs(customTitle)
            );
            LOG.info("[HistoryTitleSync] Replayed restored session title to frontend. sessionId="
                    + sessionId + ", title=" + customTitle);
        } catch (Exception e) {
            LOG.warn("[HistoryTitleSync] Failed to replay restored session title: " + e.getMessage(), e);
        }
    }

    /**
     * 读取持久化的 session-titles.json 内容。
     * 默认实现仍然走 NodeJsServiceCaller，单元测试可通过覆写该方法注入稳定测试数据，
     * 从而把测试焦点限制在“标题回放契约”而不是外部 Node 子进程。
     *
     * @return titles JSON 字符串；无数据或上下文不可用时返回 null
     * @throws Exception Node 调用或读取失败时抛出异常
     */
    protected String loadPersistedSessionTitlesJson() throws Exception {
        HandlerContext handlerContext = host.getHandlerContext();
        if (handlerContext == null) {
            return null;
        }
        NodeJsServiceCaller nodeJsServiceCaller = new NodeJsServiceCaller(handlerContext);
        return nodeJsServiceCaller.callNodeJsTitlesService("loadTitles");
    }

    private ClaudeSession createDefaultSession() {
        return new ClaudeSession(host.getProject(), host.getClaudeSDKBridge(), host.getCodexSDKBridge());
    }

    private void primeContinuationMetadata(
            ClaudeSession oldSession,
            ClaudeSession newSession,
            ContinuedSegmentRequest request
    ) {
        String sourceSessionId = firstNonBlank(oldSession.getSessionId());
        String logicalConversationId = firstNonBlank(
                oldSession.getState().getLogicalConversationId(),
                request.logicalConversationId,
                resolveLogicalConversationIdBySessionId(sourceSessionId),
                "logical-" + UUID.randomUUID()
        );

        newSession.getState().setLogicalConversationId(logicalConversationId);
        newSession.getState().setActiveSegmentSessionId(null);
        newSession.getState().setParentSegmentSessionId(sourceSessionId);
        newSession.getState().setContinuationPending(true);
        newSession.getState().setContinuationSourceSessionId(sourceSessionId);
        newSession.getState().setSummary(oldSession.getSummary());
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " primeContinuationMetadata logicalConversationId="
                + logicalConversationId
                + ", sourceSessionId=" + sourceSessionId
                + ", targetProvider=" + firstNonBlank(newSession.getProvider())
                + ", targetModel=" + firstNonBlank(newSession.getModel())
                + ", targetReasoningEffort=" + firstNonBlank(newSession.getReasoningEffort())
                + ", binding=" + describeBinding(newSession.getState().getCodexSessionBinding())
                + ", request=" + (request != null ? request.toLogString() : "(null)"));
    }

    private void syncHandlerRuntimeState(ClaudeSession session) {
        HandlerContext handlerContext = host.getHandlerContext();
        if (handlerContext == null || session == null) {
            return;
        }
        handlerContext.setCurrentProvider(firstNonBlank(session.getProvider(), HandlerContext.DEFAULT_PROVIDER));
        handlerContext.setCurrentModel(firstNonBlank(session.getModel(), HandlerContext.DEFAULT_MODEL));
        handlerContext.requestTabSessionPersistence();
    }

    private String resolveTargetProviderForRuntime(ContinuedSegmentRequest request) {
        if (request == null) {
            return "claude";
        }
        return SessionRuntimeFamily.CODEX.equals(request.targetRuntimeFamily)
                ? SessionRuntimeFamily.CODEX
                : firstNonBlank(request.targetProvider, "claude");
    }

    private CodexSessionBinding buildContinuedSegmentCodexBinding(ContinuedSegmentRequest request) {
        if (request == null || !SessionRuntimeFamily.CODEX.equals(request.targetRuntimeFamily)) {
            return null;
        }
        return buildCodexBindingFromProvider(request.targetCodexProviderId, request.targetModel);
    }

    private CodexSessionBinding buildCodexBindingFromProvider(String providerId, String modelId) {
        if (!hasText(providerId) && !hasText(modelId)) {
            return null;
        }

        try {
            JsonObject provider = createSettingsService().getCodexProviderById(providerId);
            boolean isCliLoginProvider = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)
                    || (provider != null
                    && provider.has("isCodexCliLoginProvider")
                    && !provider.get("isCodexCliLoginProvider").isJsonNull()
                    && provider.get("isCodexCliLoginProvider").getAsBoolean());
            if (isCliLoginProvider) {
                return new CodexSessionBinding(
                        providerId,
                        firstNonBlank(modelId),
                        "codex_sdk",
                        CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN,
                        CodexRuntimeProfile.CONFIG_SOURCE_CLI_LOGIN
                );
            }

            String requestMode = provider != null && provider.has("requestMode") && !provider.get("requestMode").isJsonNull()
                    ? firstNonBlank(provider.get("requestMode").getAsString(), "codex_sdk")
                    : "codex_sdk";
            String baseUrlSource = provider != null
                    && provider.has("baseUrl")
                    && !provider.get("baseUrl").isJsonNull()
                    && hasText(provider.get("baseUrl").getAsString())
                    ? "provider"
                    : "sdk_default";
            return new CodexSessionBinding(
                    firstNonBlank(providerId),
                    firstNonBlank(modelId),
                    requestMode,
                    baseUrlSource,
                    CodexRuntimeProfile.CONFIG_SOURCE_MANAGED_PROVIDER
            );
        } catch (Exception e) {
            LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " buildCodexBindingFromProvider failed: " + e.getMessage(), e);
            return new CodexSessionBinding(firstNonBlank(providerId), firstNonBlank(modelId), "codex_sdk", "sdk_default", "");
        }
    }

    private String resolveLogicalConversationIdBySessionId(String sessionId) {
        if (!hasText(sessionId)) {
            return null;
        }
        try {
            CodemossSettingsService settingsService = createSettingsService();
            ConversationSegmentRecord segmentRecord = settingsService.getConversationSegmentRecord(sessionId);
            if (segmentRecord != null && segmentRecord.isMeaningful()) {
                return segmentRecord.getLogicalConversationId();
            }
        } catch (Exception e) {
            LOG.debug(CODEX_RUNTIME_TRACE_PREFIX + " resolveLogicalConversationIdBySessionId failed: " + e.getMessage());
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * continued segment 请求载荷。
     * 该对象只承载当前最小闭环所需字段，避免第一版实现就提前绑定过重的 carryover 协议。
     */
    protected static final class ContinuedSegmentRequest {
        private final String logicalConversationId;
        private final String sourceSessionId;
        private final String targetProvider;
        private final String targetRuntimeFamily;
        private final String targetModel;
        private final String targetReasoningEffort;
        private final String targetCodexProviderId;
        private final String switchReason;

        private ContinuedSegmentRequest(
                String logicalConversationId,
                String sourceSessionId,
                String targetProvider,
                String targetRuntimeFamily,
                String targetModel,
                String targetReasoningEffort,
                String targetCodexProviderId,
                String switchReason
        ) {
            this.logicalConversationId = firstNonBlank(logicalConversationId);
            this.sourceSessionId = firstNonBlank(sourceSessionId);
            this.targetProvider = firstNonBlank(targetProvider);
            this.targetRuntimeFamily = firstNonBlank(targetRuntimeFamily);
            this.targetModel = firstNonBlank(targetModel);
            this.targetReasoningEffort = firstNonBlank(targetReasoningEffort);
            this.targetCodexProviderId = firstNonBlank(targetCodexProviderId);
            this.switchReason = firstNonBlank(switchReason);
        }

        private static ContinuedSegmentRequest fromJson(String payloadJson) {
            if (!hasText(payloadJson)) {
                return new ContinuedSegmentRequest("", "", "", "", "", "", "", "");
            }
            try {
                JsonObject json = new Gson().fromJson(payloadJson, JsonObject.class);
                if (json == null) {
                    return new ContinuedSegmentRequest("", "", "", "", "", "", "", "");
                }
                return new ContinuedSegmentRequest(
                        readString(json, "logicalConversationId"),
                        readString(json, "sourceSessionId"),
                        readString(json, "targetProvider"),
                        readString(json, "targetRuntimeFamily"),
                        readString(json, "targetModel"),
                        readString(json, "targetReasoningEffort"),
                        readString(json, "targetCodexProviderId"),
                        readString(json, "switchReason")
                );
            } catch (Exception e) {
                LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " failed to parse ContinuedSegmentRequest: " + e.getMessage(), e);
                return new ContinuedSegmentRequest("", "", "", "", "", "", "", "");
            }
        }

        private String toLogString() {
            return "{logicalConversationId=" + logicalConversationId
                    + ", sourceSessionId=" + sourceSessionId
                    + ", targetProvider=" + targetProvider
                    + ", targetRuntimeFamily=" + targetRuntimeFamily
                    + ", targetModel=" + targetModel
                    + ", targetReasoningEffort=" + targetReasoningEffort
                    + ", targetCodexProviderId=" + targetCodexProviderId
                    + ", switchReason=" + switchReason
                    + "}";
        }

        private static String readString(JsonObject json, String key) {
            if (json == null || key == null || !json.has(key) || json.get(key).isJsonNull()) {
                return "";
            }
            return firstNonBlank(json.get(key).getAsString());
        }
    }

    /**
     * 源分段运行时元数据快照。
     * 该对象只服务于继续分段元数据回填，避免在方法内部反复传递多组平行字符串。
     */
    private static final class SourceSegmentRuntimeMetadata {
        private final String provider;
        private final String runtimeFamily;
        private final String model;
        private final String reasoningEffort;

        private SourceSegmentRuntimeMetadata(
                String provider,
                String runtimeFamily,
                String model,
                String reasoningEffort
        ) {
            this.provider = firstNonBlank(provider);
            this.runtimeFamily = firstNonBlank(runtimeFamily);
            this.model = firstNonBlank(model);
            this.reasoningEffort = firstNonBlank(reasoningEffort);
        }
    }

    /**
     * 生成便于日志检索的 Codex binding 摘要。
     *
     * @param binding 待描述的 binding
     * @return 面向日志的摘要字符串
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

    private void completeNewSessionBootstrap(ClaudeSession newSession, String workingDirectory, String successLogPrefix) {
        host.clearPendingPermissionRequests();
        host.clearPermissionDecisionMemory();
        host.setSession(newSession);
        host.getHandlerContext().setSession(newSession);
        host.setupSessionCallbacks();

        newSession.setSessionInfo(null, workingDirectory);
        LOG.info(successLogPrefix + workingDirectory + ", epoch=" + newSession.getRuntimeSessionEpoch());
        host.getClaudeSDKBridge().prewarmDaemonAsync(workingDirectory, newSession.getRuntimeSessionEpoch());
        fetchSlashCommandsOnStartup();

        ApplicationManager.getApplication().invokeLater(() -> {
            // Release the frontend session transition guard so updateMessages works again.
            // Must come BEFORE updateStatus to ensure the guard is lifted before any
            // subsequent message updates arrive.
            host.callJavaScript("historyLoadComplete");
            host.callJavaScript("updateStatus",
                    JsUtils.escapeJs(ClaudeCodeGuiBundle.message("toast.newSessionCreatedReady")));
            resetTokenUsage();
        });
    }

    /**
     * 当底层 provider 回传真实 sessionId/threadId 后，按需补齐 continued segment 元数据。
     * 普通会话不会受到影响；只有 continuationPending=true 的会话才会触发逻辑会话/分段索引写回。
     *
     * @param newSessionId 底层刚回传的真实 sessionId
     */
    public void onSessionIdAssigned(String newSessionId) {
        ClaudeSession session = host.getSession();
        if (session == null || !session.getState().isContinuationPending() || !hasText(newSessionId)) {
            return;
        }

        SessionState state = session.getState();
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " onSessionIdAssigned continuationPending sessionId="
                + firstNonBlank(newSessionId)
                + ", logicalConversationId=" + firstNonBlank(state.getLogicalConversationId())
                + ", sourceSessionId=" + firstNonBlank(state.getContinuationSourceSessionId())
                + ", provider=" + firstNonBlank(session.getProvider())
                + ", runtimeFamily=" + SessionRuntimeFamily.resolve(session.getProvider(), null, state.getCodexSessionBinding())
                + ", model=" + firstNonBlank(session.getModel())
                + ", binding=" + describeBinding(state.getCodexSessionBinding()));
        completeContinuedSegment(
                state.getContinuationSourceSessionId(),
                newSessionId,
                state.getCodexSessionBinding() != null ? state.getCodexSessionBinding().getProviderId() : "",
                session.getProvider(),
                SessionRuntimeFamily.resolve(session.getProvider(), null, state.getCodexSessionBinding()),
                session.getModel(),
                session.getReasoningEffort(),
                "session_id_assigned"
        );
    }
}
