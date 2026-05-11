package com.github.claudecodegui.remote;

import com.github.claudecodegui.remote.debug.RemoteCollabDebugService;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.remote.provider.RemoteCollabProvider;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderDescriptor;
import com.github.claudecodegui.remote.provider.RemoteFeishuOperationsProvider;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderRegistry;
import com.github.claudecodegui.remote.provider.RemoteTelegramOperationsProvider;
import com.github.claudecodegui.remote.providers.gotify.GotifyWebRemoteCollabProvider;
import com.github.claudecodegui.remote.providers.feishu.FeishuRemoteCollabProvider;
import com.github.claudecodegui.remote.providers.telegram.TelegramRemoteCollabProvider;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程协作主服务。
 * 统一管理待处理请求注册、远程动作回传、通道初始化与连接状态查询。
 *
 * 当前阶段采用“provider registry + 旧 taskChannel 兼容层”并存的过渡方案：
 * - 新链路优先走 provider 抽象，为后续 Gotify/Web、飞书等方案接入打底。
 * - 旧 Telegram 单通道实现暂时继续保留，避免一次性迁移破坏现有可用能力。
 */
@Service(Service.Level.APP)
public final class RemoteCollabService {

    private static final Logger LOG = Logger.getInstance(RemoteCollabService.class);
    private static final String LEGACY_TELEGRAM_PROVIDER_ID = "legacy_telegram_channel";
    private static final RemoteCollabProviderDescriptor TELEGRAM_PROVIDER_DESCRIPTOR = new RemoteCollabProviderDescriptor(
        "telegram",
        "Telegram",
        "通过 Telegram Bot 完成通知、绑定和远程交互。",
        EnumSet.of(
            RemoteCollabCapability.TASK_EVENT_PUSH,
            RemoteCollabCapability.PENDING_REQUEST_PUSH,
            RemoteCollabCapability.BINDING,
            RemoteCollabCapability.INLINE_ACTION_CALLBACK,
            RemoteCollabCapability.HEALTH_CHECK
        )
    );
    private static final RemoteCollabProviderDescriptor GOTIFY_WEB_PROVIDER_DESCRIPTOR = new RemoteCollabProviderDescriptor(
        "gotify_web",
        "Gotify + Web",
        "通过 Gotify 通知和 Web 工作台组合完成远程协作。",
        EnumSet.of(
            RemoteCollabCapability.TASK_EVENT_PUSH,
            RemoteCollabCapability.PENDING_REQUEST_PUSH,
            RemoteCollabCapability.RESULT_POLLING,
            RemoteCollabCapability.HEALTH_CHECK,
            RemoteCollabCapability.WORKSPACE_LINK
        )
    );
    private static final RemoteCollabProviderDescriptor FEISHU_PROVIDER_DESCRIPTOR = new RemoteCollabProviderDescriptor(
        "feishu",
        "Feishu",
        "通过 Feishu 机器人私聊完成通知、绑定和远程交互。",
        EnumSet.of(
            RemoteCollabCapability.TASK_EVENT_PUSH,
            RemoteCollabCapability.PENDING_REQUEST_PUSH,
            RemoteCollabCapability.BINDING,
            RemoteCollabCapability.INLINE_ACTION_CALLBACK,
            RemoteCollabCapability.HEALTH_CHECK
        )
    );
    private static final RemoteCollabService FALLBACK_INSTANCE =
        new RemoteCollabService(RemoteRequestRegistry.getGlobalInstance());

    private final RemoteRequestRegistry requestRegistry;
    private final RemoteActionRouter actionRouter;
    private final RemoteCollabProviderRegistry providerRegistry;
    private final RemoteCollabDebugService debugService;
    private volatile RemoteRoutingPolicy routingPolicy = new RemoteRoutingPolicy("telegram", List.of("telegram"));
    private volatile RemoteTaskChannel taskChannel;

    public RemoteCollabService() {
        this(RemoteRequestRegistry.getGlobalInstance(), new RemoteCollabProviderRegistry(), new RemoteCollabDebugService());
    }

    RemoteCollabService(RemoteRequestRegistry requestRegistry) {
        this(requestRegistry, new RemoteCollabProviderRegistry(), new RemoteCollabDebugService());
    }

    RemoteCollabService(RemoteRequestRegistry requestRegistry, RemoteCollabProviderRegistry providerRegistry) {
        this(requestRegistry, providerRegistry, new RemoteCollabDebugService());
    }

    RemoteCollabService(
        RemoteRequestRegistry requestRegistry,
        RemoteCollabProviderRegistry providerRegistry,
        RemoteCollabDebugService debugService
    ) {
        this.requestRegistry = requestRegistry;
        this.actionRouter = new RemoteActionRouter(requestRegistry);
        this.providerRegistry = providerRegistry;
        this.debugService = debugService;
    }

    public static RemoteCollabService getInstance() {
        if (ApplicationManager.getApplication() == null) {
            return FALLBACK_INSTANCE;
        }
        return ApplicationManager.getApplication().getService(RemoteCollabService.class);
    }

    public RemoteRequestRegistry getRequestRegistry() {
        return requestRegistry;
    }

    public RemoteActionRouter getActionRouter() {
        return actionRouter;
    }

    /**
     * 返回远程协作调试服务。
     * 当前先给测试和后续设置页调试快照读取使用，避免调试能力散落在各个 provider 内部。
     */
    public RemoteCollabDebugService getDebugService() {
        return debugService;
    }

    /**
     * 返回当前 provider 注册表。
     * 先提供给服务层和测试使用，后续再由设置页和初始化链路统一驱动注册过程。
     */
    public RemoteCollabProviderRegistry getProviderRegistry() {
        return providerRegistry;
    }

    public void setTaskChannel(RemoteTaskChannel taskChannel) {
        this.taskChannel = taskChannel;
    }

    /**
     * 仅在远程协作启用时初始化远程通道。
     * 如果已经接入 provider，则优先初始化 provider；否则继续兼容旧 Telegram 通道。
     */
    public synchronized void initializeIfEnabled(CodemossSettingsService settingsService) throws IOException {
        JsonObject remoteCollabConfig = settingsService.getRemoteCollabConfig();
        routingPolicy = buildRoutingPolicy(remoteCollabConfig);
        boolean enabled = remoteCollabConfig.has("enabled") && remoteCollabConfig.get("enabled").getAsBoolean();
        // 先按当前配置补齐 provider 注册，避免设置页和运行链路看到的能力集合不一致。
        ensureConfiguredProvidersRegistered(settingsService, remoteCollabConfig);
        if (!enabled) {
            shutdown();
            return;
        }
        if (!providerRegistry.getProviders().isEmpty()) {
            initializeProviders();
            return;
        }
        if (taskChannel == null) {
            throw new IllegalStateException("No remote collaboration provider or legacy task channel is available");
        }
        try {
            taskChannel.initialize();
        } catch (RuntimeException e) {
            recordProviderError(LEGACY_TELEGRAM_PROVIDER_ID, "initialize", e);
            throw e;
        }
    }

    /**
     * 保存配置后重新拉起远程通道。
     * 先 shutdown 再重建，避免 botToken/chatId/polling 开关变化后继续沿用旧实例里的脏状态。
     */
    public synchronized void reinitializeIfEnabled(CodemossSettingsService settingsService) throws IOException {
        shutdown();
        taskChannel = null;
        initializeIfEnabled(settingsService);
    }

    /**
     * 关闭当前远程协作通道。
     * 当前阶段会同时关闭已注册 provider 和旧 taskChannel，保证过渡期任一链路都能被正确回收。
     */
    public synchronized void shutdown() {
        for (RemoteCollabProvider provider : providerRegistry.getProviders()) {
            try {
                provider.shutdown();
            } catch (RuntimeException e) {
                recordProviderError(getProviderId(provider), "shutdown", e);
            }
        }
        if (taskChannel != null) {
            try {
                taskChannel.shutdown();
            } catch (RuntimeException e) {
                recordProviderError(LEGACY_TELEGRAM_PROVIDER_ID, "shutdown", e);
            }
            taskChannel = null;
        }
    }

    /**
     * 注册一个可被远程端完成的待处理请求。
     */
    public void registerPendingRequest(RemotePendingRequest request) {
        requestRegistry.register(request);
    }

    /**
     * 将待处理请求下发给当前远程通道。
     * 优先走 provider 抽象；如果当前仍处于旧 Telegram 兼容链路，则回退到 taskChannel。
     */
    public boolean publishPendingRequest(RemotePendingRequest request) {
        if (request == null) {
            return false;
        }
        List<RemoteCollabProvider> providers = resolvePendingRequestProviders();
        if (!providers.isEmpty()) {
            RemotePendingRequest routedRequest = wrapProviderPendingRequest(request);
            for (RemoteCollabProvider provider : providers) {
                String providerId = getProviderId(provider);
                debugService.recordPendingRequest(providerId, request);
                try {
                    provider.publishPendingRequest(routedRequest);
                } catch (Exception e) {
                    recordProviderError(providerId, "publishPendingRequest", e);
                    LOG.warn("[RemoteCollabService] Failed to publish pending request via provider: " + e.getMessage());
                    return false;
                }
            }
            return true;
        }
        if (taskChannel == null) {
            return false;
        }
        try {
            debugService.recordPendingRequest(LEGACY_TELEGRAM_PROVIDER_ID, request);
            taskChannel.publishPendingRequest(request);
            return true;
        } catch (Exception e) {
            recordProviderError(LEGACY_TELEGRAM_PROVIDER_ID, "publishPendingRequest", e);
            LOG.warn("[RemoteCollabService] Failed to publish pending request: " + e.getMessage());
            return false;
        }
    }

    /**
     * 供远程端通过 requestId 回写本地结果。
     */
    public boolean completePendingRequest(String requestId, JsonObject response) {
        return actionRouter.completeRequest(requestId, response);
    }

    /**
     * 发布任务状态事件。
     * 移动端主要用它感知当前 IDE 任务进度；当前实现优先走 provider，否则回退到旧通道。
     */
    public void publishTaskEvent(RemoteTaskEvent event) {
        List<RemoteCollabProvider> providers = resolveTaskEventProviders();
        if (!providers.isEmpty()) {
            for (RemoteCollabProvider provider : providers) {
                String providerId = getProviderId(provider);
                debugService.recordTaskEvent(providerId, event);
                try {
                    provider.publishTaskEvent(event);
                } catch (Exception e) {
                    recordProviderError(providerId, "publishTaskEvent", e);
                    LOG.warn("[RemoteCollabService] Failed to publish task event via provider: " + e.getMessage());
                    return;
                }
            }
            return;
        }
        if (taskChannel == null) {
            return;
        }
        try {
            debugService.recordTaskEvent(LEGACY_TELEGRAM_PROVIDER_ID, event);
            taskChannel.publishTaskEvent(event);
        } catch (Exception e) {
            recordProviderError(LEGACY_TELEGRAM_PROVIDER_ID, "publishTaskEvent", e);
            LOG.warn("[RemoteCollabService] Failed to publish task event: " + e.getMessage());
        }
    }

    public String getConnectionStatus() {
        List<RemoteCollabProvider> providers = providerRegistry.getProviders();
        if (!providers.isEmpty()) {
            return aggregateProviderStatus(providers).getValue();
        }
        if (taskChannel == null) {
            return RemoteConnectionStatus.DISABLED.getValue();
        }
        return taskChannel.getConnectionStatus().getValue();
    }

    /**
     * Provider 侧回调结果时，统一先走 RemoteActionRouter 完成本地 request registry 清理。
     * 如果当前请求未提前登记，则回退到原始 completer，避免影响纯调试场景。
     */
    private RemotePendingRequest wrapProviderPendingRequest(RemotePendingRequest request) {
        return new RemotePendingRequest(
            request.getRequestId(),
            request.getRequestType(),
            request.getSessionId(),
            request.getProjectPath(),
            request.getPayload(),
            response -> {
                if (!actionRouter.completeRequest(request.getRequestId(), response)) {
                    request.complete(response);
                }
            }
        );
    }

    /**
     * 根据当前 routing policy 选择 pending request 的真实目标 provider。
     * 当交互 provider 未配置、未注册或不支持当前能力时，回退到“所有支持该能力的 provider”，避免请求被静默吞掉。
     */
    private List<RemoteCollabProvider> resolvePendingRequestProviders() {
        List<RemoteCollabProvider> providers =
            providerRegistry.getProvidersSupporting(RemoteCollabCapability.PENDING_REQUEST_PUSH);
        if (providers.isEmpty()) {
            return providers;
        }
        String interactiveProviderId = routingPolicy == null ? "" : routingPolicy.getInteractiveProviderId();
        RemoteCollabProvider interactiveProvider = providerRegistry.getProvider(interactiveProviderId);
        if (interactiveProvider != null && interactiveProvider.supports(RemoteCollabCapability.PENDING_REQUEST_PUSH)) {
            return List.of(interactiveProvider);
        }
        return providers;
    }

    /**
     * 根据当前 routing policy 选择任务通知的目标 provider 列表。
     * notifyProviderIds 为空或全部失效时回退到所有支持通知的 provider，保持历史兼容行为。
     */
    private List<RemoteCollabProvider> resolveTaskEventProviders() {
        List<RemoteCollabProvider> providers =
            providerRegistry.getProvidersSupporting(RemoteCollabCapability.TASK_EVENT_PUSH);
        if (providers.isEmpty()) {
            return providers;
        }
        List<RemoteCollabProvider> routedProviders = new ArrayList<>();
        if (routingPolicy != null) {
            for (String providerId : routingPolicy.getNotifyProviderIds()) {
                RemoteCollabProvider provider = providerRegistry.getProvider(providerId);
                if (provider != null
                    && provider.supports(RemoteCollabCapability.TASK_EVENT_PUSH)
                    && !routedProviders.contains(provider)) {
                    routedProviders.add(provider);
                }
            }
        }
        return routedProviders.isEmpty() ? providers : routedProviders;
    }

    /**
     * 查询当前 IDE 实例是否真的持有 Telegram polling 接收权。
     * 该状态仍然依赖 Telegram 旧实现，等 Telegram 完整迁移成 provider 后再收敛。
     */
    public boolean isCurrentInstanceReceivingUpdates() {
        RemoteTelegramOperationsProvider telegramProvider = getRegisteredTelegramOperationsProvider();
        return telegramProvider != null && telegramProvider.isCurrentInstanceReceivingUpdates();
    }

    /**
     * 组装设置页需要的远程协作视图模型。
     * 当前仍然兼容旧 telegram 结构，后续配置模型升级后再切换到 provider 视图结构。
     */
    public JsonObject buildRemoteCollabViewModel(CodemossSettingsService settingsService) throws IOException {
        JsonObject config = settingsService.getRemoteCollabConfig();
        // 设置页视图也要走同一套注册逻辑，确保 provider 卡片、调试按钮和真实后端能力一致。
        ensureConfiguredProvidersRegistered(settingsService, config);
        JsonObject providers = config.has("providers") && config.get("providers").isJsonObject()
            ? config.getAsJsonObject("providers")
            : new JsonObject();
        JsonObject telegram = providers.has("telegram") && providers.get("telegram").isJsonObject()
            ? providers.getAsJsonObject("telegram").deepCopy()
            : new JsonObject();
        JsonObject feishu = providers.has("feishu") && providers.get("feishu").isJsonObject()
            ? providers.getAsJsonObject("feishu").deepCopy()
            : new JsonObject();

        // 配置模型已经升级到 providers 树，但设置页桥接还处在兼容阶段，
        // 这里继续补出顶层 telegram 视图，避免前端在阶段 2~4 之间出现联调断层。
        telegram.addProperty("connectionStatus", getConnectionStatus());
        telegram.addProperty("currentInstanceReceivesUpdates", isCurrentInstanceReceivingUpdates());
        providers.add("telegram", telegram.deepCopy());
        providers.add("feishu", feishu.deepCopy());
        config.add("providers", providers);
        config.add("telegram", telegram);
        config.add("feishu", feishu);
        config.add("routingPolicy", buildRoutingPolicyView(config));
        config.add("providerOptions", buildProviderOptions(providers));
        return config;
    }

    /**
     * 触发 Telegram 绑定流程。
     * 当前阶段仍走旧 Telegram 通道，等 Telegram provider 完整落地后再切换入口。
     */
    public JsonObject startTelegramBinding(CodemossSettingsService settingsService) throws IOException {
        ensureTelegramProviderRegistered(settingsService);
        RemoteTelegramOperationsProvider telegramProvider = getRegisteredTelegramOperationsProvider();
        if (telegramProvider == null) {
            throw new IllegalStateException("Telegram provider is not registered");
        }
        return telegramProvider.startBinding(settingsService);
    }

    /**
     * 发送 Telegram 测试消息，用于校验 botToken/chatId 是否已正确配置。
     */
    public void sendTelegramTestMessage(CodemossSettingsService settingsService, String message) throws IOException {
        String text = message == null || message.trim().isEmpty()
            ? "CC GUI Telegram ????"
            : message.trim();
        ensureTelegramProviderRegistered(settingsService);
        RemoteTelegramOperationsProvider telegramProvider = getRegisteredTelegramOperationsProvider();
        if (telegramProvider == null) {
            throw new IllegalStateException("Telegram provider is not registered");
        }
        telegramProvider.sendTestMessage(settingsService, text);
    }

    private void initializeProviders() {
        for (RemoteCollabProvider provider : providerRegistry.getProviders()) {
            try {
                provider.initialize();
            } catch (RuntimeException e) {
                recordProviderError(getProviderId(provider), "initialize", e);
                throw e;
            }
        }
    }

    /**
     * 汇总已注册 provider 的连接状态。
     * 优先暴露 error 和 connected，便于设置页快速看到最需要关注的状态。
     */
    private RemoteConnectionStatus aggregateProviderStatus(List<RemoteCollabProvider> providers) {
        boolean hasConnecting = false;
        boolean hasDisconnected = false;
        boolean hasDisabled = false;
        for (RemoteCollabProvider provider : providers) {
            RemoteConnectionStatus status = provider.getConnectionStatus();
            if (status == null) {
                hasDisconnected = true;
                continue;
            }
            switch (status) {
                case ERROR:
                    return RemoteConnectionStatus.ERROR;
                case CONNECTED:
                    return RemoteConnectionStatus.CONNECTED;
                case CONNECTING:
                    hasConnecting = true;
                    break;
                case DISCONNECTED:
                    hasDisconnected = true;
                    break;
                case DISABLED:
                    hasDisabled = true;
                    break;
                default:
                    hasDisconnected = true;
                    break;
            }
        }
        if (hasConnecting) {
            return RemoteConnectionStatus.CONNECTING;
        }
        if (hasDisconnected) {
            return RemoteConnectionStatus.DISCONNECTED;
        }
        if (hasDisabled) {
            return RemoteConnectionStatus.DISABLED;
        }
        return RemoteConnectionStatus.DISABLED;
    }

    /**
     * 当前阶段只有 Telegram 一个旧实现，因此这里按需懒创建并缓存。
     * 等 Telegram 迁移为 provider 后，再彻底移除这个兼容入口。
     */
    /**
     * ??????????? Telegram ??????? provider?
     * ??????????????????? provider ?? Telegram ???
     */
    private RemoteTelegramOperationsProvider getRegisteredTelegramOperationsProvider() {
        RemoteCollabProvider provider = providerRegistry.getProvider("telegram");
        if (provider instanceof RemoteTelegramOperationsProvider telegramProvider) {
            return telegramProvider;
        }
        return null;
    }

    private RemoteFeishuOperationsProvider getRegisteredFeishuOperationsProvider() {
        RemoteCollabProvider provider = providerRegistry.getProvider("feishu");
        if (provider instanceof RemoteFeishuOperationsProvider feishuProvider) {
            return feishuProvider;
        }
        return null;
    }

    /**
     * ?? Telegram ????? provider ??? registry?
     * ??????????????????????????????????? provider ???
     */
    private void ensureTelegramProviderRegistered(CodemossSettingsService settingsService) {
        if (providerRegistry.getProvider("telegram") != null) {
            return;
        }
        providerRegistry.register(new TelegramRemoteCollabProvider(settingsService));
    }

    /**
     * 按当前配置懒注册 provider。
     * 这样无论是设置页拉配置、真正初始化还是后续调试动作，都能共享同一份 provider 注册表。
     */
    private void ensureConfiguredProvidersRegistered(CodemossSettingsService settingsService, JsonObject config) {
        if (settingsService == null) {
            return;
        }
        ensureTelegramProviderRegistered(settingsService);
        JsonObject providerConfigs = config != null && config.has("providers") && config.get("providers").isJsonObject()
            ? config.getAsJsonObject("providers")
            : new JsonObject();
        if (providerConfigs.has("gotify_web") && providerConfigs.get("gotify_web").isJsonObject()) {
            ensureGotifyProviderRegistered(settingsService);
        }
        if (providerConfigs.has("feishu") && providerConfigs.get("feishu").isJsonObject()) {
            ensureFeishuProviderRegistered(settingsService);
        }
    }

    private void ensureGotifyProviderRegistered(CodemossSettingsService settingsService) {
        if (providerRegistry.getProvider("gotify_web") != null) {
            return;
        }
        providerRegistry.register(new GotifyWebRemoteCollabProvider(settingsService));
    }

    private void ensureFeishuProviderRegistered(CodemossSettingsService settingsService) {
        if (providerRegistry.getProvider("feishu") != null) {
            return;
        }
        providerRegistry.register(new FeishuRemoteCollabProvider(settingsService));
    }

    public JsonObject startFeishuBinding(CodemossSettingsService settingsService) throws IOException {
        ensureFeishuProviderRegistered(settingsService);
        RemoteFeishuOperationsProvider feishuProvider = getRegisteredFeishuOperationsProvider();
        if (feishuProvider == null) {
            throw new IllegalStateException("Feishu provider is not registered");
        }
        return feishuProvider.startBinding(settingsService);
    }

    public JsonObject healthCheckFeishu(CodemossSettingsService settingsService) throws IOException {
        ensureFeishuProviderRegistered(settingsService);
        RemoteFeishuOperationsProvider feishuProvider = getRegisteredFeishuOperationsProvider();
        if (feishuProvider == null) {
            throw new IllegalStateException("Feishu provider is not registered");
        }
        return feishuProvider.healthCheck(settingsService);
    }

    public void sendFeishuTestMessage(CodemossSettingsService settingsService, String message) throws IOException {
        String text = message == null || message.trim().isEmpty()
            ? "CC GUI Feishu test message"
            : message.trim();
        ensureFeishuProviderRegistered(settingsService);
        RemoteFeishuOperationsProvider feishuProvider = getRegisteredFeishuOperationsProvider();
        if (feishuProvider == null) {
            throw new IllegalStateException("Feishu provider is not registered");
        }
        feishuProvider.sendTestMessage(settingsService, text);
    }

    private String getProviderId(RemoteCollabProvider provider) {
        if (provider == null || provider.getDescriptor() == null || provider.getDescriptor().getProviderId() == null) {
            return "";
        }
        return provider.getDescriptor().getProviderId();
    }

    private void recordProviderError(String providerId, String phase, Exception error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        debugService.recordError(providerId, phase, message);
    }

    /**
     * 从当前远程协作配置中提取路由策略。
     * 这里复用 `RemoteRoutingPolicy` 做统一归一化，保证 interactive provider 和 notify provider 的读取规则一致。
     */
    private RemoteRoutingPolicy buildRoutingPolicy(JsonObject config) {
        List<String> notifyProviderIds = new ArrayList<>();
        if (config != null && config.has("notifyProviderIds") && config.get("notifyProviderIds").isJsonArray()) {
            JsonArray notifyProviders = config.getAsJsonArray("notifyProviderIds");
            for (int index = 0; index < notifyProviders.size(); index++) {
                if (notifyProviders.get(index) == null || notifyProviders.get(index).isJsonNull()) {
                    continue;
                }
                notifyProviderIds.add(notifyProviders.get(index).getAsString());
            }
        }
        String interactiveProviderId = config != null
            && config.has("interactiveProviderId")
            && !config.get("interactiveProviderId").isJsonNull()
            ? config.get("interactiveProviderId").getAsString()
            : "telegram";
        return new RemoteRoutingPolicy(interactiveProviderId, notifyProviderIds);
    }

    private JsonObject buildRoutingPolicyView(JsonObject config) {
        JsonObject routingPolicy = new JsonObject();
        routingPolicy.addProperty(
            "interactiveProviderId",
            config.has("interactiveProviderId") && !config.get("interactiveProviderId").isJsonNull()
                ? config.get("interactiveProviderId").getAsString()
                : ""
        );
        routingPolicy.add(
            "notifyProviderIds",
            config.has("notifyProviderIds") && config.get("notifyProviderIds").isJsonArray()
                ? config.getAsJsonArray("notifyProviderIds").deepCopy()
                : new JsonArray()
        );
        return routingPolicy;
    }

    /**
     * 组装设置页使用的 provider 列表视图。
     * 当前阶段即使 provider 尚未真正注册，也会把 Telegram / GotifyWeb 的静态能力元数据返回给前端，
     * 这样设置页改造时可以稳定按统一结构渲染方案卡片。
     */
    private JsonArray buildProviderOptions(JsonObject providerConfigs) {
        Map<String, JsonObject> optionMap = new LinkedHashMap<>();
        optionMap.put("telegram", createProviderOption(TELEGRAM_PROVIDER_DESCRIPTOR, providerConfigs, null));
        optionMap.put("gotify_web", createProviderOption(GOTIFY_WEB_PROVIDER_DESCRIPTOR, providerConfigs, null));
        optionMap.put("feishu", createProviderOption(FEISHU_PROVIDER_DESCRIPTOR, providerConfigs, null));

        for (RemoteCollabProvider provider : providerRegistry.getProviders()) {
            RemoteCollabProviderDescriptor descriptor = provider.getDescriptor();
            if (descriptor == null) {
                continue;
            }
            optionMap.put(descriptor.getProviderId(), createProviderOption(descriptor, providerConfigs, provider));
        }

        JsonArray options = new JsonArray();
        for (JsonObject option : optionMap.values()) {
            options.add(option);
        }
        return options;
    }

    private JsonObject createProviderOption(
        RemoteCollabProviderDescriptor descriptor,
        JsonObject providerConfigs,
        RemoteCollabProvider provider
    ) {
        JsonObject option = new JsonObject();
        String providerId = descriptor.getProviderId();
        JsonObject providerConfig = providerConfigs.has(providerId) && providerConfigs.get(providerId).isJsonObject()
            ? providerConfigs.getAsJsonObject(providerId)
            : new JsonObject();
        option.addProperty("providerId", providerId);
        option.addProperty("displayName", descriptor.getDisplayName());
        option.addProperty("description", descriptor.getDescription());
        option.add("capabilities", toCapabilityJson(descriptor));
        option.addProperty("registered", provider != null);
        option.addProperty(
            "enabled",
            providerConfig.has("enabled") && !providerConfig.get("enabled").isJsonNull() && providerConfig.get("enabled").getAsBoolean()
        );
        option.addProperty(
            "connectionStatus",
            provider != null && provider.getConnectionStatus() != null
                ? provider.getConnectionStatus().getValue()
                : readString(providerConfig, "connectionStatus", "disabled")
        );
        option.add("config", providerConfig.deepCopy());
        if ("telegram".equals(providerId)) {
            option.addProperty("currentInstanceReceivesUpdates", isCurrentInstanceReceivingUpdates());
        }
        return option;
    }

    private JsonArray toCapabilityJson(RemoteCollabProviderDescriptor descriptor) {
        JsonArray capabilities = new JsonArray();
        List<String> names = new ArrayList<>();
        for (RemoteCollabCapability capability : descriptor.getCapabilities()) {
            names.add(capability.name());
        }
        names.sort(String::compareTo);
        for (String name : names) {
            capabilities.add(name);
        }
        return capabilities;
    }

    private String readString(JsonObject source, String key, String fallback) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
            return fallback;
        }
        String value = source.get(key).getAsString();
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
