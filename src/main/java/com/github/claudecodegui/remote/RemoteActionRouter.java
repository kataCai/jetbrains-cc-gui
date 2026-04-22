package com.github.claudecodegui.remote;

import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 将远程动作路由回本地待处理请求。
 * 当前仍然只做一层薄封装，目的是把“远程通道收到动作”和“本地 future 完成”隔离开，
 * 后续如果要接入更多平台或增加审计日志，只需要在这一层扩展。
 */
public class RemoteActionRouter {

    private final RemoteRequestRegistry requestRegistry;

    public RemoteActionRouter(RemoteRequestRegistry requestRegistry) {
        this.requestRegistry = Objects.requireNonNull(requestRegistry, "requestRegistry");
    }

    /**
     * 尝试完成指定 requestId 对应的本地待处理请求。
     * 返回 false 说明该请求已经被本地消费、超时清理，或 requestId 本身无效。
     */
    public boolean completeRequest(String requestId, JsonObject response) {
        return requestRegistry.complete(requestId, response);
    }
}
