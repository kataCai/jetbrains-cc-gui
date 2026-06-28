package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 处理用户自定义 Claude Code CLI 可执行文件路径的读取、校验与持久化。
 * 该配置用于覆盖 ai-bridge 默认随 SDK 打包的 Claude CLI，可用于公司内部分发版本、
 * 自定义安装目录或排查 SDK/CLI 版本兼容问题。
 *
 * @param context Handler 上下文，用于回调前端与访问 Claude bridge
 * @return 无返回值，通过 window callback 把结果回推到设置页
 */
public class ClaudeCliPathHandler {

    private static final Logger LOG = Logger.getInstance(ClaudeCliPathHandler.class);

    public static final String CLAUDE_CLI_PATH_PROPERTY_KEY = "claude.code.cli.path";

    private final HandlerContext context;
    private final Gson gson = new Gson();

    public ClaudeCliPathHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * 读取当前保存的 Claude CLI 路径。
     * 为空时向前端返回空字符串，避免设置页出现 null/undefined 状态。
     *
     * @param 无
     * @return 无返回值，通过 window.updateClaudeCliPath 回传 JSON
     */
    public void handleGetClaudeCliPath() {
        CompletableFuture.runAsync(() -> {
            try {
                String saved = PropertiesComponent.getInstance().getValue(CLAUDE_CLI_PATH_PROPERTY_KEY);
                String pathToSend = saved != null ? saved.trim() : "";

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", pathToSend);
                    context.callJavaScript("window.updateClaudeCliPath", context.escapeJs(gson.toJson(response)));
                });
            } catch (Exception e) {
                LOG.error("[ClaudeCliPathHandler] Failed to get Claude CLI path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("Failed to load Claude CLI path: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[ClaudeCliPathHandler] Unexpected error in handleGetClaudeCliPath: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * 保存用户输入的 Claude CLI 路径。
     * 成功后会主动关闭 Claude daemon，使新的 CLI 路径在下一次请求时重新注入环境变量。
     * 校验失败时保留用户输入，避免设置页因为后端回刷而清空输入框。
     *
     * @param content 前端传入的 JSON，格式为 {"path":"..."}
     * @return 无返回值，通过 window.updateClaudeCliPath / window.showError / window.showSwitchSuccess 回传结果
     */
    public void handleSetClaudeCliPath(String content) {
        String parsedPath = null;
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                parsedPath = json.get("path").getAsString();
            }
        } catch (Exception e) {
            LOG.error("[ClaudeCliPathHandler] Failed to parse set_claude_cli_path content: " + e.getMessage(), e);
            ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("window.showError", context.escapeJs("Failed to save Claude CLI path: " + e.getMessage()))
            );
            return;
        }
        final String pathArg = parsedPath != null ? parsedPath.trim() : null;

        CompletableFuture.runAsync(() -> {
            try {
                PropertiesComponent props = PropertiesComponent.getInstance();
                String finalPath = "";
                boolean success = false;
                String failureMsg = null;

                if (pathArg == null || pathArg.isEmpty()) {
                    props.unsetValue(CLAUDE_CLI_PATH_PROPERTY_KEY);
                    LOG.info("[ClaudeCliPathHandler] Cleared custom Claude CLI path");
                    success = true;
                } else {
                    failureMsg = validateCliPath(new File(pathArg), pathArg);
                    if (failureMsg == null) {
                        props.setValue(CLAUDE_CLI_PATH_PROPERTY_KEY, pathArg);
                        finalPath = pathArg;
                        success = true;
                        LOG.info("[ClaudeCliPathHandler] Saved custom Claude CLI path: " + pathArg);
                    }
                }

                if (success) {
                    try {
                        context.getClaudeSDKBridge().shutdownDaemon();
                    } catch (Exception e) {
                        LOG.warn("[ClaudeCliPathHandler] Failed to shutdown daemon after path change: " + e.getMessage());
                    }
                }

                final boolean successFlag = success;
                final String failureMsgFinal = failureMsg;
                final String finalPathToSend = finalPath;
                final String pathToEcho = successFlag ? finalPathToSend : (pathArg != null ? pathArg : "");

                ApplicationManager.getApplication().invokeLater(() -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("path", pathToEcho);
                    context.callJavaScript("window.updateClaudeCliPath", context.escapeJs(gson.toJson(response)));

                    if (successFlag) {
                        String msg = finalPathToSend.isEmpty()
                            ? "Claude CLI path cleared, using bundled SDK"
                            : "Claude CLI path saved: " + finalPathToSend;
                        context.callJavaScript("window.showSwitchSuccess", context.escapeJs(msg));
                    } else {
                        String msg = failureMsgFinal != null ? failureMsgFinal : "Invalid Claude CLI path";
                        context.callJavaScript("window.showError", context.escapeJs(msg));
                    }
                });
            } catch (Exception e) {
                LOG.error("[ClaudeCliPathHandler] Failed to set Claude CLI path: " + e.getMessage(), e);
                ApplicationManager.getApplication().invokeLater(() ->
                    context.callJavaScript("window.showError", context.escapeJs("Failed to save Claude CLI path: " + e.getMessage()))
                );
            }
        }, AppExecutorUtil.getAppExecutorService()).exceptionally(ex -> {
            LOG.error("[ClaudeCliPathHandler] Unexpected error in handleSetClaudeCliPath: " + ex.getMessage(), ex);
            return null;
        });
    }

    /**
     * 校验候选 CLI 路径是否可用。
     * 该方法保持纯函数风格，便于在不启动 IntelliJ 平台的情况下做单元测试。
     *
     * @param f 待校验文件
     * @param rawPath 原始路径文本，用于构造可读错误信息
     * @return 通过时返回 null；失败时返回面向用户的错误原因
     */
    static String validateCliPath(File f, String rawPath) {
        if (!f.isAbsolute()) {
            return "Path must be absolute: " + rawPath;
        }
        if (!f.exists()) {
            return "File does not exist: " + rawPath;
        }
        if (f.isDirectory()) {
            return "Path is a directory, expected an executable file: " + rawPath;
        }
        if (!f.canExecute()) {
            return "File is not executable (check permissions): " + rawPath;
        }
        if (!looksLikeClaudeCliExecutable(f)) {
            return "Path must point to a Claude CLI executable: " + rawPath;
        }
        return null;
    }

    /**
     * 根据文件名判断候选路径是否像 Claude CLI 可执行文件。
     * 这里只做轻量命名约束，不尝试解析二进制内容，
     * 用于拦截明显错误的 `java`、`node` 等任意可执行文件，同时兼容 `claude-*` 分发变体。
     *
     * @param file 待检查的可执行文件
     * @return 文件名满足 Claude CLI 命名约束时返回 true，否则返回 false
     */
    private static boolean looksLikeClaudeCliExecutable(File file) {
        String fileName = file.getName();
        if (fileName == null || fileName.trim().isEmpty()) {
            return false;
        }
        return fileName.toLowerCase(Locale.ROOT).startsWith("claude");
    }
}
