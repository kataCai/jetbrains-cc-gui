package com.github.claudecodegui.handler;

import com.github.claudecodegui.bridge.NodeDetector;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.util.ManagedProcessRunner;

import java.util.Set;

/**
 * Handles Node.js subprocess calls for favorites and session titles services.
 * <p>
 * Extracted from HistoryHandler to encapsulate all Node.js process invocation logic
 * for favorites-service.cjs and session-titles-service.cjs.
 */
public class NodeJsServiceCaller {

    private static final int PROCESS_TIMEOUT_SECONDS = 30;

    private static final Set<String> ALLOWED_FAVORITES_FUNCTIONS = Set.of(
        "loadFavorites", "toggleFavorite"
    );

    private static final Set<String> ALLOWED_TITLES_FUNCTIONS = Set.of(
        "loadTitles", "updateTitle", "deleteTitle"
    );

    private final HandlerContext context;

    public NodeJsServiceCaller(HandlerContext context) {
        this.context = context;
    }

    /**
     * Call Node.js favorites-service.
     */
    public String callNodeJsFavoritesService(String functionName, String sessionId) throws Exception {
        validateFunctionName(functionName, ALLOWED_FAVORITES_FUNCTIONS);

        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.isWslPath(nodePath)
                ? NodeDetector.convertToWslPath(bridgePath)
                : bridgePath.replace("\\", "\\\\");

        String nodeScript = String.format(
            "const { %s } = require('%s/services/favorites-service.cjs'); " +
            "const result = %s(process.env.SESSION_ID); " +
            "console.log(JSON.stringify(result));",
            functionName,
            scriptBridgePath,
            functionName
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);
        pb.redirectErrorStream(true);
        pb.environment().put("SESSION_ID", sessionId);

        return executeNodeScript(pb);
    }

    /**
     * Call Node.js session-titles-service (no-argument version, for loadTitles).
     */
    public String callNodeJsTitlesService(String functionName) throws Exception {
        validateFunctionName(functionName, ALLOWED_TITLES_FUNCTIONS);

        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.isWslPath(nodePath)
                ? NodeDetector.convertToWslPath(bridgePath)
                : bridgePath.replace("\\", "\\\\");

        String nodeScript = String.format(
            "const { %s } = require('%s/services/session-titles-service.cjs'); " +
            "const result = %s(); " +
            "console.log(JSON.stringify(result));",
            functionName,
            scriptBridgePath,
            functionName
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);
        pb.redirectErrorStream(true);

        return executeNodeScript(pb);
    }

    /**
     * Call Node.js session-titles-service (with parameters, for updateTitle).
     */
    public String callNodeJsTitlesServiceWithParams(String functionName, String sessionId, String customTitle) throws Exception {
        validateFunctionName(functionName, ALLOWED_TITLES_FUNCTIONS);

        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.isWslPath(nodePath)
                ? NodeDetector.convertToWslPath(bridgePath)
                : bridgePath.replace("\\", "\\\\");

        String nodeScript = String.format(
            "const { %s } = require('%s/services/session-titles-service.cjs'); " +
            "const result = %s(process.env.SESSION_ID, process.env.CUSTOM_TITLE); " +
            "console.log(JSON.stringify(result));",
            functionName,
            scriptBridgePath,
            functionName
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);
        pb.redirectErrorStream(true);
        pb.environment().put("SESSION_ID", sessionId);
        pb.environment().put("CUSTOM_TITLE", customTitle);

        return executeNodeScript(pb);
    }

    /**
     * Call Node.js session-titles-service to delete a title (single parameter version).
     */
    public String callNodeJsDeleteTitle(String sessionId) throws Exception {
        String bridgePath = context.getClaudeSDKBridge().getSdkTestDir().getAbsolutePath();
        String nodePath = context.getClaudeSDKBridge().getNodeExecutable();
        String scriptBridgePath = NodeDetector.isWslPath(nodePath)
                ? NodeDetector.convertToWslPath(bridgePath)
                : bridgePath.replace("\\", "\\\\");

        String nodeScript = String.format(
            "const { deleteTitle } = require('%s/services/session-titles-service.cjs'); " +
            "const result = deleteTitle(process.env.SESSION_ID); " +
            "console.log(JSON.stringify({ success: result }));",
            scriptBridgePath
        );

        ProcessBuilder pb = buildNodeProcessBuilder(nodePath, nodeScript);
        pb.redirectErrorStream(true);
        pb.environment().put("SESSION_ID", sessionId);

        return executeNodeScript(pb);
    }

    /**
     * Build a ProcessBuilder for running a Node.js inline script.
     * Delegates to {@link NodeDetector#buildNodeInlineCommand} so WSL prefixing is centralised.
     */
    private ProcessBuilder buildNodeProcessBuilder(String nodePath, String nodeScript) {
        return new ProcessBuilder(NodeDetector.buildNodeInlineCommand(nodePath, nodeScript));
    }

    /**
     * Validate that the function name is in the allowed set to prevent injection.
     */
    private void validateFunctionName(String functionName, Set<String> allowedFunctions) {
        if (functionName == null || !allowedFunctions.contains(functionName)) {
            throw new IllegalArgumentException(
                "Invalid function name: " + functionName + ". Allowed: " + allowedFunctions
            );
        }
    }

    /**
     * 执行收藏夹或会话标题相关的 Node.js 子进程，并返回 stdout 最后一行 JSON。
     * 这里显式复用统一的进程执行器，避免 stdout 长时间不关闭时主线程卡死在 readLine，
     * 从而让 30 秒超时保护真正可触达。
     *
     * @param pb 已完成环境变量和命令拼装的 ProcessBuilder
     * @return stdout 最后一行；若无输出则返回空对象 JSON
     * @throws Exception 当子进程超时、退出码非 0 或执行链路失败时抛出
     */
    private String executeNodeScript(ProcessBuilder pb) throws Exception {
        ManagedProcessRunner.RunResult result = ManagedProcessRunner.run(
                pb,
                context.getClaudeSDKBridge().getProcessManager(),
                "nodejs-service-call",
                null,
                PROCESS_TIMEOUT_SECONDS,
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
