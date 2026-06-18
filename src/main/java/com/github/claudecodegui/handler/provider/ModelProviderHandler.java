package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.UsagePushService;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexRuntimeProfile;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.skill.SlashCommandRegistry;
import com.github.claudecodegui.settings.CodexProviderManager;
import com.github.claudecodegui.util.EditorFileUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles model and provider selection, reasoning effort, and slash command refresh.
 */
public class ModelProviderHandler {

    private static final Logger LOG = Logger.getInstance(ModelProviderHandler.class);
    private static final String CODEX_PROVIDER = "codex";
    private static final String DEFAULT_CODEX_REQUEST_MODE = "codex_sdk";
    private static final String CODEX_RUNTIME_TRACE_PREFIX = "[CODEX_RUNTIME_TRACE]";

    static final Map<String, Integer> MODEL_CONTEXT_LIMITS = new HashMap<>();
    static {
        // Claude models with 1M context (base IDs)
        MODEL_CONTEXT_LIMITS.put("claude-sonnet-4-6", 200_000);
        MODEL_CONTEXT_LIMITS.put("claude-opus-4-7", 200_000);
        MODEL_CONTEXT_LIMITS.put("claude-opus-4-6", 200_000);
        // Claude models with [1m] suffix - 1M context
        MODEL_CONTEXT_LIMITS.put("claude-sonnet-4-6[1m]", 1_000_000);
        MODEL_CONTEXT_LIMITS.put("claude-opus-4-7[1m]", 1_000_000);
        MODEL_CONTEXT_LIMITS.put("claude-opus-4-6[1m]", 1_000_000);
        // Haiku - no 1M context available
        MODEL_CONTEXT_LIMITS.put("claude-haiku-4-5", 200_000);
        // Codex/GPT models
        MODEL_CONTEXT_LIMITS.put("gpt-5.5", 400_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5.4", 1_000_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5.4-mini", 400_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5.3-codex", 258_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5.2-codex", 258_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5.2", 258_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5.1", 128_000);
        MODEL_CONTEXT_LIMITS.put("gpt-5.1-codex", 128_000);
        MODEL_CONTEXT_LIMITS.put("gpt-4o", 128_000);
        MODEL_CONTEXT_LIMITS.put("gpt-4o-mini", 128_000);
        MODEL_CONTEXT_LIMITS.put("gpt-4-turbo", 128_000);
        MODEL_CONTEXT_LIMITS.put("gpt-4", 8_192);
        MODEL_CONTEXT_LIMITS.put("o3", 200_000);
        MODEL_CONTEXT_LIMITS.put("o3-mini", 200_000);
        MODEL_CONTEXT_LIMITS.put("o1", 200_000);
        MODEL_CONTEXT_LIMITS.put("o1-mini", 128_000);
        MODEL_CONTEXT_LIMITS.put("o1-preview", 128_000);
    }

    private final HandlerContext context;
    private final UsagePushService usagePushService;
    private final Gson gson = new Gson();

    public ModelProviderHandler(HandlerContext context, UsagePushService usagePushService) {
        this.context = context;
        this.usagePushService = usagePushService;
    }

    /**
     * 统一格式化 Codex session binding 的诊断信息。
     * 该方法用于 provider/model 切换、新 session 恢复以及前后端联调时的日志输出，
     * 保证关键字段格式稳定，便于在 debug 包日志中按关键字直接检索整条链路。
     *
     * @param binding 当前会话上的 Codex 绑定信息，允许为 null
     * @return 统一格式的 binding 描述；为空时返回 "(null)"
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

    public void handleSetModel(String content) {
        try {
            String model = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("model")) {
                        model = json.get("model").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the model
                }
            }

            LOG.info("[ModelProviderHandler] Setting model to: " + model);
            context.setCurrentModel(model);

            if (context.getSession() != null) {
                context.getSession().setModel(model);
                syncCodexSessionBindingModelIfNeeded(model);
                LOG.info("[ModelProviderHandler] Updated session model to canonical ID: " + model);
            }
            context.requestTabSessionPersistence();

            com.github.claudecodegui.notifications.ClaudeNotifier.setModel(context.getProject(), model);

            String resolvedModelForUsage = resolveConfiguredClaudeModelFromSettings(model);
            int newMaxTokens = getModelContextLimit(resolvedModelForUsage);
            LOG.info("[ModelProviderHandler] Model context limit: " + newMaxTokens
                    + " tokens for selected model: " + model
                    + ", resolved model: " + resolvedModelForUsage);

            final String confirmedModel = model;
            final String confirmedProvider = context.getCurrentProvider();
            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.onModelConfirmed", context.escapeJs(confirmedModel), context.escapeJs(confirmedProvider));
                usagePushService.pushUsageUpdateAfterModelChange(newMaxTokens);
            });
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set model: " + e.getMessage(), e);
        }
    }

    /**
     * 持久化 Codex 模型下拉框的选中值。
     * 这里的职责仅限于把 providerId/modelId 写入 CC-GUI 自有配置，
     * 用于窗口重开、provider 切换后的前端状态恢复；不会修改会话内已生效的 runtime model，
     * 也不会改写 `~/.codex/config.toml` 等 Codex live config。
     *
     * @param content 包含 providerId 与 modelId 的 JSON 字符串
     * @return 无返回值
     */
    public void handleSetSelectedCodexModel(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String providerId = json != null && json.has("providerId") && !json.get("providerId").isJsonNull()
                    ? json.get("providerId").getAsString()
                    : "";
            String modelId = json != null && json.has("modelId") && !json.get("modelId").isJsonNull()
                    ? json.get("modelId").getAsString()
                    : "";
            // 只保存 CC-GUI 自有 selectedModel，不改写 Codex live config。
            context.getSettingsService().setSelectedCodexModel(providerId, modelId);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to persist selected Codex model: " + e.getMessage(), e);
        }
    }

    /**
     * 返回统一的 Codex 模型目录配置。
     * 该接口会把后端可发现模型、可见性配置与运行时可用性一次性回传给前端，
     * 供聊天区下拉和设置页 Models 面板共享。
     */
    public void handleGetCodexModelCatalog() {
        try {
            JsonObject catalogConfig = context.getSettingsService().getCodexModelDisplayConfig();
            JsonArray catalog = catalogConfig.has("catalog") && catalogConfig.get("catalog").isJsonArray()
                    ? catalogConfig.getAsJsonArray("catalog")
                    : new JsonArray();
            invokeLaterOrRun(() ->
                    context.callJavaScript("window.updateCodexModelCatalog", context.escapeJs(catalog.toString()))
            );
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to load Codex model catalog: " + e.getMessage(), e);
            invokeLaterOrRun(() ->
                    context.callJavaScript("window.updateCodexModelCatalog", context.escapeJs(new JsonArray().toString()))
            );
        }
    }

    /**
     * 保存 Codex 模型显示开关配置。
     * 该入口只负责更新 `codex.modelDisplay`，并在保存成功后把最新目录重新推送给前端，
     * 避免设置页与聊天区各自持有旧缓存。
     *
     * @param content 前端传入的可见性配置 JSON
     */
    public void handleSetCodexModelVisibility(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            context.getSettingsService().saveCodexModelDisplayConfig(json == null ? new JsonObject() : json);
            handleGetCodexModelCatalog();
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to save Codex model visibility: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs(e.getMessage()))
            );
        }
    }

    /**
     * 原子切换 Codex provider 与 model。
     * 该入口用于聊天区统一模型目录的选择事件，除更新配置外，还会同步刷新当前 session 上的 provider/model，
     * 并清空旧 thread 绑定，确保下一条消息新建线程而不是复用旧 provider 的会话。
     *
     * @param content 包含 providerId 与 modelId 的 JSON
     */
    public void handleSelectCodexModel(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String providerId = json != null && json.has("providerId") && !json.get("providerId").isJsonNull()
                    ? json.get("providerId").getAsString().trim()
                    : "";
            String modelId = json != null && json.has("modelId") && !json.get("modelId").isJsonNull()
                    ? json.get("modelId").getAsString().trim()
                    : "";
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " handleSelectCodexModel providerId="
                    + providerId
                    + ", modelId=" + modelId
                    + ", sessionId=" + currentSessionIdForTrace()
                    + ", beforeBinding=" + currentBindingForTrace());
            context.getSettingsService().setSelectedCodexModel(providerId, modelId);
            applyTabScopedCodexRuntimeSelection(providerId, modelId);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to select Codex model atomically: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs(e.getMessage()))
            );
        }
    }

    /**
     * 切换当前标签页的 Codex runtime provider。
     * 这里只更新当前标签的会话绑定与前端运行态，不写全局 codex.current，
     * 从而避免不同标签页之间互相覆盖对方的 provider 选择。
     *
     * @param content 包含 providerId 的 JSON
     */
    public void handleSetTabCodexProvider(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String providerId = json != null && json.has("providerId") && !json.get("providerId").isJsonNull()
                    ? json.get("providerId").getAsString().trim()
                    : "";
            LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " handleSetTabCodexProvider providerId="
                    + providerId
                    + ", sessionId=" + currentSessionIdForTrace()
                    + ", beforeBinding=" + currentBindingForTrace());
            String modelId = resolveModelForTabCodexProvider(providerId);
            applyTabScopedCodexRuntimeSelection(providerId, modelId);
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to switch tab-scoped Codex provider: " + e.getMessage(), e);
            invokeLaterOrRun(() ->
                    context.callJavaScript("window.showError", context.escapeJs(e.getMessage()))
            );
        }
    }

    public void handleSetProvider(String content) {
        try {
            String provider = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("provider")) {
                        provider = json.get("provider").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the provider
                }
            }

            LOG.info("[ModelProviderHandler] Setting provider to: " + provider);
            context.setCurrentProvider(provider);

            if (context.getSession() != null) {
                context.getSession().setProvider(provider);
                if (!CODEX_PROVIDER.equalsIgnoreCase(provider)) {
                    context.getSession().getState().setCodexSessionBinding(null);
                }
            }
            context.requestTabSessionPersistence();

            refreshSlashCommandsForProvider(provider);
            usagePushService.refreshContextBar();
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set provider: " + e.getMessage(), e);
        }
    }

    public void handleSetReasoningEffort(String content) {
        try {
            String effort = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("reasoningEffort")) {
                        effort = json.get("reasoningEffort").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the effort
                }
            }

            LOG.info("[ModelProviderHandler] Setting reasoning effort to: " + effort);

            if (context.getSession() != null) {
                context.getSession().setReasoningEffort(effort);
            }
            context.getSettingsService().setLastCodexReasoningEffort(effort);
            context.requestTabSessionPersistence();
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to set reasoning effort: " + e.getMessage(), e);
        }
    }

    /**
     * 将当前 Codex 模型状态推送到前端。
     * 读取受 Codemoss 授权控制的 ~/.codex/config.toml，并仅同步聊天输入区需要的字段，
     * 避免前端依赖过期的静态默认值。
     */
    public void handleGetCodexModelState() {
        try {
            JsonObject modelState = context.getSettingsService().getCurrentCodexModelState();
            invokeLaterOrRun(() ->
                context.callJavaScript("window.updateCodexModelState", context.escapeJs(modelState.toString()))
            );
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to load Codex model state: " + e.getMessage(), e);
            invokeLaterOrRun(() ->
                context.callJavaScript("window.updateCodexModelState", context.escapeJs(new JsonObject().toString()))
            );
        }
    }

    /**
     * 在 IDE Application 可用时切回 EDT；测试环境没有 Application 时直接同步执行，避免新回调协议测试被空指针打断。
     *
     * @param action 待执行的 bridge 回调
     */
    /**
     * 将标签页级 Codex provider/model 选择写入当前会话运行态，并同步回前端。
     * 该方法不会改动全局 active Codex provider，只会更新当前标签的 runtime binding。
     *
     * @param providerId 当前标签命中的 Codex provider id
     * @param modelId 当前标签命中的 Codex model id
     * @throws Exception provider 读取或状态同步失败时抛出
     */
    private void applyTabScopedCodexRuntimeSelection(String providerId, String modelId) throws Exception {
        String normalizedProviderId = safe(providerId);
        String normalizedModelId = safe(modelId);
        if (normalizedProviderId.isEmpty()) {
            throw new IllegalArgumentException("Codex providerId is required");
        }

        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " applyTabScopedCodexRuntimeSelection start sessionId="
                + currentSessionIdForTrace()
                + ", providerId=" + normalizedProviderId
                + ", modelId=" + normalizedModelId
                + ", beforeBinding=" + currentBindingForTrace());

        context.setCurrentProvider(CODEX_PROVIDER);
        if (!normalizedModelId.isEmpty()) {
            context.setCurrentModel(normalizedModelId);
        }

        if (context.getSession() != null) {
            context.getSession().setProvider(CODEX_PROVIDER);
            if (!normalizedModelId.isEmpty()) {
                context.getSession().setModel(normalizedModelId);
            }
            context.getSession().getState().setCodexSessionBinding(
                    buildTabScopedCodexSessionBinding(normalizedProviderId, normalizedModelId)
            );
        }

        context.requestTabSessionPersistence();
        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " applyTabScopedCodexRuntimeSelection updated sessionId="
                + currentSessionIdForTrace()
                + ", provider=" + safe(context.getSession() != null ? context.getSession().getProvider() : context.getCurrentProvider())
                + ", model=" + safe(context.getSession() != null ? context.getSession().getModel() : context.getCurrentModel())
                + ", binding=" + currentBindingForTrace());
        pushTabRuntimeStateToFrontend();

        final String confirmedModel = normalizedModelId;
        invokeLaterOrRun(() -> {
            if (!confirmedModel.isEmpty()) {
                context.callJavaScript("window.onModelConfirmed", context.escapeJs(confirmedModel), context.escapeJs(CODEX_PROVIDER));
            }
        });
    }

    /**
     * 根据当前标签的上下文为目标 Codex provider 解析一个可用模型。
     *
     * @param providerId 目标 provider id
     * @return 当前标签切换到目标 provider 时应恢复的模型 id
     * @throws Exception 读取 provider 配置失败时抛出
     */
    private String resolveModelForTabCodexProvider(String providerId) throws Exception {
        String normalizedProviderId = safe(providerId);
        if (normalizedProviderId.isEmpty()) {
            return "";
        }

        if (context.getSession() != null) {
            CodexSessionBinding binding = context.getSession().getState().getCodexSessionBinding();
            if (binding != null
                    && normalizedProviderId.equals(binding.getProviderId())
                    && !safe(binding.getModel()).isEmpty()) {
                return binding.getModel();
            }
        }

        JsonObject selectedModel = context.getSettingsService().getSelectedCodexModel();
        if (selectedModel != null) {
            String selectedProviderId = readString(selectedModel, "providerId");
            String selectedModelId = readString(selectedModel, "modelId");
            if (normalizedProviderId.equals(selectedProviderId) && !selectedModelId.isEmpty()) {
                return selectedModelId;
            }
        }

        JsonObject provider = context.getSettingsService().getCodexProviderById(normalizedProviderId);
        String providerModelId = readFirstConfiguredModelId(provider);
        if (!providerModelId.isEmpty()) {
            return providerModelId;
        }

        if (context.getSession() != null) {
            return safe(context.getSession().getModel());
        }
        return "";
    }

    /**
     * 基于 provider 配置构建最小可恢复的 Codex 会话绑定。
     *
     * @param providerId 目标 provider id
     * @param modelId 目标 model id
     * @return 当前标签应保存的 Codex 会话绑定
     * @throws Exception 读取 provider 配置失败时抛出
     */
    private CodexSessionBinding buildTabScopedCodexSessionBinding(String providerId, String modelId) throws Exception {
        JsonObject provider = context.getSettingsService().getCodexProviderById(providerId);
        boolean isCliLoginProvider = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(providerId)
                || (provider != null && provider.has("isCodexCliLoginProvider"));
        if (isCliLoginProvider) {
            return new CodexSessionBinding(
                    providerId,
                    modelId,
                    DEFAULT_CODEX_REQUEST_MODE,
                    CodexRuntimeProfile.AUTH_MODE_CLI_LOGIN,
                    CodexRuntimeProfile.CONFIG_SOURCE_CLI_LOGIN
            );
        }

        String requestMode = firstNonBlank(readString(provider, "requestMode"), DEFAULT_CODEX_REQUEST_MODE);
        String baseUrlSource = readString(provider, "baseUrl").isEmpty() ? "sdk_default" : "provider";
        return new CodexSessionBinding(
                providerId,
                modelId,
                requestMode,
                baseUrlSource,
                CodexRuntimeProfile.CONFIG_SOURCE_MANAGED_PROVIDER
        );
    }

    /**
     * 将当前会话的标签页运行态快照回推给前端。
     */
    private void pushTabRuntimeStateToFrontend() {
        JsonObject payload = new JsonObject();
        String provider = context.getSession() != null ? safe(context.getSession().getProvider()) : safe(context.getCurrentProvider());
        String model = context.getSession() != null ? safe(context.getSession().getModel()) : safe(context.getCurrentModel());
        String permissionMode = context.getSession() != null ? safe(context.getSession().getPermissionMode()) : "";
        String reasoningEffort = context.getSession() != null ? safe(context.getSession().getReasoningEffort()) : "";
        CodexSessionBinding binding = context.getSession() != null
                ? context.getSession().getState().getCodexSessionBinding()
                : null;

        payload.addProperty("provider", provider);
        payload.addProperty("model", model);
        payload.addProperty("permissionMode", permissionMode);
        payload.addProperty("reasoningEffort", reasoningEffort);
        payload.addProperty("codexProviderId", binding != null ? binding.getProviderId() : "");

        LOG.info(CODEX_RUNTIME_TRACE_PREFIX + " pushTabRuntimeStateToFrontend sessionId="
                + currentSessionIdForTrace()
                + ", payload=" + payload
                + ", binding=" + describeCodexBindingForTrace(binding));

        invokeLaterOrRun(() ->
                context.callJavaScript("window.restoreTabRuntimeState", context.escapeJs(payload.toString()))
        );
    }

    /**
     * 当当前标签 provider 为 Codex 时，同步更新会话绑定中的模型字段。
     *
     * @param model 最新模型 id
     */
    private void syncCodexSessionBindingModelIfNeeded(String model) {
        if (context.getSession() == null || !CODEX_PROVIDER.equalsIgnoreCase(context.getSession().getProvider())) {
            return;
        }
        CodexSessionBinding existingBinding = context.getSession().getState().getCodexSessionBinding();
        if (existingBinding == null) {
            return;
        }
        context.getSession().getState().setCodexSessionBinding(new CodexSessionBinding(
                existingBinding.getProviderId(),
                model,
                existingBinding.getRequestMode(),
                existingBinding.getBaseUrlSource(),
                existingBinding.getEffectiveConfigSource()
        ));
    }

    private void invokeLaterOrRun(Runnable action) {
        com.intellij.openapi.application.Application application = ApplicationManager.getApplication();
        // 统一目录与状态回调在单元测试里需要立即可见；
        // 若仍走 invokeLater，整套测试运行时可能尚未调度到 EDT，导致前端 bridge 断言误判为空。
        if (application == null || application.isDisposed() || application.isUnitTestMode()) {
            action.run();
            return;
        }
        application.invokeLater(action, ModalityState.any());
    }

    /**
     * 把当前 active Codex provider 摘要重新推送给前端。
     * 该逻辑与 settings 页的 provider 回调保持一致，供聊天区统一模型目录切换后复用。
     */
    private void handleGetActiveCodexProvider() {
        try {
            JsonObject provider = context.getSettingsService().getActiveCodexProvider();
            JsonObject payload = provider == null ? new JsonObject() : provider;
            context.callJavaScript("window.updateActiveCodexProvider", context.escapeJs(payload.toString()));
        } catch (Exception e) {
            LOG.warn("[ModelProviderHandler] Failed to refresh active Codex provider: " + e.getMessage(), e);
        }
    }

    private void refreshSlashCommandsForProvider(String provider) {
        String cwd = null;
        if (context.getSession() != null) {
            cwd = context.getSession().getCwd();
        }
        if (cwd == null) {
            cwd = context.getProject().getBasePath();
        }

        final String finalCwd = cwd;
        CompletableFuture.runAsync(() -> {
            String currentFilePath = EditorFileUtils.getCurrentEditorFilePath(context.getProject());
            var commands = SlashCommandRegistry.getCommands(provider, finalCwd, currentFilePath);
            String json = SlashCommandRegistry.toJson(commands);

            final String codexJson;
            if ("codex".equalsIgnoreCase(provider)) {
                var codexSkills = SlashCommandRegistry.getCodexSkills(finalCwd);
                codexJson = SlashCommandRegistry.toJson(codexSkills);
                LOG.info("[ModelProviderHandler] Codex skills refreshed: " + codexSkills.size() + " skills");
            } else {
                codexJson = null;
            }

            ApplicationManager.getApplication().invokeLater(() -> {
                try {
                    context.callJavaScript("updateSlashCommands", context.escapeJs(json));
                    if (codexJson != null) {
                        context.callJavaScript("window.updateDollarCommands", context.escapeJs(codexJson));
                    }
                } catch (Exception e) {
                    LOG.warn("[ModelProviderHandler] Failed to refresh slash commands: " + e.getMessage());
                }
            });
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[ModelProviderHandler] Failed to refresh slash commands asynchronously: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * 读取 provider 配置中的第一个模型 id。
     * 兼容 models/customModels 两种存储结构，找不到时返回空串。
     *
     * @param provider Codex provider 配置
     * @return 第一个可用模型 id
     */
    private String readFirstConfiguredModelId(JsonObject provider) {
        if (provider == null) {
            return "";
        }
        JsonArray models = provider.has("models") && provider.get("models").isJsonArray()
                ? provider.getAsJsonArray("models")
                : new JsonArray();
        if (models.size() == 0 && provider.has("customModels") && provider.get("customModels").isJsonArray()) {
            models = provider.getAsJsonArray("customModels");
        }
        if (models.size() == 0 || !models.get(0).isJsonObject()) {
            return "";
        }
        return readString(models.get(0).getAsJsonObject(), "id");
    }

    /**
     * 读取 JsonObject 中的字符串字段。
     *
     * @param object 源对象
     * @param key 字段名
     * @return 去空白后的字符串；缺失时返回空串
     */
    private String readString(JsonObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        return safe(object.get(key).getAsString());
    }

    /**
     * 返回第一个非空白字符串。
     *
     * @param values 候选值列表
     * @return 第一个非空白值；都为空时返回空串
     */
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String normalized = safe(value);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }
        return "";
    }

    /**
     * 读取当前会话 ID 供 trace 日志使用。
     * 新建会话前 threadId 可能尚未建立，因此这里统一回退为空串，避免日志中出现 null。
     *
     * @return 当前 sessionId；未建立时返回空串
     */
    private String currentSessionIdForTrace() {
        return context.getSession() == null ? "" : safe(context.getSession().getSessionId());
    }

    /**
     * 读取当前会话 binding 的统一 trace 文本。
     * 用于避免多个日志调用点重复拼接判断逻辑，保证输出格式一致。
     *
     * @return 当前会话 binding 的标准化描述
     */
    private String currentBindingForTrace() {
        if (context.getSession() == null) {
            return "(null)";
        }
        return describeCodexBindingForTrace(context.getSession().getState().getCodexSessionBinding());
    }

    /**
     * 统一规整可空字符串。
     *
     * @param value 原始字符串
     * @return 去首尾空白后的值；为 null 时返回空串
     */
    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveConfiguredClaudeModelFromSettings(String baseModel) {
        try {
            JsonObject claudeSettings = context.getSettingsService().readClaudeSettings();
            if (claudeSettings == null || !claudeSettings.has("env") || !claudeSettings.get("env").isJsonObject()) {
                return baseModel;
            }
            return resolveConfiguredClaudeModel(baseModel, claudeSettings.getAsJsonObject("env"));
        } catch (Exception e) {
            LOG.error("[ModelProviderHandler] Failed to resolve actual model name: " + e.getMessage());
        }

        return baseModel;
    }

    static String resolveConfiguredClaudeModel(String baseModel, JsonObject env) {
        if (baseModel == null || baseModel.isEmpty() || env == null) {
            return baseModel;
        }

        String mainModel = readConfiguredEnvValue(env, "ANTHROPIC_MODEL");
        if (mainModel != null) {
            return mainModel;
        }

        String lowerBaseModel = baseModel.toLowerCase();
        boolean isClaudeModel = lowerBaseModel.startsWith("claude-") || lowerBaseModel.startsWith("claude_");
        if (!isClaudeModel) {
            return baseModel;
        }

        if (lowerBaseModel.contains("opus")) {
            String mappedOpus = readConfiguredEnvValue(env, "ANTHROPIC_DEFAULT_OPUS_MODEL");
            return mappedOpus != null ? mappedOpus : baseModel;
        }
        if (lowerBaseModel.contains("haiku")) {
            String mappedHaiku = readConfiguredEnvValue(env, "ANTHROPIC_DEFAULT_HAIKU_MODEL");
            return mappedHaiku != null ? mappedHaiku : baseModel;
        }
        if (lowerBaseModel.contains("sonnet")) {
            String mappedSonnet = readConfiguredEnvValue(env, "ANTHROPIC_DEFAULT_SONNET_MODEL");
            return mappedSonnet != null ? mappedSonnet : baseModel;
        }

        return baseModel;
    }

    private static String readConfiguredEnvValue(JsonObject env, String key) {
        if (env == null || key == null || !env.has(key) || env.get(key).isJsonNull()) {
            return null;
        }

        String value = env.get(key).getAsString();
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static int getModelContextLimit(String model) {
        if (model == null || model.isEmpty()) {
            return 200_000;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\s*\\[([0-9.]+)([kKmM])\\]\\s*$");
        java.util.regex.Matcher matcher = pattern.matcher(model);

        if (matcher.find()) {
            try {
                double value = Double.parseDouble(matcher.group(1));
                String unit = matcher.group(2).toLowerCase();

                if ("m".equals(unit)) {
                    return (int)(value * 1_000_000);
                } else if ("k".equals(unit)) {
                    return (int)(value * 1_000);
                }
            } catch (NumberFormatException e) {
                LOG.error("Failed to parse capacity from model name: " + model);
            }
        }

        return MODEL_CONTEXT_LIMITS.getOrDefault(model, 200_000);
    }
}
