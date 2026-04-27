package com.github.claudecodegui.remote.providers.telegram;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugActionDescriptor;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Telegram provider 是否把运行时能力完整收口到 provider 抽象层，
 * 避免 `RemoteCollabService` 继续直接依赖 `TelegramChannelAdapter`。
 */
public class TelegramRemoteCollabProviderTest {

    @Test
    public void shouldDelegateLifecycleAndPublishOperationsToTelegramDelegate() throws Exception {
        FakeTelegramDelegate delegate = new FakeTelegramDelegate();
        TelegramRemoteCollabProvider provider = new TelegramRemoteCollabProvider(delegate);
        RemotePendingRequest request = new RemotePendingRequest(
            "request-1",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "/project",
            new JsonObject(),
            ignored -> {
            }
        );
        RemoteTaskEvent event = new RemoteTaskEvent("session-1", "/project", "request-1", "completed", "title", "summary");

        provider.initialize();
        provider.publishTaskEvent(event);
        provider.publishPendingRequest(request);
        provider.shutdown();

        assertTrue(delegate.initializeCalled);
        assertSame(event, delegate.lastTaskEvent);
        assertSame(request, delegate.lastPendingRequest);
        assertTrue(delegate.shutdownCalled);
        assertEquals(RemoteConnectionStatus.CONNECTED, provider.getConnectionStatus());
    }

    @Test
    public void shouldDelegateTelegramSpecificOperationsAndExposeDebugActions() throws Exception {
        FakeTelegramDelegate delegate = new FakeTelegramDelegate();
        TelegramRemoteCollabProvider provider = new TelegramRemoteCollabProvider(delegate);
        CodemossSettingsService settingsService = new CodemossSettingsService();
        JsonObject request = new JsonObject();
        request.addProperty("message", "hello telegram");

        JsonObject bindingResult = provider.startBinding(settingsService);
        provider.sendTestMessage(settingsService, "hello telegram");
        JsonObject actionBindingResult = provider.executeAction(settingsService, "start_binding", new JsonObject());
        JsonObject actionTestResult = provider.executeAction(settingsService, "send_test_message", request);
        JsonObject actionHealthResult = provider.executeAction(settingsService, "test_connection", request);

        assertSame(delegate.bindingResult, bindingResult);
        assertSame(delegate.bindingResult, actionBindingResult);
        assertEquals("hello telegram", delegate.lastTestMessage);
        assertEquals("Telegram 测试消息已发送", actionTestResult.get("message").getAsString());
        assertEquals("Telegram 测试消息已发送", actionHealthResult.get("message").getAsString());
        assertTrue(provider.isCurrentInstanceReceivingUpdates());

        Set<RemoteCollabCapability> capabilities = provider.getDescriptor().getCapabilities();
        assertTrue(capabilities.contains(RemoteCollabCapability.BINDING));
        assertTrue(capabilities.contains(RemoteCollabCapability.INLINE_ACTION_CALLBACK));
        assertEquals("telegram", provider.getDescriptor().getProviderId());

        List<RemoteCollabDebugActionDescriptor> debugActions = provider.getDebugActions();
        assertEquals(3, debugActions.size());
        assertEquals("start_binding", debugActions.get(0).getActionKey());
        assertEquals("send_test_message", debugActions.get(1).getActionKey());
        assertEquals("test_connection", debugActions.get(2).getActionKey());
    }

    private static final class FakeTelegramDelegate implements TelegramRemoteCollabProvider.TelegramRuntimeDelegate {
        private final JsonObject bindingResult = new JsonObject();
        private RemoteConnectionStatus connectionStatus = RemoteConnectionStatus.CONNECTED;
        private boolean initializeCalled;
        private boolean shutdownCalled;
        private RemoteTaskEvent lastTaskEvent;
        private RemotePendingRequest lastPendingRequest;
        private String lastTestMessage;

        private FakeTelegramDelegate() {
            bindingResult.addProperty("bindingUrl", "https://t.me/cc_gui_bot?start=token");
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return connectionStatus;
        }

        @Override
        public void initialize() {
            initializeCalled = true;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
            lastTaskEvent = event;
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
            lastPendingRequest = request;
        }

        @Override
        public JsonObject startBinding() {
            return bindingResult;
        }

        @Override
        public void sendTestMessage(String message) {
            lastTestMessage = message;
        }

        @Override
        public boolean isCurrentInstanceReceivingUpdates() {
            return true;
        }
    }
}
