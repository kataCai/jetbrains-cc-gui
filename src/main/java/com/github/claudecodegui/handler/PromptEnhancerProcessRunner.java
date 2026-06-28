package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.util.PlatformUtils;
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
 * PromptEnhancer 子进程运行器。
 * 用于把 PromptEnhancerHandler 内部短生命周期的 Node 进程纳入统一的
 * ProcessManager 管理，补齐注册、超时强杀和 finally 清理，避免子进程在 IDE 生命周期内泄漏。
 * 该类只服务于 PromptEnhancer 链路，不承担通用进程执行框架职责。
 */
final class PromptEnhancerProcessRunner {

    private static final Logger LOG = Logger.getInstance(PromptEnhancerProcessRunner.class);

    private PromptEnhancerProcessRunner() {
    }

    /**
     * 在共享 ProcessManager 监管下执行 PromptEnhancer Node 进程。
     * 该方法会先注册子进程，再写入 stdin，随后异步消费 stdout，
     * 最终基于超时限制等待进程结束；无论成功、超时还是异常，都会在 finally 中执行注销和兜底终止。
     *
     * @param pb 预先配置好的进程构建器
     * @param processManager 共享进程管理器
     * @param stdinJson 需要写入子进程 stdin 的 JSON 文本
     * @param timeoutSeconds 进程最大允许运行时长，单位秒
     * @param readerDrainSeconds 进程退出后等待 reader 线程收尾的宽限时间，单位秒
     * @param lineHandler 每行 stdout 的消费回调
     * @return 子进程退出码
     * @throws IOException 当进程启动或 IO 操作失败时抛出
     * @throws InterruptedException 当当前线程等待过程中被中断时抛出
     * @throws TimeoutException 当子进程超过 timeoutSeconds 仍未退出时抛出
     */
    static int runWithProcessManager(
            ProcessBuilder pb,
            ProcessManager processManager,
            String stdinJson,
            long timeoutSeconds,
            long readerDrainSeconds,
            Consumer<String> lineHandler
    ) throws IOException, InterruptedException, TimeoutException {
        String channelId = "prompt-enhancer-" + UUID.randomUUID();
        Process process = null;
        CompletableFuture<Void> readerFuture = null;
        try {
            process = pb.start();
            processManager.registerProcess(channelId, process);
            LOG.info("[PromptEnhancer] Process started, PID: " + process.pid()
                    + ", channelId: " + channelId);

            try (OutputStreamWriter writer = new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(stdinJson);
                writer.flush();
            }

            final Process finalProcess = process;
            readerFuture = CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(finalProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lineHandler.accept(line);
                    }
                } catch (Exception e) {
                    LOG.debug("[PromptEnhancer] reader thread ended: " + e.getMessage());
                }
            });

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                LOG.warn("[PromptEnhancer] Timeout after " + timeoutSeconds
                        + "s, force killing PID " + process.pid());
                PlatformUtils.terminateProcess(process);
                throw new TimeoutException("Prompt enhancement timed out after "
                        + timeoutSeconds + "s");
            }

            int exitCode = process.exitValue();
            try {
                readerFuture.get(readerDrainSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                LOG.warn("[PromptEnhancer] Reader didn't drain within "
                        + readerDrainSeconds + "s, continuing with partial output");
            } catch (Exception ignored) {
                // readerFuture 的异常已经在 reader 线程内记录，这里不重复包装。
            }
            return exitCode;
        } finally {
            if (process != null) {
                if (process.isAlive()) {
                    PlatformUtils.terminateProcess(process);
                }
                processManager.unregisterProcess(channelId, process);
            }
            if (readerFuture != null && !readerFuture.isDone()) {
                readerFuture.cancel(true);
            }
        }
    }
}
