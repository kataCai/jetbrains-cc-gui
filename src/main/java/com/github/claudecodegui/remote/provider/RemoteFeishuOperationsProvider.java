package com.github.claudecodegui.remote.provider;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * Feishu 专属操作兼容接口。
 * 当前阶段先桥接绑定、连通性检查和测试消息，避免设置页动作继续写死在 handler 层。
 */
public interface RemoteFeishuOperationsProvider extends RemoteCollabProvider {

    /**
     * 启动 Feishu 绑定流程，并返回设置页需要展示的绑定结果。
     */
    JsonObject startBinding(CodemossSettingsService settingsService) throws IOException;

    /**
     * 执行 Feishu 基础连通性检查。
     */
    JsonObject healthCheck(CodemossSettingsService settingsService) throws IOException;

    /**
     * 发送 Feishu 测试消息，用于验证当前 provider 配置是否可用。
     */
    void sendTestMessage(CodemossSettingsService settingsService, String message) throws IOException;
}
