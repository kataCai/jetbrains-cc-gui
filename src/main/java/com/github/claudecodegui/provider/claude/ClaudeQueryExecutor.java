package com.github.claudecodegui.provider.claude;

import com.github.claudecodegui.bridge.EnvironmentConfigurator;
import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.bridge.ProcessManager;
import com.github.claudecodegui.provider.common.MessageCallback;
import com.github.claudecodegui.provider.common.SDKResult;
import com.github.claudecodegui.util.ClaudeCliPathResolver;
import com.github.claudecodegui.util.PlatformUtils;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 负责 Claude 单轮 simple-query 调用。
 * 该类同时覆盖同步查询和流式查询，两条路径都会直接拉起短生命周期 Node 子进程，
 * 因此需要统一接入 ProcessManager，确保超时、中断和异常路径都能释放进程资源。
 */
class ClaudeQueryExecutor {

    private static final String NODE_SCRIPT = "simple-query.js";

    private final Gson gson;
    private final NodeDetector nodeDetector;
    private final Supplier<File> sdkDirSupplier;
    private final ProcessManager processManager;
    private final EnvironmentConfigurator envConfigurator;
    private final ClaudeJsonOutputExtractor outputExtractor;

    ClaudeQueryExecutor(
            Gson gson,
            NodeDetector nodeDetector,
            Supplier<File> sdkDirSupplier,
            ProcessManager processManager,
            EnvironmentConfigurator envConfigurator,
            ClaudeJsonOutputExtractor outputExtractor
    ) {
        this.gson = gson;
        this.nodeDetector = nodeDetector;
        this.sdkDirSupplier = sdkDirSupplier;
        this.processManager = processManager;
        this.envConfigurator = envConfigurator;
        this.outputExtractor = outputExtractor;
    }

    /**
     * 以阻塞方式执行一次 simple-query。
     * 会把查询进程注册到 ProcessManager，并在超时或异常时执行兜底终止，
     * 避免同步查询长期占用 Node 子进程。
     *
     * @param prompt 用户输入提示词
     * @param timeoutSeconds 最大等待秒数
     * @return SDKResult，包含原始输出、提取到的 assistant 内容及错误信息
     */
    SDKResult executeQuerySync(String prompt, int timeoutSeconds) {
        SDKResult result = new SDKResult();
        StringBuilder output = new StringBuilder();
        StringBuilder jsonBuffer = new StringBuilder();
        boolean inJson = false;
        String channelId = "claude-query-sync-" + UUID.randomUUID();
        Process process = null;

        try {
            String node = nodeDetector.findNodeExecutable();

            JsonObject stdinInput = new JsonObject();
            stdinInput.addProperty("prompt", prompt);
            String stdinJson = gson.toJson(stdinInput);

            File workDir = sdkDirSupplier.get();
            if (workDir == null || !workDir.exists()) {
                result.success = false;
                result.error = "Bridge directory not ready or invalid";
                return result;
            }

            List<String> command = NodeDetector.buildNodeScriptCommand(
                    node, new File(workDir, NODE_SCRIPT).getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir);
            pb.redirectErrorStream(true);
            envConfigurator.updateProcessEnvironment(pb, node);
            pb.environment().put("CLAUDE_USE_STDIN", "true");
            injectClaudeCliOverride(pb.environment());

            process = pb.start();
            processManager.registerProcess(channelId, process);
            ClaudeBridgeUtils.writeStdin(stdinJson, process);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    if (line.contains("[JSON_START]")) {
                        inJson = true;
                        jsonBuffer.setLength(0);
                        continue;
                    }
                    if (line.contains("[JSON_END]")) {
                        inJson = false;
                        continue;
                    }
                    if (inJson) {
                        jsonBuffer.append(line).append("\n");
                    }

                    if (line.contains("[Assistant]:")) {
                        result.finalResult = line.substring(line.indexOf("[Assistant]:") + 12).trim();
                    }
                }
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                PlatformUtils.terminateProcess(process);
                result.success = false;
                result.error = "Process timeout";
                return result;
            }

            int exitCode = process.exitValue();
            result.rawOutput = output.toString();

            if (jsonBuffer.length() > 0) {
                try {
                    String jsonStr = jsonBuffer.toString().trim();
                    JsonObject jsonResult = gson.fromJson(jsonStr, JsonObject.class);
                    result.success = jsonResult.get("success").getAsBoolean();

                    if (result.success) {
                        result.messageCount = jsonResult.get("messageCount").getAsInt();
                    } else {
                        result.error = jsonResult.has("error")
                                ? jsonResult.get("error").getAsString()
                                : "Unknown error";
                    }
                } catch (Exception e) {
                    result.success = false;
                    result.error = "JSON parse failed: " + e.getMessage();
                }
            } else {
                result.success = exitCode == 0;
                if (!result.success) {
                    result.error = "Process exit code: " + exitCode;
                }
            }
        } catch (Exception e) {
            result.success = false;
            result.error = e.getMessage();
            result.rawOutput = output.toString();
        } finally {
            processManager.unregisterProcess(channelId, process);
            processManager.waitForProcessTermination(process);
            if (process != null && process.isAlive()) {
                PlatformUtils.terminateProcess(process);
            }
        }

        return result;
    }

    /**
     * 异步包装同步 simple-query，用于不要求流式回调的调用方。
     *
     * @param prompt 用户输入提示词
     * @return 异步结果
     */
    CompletableFuture<SDKResult> executeQueryAsync(String prompt) {
        return CompletableFuture.supplyAsync(() -> executeQuerySync(prompt, 60));
    }

    /**
     * 以流式方式执行 simple-query，并把中间输出回调给前端。
     * 该路径和同步路径一样会注册进程，确保流式回调结束后没有残留 Node 子进程。
     *
     * @param prompt 用户输入提示词
     * @param callback 前端消息回调
     * @return 包含最终状态的异步结果
     */
    CompletableFuture<SDKResult> executeQueryStream(String prompt, MessageCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            SDKResult result = new SDKResult();
            StringBuilder output = new StringBuilder();
            StringBuilder jsonBuffer = new StringBuilder();
            boolean inJson = false;
            String channelId = "claude-query-stream-" + UUID.randomUUID();

            try {
                String node = nodeDetector.findNodeExecutable();

                JsonObject stdinInput = new JsonObject();
                stdinInput.addProperty("prompt", prompt);
                String stdinJson = gson.toJson(stdinInput);

                File workDir = sdkDirSupplier.get();
                if (workDir == null || !workDir.exists()) {
                    result.success = false;
                    result.error = "Bridge directory not ready or invalid";
                    return result;
                }

                List<String> command = new ArrayList<>();
                command.add(node);
                command.add(NODE_SCRIPT);

                File processTempDir = processManager.prepareClaudeTempDir();

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(workDir);
                pb.redirectErrorStream(true);

                Map<String, String> env = pb.environment();
                envConfigurator.configureTempDir(env, processTempDir);
                envConfigurator.updateProcessEnvironment(pb, node);
                env.put("CLAUDE_USE_STDIN", "true");
                injectClaudeCliOverride(env);

                Process process = null;
                try {
                    process = pb.start();
                    processManager.registerProcess(channelId, process);
                    ClaudeBridgeUtils.writeStdin(stdinJson, process);

                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line).append("\n");

                            if (line.contains("[Message Type:")) {
                                String type = outputExtractor.extractBetween(line, "[Message Type:", "]");
                                if (type != null) {
                                    callback.onMessage("type", type.trim());
                                }
                            }

                            if (line.contains("[Assistant]:")) {
                                String content = line.substring(line.indexOf("[Assistant]:") + 12).trim();
                                result.finalResult = content;
                                callback.onMessage("assistant", content);
                            }

                            if (line.contains("[Result]")) {
                                callback.onMessage("status", "Complete");
                            }

                            if (line.contains("[JSON_START]")) {
                                inJson = true;
                                jsonBuffer.setLength(0);
                                continue;
                            }
                            if (line.contains("[JSON_END]")) {
                                inJson = false;
                                continue;
                            }
                            if (inJson) {
                                jsonBuffer.append(line).append("\n");
                            }
                        }
                    }

                    int exitCode = process.waitFor();
                    result.rawOutput = output.toString();

                    if (jsonBuffer.length() > 0) {
                        try {
                            String jsonStr = jsonBuffer.toString().trim();
                            JsonObject jsonResult = gson.fromJson(jsonStr, JsonObject.class);
                            result.success = jsonResult.get("success").getAsBoolean();

                            if (result.success) {
                                result.messageCount = jsonResult.get("messageCount").getAsInt();
                                callback.onComplete(result);
                            } else {
                                result.error = jsonResult.has("error")
                                        ? jsonResult.get("error").getAsString()
                                        : "Unknown error";
                                callback.onError(result.error);
                            }
                        } catch (Exception e) {
                            result.success = false;
                            result.error = "JSON parse failed: " + e.getMessage();
                            callback.onError(result.error);
                        }
                    } else {
                        result.success = exitCode == 0;
                        if (result.success) {
                            callback.onComplete(result);
                        } else {
                            result.error = "Process exit code: " + exitCode;
                            callback.onError(result.error);
                        }
                    }
                } finally {
                    processManager.unregisterProcess(channelId, process);
                    processManager.waitForProcessTermination(process);
                    if (process != null && process.isAlive()) {
                        PlatformUtils.terminateProcess(process);
                    }
                }
            } catch (Exception e) {
                result.success = false;
                result.error = e.getMessage();
                result.rawOutput = output.toString();
                callback.onError(e.getMessage());
            }

            return result;
        });
    }

    /**
     * 向 Claude 直连查询进程环境中注入自定义 CLI 路径。
     * 与消息发送链路保持一致的环境变量契约，避免设置只对部分入口生效。
     *
     * @param env 目标环境变量集合
     */
    private void injectClaudeCliOverride(Map<String, String> env) {
        String claudeCliPath = ClaudeCliPathResolver.getConfiguredPathOrNull();
        if (claudeCliPath != null) {
            env.put("CLAUDE_CODE_PATH", claudeCliPath);
        }
    }
}
