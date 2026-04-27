package com.github.claudecodegui.remote.providers.telegram;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugActionDescriptor;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderActionHandler;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderDescriptor;
import com.github.claudecodegui.remote.provider.RemoteTelegramOperationsProvider;
import com.github.claudecodegui.remote.telegram.TelegramChannelAdapter;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * Telegram 远程协作 provider。
 * 负责把原先散落在 `RemoteCollabService` 中的 Telegram 专属初始化、绑定、测试消息、
 * 任务事件发送、待处理请求发送和 polling 状态查询统一收口到 provider 抽象层。
 */
public class TelegramRemoteCollabProvider implements RemoteTelegramOperationsProvider, RemoteCollabProviderActionHandler {

    private static final String PROVIDER_ID = "telegram";
    private static final RemoteCollabProviderDescriptor DESCRIPTOR = new RemoteCollabProviderDescriptor(
        PROVIDER_ID,
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
    private static final String DEFAULT_TEST_MESSAGE = "CC GUI Telegram 测试消息";
    private static final String ACTION_START_BINDING = "start_binding";
    private static final String ACTION_SEND_TEST_MESSAGE = "send_test_message";
    private static final String ACTION_TEST_CONNECTION = "test_connection";
    private static final List<RemoteCollabDebugActionDescriptor> DEBUG_ACTIONS = List.of(
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_START_BINDING, "开始绑定", "生成 Telegram 绑定链接并确保 polling 可接收回调。"),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_SEND_TEST_MESSAGE, "发送测试消息", "向当前绑定的 Telegram 会话发送一条测试消息。"),
        new RemoteCollabDebugActionDescriptor(PROVIDER_ID, ACTION_TEST_CONNECTION, "测试连接", "复用测试消息链路，验证当前 Bot 与会话配置是否可用。")
    );

    private final TelegramRuntimeDelegate delegate;

    /**
     * 使用真实 Telegram 适配器创建 provider。
     * 适用于插件正式运行链路，确保 provider 继续复用既有 Telegram 实现细节。
     */
    public TelegramRemoteCollabProvider(CodemossSettingsService settingsService) {
        this(new TelegramChannelRuntimeDelegate(new TelegramChannelAdapter(settingsService)));
    }

    /**
     * 使用可替换 delegate 创建 provider。
     * 主要用于测试和后续扩展，避免单元测试直接触发真实网络与 polling 行为。
     */
    public TelegramRemoteCollabProvider(TelegramRuntimeDelegate delegate) {
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
    public void sendTestMessage(CodemossSettingsService settingsService, String message) throws IOException {
        delegate.sendTestMessage(normalizeTestMessage(message));
    }

    @Override
    public boolean isCurrentInstanceReceivingUpdates() {
        return delegate.isCurrentInstanceReceivingUpdates();
    }

    @Override
    public JsonObject executeAction(CodemossSettingsService settingsService, String actionKey, JsonObject request) throws Exception {
        if (ACTION_START_BINDING.equals(actionKey)) {
            return startBinding(settingsService);
        }
        if (ACTION_SEND_TEST_MESSAGE.equals(actionKey) || ACTION_TEST_CONNECTION.equals(actionKey)) {
            sendTestMessage(settingsService, readOptionalMessage(request));
            JsonObject result = new JsonObject();
            result.addProperty("message", "Telegram 测试消息已发送");
            return result;
        }
        throw new IllegalArgumentException("Unsupported Telegram action: " + actionKey);
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

    /**
     * Telegram provider 的最小运行时委托接口。
     * 通过这一层把 provider 抽象与 `TelegramChannelAdapter` 解耦，便于测试和后续替换。
     */
    public interface TelegramRuntimeDelegate {
        RemoteConnectionStatus getConnectionStatus();

        void initialize();

        void shutdown();

        void publishTaskEvent(RemoteTaskEvent event);

        void publishPendingRequest(RemotePendingRequest request);

        JsonObject startBinding() throws IOException;

        void sendTestMessage(String message) throws IOException;

        boolean isCurrentInstanceReceivingUpdates();
    }

    /**
     * 真实 Telegram 适配器委托。
     * 这里只做一层轻包装，避免外层服务和设置页继续直接 import `TelegramChannelAdapter`。
     */
    private static final class TelegramChannelRuntimeDelegate implements TelegramRuntimeDelegate {
        private final TelegramChannelAdapter adapter;

        private TelegramChannelRuntimeDelegate(TelegramChannelAdapter adapter) {
            this.adapter = Objects.requireNonNull(adapter, "adapter");
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return adapter.getConnectionStatus();
        }

        @Override
        public void initialize() {
            adapter.initialize();
        }

        @Override
        public void shutdown() {
            adapter.shutdown();
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
            adapter.publishTaskEvent(event);
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
            adapter.publishPendingRequest(request);
        }

        @Override
        public JsonObject startBinding() throws IOException {
            return adapter.startBinding();
        }

        @Override
        public void sendTestMessage(String message) throws IOException {
            adapter.sendTestMessage(message);
        }

        @Override
        public boolean isCurrentInstanceReceivingUpdates() {
            return adapter.isPollingActive();
        }
    }
}
