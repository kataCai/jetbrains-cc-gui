package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 处理设置页中的远程协作相关桥接消息。
 * 负责配置读取、保存、绑定触发和测试消息下发。
 * 这里不直接持有 Telegram 细节，只负责把前端动作翻译为 RemoteCollabService 调用，
 * 这样设置页和具体通道实现可以分别演进。
 */
public class RemoteCollabSettingsHandler {

    private static final Logger LOG = Logger.getInstance(RemoteCollabSettingsHandler.class);

    private final HandlerContext context;
    private final BindingStarter bindingStarter;
    private final TestMessageSender testMessageSender;
    private final Gson gson = new Gson();

    public RemoteCollabSettingsHandler(HandlerContext context) {
        this(
            context,
            (settingsService, ignored) -> RemoteCollabService.getInstance().startTelegramBinding(settingsService),
            (settingsService, request) -> {
                String message = request != null && request.has("message") && !request.get("message").isJsonNull()
                    ? request.get("message").getAsString()
                    : null;
                RemoteCollabService.getInstance().sendTelegramTestMessage(settingsService, message);
            }
        );
    }

    RemoteCollabSettingsHandler(
        HandlerContext context,
        BindingStarter bindingStarter,
        TestMessageSender testMessageSender
    ) {
        this.context = context;
        this.bindingStarter = bindingStarter;
        this.testMessageSender = testMessageSender;
    }

    /**
     * 读取并推送远程协作配置到前端设置页。
     */
    public void handleGetRemoteCollabConfig() {
        try {
            JsonObject config = RemoteCollabService.getInstance().buildRemoteCollabViewModel(context.getSettingsService());
            pushRemoteCollabConfig(config);
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to get remote collab config: " + e.getMessage(), e);
            runOnUiThread(() ->
                context.callJavaScript("window.showError", context.escapeJs("获取远程协作配置失败: " + e.getMessage())));
        }
    }

    /**
     * 处理远程协作总开关变更。
     * 开启时尝试重建通道，关闭时立即 shutdown，避免设置已关闭但后台 receiver 仍在运行。
     */
    public void handleSetRemoteCollabEnabled(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = json != null
                && json.has("enabled")
                && !json.get("enabled").isJsonNull()
                && json.get("enabled").getAsBoolean();
            context.getSettingsService().setRemoteCollabEnabled(enabled);
            if (enabled) {
                RemoteCollabService.getInstance().reinitializeIfEnabled(context.getSettingsService());
            } else {
                RemoteCollabService.getInstance().shutdown();
            }
            pushRemoteCollabConfig(RemoteCollabService.getInstance().buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to set remote collab enabled: " + e.getMessage(), e);
            runOnUiThread(() ->
                context.callJavaScript("window.showError", context.escapeJs("更新远程协作开关失败: " + e.getMessage())));
        }
    }

    /**
     * 保存 Telegram 子配置，并在已启用远程协作时刷新运行中通道。
     */
    public void handleSaveTelegramConfig(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            JsonObject telegramConfig = json != null && json.has("telegram") && json.get("telegram").isJsonObject()
                ? json.getAsJsonObject("telegram")
                : json;
            context.getSettingsService().saveTelegramConfig(telegramConfig);
            if (context.getSettingsService().isRemoteCollabEnabled()) {
                RemoteCollabService.getInstance().reinitializeIfEnabled(context.getSettingsService());
            }
            pushRemoteCollabConfig(RemoteCollabService.getInstance().buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to save telegram config: " + e.getMessage(), e);
            runOnUiThread(() ->
                context.callJavaScript("window.showError", context.escapeJs("保存 Telegram 配置失败: " + e.getMessage())));
        }
    }

    /**
     * 启动 Telegram 绑定流程，并将生成的链接反馈给前端。
     */
    public void handleStartTelegramBinding(String content) {
        try {
            JsonObject request = parseRequest(content);
            JsonObject result = bindingStarter.start(context.getSettingsService(), request);
            pushRemoteCollabConfig(RemoteCollabService.getInstance().buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
            String bindingUrl = result != null && result.has("bindingUrl") && !result.get("bindingUrl").isJsonNull()
                ? result.get("bindingUrl").getAsString()
                : "";
            if (!bindingUrl.isEmpty()) {
                runOnUiThread(() ->
                    context.callJavaScript("window.showSuccess", context.escapeJs("Telegram 绑定链接已生成: " + bindingUrl)));
            }
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to start telegram binding: " + e.getMessage(), e);
            runOnUiThread(() ->
                context.callJavaScript("window.showError", context.escapeJs("启动 Telegram 绑定失败: " + e.getMessage())));
        }
    }

    /**
     * 发送 Telegram 测试消息，帮助用户验证当前配置是否可达。
     */
    public void handleSendRemoteTestMessage(String content) {
        try {
            JsonObject request = parseRequest(content);
            testMessageSender.send(context.getSettingsService(), request);
            pushRemoteCollabConfig(RemoteCollabService.getInstance().buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
            runOnUiThread(() ->
                context.callJavaScript("window.showSuccess", context.escapeJs("Telegram 测试消息已发送")));
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to send telegram test message: " + e.getMessage(), e);
            runOnUiThread(() ->
                context.callJavaScript("window.showError", context.escapeJs("发送 Telegram 测试消息失败: " + e.getMessage())));
        }
    }

    private void pushRemoteCollabConfig(JsonObject config) {
        String json = gson.toJson(config);
        runOnUiThread(() ->
            context.callJavaScript("window.updateRemoteCollabConfig", context.escapeJs(json)));
    }

    private JsonObject parseRequest(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new JsonObject();
        }
        JsonObject json = gson.fromJson(content, JsonObject.class);
        return json == null ? new JsonObject() : json;
    }

    private void runOnUiThread(Runnable action) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed() || application.isUnitTestMode()) {
            action.run();
            return;
        }
        application.invokeLater(action);
    }

    private void syncRemoteStatusHint() {
        if (context.getProject() == null) {
            return;
        }
        ClaudeNotifier.updateRemoteCollabStatus(
            context.getProject(),
            RemoteCollabService.getInstance().getConnectionStatus(),
            RemoteCollabService.getInstance().isCurrentInstanceReceivingUpdates()
        );
    }

    interface BindingStarter {
        JsonObject start(CodemossSettingsService settingsService, JsonObject request) throws Exception;
    }

    interface TestMessageSender {
        void send(CodemossSettingsService settingsService, JsonObject request) throws Exception;
    }
}
