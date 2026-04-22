package com.github.claudecodegui.remote;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一登记远程待处理请求。
 * 该 registry 是 Telegram/飞书等远程通道与本地 permission 闭环之间的共享桥梁。
 * 它只负责 requestId -> pending request 的生命周期，不承担超时策略和业务判断，避免职责膨胀。
 */
public class RemoteRequestRegistry {

    private static final RemoteRequestRegistry GLOBAL_INSTANCE = new RemoteRequestRegistry();

    private final Map<String, RemotePendingRequest> pendingRequests = new ConcurrentHashMap<>();

    public static RemoteRequestRegistry getGlobalInstance() {
        return GLOBAL_INSTANCE;
    }

    /**
     * 注册新的远程待处理请求。
     * 相同 requestId 会被覆盖，因此调用方需要确保 requestId 全局唯一。
     */
    public void register(RemotePendingRequest request) {
        pendingRequests.put(request.getRequestId(), request);
    }

    public RemotePendingRequest get(String requestId) {
        return pendingRequests.get(requestId);
    }

    public RemotePendingRequest remove(String requestId) {
        return pendingRequests.remove(requestId);
    }

    /**
     * 取出并完成待处理请求。
     * 这里先 remove 再 complete，避免重复点击导致同一个本地 future 被完成多次。
     */
    public boolean complete(String requestId, JsonObject result) {
        RemotePendingRequest request = pendingRequests.remove(requestId);
        if (request == null) {
            return false;
        }
        request.complete(result);
        return true;
    }

    public int size() {
        return pendingRequests.size();
    }

    public int size(RemoteRequestType requestType) {
        int count = 0;
        for (RemotePendingRequest request : pendingRequests.values()) {
            if (request.getRequestType() == requestType) {
                count++;
            }
        }
        return count;
    }

    public Collection<RemotePendingRequest> getAll() {
        return new ArrayList<>(pendingRequests.values());
    }

    /**
     * 仅用于测试或显式重置场景，正常业务路径尽量走单个 request 的清理。
     */
    public void clear() {
        pendingRequests.clear();
    }
}
