package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.ProcessManager;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * PromptEnhancerProcessRunner 生命周期回归测试。
 * 这些测试覆盖 PromptEnhancer 短生命周期 Node 子进程的关键收口点：
 * 1. 正常退出时必须成对 register/unregister；
 * 2. 超时时必须被强制终止；
 * 3. 调用返回后不能残留活动进程。
 * 测试通过当前 JVM 启动内部辅助 main 类，避免依赖系统外部命令导致跨平台不稳定。
 */
public class PromptEnhancerProcessRunnerTest {

    /**
     * 记录 register/unregister 调用的测试型 ProcessManager。
     * 该类用于验证 channelId 前缀、进程对象一致性以及生命周期钩子是否成对触发。
     */
    private static class TrackingProcessManager extends ProcessManager {
        final AtomicReference<String> registeredChannelId = new AtomicReference<>();
        final AtomicReference<Process> registeredProcess = new AtomicReference<>();
        final AtomicReference<String> unregisteredChannelId = new AtomicReference<>();
        final AtomicReference<Process> unregisteredProcess = new AtomicReference<>();
        volatile int registerCalls = 0;
        volatile int unregisterCalls = 0;

        /**
         * 记录注册调用，并保留最近一次注册的 channelId 与进程实例。
         *
         * @param channelId 进程注册使用的通道标识
         * @param process 实际注册的子进程对象
         * @return 无返回值
         */
        @Override
        public void registerProcess(String channelId, Process process) {
            registerCalls++;
            registeredChannelId.set(channelId);
            registeredProcess.set(process);
            super.registerProcess(channelId, process);
        }

        /**
         * 记录注销调用，并保留最近一次注销的 channelId 与进程实例。
         *
         * @param channelId 进程注销使用的通道标识
         * @param process 实际注销的子进程对象
         * @return 无返回值
         */
        @Override
        public void unregisterProcess(String channelId, Process process) {
            unregisterCalls++;
            unregisteredChannelId.set(channelId);
            unregisteredProcess.set(process);
            super.unregisterProcess(channelId, process);
        }
    }

    private TrackingProcessManager pm;

    /**
     * 为每个测试方法准备一份全新的 TrackingProcessManager，避免不同测试之间共享状态。
     *
     * @return 无返回值
     */
    @Before
    public void setUp() {
        pm = new TrackingProcessManager();
    }

    /**
     * 构造一个以当前 JVM 作为子进程入口的 ProcessBuilder。
     * 这样可以直接复用测试 classpath 中的辅助 main 类，保证不同平台下一致可用。
     *
     * @param mainClass 子进程需要执行的 main 类
     * @param args 传给 main 方法的参数列表
     * @return 指向辅助 Java 进程的 ProcessBuilder
     */
    private ProcessBuilder javaChild(Class<?> mainClass, String... args) {
        String javaBin = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + "java";
        String classpath = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(mainClass.getName());
        for (String arg : args) {
            cmd.add(arg);
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        return pb;
    }

    /**
     * 验证正常退出路径会完整执行 register/unregister，并且调用返回后不残留活动进程。
     *
     * @throws Exception 当测试内部辅助进程执行失败时抛出
     */
    @Test
    public void runWithProcessManager_normalExit_registersAndUnregisters() throws Exception {
        ProcessBuilder pb = javaChild(TestChildEcho.class, "hello", "world");
        List<String> lines = new ArrayList<>();

        int exit = PromptEnhancerProcessRunner.runWithProcessManager(
                pb, pm, "", 30, 5, lines::add);

        assertEquals("child should exit cleanly", 0, exit);
        assertTrue("should capture 'hello' line", lines.contains("hello"));
        assertTrue("should capture 'world' line", lines.contains("world"));
        assertEquals(1, pm.registerCalls);
        assertEquals(1, pm.unregisterCalls);
        assertEquals(pm.registeredChannelId.get(), pm.unregisteredChannelId.get());
        assertSame(pm.registeredProcess.get(), pm.unregisteredProcess.get());
        assertNotNull(pm.registeredChannelId.get());
        assertTrue(pm.registeredChannelId.get().startsWith("prompt-enhancer-"));
        assertFalse(pm.registeredProcess.get().isAlive());
        assertEquals(0, pm.getActiveProcessCount());
    }

    /**
     * 验证超时路径会强制杀掉子进程，并且同样完成 unregister 与活动进程清理。
     * 这个场景对应原始泄漏问题的核心风险点。
     *
     * @throws Exception 当测试过程中出现非预期异常时抛出
     */
    @Test
    public void runWithProcessManager_hungProcess_isForceKilledAfterTimeout() throws Exception {
        ProcessBuilder pb = javaChild(TestChildSleep.class, "60000");

        long start = System.currentTimeMillis();
        try {
            PromptEnhancerProcessRunner.runWithProcessManager(
                    pb, pm, "", 2, 1, line -> { });
            fail("Expected TimeoutException for hung child process");
        } catch (TimeoutException expected) {
            // 预期超时异常，用于确认超时终止逻辑生效。
        }
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(elapsed < 30_000);
        assertEquals(1, pm.registerCalls);
        assertEquals(1, pm.unregisterCalls);
        assertFalse(pm.registeredProcess.get().isAlive());
        assertEquals(0, pm.getActiveProcessCount());
    }

    /**
     * 测试辅助子进程：逐行输出传入参数后正常退出。
     * 用于验证 stdout 消费与正常退出清理。
     */
    public static class TestChildEcho {

        /**
         * 把所有参数逐行打印到标准输出。
         *
         * @param args 待输出的参数列表
         * @return 无返回值
         */
        public static void main(String[] args) {
            for (String a : args) {
                System.out.println(a);
            }
        }
    }

    /**
     * 测试辅助子进程：按指定毫秒数休眠。
     * 用于稳定复现超时强杀路径，而不依赖外部 sleep 命令。
     */
    public static class TestChildSleep {

        /**
         * 让当前进程休眠指定毫秒数。
         *
         * @param args 第一个参数为休眠毫秒数
         * @return 无返回值
         * @throws InterruptedException 当休眠过程中线程被中断时抛出
         */
        public static void main(String[] args) throws InterruptedException {
            long ms = Long.parseLong(args[0]);
            Thread.sleep(ms);
        }
    }
}
