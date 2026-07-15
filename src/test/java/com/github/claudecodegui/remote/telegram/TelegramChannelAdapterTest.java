package com.github.claudecodegui.remote.telegram;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestType;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class TelegramChannelAdapterTest {

    @Test
    public void shouldInitializeByCallingGetMeAndSetConnectedStatus() throws Exception {
        RecordingTelegramClient client = new RecordingTelegramClient();
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram()),
            new TelegramMessageFormatter(),
            token -> client
        );

        adapter.initialize();

        assertTrue(client.getMeCalled);
        assertEquals(RemoteConnectionStatus.CONNECTED, adapter.getConnectionStatus());
    }

    @Test
    public void shouldSendCompletedTaskEventToTelegramWhenConfigured() {
        RecordingTelegramClient client = new RecordingTelegramClient();
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram()),
            new TelegramMessageFormatter(),
            token -> client
        );

        adapter.publishTaskEvent(new RemoteTaskEvent(
            "session-1",
            "E:/workspace/demo-project",
            null,
            "completed",
            "completed",
            "修复推送失败"
        ));

        assertEquals(1, client.sentMessages.size());
        assertTrue(client.sentMessages.get(0).text.contains("修复推送失败"));
        assertEquals(RemoteConnectionStatus.CONNECTED, adapter.getConnectionStatus());
    }

    @Test
    public void shouldSendPlanApprovalPendingRequestToTelegram() {
        RecordingTelegramClient client = new RecordingTelegramClient();
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram()),
            new TelegramMessageFormatter(),
            token -> client
        );
        JsonObject payload = new JsonObject();
        payload.addProperty("title", "Review plan");

        adapter.publishPendingRequest(new RemotePendingRequest(
            "req-plan",
            RemoteRequestType.PLAN_APPROVAL,
            "session-1",
            "E:/workspace/demo-project",
            payload,
            ignored -> {
            }
        ));

        assertEquals(1, client.sentMessages.size());
        assertTrue(client.sentMessages.get(0).text.contains("Review plan"));
        assertTrue(client.sentMessages.get(0).replyMarkup.has("inline_keyboard"));
    }

    @Test
    public void shouldMarkErrorStatusWhenTelegramSendFails() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram()),
            new TelegramMessageFormatter(),
            token -> new FailingTelegramClient()
        );

        adapter.publishTaskEvent(new RemoteTaskEvent(
            "session-1",
            "E:/workspace/demo-project",
            null,
            "completed",
            "completed",
            "修复推送失败"
        ));

        assertEquals(RemoteConnectionStatus.ERROR, adapter.getConnectionStatus());
    }

    @Test
    public void shouldReuseSamePollingReceiverWhenInitializedTwiceInSameProcess() throws Exception {
        RecordingTelegramClient client = new RecordingTelegramClient();
        RecordingPollingCoordinator pollingCoordinator = new RecordingPollingCoordinator(true);
        RecordingPollingReceiver receiver = new RecordingPollingReceiver();
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram(true, true)),
            new TelegramMessageFormatter(),
            token -> client,
            new TelegramBindingService(),
            pollingCoordinator,
            (settingsService, telegramClient, bindingService) -> receiver
        );

        adapter.initialize();
        adapter.initialize();

        assertEquals(1, pollingCoordinator.acquireCalls);
        assertEquals(1, receiver.startCalls);
        assertTrue(adapter.isPollingActive());
        adapter.shutdown();
    }

    @Test
    public void shouldStayConnectedButNotPollWhenSingleActiveLeaseIsUnavailable() throws Exception {
        RecordingTelegramClient client = new RecordingTelegramClient();
        RecordingPollingCoordinator pollingCoordinator = new RecordingPollingCoordinator(false);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram(true, true)),
            new TelegramMessageFormatter(),
            token -> client,
            new TelegramBindingService(),
            pollingCoordinator,
            (settingsService, telegramClient, bindingService) -> new RecordingPollingReceiver()
        );

        adapter.initialize();

        assertEquals(RemoteConnectionStatus.CONNECTED, adapter.getConnectionStatus());
        assertFalse(adapter.isPollingActive());
        assertEquals(1, pollingCoordinator.acquireCalls);
    }

    @Test
    public void shouldFailBindingWhenSingleActiveLeaseIsUnavailable() {
        RecordingTelegramClient client = new RecordingTelegramClient();
        RecordingPollingCoordinator pollingCoordinator = new RecordingPollingCoordinator(false);
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram(true, true)),
            new TelegramMessageFormatter(),
            token -> client,
            new TelegramBindingService(),
            pollingCoordinator,
            (settingsService, telegramClient, bindingService) -> new RecordingPollingReceiver()
        );

        IOException exception = assertThrows(IOException.class, adapter::startBinding);

        assertTrue(exception.getMessage().contains("Another IDE instance"));
    }

    @Test
    public void shouldThrowWhenSendingTestMessageWithoutBoundChat() {
        RecordingTelegramClient client = new RecordingTelegramClient();
        JsonObject telegram = configuredTelegram(false, false);
        telegram.addProperty("chatId", "");
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, telegram),
            new TelegramMessageFormatter(),
            token -> client
        );

        IOException exception = assertThrows(IOException.class, () -> adapter.sendTestMessage("hello"));

        assertTrue(exception.getMessage().contains("not bound"));
    }

    @Test
    public void shouldMarkErrorWhenGetMeFailsDuringInitialize() {
        TelegramChannelAdapter adapter = new TelegramChannelAdapter(
            new StubSettingsService(true, configuredTelegram(false, false)),
            new TelegramMessageFormatter(),
            token -> new FailingGetMeTelegramClient()
        );

        adapter.initialize();

        assertEquals(RemoteConnectionStatus.ERROR, adapter.getConnectionStatus());
    }

    private JsonObject configuredTelegram() {
        return configuredTelegram(false, false);
    }

    private JsonObject configuredTelegram(boolean pollingEnabled, boolean singleActive) {
        JsonObject telegram = new JsonObject();
        telegram.addProperty("botToken", "bot-token");
        telegram.addProperty("chatId", "42");
        telegram.addProperty("pollingEnabled", pollingEnabled);
        telegram.addProperty("singleActive", singleActive);
        telegram.addProperty("connectionStatus", "disabled");
        telegram.addProperty("lastError", "");
        return telegram;
    }

    private static class StubSettingsService extends CodemossSettingsService {
        private final boolean enabled;
        private final JsonObject telegram;

        private StubSettingsService(boolean enabled, JsonObject telegram) {
            this.enabled = enabled;
            this.telegram = telegram.deepCopy();
        }

        @Override
        public boolean isRemoteCollabEnabled() {
            return enabled;
        }

        @Override
        public JsonObject getTelegramConfig() {
            return telegram.deepCopy();
        }
    }

    private static class RecordingTelegramClient extends TelegramMessageClient {
        private boolean getMeCalled;
        private final List<SentMessage> sentMessages = new ArrayList<>();

        private RecordingTelegramClient() {
            super("bot-token", (url, body) -> "{\"ok\":true,\"result\":{}}");
        }

        @Override
        public JsonObject getMe() {
            getMeCalled = true;
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            JsonObject result = new JsonObject();
            result.addProperty("username", "cc_gui_bot");
            response.add("result", result);
            return response;
        }

        @Override
        public JsonObject sendMessage(String chatId, String text, JsonObject replyMarkup) {
            sentMessages.add(new SentMessage(chatId, text, replyMarkup == null ? null : replyMarkup.deepCopy()));
            JsonObject response = new JsonObject();
            response.addProperty("ok", true);
            return response;
        }
    }

    private static class FailingTelegramClient extends TelegramMessageClient {
        private FailingTelegramClient() {
            super("bot-token", (url, body) -> "{\"ok\":true,\"result\":{}}");
        }

        @Override
        public JsonObject sendMessage(String chatId, String text, JsonObject replyMarkup) throws IOException {
            throw new IOException("telegram offline");
        }
    }

    private static class FailingGetMeTelegramClient extends TelegramMessageClient {
        private FailingGetMeTelegramClient() {
            super("bot-token", (url, body) -> "{\"ok\":true,\"result\":{}}");
        }

        @Override
        public JsonObject getMe() throws IOException {
            throw new IOException("invalid token");
        }
    }

    private static class RecordingPollingCoordinator extends TelegramPollingCoordinator {
        private final boolean grantLease;
        private int acquireCalls;

        private RecordingPollingCoordinator(boolean grantLease) {
            super(createLockRoot());
            this.grantLease = grantLease;
        }

        @Override
        Lease tryAcquire(String botToken) {
            acquireCalls++;
            if (!grantLease) {
                return null;
            }
            return new Lease() {
                @Override
                public void release() {
                }
            };
        }

        private static Path createLockRoot() {
            try {
                return Files.createTempDirectory("telegram-polling-test-locks");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static class RecordingPollingReceiver implements TelegramChannelAdapter.PollingReceiverHandle {
        private int startCalls;
        private boolean running;

        @Override
        public void start() {
            startCalls++;
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private record SentMessage(String chatId, String text, JsonObject replyMarkup) {
    }
}
