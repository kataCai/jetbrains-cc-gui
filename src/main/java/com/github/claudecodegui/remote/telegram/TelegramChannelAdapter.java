package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteTaskChannel;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.Objects;

/**
 * Telegram 远程协作通道适配器。
 * 负责把统一远程协作事件转换为 Telegram 消息，并管理 polling 生命周期。
 * 这里同时承担“配置解析 + 状态落盘 + polling 单活跃协调”的职责，
 * 因此所有 Telegram 相关副作用都收口在这个类里，避免分散到 handler/service 多处。
 */
public class TelegramChannelAdapter implements RemoteTaskChannel {

    private static final Logger LOG = Logger.getInstance(TelegramChannelAdapter.class);

    private final CodemossSettingsService settingsService;
    private final TelegramMessageFormatter messageFormatter;
    private final TelegramClientFactory clientFactory;
    private final TelegramBindingService bindingService;
    private final TelegramPollingCoordinator pollingCoordinator;
    private final PollingReceiverFactory pollingReceiverFactory;

    private volatile RemoteConnectionStatus connectionStatus = RemoteConnectionStatus.DISCONNECTED;
    private volatile PollingReceiverHandle pollingReceiver;
    private volatile TelegramPollingCoordinator.Lease pollingLease;

    public TelegramChannelAdapter(CodemossSettingsService settingsService) {
        this(
            settingsService,
            new TelegramMessageFormatter(),
            TelegramMessageClient::new,
            new TelegramBindingService(),
            new TelegramPollingCoordinator(),
            (service, client, binding) -> new DefaultPollingReceiverHandle(
                new TelegramPollingReceiver(service, client, binding)
            )
        );
    }

    TelegramChannelAdapter(
        CodemossSettingsService settingsService,
        TelegramMessageFormatter messageFormatter,
        TelegramClientFactory clientFactory
    ) {
        this(
            settingsService,
            messageFormatter,
            clientFactory,
            new TelegramBindingService(),
            new TelegramPollingCoordinator(),
            (service, client, binding) -> new DefaultPollingReceiverHandle(
                new TelegramPollingReceiver(service, client, binding)
            )
        );
    }

    TelegramChannelAdapter(
        CodemossSettingsService settingsService,
        TelegramMessageFormatter messageFormatter,
        TelegramClientFactory clientFactory,
        TelegramBindingService bindingService
    ) {
        this(
            settingsService,
            messageFormatter,
            clientFactory,
            bindingService,
            new TelegramPollingCoordinator(),
            (service, client, binding) -> new DefaultPollingReceiverHandle(
                new TelegramPollingReceiver(service, client, binding)
            )
        );
    }

    TelegramChannelAdapter(
        CodemossSettingsService settingsService,
        TelegramMessageFormatter messageFormatter,
        TelegramClientFactory clientFactory,
        TelegramBindingService bindingService,
        TelegramPollingCoordinator pollingCoordinator,
        PollingReceiverFactory pollingReceiverFactory
    ) {
        this.settingsService = Objects.requireNonNull(settingsService, "settingsService");
        this.messageFormatter = Objects.requireNonNull(messageFormatter, "messageFormatter");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.bindingService = Objects.requireNonNull(bindingService, "bindingService");
        this.pollingCoordinator = Objects.requireNonNull(pollingCoordinator, "pollingCoordinator");
        this.pollingReceiverFactory = Objects.requireNonNull(pollingReceiverFactory, "pollingReceiverFactory");
    }

    @Override
    public String getChannelId() {
        return "telegram";
    }

    @Override
    public RemoteConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    /**
     * 初始化 Telegram 通道。
     * 初始化成功后即便还没有 chatId，也会把连接状态持久化为 connecting/connected，便于设置页给出正确提示。
     */
    @Override
    public void initialize() {
        TelegramRuntimeConfig config = resolveRuntimeConfig();
        if (config == null) {
            return;
        }
        connectionStatus = RemoteConnectionStatus.CONNECTING;
        try {
            TelegramMessageClient client = clientFactory.create(config.botToken());
            client.getMe();
            ensurePollingStarted(config, client, false);
            connectionStatus = RemoteConnectionStatus.CONNECTED;
            updatePersistedStatus(RemoteConnectionStatus.CONNECTED, "");
        } catch (IOException e) {
            markError("initialize", e);
        }
    }

    /**
     * 停止 polling 并释放单活跃 lease。
     * 必须先停 receiver 再释放 lease，否则其他 IDE 实例可能抢到 lease 后仍与旧 receiver 并发轮询。
     */
    @Override
    public void shutdown() {
        if (pollingReceiver != null) {
            pollingReceiver.stop();
            pollingReceiver = null;
        }
        releasePollingLease();
        connectionStatus = RemoteConnectionStatus.DISCONNECTED;
        updatePersistedStatus(RemoteConnectionStatus.DISCONNECTED, "");
    }

    /**
     * 返回当前实例是否真正处于“可接收入站更新”的状态。
     */
    public boolean isPollingActive() {
        return pollingReceiver != null && pollingReceiver.isRunning();
    }

    /**
     * 发布任务状态更新。
     * 为了降低 Telegram 噪音，这里会先做 shouldPublishTaskEvent 判断，只推送关键节点。
     */
    @Override
    public void publishTaskEvent(RemoteTaskEvent event) {
        if (!shouldPublishTaskEvent(event)) {
            return;
        }
        sendMessage(messageFormatter.formatTaskEvent(event));
    }

    /**
     * 发布需要远程处理的待办请求，例如审批、问题回答等。
     */
    @Override
    public void publishPendingRequest(RemotePendingRequest request) {
        sendMessage(messageFormatter.formatPendingRequest(request));
    }

    /**
     * 生成一次性 Telegram 绑定链接，并在必要时确保 polling 已启动。
     */
    public JsonObject startBinding() throws IOException {
        TelegramBindingConfig config = resolveBindingConfig();
        TelegramMessageClient client = clientFactory.create(config.botToken());
        JsonObject result = bindingService.startBinding(settingsService, client);
        ensurePollingStarted(
            new TelegramRuntimeConfig(config.botToken(), config.chatId(), config.pollingEnabled(), config.singleActive()),
            client,
            true
        );
        connectionStatus = RemoteConnectionStatus.CONNECTING;
        return result;
    }

    /**
     * 发送测试消息，帮助用户快速验证 botToken/chatId 是否可用。
     */
    public void sendTestMessage(String text) throws IOException {
        TelegramRuntimeConfig config = resolveRuntimeConfig();
        if (config == null) {
            throw new IOException("Telegram chat is not bound");
        }
        TelegramMessageClient client = clientFactory.create(config.botToken());
        client.sendMessage(config.chatId(), text, null);
        connectionStatus = RemoteConnectionStatus.CONNECTED;
        updatePersistedStatus(RemoteConnectionStatus.CONNECTED, "");
    }

    private void sendMessage(TelegramOutgoingMessage message) {
        if (message == null) {
            return;
        }
        TelegramRuntimeConfig config = resolveRuntimeConfig();
        if (config == null) {
            return;
        }
        try {
            TelegramMessageClient client = clientFactory.create(config.botToken());
            client.sendMessage(config.chatId(), message.text(), message.replyMarkup());
            connectionStatus = RemoteConnectionStatus.CONNECTED;
            updatePersistedStatus(RemoteConnectionStatus.CONNECTED, "");
        } catch (IOException e) {
            markError("sendMessage", e);
        }
    }

    private boolean shouldPublishTaskEvent(RemoteTaskEvent event) {
        if (event == null || event.getTaskState() == null) {
            return false;
        }
        return "completed".equals(event.getTaskState())
            || "final_error".equals(event.getTaskState())
            || "waiting_confirm".equals(event.getTaskState());
    }

    private TelegramRuntimeConfig resolveRuntimeConfig() {
        try {
            if (!settingsService.isRemoteCollabEnabled()) {
                connectionStatus = RemoteConnectionStatus.DISABLED;
                return null;
            }
            JsonObject telegram = settingsService.getTelegramConfig();
            String botToken = readString(telegram, "botToken");
            String chatId = readString(telegram, "chatId");
            if (!isNotBlank(botToken) || !isNotBlank(chatId)) {
                connectionStatus = RemoteConnectionStatus.DISCONNECTED;
                return null;
            }
            boolean pollingEnabled = !telegram.has("pollingEnabled")
                || telegram.get("pollingEnabled").isJsonNull()
                || telegram.get("pollingEnabled").getAsBoolean();
            boolean singleActive = !telegram.has("singleActive")
                || telegram.get("singleActive").isJsonNull()
                || telegram.get("singleActive").getAsBoolean();
            return new TelegramRuntimeConfig(botToken, chatId, pollingEnabled, singleActive);
        } catch (IOException e) {
            markError("loadConfig", e);
            return null;
        }
    }

    private TelegramBindingConfig resolveBindingConfig() throws IOException {
        JsonObject telegram = settingsService.getTelegramConfig();
        String botToken = readString(telegram, "botToken");
        if (!isNotBlank(botToken)) {
            throw new IOException("Please save Telegram Bot Token first");
        }
        String chatId = readString(telegram, "chatId");
        boolean pollingEnabled = !telegram.has("pollingEnabled")
            || telegram.get("pollingEnabled").isJsonNull()
            || telegram.get("pollingEnabled").getAsBoolean();
        boolean singleActive = !telegram.has("singleActive")
            || telegram.get("singleActive").isJsonNull()
            || telegram.get("singleActive").getAsBoolean();
        return new TelegramBindingConfig(botToken, chatId, pollingEnabled, singleActive);
    }

    private String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void markError(String phase, Exception e) {
        connectionStatus = RemoteConnectionStatus.ERROR;
        updatePersistedStatus(RemoteConnectionStatus.ERROR, e.getMessage());
        LOG.warn("[TelegramChannelAdapter] Failed to " + phase + ": " + e.getMessage());
    }

    private boolean ensurePollingStarted(
        TelegramRuntimeConfig config,
        TelegramMessageClient client,
        boolean requirePolling
    ) throws IOException {
        if (config == null) {
            if (requirePolling) {
                throw new IOException("Telegram polling configuration is missing");
            }
            return false;
        }
        if (!config.pollingEnabled()) {
            if (requirePolling) {
                throw new IOException("Telegram binding requires pollingEnabled to be turned on");
            }
            return false;
        }
        if (pollingReceiver != null && pollingReceiver.isRunning()) {
            return true;
        }

        // 多 IDE 进程共享同一个 Bot Token 时，只允许一个实例负责入站 polling。
        TelegramPollingCoordinator.Lease acquiredLease = null;
        if (config.singleActive() && pollingLease == null) {
            acquiredLease = pollingCoordinator.tryAcquire(config.botToken());
            if (acquiredLease == null) {
                if (requirePolling) {
                    throw new IOException("Another IDE instance is already receiving Telegram updates for this bot.");
                }
                return false;
            }
            pollingLease = acquiredLease;
        }

        try {
            client.deleteWebhook(true);
            pollingReceiver = pollingReceiverFactory.create(settingsService, client, bindingService);
            pollingReceiver.start();
            return true;
        } catch (IOException | RuntimeException e) {
            if (acquiredLease != null) {
                releasePollingLease();
            }
            throw e;
        }
    }

    private void releasePollingLease() {
        if (pollingLease != null) {
            pollingLease.release();
            pollingLease = null;
        }
    }

    /**
     * 将运行时连接状态写回设置，保证设置页、状态栏和通知提示读取的是同一份状态。
     */
    private void updatePersistedStatus(RemoteConnectionStatus status, String lastError) {
        try {
            JsonObject telegram = settingsService.getTelegramConfig();
            telegram.addProperty("connectionStatus", status.getValue());
            telegram.addProperty("lastError", lastError == null ? "" : lastError);
            settingsService.saveTelegramConfig(telegram);
        } catch (IOException e) {
            LOG.debug("[TelegramChannelAdapter] Failed to persist status: " + e.getMessage());
        }
    }

    interface TelegramClientFactory {
        TelegramMessageClient create(String botToken);
    }

    interface PollingReceiverFactory {
        PollingReceiverHandle create(
            CodemossSettingsService settingsService,
            TelegramMessageClient client,
            TelegramBindingService bindingService
        );
    }

    interface PollingReceiverHandle {
        void start();

        void stop();

        boolean isRunning();
    }

    private static final class DefaultPollingReceiverHandle implements PollingReceiverHandle {
        private final TelegramPollingReceiver delegate;

        private DefaultPollingReceiverHandle(TelegramPollingReceiver delegate) {
            this.delegate = delegate;
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }
    }

    private record TelegramRuntimeConfig(String botToken, String chatId, boolean pollingEnabled, boolean singleActive) {
    }

    private record TelegramBindingConfig(String botToken, String chatId, boolean pollingEnabled, boolean singleActive) {
    }
}
