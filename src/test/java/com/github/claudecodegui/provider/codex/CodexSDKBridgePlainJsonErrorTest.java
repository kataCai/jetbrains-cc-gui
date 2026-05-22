package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * CodexSDKBridge 普通 JSON 失败透传测试。
 * 用于覆盖 channel-manager / Node 侧在更早阶段抛错时，
 * 只输出 `{"success":false,"error":"..."}` 而没有 `[SEND_ERROR]` 标签的场景。
 */
public class CodexSDKBridgePlainJsonErrorTest {

    /**
     * 验证普通 JSON 失败行会被桥接层识别成真实错误，
     * 而不是落到后续的 `process exited with code: 1` 泛化提示。
     */
    @Test
    public void shouldTreatPlainJsonFailureLineAsSendError() {
        ExposedCodexSDKBridge bridge = new ExposedCodexSDKBridge();
        SDKResult result = new SDKResult();
        StringBuilder assistantContent = new StringBuilder();
        AtomicBoolean hadSendError = new AtomicBoolean(false);
        AtomicReference<String> lastNodeError = new AtomicReference<>(null);
        RecordingCallback callback = new RecordingCallback();

        bridge.processOutputLine(
                "{\"success\":false,\"error\":\"Codex authentication error\"}",
                callback,
                result,
                assistantContent,
                hadSendError,
                lastNodeError
        );

        assertTrue(hadSendError.get());
        assertFalse(result.success);
        assertEquals("Codex authentication error", result.error);
        assertEquals("Codex authentication error", callback.lastError);
    }

    /**
     * 暴露 protected 处理方法，方便测试直接喂单行输出。
     */
    private static final class ExposedCodexSDKBridge extends CodexSDKBridge {
        @Override
        public void processOutputLine(
                String line,
                MessageCallback callback,
                SDKResult result,
                StringBuilder assistantContent,
                AtomicBoolean hadSendError,
                AtomicReference<String> lastNodeError
        ) {
            super.processOutputLine(line, callback, result, assistantContent, hadSendError, lastNodeError);
        }
    }

    /**
     * 用于记录错误回调的最小实现。
     */
    private static final class RecordingCallback implements MessageCallback {
        private final List<String> messages = new ArrayList<>();
        private String lastError;

        @Override
        public void onMessage(String type, String content) {
            messages.add(type + ":" + content);
        }

        @Override
        public void onComplete(SDKResult result) {
        }

        @Override
        public void onError(String error) {
            this.lastError = error;
        }
    }
}
