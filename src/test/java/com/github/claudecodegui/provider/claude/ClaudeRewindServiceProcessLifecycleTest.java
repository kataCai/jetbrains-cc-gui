package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.ProcessManager;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * ClaudeRewindService 生命周期回归测试。
 * 目标是验证 rewindFiles 在失败或成功路径下都能把子进程纳入 ProcessManager，
 * 并在调用返回前完成注销和兜底终止，避免 rewind 子进程泄漏。
 */
public class ClaudeRewindServiceProcessLifecycleTest {

    /**
     * 用于记录 register/unregister 生命周期事件的测试型 ProcessManager。
     */
    private static class TrackingProcessManager extends ProcessManager {
        final AtomicInteger registerCalls = new AtomicInteger();
        final AtomicInteger unregisterCalls = new AtomicInteger();
        final AtomicReference<String> lastRegisteredChannelId = new AtomicReference<>();
        final AtomicReference<String> lastUnregisteredChannelId = new AtomicReference<>();
        final AtomicReference<Process> registeredProcess = new AtomicReference<>();
        final AtomicReference<Process> unregisteredProcess = new AtomicReference<>();

        /**
         * 记录最近一次注册调用。
         *
         * @param channelId 进程通道标识
         * @param process 子进程实例
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
         * 记录最近一次注销调用。
         *
         * @param channelId 进程通道标识
         * @param process 子进程实例
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
     * 获取当前 JVM 可执行路径，作为模拟 Node 可执行文件。
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
     * 初始化测试所需的 ProcessManager、临时目录与伪 NodeDetector。
     *
     * @throws Exception 当反射或临时目录创建失败时抛出
     */
    @Before
    public void setUp() throws Exception {
        processManager = new TrackingProcessManager();
        workDir = Files.createTempDirectory("claude-rewind-test").toFile();
        NodeDetector.resetInstance();
        node = NodeDetector.getInstance();
        Field cache = NodeDetector.class.getDeclaredField("cachedNodeExecutable");
        cache.setAccessible(true);
        cache.set(node, javaExecutable());
    }

    /**
     * 验证 rewindFiles 至少会完整执行一次 register/unregister，并在返回时清空活动进程。
     *
     * @throws Exception 当 rewind 异步调用失败时抛出
     */
    @Test
    public void rewindFiles_registersAndUnregistersChild() throws Exception {
        ClaudeRewindService service = new ClaudeRewindService(
                Logger.getInstance(ClaudeRewindServiceProcessLifecycleTest.class),
                new Gson(),
                node,
                () -> workDir,
                processManager,
                new EnvironmentConfigurator(),
                new ClaudeJsonOutputExtractor()
        );

        JsonObject response = service.rewindFiles(
                "test-session-id", "msg-id", workDir.getAbsolutePath()
        ).get(30, TimeUnit.SECONDS);
        assertNotNull(response);

        assertEquals(1, processManager.registerCalls.get());
        assertEquals(1, processManager.unregisterCalls.get());
        assertEquals(processManager.lastRegisteredChannelId.get(),
                processManager.lastUnregisteredChannelId.get());
        assertSame(processManager.registeredProcess.get(),
                processManager.unregisteredProcess.get());
        assertNotNull(processManager.lastRegisteredChannelId.get());
        assertTrue(processManager.lastRegisteredChannelId.get().startsWith("claude-rewind-"));
        assertEquals(0, processManager.getActiveProcessCount());
        assertFalse(processManager.registeredProcess.get().isAlive());
    }
}
