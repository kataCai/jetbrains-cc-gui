package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ProjectConfigHandlerPermissionTimeoutTest {

    @Test
    public void setPermissionDialogTimeoutResponseUsesEffectiveClampedValue() throws Exception {
        FakeSettingsService settingsService = new FakeSettingsService();
        ProjectConfigHandler handler = new ProjectConfigHandler(contextWith(settingsService));

        JsonObject lowResponse = handler.setPermissionDialogTimeoutAndCreateResponse(
                "{\"permissionDialogTimeoutSeconds\":1}"
        );
        assertEquals(1, settingsService.lastRequestedSeconds);
        assertEquals(30, lowResponse.get("permissionDialogTimeoutSeconds").getAsInt());

        JsonObject highResponse = handler.setPermissionDialogTimeoutAndCreateResponse(
                "{\"permissionDialogTimeoutSeconds\":99999}"
        );
        assertEquals(99999, settingsService.lastRequestedSeconds);
        assertEquals(3600, highResponse.get("permissionDialogTimeoutSeconds").getAsInt());
    }

    @Test
    public void setPermissionDialogTimeoutDefaultsMissingValue() throws Exception {
        FakeSettingsService settingsService = new FakeSettingsService();
        ProjectConfigHandler handler = new ProjectConfigHandler(contextWith(settingsService));

        JsonObject response = handler.setPermissionDialogTimeoutAndCreateResponse("{}");

        assertEquals(300, settingsService.lastRequestedSeconds);
        assertEquals(300, response.get("permissionDialogTimeoutSeconds").getAsInt());
    }

    @Test
    public void setRightClickOpenDevtoolsDefaultsMissingValueToFalse() throws Exception {
        FakeSettingsService settingsService = new FakeSettingsService();
        ProjectConfigHandler handler = new ProjectConfigHandler(contextWith(settingsService));

        JsonObject response = handler.setRightClickOpenDevToolsEnabledAndCreateResponse("{}");

        assertEquals(false, settingsService.lastRightClickOpenDevToolsEnabled);
        assertEquals(false, response.get("rightClickOpenDevToolsEnabled").getAsBoolean());
    }

    @Test
    public void setRightClickOpenDevtoolsRefreshesCurrentBrowserContextMenuImmediately() throws Exception {
        FakeSettingsService settingsService = new FakeSettingsService();
        HandlerContext context = contextWith(settingsService);
        RecordingBrowser browser = allocateRecordingBrowser();
        context.setBrowser(browser);
        ProjectConfigHandler handler = new ProjectConfigHandler(context);

        handler.setRightClickOpenDevToolsEnabledAndCreateResponse("{\"rightClickOpenDevToolsEnabled\":true}");

        assertTrue(
                "开启右键调试入口后，当前 Browser 应立即更新原生右键菜单开关",
                browser.booleanProperties.containsKey(JBCefBrowserBase.Properties.NO_CONTEXT_MENU)
        );
        assertEquals(false, browser.booleanProperties.get(JBCefBrowserBase.Properties.NO_CONTEXT_MENU));
    }

    /**
     * 验证前端调试配置的默认响应会明确标记两个开关“尚未显式配置”。
     * 这样当读取配置失败或配置节点缺失时，前端才能继续回退到构建期默认值，
     * 而不是把兜底值误判成“用户已经手动关闭了调试能力”。
     *
     * @throws Exception 反射调用默认响应构造方法失败时抛出
     */
    @Test
    public void defaultFrontendDebugConfigResponseMarksFlagsAsUnconfigured() throws Exception {
        ProjectConfigHandler handler = new ProjectConfigHandler(contextWith(new FakeSettingsService()));
        Method method = ProjectConfigHandler.class.getDeclaredMethod("buildDefaultFrontendDebugConfigResponse");
        method.setAccessible(true);

        JsonObject response = (JsonObject) method.invoke(handler);

        assertEquals(false, response.get("panelEnabled").getAsBoolean());
        assertEquals(false, response.get("archiveEnabled").getAsBoolean());
        assertEquals(false, response.get("panelConfigured").getAsBoolean());
        assertEquals(false, response.get("archiveConfigured").getAsBoolean());
    }

    private HandlerContext contextWith(CodemossSettingsService settingsService) {
        return new HandlerContext(
                null,
                null,
                null,
                settingsService,
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                }
        );
    }

    private static class FakeSettingsService extends CodemossSettingsService {
        private int effectiveSeconds = 300;
        private int lastRequestedSeconds = -1;
        private boolean lastRightClickOpenDevToolsEnabled = false;

        @Override
        public void setPermissionDialogTimeoutSeconds(int seconds) throws IOException {
            lastRequestedSeconds = seconds;
            effectiveSeconds = CodemossSettingsService.clampPermissionDialogTimeoutSeconds(seconds);
        }

        @Override
        public int getPermissionDialogTimeoutSeconds() throws IOException {
            return effectiveSeconds;
        }

        @Override
        public void setRightClickOpenDevToolsEnabled(boolean enabled) throws IOException {
            lastRightClickOpenDevToolsEnabled = enabled;
        }

        @Override
        public boolean getRightClickOpenDevToolsEnabled() throws IOException {
            return lastRightClickOpenDevToolsEnabled;
        }
    }

    /**
     * 通过 Unsafe 分配未执行 JCEF 构造流程的浏览器替身。
     * 这里仅需要观测 setProperty 调用，不能在单测里触发真实 JCEF 初始化，
     * 否则会把测试耦合到 IDE/JCEF 运行环境并产生无关失败。
     *
     * @return 可记录属性写入的浏览器替身
     * @throws Exception 反射获取 Unsafe 或分配实例时抛出
     */
    private static RecordingBrowser allocateRecordingBrowser() throws Exception {
        RecordingBrowser browser = (RecordingBrowser) getUnsafe().allocateInstance(RecordingBrowser.class);
        browser.booleanProperties = new HashMap<>();
        return browser;
    }

    /**
     * 获取 Unsafe 单例，用于跳过 JBCefBrowser 构造器。
     *
     * @return Unsafe 实例
     * @throws Exception 反射访问失败时抛出
     */
    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    /**
     * 记录 JCEF Browser 属性写入的测试替身。
     * 这里只关心 handler 是否在保存配置后立即刷新原生右键菜单开关，
     * 因此仅覆盖 setProperty(Boolean) 这一条观测路径。
     */
    private static class RecordingBrowser extends JBCefBrowser {
        private Map<String, Boolean> booleanProperties;

        @Override
        public void setProperty(String name, Object value) {
            if (value instanceof Boolean) {
                booleanProperties.put(name, (Boolean) value);
            }
        }
    }
}
