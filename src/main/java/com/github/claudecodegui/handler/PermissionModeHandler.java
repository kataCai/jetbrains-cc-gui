package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.github.claudecodegui.session.SessionState;

/**
 * Handles permission mode (bypassPermissions, etc.) get/set operations.
 */
public class PermissionModeHandler {

    private static final Logger LOG = Logger.getInstance(PermissionModeHandler.class);

    static final String PERMISSION_MODE_PROPERTY_KEY = "claude.code.permission.mode";

    private final HandlerContext context;
    private final Gson gson = new Gson();

    public PermissionModeHandler(HandlerContext context) {
        this.context = context;
    }

    /**
     * Get current permission mode.
     */
    public void handleGetMode() {
        try {
            String currentMode = "bypassPermissions";  // Default value
            String provider = null;

            // Prefer getting from session first
            if (context.getSession() != null) {
                String sessionMode = context.getSession().getPermissionMode();
                if (sessionMode != null && !sessionMode.trim().isEmpty()) {
                    currentMode = sessionMode;
                }
                provider = context.getSession().getProvider();
            } else {
                // If session does not exist, load from persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                String savedMode = props.getValue(PERMISSION_MODE_PROPERTY_KEY);
                if (savedMode != null && !savedMode.trim().isEmpty()) {
                    currentMode = savedMode.trim();
                }
            }

            final String modeToSend = resolveEffectivePermissionMode(provider, currentMode);

            ApplicationManager.getApplication().invokeLater(() -> {
                context.callJavaScript("window.onModeReceived", context.escapeJs(modeToSend));
            });
        } catch (Exception e) {
            LOG.error("[PermissionModeHandler] Failed to get mode: " + e.getMessage(), e);
        }
    }

    /**
     * Handle set mode request.
     */
    public void handleSetMode(String content) {
        try {
            String mode = content;
            if (content != null && !content.isEmpty()) {
                try {
                    JsonObject json = gson.fromJson(content, JsonObject.class);
                    if (json.has("mode")) {
                        mode = json.get("mode").getAsString();
                    }
                } catch (Exception e) {
                    // content itself is the mode
                }
            }

            String provider = context.getSession() != null ? context.getSession().getProvider() : null;
            mode = resolveEffectivePermissionMode(provider, mode);

            // Check if session exists
            if (context.getSession() != null) {
                context.getSession().setPermissionMode(mode);

                // Save permission mode to persistent storage
                PropertiesComponent props = PropertiesComponent.getInstance();
                props.setValue(PERMISSION_MODE_PROPERTY_KEY, mode);
                LOG.info("Saved permission mode to settings: " + mode);
                com.github.claudecodegui.notifications.ClaudeNotifier.setMode(context.getProject(), mode);
            } else {
                LOG.warn("[PermissionModeHandler] WARNING: Session is null! Cannot set permission mode");
            }
        } catch (Exception e) {
            LOG.error("[PermissionModeHandler] Failed to set mode: " + e.getMessage(), e);
        }
    }

    static String resolveEffectivePermissionMode(String provider, String mode) {
        // 统一在这里做模式归一化，避免前端、持久化配置、运行时 session
        // 各自保留一套不同的兜底逻辑，最终导致 UI 展示和真实执行模式不一致。
        String normalizedMode = mode == null ? "" : mode.trim();
        if (normalizedMode.isEmpty()) {
            return "default";
        }
        if (!SessionState.isValidPermissionMode(normalizedMode)) {
            LOG.warn("[PermissionModeHandler] Ignoring invalid mode: " + mode);
            return "default";
        }
        if ("codex".equals(provider) && "plan".equals(normalizedMode)) {
            // 当前产品层虽然暴露了 Chat/Plan 的概念，但 Codex provider
            // 底层还没有真正支持 plan permission mode，因此这里强制回落到 default，
            // 保证发送链路、状态栏和持久化配置看到的是同一个“可执行模式”。
            return "default";
        }
        return normalizedMode;
    }
}
