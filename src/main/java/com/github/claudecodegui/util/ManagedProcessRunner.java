package com.github.claudecodegui.util;

import com.github.claudecodegui.bridge.ProcessManager;
import com.intellij.openapi.diagnostic.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 统一管理短生命周期子进程的启动、超时、输出消费与清理。
 * 该工具类用于替代“先阻塞读取 stdout 到 EOF，再 waitFor 超时”的危险模式，
 * 确保即使子进程保持 stdout 打开但迟迟不退出，调用方仍能在超时后进入强制终止分支。
 *
 * 适用场景：
 * 1. 由插件临时拉起的 Node/Java 子进程；
 * 2. 需要纳入共享 ProcessManager 管理的短生命周期命令；
 * 3. 既要采集完整 stdout，又要避免 stdout 阻塞吞掉 waitFor 超时的入口。
 */
public final class ManagedProcessRunner {

    private static final Logger LOG = Logger.getInstance(ManagedProcessRunner.class);

    private ManagedProcessRunner() {
    }

    /**
     * 在共享 ProcessManager 监管下执行一个短生命周期子进程，并完整采集 stdout。
     * 执行流程固定为：
     * 1. 启动并注册子进程；
     * 2. 可选写入 stdin；
     * 3. 使用独立 reader 线程持续消费 stdout，避免主线程阻塞在 readLine；
     * 4. 主线程按 timeoutSeconds 等待进程退出；
     * 5. 超时则强制结束进程；
     * 6. finally 中注销进程并做兜底清理。
     *
     * @param pb 已配置完成的 ProcessBuilder
     * @param processManager 共享进程管理器
     * @param channelIdPrefix 通道前缀，方法内部会自动拼接 UUID 保证唯一
     * @param stdinData 需要写入子进程 stdin 的数据；为 null 时表示无需写入
     * @param timeoutSeconds 允许子进程存活的最长秒数
     * @param readerDrainSeconds 进程退出后等待 reader 线程收尾的秒数
     * @param lineHandler stdout 行级回调；不需要时可传 null
     * @return 包含退出码与完整 stdout 的执行结果
     * @throws IOException 当进程启动或 IO 写入失败时抛出
     * @throws InterruptedException 当当前线程等待过程中被中断时抛出
     * @throws TimeoutException 当子进程超过 timeoutSeconds 仍未退出时抛出
     */
    public static RunResult run(
            ProcessBuilder pb,
            ProcessManager processManager,
            String channelIdPrefix,
            String stdinData,
            long timeoutSeconds,
            long readerDrainSeconds,
            Consumer<String> lineHandler
    ) throws IOException, InterruptedException, TimeoutException {
        String channelId = (channelIdPrefix == null || channelIdPrefix.trim().isEmpty()
                ? "managed-process"
                : channelIdPrefix.trim()) + "-" + UUID.randomUUID();
        Process process = null;
        CompletableFuture<Void> readerFuture = null;
        StringBuilder output = new StringBuilder();
        try {
            process = pb.start();
            processManager.registerProcess(channelId, process);

            if (stdinData != null) {
                try (OutputStreamWriter writer = new OutputStreamWriter(
                        process.getOutputStream(), StandardCharsets.UTF_8)) {
                    writer.write(stdinData);
                    writer.flush();
                }
            }

            final Process finalProcess = process;
            final Consumer<String> safeLineHandler = lineHandler != null ? lineHandler : line -> { };
            readerFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        safeLineHandler.accept(line);
                    }
                } catch (Exception e) {
                    LOG.debug("[ManagedProcessRunner] reader thread ended: " + e.getMessage());
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcess(process);
                throw new TimeoutException("Process timed out after " + timeoutSeconds + " seconds");
            }

            int exitCode = process.exitValue();
            waitForReaderDrain(readerFuture, readerDrainSeconds);
            return new RunResult(exitCode, output.toString());
        } finally {
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcess(process);
            }
            processManager.unregisterProcess(channelId, process);
            processManager.waitForProcessTermination(process);
            if (readerFuture != null && !readerFuture.isDone()) {
                readerFuture.cancel(true);
            }
        }
    }

    /**
     * 等待 reader 线程在限定时间内把 stdout 缓冲区消费完毕。
     * 这里不把 reader 收尾超时升级为主流程失败，只记录调试日志并返回当前已收集的部分输出，
     * 以避免“主进程已退出但 reader drain 略慢”把正常命令误判为失败。
     *
     * @param readerFuture 后台 stdout reader 任务
     * @param readerDrainSeconds 等待 reader 收尾的最大秒数
     * @return 无返回值
     * @throws InterruptedException 当当前线程等待过程中被中断时抛出
     */
    private static void waitForReaderDrain(
            CompletableFuture<Void> readerFuture,
            long readerDrainSeconds
    ) throws InterruptedException {
        if (readerFuture == null) {
            return;
        }
        try {
            readerFuture.get(readerDrainSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("[ManagedProcessRunner] Reader did not drain within "
                    + readerDrainSeconds + " seconds, returning partial stdout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            LOG.debug("[ManagedProcessRunner] Reader future completed exceptionally: " + e.getMessage());
        }
    }

    /**
     * 表示一次短生命周期子进程执行的结果。
     * 该对象只承载两个最稳定的输出维度：退出码与完整 stdout 文本，
     * 便于不同调用方自行决定如何解析最后一行、JSON 块或调试日志。
     */
    public static final class RunResult {
        private final int exitCode;
        private final String output;

        /**
         * 构造一次子进程执行结果对象。
         *
         * @param exitCode 子进程退出码
         * @param output 执行期间收集到的完整 stdout 文本
         */
        public RunResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output != null ? output : "";
        }

        /**
         * 获取子进程退出码。
         *
         * @return 子进程退出码
         */
        public int getExitCode() {
            return exitCode;
        }

        /**
         * 获取执行期间收集到的完整 stdout 文本。
         *
         * @return 完整 stdout；若无输出则返回空字符串
         */
        public String getOutput() {
            return output;
        }
    }
}
