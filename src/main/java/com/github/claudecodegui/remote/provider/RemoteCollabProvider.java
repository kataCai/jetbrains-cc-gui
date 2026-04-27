package com.github.claudecodegui.remote.provider;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteTaskEvent;

/**
 * 远程协作方案统一接口。
 * 各种远程方案都通过该接口接入主链路，主流程只依赖 provider 抽象，不再直接依赖 Telegram 等具体实现。
 */
public interface RemoteCollabProvider {

    /**
     * 返回当前 provider 的静态描述信息。
     */
    RemoteCollabProviderDescriptor getDescriptor();

    default String getProviderId() {
        return getDescriptor().getProviderId();
    }

    default boolean supports(RemoteCollabCapability capability) {
        return getDescriptor().supports(capability);
    }

    RemoteConnectionStatus getConnectionStatus();

    void initialize();

    void shutdown();

    void publishTaskEvent(RemoteTaskEvent event);

    void publishPendingRequest(RemotePendingRequest request);
}
