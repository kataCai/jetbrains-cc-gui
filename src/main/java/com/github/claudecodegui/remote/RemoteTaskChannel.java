package com.github.claudecodegui.remote;

/**
 * 远程协作通道抽象。
 * Telegram/飞书等平台都应通过这一层接入，而不是直接依赖 permission 主链路。
 */
public interface RemoteTaskChannel {

    String getChannelId();

    RemoteConnectionStatus getConnectionStatus();

    void initialize();

    void shutdown();

    void publishTaskEvent(RemoteTaskEvent event);

    void publishPendingRequest(RemotePendingRequest request);
}
