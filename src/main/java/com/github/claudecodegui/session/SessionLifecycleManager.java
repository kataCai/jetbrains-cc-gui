package com.github.claudecodegui.session;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.history.HistoryMessageInjector;
import com.github.claudecodegui.model.SessionTemplate;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
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
import com.github.claudecodegui.util.TokenUsageUtils;
import com.github.claudecodegui.util.UserMessageSanitizer;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.ui.jcef.JBCefBrowser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages session lifecycle operations: creation, history loading,
 * working directory resolution, slash commands, and permission mode sync.
 */
public class SessionLifecycleManager {

    private static final Logger LOG = Logger.getInstance(SessionLifecycleManager.class);
    private static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";
    private static final String HISTORY_RESTORE_KIND_SINGLE_SESSION = "single_session";
    private static final String HISTORY_RESTORE_KIND_LOGICAL_CONVERSATION = "logical_conversation";
    private static final String HISTORY_RESTORE_KIND_RUNTIME_CONTINUE_AUTHORITATIVE =
            "runtime_continue_authoritative";
    private static final String CONTINUATION_CARRYOVER_MODE = "recent_turns_snapshot";
    private static final String CONTINUATION_CARRYOVER_SUMMARY_FALLBACK_MODE = "summary_fallback";
    private static final int CONTINUATION_CARRYOVER_MAX_VISIBLE_MESSAGES = 4;
    private static final int CONTINUATION_CARRYOVER_MAX_MESSAGE_LENGTH = 240;
    private static final long CONTINUED_LOGICAL_REFRESH_RETRY_DELAY_MS = 300L;
    private static final int CONTINUED_LOGICAL_REFRESH_MAX_RETRIES = 5;
    private final Set<String> inFlightHistoryRestoreKeys = ConcurrentHashMap.newKeySet();
    private final AtomicReference<String> lastFinishedHistoryRestoreKey = new AtomicReference<>();

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
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " createContinuedSessionWithRuntimeSwitch session_attached"
                    + ", sessionId=" + firstNonBlank(newSession.getSessionId())
                    + ", logicalConversationId=" + firstNonBlank(newSession.getState().getLogicalConversationId())
                    + ", activeSegmentSessionId=" + firstNonBlank(newSession.getState().getActiveSegmentSessionId())
                    + ", parentSegmentSessionId=" + firstNonBlank(newSession.getState().getParentSegmentSessionId())
                    + ", continuationPending=" + newSession.getState().isContinuationPending()
                    + ", continuationSourceSessionId=" + firstNonBlank(newSession.getState().getContinuationSourceSessionId())
                    + ", provider=" + firstNonBlank(newSession.getProvider())
                    + ", runtimeFamily=" + SessionRuntimeFamily.resolve(
                    newSession.getProvider(),
                    null,
                    newSession.getState().getCodexSessionBinding()
            )
                    + ", model=" + firstNonBlank(newSession.getModel())
                    + ", binding=" + describeBinding(newSession.getState().getCodexSessionBinding()));

            newSession.setSessionInfo(null, workingDirectory);
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " createContinuedSessionWithRuntimeSwitch session_initialized"
                    + ", sessionId=" + firstNonBlank(newSession.getSessionId())
                    + ", logicalConversationId=" + firstNonBlank(newSession.getState().getLogicalConversationId())
                    + ", activeSegmentSessionId=" + firstNonBlank(newSession.getState().getActiveSegmentSessionId())
                    + ", parentSegmentSessionId=" + firstNonBlank(newSession.getState().getParentSegmentSessionId())
                    + ", continuationPending=" + newSession.getState().isContinuationPending()
                    + ", continuationSourceSessionId=" + firstNonBlank(newSession.getState().getContinuationSourceSessionId())
                    + ", cwd=" + firstNonBlank(newSession.getCwd()));
            fetchSlashCommandsOnStartup();

            ApplicationManager.getApplication().invokeLater(this::resetTokenUsage);
        }).exceptionally(ex -> {
            LOG.error("Failed to create continued session: " + ex.getMessage(), ex);
            ApplicationManager.getApplication().invokeLater(() -> {
                rollbackFailedContinuedSessionCreation(oldSession, host.getSession(), ex.getMessage());
            });
            return null;
        });
    }

    /**
     * 在 continued 新分段创建失败后，回滚插件与前端状态到旧会话。
     * 这里必须同时恢复 host/handlerContext 当前会话，并显式通知前端清理 continued pending/cache，
     * 否则当前标签页会继续停留在“continued 尚未就绪”的错误状态，后续发送也会被持续拦截。
     *
     * @param previousSession continued 创建前的旧会话
     * @param failedSession 已经挂到 host 上但随后初始化失败的新会话
     * @param errorMessage 失败原因
     */
    protected void rollbackFailedContinuedSessionCreation(
            ClaudeSession previousSession,
            ClaudeSession failedSession,
            String errorMessage
    ) {
        boolean shouldRestorePreviousSession = previousSession != null
                && failedSession != null
                && host.getSession() == failedSession;

        if (shouldRestorePreviousSession) {
            host.setSession(previousSession);
            if (host.getHandlerContext() != null) {
                host.getHandlerContext().setSession(previousSession);
            }
            syncHandlerRuntimeState(previousSession);
            host.setupSessionCallbacks();
            host.callJavaScript(
                    "window.abortContinuedSegmentTransition",
                    JsUtils.escapeJs(previousSession.getSessionId())
            );
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " rollbackFailedContinuedSessionCreation restored previousSessionId="
                    + firstNonBlank(previousSession.getSessionId())
                    + ", failedSessionId=" + firstNonBlank(failedSession.getSessionId())
                    + ", logicalConversationId="
                    + firstNonBlank(previousSession.getState().getLogicalConversationId()));
        } else {
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " rollbackFailedContinuedSessionCreation skipped_restore"
                    + ", previousSessionId=" + firstNonBlank(previousSession != null ? previousSession.getSessionId() : null)
                    + ", failedSessionId=" + firstNonBlank(failedSession != null ? failedSession.getSessionId() : null)
                    + ", currentHostSessionId=" + firstNonBlank(host.getSession() != null ? host.getSession().getSessionId() : null));
        }

        host.callJavaScript("historyLoadComplete");
        host.callJavaScript(
                "updateStatus",
                JsUtils.escapeJs("Failed to continue session: " + firstNonBlank(errorMessage, "unknown error"))
        );
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
        String restoreRequestKey = tryAcquireHistoryRestoreRequest(sessionId, restoreSource, transitionToken);
        if (restoreRequestKey == null) {
            LOG.info("[HistoryRestore] Skip duplicate restore request, sessionId=" + sessionId
                    + ", restoreSource=" + restoreSource
                    + ", transitionToken=" + transitionToken);
            return;
        }
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

            CompletableFuture<Void> loadFuture = SessionRuntimeFamily.CODEX.equals(resolvedRuntimeFamily)
                    ? loadCodexHistorySession(newSession, sessionId, resolvedProvider, restoreSource, transitionToken)
                    : newSession.loadFromServer();

            loadFuture.handle((unused, ex) -> {
                finishHistoryRestoreRequest(restoreRequestKey);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (shouldSkipHistoryRestoreForInactiveSession(newSession)) {
                        LOG.info("[HistoryRestore] Skip stale history restore completion callback, sessionId="
                                + firstNonBlank(sessionId)
                                + ", restoreSource=" + firstNonBlank(restoreSource));
                        return;
                    }
                    if (ex == null) {
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
                        return;
                    }

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
                    // 释放切换保护，避免前端因为历史恢复失败而永久卡住。
                    host.callJavaScript("historyLoadComplete");
                    host.callJavaScript("addErrorMessage",
                            JsUtils.escapeJs("Failed to load session: " + ex.getMessage()));
                });
                return null;
            });
        }).exceptionally(ex -> {
            finishHistoryRestoreRequest(restoreRequestKey);
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
     * 以 Codex 增强恢复链路加载历史会话。
     * 通用 `loadFromServer()` 只会把服务端快照交给通用消息解析器，无法恢复 `local_images` 对应的真实图片语义，
     * 也无法避免占位文本快照与增强图片快照的双重注入。因此 Codex 历史恢复必须在这里统一走增强链路。
     *
     * @param session 当前待恢复的会话对象
     * @param sessionId 历史会话 ID
     * @param provider 展示 provider
     * @param restoreSource 恢复来源
     * @param transitionToken 前端切换令牌
     * @return 异步加载任务
     */
    private CompletableFuture<Void> loadCodexHistorySession(
            ClaudeSession session,
            String sessionId,
            String provider,
            String restoreSource,
            String transitionToken
    ) {
        return CompletableFuture.runAsync(() -> {
            try {
                CodexHistoryReader codexReader = createCodexHistoryReader();
                String messagesJson = codexReader.getSessionMessagesAsJson(sessionId);
                JsonArray messages = new Gson().fromJson(messagesJson, JsonArray.class);
                if (messages == null) {
                    messages = new JsonArray();
                }

                String[] sessionMeta = HistoryMessageInjector.extractCodexSessionMeta(messages);
                String actualThreadId = sessionMeta[0] != null ? sessionMeta[0] : sessionId;
                String resolvedCwd = sessionMeta[1];
                if (resolvedCwd == null || resolvedCwd.trim().isEmpty()) {
                    resolvedCwd = session.getCwd();
                }

                if (shouldSkipHistoryRestoreForInactiveSession(session)) {
                    LOG.info("[HistoryRestore] Skip stale Codex history restore before applying snapshot, sessionId="
                            + actualThreadId + ", restoreSource=" + firstNonBlank(restoreSource));
                    return;
                }

                session.setSessionInfo(actualThreadId, resolvedCwd);
                restoreCodexSessionBindingIfPresent(session, actualThreadId);
                HistoryMessageInjector.restoreCodexMessagesToSessionState(session.getState(), messages);
                pushCodexHistoryMessagesToFrontend(
                        session,
                        messages,
                        buildHistoryRestoreRequestKey(actualThreadId, restoreSource, transitionToken)
                );
                if (!shouldSkipHistoryRestoreForInactiveSession(session)) {
                    restoreCodexHistoryTokenUsage(session, messages);
                }

                LOG.info(TabSessionRestoreDebugTrace.buildMessage(
                        "history_restore_codex_enhanced_completed",
                        -1,
                        actualThreadId,
                        provider,
                        SessionRuntimeFamily.CODEX,
                        restoreSource,
                        false,
                        transitionToken
                ) + ", messageCount=" + messages.size());
            } catch (Exception e) {
                session.getState().setError(e.getMessage());
                throw new RuntimeException("Failed to load Codex history session: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 把 Codex 增强恢复后的最终消息快照推送到前端。
     * 这里只保留单一的 `clearMessages -> updateMessages` 注入路径，
     * 避免同一历史恢复周期内再出现第二条占位文本快照覆盖增强消息的问题。
     *
     * @param messages Codex 原始历史消息数组
     */
    private void pushCodexHistoryMessagesToFrontend(
            ClaudeSession expectedSession,
            JsonArray messages,
            String restoreRequestKey
    ) {
        List<JsonObject> frontendMessages = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);
        pushFrontendMessagesToFrontendIfSessionCurrent(
                expectedSession,
                frontendMessages,
                restoreRequestKey,
                messages.size(),
                HISTORY_RESTORE_KIND_SINGLE_SESSION
        );
    }

    /**
     * 把已经构造完成的前端消息快照推送到当前窗口。
     * 统一保留 `prepareHistoryRestoreSnapshot -> clearMessages -> updateMessages` 注入顺序，
     * 避免运行时 continued 回刷与历史恢复再次出现两套不同的前端重放语义。
     *
     * @param frontendMessages 供前端直接渲染的消息快照
     * @param restoreRequestKey 本轮快照对应的稳定 restore key
     * @param rawMessageCount 用于日志观测的原始消息数
     */
    private void pushFrontendMessagesToFrontend(
            List<JsonObject> frontendMessages,
            String restoreRequestKey,
            int rawMessageCount,
            String restoreKind
    ) {
        String payload = JsUtils.escapeJs(new Gson().toJson(frontendMessages));
        String rawSnapshotSignature = HistoryMessageInjector.buildFrontendSnapshotSignature(frontendMessages);
        String snapshotSignature = JsUtils.escapeJs(rawSnapshotSignature);
        String escapedRestoreRequestKey = JsUtils.escapeJs(restoreRequestKey);
        String normalizedRestoreKind = hasText(restoreKind) ? restoreKind : HISTORY_RESTORE_KIND_SINGLE_SESSION;
        String escapedRestoreKind = JsUtils.escapeJs(normalizedRestoreKind);
        LOG.info("[HistoryRestore] Push Codex history snapshot to frontend: restoreRequestKey="
                + restoreRequestKey
                + ", snapshotSignature=" + rawSnapshotSignature
                + ", restoreKind=" + normalizedRestoreKind
                + ", frontendMessageCount=" + frontendMessages.size()
                + ", rawMessageCount=" + rawMessageCount);
        Runnable pushSnapshot = () -> {
            host.callJavaScript(
                    "prepareHistoryRestoreSnapshot",
                    escapedRestoreRequestKey,
                    snapshotSignature,
                    escapedRestoreKind
            );
            host.callJavaScript("clearMessages");
            host.callJavaScript("updateMessages", payload);
        };
        if (ApplicationManager.getApplication() == null || ApplicationManager.getApplication().isUnitTestMode()) {
            pushSnapshot.run();
            return;
        }
        ApplicationManager.getApplication().invokeLater(pushSnapshot);
    }

    /**
     * 仅当当前 host 仍然指向同一个会话实例时，才允许把历史恢复或 continued 聚合快照推给前端。
     * 这样可以阻止“旧 startup restore 晚到后覆盖当前新会话”以及“旧 continued 重试任务晚到后污染新分段”的竞态。
     *
     * @param expectedSession 发起本次恢复或回刷的目标会话实例
     * @param frontendMessages 供前端渲染的消息快照
     * @param restoreRequestKey 本轮 restore key
     * @param rawMessageCount 原始消息数量
     */
    protected void pushFrontendMessagesToFrontendIfSessionCurrent(
            ClaudeSession expectedSession,
            List<JsonObject> frontendMessages,
            String restoreRequestKey,
            int rawMessageCount,
            String restoreKind
    ) {
        if (shouldSkipHistoryRestoreForInactiveSession(expectedSession)) {
            LOG.info("[HistoryRestore] Skip stale frontend snapshot push: restoreRequestKey="
                    + restoreRequestKey + ", rawMessageCount=" + rawMessageCount);
            return;
        }
        pushFrontendMessagesToFrontend(frontendMessages, restoreRequestKey, rawMessageCount, restoreKind);
    }

    /**
     * 判断某个历史恢复或 continued 回刷任务是否已经指向了过期会话实例。
     * 这里必须按对象实例判断，而不是只看 sessionId，因为 startup restore 过程中用户可能已经在同一窗口创建了全新空会话。
     *
     * @param expectedSession 发起恢复或回刷的目标会话实例
     * @return true 表示当前任务已经过期，不能再继续向前端回放
     */
    protected boolean shouldSkipHistoryRestoreForInactiveSession(ClaudeSession expectedSession) {
        return expectedSession != null && host.getSession() != expectedSession;
    }

    /**
     * 构造历史恢复请求幂等 key。
     * 该 key 由 `sessionId + restoreSource + transitionToken` 组成，用于把同一轮恢复链路里的重复请求
     * 归并到同一个逻辑恢复周期内，避免重复打断会话和重复回放历史快照。
     *
     * @param sessionId 会话 ID
     * @param restoreSource 恢复来源
     * @param transitionToken 前端切换令牌
     * @return 稳定的历史恢复请求 key
     */
    static String buildHistoryRestoreRequestKey(String sessionId, String restoreSource, String transitionToken) {
        String normalizedSessionId = sessionId == null || sessionId.trim().isEmpty() ? "(unknown)" : sessionId.trim();
        String normalizedRestoreSource = restoreSource == null || restoreSource.trim().isEmpty()
                ? "history_switch"
                : restoreSource.trim();
        String normalizedTransitionToken = transitionToken == null || transitionToken.trim().isEmpty()
                ? "(none)"
                : transitionToken.trim();
        return normalizedSessionId + "|" + normalizedRestoreSource + "|" + normalizedTransitionToken;
    }

    /**
     * 申请当前历史恢复周期的幂等 key。
     * 如果同一个 key 已经在执行中，或刚刚完成过一次，则直接拒绝后续重复请求。
     *
     * @param sessionId 会话 ID
     * @param restoreSource 恢复来源
     * @param transitionToken 前端切换令牌
     * @return 可受理时返回 restore key；重复请求返回 `null`
     */
    protected String tryAcquireHistoryRestoreRequest(String sessionId, String restoreSource, String transitionToken) {
        String restoreRequestKey = buildHistoryRestoreRequestKey(sessionId, restoreSource, transitionToken);
        if (restoreRequestKey.equals(lastFinishedHistoryRestoreKey.get())) {
            return null;
        }
        return inFlightHistoryRestoreKeys.add(restoreRequestKey) ? restoreRequestKey : null;
    }

    /**
     * 标记一轮历史恢复周期结束。
     * 无论恢复成功还是失败，都必须释放 in-flight 标记，并记录最后一次完成的 restore key，
     * 以便抑制同一周期内的尾随重复请求。
     *
     * @param restoreRequestKey 已完成的恢复 key
     */
    protected void finishHistoryRestoreRequest(String restoreRequestKey) {
        if (restoreRequestKey == null || restoreRequestKey.trim().isEmpty()) {
            return;
        }
        inFlightHistoryRestoreKeys.remove(restoreRequestKey);
        lastFinishedHistoryRestoreKey.set(restoreRequestKey);
    }

    /**
     * 恢复 Codex 历史会话的 token usage 展示。
     * 由于 Codex 历史不再走通用 `loadFromServer()`，这里需要把状态栏和前端 usage 回调手动补齐，
     * 避免历史恢复后 usage 信息回退为 0。
     *
     * @param session 当前历史会话
     * @param messages Codex 原始历史消息数组
     */
    private void restoreCodexHistoryTokenUsage(ClaudeSession session, JsonArray messages) {
        List<JsonObject> rawMessages = new java.util.ArrayList<>();
        messages.forEach(element -> {
            if (element != null && element.isJsonObject()) {
                rawMessages.add(element.getAsJsonObject());
            }
        });

        JsonObject lastUsage = TokenUsageUtils.findLastUsageFromRawMessages(rawMessages);
        if (lastUsage == null) {
            return;
        }

        int usedTokens = TokenUsageUtils.extractUsedTokens(lastUsage, session.getProvider());
        int maxTokens = SettingsHandler.getModelContextLimit(session.getModel());
        ClaudeNotifier.setTokenUsage(host.getProject(), usedTokens, maxTokens);

        ApplicationManager.getApplication().invokeLater(() -> {
            double percentage = maxTokens > 0 ? (usedTokens * 100.0 / maxTokens) : 0.0;
            String json = String.format("{\"percentage\":%.2f,\"usedTokens\":%d,\"maxTokens\":%d}",
                    percentage,
                    usedTokens,
                    maxTokens);
            host.callJavaScript("onUsageUpdate", JsUtils.escapeJs(json));
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
     * 创建 Codex 历史读取器。
     * 单独保留工厂方法，便于单元测试替换历史来源，而不影响生产链路的真实会话读取。
     *
     * @return Codex 历史读取器
     */
    protected CodexHistoryReader createCodexHistoryReader() {
        return new CodexHistoryReader();
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
        String carryoverMode = resolveContinuationCarryoverMode(state);

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
                        carryoverMode,
                        previousLogicalRecord != null && previousLogicalRecord.isMeaningful()
                                ? previousLogicalRecord.getCreatedAt()
                                : now,
                        sourceMetadata.codexProviderId,
                        sourceMetadata.providerDisplayName
                ));
                existingSegments = settingsService.listConversationSegments(logicalConversationId);
                nextSegmentIndex = existingSegments.size();
            }

            String targetCodexProviderDisplayName = resolveCodexProviderDisplayName(settingsService, targetCodexProviderId);
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
                    carryoverMode,
                    now,
                    firstNonBlank(targetCodexProviderId),
                    targetCodexProviderDisplayName
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
        state.setContinuationCarryoverText(null);

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
        refreshContinuedLogicalConversationMessages(
                newSessionId,
                logicalConversationId,
                newSessionId,
                session
        );
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " completeContinuedSegment applied logicalConversationId="
                + firstNonBlank(state.getLogicalConversationId())
                + ", activeSegmentSessionId=" + firstNonBlank(state.getActiveSegmentSessionId())
                + ", parentSegmentSessionId=" + firstNonBlank(state.getParentSegmentSessionId())
                + ", continuationPending=" + state.isContinuationPending()
                + ", carryoverMode=" + carryoverMode
                + ", provider=" + firstNonBlank(session.getProvider())
                + ", runtimeFamily=" + SessionRuntimeFamily.resolve(session.getProvider(), null, state.getCodexSessionBinding())
                + ", model=" + firstNonBlank(session.getModel())
                + ", binding=" + describeBinding(state.getCodexSessionBinding()));

        syncHandlerRuntimeState(session);
    }

    /**
     * 构建 continued 收口后需要回刷到前端的逻辑会话聚合消息。
     * 默认实现复用 HistoryMessageInjector 的逻辑会话聚合能力；测试可按需覆盖该入口隔离文件系统依赖。
     *
     * @param requestedSessionId 本轮回刷对应的最新 sessionId
     * @param logicalConversationId 所属逻辑会话 id
     * @param activeSegmentSessionId 当前活动分段 sessionId
     * @return 可直接写回 SessionState 并推送前端的聚合消息快照
     */
    protected List<JsonObject> buildContinuedLogicalConversationFrontendMessages(
            String requestedSessionId,
            String logicalConversationId,
            String activeSegmentSessionId
    ) {
        return HistoryMessageInjector.buildCodexLogicalConversationFrontendBatch(
                requestedSessionId,
                logicalConversationId,
                activeSegmentSessionId,
                createSettingsService(),
                createCodexHistoryReader()
        );
    }

    /**
     * 在 continued segment 元数据收口后，按逻辑会话维度回刷聚合消息。
     * 这样运行时 continued 与历史恢复将共享同一套“多分段聚合 + 边界提示”的展示语义，
     * 避免新物理 session 的局部快照把旧消息整段覆盖掉。
     *
     * @param requestedSessionId 本轮回刷对应的最新 sessionId
     * @param logicalConversationId 所属逻辑会话 id
     * @param activeSegmentSessionId 当前活动分段 sessionId
     * @param session 当前会话对象
     */
    private void refreshContinuedLogicalConversationMessages(
            String requestedSessionId,
            String logicalConversationId,
            String activeSegmentSessionId,
            ClaudeSession session
    ) {
        refreshContinuedLogicalConversationMessages(
                requestedSessionId,
                logicalConversationId,
                activeSegmentSessionId,
                session,
                0
        );
    }

    /**
     * 按 attempt 序号执行一次 continued 聚合回刷。
     * 如果新分段历史中尚未出现任何可见 user 消息，说明该分段还没有达到“可安全覆盖前端快照”的时机，
     * 此时只能跳过本轮覆盖式回刷并短延迟重试，避免把前端 optimistic user message 抹掉。
     *
     * @param requestedSessionId 本轮回刷对应的最新 sessionId
     * @param logicalConversationId 所属逻辑会话 id
     * @param activeSegmentSessionId 当前活动分段 sessionId
     * @param session 当前会话对象
     * @param attempt 当前尝试次数，从 0 开始
     */
    /**
     * 在流式回复结束或 send_complete 收口后，按当前活动会话状态尝试补一轮 continued 逻辑会话回刷。
     * 这个入口专门处理“首次 continued 收口时历史尚未可见，但稍后历史文件已经完整落盘”的晚到场景，
     * 避免前一次 defer 结束后，最终 assistant 结果再也没有机会回刷到当前逻辑会话视图。
     */
    public void refreshActiveContinuedLogicalConversationMessagesIfNeeded() {
        refreshActiveContinuedLogicalConversationMessagesIfNeeded(host.getSession());
    }

    /**
     * 根据给定会话的 continued 元数据决定是否需要执行补偿式逻辑回刷。
     * 仅当 continued 已完成绑定，且逻辑会话 id、父分段 id、活动分段 id 都完整时才触发；
     * 对普通会话、仍处于 continuationPending 的过渡态，以及缺失关键标识的脏状态一律直接跳过。
     *
     * @param session 当前准备执行补偿回刷的会话实例
     */
    protected void refreshActiveContinuedLogicalConversationMessagesIfNeeded(ClaudeSession session) {
        if (session == null) {
            return;
        }
        SessionState state = session.getState();
        if (state == null || state.isContinuationPending()) {
            return;
        }
        String logicalConversationId = firstNonBlank(state.getLogicalConversationId());
        String activeSegmentSessionId = firstNonBlank(state.getActiveSegmentSessionId());
        String parentSegmentSessionId = firstNonBlank(state.getParentSegmentSessionId());
        if (!hasText(logicalConversationId) || !hasText(activeSegmentSessionId) || !hasText(parentSegmentSessionId)) {
            return;
        }
        refreshContinuedLogicalConversationMessages(
                activeSegmentSessionId,
                logicalConversationId,
                activeSegmentSessionId,
                session
        );
    }

    private void refreshContinuedLogicalConversationMessages(
            String requestedSessionId,
            String logicalConversationId,
            String activeSegmentSessionId,
            ClaudeSession session,
            int attempt
    ) {
        if (session == null || !hasText(logicalConversationId) || !hasText(activeSegmentSessionId)) {
            return;
        }
        try {
            List<JsonObject> activeSegmentFrontendMessages =
                    loadVisibleFrontendMessagesFromSession(activeSegmentSessionId);
            if (!containsVisibleUserMessage(activeSegmentFrontendMessages)) {
                LOG.info(CODEX_RUNTIME_TRACE_PREFIX
                        + " refreshContinuedLogicalConversationMessages deferred active segment not ready"
                        + ", logicalConversationId=" + firstNonBlank(logicalConversationId)
                        + ", activeSegmentSessionId=" + firstNonBlank(activeSegmentSessionId)
                        + ", requestedSessionId=" + firstNonBlank(requestedSessionId)
                        + ", attempt=" + attempt);
                scheduleContinuedLogicalConversationRefreshRetry(
                        requestedSessionId,
                        logicalConversationId,
                        activeSegmentSessionId,
                        session,
                        attempt
                );
                return;
            }
            List<JsonObject> frontendMessages = buildContinuedLogicalConversationFrontendMessages(
                    requestedSessionId,
                    logicalConversationId,
                    activeSegmentSessionId
            );
            if (frontendMessages == null || frontendMessages.isEmpty()) {
                LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " refreshContinuedLogicalConversationMessages skipped empty snapshot"
                        + ", logicalConversationId=" + firstNonBlank(logicalConversationId)
                        + ", activeSegmentSessionId=" + firstNonBlank(activeSegmentSessionId));
                return;
            }
            HistoryMessageInjector.restoreCodexMessagesToSessionState(session.getState(), frontendMessages);
            pushFrontendMessagesToFrontendIfSessionCurrent(
                    session,
                    frontendMessages,
                    buildHistoryRestoreRequestKey(activeSegmentSessionId, "runtime_continue", null),
                    frontendMessages.size(),
                    HISTORY_RESTORE_KIND_RUNTIME_CONTINUE_AUTHORITATIVE
            );
        } catch (Exception e) {
            LOG.warn(CODEX_RUNTIME_TRACE_PREFIX + " refreshContinuedLogicalConversationMessages failed: "
                    + e.getMessage(), e);
        }
    }

    /**
     * 读取指定分段当前已经可见的前端消息。
     * 这里只关心“是否已经出现真实 user 消息”，不直接把结果推给前端；
     * 目的是在 continued 收口后先判断新分段历史是否足够完整，再决定是否执行覆盖式聚合回刷。
     *
     * @param sessionId 目标物理分段 sessionId
     * @return 转换后的可见前端消息列表；读取失败或尚未可见时返回空列表
     */
    protected List<JsonObject> loadVisibleFrontendMessagesFromSession(String sessionId) {
        if (!hasText(sessionId)) {
            return java.util.Collections.emptyList();
        }
        try {
            String messagesJson = createCodexHistoryReader().getSessionMessagesAsJson(sessionId);
            JsonArray messages = new Gson().fromJson(messagesJson, JsonArray.class);
            if (messages == null) {
                return java.util.Collections.emptyList();
            }
            return HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);
        } catch (Exception e) {
            LOG.debug(CODEX_RUNTIME_TRACE_PREFIX + " loadVisibleFrontendMessagesFromSession failed: " + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 判断一批前端消息中是否已经出现真实 user 消息。
     * continued 首次继续提问场景下，只有看到了新分段 user turn，才允许后端用聚合快照覆盖当前前端列表；
     * 否则该聚合快照大概率还缺少最新用户追问，会直接触发“用户可见记录丢失”。
     *
     * @param frontendMessages 待判断的前端消息列表
     * @return 至少包含一条 user 消息时返回 true
     */
    protected boolean containsVisibleUserMessage(List<JsonObject> frontendMessages) {
        if (frontendMessages == null || frontendMessages.isEmpty()) {
            return false;
        }
        for (JsonObject frontendMessage : frontendMessages) {
            if (frontendMessage == null || !frontendMessage.has("type")) {
                continue;
            }
            if ("user".equals(firstNonBlank(frontendMessage.get("type").getAsString()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为未就绪的新分段安排短延迟重试。
     * 生产环境需要在历史文件稍后落盘后重新补齐 continued 边界提示；但单元测试不应引入异步重试噪音，
     * 因此测试模式下只记录 defer 行为，不自动调度下一次尝试。
     *
     * @param requestedSessionId 本轮回刷对应的最新 sessionId
     * @param logicalConversationId 所属逻辑会话 id
     * @param activeSegmentSessionId 当前活动分段 sessionId
     * @param session 当前会话对象
     * @param attempt 当前尝试次数
     */
    protected void scheduleContinuedLogicalConversationRefreshRetry(
            String requestedSessionId,
            String logicalConversationId,
            String activeSegmentSessionId,
            ClaudeSession session,
            int attempt
    ) {
        Application application = ApplicationManager.getApplication();
        if (attempt >= CONTINUED_LOGICAL_REFRESH_MAX_RETRIES) {
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX
                    + " refreshContinuedLogicalConversationMessages gave up waiting for active segment"
                    + ", logicalConversationId=" + firstNonBlank(logicalConversationId)
                    + ", activeSegmentSessionId=" + firstNonBlank(activeSegmentSessionId)
                    + ", attempt=" + attempt);
            return;
        }
        if (application == null || application.isUnitTestMode()) {
            return;
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
            if (host.isDisposed()) {
                return;
            }
            refreshContinuedLogicalConversationMessages(
                    requestedSessionId,
                    logicalConversationId,
                    activeSegmentSessionId,
                    session,
                    attempt + 1
            );
        }, CONTINUED_LOGICAL_REFRESH_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
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
                        firstNonBlank(sourceSegmentRecord.getReasoningEffort()),
                        firstNonBlank(sourceSegmentRecord.getCodexProviderId()),
                        firstNonBlank(sourceSegmentRecord.getProviderDisplayName())
                );
            }
        } catch (Exception e) {
            LOG.debug(CODEX_RUNTIME_TRACE_PREFIX + " resolveSourceSegmentRuntimeMetadata segment lookup failed: " + e.getMessage());
        }

        String sourceCodexProviderId = currentSession != null && currentSession.getState().getCodexSessionBinding() != null
                ? firstNonBlank(currentSession.getState().getCodexSessionBinding().getProviderId())
                : "";
        String sourceProviderDisplayName = resolveCodexProviderDisplayName(settingsService, sourceCodexProviderId);
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
                    currentSession != null ? firstNonBlank(currentSession.getReasoningEffort()) : "",
                    sourceCodexProviderId,
                    sourceProviderDisplayName
            );
        }

        if (currentSession == null) {
            return new SourceSegmentRuntimeMetadata("", "", "", "", "", "");
        }
        return new SourceSegmentRuntimeMetadata(
                firstNonBlank(currentSession.getProvider()),
                firstNonBlank(SessionRuntimeFamily.resolve(
                        currentSession.getProvider(),
                        null,
                        currentSession.getState().getCodexSessionBinding()
                )),
                firstNonBlank(currentSession.getModel()),
                firstNonBlank(currentSession.getReasoningEffort()),
                sourceCodexProviderId,
                sourceProviderDisplayName
        );
    }

    /**
     * 解析 Codex provider 的人类可读展示名。
     * 该方法只作为历史边界提示的辅助信息来源；若配置缺失或 provider 已删除，则允许回退为空并最终展示 providerId。
     *
     * @param settingsService 配置服务
     * @param providerId 目标 Codex provider id
     * @return provider 展示名；未命中时返回空串
     */
    private String resolveCodexProviderDisplayName(CodemossSettingsService settingsService, String providerId) {
        if (!hasText(providerId) || settingsService == null) {
            return "";
        }
        try {
            JsonObject provider = settingsService.getCodexProviderById(providerId);
            if (provider == null) {
                return "";
            }
            if (provider.has("name") && !provider.get("name").isJsonNull()) {
                return firstNonBlank(provider.get("name").getAsString());
            }
        } catch (Exception e) {
            LOG.debug(CODEX_RUNTIME_TRACE_PREFIX + " resolveCodexProviderDisplayName failed: " + e.getMessage());
        }
        return "";
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

    /**
     * 为新建 continued segment 预填最小但足够准确的续接元数据。
     * 这里除了复制逻辑会话标识和来源分段信息，还会从旧会话的最近可见消息中提取一段 carryover 快照，
     * 供新 runtime 在首条发送前准确接住最新上下文，而不是退回到首轮摘要。
     *
     * @param oldSession 继续链路中的来源会话
     * @param newSession 待初始化的目标会话
     * @param request 前端发起继续操作时携带的目标 runtime 请求
     */
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
        String continuationCarryoverText = buildContinuationCarryoverText(oldSession);

        newSession.getState().setLogicalConversationId(logicalConversationId);
        newSession.getState().setActiveSegmentSessionId(null);
        newSession.getState().setParentSegmentSessionId(sourceSessionId);
        newSession.getState().setContinuationPending(true);
        newSession.getState().setContinuationSourceSessionId(sourceSessionId);
        newSession.getState().setSummary(oldSession.getSummary());
        newSession.getState().setContinuationCarryoverText(continuationCarryoverText);
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " primeContinuationMetadata logicalConversationId="
                + logicalConversationId
                + ", sourceSessionId=" + sourceSessionId
                + ", carryoverMode=" + resolveContinuationCarryoverMode(newSession.getState())
                + ", carryoverPreview=" + firstNonBlank(continuationCarryoverText)
                + ", targetProvider=" + firstNonBlank(newSession.getProvider())
                + ", targetModel=" + firstNonBlank(newSession.getModel())
                + ", targetReasoningEffort=" + firstNonBlank(newSession.getReasoningEffort())
                + ", binding=" + describeBinding(newSession.getState().getCodexSessionBinding())
                + ", request=" + (request != null ? request.toLogString() : "(null)"));
    }

    /**
     * 从来源会话的最近可见消息中构建 continued 首发所需的 carryover 快照。
     * 优先保留最后几条用户/助手消息，确保后续多次继续时始终衔接最近轮次；若当前消息为空，则回退到旧的 summary。
     *
     * @param sourceSession 作为上下文来源的旧会话
     * @return 可直接注入首条 prompt 的最近对话快照；若没有可用消息则返回 summary 回退文本或空串
     */
    protected String buildContinuationCarryoverText(ClaudeSession sourceSession) {
        if (sourceSession == null) {
            return "";
        }
        return buildContinuationCarryoverText(
                sourceSession.getState().getMessages(),
                sourceSession.getSummary()
        );
    }

    /**
     * 基于消息列表生成最近轮次快照文本。
     * 只保留用户与助手的可见文本消息，并对内容做压缩裁剪，避免把完整历史整段塞回首条 carryover prompt。
     *
     * @param messages 来源会话当前可见的消息列表
     * @param fallbackSummary 当消息列表为空时可退回的摘要文本
     * @return 规范化后的最近对话快照；若无可用消息则返回摘要回退文本或空串
     */
    private String buildContinuationCarryoverText(List<ClaudeSession.Message> messages, String fallbackSummary) {
        List<ClaudeSession.Message> eligibleMessages = new ArrayList<>();
        if (messages != null) {
            for (ClaudeSession.Message message : messages) {
                if (isContinuationCarryoverEligible(message)) {
                    eligibleMessages.add(message);
                }
            }
        }

        if (eligibleMessages.isEmpty()) {
            return firstNonBlank(fallbackSummary);
        }

        int startIndex = Math.max(0, eligibleMessages.size() - CONTINUATION_CARRYOVER_MAX_VISIBLE_MESSAGES);
        StringBuilder carryoverBuilder = new StringBuilder();
        for (int index = startIndex; index < eligibleMessages.size(); index++) {
            String formattedMessage = formatContinuationCarryoverMessage(eligibleMessages.get(index));
            if (!hasText(formattedMessage)) {
                continue;
            }
            if (carryoverBuilder.length() > 0) {
                carryoverBuilder.append("\n");
            }
            carryoverBuilder.append(formattedMessage);
        }
        return hasText(carryoverBuilder.toString()) ? carryoverBuilder.toString() : firstNonBlank(fallbackSummary);
    }

    /**
     * 判断一条消息是否适合作为 continued carryover 快照的一部分。
     * 过滤系统消息、错误消息、空白内容、工具结果占位文本以及已被历史恢复污染出来的 synthetic continued user message，
     * 尽量只保留用户真正看到、且适合继续拼接到下一次 carryoverPreview 的自然语言上下文。
     *
     * @param message 待判断的消息
     * @return true 表示该消息可参与最近轮次快照构建
     */
    private boolean isContinuationCarryoverEligible(ClaudeSession.Message message) {
        if (message == null) {
            return false;
        }
        if (message.type != ClaudeSession.Message.Type.USER && message.type != ClaudeSession.Message.Type.ASSISTANT) {
            return false;
        }
        if (message.type == ClaudeSession.Message.Type.USER
                && UserMessageSanitizer.isSyntheticContinuationCarryoverMessage(message.content)) {
            return false;
        }
        String normalizedContent = normalizeContinuationCarryoverContent(message.content);
        return hasText(normalizedContent) && !"[tool_result]".equals(normalizedContent);
    }

    /**
     * 将单条消息格式化为 carryover 快照中的稳定文本行。
     * 统一使用 `User:` / `Assistant:` 前缀，便于新 runtime 快速理解最后几轮对话角色与顺序。
     *
     * @param message 已通过筛选的消息
     * @return 单行格式化文本；若消息内容不可用则返回空串
     */
    private String formatContinuationCarryoverMessage(ClaudeSession.Message message) {
        if (message == null) {
            return "";
        }
        String normalizedContent = normalizeContinuationCarryoverContent(message.content);
        if (!hasText(normalizedContent)) {
            return "";
        }
        String role = message.type == ClaudeSession.Message.Type.USER ? "User" : "Assistant";
        return role + ": " + normalizedContent;
    }

    /**
     * 规范化 carryover 快照中的消息内容。
     * 这里会先复用统一的用户可见文本清洗逻辑，再压平换行与多余空白，并截断超长文本，
     * 避免继续会话的首条 prompt 被内部前缀或单条长消息放大。
     *
     * @param content 原始消息内容
     * @return 归一化后的单行文本；若原始内容为空则返回空串
     */
    private String normalizeContinuationCarryoverContent(String content) {
        if (!hasText(content)) {
            return "";
        }
        String sanitizedContent = UserMessageSanitizer.sanitizeInjectedRequestTextToUserVisibleText(content);
        if (!hasText(sanitizedContent)) {
            return "";
        }
        String normalizedContent = sanitizedContent.replace("\r", " ").replace("\n", " ").trim().replaceAll("\\s+", " ");
        if (normalizedContent.length() > CONTINUATION_CARRYOVER_MAX_MESSAGE_LENGTH) {
            return normalizedContent.substring(0, CONTINUATION_CARRYOVER_MAX_MESSAGE_LENGTH) + "...";
        }
        return normalizedContent;
    }

    /**
     * 根据当前会话状态判断本次 continued 链路实际采用的 carryover 模式。
     * 最近轮次快照优先级最高；若快照不可用但仍存在 summary，则显式标记为摘要回退，便于日志与元数据排查。
     *
     * @param state 当前 continued 链路对应的会话状态
     * @return 本次延续链路的上下文迁移模式标识
     */
    private String resolveContinuationCarryoverMode(SessionState state) {
        if (state == null) {
            return "";
        }
        if (hasText(state.getContinuationCarryoverText())) {
            return CONTINUATION_CARRYOVER_MODE;
        }
        if (hasText(state.getSummary())) {
            return CONTINUATION_CARRYOVER_SUMMARY_FALLBACK_MODE;
        }
        return "";
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
        private final String codexProviderId;
        private final String providerDisplayName;

        private SourceSegmentRuntimeMetadata(
                String provider,
                String runtimeFamily,
                String model,
                String reasoningEffort,
                String codexProviderId,
                String providerDisplayName
        ) {
            this.provider = firstNonBlank(provider);
            this.runtimeFamily = firstNonBlank(runtimeFamily);
            this.model = firstNonBlank(model);
            this.reasoningEffort = firstNonBlank(reasoningEffort);
            this.codexProviderId = firstNonBlank(codexProviderId);
            this.providerDisplayName = firstNonBlank(providerDisplayName);
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

        Application continuationApplication = ApplicationManager.getApplication();
        Runnable notifyContinuedSegmentReady = () -> {
            // 中文注释：显式桥接 continued 生命周期完成信号，避免前端继续把“首帧消息到达”
            // 当作唯一的收口条件。这样即使首帧快照延迟，continued 的 pending/source 状态也能先正确收口。
            host.callJavaScript("window.completeContinuedSegmentTransition", JsUtils.escapeJs(newSessionId));
            // continued segment 只有在真实 sessionId 已经落地后才能释放前端 guard，
            // 否则前端会把“继续”误当成可发送状态，进而在空 sessionId 上启动新一轮发送。
            host.callJavaScript("historyLoadComplete");
            host.callJavaScript("updateStatus",
                    JsUtils.escapeJs(ClaudeCodeGuiBundle.message("toast.conversationContinuedReady")));
            resetTokenUsage();
        };
        if (continuationApplication != null) {
            continuationApplication.invokeLater(notifyContinuedSegmentReady);
        } else {
            notifyContinuedSegmentReady.run();
        }
    }
}
