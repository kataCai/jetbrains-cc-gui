package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteRequestRegistry;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugService;
import com.github.claudecodegui.remote.provider.RemoteCollabCapability;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderActionHandler;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderDescriptor;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderRegistry;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ???? provider ???????????????? provider?????????????
 */
public class RemoteCollabSettingsHandlerProviderActionTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldDispatchGenericProviderTestActionToRegisteredProvider() throws Exception {
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        StubSettingsService settingsService = new StubSettingsService();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        FakeActionProvider provider = new FakeActionProvider("gotify_web");
        RemoteCollabProviderRegistry registry = new RemoteCollabProviderRegistry();
        registry.register(provider);
        RemoteCollabSettingsHandler handler = new RemoteCollabSettingsHandler(
            context,
            newRemoteCollabService(registry),
            (service, request) -> new JsonObject(),
            (service, request) -> {
            }
        );

        handler.handleTestRemoteCollabProvider("{\"providerId\":\"gotify_web\",\"actionKey\":\"health_check\"}");

        assertEquals("health_check", provider.lastActionKey);
        JsCall resultCall = jsCallback.findCall("window.updateRemoteCollabProviderOperationResult");
        assertNotNull(resultCall);
        JsonObject payload = gson.fromJson(resultCall.payload, JsonObject.class);
        assertEquals("test", payload.get("operationType").getAsString());
        assertEquals("gotify_web", payload.get("providerId").getAsString());
        assertEquals("healthy", payload.getAsJsonObject("result").get("message").getAsString());
    }

    @Test
    public void shouldDispatchGenericProviderActionToRegisteredProvider() throws Exception {
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        StubSettingsService settingsService = new StubSettingsService();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        FakeActionProvider provider = new FakeActionProvider("gotify_web");
        RemoteCollabProviderRegistry registry = new RemoteCollabProviderRegistry();
        registry.register(provider);
        RemoteCollabSettingsHandler handler = new RemoteCollabSettingsHandler(
            context,
            newRemoteCollabService(registry),
            (service, request) -> new JsonObject(),
            (service, request) -> {
            }
        );

        handler.handleRunRemoteCollabProviderAction("{\"providerId\":\"gotify_web\",\"actionKey\":\"poll_results_once\"}");

        assertEquals("poll_results_once", provider.lastActionKey);
        JsCall resultCall = jsCallback.findCall("window.updateRemoteCollabProviderOperationResult");
        assertNotNull(resultCall);
        JsonObject payload = gson.fromJson(resultCall.payload, JsonObject.class);
        assertEquals("action", payload.get("operationType").getAsString());
        assertEquals("poll_results_once", payload.get("actionKey").getAsString());
        assertEquals("action:poll_results_once", payload.getAsJsonObject("result").get("message").getAsString());
        assertTrue(jsCallback.findCall("window.showSuccess").payload.contains("action:poll_results_once"));
    }

    @Test
    public void shouldRecordProviderActionIntoDebugSnapshot() throws Exception {
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        StubSettingsService settingsService = new StubSettingsService();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        FakeActionProvider provider = new FakeActionProvider("gotify_web");
        RemoteCollabProviderRegistry registry = new RemoteCollabProviderRegistry();
        registry.register(provider);
        RemoteCollabService remoteCollabService = newRemoteCollabService(registry);
        RemoteCollabSettingsHandler handler = new RemoteCollabSettingsHandler(
            context,
            remoteCollabService,
            (service, request) -> new JsonObject(),
            (service, request) -> {
            }
        );

        handler.handleRunRemoteCollabProviderAction("{\"providerId\":\"gotify_web\",\"actionKey\":\"poll_results_once\"}");

        assertEquals(1, remoteCollabService.getDebugService().getSnapshot().getRecentActions().size());
        JsonObject actionRecord = remoteCollabService.getDebugService().getSnapshot().getRecentActions().get(0);
        assertEquals("gotify_web", actionRecord.get("providerId").getAsString());
        assertEquals("poll_results_once", actionRecord.get("actionKey").getAsString());
    }

    private RemoteCollabService newRemoteCollabService(RemoteCollabProviderRegistry registry) throws Exception {
        Constructor<RemoteCollabService> constructor = RemoteCollabService.class.getDeclaredConstructor(
            RemoteRequestRegistry.class,
            RemoteCollabProviderRegistry.class,
            RemoteCollabDebugService.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(new RemoteRequestRegistry(), registry, new RemoteCollabDebugService());
    }

    private static final class FakeActionProvider implements RemoteCollabProviderActionHandler {
        private final RemoteCollabProviderDescriptor descriptor;
        private String lastActionKey;

        private FakeActionProvider(String providerId) {
            this.descriptor = new RemoteCollabProviderDescriptor(
                providerId,
                providerId,
                providerId + " provider",
                EnumSet.of(RemoteCollabCapability.HEALTH_CHECK)
            );
        }

        @Override
        public RemoteCollabProviderDescriptor getDescriptor() {
            return descriptor;
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

        @Override
        public JsonObject executeAction(CodemossSettingsService settingsService, String actionKey, JsonObject request) {
            this.lastActionKey = actionKey;
            JsonObject result = new JsonObject();
            if ("health_check".equals(actionKey)) {
                result.addProperty("message", "healthy");
            } else {
                result.addProperty("message", "action:" + actionKey);
            }
            return result;
        }
    }

    private static final class StubSettingsService extends CodemossSettingsService {
        private JsonObject remoteCollab = createRemoteCollab();

        @Override
        public JsonObject getRemoteCollabConfig() {
            return remoteCollab.deepCopy();
        }

        @Override
        public void setRemoteCollabDebugEnabled(boolean enabled) {
            remoteCollab.getAsJsonObject("debug").addProperty("enabled", enabled);
        }

        @Override
        public boolean isRemoteCollabEnabled() {
            return remoteCollab.get("enabled").getAsBoolean();
        }

        @Override
        public void saveRemoteCollabProviderConfig(String providerId, JsonObject providerConfig) {
            remoteCollab.getAsJsonObject("providers").add(providerId, providerConfig.deepCopy());
        }

        private static JsonObject createRemoteCollab() {
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
            telegram.addProperty("currentInstanceReceivesUpdates", false);

            JsonObject gotify = new JsonObject();
            gotify.addProperty("enabled", false);
            gotify.addProperty("serverUrl", "");
            gotify.addProperty("apiToken", "");
            gotify.addProperty("workspaceBaseUrl", "");
            gotify.addProperty("resultPollIntervalSeconds", 3);
            gotify.addProperty("connectionStatus", "disabled");
            gotify.addProperty("lastError", "");

            JsonObject providers = new JsonObject();
            providers.add("telegram", telegram);
            providers.add("gotify_web", gotify);

            JsonObject debug = new JsonObject();
            debug.addProperty("enabled", false);

            JsonObject remoteCollab = new JsonObject();
            remoteCollab.addProperty("enabled", false);
            remoteCollab.add("debug", debug);
            remoteCollab.addProperty("interactiveProviderId", "telegram");
            remoteCollab.add("notifyProviderIds", new com.google.gson.JsonArray());
            remoteCollab.getAsJsonArray("notifyProviderIds").add("telegram");
            remoteCollab.add("providers", providers);
            remoteCollab.add("telegram", telegram.deepCopy());
            return remoteCollab;
        }
    }

    private static final class CapturingJsCallback implements HandlerContext.JsCallback {
        private final List<JsCall> calls = new ArrayList<>();

        @Override
        public void callJavaScript(String functionName, String... args) {
            String payload = args != null && args.length > 0 ? args[0] : "";
            calls.add(new JsCall(functionName, payload));
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }

        private JsCall findCall(String functionName) {
            for (JsCall call : calls) {
                if (functionName.equals(call.functionName)) {
                    return call;
                }
            }
            return null;
        }
    }

    private record JsCall(String functionName, String payload) {
    }
}
