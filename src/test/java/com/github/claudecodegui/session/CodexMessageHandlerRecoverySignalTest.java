package com.github.claudecodegui.session;

import com.github.claudecodegui.permission.PermissionRequest;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * 验证 CodexMessageHandler 对恢复链路信号的透传行为。
 * 重点确保 Node 侧发出的 retrying/recovery 等中间态不会在 Java 会话层被吞掉，
 * 便于后续任务状态服务据此写回 RETRYING / RECOVERED。
 */
public class CodexMessageHandlerRecoverySignalTest {

    @Test
    public void shouldForwardRetryingSignalToSessionCallback() {
        SessionState state = new SessionState();
        CallbackHandler callbackHandler = new CallbackHandler();
        RecordingSessionCallback callback = new RecordingSessionCallback();
        callbackHandler.setCallback(callback);
        CodexMessageHandler handler = new CodexMessageHandler(state, callbackHandler);

        handler.onMessage("retrying", "transient_retryable | attempt=1 | delayMs=1200");

        assertEquals(1, callback.retryingReasons.size());
        assertEquals("transient_retryable | attempt=1 | delayMs=1200", callback.retryingReasons.get(0));
    }

    /**
     * 仅保留本测试关心的最小会话回调实现。
     * retrying 信号当前通过扩展的 SessionCallback 默认方法透传，其余回调维持空实现即可。
     */
    private static final class RecordingSessionCallback implements ClaudeSession.SessionCallback {
        private final List<String> retryingReasons = new ArrayList<>();

        @Override
        public void onMessageUpdate(List<ClaudeSession.Message> messages) {
        }

        @Override
        public void onStateChange(boolean busy, boolean loading, String error) {
        }

        @Override
        public void onSessionIdReceived(String sessionId) {
        }

        @Override
        public void onPermissionRequested(PermissionRequest request) {
        }

        @Override
        public void onThinkingStatusChanged(boolean isThinking) {
        }

        @Override
        public void onSlashCommandsReceived(List<String> slashCommands) {
        }

        @Override
        public void onNodeLog(String log) {
        }

        @Override
        public void onSummaryReceived(String summary) {
        }

        @Override
        public void onRetrying(String reason) {
            retryingReasons.add(reason);
        }
    }
}
