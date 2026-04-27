package com.github.claudecodegui.remote.provider;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * Telegram 专属操作兼容接口。
 * 当前阶段用于把绑定、测试消息等历史 Telegram 入口先桥接到 provider 抽象上，
 * 等 Telegram 正式迁移为 provider 后，RemoteCollabService 就不再需要直接依赖旧通道类型。
 */
public interface RemoteTelegramOperationsProvider extends RemoteCollabProvider {

    /**
     * 启动 Telegram 绑定流程，并返回设置页需要展示的绑定结果。
     */
    JsonObject startBinding(CodemossSettingsService settingsService) throws IOException;

    /**
     * 发送 Telegram 测试消息，用于验证当前 provider 配置是否可用。
     */
    void sendTestMessage(CodemossSettingsService settingsService, String message) throws IOException;

    /**
     * 返回当前 IDE 实例是否正在接收 Telegram 更新。
     * 用于设置页和状态提示展示单活 polling 的当前归属。
     */
    boolean isCurrentInstanceReceivingUpdates();
}
