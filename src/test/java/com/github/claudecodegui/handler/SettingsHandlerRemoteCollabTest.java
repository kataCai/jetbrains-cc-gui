package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * ?? SettingsHandler ???????????????? action ???
 */
public class SettingsHandlerRemoteCollabTest {

    private final Gson gson = new Gson();
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldReturnRemoteCollabConfigForNewBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("settings-handler-remote-collab-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SettingsHandler handler = new SettingsHandler(context, null);

        assertTrue(handler.handle("get_remote_collab_config", ""));

        JsCall call = jsCallback.findCall("window.updateRemoteCollabConfig");
        assertNotNull(call);
        JsonObject payload = gson.fromJson(call.payload, JsonObject.class);
        assertTrue(payload.has("telegram"));
        assertTrue(payload.has("feishu"));
        assertTrue(payload.has("providers"));
        assertTrue(payload.has("debug"));
        assertEquals(false, payload.get("enabled").getAsBoolean());
    }

    @Test
    public void shouldPersistRemoteCollabEnabledFlagFromBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("settings-handler-remote-collab-toggle-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SettingsHandler handler = new SettingsHandler(context, null);

        assertTrue(handler.handle("set_remote_collab_enabled", "{\"enabled\":true}"));

        JsonObject remoteCollab = settingsService.getRemoteCollabConfig();
        assertTrue(remoteCollab.get("enabled").getAsBoolean());
        assertNotNull(jsCallback.findCall("window.updateRemoteCollabConfig"));
    }

    @Test
    public void shouldPersistRemoteCollabDebugEnabledFlagFromBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("settings-handler-remote-debug-toggle-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SettingsHandler handler = new SettingsHandler(context, null);

        assertTrue(handler.handle("set_remote_collab_debug_enabled", "{\"enabled\":true}"));

        JsonObject remoteCollab = settingsService.getRemoteCollabConfig();
        assertTrue(remoteCollab.getAsJsonObject("debug").get("enabled").getAsBoolean());
        assertNotNull(jsCallback.findCall("window.updateRemoteCollabConfig"));
    }

    @Test
    public void shouldPersistProviderConfigFromGenericBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("settings-handler-provider-config-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SettingsHandler handler = new SettingsHandler(context, null);

        assertTrue(handler.handle(
            "save_remote_collab_provider_config",
            "{\"providerId\":\"gotify_web\",\"config\":{\"enabled\":true,\"serverUrl\":\"https://gotify.example\",\"resultPollIntervalSeconds\":0}}"
        ));

        JsonObject gotifyConfig = settingsService.getRemoteCollabProviderConfig("gotify_web");
        assertTrue(gotifyConfig.get("enabled").getAsBoolean());
        assertEquals("https://gotify.example", gotifyConfig.get("serverUrl").getAsString());
        assertEquals(1, gotifyConfig.get("resultPollIntervalSeconds").getAsInt());
        assertNotNull(jsCallback.findCall("window.updateRemoteCollabConfig"));
    }

    @Test
    public void shouldPersistFeishuProviderConfigFromGenericBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("settings-handler-feishu-config-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SettingsHandler handler = new SettingsHandler(context, null);

        assertTrue(handler.handle(
            "save_remote_collab_provider_config",
            "{\"providerId\":\"feishu\",\"config\":{\"enabled\":true,\"appId\":\"cli_test\",\"appSecret\":\"secret_test\",\"eventMode\":\"long_poll\"}}"
        ));

        JsonObject feishuConfig = settingsService.getRemoteCollabProviderConfig("feishu");
        assertTrue(feishuConfig.get("enabled").getAsBoolean());
        assertEquals("cli_test", feishuConfig.get("appId").getAsString());
        assertEquals("secret_test", feishuConfig.get("appSecret").getAsString());
        assertEquals("long_poll", feishuConfig.get("eventMode").getAsString());
        assertNotNull(jsCallback.findCall("window.updateRemoteCollabConfig"));
    }

    @Test
    public void shouldPersistRoutingPolicyFromBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("settings-handler-routing-policy-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SettingsHandler handler = new SettingsHandler(context, null);

        assertTrue(handler.handle(
            "save_remote_collab_routing_policy",
            "{\"interactiveProviderId\":\"gotify_web\",\"notifyProviderIds\":[\"telegram\",\"gotify_web\",\"telegram\",\"\"]}"
        ));

        JsonObject remoteCollab = settingsService.getRemoteCollabConfig();
        assertEquals("gotify_web", remoteCollab.get("interactiveProviderId").getAsString());
        assertEquals(2, remoteCollab.getAsJsonArray("notifyProviderIds").size());
        assertEquals("telegram", remoteCollab.getAsJsonArray("notifyProviderIds").get(0).getAsString());
        assertEquals("gotify_web", remoteCollab.getAsJsonArray("notifyProviderIds").get(1).getAsString());
        assertNotNull(jsCallback.findCall("window.updateRemoteCollabConfig"));
    }

    @Test
    public void shouldReturnRemoteCollabDebugSnapshotForNewBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("settings-handler-remote-debug-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SettingsHandler handler = new SettingsHandler(context, null);
        String providerId = "debug-test-provider-" + System.nanoTime();
        RemoteCollabService.getInstance().getDebugService().recordError(providerId, "health_check", "boom");

        assertTrue(handler.handle("get_remote_collab_debug_snapshot", ""));

        JsCall call = jsCallback.findCall("window.updateRemoteCollabDebugSnapshot");
        assertNotNull(call);
        JsonObject payload = gson.fromJson(call.payload, JsonObject.class);
        JsonArray recentErrors = payload.getAsJsonArray("recentErrors");
        assertTrue(containsProviderId(recentErrors, providerId));
    }

    private boolean containsProviderId(JsonArray errors, String providerId) {
        for (int i = 0; i < errors.size(); i++) {
            JsonObject item = errors.get(i).getAsJsonObject();
            if (providerId.equals(item.get("providerId").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }

    private static class CapturingJsCallback implements HandlerContext.JsCallback {
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
