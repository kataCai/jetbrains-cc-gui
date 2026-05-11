package com.github.claudecodegui.remote.feishu;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证飞书绑定协议的口令生成、过期保护和重复绑定保护。
 * 这里先把协议规则钉死，避免后续联调时不同入口各自解释 bindingToken。
 */
public class FeishuBindingServiceTest {

    @Test
    public void shouldStartBindingAndPersistExpiringToken() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        FeishuBindingService bindingService = new FeishuBindingService(() -> "bind-token-1", () -> 1_000L);

        JsonObject result = bindingService.startBinding(settingsService);

        JsonObject saved = settingsService.getRemoteCollabProviderConfig("feishu");
        assertEquals("bind-token-1", result.get("bindingToken").getAsString());
        assertEquals("/cc-bind bind-token-1", result.get("bindingCommand").getAsString());
        assertEquals("bind-token-1", saved.get("bindingToken").getAsString());
        assertEquals(301_000L, saved.get("bindingTokenExpiresAt").getAsLong());
        assertEquals("connecting", saved.get("connectionStatus").getAsString());
        assertEquals("", saved.get("lastError").getAsString());
    }

    @Test
    public void shouldBindOpenIdWhenInboundCommandMatchesActiveToken() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("bindingToken", "bind-token-2");
        settingsService.config.addProperty("bindingTokenExpiresAt", 500_000L);
        FeishuBindingService bindingService = new FeishuBindingService(() -> "unused", () -> 10_000L);

        FeishuBindingService.BindingHandleResult result = bindingService.handleBindingMessage(
            settingsService,
            new FeishuIncomingMessage("ou_123", "oc_456", "/cc-bind bind-token-2")
        );

        JsonObject saved = settingsService.getRemoteCollabProviderConfig("feishu");
        assertTrue(result.isHandled());
        assertTrue(result.isBound());
        assertEquals("Binding completed. This Feishu account is now linked to CC GUI.", result.getReplyText());
        assertEquals("ou_123", saved.get("boundOpenId").getAsString());
        assertEquals("oc_456", saved.get("boundChatId").getAsString());
        assertEquals("", saved.get("bindingToken").getAsString());
        assertEquals(0L, saved.get("bindingTokenExpiresAt").getAsLong());
        assertEquals("connected", saved.get("connectionStatus").getAsString());
    }

    @Test
    public void shouldRejectExpiredBindingToken() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("bindingToken", "bind-token-3");
        settingsService.config.addProperty("bindingTokenExpiresAt", 9_999L);
        FeishuBindingService bindingService = new FeishuBindingService(() -> "unused", () -> 10_000L);

        FeishuBindingService.BindingHandleResult result = bindingService.handleBindingMessage(
            settingsService,
            new FeishuIncomingMessage("ou_123", "oc_456", "/cc-bind bind-token-3")
        );

        JsonObject saved = settingsService.getRemoteCollabProviderConfig("feishu");
        assertTrue(result.isHandled());
        assertFalse(result.isBound());
        assertEquals("Binding token has expired. Please restart binding in the IDE.", result.getReplyText());
        assertEquals("error", saved.get("connectionStatus").getAsString());
        assertEquals("Binding token expired", saved.get("lastError").getAsString());
        assertEquals("", saved.get("bindingToken").getAsString());
    }

    @Test
    public void shouldRejectBindingFromDifferentUserWhenAlreadyBound() throws Exception {
        StubFeishuSettingsService settingsService = new StubFeishuSettingsService();
        settingsService.config.addProperty("bindingToken", "bind-token-4");
        settingsService.config.addProperty("bindingTokenExpiresAt", 999_999L);
        settingsService.config.addProperty("boundOpenId", "ou_owner");
        settingsService.config.addProperty("boundChatId", "oc_owner");
        FeishuBindingService bindingService = new FeishuBindingService(() -> "unused", () -> 10_000L);

        FeishuBindingService.BindingHandleResult result = bindingService.handleBindingMessage(
            settingsService,
            new FeishuIncomingMessage("ou_other", "oc_other", "/cc-bind bind-token-4")
        );

        JsonObject saved = settingsService.getRemoteCollabProviderConfig("feishu");
        assertTrue(result.isHandled());
        assertFalse(result.isBound());
        assertEquals("This IDE is already bound to another Feishu account.", result.getReplyText());
        assertEquals("ou_owner", saved.get("boundOpenId").getAsString());
        assertEquals("oc_owner", saved.get("boundChatId").getAsString());
    }

    private static final class StubFeishuSettingsService extends CodemossSettingsService {
        private final JsonObject config = new JsonObject();

        private StubFeishuSettingsService() {
            config.addProperty("enabled", true);
            config.addProperty("appId", "cli_test");
            config.addProperty("appSecret", "secret_test");
            config.addProperty("boundOpenId", "");
            config.addProperty("boundChatId", "");
            config.addProperty("bindingToken", "");
            config.addProperty("bindingTokenExpiresAt", 0L);
            config.addProperty("connectionStatus", "disabled");
            config.addProperty("lastError", "");
        }

        @Override
        public JsonObject getRemoteCollabProviderConfig(String providerId) {
            return config.deepCopy();
        }

        @Override
        public void saveRemoteCollabProviderConfig(String providerId, JsonObject providerConfig) {
            config.entrySet().clear();
            for (String key : providerConfig.keySet()) {
                config.add(key, providerConfig.get(key));
            }
        }
    }
}
