package com.github.claudecodegui.util;

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
 * ManagedProcessRunner 生命周期与超时行为回归测试。
 * 该测试类专门覆盖“stdout 保持打开时主线程是否仍能走到超时分支”这一根因场景，
 * 防止调用方继续采用“先阻塞读完输出，再 waitFor 超时”的旧模式。
 *
 * 适用范围：
 * 1. 通用 Node/外部进程执行辅助类；
 * 2. 需要统一接入 ProcessManager 的短生命周期子进程；
 * 3. 需要同时验证 register/unregister 对称性与超时强杀行为的入口。
 */
public class ManagedProcessRunnerTest {

    /**
     * 记录 register/unregister 调用细节的测试型 ProcessManager。
     * 用于验证通用执行器是否正确接入共享进程管理器，并在异常/超时路径上完成对称清理。
     */
    private static class TrackingProcessManager extends ProcessManager {
        final AtomicReference<String> registeredChannelId = new AtomicReference<>();
        final AtomicReference<Process> registeredProcess = new AtomicReference<>();
        final AtomicReference<String> unregisteredChannelId = new AtomicReference<>();
        final AtomicReference<Process> unregisteredProcess = new AtomicReference<>();
        volatile int registerCalls = 0;
        volatile int unregisterCalls = 0;

        /**
         * 记录最近一次 registerProcess 调用。
         *
         * @param channelId 调用方分配的进程通道标识
         * @param process 已启动并待管理的子进程
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
         * 记录最近一次 unregisterProcess 调用。
         *
         * @param channelId 调用方分配的进程通道标识
         * @param process 即将移出管理器的子进程
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

    private TrackingProcessManager processManager;

    /**
     * 为每个测试准备全新的 ProcessManager 实例，
     * 避免跨用例复用状态导致的活跃进程计数或最后一次调用记录串扰。
     *
     * @return 无返回值
     */
    @Before
    public void setUp() {
        processManager = new TrackingProcessManager();
    }

    /**
     * 构造以当前 JVM 为入口的子进程。
     * 这样可以在不依赖外部 Node、sleep 等平台命令的前提下，
     * 稳定复现“正常退出”和“stdout 打开但不退出”的两类行为。
     *
     * @param mainClass 目标 main 类
     * @param args 透传给 main 方法的参数
     * @return 指向测试子进程的 ProcessBuilder
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
     * 验证正常退出路径会完整执行 register/unregister，并把 stdout 全量收集到结果对象中。
     * 该用例确保通用执行器在最常见的成功路径上不会破坏现有行为。
     *
     * @throws Exception 当测试子进程执行失败时抛出
     */
    @Test
    public void run_normalExit_capturesOutputAndCleansUp() throws Exception {
        ProcessBuilder pb = javaChild(TestChildEcho.class, "hello", "world");

        ManagedProcessRunner.RunResult result = ManagedProcessRunner.run(
                pb,
                processManager,
                "managed-runner",
                null,
                30,
                5,
                null
        );

        assertEquals("child should exit cleanly", 0, result.getExitCode());
        assertTrue("stdout should contain first line", result.getOutput().contains("hello"));
        assertTrue("stdout should contain second line", result.getOutput().contains("world"));
        assertEquals(1, processManager.registerCalls);
        assertEquals(1, processManager.unregisterCalls);
        assertEquals(processManager.registeredChannelId.get(), processManager.unregisteredChannelId.get());
        assertSame(processManager.registeredProcess.get(), processManager.unregisteredProcess.get());
        assertNotNull(processManager.registeredChannelId.get());
        assertTrue(processManager.registeredChannelId.get().startsWith("managed-runner-"));
        assertFalse(processManager.registeredProcess.get().isAlive());
        assertEquals(0, processManager.getActiveProcessCount());
    }

    /**
     * 验证当子进程写出一行 stdout 后继续挂起时，执行器仍能在超时后强制结束进程。
     * 这正是本轮修复要兜住的根因场景：如果仍沿用阻塞读到 EOF 的实现，这个测试会一直卡死而不是抛 TimeoutException。
     *
     * @throws Exception 当测试框架或子进程准备失败时抛出
     */
    @Test
    public void run_hungProcessWithOpenStdout_timesOutAndCleansUp() throws Exception {
        ProcessBuilder pb = javaChild(TestChildHangWithStdout.class, "60000");

        long startAt = System.currentTimeMillis();
        try {
            ManagedProcessRunner.run(
                    pb,
                    processManager,
                    "managed-runner",
                    null,
                    2,
                    1,
                    null
            );
            fail("Expected TimeoutException for child process that keeps stdout open");
        } catch (TimeoutException expected) {
            // 预期路径：证明超时逻辑在 stdout 未关闭时仍可达。
        }
        long elapsedMs = System.currentTimeMillis() - startAt;

        assertTrue("timeout should happen promptly instead of blocking on readLine", elapsedMs < 30_000);
        assertEquals(1, processManager.registerCalls);
        assertEquals(1, processManager.unregisterCalls);
        assertFalse(processManager.registeredProcess.get().isAlive());
        assertEquals(0, processManager.getActiveProcessCount());
    }

    /**
     * 测试子进程：逐行输出传入参数后立即退出。
     * 用于验证标准输出采集与正常退出路径。
     */
    public static class TestChildEcho {

        /**
         * 按顺序输出所有参数。
         *
         * @param args 待输出的参数列表
         * @return 无返回值
         */
        public static void main(String[] args) {
            for (String arg : args) {
                System.out.println(arg);
            }
        }
    }

    /**
     * 测试子进程：先输出一行文本，再长时间休眠但不关闭 stdout。
     * 用于稳定复现“调用方若阻塞读取 stdout，将无法走到 waitFor 超时”的问题。
     */
    public static class TestChildHangWithStdout {

        /**
         * 打印一行文本后休眠指定毫秒数。
         *
         * @param args 第一个参数为休眠毫秒数
         * @return 无返回值
         * @throws InterruptedException 当进程被外部终止时抛出
         */
        public static void main(String[] args) throws InterruptedException {
            long sleepMs = Long.parseLong(args[0]);
            System.out.println("ready");
            System.out.flush();
            Thread.sleep(sleepMs);
        }
    }
}
