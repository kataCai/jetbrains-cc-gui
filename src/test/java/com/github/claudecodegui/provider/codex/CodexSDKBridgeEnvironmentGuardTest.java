package com.github.claudecodegui.provider.codex;

import com.github.claudecodegui.provider.common.EnvironmentCheckResult;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * CodexSDKBridge 发送前环境保护测试。
 * 该测试覆盖“bridge 资源未就绪时必须在 Java 侧直接失败并返回明确原因”的行为，
 * 防止发送链路继续拉起 Node 子进程，最终只退化成模糊的 exit code 1。
 */
public class CodexSDKBridgeEnvironmentGuardTest {

    /**
     * 验证环境预检失败时，发送流程会直接返回结构化错误，
     * 且错误文案能够明确指出 bridge 未准备完成，而不是泛化为退出码错误。
     *
     * @throws Exception 等待异步结果异常时抛出
     */
    @Test
    public void shouldFailEarlyWhenBridgeIsNotReady() throws Exception {
        GuardedCodexSDKBridge bridge = new GuardedCodexSDKBridge(
                EnvironmentCheckResult.failed(
                        EnvironmentCheckResult.FailureCode.BRIDGE_NOT_READY,
                        "Bridge directory not ready yet (extraction in progress)",
                        "node",
                        "v22.20.0",
                        null
                )
        );
        RecordingCallback callback = new RecordingCallback();

        SDKResult result = bridge.sendMessage(
                "test-channel",
                "hello",
                "",
                "",
                List.of(),
                "acceptEdits",
                "",
                "",
                "medium",
                callback
        ).get(10, TimeUnit.SECONDS);

        assertFalse(result.success);
        assertNotNull(result.error);
        assertTrue(result.error.contains("AI Bridge 尚未准备完成"));
        assertTrue(callback.awaitError());
        assertTrue(callback.lastError.contains("AI Bridge 尚未准备完成"));
    }

    /**
     * 用于注入固定环境检查结果的测试替身。
     * 这样可以稳定覆盖发送前预检分支，而不依赖真实 bridge 解压或本机环境。
     */
    private static final class GuardedCodexSDKBridge extends CodexSDKBridge {
        private final EnvironmentCheckResult environmentCheckResult;

        private GuardedCodexSDKBridge(EnvironmentCheckResult environmentCheckResult) {
            super();
            this.environmentCheckResult = environmentCheckResult;
        }

        /**
         * 覆盖环境检查结果，让测试精确命中目标分支。
         *
         * @return 预设的环境检查结果
         */
        @Override
        public EnvironmentCheckResult checkEnvironmentDetails() {
            return environmentCheckResult;
        }
    }

    /**
     * 用于记录异步错误回调的最小实现。
     * 该实现通过 CountDownLatch 确保测试可以稳定等待 onError 被触发。
     */
    private static final class RecordingCallback implements MessageCallback {
        private final CountDownLatch errorLatch = new CountDownLatch(1);
        private String lastError;

        @Override
        public void onMessage(String type, String content) {
        }

        @Override
        public void onError(String error) {
            this.lastError = error;
            this.errorLatch.countDown();
        }

        @Override
        public void onComplete(SDKResult result) {
        }

        /**
         * 等待错误回调触发。
         *
         * @return true 表示在超时时间内收到错误回调
         * @throws InterruptedException 当前线程被中断时抛出
         */
        private boolean awaitError() throws InterruptedException {
            return errorLatch.await(3, TimeUnit.SECONDS);
        }
    }
}
