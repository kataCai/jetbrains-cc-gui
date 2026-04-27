package com.github.claudecodegui.remote.providers.gotify;

/**
 * Gotify/Web 后台创建请求后的最小返回结果。
 * 当前插件只依赖后台 requestId 和工作台链接，后续如果后台补更多元数据，再按需扩展该模型。
 */
public final class GotifyWorkspaceCreateResult {

    private final String requestId;
    private final String workspaceLink;

    public GotifyWorkspaceCreateResult(String requestId, String workspaceLink) {
        this.requestId = requestId == null ? "" : requestId.trim();
        this.workspaceLink = workspaceLink == null ? "" : workspaceLink.trim();
    }

    public String getRequestId() {
        return requestId;
    }

    public String getWorkspaceLink() {
        return workspaceLink;
    }
}
