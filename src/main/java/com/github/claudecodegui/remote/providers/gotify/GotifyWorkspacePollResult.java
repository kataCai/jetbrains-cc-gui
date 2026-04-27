package com.github.claudecodegui.remote.providers.gotify;

/**
 * Gotify/Web 后台结果轮询返回的最小快照。
 * 插件当前只关心请求状态、动作数量以及最新动作，用于判断是否可以回写本地请求。
 */
public final class GotifyWorkspacePollResult {

    private final String requestId;
    private final String status;
    private final int actionCount;
    private final GotifyWorkspaceAction latestAction;

    public GotifyWorkspacePollResult(String requestId, String status, int actionCount, GotifyWorkspaceAction latestAction) {
        this.requestId = requestId == null ? "" : requestId.trim();
        this.status = status == null ? "" : status.trim();
        this.actionCount = Math.max(0, actionCount);
        this.latestAction = latestAction;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getStatus() {
        return status;
    }

    public int getActionCount() {
        return actionCount;
    }

    public GotifyWorkspaceAction getLatestAction() {
        return latestAction;
    }
}
