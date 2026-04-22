package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
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
 * 验证 SettingsHandler 对远程协作桥接消息的分发。
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
