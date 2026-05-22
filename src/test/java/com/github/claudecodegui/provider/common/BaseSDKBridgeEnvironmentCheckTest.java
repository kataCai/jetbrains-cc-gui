package com.github.claudecodegui.provider.common;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * BaseSDKBridge 环境检查测试。
 * 该测试覆盖“环境检查失败时必须返回真实失败原因”的行为，
 * 防止上层 UI 再次把 bridge 未就绪或入口脚本缺失误报成 Node.js 缺失。
 */
public class BaseSDKBridgeEnvironmentCheckTest {

    /**
     * 验证 bridge 目录尚未就绪时，环境检查会返回 BRIDGE_NOT_READY，
     * 而不是简单返回 false 并丢失真实失败类型。
     */
    @Test
    public void shouldReportBridgeNotReadyWhenSdkDirectoryIsMissing() {
        RecordingBridge bridge = new RecordingBridge("node", null);

        EnvironmentCheckResult result = bridge.checkEnvironmentDetails();

        assertFalse(result.isReady());
        assertEquals(EnvironmentCheckResult.FailureCode.BRIDGE_NOT_READY, result.getFailureCode());
        assertTrue(result.getDetailMessage().contains("Bridge directory not ready"));
    }

    /**
     * 验证 bridge 目录存在但入口脚本缺失时，环境检查会返回 CHANNEL_SCRIPT_MISSING。
     * 这样新建窗口的错误面板就可以直接提示资源不完整，而不是继续误导用户去修改 Node 路径。
     *
     * @throws IOException 创建临时目录失败时抛出异常
     */
    @Test
    public void shouldReportMissingChannelManagerWhenScriptDoesNotExist() throws IOException {
        Path tempDir = Files.createTempDirectory("bridge-env-check");
        try {
            RecordingBridge bridge = new RecordingBridge("node", tempDir.toFile());

            EnvironmentCheckResult result = bridge.checkEnvironmentDetails();

            assertFalse(result.isReady());
            assertEquals(EnvironmentCheckResult.FailureCode.CHANNEL_SCRIPT_MISSING, result.getFailureCode());
            assertTrue(result.getDetailMessage().contains("channel-manager.js"));
        } finally {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 清理临时目录。
     *
     * @param path 待清理目录
     * @throws IOException 删除失败时抛出异常
     */
    private void deleteDirectory(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /**
     * 用于测试的最小 bridge 实现。
     * 该测试替身强制固定 Node 路径和 bridge 目录，
     * 避免依赖真实本机环境，同时把注意力聚焦到环境检查分支本身。
     */
    private static final class RecordingBridge extends BaseSDKBridge {

        private final File sdkDir;

        private RecordingBridge(String nodePath, File sdkDir) {
            super(RecordingBridge.class);
            this.sdkDir = sdkDir;
            setNodeExecutable(nodePath);
            this.nodeDetector.verifyAndCacheNodePath(nodePath);
        }

        @Override
        protected String getProviderName() {
            return "test";
        }

        @Override
        protected void configureProviderEnv(Map<String, String> env, String stdinJson) {
        }

        @Override
        protected void processOutputLine(
                String line,
                MessageCallback callback,
                SDKResult result,
                StringBuilder assistantContent,
                AtomicBoolean hadSendError,
                AtomicReference<String> lastNodeError
        ) {
        }

        /**
         * 通过覆写目录解析逻辑，把测试定向到临时目录或空目录。
         *
         * @return 仅供当前测试使用的 bridge 目录解析器
         */
        @Override
        protected com.github.claudecodegui.bridge.BridgeDirectoryResolver getDirectoryResolver() {
            return new com.github.claudecodegui.bridge.BridgeDirectoryResolver() {
                @Override
                public File findSdkDir() {
                    return sdkDir;
                }
            };
        }
    }
}
