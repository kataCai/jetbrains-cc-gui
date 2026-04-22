package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RemoteCollabSettingsHandlerBindingTest {

    private final Gson gson = new Gson();

    @Test
    public void shouldStartTelegramBindingAndPushUpdatedConfig() {
        StubSettingsService settingsService = new StubSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        RemoteCollabSettingsHandler handler = new RemoteCollabSettingsHandler(
            context,
            (service, request) -> {
                JsonObject telegram = service.getTelegramConfig();
                telegram.addProperty("bindingToken", "bind-token");
                telegram.addProperty("botUsername", "cc_gui_bot");
                service.saveTelegramConfig(telegram);

                JsonObject result = new JsonObject();
                result.addProperty("bindingUrl", "https://t.me/cc_gui_bot?start=bind-token");
                return result;
            },
            (service, request) -> {
            }
        );

        handler.handleStartTelegramBinding("{}");

        JsCall configCall = jsCallback.findCall("window.updateRemoteCollabConfig");
        assertNotNull(configCall);
        JsonObject configPayload = gson.fromJson(configCall.payload, JsonObject.class);
        assertEquals("bind-token", configPayload.getAsJsonObject("telegram").get("bindingToken").getAsString());

        JsCall successCall = jsCallback.findCall("window.showSuccess");
        assertNotNull(successCall);
        assertTrue(successCall.payload.contains("https://t.me/cc_gui_bot?start=bind-token"));
    }

    private static class StubSettingsService extends CodemossSettingsService {
        private JsonObject remoteCollab;

        private StubSettingsService() {
            JsonObject telegram = new JsonObject();
            telegram.addProperty("botToken", "bot-token");
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

            remoteCollab = new JsonObject();
            remoteCollab.addProperty("enabled", false);
            remoteCollab.add("telegram", telegram);
        }

        @Override
        public JsonObject getRemoteCollabConfig() {
            return remoteCollab.deepCopy();
        }

        @Override
        public JsonObject getTelegramConfig() {
            return remoteCollab.getAsJsonObject("telegram").deepCopy();
        }

        @Override
        public void saveTelegramConfig(JsonObject telegramConfig) {
            remoteCollab.add("telegram", telegramConfig.deepCopy());
        }
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
