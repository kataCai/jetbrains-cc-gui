package com.github.claudecodegui.remote.providers.feishu;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugActionDescriptor;
import com.github.claudecodegui.remote.feishu.FeishuBindingService;
import com.github.claudecodegui.remote.feishu.FeishuEventSubscriber;
import com.github.claudecodegui.remote.feishu.FeishuIncomingMessage;
import com.github.claudecodegui.remote.feishu.FeishuMessageFormatter;
import com.github.claudecodegui.remote.feishu.FeishuMessageClient;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderActionHandler;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderDescriptor;
import com.github.claudecodegui.remote.provider.RemoteFeishuOperationsProvider;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Feishu 远程协作 provider。
 * 当前先落地第一阶段最小骨架：配置接入、连通性检查、测试消息和绑定令牌生成。
 */
public class FeishuRemoteCollabProvider implements RemoteFeishuOperationsProvider, RemoteCollabProviderActionHandler {

    private static final Logger LOG = Logger.getInstance(FeishuRemoteCollabProvider.class);
    private static final String PROVIDER_ID = "feishu";
    private static final String DEFAULT_TEST_MESSAGE = "CC GUI Feishu test message";
    private static final String ACTION_START_BINDING = "start_binding";
    private static final String ACTION_HEALTH_CHECK = "health_check";
    private static final String ACTION_SEND_TEST_MESSAGE = "send_test_message";
    private static final String ACTION_HANDLE_INBOUND_MESSAGE = "handle_inbound_message";
    private static final RemoteCollabProviderDescriptor DESCRIPTOR = new RemoteCollabProviderDescriptor(
        PROVIDER_ID,
        "Feishu",
        "Use Feishu bot direct messages for remote notifications and approvals.",
        EnumSet.of(
            RemoteCollabCapability.TASK_EVENT_PUSH,
            RemoteCollabCapability.PENDING_REQUEST_PUSH,
            RemoteCollabCapability.BINDING,
            RemoteCollabCapability.INLINE_ACTION_CALLBACK,
            RemoteCollabCapability.HEALTH_CHECK
        )
    );
    private static final List<RemoteCollabDebugActionDescriptor> DEBUG_ACTIONS = List.of(
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_START_BINDING, "Start Binding", "Generate a one-time Feishu binding token."),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_HEALTH_CHECK, "Health Check", "Verify tenant access token can be fetched."),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_SEND_TEST_MESSAGE, "Send Test Message", "Send a test message to the bound Feishu user."),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_HANDLE_INBOUND_MESSAGE, "Handle Inbound Message", "Inject a Feishu text command for local callback debugging.")
    );

    private final FeishuRuntimeDelegate delegate;

    public FeishuRemoteCollabProvider(CodemossSettingsService settingsService) {
        this(new LiveFeishuRuntimeDelegate(settingsService));
    }

    FeishuRemoteCollabProvider(
        CodemossSettingsService settingsService,
        FeishuBindingService bindingService,
        FeishuMessageFormatter messageFormatter,
        FeishuEventSubscriber eventSubscriber,
        FeishuClientFactory clientFactory
    ) {
        this(new LiveFeishuRuntimeDelegate(settingsService, bindingService, messageFormatter, eventSubscriber, clientFactory));
    }

    public FeishuRemoteCollabProvider(FeishuRuntimeDelegate delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public RemoteCollabProviderDescriptor getDescriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RemoteConnectionStatus getConnectionStatus() {
        return delegate.getConnectionStatus();
    }

    @Override
    public void initialize() {
        delegate.initialize();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public void publishTaskEvent(RemoteTaskEvent event) {
        delegate.publishTaskEvent(event);
    }

    @Override
    public void publishPendingRequest(RemotePendingRequest request) {
        delegate.publishPendingRequest(request);
    }

    @Override
    public JsonObject startBinding(CodemossSettingsService settingsService) throws IOException {
        return delegate.startBinding();
    }

    @Override
    public JsonObject healthCheck(CodemossSettingsService settingsService) throws IOException {
        try {
            JsonObject result = delegate.healthCheck();
            persistProviderStatus(settingsService, readStatus(result, delegate.getConnectionStatus()), "");
            return result;
        } catch (IOException e) {
            // 失败时立即把错误落到配置树，避免设置页只能看到旧状态，难以及时定位配置问题。
            persistProviderStatus(settingsService, RemoteConnectionStatus.ERROR.getValue(), safeMessage(e));
            throw e;
        }
    }

    @Override
    public void sendTestMessage(CodemossSettingsService settingsService, String message) throws IOException {
        delegate.sendTestMessage(normalizeTestMessage(message));
    }

    @Override
    public JsonObject executeAction(CodemossSettingsService settingsService, String actionKey, JsonObject request) throws Exception {
        if (ACTION_START_BINDING.equals(actionKey)) {
            return startBinding(settingsService);
        }
        if (ACTION_HEALTH_CHECK.equals(actionKey) || "test_connection".equals(actionKey)) {
            return healthCheck(settingsService);
        }
        if (ACTION_SEND_TEST_MESSAGE.equals(actionKey)) {
            sendTestMessage(settingsService, readOptionalMessage(request));
            JsonObject result = new JsonObject();
            result.addProperty("message", "Feishu 测试消息已发送");
            return result;
        }
        if (ACTION_HANDLE_INBOUND_MESSAGE.equals(actionKey)) {
            return handleInboundMessage(settingsService, request);
        }
        throw new IllegalArgumentException("Unsupported Feishu action: " + actionKey);
    }

    @Override
    public List<RemoteCollabDebugActionDescriptor> getDebugActions() {
        return DEBUG_ACTIONS;
    }

    private String normalizeTestMessage(String message) {
        return message == null || message.trim().isEmpty() ? DEFAULT_TEST_MESSAGE : message.trim();
    }

    private String readOptionalMessage(JsonObject request) {
        if (request == null || !request.has("message") || request.get("message").isJsonNull()) {
            return DEFAULT_TEST_MESSAGE;
        }
        return normalizeTestMessage(request.get("message").getAsString());
    }

    private JsonObject handleInboundMessage(CodemossSettingsService settingsService, JsonObject request) throws IOException {
        FeishuEventSubscriber.HandleResult result = delegate.handleInboundMessage(
            new FeishuIncomingMessage(
                readOptionalField(request, "openId"),
                readOptionalField(request, "chatId"),
                readOptionalField(request, "message")
            )
        );
        JsonObject payload = new JsonObject();
        payload.addProperty("handled", result.isHandled());
        payload.addProperty("completed", result.isCompleted());
        payload.addProperty("message", result.getReplyText());
        persistProviderStatus(settingsService, delegate.getConnectionStatus().getValue(), "");
        return payload;
    }

    private String readOptionalField(JsonObject request, String key) {
        if (request == null || !request.has(key) || request.get(key).isJsonNull()) {
            return "";
        }
        String value = request.get(key).getAsString();
        return value == null ? "" : value.trim();
    }

    private void persistProviderStatus(CodemossSettingsService settingsService, String connectionStatus, String lastError) throws IOException {
        JsonObject config = settingsService.getRemoteCollabProviderConfig(PROVIDER_ID);
        config.addProperty("connectionStatus", connectionStatus == null || connectionStatus.trim().isEmpty() ? "disabled" : connectionStatus.trim());
        config.addProperty("lastError", lastError == null ? "" : lastError.trim());
        settingsService.saveRemoteCollabProviderConfig(PROVIDER_ID, config);
    }

    private String readStatus(JsonObject result, RemoteConnectionStatus fallbackStatus) {
        if (result != null && result.has("status") && !result.get("status").isJsonNull()) {
            String value = result.get("status").getAsString();
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return fallbackStatus == null ? RemoteConnectionStatus.DISABLED.getValue() : fallbackStatus.getValue();
    }

    private String safeMessage(Exception error) {
        if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()) {
            return "unknown feishu error";
        }
        return error.getMessage().trim();
    }

    /**
     * Feishu provider 的最小运行时委托接口。
     * 先把 provider 抽象和真实 HTTP/事件接线解耦，便于测试先行。
     */
    public interface FeishuRuntimeDelegate {
        RemoteConnectionStatus getConnectionStatus();

        void initialize();

        void shutdown();

        void publishTaskEvent(RemoteTaskEvent event);

        void publishPendingRequest(RemotePendingRequest request);

        JsonObject startBinding() throws IOException;

        JsonObject healthCheck() throws IOException;

        void sendTestMessage(String message) throws IOException;

        FeishuEventSubscriber.HandleResult handleInboundMessage(FeishuIncomingMessage message) throws IOException;
    }

    interface FeishuClientFactory {
        FeishuMessageClient create(String appId, String appSecret) throws IOException;
    }

    static final class LiveFeishuRuntimeDelegate implements FeishuRuntimeDelegate {
        private final CodemossSettingsService settingsService;
        private final FeishuBindingService bindingService;
        private final FeishuMessageFormatter messageFormatter;
        private final FeishuEventSubscriber eventSubscriber;
        private final FeishuClientFactory clientFactory;
        private volatile RemoteConnectionStatus connectionStatus = RemoteConnectionStatus.DISABLED;

        private LiveFeishuRuntimeDelegate(CodemossSettingsService settingsService) {
            this(
                settingsService,
                new FeishuBindingService(),
                new FeishuMessageFormatter(),
                new FeishuEventSubscriber(settingsService, RemoteRequestRegistry.getGlobalInstance()),
                FeishuMessageClient::new
            );
        }

        LiveFeishuRuntimeDelegate(
            CodemossSettingsService settingsService,
            FeishuBindingService bindingService,
            FeishuMessageFormatter messageFormatter,
            FeishuEventSubscriber eventSubscriber,
            FeishuClientFactory clientFactory
        ) {
            this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
            this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
            this.messageFormatter = Objects.requireNonNull(messageFormatter, "messageFormatter");
            this.eventSubscriber = Objects.requireNonNull(eventSubscriber, "eventSubscriber");
            this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return connectionStatus;
        }

        @Override
        public void initialize() {
            try {
                JsonObject config = settingsService.getRemoteCollabProviderConfig(PROVIDER_ID);
                boolean enabled = config.has("enabled") && !config.get("enabled").isJsonNull() && config.get("enabled").getAsBoolean();
                connectionStatus = enabled ? RemoteConnectionStatus.DISCONNECTED : RemoteConnectionStatus.DISABLED;
            } catch (IOException e) {
                connectionStatus = RemoteConnectionStatus.ERROR;
            }
        }

        @Override
        public void shutdown() {
            connectionStatus = RemoteConnectionStatus.DISABLED;
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
            if (event == null) {
                return;
            }
            try {
                JsonObject config = settingsService.getRemoteCollabProviderConfig(PROVIDER_ID);
                String boundOpenId = readRequiredText(config, "boundOpenId");
                String tenantAccessToken = readTenantAccessToken(createClient().getTenantAccessToken());
                createClient().sendTextMessage(tenantAccessToken, boundOpenId, messageFormatter.formatTaskEvent(event));
                // 增加关键出站日志，便于联调时区分“状态未产生”和“状态已产生但发送失败”。
                LOG.info("[FeishuRemoteCollabProvider] Sent task event to Feishu. state=" + event.getTaskState() + ", requestId=" + event.getRequestId());
                connectionStatus = RemoteConnectionStatus.CONNECTED;
            } catch (IOException e) {
                LOG.warn("[FeishuRemoteCollabProvider] Failed to send task event: " + safeRuntimeMessage(e), e);
                connectionStatus = RemoteConnectionStatus.ERROR;
            }
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
            if (request == null) {
                return;
            }
            try {
                JsonObject config = settingsService.getRemoteCollabProviderConfig(PROVIDER_ID);
                String boundOpenId = readRequiredText(config, "boundOpenId");
                String tenantAccessToken = readTenantAccessToken(createClient().getTenantAccessToken());
                createClient().sendTextMessage(tenantAccessToken, boundOpenId, messageFormatter.formatPendingRequest(request));
                // 增加待处理请求日志，方便定位审批/问答消息是否真正出站。
                LOG.info("[FeishuRemoteCollabProvider] Sent pending request to Feishu. type=" + request.getRequestType() + ", requestId=" + request.getRequestId());
                connectionStatus = RemoteConnectionStatus.CONNECTED;
            } catch (IOException e) {
                LOG.warn("[FeishuRemoteCollabProvider] Failed to send pending request: " + safeRuntimeMessage(e), e);
                connectionStatus = RemoteConnectionStatus.ERROR;
            }
        }

        @Override
        public JsonObject startBinding() throws IOException {
            connectionStatus = RemoteConnectionStatus.CONNECTING;
            JsonObject result = bindingService.startBinding(settingsService);
            LOG.info("[FeishuRemoteCollabProvider] Started Feishu binding flow.");
            return result;
        }

        @Override
        public JsonObject healthCheck() throws IOException {
            connectionStatus = RemoteConnectionStatus.CONNECTING;
            FeishuMessageClient client = createClient();
            JsonObject tokenResponse = client.getTenantAccessToken();
            String tenantAccessToken = readTenantAccessToken(tokenResponse);
            connectionStatus = tenantAccessToken.isEmpty() ? RemoteConnectionStatus.DISCONNECTED : RemoteConnectionStatus.CONNECTED;

            JsonObject result = new JsonObject();
            result.addProperty("status", connectionStatus.getValue());
            result.addProperty("tenantAccessTokenReady", !tenantAccessToken.isEmpty());
            return result;
        }

        @Override
        public void sendTestMessage(String message) throws IOException {
            JsonObject config = settingsService.getRemoteCollabProviderConfig(PROVIDER_ID);
            String boundOpenId = readRequiredText(config, "boundOpenId");
            FeishuMessageClient client = createClient();
            String tenantAccessToken = readTenantAccessToken(client.getTenantAccessToken());
            client.sendTextMessage(tenantAccessToken, boundOpenId, message);
            LOG.info("[FeishuRemoteCollabProvider] Sent Feishu test message to bound user.");
            connectionStatus = RemoteConnectionStatus.CONNECTED;
        }

        @Override
        public FeishuEventSubscriber.HandleResult handleInboundMessage(FeishuIncomingMessage message) throws IOException {
            FeishuBindingService.BindingHandleResult bindingResult = bindingService.handleBindingMessage(settingsService, message);
            if (bindingResult.isHandled()) {
                // 绑定命令单独打点，便于区分“事件到了但未通过绑定校验”和“事件根本未到达”。
                LOG.info("[FeishuRemoteCollabProvider] Processed Feishu binding command. bound=" + bindingResult.isBound());
                connectionStatus = bindingResult.isBound() ? RemoteConnectionStatus.CONNECTED : RemoteConnectionStatus.ERROR;
                return FeishuEventSubscriber.HandleResult.handled(bindingResult.isBound(), bindingResult.getReplyText());
            }
            FeishuEventSubscriber.HandleResult result = eventSubscriber.handleIncomingMessage(message);
            if (result.isCompleted()) {
                LOG.info("[FeishuRemoteCollabProvider] Completed inbound Feishu action.");
                connectionStatus = RemoteConnectionStatus.CONNECTED;
            } else if (result.isHandled()) {
                LOG.info("[FeishuRemoteCollabProvider] Ignored or rejected inbound Feishu action: " + result.getReplyText());
            }
            return result;
        }

        private FeishuMessageClient createClient() throws IOException {
            JsonObject config = settingsService.getRemoteCollabProviderConfig(PROVIDER_ID);
            // 通过可注入工厂创建 client，便于 provider 级闭环测试验证绑定、出站与入站串联行为。
            return clientFactory.create(
                readRequiredText(config, "appId"),
                readRequiredText(config, "appSecret")
            );
        }

        private String readTenantAccessToken(JsonObject response) throws IOException {
            if (response == null || !response.has("tenant_access_token") || response.get("tenant_access_token").isJsonNull()) {
                throw new IOException("Feishu tenant access token is missing");
            }
            return response.get("tenant_access_token").getAsString().trim();
        }

        private String readRequiredText(JsonObject config, String key) throws IOException {
            if (config == null || !config.has(key) || config.get(key).isJsonNull()) {
                throw new IOException("Feishu config is missing " + key);
            }
            String value = config.get(key).getAsString();
            if (value == null || value.trim().isEmpty()) {
                throw new IOException("Feishu config is missing " + key);
            }
            return value.trim();
        }

        private static String safeRuntimeMessage(Exception error) {
            if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty()) {
                return "unknown feishu runtime error";
            }
            return error.getMessage().trim();
        }
    }
}
