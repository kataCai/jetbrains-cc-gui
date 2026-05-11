package com.github.claudecodegui.remote.feishu;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.Objects;

/**
 * 飞书绑定协议服务。
 * 当前统一使用一次性文本口令 `/cc-bind <token>` 完成实例绑定，先保证本地 IDE 与手机端账号能稳定关联。
 */
public class FeishuBindingService {

    private static final long TOKEN_TTL_MILLIS = 5 * 60 * 1000L;

    private final TokenGenerator tokenGenerator;
    private final TimeProvider timeProvider;

    public FeishuBindingService() {
        this(() -> "feishu-bind-" + System.currentTimeMillis(), System::currentTimeMillis);
    }

    FeishuBindingService(TokenGenerator tokenGenerator, TimeProvider timeProvider) {
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
        this.timeProvider = Objects.requireNonNull(timeProvider, "timeProvider");
    }

    public JsonObject startBinding(CodemossSettingsService settingsService) throws IOException {
        String bindingToken = normalize(tokenGenerator.nextToken());
        if (bindingToken.isEmpty()) {
            throw new IOException("Failed to generate Feishu binding token");
        }

        JsonObject config = settingsService.getRemoteCollabProviderConfig("feishu");
        long expiresAt = timeProvider.now() + TOKEN_TTL_MILLIS;

        // 进入绑定模式时显式清空最近错误，避免用户误把旧错误当成当前绑定失败原因。
        config.addProperty("bindingToken", bindingToken);
        config.addProperty("bindingTokenExpiresAt", expiresAt);
        config.addProperty("connectionStatus", "connecting");
        config.addProperty("lastError", "");
        settingsService.saveRemoteCollabProviderConfig("feishu", config);

        JsonObject result = new JsonObject();
        result.addProperty("bindingToken", bindingToken);
        result.addProperty("bindingCommand", "/cc-bind " + bindingToken);
        // 当前弹窗直接展示后端返回文案，这里改为中文以匹配中文环境下的绑定引导。
        result.addProperty("message", "请向飞书机器人发送绑定命令以完成绑定。");
        return result;
    }

    public BindingHandleResult handleBindingMessage(CodemossSettingsService settingsService, FeishuIncomingMessage message) throws IOException {
        String text = message == null ? "" : normalize(message.getText());
        if (!text.startsWith("/cc-bind ")) {
            return BindingHandleResult.ignored();
        }

        JsonObject config = settingsService.getRemoteCollabProviderConfig("feishu");
        String currentToken = normalize(readString(config, "bindingToken"));
        long expiresAt = readLong(config, "bindingTokenExpiresAt");
        String incomingToken = normalize(text.substring("/cc-bind ".length()));
        if (currentToken.isEmpty() || incomingToken.isEmpty() || !currentToken.equals(incomingToken)) {
            return BindingHandleResult.handled(true, false, "Binding token is invalid. Please restart binding in the IDE.");
        }
        if (expiresAt > 0L && expiresAt <= timeProvider.now()) {
            clearBindingToken(config);
            config.addProperty("connectionStatus", "error");
            config.addProperty("lastError", "Binding token expired");
            settingsService.saveRemoteCollabProviderConfig("feishu", config);
            return BindingHandleResult.handled(true, false, "Binding token has expired. Please restart binding in the IDE.");
        }

        String boundOpenId = normalize(readString(config, "boundOpenId"));
        String incomingOpenId = message == null ? "" : normalize(message.getOpenId());
        if (!boundOpenId.isEmpty() && !boundOpenId.equals(incomingOpenId)) {
            return BindingHandleResult.handled(true, false, "This IDE is already bound to another Feishu account.");
        }

        config.addProperty("boundOpenId", incomingOpenId);
        config.addProperty("boundChatId", message == null ? "" : normalize(message.getChatId()));
        clearBindingToken(config);
        config.addProperty("connectionStatus", "connected");
        config.addProperty("lastError", "");
        settingsService.saveRemoteCollabProviderConfig("feishu", config);
        return BindingHandleResult.handled(true, true, "Binding completed. This Feishu account is now linked to CC GUI.");
    }

    private void clearBindingToken(JsonObject config) {
        config.addProperty("bindingToken", "");
        config.addProperty("bindingTokenExpiresAt", 0L);
    }

    private String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return json.get(key).getAsString();
    }

    private long readLong(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return json.get(key).getAsLong();
        } catch (RuntimeException ignore) {
            return 0L;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    interface TokenGenerator {
        String nextToken();
    }

    interface TimeProvider {
        long now();
    }

    public static final class BindingHandleResult {
        private final boolean handled;
        private final boolean bound;
        private final String replyText;

        private BindingHandleResult(boolean handled, boolean bound, String replyText) {
            this.handled = handled;
            this.bound = bound;
            this.replyText = replyText == null ? "" : replyText.trim();
        }

        public static BindingHandleResult ignored() {
            return new BindingHandleResult(false, false, "");
        }

        public static BindingHandleResult handled(boolean handled, boolean bound, String replyText) {
            return new BindingHandleResult(handled, bound, replyText);
        }

        public boolean isHandled() {
            return handled;
        }

        public boolean isBound() {
            return bound;
        }

        public String getReplyText() {
            return replyText;
        }
    }
}
