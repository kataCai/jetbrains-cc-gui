package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
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
 * 验证 SoundSettingsHandler 在新旧桥接协议下的回调行为。
 * 重点确保：
 * 1. 新接口能返回完整 taskReminder 配置；
 * 2. 旧接口仍能得到兼容的 soundNotification 结构。
 */
public class SoundSettingsHandlerTaskReminderTest {

    private final Gson gson = new Gson();
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        // 测试期间会重定向 home 目录，结束后必须恢复，避免污染其他测试。
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldReturnFullTaskReminderPayloadForNewBridgeMessage() throws Exception {
        Path tempHome = Files.createTempDirectory("sound-handler-task-reminder-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SoundSettingsHandler handler = new SoundSettingsHandler(context);

        handler.handleGetTaskReminderConfig();
        flushEdt();

        // 新桥接必须直接回推完整 taskReminder 树，而不是只给 sound 子树。
        JsCall call = jsCallback.findCall("window.updateTaskReminderConfig");
        assertNotNull(call);

        JsonObject payload = gson.fromJson(call.payload, JsonObject.class);
        assertTrue(payload.has("popup"));
        assertTrue(payload.has("balloon"));
        assertTrue(payload.has("sound"));

        JsonObject sound = payload.getAsJsonObject("sound");
        assertEquals("default", sound.get("selectedSound").getAsString());
        assertTrue(sound.getAsJsonArray("states").size() > 0);
    }

    @Test
    public void shouldKeepLegacySoundBridgePayloadCompatible() throws Exception {
        Path tempHome = Files.createTempDirectory("sound-handler-legacy-bridge-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService settingsService = new CodemossSettingsService();
        settingsService.setSoundNotificationEnabled(true);
        settingsService.setSoundOnlyWhenUnfocused(true);
        settingsService.setSelectedSound("chime");
        settingsService.setCustomSoundPath("/tmp/chime.wav");

        CapturingJsCallback jsCallback = new CapturingJsCallback();
        HandlerContext context = new HandlerContext(null, null, null, settingsService, jsCallback);
        SoundSettingsHandler handler = new SoundSettingsHandler(context);

        handler.handleGetSoundNotificationConfig();
        flushEdt();

        // 旧回调只要求看到声音相关字段，但这些值应来自新的 canonical 配置源。
        JsCall call = jsCallback.findCall("window.updateSoundNotificationConfig");
        assertNotNull(call);

        JsonObject payload = gson.fromJson(call.payload, JsonObject.class);
        assertTrue(payload.get("enabled").getAsBoolean());
        assertTrue(payload.get("onlyWhenUnfocused").getAsBoolean());
        assertEquals("chime", payload.get("selectedSound").getAsString());
        assertEquals("/tmp/chime.wav", payload.get("customSoundPath").getAsString());
    }

    private void flushEdt() {
        // 某些回调通过 invokeLater 投递，测试中需要主动冲刷 EDT 才能拿到结果。
        if (ApplicationManager.getApplication() != null) {
            ApplicationManager.getApplication().invokeAndWait(() -> {
            });
        }
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        // 通过替换缓存 home 目录隔离配置文件读写，避免触碰真实用户环境。
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
            // 记录所有 JS 调用，供测试按函数名筛选断言。
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

    /**
     * 记录一次 JS 调用及其首个 payload。
     */
    private record JsCall(String functionName, String payload) {
    }
}
