package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * ClaudeQueryExecutor 生命周期回归测试。
 * 覆盖流式查询和同步查询两条路径，重点验证：
 * 1. 子进程会先注册到 ProcessManager；
 * 2. finally 中会注销并清空活动进程；
 * 3. 调用返回后子进程已经死亡，不再泄漏。
 * 这里通过把 Node 可执行路径替换为当前 JVM，可在不依赖真实 Node 环境的前提下稳定触发快速退出路径。
 */
public class ClaudeQueryExecutorProcessLifecycleTest {

    /**
     * 记录 register/unregister 调用次数与参数的测试型 ProcessManager。
     * 用于验证不同查询路径是否正确接入统一进程管理。
     */
    private static class TrackingProcessManager extends ProcessManager {
        final AtomicInteger registerCalls = new AtomicInteger();
        final AtomicInteger unregisterCalls = new AtomicInteger();
        final AtomicReference<String> lastRegisteredChannelId = new AtomicReference<>();
        final AtomicReference<String> lastUnregisteredChannelId = new AtomicReference<>();
        final AtomicReference<Process> registeredProcess = new AtomicReference<>();
        final AtomicReference<Process> unregisteredProcess = new AtomicReference<>();

        /**
         * 记录最近一次 registerProcess 调用。
         *
         * @param channelId 注册使用的通道标识
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
         * 记录最近一次 unregisterProcess 调用。
         *
         * @param channelId 注销使用的通道标识
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
     * 获取当前测试 JVM 的可执行路径，作为模拟 Node 可执行文件使用。
     *
     * @return 当前 JVM 二进制路径
     */
    private static String javaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
    }

    private TrackingProcessManager processManager;
    private File workDir;

    /**
     * 为每个测试准备独立的临时目录与 TrackingProcessManager。
     *
     * @throws IOException 当临时目录创建失败时抛出
     */
    @Before
    public void setUp() throws IOException {
        processManager = new TrackingProcessManager();
        workDir = Files.createTempDirectory("claude-query-executor-test").toFile();
    }

    /**
     * 构造待测 ClaudeQueryExecutor，并通过反射把 NodeDetector 缓存路径指向当前 JVM。
     * 这样执行 `java simple-query.js` 会快速失败退出，但仍然覆盖真实的子进程生命周期。
     *
     * @return 配置好的 ClaudeQueryExecutor
     * @throws Exception 当反射写入缓存字段失败时抛出
     */
    private ClaudeQueryExecutor newExecutor() throws Exception {
        NodeDetector.resetInstance();
        NodeDetector node = NodeDetector.getInstance();
        Field cache = NodeDetector.class.getDeclaredField("cachedNodeExecutable");
        cache.setAccessible(true);
        cache.set(node, javaExecutable());

        return new ClaudeQueryExecutor(
                new Gson(),
                node,
                () -> workDir,
                processManager,
                new EnvironmentConfigurator(),
                new ClaudeJsonOutputExtractor()
        );
    }

    /**
     * 流式查询回调记录器。
     * 用于接收测试期间的回调事件，避免真实 UI 依赖。
     */
    private static class RecordingCallback implements MessageCallback {
        final List<String> messages = new ArrayList<>();
        SDKResult completedWith;
        String errorWith;

        /**
         * 记录每次 onMessage 的消息类型与内容。
         *
         * @param type 消息类型
         * @param content 消息内容
         * @return 无返回值
         */
        @Override
        public void onMessage(String type, String content) {
            messages.add(type + ":" + content);
        }

        /**
         * 记录完成回调。
         *
         * @param result 查询最终结果
         * @return 无返回值
         */
        @Override
        public void onComplete(SDKResult result) {
            completedWith = result;
        }

        /**
         * 记录错误回调。
         *
         * @param error 错误信息
         * @return 无返回值
         */
        @Override
        public void onError(String error) {
            errorWith = error;
        }
    }

    /**
     * 验证流式查询路径会完成 register/unregister 配对，并使用预期的 channelId 前缀。
     *
     * @throws Exception 当异步查询执行失败时抛出
     */
    @Test
    public void executeQueryStream_registersAndUnregistersChild() throws Exception {
        ClaudeQueryExecutor executor = newExecutor();
        RecordingCallback callback = new RecordingCallback();

        SDKResult result = executor.executeQueryStream("hello", callback)
                .get(30, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(1, processManager.registerCalls.get());
        assertEquals(1, processManager.unregisterCalls.get());
        assertEquals(processManager.lastRegisteredChannelId.get(),
                processManager.lastUnregisteredChannelId.get());
        assertSame(processManager.registeredProcess.get(),
                processManager.unregisteredProcess.get());
        assertNotNull(processManager.lastRegisteredChannelId.get());
        assertTrue(processManager.lastRegisteredChannelId.get().startsWith("claude-query-stream-"));
    }

    /**
     * 验证流式查询结束后没有残留活动进程，且注册过的子进程已经死亡。
     *
     * @throws Exception 当异步查询执行失败时抛出
     */
    @Test
    public void executeQueryStream_leavesNoActiveProcesses() throws Exception {
        ClaudeQueryExecutor executor = newExecutor();
        RecordingCallback callback = new RecordingCallback();

        executor.executeQueryStream("hello", callback).get(30, TimeUnit.SECONDS);

        assertEquals(0, processManager.getActiveProcessCount());
        assertFalse(processManager.registeredProcess.get().isAlive());
    }

    /**
     * 验证同步查询路径也会执行完整的 register/unregister 生命周期管理。
     *
     * @throws Exception 当同步查询执行失败时抛出
     */
    @Test
    public void executeQuerySync_registersAndUnregistersChild() throws Exception {
        ClaudeQueryExecutor executor = newExecutor();

        executor.executeQuerySync("hello", 30);

        assertEquals(1, processManager.registerCalls.get());
        assertEquals(1, processManager.unregisterCalls.get());
        assertEquals(processManager.lastRegisteredChannelId.get(),
                processManager.lastUnregisteredChannelId.get());
        assertNotNull(processManager.lastRegisteredChannelId.get());
        assertTrue(processManager.lastRegisteredChannelId.get().startsWith("claude-query-sync-"));
        assertEquals(0, processManager.getActiveProcessCount());
        assertFalse(processManager.registeredProcess.get().isAlive());
    }
}
