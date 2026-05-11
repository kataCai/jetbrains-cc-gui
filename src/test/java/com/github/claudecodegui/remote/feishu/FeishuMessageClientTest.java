package com.github.claudecodegui.remote.feishu;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 Feishu 最小客户端的关键请求格式，
 * 避免第一阶段联调时因为 tenant token 或消息发送载荷拼错导致误判平台问题。
 */
public class FeishuMessageClientTest {

    @Test
    public void shouldRequestTenantAccessTokenWithAppCredentials() throws Exception {
        CapturingTransport transport = new CapturingTransport("{\"code\":0,\"tenant_access_token\":\"t-123\"}");
        FeishuMessageClient client = new FeishuMessageClient("cli_test", "secret_test", transport);

        JsonObject response = client.getTenantAccessToken();

        assertEquals("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal", transport.lastUrl);
        assertEquals("", transport.lastBearerToken);
        assertEquals("cli_test", transport.lastBody.get("app_id").getAsString());
        assertEquals("secret_test", transport.lastBody.get("app_secret").getAsString());
        assertEquals("t-123", response.get("tenant_access_token").getAsString());
    }

    @Test
    public void shouldSendTextMessageToOpenId() throws Exception {
        CapturingTransport transport = new CapturingTransport("{\"code\":0,\"data\":{\"message_id\":\"om_xxx\"}}");
        FeishuMessageClient client = new FeishuMessageClient("cli_test", "secret_test", transport);

        JsonObject response = client.sendTextMessage("tenant-token", "ou_abc", "hello feishu");

        assertEquals("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id", transport.lastUrl);
        assertEquals("tenant-token", transport.lastBearerToken);
        assertEquals("ou_abc", transport.lastBody.get("receive_id").getAsString());
        assertEquals("text", transport.lastBody.get("msg_type").getAsString());
        assertTrue(transport.lastBody.get("content").getAsString().contains("hello feishu"));
        assertEquals("om_xxx", response.getAsJsonObject("data").get("message_id").getAsString());
    }

    private static final class CapturingTransport implements FeishuMessageClient.HttpTransport {
        private final String responseBody;
        private String lastUrl = "";
        private String lastBearerToken = "";
        private JsonObject lastBody = new JsonObject();

        private CapturingTransport(String responseBody) {
            this.responseBody = responseBody;
        }

        @Override
        public String postJson(String url, String bearerToken, JsonObject body) {
            lastUrl = url;
            lastBearerToken = bearerToken == null ? "" : bearerToken;
            lastBody = body == null ? new JsonObject() : body.deepCopy();
            return responseBody;
        }
    }
}
