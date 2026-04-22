package com.github.claudecodegui.remote;

import com.github.claudecodegui.remote.telegram.TelegramChannelAdapter;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;

/**
 * 远程协作主服务。
 * 统一管理请求注册、动作回传、通道初始化与当前连接状态查询。
 * 这里刻意只暴露“统一入口”，避免 PermissionHandler、SessionHandler、设置页直接依赖 Telegram 实现，
 * 这样后续替换为飞书、Telegram 多通道并行时，主链路仍然只需要面向 RemoteTaskChannel。
 */
@Service(Service.Level.APP)
public final class RemoteCollabService {

    private static final Logger LOG = Logger.getInstance(RemoteCollabService.class);
    private static final RemoteCollabService FALLBACK_INSTANCE =
        new RemoteCollabService(RemoteRequestRegistry.getGlobalInstance());

    private final RemoteRequestRegistry requestRegistry;
    private final RemoteActionRouter actionRouter;
    private volatile RemoteTaskChannel taskChannel;

    public RemoteCollabService() {
        this(RemoteRequestRegistry.getGlobalInstance());
    }

    RemoteCollabService(RemoteRequestRegistry requestRegistry) {
        this.requestRegistry = requestRegistry;
        this.actionRouter = new RemoteActionRouter(requestRegistry);
    }

    public static RemoteCollabService getInstance() {
        if (ApplicationManager.getApplication() == null) {
            return FALLBACK_INSTANCE;
        }
        return ApplicationManager.getApplication().getService(RemoteCollabService.class);
    }

    public RemoteRequestRegistry getRequestRegistry() {
        return requestRegistry;
    }

    public RemoteActionRouter getActionRouter() {
        return actionRouter;
    }

    public void setTaskChannel(RemoteTaskChannel taskChannel) {
        this.taskChannel = taskChannel;
    }

    /**
     * 仅在配置开启时初始化远程通道。
     * 如果用户关闭了远程协作，这里会主动执行 shutdown，确保旧 polling 资源被释放。
     */
    public synchronized void initializeIfEnabled(CodemossSettingsService settingsService) throws IOException {
        JsonObject remoteCollabConfig = settingsService.getRemoteCollabConfig();
        boolean enabled = remoteCollabConfig.has("enabled") && remoteCollabConfig.get("enabled").getAsBoolean();
        if (!enabled) {
            shutdown();
            return;
        }
        getOrCreateTelegramChannel(settingsService).initialize();
    }

    /**
     * 保存配置后重新拉起通道。
     * 先 shutdown 再重建，避免 botToken/chatId/polling 开关变化后沿用旧实例里的脏状态。
     */
    public synchronized void reinitializeIfEnabled(CodemossSettingsService settingsService) throws IOException {
        shutdown();
        taskChannel = null;
        initializeIfEnabled(settingsService);
    }

    /**
     * 关闭当前远程协作通道。
     * 这里只负责通道生命周期，不主动清空 request registry，避免误伤仍在本地等待的请求。
     */
    public synchronized void shutdown() {
        if (taskChannel != null) {
            taskChannel.shutdown();
            // 关闭后清空引用，避免设置页继续读取到已失效的旧状态。
            taskChannel = null;
        }
    }

    /**
     * 注册一个可被远程端完成的待处理请求。
     */
    public void registerPendingRequest(RemotePendingRequest request) {
        requestRegistry.register(request);
    }

    /**
     * 将待处理请求下发给当前远程通道。
     * 当通道未初始化或未启用时，返回 false，调用方仍然保留本地交互兜底。
     */
    public boolean publishPendingRequest(RemotePendingRequest request) {
        if (taskChannel == null || request == null) {
            return false;
        }
        try {
            taskChannel.publishPendingRequest(request);
            return true;
        } catch (Exception e) {
            LOG.warn("[RemoteCollabService] Failed to publish pending request: " + e.getMessage());
            return false;
        }
    }

    /**
     * 供远程端通过 requestId 回写本地结果。
     */
    public boolean completePendingRequest(String requestId, JsonObject response) {
        return actionRouter.completeRequest(requestId, response);
    }

    /**
     * 发布任务状态事件。
     * 这类事件主要用于移动端感知“当前 IDE 做到哪一步了”，不直接驱动本地逻辑。
     */
    public void publishTaskEvent(RemoteTaskEvent event) {
        if (taskChannel == null) {
            return;
        }
        try {
            taskChannel.publishTaskEvent(event);
        } catch (Exception e) {
            LOG.warn("[RemoteCollabService] Failed to publish task event: " + e.getMessage());
        }
    }

    public String getConnectionStatus() {
        if (taskChannel == null) {
            return RemoteConnectionStatus.DISABLED.getValue();
        }
        return taskChannel.getConnectionStatus().getValue();
    }

    /**
     * 查询当前 IDE 实例是否真正持有 Telegram polling 接收权。
     * 该状态会直接反映到设置页提示文案，帮助用户判断“为什么消息只发不收”。
     */
    public boolean isCurrentInstanceReceivingUpdates() {
        return taskChannel instanceof TelegramChannelAdapter adapter && adapter.isPollingActive();
    }

    /**
     * 组装设置页需要的远程协作视图模型。
     * 这里顺带补齐运行时状态，避免前端自己拼装“连接状态/当前实例是否接收更新”等派生字段。
     */
    public JsonObject buildRemoteCollabViewModel(CodemossSettingsService settingsService) throws IOException {
        JsonObject config = settingsService.getRemoteCollabConfig();
        JsonObject telegram = config.has("telegram") && config.get("telegram").isJsonObject()
            ? config.getAsJsonObject("telegram")
            : new JsonObject();
        telegram.addProperty("connectionStatus", getConnectionStatus());
        telegram.addProperty("currentInstanceReceivesUpdates", isCurrentInstanceReceivingUpdates());
        config.add("telegram", telegram);
        return config;
    }

    /**
     * 触发 Telegram 绑定流程，返回设置页需要展示的绑定链接等信息。
     */
    public JsonObject startTelegramBinding(CodemossSettingsService settingsService) throws IOException {
        return getOrCreateTelegramChannel(settingsService).startBinding();
    }

    /**
     * 发送测试消息，用于校验 botToken/chatId 是否已正确配置。
     */
    public void sendTelegramTestMessage(CodemossSettingsService settingsService, String message) throws IOException {
        String text = message == null || message.trim().isEmpty()
            ? "CC GUI Telegram 测试消息"
            : message.trim();
        getOrCreateTelegramChannel(settingsService).sendTestMessage(text);
    }

    /**
     * 当前阶段只有 Telegram 一个实现，因此这里按需懒创建并缓存。
     * 后续如果扩展到多平台，可以在这里升级为按 channelId 分发。
     */
    private TelegramChannelAdapter getOrCreateTelegramChannel(CodemossSettingsService settingsService) {
        if (taskChannel == null) {
            taskChannel = new TelegramChannelAdapter(settingsService);
        }
        if (taskChannel instanceof TelegramChannelAdapter adapter) {
            return adapter;
        }
        throw new IllegalStateException("Current remote task channel is not Telegram");
    }
}
