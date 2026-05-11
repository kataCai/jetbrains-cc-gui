package com.github.claudecodegui.remote;

import com.github.claudecodegui.remote.debug.RemoteCollabDebugService;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.remote.provider.RemoteCollabProvider;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderDescriptor;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderRegistry;
import com.github.claudecodegui.remote.provider.RemoteTelegramOperationsProvider;
import com.google.gson.JsonArray;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * ?????????? provider ????????????????????
 */
public class RemoteCollabServiceTest {

    @Test
    public void shouldReportDisabledAfterShutdownClearsActiveChannel() throws Exception {
        RemoteCollabService service = newRemoteCollabService();
        service.setTaskChannel(new NoopRemoteTaskChannel());

        service.shutdown();

        assertEquals("disabled", service.getConnectionStatus());
    }

    @Test
    public void shouldAggregateConnectionStatusFromRegisteredProviders() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        providerRegistry.register(new FakeProvider("telegram", RemoteConnectionStatus.DISCONNECTED, EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH)));
        providerRegistry.register(new FakeProvider("gotify_web", RemoteConnectionStatus.CONNECTED, EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH)));
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        assertEquals("connected", service.getConnectionStatus());
    }

    @Test
    public void shouldInitializeRegisteredProvidersWhenRemoteCollabEnabled() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        FakeProvider provider = new FakeProvider("telegram", RemoteConnectionStatus.CONNECTING, EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH));
        providerRegistry.register(provider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        service.initializeIfEnabled(new EnabledRemoteCollabSettingsService());

        assertTrue(provider.initialized);
    }

    @Test
    public void shouldShutdownRegisteredProviders() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        FakeProvider provider = new FakeProvider("telegram", RemoteConnectionStatus.CONNECTED, EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH));
        providerRegistry.register(provider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        service.shutdown();

        assertTrue(provider.shutdownCalled);
        assertEquals("disabled", service.getConnectionStatus());
    }

    @Test
    public void shouldUseRegisteredTelegramProviderForBinding() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        FakeTelegramOperationsProvider provider = new FakeTelegramOperationsProvider();
        providerRegistry.register(provider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        JsonObject result = service.startTelegramBinding(new EnabledRemoteCollabSettingsService());

        assertSame(provider.bindingResult, result);
        assertTrue(provider.bindingStarted);
    }

    @Test
    public void shouldUseRegisteredTelegramProviderForTestMessage() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        FakeTelegramOperationsProvider provider = new FakeTelegramOperationsProvider();
        providerRegistry.register(provider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        service.sendTelegramTestMessage(new EnabledRemoteCollabSettingsService(), "hello");

        assertEquals("hello", provider.lastTestMessage);
    }

    @Test
    public void shouldReadCurrentInstanceReceivingUpdatesFromRegisteredTelegramProvider() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        FakeTelegramOperationsProvider provider = new FakeTelegramOperationsProvider();
        provider.currentInstanceReceivingUpdates = true;
        providerRegistry.register(provider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        assertTrue(service.isCurrentInstanceReceivingUpdates());
    }

    @Test
    public void shouldRecordPublishedPendingRequestIntoDebugSnapshot() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        providerRegistry.register(new FakeProvider(
            "telegram",
            RemoteConnectionStatus.CONNECTED,
            EnumSet.of(RemoteCollabCapability.PENDING_REQUEST_PUSH)
        ));
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        service.publishPendingRequest(new RemotePendingRequest(
            "req-1",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-1",
            "/project",
            new JsonObject(),
            ignored -> {
            }
        ));

        JsonObject debugRequest = service.getDebugService().getSnapshot().getRecentRequests().get(0);
        assertEquals("telegram", debugRequest.get("providerId").getAsString());
        assertEquals("pending_request", debugRequest.get("category").getAsString());
        assertEquals("req-1", debugRequest.get("requestId").getAsString());
    }

    @Test
    public void shouldRecordProviderErrorsIntoDebugSnapshot() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        providerRegistry.register(new ThrowingProvider(
            "gotify_web",
            EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH),
            new IllegalStateException("network down")
        ));
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        service.publishTaskEvent(new RemoteTaskEvent("session-2", "/project", "req-2", "running", "Build", "summary"));

        JsonObject debugError = service.getDebugService().getSnapshot().getRecentErrors().get(0);
        assertEquals("gotify_web", debugError.get("providerId").getAsString());
        assertEquals("publishTaskEvent", debugError.get("phase").getAsString());
        assertEquals("network down", debugError.get("message").getAsString());
    }

    @Test
    public void shouldBuildProviderOptionsAndRoutingPolicyInViewModel() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        providerRegistry.register(new FakeProvider(
            "gotify_web",
            RemoteConnectionStatus.CONNECTED,
            EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH, RemoteCollabCapability.RESULT_POLLING)
        ));
        RemoteCollabService service = newRemoteCollabService(providerRegistry);

        JsonObject viewModel = service.buildRemoteCollabViewModel(new ConfiguredRemoteCollabSettingsService());

        assertTrue(viewModel.has("routingPolicy"));
        assertEquals("gotify_web", viewModel.getAsJsonObject("routingPolicy").get("interactiveProviderId").getAsString());
        JsonArray notifyProviderIds = viewModel.getAsJsonObject("routingPolicy").getAsJsonArray("notifyProviderIds");
        assertEquals(2, notifyProviderIds.size());

        JsonObject telegram = viewModel.getAsJsonObject("telegram");
        assertNotNull(telegram);
        assertTrue(viewModel.has("providerOptions"));
        JsonObject gotifyOption = findProviderOption(viewModel.getAsJsonArray("providerOptions"), "gotify_web");
        assertNotNull(gotifyOption);
        assertTrue(gotifyOption.get("registered").getAsBoolean());
        assertTrue(gotifyOption.get("enabled").getAsBoolean());
        assertEquals("connected", gotifyOption.get("connectionStatus").getAsString());
        assertTrue(containsCapability(gotifyOption.getAsJsonArray("capabilities"), "RESULT_POLLING"));

        JsonObject telegramOption = findProviderOption(viewModel.getAsJsonArray("providerOptions"), "telegram");
        assertNotNull(telegramOption);
        assertTrue(telegramOption.get("registered").getAsBoolean());
        assertTrue(telegramOption.get("enabled").getAsBoolean());
    }

    @Test
    public void shouldRegisterConfiguredGotifyProviderBeforeBuildingViewModel() throws Exception {
        RemoteCollabService service = newRemoteCollabService();

        JsonObject viewModel = service.buildRemoteCollabViewModel(new ConfiguredRemoteCollabSettingsService());

        JsonObject gotifyOption = findProviderOption(viewModel.getAsJsonArray("providerOptions"), "gotify_web");
        assertNotNull(gotifyOption);
        assertTrue(gotifyOption.get("registered").getAsBoolean());
        assertNotNull(service.getProviderRegistry().getProvider("gotify_web"));
    }

    @Test
    public void shouldRegisterConfiguredFeishuProviderBeforeBuildingViewModel() throws Exception {
        RemoteCollabService service = newRemoteCollabService();

        JsonObject viewModel = service.buildRemoteCollabViewModel(new FeishuConfiguredRemoteCollabSettingsService());

        JsonObject feishuOption = findProviderOption(viewModel.getAsJsonArray("providerOptions"), "feishu");
        assertNotNull(feishuOption);
        assertTrue(feishuOption.get("registered").getAsBoolean());
        assertTrue(feishuOption.get("enabled").getAsBoolean());
        assertNotNull(service.getProviderRegistry().getProvider("feishu"));
    }

    @Test
    public void shouldRoutePendingRequestToConfiguredInteractiveProviderOnly() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        FakeProvider telegramProvider = new FakeProvider(
            "telegram",
            RemoteConnectionStatus.CONNECTED,
            EnumSet.of(RemoteCollabCapability.PENDING_REQUEST_PUSH, RemoteCollabCapability.TASK_EVENT_PUSH)
        );
        FakeProvider gotifyProvider = new FakeProvider(
            "gotify_web",
            RemoteConnectionStatus.CONNECTED,
            EnumSet.of(RemoteCollabCapability.PENDING_REQUEST_PUSH, RemoteCollabCapability.TASK_EVENT_PUSH)
        );
        providerRegistry.register(telegramProvider);
        providerRegistry.register(gotifyProvider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);
        service.initializeIfEnabled(new RoutedRemoteCollabSettingsService("gotify_web", new String[]{"telegram", "gotify_web"}));

        boolean published = service.publishPendingRequest(new RemotePendingRequest(
            "req-route-1",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-1",
            "/project",
            new JsonObject(),
            ignored -> {
            }
        ));

        assertTrue(published);
        assertEquals(0, telegramProvider.pendingRequestCount);
        assertEquals(1, gotifyProvider.pendingRequestCount);
    }

    @Test
    public void shouldRouteTaskEventToConfiguredNotifyProvidersOnly() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        FakeProvider telegramProvider = new FakeProvider(
            "telegram",
            RemoteConnectionStatus.CONNECTED,
            EnumSet.of(RemoteCollabCapability.PENDING_REQUEST_PUSH, RemoteCollabCapability.TASK_EVENT_PUSH)
        );
        FakeProvider gotifyProvider = new FakeProvider(
            "gotify_web",
            RemoteConnectionStatus.CONNECTED,
            EnumSet.of(RemoteCollabCapability.PENDING_REQUEST_PUSH, RemoteCollabCapability.TASK_EVENT_PUSH)
        );
        providerRegistry.register(telegramProvider);
        providerRegistry.register(gotifyProvider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);
        service.initializeIfEnabled(new RoutedRemoteCollabSettingsService("gotify_web", new String[]{"gotify_web"}));

        service.publishTaskEvent(new RemoteTaskEvent("session-2", "/project", "req-event-1", "running", "Build", "summary"));

        assertEquals(0, telegramProvider.taskEventCount);
        assertEquals(1, gotifyProvider.taskEventCount);
    }

    @Test
    public void shouldCompleteProviderPendingRequestThroughRemoteActionRouter() throws Exception {
        RemoteCollabProviderRegistry providerRegistry = new RemoteCollabProviderRegistry();
        CompletingProvider gotifyProvider = new CompletingProvider(
            "gotify_web",
            EnumSet.of(RemoteCollabCapability.PENDING_REQUEST_PUSH)
        );
        providerRegistry.register(gotifyProvider);
        RemoteCollabService service = newRemoteCollabService(providerRegistry);
        service.initializeIfEnabled(new RoutedRemoteCollabSettingsService("gotify_web", new String[]{"gotify_web"}));

        JsonObject expectedResult = new JsonObject();
        expectedResult.addProperty("answer", "confirmed");
        JsonObject[] completedResult = new JsonObject[1];
        RemotePendingRequest request = new RemotePendingRequest(
            "req-route-2",
            RemoteRequestType.ASK_USER_QUESTION,
            "session-2",
            "/project",
            new JsonObject(),
            result -> completedResult[0] = result
        );
        service.registerPendingRequest(request);

        boolean published = service.publishPendingRequest(request);
        gotifyProvider.completePendingRequest(expectedResult);

        assertTrue(published);
        assertEquals(0, service.getRequestRegistry().size());
        assertEquals("confirmed", completedResult[0].get("answer").getAsString());
    }

    private static RemoteCollabService newRemoteCollabService() throws Exception {
        return newRemoteCollabService(new RemoteCollabProviderRegistry());
    }

    private static RemoteCollabService newRemoteCollabService(RemoteCollabProviderRegistry providerRegistry) throws Exception {
        Constructor<RemoteCollabService> constructor =
            RemoteCollabService.class.getDeclaredConstructor(RemoteRequestRegistry.class, RemoteCollabProviderRegistry.class, RemoteCollabDebugService.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new RemoteRequestRegistry(), providerRegistry, new RemoteCollabDebugService());
    }

    private static class NoopRemoteTaskChannel implements RemoteTaskChannel {

        @Override
        public String getChannelId() {
            return "noop";
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return RemoteConnectionStatus.CONNECTED;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
        }
    }

    private static class FakeProvider implements RemoteCollabProvider {
        private final RemoteCollabProviderDescriptor descriptor;
        private final RemoteConnectionStatus connectionStatus;
        private boolean initialized;
        private boolean shutdownCalled;
        private int taskEventCount;
        private int pendingRequestCount;

        private FakeProvider(String providerId, RemoteConnectionStatus connectionStatus, EnumSet<RemoteCollabCapability> capabilities) {
            this.descriptor = new RemoteCollabProviderDescriptor(
                providerId,
                providerId,
                providerId + " provider",
                capabilities
            );
            this.connectionStatus = connectionStatus;
        }

        @Override
        public RemoteCollabProviderDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return shutdownCalled ? RemoteConnectionStatus.DISABLED : connectionStatus;
        }

        @Override
        public void initialize() {
            initialized = true;
        }

        @Override
        public void shutdown() {
            shutdownCalled = true;
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
            taskEventCount++;
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
            pendingRequestCount++;
        }
    }

    private static final class ThrowingProvider extends FakeProvider {
        private final RuntimeException publishException;

        private ThrowingProvider(String providerId, EnumSet<RemoteCollabCapability> capabilities, RuntimeException publishException) {
            super(providerId, RemoteConnectionStatus.ERROR, capabilities);
            this.publishException = publishException;
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
            throw publishException;
        }
    }

    private static final class EnabledRemoteCollabSettingsService extends CodemossSettingsService {
        @Override
        public JsonObject getRemoteCollabConfig() {
            JsonObject config = new JsonObject();
            config.addProperty("enabled", true);
            return config;
        }
    }

    private static final class CompletingProvider extends FakeProvider {
        private RemotePendingRequest publishedPendingRequest;

        private CompletingProvider(String providerId, EnumSet<RemoteCollabCapability> capabilities) {
            super(providerId, RemoteConnectionStatus.CONNECTED, capabilities);
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
            super.publishPendingRequest(request);
            publishedPendingRequest = request;
        }

        private void completePendingRequest(JsonObject result) {
            if (publishedPendingRequest != null) {
                publishedPendingRequest.complete(result);
            }
        }
    }

    private static final class ConfiguredRemoteCollabSettingsService extends CodemossSettingsService {
        @Override
        public JsonObject getRemoteCollabConfig() {
            JsonObject telegram = new JsonObject();
            telegram.addProperty("enabled", true);
            telegram.addProperty("botToken", "");
            telegram.addProperty("botUsername", "");
            telegram.addProperty("chatId", "");
            telegram.addProperty("boundUserId", "");
            telegram.addProperty("boundUsername", "");
            telegram.addProperty("bindingToken", "");
            telegram.addProperty("pollingEnabled", true);
            telegram.addProperty("pollIntervalSeconds", 1);
            telegram.addProperty("singleActive", true);
            telegram.addProperty("connectionStatus", "disabled");
            telegram.addProperty("lastError", "");

            JsonObject gotify = new JsonObject();
            gotify.addProperty("enabled", true);
            gotify.addProperty("serverUrl", "https://gotify.example");
            gotify.addProperty("apiToken", "");
            gotify.addProperty("workspaceBaseUrl", "https://workspace.example");
            gotify.addProperty("resultPollIntervalSeconds", 3);
            gotify.addProperty("connectionStatus", "disabled");
            gotify.addProperty("lastError", "");

            JsonObject debug = new JsonObject();
            debug.addProperty("enabled", true);

            JsonObject providers = new JsonObject();
            providers.add("telegram", telegram);
            providers.add("gotify_web", gotify);

            JsonArray notifyProviderIds = new JsonArray();
            notifyProviderIds.add("telegram");
            notifyProviderIds.add("gotify_web");

            JsonObject config = new JsonObject();
            config.addProperty("enabled", true);
            config.add("debug", debug);
            config.addProperty("interactiveProviderId", "gotify_web");
            config.add("notifyProviderIds", notifyProviderIds);
            config.add("providers", providers);
            return config;
        }
    }

    private static final class FeishuConfiguredRemoteCollabSettingsService extends CodemossSettingsService {
        @Override
        public JsonObject getRemoteCollabConfig() {
            JsonObject telegram = new JsonObject();
            telegram.addProperty("enabled", true);

            JsonObject feishu = new JsonObject();
            feishu.addProperty("enabled", true);
            feishu.addProperty("appId", "cli_xxx");
            feishu.addProperty("appSecret", "secret_xxx");
            feishu.addProperty("eventMode", "long_poll");
            feishu.addProperty("connectionStatus", "disabled");
            feishu.addProperty("lastError", "");

            JsonObject debug = new JsonObject();
            debug.addProperty("enabled", true);

            JsonObject providers = new JsonObject();
            providers.add("telegram", telegram);
            providers.add("feishu", feishu);

            JsonArray notifyProviderIds = new JsonArray();
            notifyProviderIds.add("telegram");
            notifyProviderIds.add("feishu");

            JsonObject config = new JsonObject();
            config.addProperty("enabled", true);
            config.add("debug", debug);
            config.addProperty("interactiveProviderId", "feishu");
            config.add("notifyProviderIds", notifyProviderIds);
            config.add("providers", providers);
            return config;
        }
    }

    private static final class RoutedRemoteCollabSettingsService extends CodemossSettingsService {
        private final String interactiveProviderId;
        private final String[] notifyProviderIds;

        private RoutedRemoteCollabSettingsService(String interactiveProviderId, String[] notifyProviderIds) {
            this.interactiveProviderId = interactiveProviderId;
            this.notifyProviderIds = notifyProviderIds == null ? new String[0] : notifyProviderIds.clone();
        }

        @Override
        public JsonObject getRemoteCollabConfig() {
            JsonArray notifyProviders = new JsonArray();
            for (String providerId : notifyProviderIds) {
                notifyProviders.add(providerId);
            }
            JsonObject providers = new JsonObject();
            JsonObject telegram = new JsonObject();
            telegram.addProperty("enabled", true);
            providers.add("telegram", telegram);
            JsonObject gotify = new JsonObject();
            gotify.addProperty("enabled", true);
            providers.add("gotify_web", gotify);
            JsonObject config = new JsonObject();
            config.addProperty("enabled", true);
            config.addProperty("interactiveProviderId", interactiveProviderId);
            config.add("notifyProviderIds", notifyProviders);
            config.add("providers", providers);
            return config;
        }
    }

    private static JsonObject findProviderOption(JsonArray providerOptions, String providerId) {
        for (int i = 0; i < providerOptions.size(); i++) {
            JsonObject option = providerOptions.get(i).getAsJsonObject();
            if (providerId.equals(option.get("providerId").getAsString())) {
                return option;
            }
        }
        return null;
    }

    private static boolean containsCapability(JsonArray capabilities, String capability) {
        for (int i = 0; i < capabilities.size(); i++) {
            if (capability.equals(capabilities.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static final class FakeTelegramOperationsProvider extends FakeProvider implements RemoteTelegramOperationsProvider {
        private final JsonObject bindingResult = new JsonObject();
        private boolean bindingStarted;
        private String lastTestMessage;
        private boolean currentInstanceReceivingUpdates;

        private FakeTelegramOperationsProvider() {
            super(
                "telegram",
                RemoteConnectionStatus.CONNECTED,
                EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH, RemoteCollabCapability.PENDING_REQUEST_PUSH)
            );
            bindingResult.addProperty("bindingUrl", "https://telegram.test/bind");
        }

        @Override
        public JsonObject startBinding(CodemossSettingsService settingsService) {
            bindingStarted = true;
            return bindingResult;
        }

        @Override
        public void sendTestMessage(CodemossSettingsService settingsService, String message) {
            lastTestMessage = message;
        }

        @Override
        public boolean isCurrentInstanceReceivingUpdates() {
            return currentInstanceReceivingUpdates;
        }
    }
}
