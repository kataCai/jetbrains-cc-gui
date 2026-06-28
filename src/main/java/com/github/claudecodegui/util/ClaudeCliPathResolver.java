package com.github.claudecodegui.util;

import com.github.claudecodegui.handler.ClaudeCliPathHandler;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;

/**
 * Claude CLI 自定义路径读取工具。
 * 该工具用于在运行时安全地读取用户保存的 CLI 路径配置，
 * 并兼容纯单测、早期启动等尚未初始化 IntelliJ Application 的场景，
 * 避免因 PropertiesComponent 不可用导致功能入口在进程启动前直接失败。
 */
public final class ClaudeCliPathResolver {

    private ClaudeCliPathResolver() {
    }

    /**
     * 安全读取当前保存的 Claude CLI 自定义路径。
     * 当 IntelliJ Application 尚未初始化、PropertiesComponent 不可用，
     * 或读取过程中发生异常时，统一返回 null，让调用方回退到默认 CLI 路径解析逻辑。
     *
     * @return 用户保存的 Claude CLI 路径；若未配置或当前环境不可安全读取则返回 null
     */
    public static String getConfiguredPathOrNull() {
        if (ApplicationManager.getApplication() == null) {
            return null;
        }
        try {
            String value = PropertiesComponent.getInstance()
                    .getValue(ClaudeCliPathHandler.CLAUDE_CLI_PATH_PROPERTY_KEY);
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Exception ignored) {
            return null;
        }
    }
}
