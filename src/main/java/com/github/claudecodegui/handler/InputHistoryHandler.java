package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.ManagedProcessRunner;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Handles input history management messages.
 * Delegates to Node.js input-history-service.cjs for actual storage.
 */
public class InputHistoryHandler {

    private static final Logger LOG = Logger.getInstance(InputHistoryHandler.class);

    private final HandlerContext context;

    public InputHistoryHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get input history records.
     */
    public void handleGetInputHistory() {
        CompletableFuture.runAsync(() -> {
            try {
                String result = callInputHistoryService("getAllHistoryData", null);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.callJavaScript("window.onInputHistoryLoaded", context.escapeJs(result));
                });
            } catch (Exception e) {
                LOG.error("[InputHistoryHandler] Failed to get input history: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.callJavaScript("window.onInputHistoryLoaded", context.escapeJs("{\"items\":[],\"counts\":{}}"));
                });
            }
        });
    }

    /**
     * Record input history.
     * @param content JSON array of fragments
     */
    public void handleRecordInputHistory(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                String result = callInputHistoryServiceWithArray("recordHistory", content);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.callJavaScript("window.onInputHistoryRecorded", context.escapeJs(result));
                });
            } catch (Exception e) {
                LOG.error("[InputHistoryHandler] Failed to record input history: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Delete a single input history item.
     * @param content the history item to delete
     */
    public void handleDeleteInputHistoryItem(String content) {
        CompletableFuture.runAsync(() -> {
            try {
                String result = callInputHistoryService("deleteHistoryItem", content);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.callJavaScript("window.onInputHistoryDeleted", context.escapeJs(result));
                });
            } catch (Exception e) {
                LOG.error("[InputHistoryHandler] Failed to delete input history item: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Clear all input history.
     */
    public void handleClearInputHistory() {
        CompletableFuture.runAsync(() -> {
            try {
                String result = callInputHistoryService("clearAllHistory", null);
                ApplicationManager.getApplication().invokeLater(() -> {
                    context.callJavaScript("window.onInputHistoryCleared", context.escapeJs(result));
                });
            } catch (Exception e) {
                LOG.error("[InputHistoryHandler] Failed to clear input history: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Call Node.js input-history-service (single parameter version).
     */
    public String callInputHistoryService(String functionName, String param) throws Exception {
        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();

        String nodeScript;
        if (param == null || param.isEmpty()) {
            // Call without parameters
            nodeScript = String.format(
                "const { %s } = require('%s/services/input-history-service.cjs'); " +
                "const result = %s(); " +
                "console.log(JSON.stringify(result));",
                functionName,
                bridgePath.replace("\\", "\\\\"),
                functionName
            );
            return executeNodeScript(nodePath, nodeScript, null);
        } else {
            // Single parameter call (passed via stdin to avoid escaping issues)
            nodeScript = String.format(
                "const { %s } = require('%s/services/input-history-service.cjs'); " +
                "let input = ''; " +
                "process.stdin.on('data', chunk => input += chunk); " +
                "process.stdin.on('end', () => { " +
                "  try { " +
                "    const param = input.trim(); " +
                "    const result = %s(param); " +
                "    console.log(JSON.stringify(result)); " +
                "  } catch (err) { " +
                "    console.error(JSON.stringify({ error: err.message })); " +
                "    process.exit(1); " +
                "  } " +
                "});",
                functionName,
                bridgePath.replace("\\", "\\\\"),
                functionName
            );
            return executeNodeScript(nodePath, nodeScript, param);
        }
    }

    /**
     * Call Node.js input-history-service (array parameter version, used for recordHistory).
     */
    public String callInputHistoryServiceWithArray(String functionName, String jsonArrayParam) throws Exception {
        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();

        // Use stdin to pass JSON data, avoiding shell escaping issues with special characters
        String nodeScript = String.format(
            "const { %s } = require('%s/services/input-history-service.cjs'); " +
            "let input = ''; " +
            "process.stdin.on('data', chunk => input += chunk); " +
            "process.stdin.on('end', () => { " +
            "  try { " +
            "    const data = JSON.parse(input); " +
            "    const result = %s(data); " +
            "    console.log(JSON.stringify(result)); " +
            "  } catch (err) { " +
            "    console.error(JSON.stringify({ error: err.message })); " +
            "    process.exit(1); " +
            "  } " +
            "});",
            functionName,
            bridgePath.replace("\\", "\\\\"),
            functionName
        );

        return executeNodeScript(nodePath, nodeScript, jsonArrayParam);
    }

    /**
     * 执行一次短生命周期的 Node.js inline script，并返回 stdout 最后一行 JSON。
     * 这里统一复用 ManagedProcessRunner，避免旧实现“先阻塞读完 stdout，再 waitFor 超时”
     * 导致子进程卡住但 stdout 未关闭时，30 秒超时分支永远无法触达。
     *
     * @param nodePath Node 可执行文件路径
     * @param nodeScript 通过 `node -e` 执行的脚本文本
     * @param stdinData 需要写入 stdin 的内容；为 null 时表示无输入
     * @return stdout 最后一行；若无输出则返回空对象 JSON
     * @throws Exception 当子进程超时、退出码非 0 或执行链路失败时抛出
     */
    private String executeNodeScript(String nodePath, String nodeScript, String stdinData) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(NodeDetector.buildNodeInlineCommand(nodePath, nodeScript));
        pb.redirectErrorStream(true);

        ManagedProcessRunner.RunResult result = ManagedProcessRunner.run(
                pb,
                context.getClaudeSDKBridge().getProcessManager(),
                "input-history",
                stdinData,
                30,
                5,
                null
        );
        if (result.getExitCode() != 0) {
            throw new Exception("Node.js process exited with code "
                    + result.getExitCode() + ": " + result.getOutput());
        }
        String[] lines = result.getOutput().split("\n");
        return lines.length > 0 ? lines[lines.length - 1] : "{}";
    }
}
