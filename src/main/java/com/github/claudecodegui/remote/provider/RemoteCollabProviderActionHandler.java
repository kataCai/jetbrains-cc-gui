package com.github.claudecodegui.remote.provider;

import com.github.claudecodegui.remote.debug.RemoteCollabDebugActionDescriptor;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

/**
 * 远程协作 provider 可选动作桥接接口。
 * 用于承接设置页发起的 provider 级测试动作和特殊动作，避免 SettingsHandler 继续写死 Telegram 细节。
 */
public interface RemoteCollabProviderActionHandler extends RemoteCollabProvider {

    /**
     * 执行 provider 级动作。
     *
     * @param settingsService 配置服务，provider 可按需读取或回写自身配置
     * @param actionKey       动作标识，例如 start_binding、test_connection、send_test_message
     * @param request         动作请求载荷
     * @return 结构化动作结果，供后续设置页调试面板直接消费
     * @throws Exception 动作执行失败时抛出，由上层统一转成错误提示和调试记录
     */
    JsonObject executeAction(CodemossSettingsService settingsService, String actionKey, JsonObject request) throws Exception;

    /**
     * 返回当前 provider 暴露给调试页的动作声明。
     * 默认返回空列表，避免旧 provider 在迁移阶段被迫一次性补齐所有调试元数据。
     */
    default List<RemoteCollabDebugActionDescriptor> getDebugActions() {
        return Collections.emptyList();
    }
}
