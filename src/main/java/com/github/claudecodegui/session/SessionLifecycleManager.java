package com.github.claudecodegui.session;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.history.HistoryMessageInjector;
import com.github.claudecodegui.model.SessionTemplate;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.codex.CodexHistoryReader;
import com.github.claudecodegui.remote.debug.TabSessionRestoreDebugTrace;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.handler.SettingsHandler;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.skill.SlashCommandRegistry;
import com.github.claudecodegui.util.JsUtils;
import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.util.TokenUsageUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.ui.jcef.JBCefBrowser;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages session lifecycle operations: creation, history loading,
 * working directory resolution, slash commands, and permission mode sync.
 */
public class SessionLifecycleManager {

    private static final Logger LOG = Logger.getInstance(SessionLifecycleManager.class);
    private static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";
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

            // Prewarm daemon runtime for the historical session so /context and first message are fast
            host.getClaudeSDKBridge().prewarmDaemonAsync(workingDir, newSession.getRuntimeSessionEpoch(), sessionId);

            CompletableFuture<Void> loadFuture = SessionRuntimeFamily.CODEX.equals(resolvedRuntimeFamily)
                    ? loadCodexHistorySession(newSession, sessionId, resolvedProvider, restoreSource, transitionToken)
                    : newSession.loadFromServer();

            loadFuture.handle((unused, ex) -> {
                finishHistoryRestoreRequest(restoreRequestKey);
                ApplicationManager.getApplication().invokeLater(() -> {
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
                CodexHistoryReader codexReader = new CodexHistoryReader();
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

                session.setSessionInfo(actualThreadId, resolvedCwd);
                restoreCodexSessionBindingIfPresent(session, actualThreadId);
                HistoryMessageInjector.restoreCodexMessagesToSessionState(session.getState(), messages);
                pushCodexHistoryMessagesToFrontend(
                        messages,
                        buildHistoryRestoreRequestKey(actualThreadId, restoreSource, transitionToken)
                );
                restoreCodexHistoryTokenUsage(session, messages);

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
    private void pushCodexHistoryMessagesToFrontend(JsonArray messages, String restoreRequestKey) {
        List<JsonObject> frontendMessages = HistoryMessageInjector.convertCodexMessagesToFrontendBatch(messages);
        String payload = JsUtils.escapeJs(new Gson().toJson(frontendMessages));
        String rawSnapshotSignature = HistoryMessageInjector.buildFrontendSnapshotSignature(frontendMessages);
        String snapshotSignature = JsUtils.escapeJs(rawSnapshotSignature);
        String escapedRestoreRequestKey = JsUtils.escapeJs(restoreRequestKey);
        LOG.info("[HistoryRestore] Push Codex history snapshot to frontend: restoreRequestKey="
                + restoreRequestKey
                + ", snapshotSignature=" + rawSnapshotSignature
                + ", frontendMessageCount=" + frontendMessages.size()
                + ", rawMessageCount=" + messages.size());
        ApplicationManager.getApplication().invokeLater(() -> {
            host.callJavaScript("prepareHistoryRestoreSnapshot", escapedRestoreRequestKey, snapshotSignature);
            host.callJavaScript("clearMessages");
            host.callJavaScript("updateMessages", payload);
        });
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
}
