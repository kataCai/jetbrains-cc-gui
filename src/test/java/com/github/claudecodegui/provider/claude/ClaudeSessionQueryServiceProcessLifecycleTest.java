package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.ProcessManager;
import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * ClaudeSessionQueryService 生命周期回归测试。
 * 该测试验证会话历史查询路径已经接入 ProcessManager，
 * 即使最终因为没有真实 Node 输出而报错，也必须在异常路径上完成注销和进程清理。
 */
public class ClaudeSessionQueryServiceProcessLifecycleTest {

    /**
     * 记录进程注册与注销行为的测试型 ProcessManager。
     */
    private static class TrackingProcessManager extends ProcessManager {
        final AtomicInteger registerCalls = new AtomicInteger();
        final AtomicInteger unregisterCalls = new AtomicInteger();
        final AtomicReference<String> lastRegisteredChannelId = new AtomicReference<>();
        final AtomicReference<String> lastUnregisteredChannelId = new AtomicReference<>();
        final AtomicReference<Process> registeredProcess = new AtomicReference<>();
        final AtomicReference<Process> unregisteredProcess = new AtomicReference<>();

        /**
         * 记录注册调用信息。
         *
         * @param channelId 通道标识
         * @param process 注册的子进程
         * @return 无返回值
         */
        @Override
        public void registerProcess(String channelId, Process process) {
            registerCalls.incrementAndGet();
            lastRegisteredChannelId.set(channelId);
            registeredProcess.set(process);
            super.registerProcess(channelId, process);
        }

        /**
         * 记录注销调用信息。
         *
         * @param channelId 通道标识
         * @param process 注销的子进程
         * @return 无返回值
         */
        @Override
        public void unregisterProcess(String channelId, Process process) {
            unregisterCalls.incrementAndGet();
            lastUnregisteredChannelId.set(channelId);
            unregisteredProcess.set(process);
            super.unregisterProcess(channelId, process);
        }
    }

    /**
     * 获取当前 JVM 可执行路径，作为测试中的伪 Node 可执行文件。
     *
     * @return JVM 可执行路径
     */
    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
    }

    private TrackingProcessManager processManager;
    private File workDir;
    private NodeDetector node;

    /**
     * 准备独立的临时目录和伪 NodeDetector，避免依赖真实 Node 运行环境。
     *
     * @throws Exception 当初始化步骤失败时抛出
     */
    @Before
    public void setUp() throws Exception {
        processManager = new TrackingProcessManager();
        workDir = Files.createTempDirectory("claude-session-query-test").toFile();
        NodeDetector.resetInstance();
        node = NodeDetector.getInstance();
        Field cache = NodeDetector.class.getDeclaredField("cachedNodeExecutable");
        cache.setAccessible(true);
        cache.set(node, javaExecutable());
    }

    /**
     * 验证 runSessionQuery 对应的历史查询链路会在异常返回前完成 register/unregister，
     * 并确保 ProcessManager 活动进程集合为空。
     *
     * @throws IOException 当临时 IO 操作失败时抛出
     */
    @Test
    public void runSessionQuery_registersAndUnregistersChild() throws IOException {
        ClaudeSessionQueryService service = new ClaudeSessionQueryService(
                Logger.getInstance(ClaudeSessionQueryServiceProcessLifecycleTest.class),
                new Gson(),
                node,
                () -> workDir,
                processManager,
                new EnvironmentConfigurator(),
                new ClaudeJsonOutputExtractor()
        );

        try {
            service.getSessionMessages("test-session-id", workDir.getAbsolutePath());
        } catch (Throwable expected) {
            // 没有真实 Node 输出时这里会走异常路径，正好覆盖 finally 清理分支。
        }

        assertEquals(1, processManager.registerCalls.get());
        assertEquals(1, processManager.unregisterCalls.get());
        assertEquals(processManager.lastRegisteredChannelId.get(),
                processManager.lastUnregisteredChannelId.get());
        assertSame(processManager.registeredProcess.get(),
                processManager.unregisteredProcess.get());
        assertNotNull(processManager.lastRegisteredChannelId.get());
        assertTrue(processManager.lastRegisteredChannelId.get().startsWith("claude-session-query-"));
        assertEquals(0, processManager.getActiveProcessCount());
        assertFalse(processManager.registeredProcess.get().isAlive());
    }
}
