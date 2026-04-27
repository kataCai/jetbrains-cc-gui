package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.remote.RemoteCollabService;
import com.github.claudecodegui.remote.debug.RemoteCollabDebugResult;
import com.github.claudecodegui.remote.provider.RemoteCollabProvider;
import com.github.claudecodegui.remote.provider.RemoteCollabProviderActionHandler;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * ????????????????
 * ??????????provider ????????????????????
 */
public class RemoteCollabSettingsHandler {

    private static final Logger LOG = Logger.getInstance(RemoteCollabSettingsHandler.class);

    private final HandlerContext context;
    private final RemoteCollabService remoteCollabService;
    private final BindingStarter bindingStarter;
    private final TestMessageSender testMessageSender;
    private final Gson gson = new Gson();

    public RemoteCollabSettingsHandler(HandlerContext context) {
        this(
            context,
            RemoteCollabService.getInstance(),
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
        RemoteCollabService remoteCollabService,
        BindingStarter bindingStarter,
        TestMessageSender testMessageSender
    ) {
        this.context = context;
        this.remoteCollabService = remoteCollabService;
        this.bindingStarter = bindingStarter;
        this.testMessageSender = testMessageSender;
    }

    /**
     * ??????????????????
     */
    public void handleGetRemoteCollabConfig() {
        try {
            JsonObject config = remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService());
            pushRemoteCollabConfig(config);
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to get remote collab config: " + e.getMessage(), e);
            showError("??????????: " + e.getMessage());
        }
    }

    /**
     * ????????????
     */
    public void handleSetRemoteCollabEnabled(String content) {
        try {
            JsonObject json = parseRequest(content);
            boolean enabled = json.has("enabled") && !json.get("enabled").isJsonNull() && json.get("enabled").getAsBoolean();
            context.getSettingsService().setRemoteCollabEnabled(enabled);
            if (enabled) {
                remoteCollabService.reinitializeIfEnabled(context.getSettingsService());
            } else {
                remoteCollabService.shutdown();
            }
            pushRemoteCollabConfig(remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to set remote collab enabled: " + e.getMessage(), e);
            showError("??????????: " + e.getMessage());
        }
    }

    /**
     * ??? Telegram ????????????? provider ???????
     */
    public void handleSaveTelegramConfig(String content) {
        handleSaveRemoteCollabProviderConfig(gson.toJson(wrapProviderConfigRequest("telegram", parseRequest(content))));
    }

    /**
     * ???? provider ???
     * ????????? settings ? providers ?????????????????
     */
    public void handleSaveRemoteCollabProviderConfig(String content) {
        try {
            JsonObject request = parseRequest(content);
            String providerId = readRequiredString(request, "providerId");
            JsonObject providerConfig = request.has("config") && request.get("config").isJsonObject()
                ? request.getAsJsonObject("config")
                : request;
            context.getSettingsService().saveRemoteCollabProviderConfig(providerId, providerConfig);
            if (context.getSettingsService().isRemoteCollabEnabled()) {
                remoteCollabService.reinitializeIfEnabled(context.getSettingsService());
            }
            pushRemoteCollabConfig(remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to save remote collab provider config: " + e.getMessage(), e);
            showError("????????????: " + e.getMessage());
        }
    }

    /**
     * ??? Telegram ??????????? provider ?????
     */
    public void handleStartTelegramBinding(String content) {
        handleRunRemoteCollabProviderAction(gson.toJson(wrapProviderActionRequest("telegram", "start_binding", parseRequest(content))));
    }

    /**
     * ?? provider ??????? Telegram ???Gotify/Web ??????
     */
    public void handleRunRemoteCollabProviderAction(String content) {
        String providerId = "";
        String actionKey = "";
        try {
            JsonObject request = parseRequest(content);
            providerId = readRequiredString(request, "providerId");
            actionKey = readRequiredString(request, "actionKey");
            JsonObject result = executeProviderAction(providerId, actionKey, request);
            pushRemoteCollabConfig(remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
            recordDebugAction(providerId, actionKey, result, null);
            pushProviderOperationResult("action", providerId, actionKey, result);
            showSuccess(resolveSuccessMessage(actionKey, result));
        } catch (Exception e) {
            recordDebugAction(providerId, actionKey, null, e);
            LOG.error("[RemoteCollabSettingsHandler] Failed to run remote collab provider action: " + e.getMessage(), e);
            showError("????????????: " + e.getMessage());
        }
    }

    /**
     * ??? Telegram ????????????? provider ?????
     */
    public void handleSendRemoteTestMessage(String content) {
        handleTestRemoteCollabProvider(gson.toJson(wrapProviderActionRequest("telegram", "send_test_message", parseRequest(content))));
    }

    /**
     * ?? provider ??????? Telegram ?????Gotify/Web ???????
     */
    public void handleTestRemoteCollabProvider(String content) {
        String providerId = "";
        String actionKey = "test_connection";
        try {
            JsonObject request = parseRequest(content);
            providerId = readRequiredString(request, "providerId");
            actionKey = request.has("actionKey") && !request.get("actionKey").isJsonNull()
                ? readRequiredString(request, "actionKey")
                : "test_connection";
            JsonObject result = executeProviderAction(providerId, actionKey, request);
            pushRemoteCollabConfig(remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
            recordDebugAction(providerId, actionKey, result, null);
            pushProviderOperationResult("test", providerId, actionKey, result);
            showSuccess(resolveSuccessMessage(actionKey, result));
        } catch (Exception e) {
            recordDebugAction(providerId, actionKey, null, e);
            LOG.error("[RemoteCollabSettingsHandler] Failed to test remote collab provider: " + e.getMessage(), e);
            showError("????????????: " + e.getMessage());
        }
    }

    /**
     * ???????????
     */
    public void handleGetRemoteCollabDebugSnapshot() {
        JsonObject snapshot = remoteCollabService.getDebugService().getSnapshot().toJson();
        runOnUiThread(() ->
            context.callJavaScript("window.updateRemoteCollabDebugSnapshot", context.escapeJs(gson.toJson(snapshot))));
    }

    /**
     * ????????????????????????????
     */
    public void handleSetRemoteCollabDebugEnabled(String content) {
        try {
            JsonObject json = parseRequest(content);
            boolean enabled = json.has("enabled") && !json.get("enabled").isJsonNull() && json.get("enabled").getAsBoolean();
            context.getSettingsService().setRemoteCollabDebugEnabled(enabled);
            pushRemoteCollabConfig(remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService()));
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to set remote collab debug enabled: " + e.getMessage(), e);
            showError("????????????: " + e.getMessage());
        }
    }

    /**
     * 保存公共路由策略。
     * 远程协作页面在这里单独持久化 interactive / notify 选择，避免覆盖 provider 明细配置。
     */
    public void handleSaveRemoteCollabRoutingPolicy(String content) {
        try {
            JsonObject request = parseRequest(content);
            String interactiveProviderId = readOptionalString(request, "interactiveProviderId", "telegram");
            JsonArray notifyProviderIds = request.has("notifyProviderIds") && request.get("notifyProviderIds").isJsonArray()
                ? request.getAsJsonArray("notifyProviderIds")
                : new JsonArray();
            context.getSettingsService().saveRemoteCollabRoutingPolicy(interactiveProviderId, notifyProviderIds);
            if (context.getSettingsService().isRemoteCollabEnabled()) {
                remoteCollabService.reinitializeIfEnabled(context.getSettingsService());
            }
            pushRemoteCollabConfig(remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService()));
            syncRemoteStatusHint();
        } catch (Exception e) {
            LOG.error("[RemoteCollabSettingsHandler] Failed to save remote collab routing policy: " + e.getMessage(), e);
            showError("????????????????: " + e.getMessage());
        }
    }

    private void pushRemoteCollabConfig(JsonObject config) {
        String json = gson.toJson(config);
        runOnUiThread(() -> context.callJavaScript("window.updateRemoteCollabConfig", context.escapeJs(json)));
    }

    private JsonObject executeProviderAction(String providerId, String actionKey, JsonObject request) throws Exception {
        // Telegram 仍保留 legacy/stub 入口，避免测试替身和兼容路径被 provider 自动注册提前截走。
        if ("telegram".equals(providerId)) {
            RemoteCollabProvider telegramProvider = remoteCollabService.getProviderRegistry().getProvider(providerId);
            if (telegramProvider instanceof RemoteCollabProviderActionHandler actionHandler) {
                return actionHandler.executeAction(context.getSettingsService(), actionKey, request);
            }
            return executeLegacyTelegramAction(actionKey, request);
        }
        // 其他 provider 动作可能早于设置页完整刷新触发，这里先确保配置驱动的 provider 已完成注册。
        remoteCollabService.buildRemoteCollabViewModel(context.getSettingsService());
        RemoteCollabProvider provider = remoteCollabService.getProviderRegistry().getProvider(providerId);
        if (provider instanceof RemoteCollabProviderActionHandler actionHandler) {
            return actionHandler.executeAction(context.getSettingsService(), actionKey, request);
        }
        throw new IllegalArgumentException("Unsupported remote collaboration provider: " + providerId);
    }

    /**
     * Telegram ?? provider ????? action ???????????????? legacy ?????
     */
    private JsonObject executeLegacyTelegramAction(String actionKey, JsonObject request) throws Exception {
        if ("start_binding".equals(actionKey)) {
            return bindingStarter.start(context.getSettingsService(), request);
        }
        if ("send_test_message".equals(actionKey) || "test_connection".equals(actionKey)) {
            testMessageSender.send(context.getSettingsService(), request);
            JsonObject result = new JsonObject();
            result.addProperty("message", "Telegram ???????");
            return result;
        }
        throw new IllegalArgumentException("Unsupported Telegram action: " + actionKey);
    }

    private void pushProviderOperationResult(String operationType, String providerId, String actionKey, JsonObject result) {
        JsonObject payload = new JsonObject();
        payload.addProperty("operationType", operationType);
        payload.addProperty("providerId", providerId);
        payload.addProperty("actionKey", actionKey);
        payload.add("result", result == null ? new JsonObject() : result.deepCopy());
        runOnUiThread(() ->
            context.callJavaScript("window.updateRemoteCollabProviderOperationResult", context.escapeJs(gson.toJson(payload))));
    }

    private String resolveSuccessMessage(String actionKey, JsonObject result) {
        if (result != null && result.has("message") && !result.get("message").isJsonNull()) {
            return result.get("message").getAsString();
        }
        if (result != null && result.has("bindingUrl") && !result.get("bindingUrl").isJsonNull()) {
            return "Telegram ???????: " + result.get("bindingUrl").getAsString();
        }
        return switch (actionKey) {
            case "start_binding" -> "?????????????";
            case "send_test_message", "test_connection" -> "?????????????";
            default -> "???????????";
        };
    }

    private void recordDebugAction(String providerId, String actionKey, JsonObject result, Exception error) {
        if ((providerId == null || providerId.trim().isEmpty()) && (actionKey == null || actionKey.trim().isEmpty())) {
            return;
        }
        String message;
        if (error != null) {
            message = error.getMessage();
            remoteCollabService.getDebugService().recordDebugAction(RemoteCollabDebugResult.failure(providerId, actionKey, message));
            return;
        }
        message = result != null && result.has("message") && !result.get("message").isJsonNull()
            ? result.get("message").getAsString()
            : resolveSuccessMessage(actionKey, result);
        remoteCollabService.getDebugService().recordDebugAction(RemoteCollabDebugResult.success(providerId, actionKey, message));
    }

    private JsonObject parseRequest(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new JsonObject();
        }
        JsonObject json = gson.fromJson(content, JsonObject.class);
        return json == null ? new JsonObject() : json;
    }

    private String readRequiredString(JsonObject request, String key) {
        if (!request.has(key) || request.get(key).isJsonNull()) {
            throw new IllegalArgumentException(key + " is required");
        }
        String value = request.get(key).getAsString();
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value.trim();
    }

    private String readOptionalString(JsonObject request, String key, String defaultValue) {
        if (!request.has(key) || request.get(key).isJsonNull()) {
            return defaultValue;
        }
        String value = request.get(key).getAsString();
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    private JsonObject wrapProviderConfigRequest(String providerId, JsonObject request) {
        JsonObject payload = new JsonObject();
        payload.addProperty("providerId", providerId);
        JsonObject config = request != null && request.has("telegram") && request.get("telegram").isJsonObject()
            ? request.getAsJsonObject("telegram")
            : request;
        payload.add("config", config == null ? new JsonObject() : config.deepCopy());
        return payload;
    }

    private JsonObject wrapProviderActionRequest(String providerId, String actionKey, JsonObject request) {
        JsonObject payload = request == null ? new JsonObject() : request.deepCopy();
        payload.addProperty("providerId", providerId);
        payload.addProperty("actionKey", actionKey);
        return payload;
    }

    private void showSuccess(String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        runOnUiThread(() -> context.callJavaScript("window.showSuccess", context.escapeJs(message)));
    }

    private void showError(String message) {
        runOnUiThread(() -> context.callJavaScript("window.showError", context.escapeJs(message)));
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
            remoteCollabService.getConnectionStatus(),
            remoteCollabService.isCurrentInstanceReceivingUpdates()
        );
    }

    interface BindingStarter {
        JsonObject start(CodemossSettingsService settingsService, JsonObject request) throws Exception;
    }

    interface TestMessageSender {
        void send(CodemossSettingsService settingsService, JsonObject request) throws Exception;
    }
}
