package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.FontConfigService;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.model.ConflictStrategy;
import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.model.PromptScope;
import com.github.claudecodegui.dependency.DependencyManager;
import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.session.LogicalConversationRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Codemoss configuration service (Facade pattern).
 * Delegates specific functionality to specialized managers.
 */
public class CodemossSettingsService {

    private static final Logger LOG = Logger.getInstance(CodemossSettingsService.class);
    private static final int CONFIG_VERSION = 2;
    private static final String CODEX_SANDBOX_MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS = "danger-full-access";
    private static final String UI_FONT_CONFIG_KEY = "uiFont";
    private static final String UI_FONT_MODE_KEY = "mode";
    private static final String UI_FONT_CUSTOM_PATH_KEY = "customFontPath";
    private static final Set<String> VALID_UI_FONT_MODES = Set.of(
            FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR,
            FontConfigService.UI_FONT_MODE_CUSTOM_FILE
    );
    public static final String CODEX_RUNTIME_ACCESS_INACTIVE = "inactive";
    public static final String CODEX_RUNTIME_ACCESS_MANAGED = "managed";
    public static final String CODEX_RUNTIME_ACCESS_CLI_LOGIN = "cli_login";
    private static final String TASK_REMINDER_KEY = "taskReminder";
    private static final String SOUND_NOTIFICATION_KEY = "soundNotification";
    private static final String REMOTE_COLLAB_KEY = "remoteCollab";
    private static final String FRONTEND_DEBUG_CONFIG_KEY = "frontendDebugConfig";
    private static final String FRONTEND_DEBUG_PANEL_ENABLED_KEY = "panelEnabled";
    private static final String FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY = "archiveEnabled";
    private static final String DEBUG_KEY = "debug";
    private static final String ENABLED_KEY = "enabled";
    private static final String INTERACTIVE_PROVIDER_ID_KEY = "interactiveProviderId";
    private static final String NOTIFY_PROVIDER_IDS_KEY = "notifyProviderIds";
    private static final String PROVIDERS_KEY = "providers";
    private static final String TELEGRAM_KEY = "telegram";
    private static final String GOTIFY_WEB_KEY = "gotify_web";
    private static final String FEISHU_KEY = "feishu";
    private static final String COMMIT_AI_KEY = "commitAi";
    private static final String PROMPT_ENHANCER_KEY = "promptEnhancer";
    private static final String CODEX_MODEL_DISPLAY_KEY = "modelDisplay";
    private static final String CODEX_MODEL_DISPLAY_VISIBLE_KEY = "visible";
    private static final String CODEX_MODEL_DISPLAY_CATALOG_KEY = "catalog";
    private static final String CODEX_MODEL_DISPLAY_VISIBILITY_KEY = "visibility";
    private static final String CODEX_LOGICAL_CONVERSATIONS_KEY = "logicalConversations";
    private static final String CODEX_CONVERSATION_SEGMENTS_KEY = "conversationSegments";
    private static final String CODEX_MODEL_DESCRIPTION_KEY = "description";
    private static final String CODEX_MODEL_REASONING_EFFORT_KEY = "reasoningEffort";
    private static final String CODEX_LAST_REASONING_EFFORT_KEY = "lastReasoningEffort";
    private static final String CODEX_MODEL_SOURCE_KEY = "source";
    private static final String CODEX_MODEL_RUNNABLE_KEY = "runnable";
    private static final String CODEX_CONFIG_KEY = "codex";
    private static final String CODEX_HISTORY_IMAGE_CACHE_KEY = "historyImageCache";
    private static final String CODEX_HISTORY_IMAGE_CACHE_CUSTOM_DIR_KEY = "customDir";
    private static final String CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS_KEY = "retentionDays";
    private static final String CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB_KEY = "maxSizeMb";
    private static final String AI_FEATURE_PROVIDER_KEY = "provider";
    private static final String AI_FEATURE_MODELS_KEY = "models";
    private static final String AI_FEATURE_EFFECTIVE_PROVIDER_KEY = "effectiveProvider";
    private static final String AI_FEATURE_RESOLUTION_SOURCE_KEY = "resolutionSource";
    private static final String AI_FEATURE_AVAILABILITY_KEY = "availability";
    private static final String AI_FEATURE_PROVIDER_CLAUDE = "claude";
    private static final String AI_FEATURE_PROVIDER_CODEX = "codex";
    private static final String AI_FEATURE_RESOLUTION_MANUAL = "manual";
    private static final String AI_FEATURE_RESOLUTION_AUTO = "auto";
    private static final String AI_FEATURE_RESOLUTION_UNAVAILABLE = "unavailable";
    private static final String DEFAULT_PROMPT_ENHANCER_CLAUDE_MODEL = "claude-sonnet-4-6";
    private static final String DEFAULT_PROMPT_ENHANCER_CODEX_MODEL = "gpt-5.5";
    private static final String DEFAULT_COMMIT_AI_CLAUDE_MODEL = "claude-sonnet-4-6";
    private static final String DEFAULT_COMMIT_AI_CODEX_MODEL = "gpt-5.5";
    private static final String USER_LANGUAGE_CONFIG_KEY = "language";
    public static final int DEFAULT_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS = 30;
    public static final int DEFAULT_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB = 1024;
    public static final int MIN_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS = 1;
    public static final int MAX_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS = 365;
    public static final int MIN_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB = 64;
    public static final int MAX_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB = 10 * 1024;

    private final Gson gson;

    // Managers
    private final ConfigPathManager pathManager;
    private final ClaudeSettingsManager claudeSettingsManager;
    private final CodexSettingsManager codexSettingsManager;
    private final CodexMcpServerManager codexMcpServerManager;
    private final WorkingDirectoryManager workingDirectoryManager;
    private final AgentManager agentManager;
    private final SkillManager skillManager;
    private final McpServerManager mcpServerManager;
    private final ProviderManager providerManager;
    private final CodexProviderManager codexProviderManager;

    public CodemossSettingsService() {
        this.gson = new GsonBuilder().setPrettyPrinting().serializeNulls().create();

        // Initialize ConfigPathManager
        this.pathManager = new ConfigPathManager();

        // Initialize ClaudeSettingsManager
        this.claudeSettingsManager = new ClaudeSettingsManager(gson, pathManager);

        // Initialize WorkingDirectoryManager
        this.workingDirectoryManager = new WorkingDirectoryManager(
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        // Initialize AgentManager
        this.agentManager = new AgentManager(gson, pathManager);

        // Initialize SkillManager
        this.skillManager = new SkillManager(
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                claudeSettingsManager
        );

        // Initialize McpServerManager
        this.mcpServerManager = new McpServerManager(
                gson,
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                claudeSettingsManager
        );

        // Initialize ProviderManager
        this.providerManager = new ProviderManager(
                gson,
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                pathManager,
                claudeSettingsManager
        );

        // Initialize CodexSettingsManager
        this.codexSettingsManager = new CodexSettingsManager(gson);

        // Initialize CodexMcpServerManager
        this.codexMcpServerManager = new CodexMcpServerManager(codexSettingsManager);

        // Initialize CodexProviderManager
        this.codexProviderManager = new CodexProviderManager(
                gson,
                (ignored) -> {
                    try {
                        return readConfig();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                (config) -> {
                    try {
                        writeConfig(config);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                pathManager,
                codexSettingsManager
        );
    }

    // ==================== Basic Config Management ====================

    /**
     * Get config file path (~/.codemoss/config.json).
     */
    public String getConfigPath() {
        return pathManager.getConfigPath();
    }

    /**
     * Read the config file.
     */
    public JsonObject readConfig() throws IOException {
        String configPath = getConfigPath();
        File configFile = new File(configPath);

        if (!configFile.exists()) {
            LOG.info("[CodemossSettings] Config file not found, creating default: " + configPath);
            return createDefaultConfig();
        }

        try (FileReader reader = new FileReader(configFile, StandardCharsets.UTF_8)) {
            JsonObject config = JsonParser.parseReader(reader).getAsJsonObject();
            if (migrateTaskReminderConfig(config)) {
                writeConfig(config);
            }
            LOG.info("[CodemossSettings] Successfully read config from: " + configPath);
            return config;
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to read config: " + e.getMessage());
            return createDefaultConfig();
        }
    }

    /**
     * Write the config file.
     */
    public void writeConfig(JsonObject config) throws IOException {
        pathManager.ensureConfigDirectory();

        // Back up existing config
        backupConfig();

        String configPath = getConfigPath();
        try (FileWriter writer = new FileWriter(configPath, StandardCharsets.UTF_8)) {
            gson.toJson(config, writer);
            LOG.info("[CodemossSettings] Successfully wrote config to: " + configPath);
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to write config: " + e.getMessage());
            throw e;
        }
    }

    private void backupConfig() {
        try {
            Path configPath = pathManager.getConfigFilePath();
            if (Files.exists(configPath)) {
                Files.copy(configPath, Paths.get(pathManager.getBackupPath()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to backup config: " + e.getMessage());
        }
    }

    /**
     * 创建默认配置。
     * 这里需要同时初始化 Claude、Codex 与基础功能配置，避免首次启动时各模块读取到缺失节点。
     *
     * @return 包含默认根结构的配置对象
     */
    private JsonObject createDefaultConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("version", CONFIG_VERSION);

        // Claude config - empty provider list
        JsonObject claude = new JsonObject();
        JsonObject providers = new JsonObject();

        claude.addProperty("current", "");
        claude.add("providers", providers);
        config.add("claude", claude);

        JsonObject codex = new JsonObject();
        codex.addProperty("current", "");
        codex.add("providers", new JsonObject());
        codex.add(CODEX_MODEL_DISPLAY_KEY, new JsonObject());
        codex.addProperty("localConfigAuthorized", false);
        codex.add(CODEX_HISTORY_IMAGE_CACHE_KEY, createDefaultCodexHistoryImageCacheConfig());
        config.add(CODEX_CONFIG_KEY, codex);

        config.add(TASK_REMINDER_KEY, createDefaultTaskReminderConfig());
        config.add(REMOTE_COLLAB_KEY, createDefaultRemoteCollabConfig());

        return config;
    }

    /**
     * 获取前端调试配置。
     * 该配置属于插件级运行时偏好，不依赖具体项目，因此保存在根配置节点。
     * 这里统一做标准化，保证旧配置缺字段或字段类型异常时前端仍能拿到完整布尔结构。
     *
     * @return 标准化后的前端调试配置
     * @throws IOException 读取配置失败时抛出
     */
    public JsonObject getFrontendDebugConfig() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(FRONTEND_DEBUG_CONFIG_KEY) || !config.get(FRONTEND_DEBUG_CONFIG_KEY).isJsonObject()) {
            return createDefaultFrontendDebugConfig();
        }
        return normalizeFrontendDebugConfig(config.getAsJsonObject(FRONTEND_DEBUG_CONFIG_KEY));
    }

    /**
     * 获取带“是否已显式配置”标记的前端调试配置快照。
     * 设置页保存值的优先级高于构建期开关；若用户尚未保存该配置，则前端应退回构建期默认值。
     *
     * @return 包含生效布尔值与 configured 标记的配置快照
     * @throws IOException 读取配置失败时抛出
     */
    public JsonObject getFrontendDebugConfigState() throws IOException {
        JsonObject config = readConfig();
        JsonObject persisted = config.has(FRONTEND_DEBUG_CONFIG_KEY) && config.get(FRONTEND_DEBUG_CONFIG_KEY).isJsonObject()
                ? config.getAsJsonObject(FRONTEND_DEBUG_CONFIG_KEY)
                : null;
        JsonObject normalized = normalizeFrontendDebugConfig(persisted);
        JsonObject response = new JsonObject();
        response.addProperty(
                FRONTEND_DEBUG_PANEL_ENABLED_KEY,
                normalized.get(FRONTEND_DEBUG_PANEL_ENABLED_KEY).getAsBoolean()
        );
        response.addProperty(
                FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY,
                normalized.get(FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY).getAsBoolean()
        );
        response.addProperty(
                "panelConfigured",
                hasExplicitBooleanProperty(persisted, FRONTEND_DEBUG_PANEL_ENABLED_KEY)
        );
        response.addProperty(
                "archiveConfigured",
                hasExplicitBooleanProperty(persisted, FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY)
        );
        return response;
    }

    /**
     * 保存前端调试配置。
     * 调试面板显示和诊断日志落档两个开关需要一次性持久化，避免前端分两次提交时出现中间态。
     *
     * @param panelEnabled 是否允许显示前端调试面板
     * @param archiveEnabled 是否允许将关键前端诊断日志桥接并落入 idea.log
     * @throws IOException 写配置失败时抛出
     */
    public void setFrontendDebugConfig(boolean panelEnabled, boolean archiveEnabled) throws IOException {
        JsonObject config = readConfig();
        JsonObject frontendDebugConfig = new JsonObject();
        frontendDebugConfig.addProperty(FRONTEND_DEBUG_PANEL_ENABLED_KEY, panelEnabled);
        frontendDebugConfig.addProperty(FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY, archiveEnabled);
        config.add(FRONTEND_DEBUG_CONFIG_KEY, frontendDebugConfig);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set frontend debug config: panelEnabled=" + panelEnabled
                + ", archiveEnabled=" + archiveEnabled);
    }

    // ==================== Language Config Management ====================

    /**
     * Get the manually configured UI language.
     *
     * @return configured language code, or null when the UI should follow the IDE language
     */
    public String getUserLanguage() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(USER_LANGUAGE_CONFIG_KEY) || config.get(USER_LANGUAGE_CONFIG_KEY).isJsonNull()) {
            return null;
        }
        String language = config.get(USER_LANGUAGE_CONFIG_KEY).getAsString();
        return language == null || language.trim().isEmpty() ? null : language.trim();
    }

    /**
     * Persist the manually configured UI language.
     *
     * @param language supported UI language code
     */
    public void setUserLanguage(String language) throws IOException {
        JsonObject config = readConfig();
        config.addProperty(USER_LANGUAGE_CONFIG_KEY, language);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set user language: " + language);
    }

    /**
     * Clear the manual UI language override so the webview follows the IDE language.
     */
    public void clearUserLanguage() throws IOException {
        JsonObject config = readConfig();
        config.remove(USER_LANGUAGE_CONFIG_KEY);
        writeConfig(config);
        LOG.info("[CodemossSettings] Cleared user language override");
    }

    // ==================== Claude Settings Management ====================

    public JsonObject getCurrentClaudeConfig() throws IOException {
        JsonObject currentConfig = claudeSettingsManager.getCurrentClaudeConfig();

        // If codemossProviderId exists, try to get provider name from codemoss config
        if (currentConfig.has("providerId")) {
            String providerId = currentConfig.get("providerId").getAsString();
            try {
                JsonObject config = readConfig();
                if (config.has("claude")) {
                    JsonObject claude = config.getAsJsonObject("claude");
                    if (claude.has("providers")) {
                        JsonObject providers = claude.getAsJsonObject("providers");
                        if (providers.has(providerId)) {
                            JsonObject provider = providers.getAsJsonObject(providerId);
                            if (provider.has("name")) {
                                currentConfig.addProperty("providerName", provider.get("name").getAsString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore error - provider name is optional
            }
        }

        return currentConfig;
    }

    public JsonObject readClaudeSettings() throws IOException {
        return claudeSettingsManager.readClaudeSettings();
    }

    public Boolean getAlwaysThinkingEnabledFromClaudeSettings() throws IOException {
        return claudeSettingsManager.getAlwaysThinkingEnabled();
    }

    public void setAlwaysThinkingEnabledInClaudeSettings(boolean enabled) throws IOException {
        claudeSettingsManager.setAlwaysThinkingEnabled(enabled);
    }

    public boolean setAlwaysThinkingEnabledInActiveProvider(boolean enabled) throws IOException {
        return providerManager.setAlwaysThinkingEnabledInActiveProvider(enabled);
    }

    public void applyProviderToClaudeSettings(JsonObject provider) throws IOException {
        claudeSettingsManager.applyProviderToClaudeSettings(provider);
    }

    public void applyCliLoginToClaudeSettings() throws IOException {
        claudeSettingsManager.applyCliLoginToClaudeSettings();
    }

    public void removeCliLoginFromClaudeSettings() throws IOException {
        claudeSettingsManager.removeCliLoginFromClaudeSettings();
    }

    public JsonObject readCliLoginAccountInfo() {
        return claudeSettingsManager.readCliLoginAccountInfo();
    }

    public void applyActiveProviderToClaudeSettings() throws IOException {
        providerManager.applyActiveProviderToClaudeSettings();
    }

    // ==================== Working Directory Management ====================

    public String getCustomWorkingDirectory(String projectPath) throws IOException {
        return workingDirectoryManager.getCustomWorkingDirectory(projectPath);
    }

    public void setCustomWorkingDirectory(String projectPath, String customWorkingDir) throws IOException {
        workingDirectoryManager.setCustomWorkingDirectory(projectPath, customWorkingDir);
    }

    public Map<String, String> getAllWorkingDirectories() throws IOException {
        return workingDirectoryManager.getAllWorkingDirectories();
    }

    // ==================== Commit Prompt Config Management ====================

    /**
     * Get the commit AI prompt.
     *
     * @return commit prompt
     */
    public String getCommitPrompt() throws IOException {
        JsonObject config = readConfig();

        // Check for commitPrompt config
        if (config.has("commitPrompt")) {
            return config.get("commitPrompt").getAsString();
        }

        // Return default value (from i18n resource bundle)
        return ClaudeCodeGuiBundle.message("commit.defaultPrompt");
    }

    /**
     * Set the commit AI prompt.
     *
     * @param prompt commit prompt
     */
    public void setCommitPrompt(String prompt) throws IOException {
        JsonObject config = readConfig();

        // Save config
        config.addProperty("commitPrompt", prompt);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set commit prompt: " + prompt);
    }

    /**
     * Get project-level commit AI prompt.
     *
     * @param projectPath project path
     * @return project commit prompt, empty string if not configured
     */
    public String getProjectCommitPrompt(String projectPath) throws IOException {
        if (projectPath == null) {
            return "";
        }
        JsonObject config = readConfig();
        if (config.has("projectCommitPrompt")) {
            JsonObject projectPrompts = config.getAsJsonObject("projectCommitPrompt");
            if (projectPrompts.has(projectPath)) {
                return projectPrompts.get(projectPath).getAsString();
            }
        }
        return "";
    }

    /**
     * Set project-level commit AI prompt.
     *
     * @param projectPath project path
     * @param prompt commit prompt
     */
    public void setProjectCommitPrompt(String projectPath, String prompt) throws IOException {
        if (projectPath == null) {
            return;
        }
        JsonObject config = readConfig();
        JsonObject projectPrompts;
        if (config.has("projectCommitPrompt")) {
            projectPrompts = config.getAsJsonObject("projectCommitPrompt");
        } else {
            projectPrompts = new JsonObject();
            config.add("projectCommitPrompt", projectPrompts);
        }
        projectPrompts.addProperty(projectPath, prompt);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set project commit prompt for project: " + projectPath);
    }

    // ==================== UI Font Config Management ====================

    /**
     * Get persisted UI font configuration.
     *
     * @return normalized UI font configuration
     */
    public JsonObject getUiFontConfig() throws IOException {
        JsonObject config = readConfig();
        if (!config.has(UI_FONT_CONFIG_KEY) || !config.get(UI_FONT_CONFIG_KEY).isJsonObject()) {
            return createDefaultUiFontConfig();
        }
        return normalizeUiFontConfig(config.getAsJsonObject(UI_FONT_CONFIG_KEY));
    }

    /**
     * Persist UI font configuration.
     *
     * @param mode requested mode
     * @param customFontPath custom font path for custom file mode
     */
    public void setUiFontConfig(String mode, String customFontPath) throws IOException {
        JsonObject config = readConfig();
        config.add(UI_FONT_CONFIG_KEY, createUiFontConfig(mode, customFontPath));
        writeConfig(config);
        LOG.debug("[CodemossSettings] Set UI font config: mode=" + mode
                + ", customFontPath=" + customFontPath);
    }

    // ==================== Codex History Image Cache Config Management ====================

    /**
     * 读取 Codex 历史图片缓存配置。
     * <p>
     * 配置存放在 `config.json -> codex.historyImageCache`，并在读取时统一补齐默认值与范围校验，
     * 避免设置页、缓存写入服务和清理逻辑各自散落默认值。
     *
     * @return 归一化后的缓存配置对象
     * @throws IOException 配置文件读取失败时抛出
     */
    public JsonObject getCodexHistoryImageCacheConfig() throws IOException {
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        if (!codex.has(CODEX_HISTORY_IMAGE_CACHE_KEY) || !codex.get(CODEX_HISTORY_IMAGE_CACHE_KEY).isJsonObject()) {
            return createDefaultCodexHistoryImageCacheConfig();
        }
        return normalizeCodexHistoryImageCacheConfig(codex.getAsJsonObject(CODEX_HISTORY_IMAGE_CACHE_KEY));
    }

    /**
     * 持久化 Codex 历史图片缓存配置。
     *
     * @param customDir 用户自定义缓存目录；空串表示恢复默认目录
     * @param retentionDays 缓存保留天数
     * @param maxSizeMb 缓存容量上限，单位 MB
     * @throws IOException 配置写入失败时抛出
     */
    public void setCodexHistoryImageCacheConfig(String customDir, int retentionDays, int maxSizeMb) throws IOException {
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        codex.add(
                CODEX_HISTORY_IMAGE_CACHE_KEY,
                createCodexHistoryImageCacheConfig(customDir, retentionDays, maxSizeMb)
        );
        writeConfig(config);
        LOG.info("[CodemossSettings] Updated Codex history image cache config: customDir=" + customDir
                + ", retentionDays=" + retentionDays
                + ", maxSizeMb=" + maxSizeMb);
    }

    // ==================== Permission Dialog Timeout Config Management ====================

    public static final int DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS =
            PermissionDialogTimeoutSettings.DEFAULT_PERMISSION_DIALOG_TIMEOUT_SECONDS;
    public static final int MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS =
            PermissionDialogTimeoutSettings.MIN_PERMISSION_DIALOG_TIMEOUT_SECONDS;
    public static final int MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS =
            PermissionDialogTimeoutSettings.MAX_PERMISSION_DIALOG_TIMEOUT_SECONDS;
    public static final long PERMISSION_SAFETY_NET_BUFFER_SECONDS =
            PermissionDialogTimeoutSettings.PERMISSION_SAFETY_NET_BUFFER_SECONDS;

    public static int clampPermissionDialogTimeoutSeconds(int seconds) {
        return PermissionDialogTimeoutSettings.clampPermissionDialogTimeoutSeconds(seconds);
    }

    public int getPermissionDialogTimeoutSeconds() throws IOException {
        return PermissionDialogTimeoutSettings.getPermissionDialogTimeoutSeconds(this);
    }

    public void setPermissionDialogTimeoutSeconds(int seconds) throws IOException {
        PermissionDialogTimeoutSettings.setPermissionDialogTimeoutSeconds(this, seconds);
    }

    /**
     * 获取“是否允许通过右键菜单打开调试面板”的全局开关。
     * 该配置属于 IDE / 插件级偏好，而不是项目级偏好，因此直接保存在根配置节点。
     * 当配置缺失或值非法时，统一按关闭处理，避免在普通用户环境里默认暴露调试入口。
     *
     * @return true 表示允许在右键菜单中显示调试面板入口；false 表示隐藏
     * @throws IOException 读取配置文件失败时抛出
     */
    public boolean getRightClickOpenDevToolsEnabled() throws IOException {
        JsonObject config = readConfig();
        if (config.has("rightClickOpenDevToolsEnabled") && !config.get("rightClickOpenDevToolsEnabled").isJsonNull()) {
            return config.get("rightClickOpenDevToolsEnabled").getAsBoolean();
        }
        return false;
    }

    /**
     * 保存“是否允许通过右键菜单打开调试面板”的全局开关。
     * 这里仅负责持久化用户偏好；前端自定义菜单与 JCEF 原生菜单都会消费同一份布尔值，
     * 以保证设置页、聊天页和浏览器右键菜单的表现一致。
     *
     * @param enabled true 表示显示右键调试入口；false 表示隐藏
     * @throws IOException 写入配置文件失败时抛出
     */
    public void setRightClickOpenDevToolsEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("rightClickOpenDevToolsEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set right click open DevTools enabled: " + enabled);
    }

    /**
     * 创建前端调试配置默认值。
     * 分发包默认关闭调试面板和诊断日志落档，避免普通使用场景产生额外噪声。
     *
     * @return 默认前端调试配置
     */
    private JsonObject createDefaultFrontendDebugConfig() {
        JsonObject config = new JsonObject();
        config.addProperty(FRONTEND_DEBUG_PANEL_ENABLED_KEY, false);
        config.addProperty(FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY, false);
        return config;
    }

    /**
     * 标准化前端调试配置。
     * 仅接受布尔值；缺失或类型异常时统一回退到 false，避免脏配置导致调试能力被意外打开。
     *
     * @param rawConfig 原始前端调试配置
     * @return 标准化后的配置对象
     */
    private JsonObject normalizeFrontendDebugConfig(JsonObject rawConfig) {
        JsonObject normalized = createDefaultFrontendDebugConfig();
        if (rawConfig == null) {
            return normalized;
        }
        if (rawConfig.has(FRONTEND_DEBUG_PANEL_ENABLED_KEY)
                && rawConfig.get(FRONTEND_DEBUG_PANEL_ENABLED_KEY).isJsonPrimitive()
                && rawConfig.get(FRONTEND_DEBUG_PANEL_ENABLED_KEY).getAsJsonPrimitive().isBoolean()) {
            normalized.addProperty(
                    FRONTEND_DEBUG_PANEL_ENABLED_KEY,
                    rawConfig.get(FRONTEND_DEBUG_PANEL_ENABLED_KEY).getAsBoolean()
            );
        }
        if (rawConfig.has(FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY)
                && rawConfig.get(FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY).isJsonPrimitive()
                && rawConfig.get(FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY).getAsJsonPrimitive().isBoolean()) {
            normalized.addProperty(
                    FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY,
                    rawConfig.get(FRONTEND_DEBUG_ARCHIVE_ENABLED_KEY).getAsBoolean()
            );
        }
        return normalized;
    }

    /**
     * 判断指定配置节点中某个布尔字段是否由用户显式保存过。
     * 只有显式存在且类型为 boolean 时，前端才应把该字段视为运行时覆盖值。
     *
     * @param source 原始配置节点
     * @param key 目标字段名
     * @return 字段显式存在且为布尔值时返回 true
     */
    private boolean hasExplicitBooleanProperty(JsonObject source, String key) {
        return source != null
                && source.has(key)
                && source.get(key).isJsonPrimitive()
                && source.get(key).getAsJsonPrimitive().isBoolean();
    }

    // ==================== Streaming Config Management ====================

    /**
     * Get streaming configuration.
     *
     * @param projectPath project path
     * @return whether streaming is enabled
     */
    public boolean getStreamingEnabled(String projectPath) throws IOException {
        JsonObject config = readConfig();

        // Check for streaming config
        if (!config.has("streaming")) {
            return true;
        }

        JsonObject streaming = config.getAsJsonObject("streaming");

        // Check project-specific config first
        if (projectPath != null && streaming.has(projectPath)) {
            return streaming.get(projectPath).getAsBoolean();
        }

        // Fall back to global default if no project-specific config
        if (streaming.has("default")) {
            return streaming.get("default").getAsBoolean();
        }

        return true;
    }

    private JsonObject createDefaultUiFontConfig() {
        JsonObject uiFont = new JsonObject();
        uiFont.addProperty(UI_FONT_MODE_KEY, FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR);
        return uiFont;
    }

    /**
     * 构造默认的 Codex 历史图片缓存配置。
     */
    private JsonObject createDefaultCodexHistoryImageCacheConfig() {
        return createCodexHistoryImageCacheConfig(
                "",
                DEFAULT_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS,
                DEFAULT_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB
        );
    }

    /**
     * 归一化 Codex 历史图片缓存配置，统一裁剪非法目录与数值范围。
     */
    private JsonObject normalizeCodexHistoryImageCacheConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultCodexHistoryImageCacheConfig();
        }
        String customDir = rawConfig.has(CODEX_HISTORY_IMAGE_CACHE_CUSTOM_DIR_KEY)
                && !rawConfig.get(CODEX_HISTORY_IMAGE_CACHE_CUSTOM_DIR_KEY).isJsonNull()
                ? rawConfig.get(CODEX_HISTORY_IMAGE_CACHE_CUSTOM_DIR_KEY).getAsString()
                : "";
        int retentionDays = rawConfig.has(CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS_KEY)
                && !rawConfig.get(CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS_KEY).isJsonNull()
                ? rawConfig.get(CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS_KEY).getAsInt()
                : DEFAULT_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS;
        int maxSizeMb = rawConfig.has(CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB_KEY)
                && !rawConfig.get(CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB_KEY).isJsonNull()
                ? rawConfig.get(CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB_KEY).getAsInt()
                : DEFAULT_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB;
        return createCodexHistoryImageCacheConfig(customDir, retentionDays, maxSizeMb);
    }

    /**
     * 构造可落盘的 Codex 历史图片缓存配置对象。
     */
    private JsonObject createCodexHistoryImageCacheConfig(String customDir, int retentionDays, int maxSizeMb) {
        JsonObject cacheConfig = new JsonObject();
        cacheConfig.addProperty(
                CODEX_HISTORY_IMAGE_CACHE_CUSTOM_DIR_KEY,
                normalizeString(customDir, "")
        );
        cacheConfig.addProperty(
                CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS_KEY,
                clampCodexHistoryImageCacheRetentionDays(retentionDays)
        );
        cacheConfig.addProperty(
                CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB_KEY,
                clampCodexHistoryImageCacheMaxSizeMb(maxSizeMb)
        );
        return cacheConfig;
    }

    private int clampCodexHistoryImageCacheRetentionDays(int retentionDays) {
        return Math.max(
                MIN_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS,
                Math.min(MAX_CODEX_HISTORY_IMAGE_CACHE_RETENTION_DAYS, retentionDays)
        );
    }

    private int clampCodexHistoryImageCacheMaxSizeMb(int maxSizeMb) {
        return Math.max(
                MIN_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB,
                Math.min(MAX_CODEX_HISTORY_IMAGE_CACHE_MAX_SIZE_MB, maxSizeMb)
        );
    }

    /**
     * 确保 codex 根配置对象存在。
     */
    private JsonObject normalizeUiFontConfig(JsonObject rawConfig) {
        if (rawConfig == null) {
            return createDefaultUiFontConfig();
        }
        String requestedMode = rawConfig.has(UI_FONT_MODE_KEY) && !rawConfig.get(UI_FONT_MODE_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_MODE_KEY).getAsString()
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        String customFontPath = rawConfig.has(UI_FONT_CUSTOM_PATH_KEY) && !rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).isJsonNull()
                ? rawConfig.get(UI_FONT_CUSTOM_PATH_KEY).getAsString()
                : null;
        return createUiFontConfig(requestedMode, customFontPath);
    }

    private JsonObject createUiFontConfig(String mode, String customFontPath) {
        String normalizedMode = VALID_UI_FONT_MODES.contains(mode)
                ? mode
                : FontConfigService.UI_FONT_MODE_FOLLOW_EDITOR;
        JsonObject uiFont = new JsonObject();
        uiFont.addProperty(UI_FONT_MODE_KEY, normalizedMode);

        if (FontConfigService.UI_FONT_MODE_CUSTOM_FILE.equals(normalizedMode)
                && customFontPath != null
                && !customFontPath.trim().isEmpty()) {
            uiFont.addProperty(UI_FONT_CUSTOM_PATH_KEY, customFontPath.trim());
        }

        return uiFont;
    }

    /**
     * Set streaming configuration.
     *
     * @param projectPath project path
     * @param enabled     whether to enable
     */
    public void setStreamingEnabled(String projectPath, boolean enabled) throws IOException {
        JsonObject config = readConfig();

        // Ensure streaming object exists
        JsonObject streaming;
        if (config.has("streaming")) {
            streaming = config.getAsJsonObject("streaming");
        } else {
            streaming = new JsonObject();
            config.add("streaming", streaming);
        }

        // Save project-specific config (also serves as default)
        if (projectPath != null) {
            streaming.addProperty(projectPath, enabled);
        }
        streaming.addProperty("default", enabled);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set streaming enabled to " + enabled + " for project: " + projectPath);
    }

    // ==================== Auto Open File Config Management ====================

    /**
     * Get auto-open file configuration.
     *
     * @param projectPath project path
     * @return whether auto-open file is enabled
     */
    public boolean getAutoOpenFileEnabled(String projectPath) throws IOException {
        JsonObject config = readConfig();

        // Check for autoOpenFile config
        if (!config.has("autoOpenFile")) {
            return false;
        }

        JsonObject autoOpenFile = config.getAsJsonObject("autoOpenFile");

        // Check project-specific config first
        if (projectPath != null && autoOpenFile.has(projectPath)) {
            return autoOpenFile.get(projectPath).getAsBoolean();
        }

        // Fall back to global default if no project-specific config
        if (autoOpenFile.has("default")) {
            return autoOpenFile.get("default").getAsBoolean();
        }

        return false;
    }

    /**
     * Set auto-open file configuration.
     *
     * @param projectPath project path
     * @param enabled     whether to enable
     */
    public void setAutoOpenFileEnabled(String projectPath, boolean enabled) throws IOException {
        JsonObject config = readConfig();

        // Ensure autoOpenFile object exists
        JsonObject autoOpenFile;
        if (config.has("autoOpenFile")) {
            autoOpenFile = config.getAsJsonObject("autoOpenFile");
        } else {
            autoOpenFile = new JsonObject();
            config.add("autoOpenFile", autoOpenFile);
        }

        // Save project-specific config (also serves as default)
        if (projectPath != null) {
            autoOpenFile.addProperty(projectPath, enabled);
        }
        autoOpenFile.addProperty("default", enabled);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set auto open file enabled to " + enabled + " for project: " + projectPath);
    }

    // ==================== Codex Sandbox Mode Config Management ====================

    /**
     * Get Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @return sandbox mode (workspace-write or danger-full-access)
     */
    public String getCodexSandboxMode(String projectPath) throws IOException {
        JsonObject config = readConfig();
        String defaultMode = getDefaultCodexSandboxMode();

        if (!config.has("codexSandboxMode")) {
            return defaultMode;
        }

        JsonObject sandboxConfig = config.getAsJsonObject("codexSandboxMode");

        if (projectPath != null && sandboxConfig.has(projectPath)) {
            String mode = sandboxConfig.get(projectPath).getAsString();
            return isValidCodexSandboxMode(mode) ? mode : defaultMode;
        }

        if (sandboxConfig.has("default")) {
            String mode = sandboxConfig.get("default").getAsString();
            return isValidCodexSandboxMode(mode) ? mode : defaultMode;
        }

        return defaultMode;
    }

    /**
     * Set Codex sandbox mode configuration.
     *
     * @param projectPath project path
     * @param sandboxMode sandbox mode (workspace-write or danger-full-access)
     */
    public void setCodexSandboxMode(String projectPath, String sandboxMode) throws IOException {
        if (!isValidCodexSandboxMode(sandboxMode)) {
            throw new IllegalArgumentException("Invalid Codex sandbox mode: " + sandboxMode);
        }

        JsonObject config = readConfig();

        JsonObject sandboxConfig;
        if (config.has("codexSandboxMode")) {
            sandboxConfig = config.getAsJsonObject("codexSandboxMode");
        } else {
            sandboxConfig = new JsonObject();
            config.add("codexSandboxMode", sandboxConfig);
        }

        if (projectPath != null) {
            sandboxConfig.addProperty(projectPath, sandboxMode);
        }
        sandboxConfig.addProperty("default", sandboxMode);

        writeConfig(config);
        LOG.info("[CodemossSettings] Set Codex sandbox mode to " + sandboxMode + " for project: " + projectPath);
    }

    private boolean isValidCodexSandboxMode(String mode) {
        return CODEX_SANDBOX_MODE_WORKSPACE_WRITE.equals(mode)
                || CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS.equals(mode);
    }

    private String getDefaultCodexSandboxMode() {
        return CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS;
    }

    // ==================== Provider Management ====================

    public List<JsonObject> getClaudeProviders() throws IOException {
        return providerManager.getClaudeProviders();
    }

    public JsonObject getActiveClaudeProvider() throws IOException {
        return providerManager.getActiveClaudeProvider();
    }

    public void addClaudeProvider(JsonObject provider) throws IOException {
        providerManager.addClaudeProvider(provider);
    }

    public void saveClaudeProvider(JsonObject provider) throws IOException {
        providerManager.saveClaudeProvider(provider);
    }

    public void updateClaudeProvider(String id, JsonObject updates) throws IOException {
        providerManager.updateClaudeProvider(id, updates);
    }

    public DeleteResult deleteClaudeProvider(String id) {
        return providerManager.deleteClaudeProvider(id);
    }

    @Deprecated
    public void deleteClaudeProviderWithException(String id) throws IOException {
        DeleteResult result = deleteClaudeProvider(id);
        if (!result.isSuccess()) {
            throw new IOException(result.getUserFriendlyMessage());
        }
    }

    public void switchClaudeProvider(String id) throws IOException {
        providerManager.switchClaudeProvider(id);
    }

    public void deactivateClaudeProvider() throws IOException {
        providerManager.deactivateClaudeProvider();
    }

    public List<JsonObject> parseProvidersFromCcSwitchDb(String dbPath) throws IOException {
        return providerManager.parseProvidersFromCcSwitchDb(dbPath);
    }

    public int saveProviders(List<JsonObject> providers) throws IOException {
        return providerManager.saveProviders(providers);
    }

    public void saveProviderOrder(List<String> orderedIds) throws IOException {
        providerManager.saveProviderOrder(orderedIds);
    }

    public boolean isLocalProviderActive() {
        return providerManager.isLocalProviderActive();
    }

    // ==================== MCP Server Management ====================

    public List<JsonObject> getMcpServers() throws IOException {
        return mcpServerManager.getMcpServers();
    }

    public List<JsonObject> getMcpServersWithProjectPath(String projectPath) throws IOException {
        return mcpServerManager.getMcpServersWithProjectPath(projectPath);
    }

    public void upsertMcpServer(JsonObject server) throws IOException {
        mcpServerManager.upsertMcpServer(server);
    }

    public void upsertMcpServer(JsonObject server, String projectPath) throws IOException {
        mcpServerManager.upsertMcpServer(server, projectPath);
    }

    public boolean deleteMcpServer(String serverId) throws IOException {
        return mcpServerManager.deleteMcpServer(serverId);
    }

    public Map<String, Object> validateMcpServer(JsonObject server) {
        return mcpServerManager.validateMcpServer(server);
    }

    // ==================== Codex MCP Server Management ====================

    public CodexMcpServerManager getCodexMcpServerManager() {
        return codexMcpServerManager;
    }

    public List<JsonObject> getCodexMcpServers() throws IOException {
        return codexMcpServerManager.getMcpServers();
    }

    public void upsertCodexMcpServer(JsonObject server) throws IOException {
        codexMcpServerManager.upsertMcpServer(server);
    }

    public boolean deleteCodexMcpServer(String serverId) throws IOException {
        return codexMcpServerManager.deleteMcpServer(serverId);
    }

    public Map<String, Object> validateCodexMcpServer(JsonObject server) {
        return codexMcpServerManager.validateMcpServer(server);
    }

    // ==================== Skills Management ====================

    public List<JsonObject> getSkills() throws IOException {
        return skillManager.getSkills();
    }

    public void upsertSkill(JsonObject skill) throws IOException {
        skillManager.upsertSkill(skill);
    }

    public boolean deleteSkill(String id) throws IOException {
        return skillManager.deleteSkill(id);
    }

    public Map<String, Object> validateSkill(JsonObject skill) {
        return skillManager.validateSkill(skill);
    }

    public void syncSkillsToClaudeSettings() throws IOException {
        skillManager.syncSkillsToClaudeSettings();
    }

    // ==================== Agents Management ====================

    public List<JsonObject> getAgents() throws IOException {
        return agentManager.getAgents();
    }

    public void addAgent(JsonObject agent) throws IOException {
        agentManager.addAgent(agent);
    }

    public void updateAgent(String id, JsonObject updates) throws IOException {
        agentManager.updateAgent(id, updates);
    }

    public boolean deleteAgent(String id) throws IOException {
        return agentManager.deleteAgent(id);
    }

    public JsonObject getAgent(String id) throws IOException {
        return agentManager.getAgent(id);
    }

    public String getSelectedAgentId() throws IOException {
        return agentManager.getSelectedAgentId();
    }

    public void setSelectedAgentId(String agentId) throws IOException {
        agentManager.setSelectedAgentId(agentId);
    }

    public AgentManager getAgentManager() {
        return agentManager;
    }

    // ==================== Prompts Management ====================

    /**
     * Get a PromptManager for the specified scope.
     * Creates managers on-demand using PromptManagerFactory.
     *
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return An AbstractPromptManager instance for the specified scope
     */
    public AbstractPromptManager getPromptManager(PromptScope scope, Project project) {
        return PromptManagerFactory.create(scope, gson, pathManager, project);
    }

    /**
     * Get prompts from the specified scope.
     *
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return List of prompts
     * @throws IOException if reading fails
     */
    public List<JsonObject> getPrompts(PromptScope scope, Project project) throws IOException {
        return getPromptManager(scope, project).getPrompts();
    }

    /**
     * Add a prompt to the specified scope.
     *
     * @param prompt  The prompt to add
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @throws IOException if writing fails
     */
    public void addPrompt(JsonObject prompt, PromptScope scope, Project project) throws IOException {
        getPromptManager(scope, project).addPrompt(prompt);
    }

    /**
     * Update a prompt in the specified scope.
     *
     * @param id      The prompt ID
     * @param updates The updates to apply
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @throws IOException if writing fails
     */
    public void updatePrompt(String id, JsonObject updates, PromptScope scope, Project project) throws IOException {
        getPromptManager(scope, project).updatePrompt(id, updates);
    }

    /**
     * Delete a prompt from the specified scope.
     *
     * @param id      The prompt ID
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return true if deleted, false if not found
     * @throws IOException if writing fails
     */
    public boolean deletePrompt(String id, PromptScope scope, Project project) throws IOException {
        return getPromptManager(scope, project).deletePrompt(id);
    }

    /**
     * Get a prompt by ID from the specified scope.
     *
     * @param id      The prompt ID
     * @param scope   The prompt scope (GLOBAL or PROJECT)
     * @param project The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return The prompt JsonObject, or null if not found
     * @throws IOException if reading fails
     */
    public JsonObject getPrompt(String id, PromptScope scope, Project project) throws IOException {
        return getPromptManager(scope, project).getPrompt(id);
    }

    /**
     * Batch import prompts to the specified scope.
     *
     * @param promptsToImport The prompts to import
     * @param strategy        The conflict resolution strategy
     * @param scope           The prompt scope (GLOBAL or PROJECT)
     * @param project         The IntelliJ Project instance (required for PROJECT scope, can be null for GLOBAL scope)
     * @return A map containing the results of the import operation
     * @throws IOException if writing fails
     */
    public Map<String, Object> batchImportPrompts(List<JsonObject> promptsToImport, ConflictStrategy strategy, PromptScope scope, Project project) throws IOException {
        return getPromptManager(scope, project).batchImportPrompts(promptsToImport, strategy);
    }

    // ==================== Deprecated Backward-Compatible Methods ====================

    /**
     * Get a PromptManager (defaults to GLOBAL scope).
     *
     * @deprecated Use {@link #getPromptManager(PromptScope, Project)} instead
     */
    @Deprecated
    public AbstractPromptManager getPromptManager() {
        return getPromptManager(PromptScope.GLOBAL, null);
    }

    /**
     * Get prompts (defaults to GLOBAL scope).
     *
     * @deprecated Use {@link #getPrompts(PromptScope, Project)} instead
     */
    @Deprecated
    public List<JsonObject> getPrompts() throws IOException {
        return getPrompts(PromptScope.GLOBAL, null);
    }

    /**
     * Add a prompt (defaults to GLOBAL scope).
     *
     * @deprecated Use {@link #addPrompt(JsonObject, PromptScope, Project)} instead
     */
    @Deprecated
    public void addPrompt(JsonObject prompt) throws IOException {
        addPrompt(prompt, PromptScope.GLOBAL, null);
    }

    /**
     * Update a prompt (defaults to GLOBAL scope).
     *
     * @deprecated Use {@link #updatePrompt(String, JsonObject, PromptScope, Project)} instead
     */
    @Deprecated
    public void updatePrompt(String id, JsonObject updates) throws IOException {
        updatePrompt(id, updates, PromptScope.GLOBAL, null);
    }

    /**
     * Delete a prompt (defaults to GLOBAL scope).
     *
     * @deprecated Use {@link #deletePrompt(String, PromptScope, Project)} instead
     */
    @Deprecated
    public boolean deletePrompt(String id) throws IOException {
        return deletePrompt(id, PromptScope.GLOBAL, null);
    }

    /**
     * Get a prompt by ID (defaults to GLOBAL scope).
     *
     * @deprecated Use {@link #getPrompt(String, PromptScope, Project)} instead
     */
    @Deprecated
    public JsonObject getPrompt(String id) throws IOException {
        return getPrompt(id, PromptScope.GLOBAL, null);
    }

    // ==================== Sound Notification Management ====================

    /**
     * Returns the canonical task reminder config.
     */
    public JsonObject getTaskReminderConfig() throws IOException {
        JsonObject config = readConfig();
        JsonObject taskReminder = ensureTaskReminderConfig(config);
        return JsonParser.parseString(taskReminder.toString()).getAsJsonObject();
    }

    /**
     * Saves canonical task reminder config.
     */
    public void setTaskReminderConfig(JsonObject taskReminderConfig) throws IOException {
        JsonObject config = readConfig();
        JsonObject normalized = normalizeTaskReminderConfig(taskReminderConfig);
        config.add(TASK_REMINDER_KEY, normalized);
        writeConfig(config);
        LOG.info("[CodemossSettings] Updated taskReminder config");
    }

    /**
     * Get whether sound notification is enabled.
     *
     * @return whether sound notification is enabled, default is true
     */
    public boolean getSoundNotificationEnabled() throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        return soundConfig.has("enabled") && !soundConfig.get("enabled").isJsonNull()
            ? soundConfig.get("enabled").getAsBoolean()
            : true;
    }

    /**
     * Set whether sound notification is enabled.
     *
     * @param enabled whether to enable
     */
    public void setSoundNotificationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        soundConfig.addProperty("enabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set sound notification enabled: " + enabled);
    }

    /**
     * Get custom sound file path.
     *
     * @return custom sound path, null means use default sound
     */
    public String getCustomSoundPath() throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        if (!soundConfig.has("customSoundPath") || soundConfig.get("customSoundPath").isJsonNull()) {
            return null;
        }
        String customPath = soundConfig.get("customSoundPath").getAsString();
        return customPath == null || customPath.isEmpty() ? null : customPath;
    }

    /**
     * Set custom sound file path.
     *
     * @param path file path, null means use default sound
     */
    public void setCustomSoundPath(String path) throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        soundConfig.addProperty("customSoundPath", (path == null || path.isEmpty()) ? "" : path);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set custom sound path: " + path);
    }

    /**
     * Get whether sound should only play when IDE window is not focused.
     *
     * @return whether only-when-unfocused is enabled, default is true
     */
    public boolean getSoundOnlyWhenUnfocused() throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        return soundConfig.has("onlyWhenIdeUnfocused") && !soundConfig.get("onlyWhenIdeUnfocused").isJsonNull()
            ? soundConfig.get("onlyWhenIdeUnfocused").getAsBoolean()
            : true;
    }

    /**
     * Set whether sound should only play when IDE window is not focused.
     *
     * @param enabled whether to enable
     */
    public void setSoundOnlyWhenUnfocused(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        soundConfig.addProperty("onlyWhenIdeUnfocused", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set sound only when unfocused: " + enabled);
    }

    /**
     * Get selected sound ID.
     *
     * @return sound ID (e.g. "default", "chime", "bell", "ding", "success", "custom"), defaults to "default"
     */
    public String getSelectedSound() throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        if (!soundConfig.has("selectedSound") || soundConfig.get("selectedSound").isJsonNull()) {
            return "default";
        }
        String selected = soundConfig.get("selectedSound").getAsString();
        return selected == null || selected.isEmpty() ? "default" : selected;
    }

    /**
     * Set selected sound ID.
     *
     * @param soundId sound ID, null or empty means "default"
     */
    public void setSelectedSound(String soundId) throws IOException {
        JsonObject config = readConfig();
        JsonObject soundConfig = ensureTaskReminderConfig(config).getAsJsonObject("sound");
        soundConfig.addProperty("selectedSound", (soundId == null || soundId.isEmpty()) ? "default" : soundId);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set selected sound: " + soundId);
    }

    private JsonObject ensureTaskReminderConfig(JsonObject config) {
        if (migrateTaskReminderConfig(config)) {
            // migration has already populated canonical structure in-memory
        }
        return config.getAsJsonObject(TASK_REMINDER_KEY);
    }

    private boolean migrateTaskReminderConfig(JsonObject config) {
        boolean changed = false;
        boolean hadTaskReminder = config.has(TASK_REMINDER_KEY) && config.get(TASK_REMINDER_KEY).isJsonObject();
        boolean hadTaskReminderSound = hadTaskReminder
            && config.getAsJsonObject(TASK_REMINDER_KEY).has("sound")
            && config.getAsJsonObject(TASK_REMINDER_KEY).get("sound").isJsonObject();

        JsonObject normalized = normalizeTaskReminderConfig(
            hadTaskReminder ? config.getAsJsonObject(TASK_REMINDER_KEY) : null
        );

        // 无论旧配置是否完整，先保证 taskReminder 主结构一定存在，
        // 这样后续读写 sound/popup/balloon 都不需要再判空兜底。
        if (!hadTaskReminder || !normalized.toString().equals(config.getAsJsonObject(TASK_REMINDER_KEY).toString())) {
            config.add(TASK_REMINDER_KEY, normalized);
            changed = true;
        }

        if (config.has(SOUND_NOTIFICATION_KEY) && config.get(SOUND_NOTIFICATION_KEY).isJsonObject()) {
            JsonObject legacy = config.getAsJsonObject(SOUND_NOTIFICATION_KEY);
            JsonObject sound = config.getAsJsonObject(TASK_REMINDER_KEY).getAsJsonObject("sound");
            // 如果此前没有新结构，允许老 soundNotification 完整覆盖 sound 默认值；
            // 如果新结构已经存在，只补缺失字段，避免旧字段把用户的新配置回滚掉。
            boolean forceLegacyOverrides = !hadTaskReminder || !hadTaskReminderSound;
            changed |= applyLegacySoundMigration(sound, legacy, forceLegacyOverrides);
        }

        return changed;
    }

    private boolean applyLegacySoundMigration(JsonObject sound, JsonObject legacy, boolean forceOverride) {
        boolean changed = false;
        changed |= copyLegacyBoolean(legacy, "enabled", sound, "enabled", forceOverride);
        changed |= copyLegacyBoolean(legacy, "onlyWhenUnfocused", sound, "onlyWhenIdeUnfocused", forceOverride);
        changed |= copyLegacyString(legacy, "selectedSound", sound, "selectedSound", forceOverride);
        changed |= copyLegacyString(legacy, "customSoundPath", sound, "customSoundPath", forceOverride);
        return changed;
    }

    private boolean copyLegacyBoolean(
        JsonObject source,
        String sourceKey,
        JsonObject target,
        String targetKey,
        boolean forceOverride
    ) {
        if (!source.has(sourceKey) || source.get(sourceKey).isJsonNull()) {
            return false;
        }
        if (!forceOverride && target.has(targetKey) && !target.get(targetKey).isJsonNull()) {
            // 新结构已有显式值时，不让旧字段反向覆盖，避免迁移后每次读取都被旧配置“抢回去”。
            return false;
        }
        boolean value = source.get(sourceKey).getAsBoolean();
        if (target.has(targetKey) && !target.get(targetKey).isJsonNull() && target.get(targetKey).getAsBoolean() == value) {
            return false;
        }
        target.addProperty(targetKey, value);
        return true;
    }

    private boolean copyLegacyString(
        JsonObject source,
        String sourceKey,
        JsonObject target,
        String targetKey,
        boolean forceOverride
    ) {
        if (!source.has(sourceKey) || source.get(sourceKey).isJsonNull()) {
            return false;
        }
        if (!forceOverride && target.has(targetKey) && !target.get(targetKey).isJsonNull()) {
            return false;
        }
        String value = source.get(sourceKey).getAsString();
        if (target.has(targetKey) && !target.get(targetKey).isJsonNull()
            && value.equals(target.get(targetKey).getAsString())) {
            return false;
        }
        target.addProperty(targetKey, value);
        return true;
    }

    private JsonObject normalizeTaskReminderConfig(JsonObject source) {
        JsonObject normalized = createDefaultTaskReminderConfig();
        if (source == null) {
            return normalized;
        }

        // 逐 channel 合并，既保留默认值，又允许 source 做增量覆盖。
        mergeChannel(source, normalized, "popup", false);
        mergeChannel(source, normalized, "balloon", false);
        mergeChannel(source, normalized, "sound", true);
        mergeChannel(source, normalized, "system", false);
        mergeRecoveryPolicy(source, normalized);
        return normalized;
    }

    /**
     * 合并任务恢复策略配置。
     * 首期只暴露恢复成功与瞬时错误重试两个核心能力，避免设置项过度膨胀。
     */
    private void mergeRecoveryPolicy(JsonObject source, JsonObject normalized) {
        if (!source.has("recoveryPolicy") || !source.get("recoveryPolicy").isJsonObject()) {
            return;
        }
        JsonObject sourcePolicy = source.getAsJsonObject("recoveryPolicy");
        JsonObject targetPolicy = normalized.getAsJsonObject("recoveryPolicy");

        if (sourcePolicy.has("enabled") && !sourcePolicy.get("enabled").isJsonNull()) {
            targetPolicy.addProperty("enabled", sourcePolicy.get("enabled").getAsBoolean());
        }
        if (sourcePolicy.has("recoverCompletedOnParseNoise") && !sourcePolicy.get("recoverCompletedOnParseNoise").isJsonNull()) {
            targetPolicy.addProperty("recoverCompletedOnParseNoise", sourcePolicy.get("recoverCompletedOnParseNoise").getAsBoolean());
        }
        if (sourcePolicy.has("retryTransientErrors") && !sourcePolicy.get("retryTransientErrors").isJsonNull()) {
            targetPolicy.addProperty("retryTransientErrors", sourcePolicy.get("retryTransientErrors").getAsBoolean());
        }
        if (sourcePolicy.has("maxAttempts") && !sourcePolicy.get("maxAttempts").isJsonNull()) {
            targetPolicy.addProperty("maxAttempts", sourcePolicy.get("maxAttempts").getAsInt());
        }
        if (sourcePolicy.has("initialDelayMs") && !sourcePolicy.get("initialDelayMs").isJsonNull()) {
            targetPolicy.addProperty("initialDelayMs", sourcePolicy.get("initialDelayMs").getAsInt());
        }
    }

    private void mergeChannel(
        JsonObject source,
        JsonObject normalized,
        String channelKey,
        boolean withSoundFields
    ) {
        if (!source.has(channelKey) || !source.get(channelKey).isJsonObject()) {
            return;
        }
        JsonObject sourceChannel = source.getAsJsonObject(channelKey);
        JsonObject targetChannel = normalized.getAsJsonObject(channelKey);

        if (sourceChannel.has("enabled") && !sourceChannel.get("enabled").isJsonNull()) {
            targetChannel.addProperty("enabled", sourceChannel.get("enabled").getAsBoolean());
        }
        if (sourceChannel.has("onlyWhenIdeUnfocused") && !sourceChannel.get("onlyWhenIdeUnfocused").isJsonNull()) {
            targetChannel.addProperty("onlyWhenIdeUnfocused", sourceChannel.get("onlyWhenIdeUnfocused").getAsBoolean());
        }
        if (sourceChannel.has("states") && sourceChannel.get("states").isJsonArray()) {
            JsonArray states = new JsonArray();
            sourceChannel.getAsJsonArray("states").forEach(element -> {
                if (!element.isJsonNull()) {
                    String state = element.getAsString();
                    if (state != null && !state.trim().isEmpty()) {
                        // 这里只做最小规范化，不在后端强制枚举校验，
                        // 这样前后端都能平滑演进；真正的状态集合约束由前端 normalize 再兜一层。
                        states.add(state.trim());
                    }
                }
            });
            if (states.size() > 0) {
                targetChannel.add("states", states);
            }
        }
        if (withSoundFields) {
            if (sourceChannel.has("selectedSound") && !sourceChannel.get("selectedSound").isJsonNull()) {
                String selectedSound = sourceChannel.get("selectedSound").getAsString();
                targetChannel.addProperty("selectedSound", selectedSound == null || selectedSound.isEmpty() ? "default" : selectedSound);
            }
            if (sourceChannel.has("customSoundPath") && !sourceChannel.get("customSoundPath").isJsonNull()) {
                targetChannel.addProperty("customSoundPath", sourceChannel.get("customSoundPath").getAsString());
            }
        }
    }

    private JsonObject createDefaultTaskReminderConfig() {
        JsonObject taskReminder = new JsonObject();
        taskReminder.add("popup", createDefaultReminderChannel(true, false, "waiting_confirm", "final_error"));
        taskReminder.add("balloon", createDefaultReminderChannel(true, true, "completed", "recovered", "final_error"));

        JsonObject sound = createDefaultReminderChannel(true, true, "completed");
        sound.addProperty("selectedSound", "default");
        sound.addProperty("customSoundPath", "");
        taskReminder.add("sound", sound);
        taskReminder.add("system", createDefaultReminderChannel(false, true, "waiting_confirm", "final_error", "completed"));
        taskReminder.add("recoveryPolicy", createDefaultRecoveryPolicyConfig());
        return taskReminder;
    }

    /**
     * 创建默认恢复策略配置。
     * 这份默认值与 Codex Node 侧的首期恢复策略保持一致。
     */
    private JsonObject createDefaultRecoveryPolicyConfig() {
        JsonObject policy = new JsonObject();
        policy.addProperty("enabled", true);
        policy.addProperty("recoverCompletedOnParseNoise", true);
        policy.addProperty("retryTransientErrors", true);
        policy.addProperty("maxAttempts", 2);
        policy.addProperty("initialDelayMs", 1200);
        return policy;
    }

    private JsonObject createDefaultReminderChannel(boolean enabled, boolean onlyWhenIdeUnfocused, String... states) {
        JsonObject channel = new JsonObject();
        channel.addProperty("enabled", enabled);
        channel.addProperty("onlyWhenIdeUnfocused", onlyWhenIdeUnfocused);
        JsonArray stateArray = new JsonArray();
        for (String state : states) {
            stateArray.add(state);
        }
        channel.add("states", stateArray);
        return channel;
    }

    // ==================== Task Completion Notification Management ====================

    /**
     * Get whether task completion balloon notification is enabled.
     *
     * @return whether task completion notification is enabled, default is false (opt-in)
     */
    public boolean getTaskCompletionNotificationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("taskCompletionNotificationEnabled") && !config.get("taskCompletionNotificationEnabled").isJsonNull()) {
            return config.get("taskCompletionNotificationEnabled").getAsBoolean();
        }

        return false;
    }

    /**
     * Set whether task completion balloon notification is enabled.
     *
     * @param enabled whether to enable
     */
    public void setTaskCompletionNotificationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("taskCompletionNotificationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set task completion notification enabled: " + enabled);
    }

    // ==================== AI Feature Toggle Management ====================

    /**
     * Get whether AI commit message generation is enabled.
     *
     * @return whether commit generation is enabled, default is true
     */
    public boolean getCommitGenerationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("commitGenerationEnabled") && !config.get("commitGenerationEnabled").isJsonNull()) {
            return config.get("commitGenerationEnabled").getAsBoolean();
        }

        return true;
    }

    /**
     * Set whether AI commit message generation is enabled.
     *
     * @param enabled whether to enable
     */
    public void setCommitGenerationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("commitGenerationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set commit generation enabled: " + enabled);
    }

    /**
     * Get whether status bar widget is enabled.
     *
     * @return whether status bar widget is enabled, default is true
     */
    public boolean getStatusBarWidgetEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("statusBarWidgetEnabled") && !config.get("statusBarWidgetEnabled").isJsonNull()) {
            return config.get("statusBarWidgetEnabled").getAsBoolean();
        }

        return true;
    }

    /**
     * Set whether status bar widget is enabled.
     *
     * @param enabled whether to enable
     */
    public void setStatusBarWidgetEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("statusBarWidgetEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set status bar widget enabled: " + enabled);
    }

    // ==================== Remote Collaboration Config Management ====================

    /**
     * Get canonical remote collaboration configuration.
     */
    public JsonObject getRemoteCollabConfig() throws IOException {
        JsonObject config = readConfig();
        boolean changed = migrateRemoteCollabConfig(config);
        if (changed) {
            writeConfig(config);
        }
        return config.getAsJsonObject(REMOTE_COLLAB_KEY).deepCopy();
    }

    /**
     * Save the whole remote collaboration configuration tree.
     */
    public void saveRemoteCollabConfig(JsonObject remoteCollabConfig) throws IOException {
        JsonObject config = readConfig();
        config.add(REMOTE_COLLAB_KEY, normalizeRemoteCollabConfig(remoteCollabConfig));
        writeConfig(config);
    }

    /**
     * Check whether remote collaboration is enabled.
     */
    public boolean isRemoteCollabEnabled() throws IOException {
        return getRemoteCollabConfig().get("enabled").getAsBoolean();
    }

    /**
     * Set remote collaboration enabled flag.
     */
    public void setRemoteCollabEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        JsonObject remoteCollab = ensureRemoteCollabConfig(config);
        remoteCollab.addProperty("enabled", enabled);
        writeConfig(config);
    }

    /**
     * Check whether remote collaboration debug tools are enabled.
     */
    public boolean isRemoteCollabDebugEnabled() throws IOException {
        return getRemoteCollabConfig()
                .getAsJsonObject(DEBUG_KEY)
                .get(ENABLED_KEY)
                .getAsBoolean();
    }

    /**
     * Persist the debug mode switch for remote collaboration.
     * 这里只控制调试工具显隐，不改变正常远程协作链路是否启用。
     */
    public void setRemoteCollabDebugEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        JsonObject remoteCollab = ensureRemoteCollabConfig(config);
        remoteCollab.getAsJsonObject(DEBUG_KEY).addProperty(ENABLED_KEY, enabled);
        writeConfig(config);
    }

    /**
     * 保存远程协作公共路由策略。
     * 这里只更新 interactiveProviderId / notifyProviderIds，避免 provider 细项配置被整棵覆盖。
     */
    public void saveRemoteCollabRoutingPolicy(String interactiveProviderId, JsonArray notifyProviderIds) throws IOException {
        JsonObject config = readConfig();
        JsonObject remoteCollab = ensureRemoteCollabConfig(config);
        JsonObject routingSource = new JsonObject();
        routingSource.addProperty(
            INTERACTIVE_PROVIDER_ID_KEY,
            normalizeString(interactiveProviderId, TELEGRAM_KEY)
        );
        routingSource.add(
            NOTIFY_PROVIDER_IDS_KEY,
            notifyProviderIds == null ? new JsonArray() : notifyProviderIds.deepCopy()
        );
        remoteCollab.addProperty(
            INTERACTIVE_PROVIDER_ID_KEY,
            normalizeString(interactiveProviderId, TELEGRAM_KEY)
        );
        remoteCollab.add(
            NOTIFY_PROVIDER_IDS_KEY,
            normalizeNotifyProviderIds(routingSource, remoteCollab.get(INTERACTIVE_PROVIDER_ID_KEY).getAsString())
        );
        writeConfig(config);
    }

    /**
     * Get canonical Telegram configuration subtree.
     */
    public JsonObject getTelegramConfig() throws IOException {
        return getRemoteCollabConfig()
                .getAsJsonObject(PROVIDERS_KEY)
                .getAsJsonObject(TELEGRAM_KEY)
                .deepCopy();
    }

    /**
     * Get canonical provider configuration subtree by providerId.
     * 当前阶段主要给设置页的通用 provider 配置保存入口使用，避免每新增一种方案都再加一组专用 getter/setter。
     */
    public JsonObject getRemoteCollabProviderConfig(String providerId) throws IOException {
        JsonObject providers = getRemoteCollabConfig().getAsJsonObject(PROVIDERS_KEY);
        String normalizedProviderId = normalizeString(providerId, "");
        if (!providers.has(normalizedProviderId) || !providers.get(normalizedProviderId).isJsonObject()) {
            return new JsonObject();
        }
        return providers.getAsJsonObject(normalizedProviderId).deepCopy();
    }

    /**
     * Save Telegram configuration subtree while preserving the root enabled flag.
     */
    public void saveTelegramConfig(JsonObject telegramConfig) throws IOException {
        JsonObject config = readConfig();
        JsonObject remoteCollab = ensureRemoteCollabConfig(config);
        remoteCollab.getAsJsonObject(PROVIDERS_KEY).add(TELEGRAM_KEY, normalizeTelegramConfig(telegramConfig));
        writeConfig(config);
    }

    /**
     * Save provider configuration subtree.
     * 已知 provider 走规范化逻辑，未知 provider 先按对象深拷贝保留，避免实验性扩展点被当前实现抹掉。
     */
    public void saveRemoteCollabProviderConfig(String providerId, JsonObject providerConfig) throws IOException {
        String normalizedProviderId = normalizeString(providerId, "");
        if (normalizedProviderId.isEmpty()) {
            throw new IllegalArgumentException("providerId cannot be blank");
        }

        JsonObject config = readConfig();
        JsonObject providers = ensureRemoteCollabConfig(config).getAsJsonObject(PROVIDERS_KEY);
        JsonObject normalizedConfig = normalizeProviderConfig(normalizedProviderId, providerConfig);
        providers.add(normalizedProviderId, normalizedConfig);
        writeConfig(config);
    }

    /**
     * Get whether AI session title generation is enabled.
     *
     * @return whether AI title generation is enabled, default is true
     */
    public boolean getAiTitleGenerationEnabled() throws IOException {
        JsonObject config = readConfig();

        if (config.has("aiTitleGenerationEnabled") && !config.get("aiTitleGenerationEnabled").isJsonNull()) {
            return config.get("aiTitleGenerationEnabled").getAsBoolean();
        }

        return true;
    }

    /**
     * Set whether AI session title generation is enabled.
     *
     * @param enabled whether to enable
     */
    public void setAiTitleGenerationEnabled(boolean enabled) throws IOException {
        JsonObject config = readConfig();
        config.addProperty("aiTitleGenerationEnabled", enabled);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set AI title generation enabled: " + enabled);
    }

    // ==================== Prompt Enhancer Config Management ====================

    /**
     * Get prompt enhancer configuration with resolved provider availability.
     *
     * <p>The returned object always includes:
     * <ul>
     *     <li>provider: manual override or null</li>
     *     <li>models: per-provider remembered models</li>
     *     <li>effectiveProvider: resolved runtime provider or null</li>
     *     <li>resolutionSource: manual/auto/unavailable</li>
     *     <li>availability: per-provider availability flags</li>
     * </ul>
     */
    public JsonObject getPromptEnhancerConfig() throws IOException {
        return getAiFeatureConfig(
                PROMPT_ENHANCER_KEY,
                DEFAULT_PROMPT_ENHANCER_CLAUDE_MODEL,
                DEFAULT_PROMPT_ENHANCER_CODEX_MODEL
        );
    }

    /**
     * Persist prompt enhancer provider override and per-provider models.
     *
     * @param provider manual provider override, null/blank to restore auto mode
     * @param claudeModel remembered Claude enhancer model
     * @param codexModel remembered Codex enhancer model
     */
    public void setPromptEnhancerConfig(String provider, String claudeModel, String codexModel) throws IOException {
        setAiFeatureConfig(
                PROMPT_ENHANCER_KEY,
                provider,
                claudeModel,
                codexModel,
                DEFAULT_PROMPT_ENHANCER_CLAUDE_MODEL,
                DEFAULT_PROMPT_ENHANCER_CODEX_MODEL,
                "prompt enhancer"
        );
    }

    public JsonObject getCommitAiConfig() throws IOException {
        return getAiFeatureConfig(
                COMMIT_AI_KEY,
                DEFAULT_COMMIT_AI_CLAUDE_MODEL,
                DEFAULT_COMMIT_AI_CODEX_MODEL
        );
    }

    public void setCommitAiConfig(String provider, String claudeModel, String codexModel) throws IOException {
        setAiFeatureConfig(
                COMMIT_AI_KEY,
                provider,
                claudeModel,
                codexModel,
                DEFAULT_COMMIT_AI_CLAUDE_MODEL,
                DEFAULT_COMMIT_AI_CODEX_MODEL,
                "commit AI"
        );
    }

    private JsonObject getAiFeatureConfig(
            String featureKey,
            String defaultClaudeModel,
            String defaultCodexModel
    ) throws IOException {
        JsonObject rootConfig = readConfig();
        JsonObject featureConfig = getAiFeatureRootObject(rootConfig, featureKey);
        String manualProvider = normalizeAiFeatureProvider(
                featureConfig.has(AI_FEATURE_PROVIDER_KEY) && !featureConfig.get(AI_FEATURE_PROVIDER_KEY).isJsonNull()
                        ? featureConfig.get(AI_FEATURE_PROVIDER_KEY).getAsString()
                        : null
        );
        JsonObject models = getNormalizedAiFeatureModels(featureConfig, defaultClaudeModel, defaultCodexModel);
        JsonObject availability = buildAiFeatureAvailability();
        boolean claudeAvailable = availability.get(AI_FEATURE_PROVIDER_CLAUDE).getAsBoolean();
        boolean codexAvailable = availability.get(AI_FEATURE_PROVIDER_CODEX).getAsBoolean();
        ResolvedAiFeatureProvider resolvedProvider = resolveAiFeatureProvider(
                manualProvider,
                claudeAvailable,
                codexAvailable
        );

        JsonObject response = new JsonObject();
        if (manualProvider == null) {
            response.add(AI_FEATURE_PROVIDER_KEY, JsonNull.INSTANCE);
        } else {
            response.addProperty(AI_FEATURE_PROVIDER_KEY, manualProvider);
        }
        response.add(AI_FEATURE_MODELS_KEY, models);
        if (resolvedProvider.effectiveProvider == null) {
            response.add(AI_FEATURE_EFFECTIVE_PROVIDER_KEY, JsonNull.INSTANCE);
        } else {
            response.addProperty(AI_FEATURE_EFFECTIVE_PROVIDER_KEY, resolvedProvider.effectiveProvider);
        }
        response.addProperty(AI_FEATURE_RESOLUTION_SOURCE_KEY, resolvedProvider.resolutionSource);
        response.add(AI_FEATURE_AVAILABILITY_KEY, availability);
        return response;
    }

    private void setAiFeatureConfig(
            String featureKey,
            String provider,
            String claudeModel,
            String codexModel,
            String defaultClaudeModel,
            String defaultCodexModel,
            String featureLabel
    ) throws IOException {
        JsonObject config = readConfig();
        JsonObject featureConfig = getAiFeatureRootObject(config, featureKey);
        String normalizedProvider = normalizeAiFeatureProvider(provider);
        if (normalizedProvider == null) {
            featureConfig.add(AI_FEATURE_PROVIDER_KEY, JsonNull.INSTANCE);
        } else {
            featureConfig.addProperty(AI_FEATURE_PROVIDER_KEY, normalizedProvider);
        }
        featureConfig.add(
                AI_FEATURE_MODELS_KEY,
                createAiFeatureModels(claudeModel, codexModel, defaultClaudeModel, defaultCodexModel)
        );

        config.add(featureKey, featureConfig);
        writeConfig(config);
        LOG.info("[CodemossSettings] Set " + featureLabel + " config: provider=" + normalizedProvider);
    }

    private JsonObject getAiFeatureRootObject(JsonObject rootConfig, String featureKey) {
        if (rootConfig.has(featureKey) && rootConfig.get(featureKey).isJsonObject()) {
            return rootConfig.getAsJsonObject(featureKey);
        }
        return new JsonObject();
    }

    private JsonObject buildAiFeatureAvailability() {
        JsonObject availability = new JsonObject();
        availability.addProperty(AI_FEATURE_PROVIDER_CLAUDE, isAiFeatureProviderAvailable(AI_FEATURE_PROVIDER_CLAUDE));
        availability.addProperty(AI_FEATURE_PROVIDER_CODEX, isAiFeatureProviderAvailable(AI_FEATURE_PROVIDER_CODEX));
        return availability;
    }

    private boolean isAiFeatureProviderAvailable(String provider) {
        try {
            DependencyManager dependencyManager = new DependencyManager();
            if (AI_FEATURE_PROVIDER_CODEX.equals(provider)) {
                return getActiveCodexProvider() != null && dependencyManager.isInstalled("codex-sdk");
            }
            return getActiveClaudeProvider() != null && dependencyManager.isInstalled("claude-sdk");
        } catch (Exception e) {
            LOG.warn("[CodemossSettings] Failed to resolve AI feature availability for " + provider + ": " + e.getMessage());
            return false;
        }
    }

    private JsonObject getNormalizedAiFeatureModels(
            JsonObject featureConfig,
            String defaultClaudeModel,
            String defaultCodexModel
    ) {
        if (featureConfig != null
                && featureConfig.has(AI_FEATURE_MODELS_KEY)
                && featureConfig.get(AI_FEATURE_MODELS_KEY).isJsonObject()) {
            JsonObject rawModels = featureConfig.getAsJsonObject(AI_FEATURE_MODELS_KEY);
            String claudeModel = rawModels.has(AI_FEATURE_PROVIDER_CLAUDE) && !rawModels.get(AI_FEATURE_PROVIDER_CLAUDE).isJsonNull()
                    ? rawModels.get(AI_FEATURE_PROVIDER_CLAUDE).getAsString()
                    : null;
            String codexModel = rawModels.has(AI_FEATURE_PROVIDER_CODEX) && !rawModels.get(AI_FEATURE_PROVIDER_CODEX).isJsonNull()
                    ? rawModels.get(AI_FEATURE_PROVIDER_CODEX).getAsString()
                    : null;
            return createAiFeatureModels(claudeModel, codexModel, defaultClaudeModel, defaultCodexModel);
        }
        return createAiFeatureModels(null, null, defaultClaudeModel, defaultCodexModel);
    }

    private JsonObject createAiFeatureModels(
            String claudeModel,
            String codexModel,
            String defaultClaudeModel,
            String defaultCodexModel
    ) {
        JsonObject models = new JsonObject();
        models.addProperty(
                AI_FEATURE_PROVIDER_CLAUDE,
                normalizeAiFeatureModel(claudeModel, defaultClaudeModel)
        );
        models.addProperty(
                AI_FEATURE_PROVIDER_CODEX,
                normalizeAiFeatureModel(codexModel, defaultCodexModel)
        );
        return models;
    }

    private ResolvedAiFeatureProvider resolveAiFeatureProvider(
            String manualProvider,
            boolean claudeAvailable,
            boolean codexAvailable
    ) {
        if (manualProvider != null) {
            boolean manualProviderAvailable = AI_FEATURE_PROVIDER_CODEX.equals(manualProvider)
                    ? codexAvailable
                    : claudeAvailable;
            if (manualProviderAvailable) {
                return new ResolvedAiFeatureProvider(manualProvider, AI_FEATURE_RESOLUTION_MANUAL);
            }
            return new ResolvedAiFeatureProvider(null, AI_FEATURE_RESOLUTION_UNAVAILABLE);
        }
        if (codexAvailable) {
            return new ResolvedAiFeatureProvider(AI_FEATURE_PROVIDER_CODEX, AI_FEATURE_RESOLUTION_AUTO);
        }
        if (claudeAvailable) {
            return new ResolvedAiFeatureProvider(AI_FEATURE_PROVIDER_CLAUDE, AI_FEATURE_RESOLUTION_AUTO);
        }
        return new ResolvedAiFeatureProvider(null, AI_FEATURE_RESOLUTION_UNAVAILABLE);
    }

    private String normalizeAiFeatureProvider(String provider) {
        if (provider == null) {
            return null;
        }
        String normalized = provider.trim().toLowerCase();
        if (normalized.isEmpty()) {
            return null;
        }
        if (AI_FEATURE_PROVIDER_CLAUDE.equals(normalized) || AI_FEATURE_PROVIDER_CODEX.equals(normalized)) {
            return normalized;
        }
        return null;
    }

    private String normalizeAiFeatureModel(String model, String defaultValue) {
        if (model == null) {
            return defaultValue;
        }
        String normalized = model.trim();
        return normalized.isEmpty() ? defaultValue : normalized;
    }

    private static class ResolvedAiFeatureProvider {
        private final String effectiveProvider;
        private final String resolutionSource;

        private ResolvedAiFeatureProvider(String effectiveProvider, String resolutionSource) {
            this.effectiveProvider = effectiveProvider;
            this.resolutionSource = resolutionSource;
        }
    }

    // ==================== Codex Provider Management ====================

    public List<JsonObject> getCodexProviders() throws IOException {
        return codexProviderManager.getCodexProviders();
    }

    public JsonObject getActiveCodexProvider() throws IOException {
        return codexProviderManager.getActiveCodexProvider();
    }

    /**
     * 按 id 读取指定的 Codex provider，只读不切换 current。
     *
     * @param providerId provider id
     * @return provider 配置；不存在时返回 null
     * @throws IOException 读取配置失败时抛出
     */
    public JsonObject getCodexProviderById(String providerId) throws IOException {
        return codexProviderManager.getCodexProviderById(providerId);
    }

    public void addCodexProvider(JsonObject provider) throws IOException {
        codexProviderManager.addCodexProvider(provider);
    }

    public void saveCodexProvider(JsonObject provider) throws IOException {
        codexProviderManager.saveCodexProvider(provider);
    }

    public void updateCodexProvider(String id, JsonObject updates) throws IOException {
        codexProviderManager.updateCodexProvider(id, updates);
    }

    public DeleteResult deleteCodexProvider(String id) {
        return codexProviderManager.deleteCodexProvider(id);
    }

    public void switchCodexProvider(String id) throws IOException {
        codexProviderManager.switchCodexProvider(id);
    }

    public void applyActiveProviderToCodexSettings() throws IOException {
        codexProviderManager.applyActiveProviderToCodexSettings();
    }

    public JsonObject getCurrentCodexConfig() throws IOException {
        if (!isCodexLocalConfigAuthorized()) {
            return new JsonObject();
        }
        return codexProviderManager.getCurrentCodexConfig();
    }

    /**
     * 获取当前 Codex 模型状态。
     * 该接口与读取本地 ~/.codex/ 配置共享同一授权边界，未授权时必须返回空对象，
     * 避免前端在未经确认的情况下读取用户本地 CLI 配置。
     *
     * @return 当前模型状态；未授权或读取失败时返回空对象
     * @throws IOException 配置读取失败时抛出
     */
    public JsonObject getCurrentCodexModelState() throws IOException {
        if (!isCodexLocalConfigAuthorized()) {
            return new JsonObject();
        }
        return codexSettingsManager.getCurrentCodexModelState();
    }

    public boolean isCodexCliLoginAvailable() {
        try {
            if (!isCodexLocalConfigAuthorized()) {
                return false;
            }
            return codexSettingsManager.isCodexCliLoginAvailable();
        } catch (IOException e) {
            LOG.warn("[CodemossSettings] Failed to check Codex local authorization: " + e.getMessage());
            return false;
        }
    }

    public void applyCodexCliLoginToSettings() throws IOException {
        codexSettingsManager.applyCodexCliLoginToSettings();
    }

    public void removeCodexCliLoginFromSettings() throws IOException {
        codexSettingsManager.removeCodexCliLoginFromSettings();
    }

    public JsonObject readCodexCliLoginAccountInfo() {
        try {
            if (!isCodexLocalConfigAuthorized()) {
                return null;
            }
            return codexSettingsManager.readCodexCliLoginAccountInfo();
        } catch (IOException e) {
            LOG.warn("[CodemossSettings] Failed to read Codex local authorization state: " + e.getMessage());
            return null;
        }
    }

    public boolean isCodexLocalConfigAuthorized() throws IOException {
        JsonObject config = readConfig();
        if (!config.has("codex") || !config.get("codex").isJsonObject()) {
            return false;
        }
        JsonObject codex = config.getAsJsonObject("codex");
        return codex.has("localConfigAuthorized")
                && !codex.get("localConfigAuthorized").isJsonNull()
                && codex.get("localConfigAuthorized").getAsBoolean();
    }

    public void setCodexLocalConfigAuthorized(boolean authorized) throws IOException {
        JsonObject config = readConfig();
        JsonObject codex;
        if (config.has("codex") && config.get("codex").isJsonObject()) {
            codex = config.getAsJsonObject("codex");
        } else {
            codex = new JsonObject();
            codex.add("providers", new JsonObject());
            codex.addProperty("current", "");
            config.add("codex", codex);
        }

        codex.addProperty("localConfigAuthorized", authorized);
        writeConfig(config);
    }

    public String getCodexRuntimeAccessMode() throws IOException {
        JsonObject config = readConfig();
        if (!config.has("codex") || !config.get("codex").isJsonObject()) {
            return CODEX_RUNTIME_ACCESS_INACTIVE;
        }

        JsonObject codex = config.getAsJsonObject("codex");
        String currentId = codex.has("current") && !codex.get("current").isJsonNull()
                ? codex.get("current").getAsString().trim()
                : "";

        if (CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(currentId)) {
            return isCodexLocalConfigAuthorized()
                    ? CODEX_RUNTIME_ACCESS_CLI_LOGIN
                    : CODEX_RUNTIME_ACCESS_INACTIVE;
        }

        if (!currentId.isEmpty()
                && codex.has("providers")
                && codex.get("providers").isJsonObject()
                && codex.getAsJsonObject("providers").has(currentId)) {
            return CODEX_RUNTIME_ACCESS_MANAGED;
        }

        return CODEX_RUNTIME_ACCESS_INACTIVE;
    }

    public int saveCodexProviders(List<JsonObject> providers) throws IOException {
        return codexProviderManager.saveProviders(providers);
    }

    public void saveCodexProviderOrder(List<String> orderedIds) throws IOException {
        codexProviderManager.saveProviderOrder(orderedIds);
    }

    public JsonObject getSelectedCodexModel() {
        return codexProviderManager.getSelectedModel();
    }

    public void setSelectedCodexModel(String providerId, String modelId) throws IOException {
        codexProviderManager.setSelectedModel(providerId, modelId);
    }

    /**
     * 读取最近一次由用户显式选择的 Codex 思考强度。
     * 该值仅用于“新建 Tab 默认值”初始化，不参与现有 Tab 运行态恢复，
     * 以避免把跨 Tab 的全局偏好和单 Tab 的会话快照语义混在一起。
     *
     * @return 最近一次记录的思考强度；不存在或为空时返回空字符串
     * @throws IOException 配置读取失败时抛出
     */
    public String getLastCodexReasoningEffort() throws IOException {
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        return normalizeString(getOptionalString(codex, CODEX_LAST_REASONING_EFFORT_KEY), "");
    }

    /**
     * 持久化最近一次由用户显式选择的 Codex 思考强度。
     * 只接受非空字符串；空值会清理旧记录，避免脏数据误导新建 Tab 默认值解析。
     *
     * @param reasoningEffort 最近一次选择的思考强度
     * @throws IOException 配置写入失败时抛出
     */
    public void setLastCodexReasoningEffort(String reasoningEffort) throws IOException {
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        String normalizedReasoningEffort = normalizeString(reasoningEffort, "");
        if (normalizedReasoningEffort.isEmpty()) {
            codex.remove(CODEX_LAST_REASONING_EFFORT_KEY);
        } else {
            codex.addProperty(CODEX_LAST_REASONING_EFFORT_KEY, normalizedReasoningEffort);
        }
        writeConfig(config);
    }

    /**
     * 构建仅用于 fresh new tab 的默认初始化快照。
     * 该快照显式固定 provider/mode，并按“最近选择 -> CLI 默认 -> provider/model 元数据 -> 兜底”
     * 的顺序解析 model 与 reasoning，避免新建 Tab 首帧与实际发送链路不一致。
     *
     * @return 可直接下发给前端的新建 Tab 默认快照
     * @throws IOException 配置读取失败时抛出
     */
    public JsonObject buildFreshNewTabDefaults() throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("provider", AI_FEATURE_PROVIDER_CODEX);
        result.addProperty("permissionMode", "bypassPermissions");

        JsonObject selectedCodexModel = getSelectedCodexModel();
        JsonObject cliModelState = getCurrentCodexModelState();

        String rememberedProviderId = normalizeString(getOptionalString(selectedCodexModel, "providerId"), "");
        String rememberedModelId = normalizeString(getOptionalString(selectedCodexModel, "modelId"), "");
        String cliModelId = normalizeString(getOptionalString(cliModelState, "model"), "");
        String cliModelProviderId = normalizeString(getOptionalString(cliModelState, "modelProvider"), "");
        String rememberedReasoningEffort = normalizeString(getLastCodexReasoningEffort(), "");
        String cliReasoningEffort = normalizeString(getOptionalString(cliModelState, "reasoningEffort"), "");

        JsonObject rememberedProvider = resolveRunnableCodexProvider(rememberedProviderId);
        JsonObject cliProvider = resolveRunnableCodexProvider(cliModelProviderId);
        JsonObject activeProvider = getActiveCodexProvider();

        String resolvedProviderId = "";
        String resolvedModelId = "";
        String resolvedModelSource = "runtime_fallback";

        boolean rememberedCliLoginModel = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(rememberedProviderId)
                && rememberedProvider != null
                && !rememberedModelId.isEmpty();
        if (rememberedCliLoginModel || (rememberedProvider != null && providerContainsModel(rememberedProvider, rememberedModelId))) {
            resolvedProviderId = rememberedProviderId;
            resolvedModelId = rememberedModelId;
            resolvedModelSource = "remembered_model";
        } else if (!rememberedModelId.isEmpty()) {
            LOG.info("[CodemossSettings] Fresh new tab ignored stale remembered Codex model: providerId="
                    + rememberedProviderId + ", modelId=" + rememberedModelId);
        }

        if (resolvedModelId.isEmpty() && !cliModelId.isEmpty()) {
            if (cliProvider != null && providerContainsModel(cliProvider, cliModelId)) {
                resolvedProviderId = cliModelProviderId;
                resolvedModelId = cliModelId;
                resolvedModelSource = "codex_config";
            } else if (CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(cliModelProviderId)
                    || cliModelProviderId.isEmpty()) {
                resolvedProviderId = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID;
                resolvedModelId = cliModelId;
                resolvedModelSource = "codex_config_cli";
            } else {
                LOG.info("[CodemossSettings] Fresh new tab ignored CLI model because provider/model is unavailable: providerId="
                        + cliModelProviderId + ", modelId=" + cliModelId);
            }
        }

        if (resolvedModelId.isEmpty()) {
            JsonObject fallbackProvider = activeProvider != null ? activeProvider : rememberedProvider;
            String fallbackProviderId = normalizeString(getOptionalString(fallbackProvider, "id"), "");
            String fallbackModelId = readFirstConfiguredModelId(fallbackProvider);
            if (!fallbackModelId.isEmpty()) {
                resolvedProviderId = fallbackProviderId;
                resolvedModelId = fallbackModelId;
                resolvedModelSource = "provider_default";
            }
        }

        if (resolvedModelId.isEmpty()) {
            resolvedProviderId = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID;
            resolvedModelId = "gpt-5.5";
        }

        JsonObject resolvedProvider = resolveRunnableCodexProvider(resolvedProviderId);
        String resolvedReasoningEffort = "";
        String resolvedReasoningSource = "runtime_fallback";

        if (!rememberedReasoningEffort.isEmpty()) {
            resolvedReasoningEffort = rememberedReasoningEffort;
            resolvedReasoningSource = "remembered_reasoning";
        } else if (!cliReasoningEffort.isEmpty()) {
            resolvedReasoningEffort = cliReasoningEffort;
            resolvedReasoningSource = "codex_config";
        } else {
            JsonObject modelMetadata = findProviderModelById(resolvedProvider, resolvedModelId);
            if (modelMetadata == null
                    && CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(resolvedProviderId)) {
                modelMetadata = findBuiltinCodexCliModelById(resolvedModelId);
            }
            String modelReasoningEffort = normalizeString(getOptionalString(modelMetadata, CODEX_MODEL_REASONING_EFFORT_KEY), "");
            String providerReasoningEffort = normalizeString(getOptionalString(resolvedProvider, CODEX_MODEL_REASONING_EFFORT_KEY), "");
            if (!modelReasoningEffort.isEmpty()) {
                resolvedReasoningEffort = modelReasoningEffort;
                resolvedReasoningSource = "model_metadata";
            } else if (!providerReasoningEffort.isEmpty()) {
                resolvedReasoningEffort = providerReasoningEffort;
                resolvedReasoningSource = "provider_metadata";
            }
        }

        if (resolvedReasoningEffort.isEmpty()) {
            resolvedReasoningEffort = "medium";
        }

        result.addProperty("model", resolvedModelId);
        result.addProperty("reasoningEffort", resolvedReasoningEffort);
        result.addProperty("codexProviderId", resolvedProviderId);
        result.addProperty("modelSource", resolvedModelSource);
        result.addProperty("reasoningSource", resolvedReasoningSource);
        return result;
    }

    /**
     * 原子切换 Codex 当前运行时 provider 与 selectedModel。
     * 统一模型目录选择事件需要一次性更新 `codex.current` 与 `codex.selectedModel`，
     * 避免界面已切换到新模型，但后端下一条消息仍沿用旧 active provider。
     *
     * @param providerId 目标 provider id
     * @param modelId 目标 model id
     * @throws IOException 配置写入失败时抛出
     */
    public void selectCodexModel(String providerId, String modelId) throws IOException {
        codexProviderManager.selectModel(providerId, modelId);
    }

    /**
     * 读取 Codex 模型展示配置。
     * 该方法会基于当前 codex.providers[*].models 动态构造后端 schema，并把缺失的可见性配置迁移为 visible=true，
     * 以便与前端 `providerId::modelId` 复合 key 约定保持一致。
     *
     * @return 包含 catalog 与 visibility 的模型展示配置
     * @throws IOException 配置读写失败时抛出
     */
    public JsonObject getCodexModelDisplayConfig() throws IOException {
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonArray catalog = collectCodexModelDisplayCatalog(codex);
        JsonObject persistedVisibility = ensureCodexModelDisplayObject(codex);
        JsonObject normalizedVisibility = normalizeCodexModelDisplayVisibility(persistedVisibility, catalog, true);

        // 只有当补齐缺失模型或清洗掉非法结构时才落盘，避免无意义改写配置文件。
        if (!normalizedVisibility.equals(persistedVisibility)) {
            codex.add(CODEX_MODEL_DISPLAY_KEY, normalizedVisibility);
            writeConfig(config);
        }

        applyCodexModelDisplayVisibility(catalog, normalizedVisibility);

        JsonObject result = new JsonObject();
        result.add(CODEX_MODEL_DISPLAY_CATALOG_KEY, catalog);
        result.add(CODEX_MODEL_DISPLAY_VISIBILITY_KEY, normalizedVisibility.deepCopy());
        return result;
    }

    /**
     * 保存 Codex 模型展示可见性配置。
     * 该接口只持久化合法的 `providerId::modelId -> { visible: boolean }` 结构，忽略空 key、非对象 value
     * 以及缺失 visible 字段的节点，其余缺失模型会在后续读取时按默认可见补齐。
     *
     * @param visibilityConfig 前端提交的模型可见性配置
     * @throws IOException 配置读写失败时抛出
     */
    public void saveCodexModelDisplayConfig(JsonObject visibilityConfig) throws IOException {
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonObject normalizedVisibility = normalizeCodexModelDisplayVisibility(visibilityConfig, null, false);
        codex.add(CODEX_MODEL_DISPLAY_KEY, normalizedVisibility);
        writeConfig(config);
    }

    /**
     * 保存 Codex 会话绑定元数据。
     * 该映射把 session/thread id 与当时命中的 provider/model/requestMode 绑定，
     * 用于历史恢复后继续发送时保持运行时一致性，避免误用当前 active provider。
     *
     * @param sessionId Codex 会话或 thread id
     * @param binding 待保存的绑定元数据；传入 null 或空绑定时会删除旧记录
     * @throws IOException 配置读写失败时抛出
     */
    public void saveCodexSessionBinding(String sessionId, CodexSessionBinding binding) throws IOException {
        String normalizedSessionId = normalizeSessionBindingSessionId(sessionId);
        if (normalizedSessionId == null) {
            return;
        }

        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonObject sessionBindings = ensureCodexSessionBindingsObject(codex);
        if (binding == null || !binding.isMeaningful()) {
            sessionBindings.remove(normalizedSessionId);
        } else {
            sessionBindings.add(normalizedSessionId, binding.toJson());
        }
        writeConfig(config);
    }

    /**
     * 读取指定 Codex 会话的绑定元数据。
     *
     * @param sessionId Codex 会话或 thread id
     * @return 已保存的绑定元数据；不存在时返回 null
     * @throws IOException 配置读取失败时抛出
     */
    public CodexSessionBinding getCodexSessionBinding(String sessionId) throws IOException {
        String normalizedSessionId = normalizeSessionBindingSessionId(sessionId);
        if (normalizedSessionId == null) {
            return null;
        }

        JsonObject config = readConfig();
        if (!config.has("codex") || !config.get("codex").isJsonObject()) {
            return null;
        }
        JsonObject codex = config.getAsJsonObject("codex");
        if (!codex.has("sessionBindings") || !codex.get("sessionBindings").isJsonObject()) {
            return null;
        }
        JsonObject sessionBindings = codex.getAsJsonObject("sessionBindings");
        if (!sessionBindings.has(normalizedSessionId) || !sessionBindings.get(normalizedSessionId).isJsonObject()) {
            return null;
        }
        return CodexSessionBinding.fromJson(sessionBindings.getAsJsonObject(normalizedSessionId));
    }

    /**
     * 删除指定 Codex 会话的绑定元数据。
     *
     * @param sessionId Codex 会话或 thread id
     * @throws IOException 配置读写失败时抛出
     */
    public void deleteCodexSessionBinding(String sessionId) throws IOException {
        saveCodexSessionBinding(sessionId, null);
    }

    /**
     * 保存逻辑会话元数据。
     * 该记录用于给历史列表、Tab 恢复和继续分段逻辑提供统一的“用户会话身份”，
     * 避免不同物理 session 在切模型或切供应商后被历史层误判为互不相关的新会话。
     *
     * @param record 待保存的逻辑会话记录；传入 null 或无效记录时会忽略
     * @throws IOException 配置读写失败时抛出
     */
    public void saveLogicalConversationRecord(LogicalConversationRecord record) throws IOException {
        if (record == null || !record.isMeaningful()) {
            return;
        }
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonObject logicalConversations = ensureCodexLogicalConversationsObject(codex);
        logicalConversations.add(record.getLogicalConversationId(), record.toJson());
        writeConfig(config);
    }

    /**
     * 读取指定逻辑会话元数据。
     *
     * @param logicalConversationId 逻辑会话唯一标识
     * @return 已保存的逻辑会话记录；不存在时返回 null
     * @throws IOException 配置读取失败时抛出
     */
    public LogicalConversationRecord getLogicalConversationRecord(String logicalConversationId) throws IOException {
        String normalizedConversationId = normalizeConversationMetadataId(logicalConversationId);
        if (normalizedConversationId == null) {
            return null;
        }
        JsonObject config = readConfig();
        if (!config.has("codex") || !config.get("codex").isJsonObject()) {
            return null;
        }
        JsonObject codex = config.getAsJsonObject("codex");
        if (!codex.has(CODEX_LOGICAL_CONVERSATIONS_KEY) || !codex.get(CODEX_LOGICAL_CONVERSATIONS_KEY).isJsonObject()) {
            return null;
        }
        JsonObject logicalConversations = codex.getAsJsonObject(CODEX_LOGICAL_CONVERSATIONS_KEY);
        if (!logicalConversations.has(normalizedConversationId)
                || !logicalConversations.get(normalizedConversationId).isJsonObject()) {
            return null;
        }
        return LogicalConversationRecord.fromJson(logicalConversations.getAsJsonObject(normalizedConversationId));
    }

    /**
     * 删除指定逻辑会话元数据。
     *
     * @param logicalConversationId 逻辑会话唯一标识
     * @throws IOException 配置读写失败时抛出
     */
    public void deleteLogicalConversationRecord(String logicalConversationId) throws IOException {
        String normalizedConversationId = normalizeConversationMetadataId(logicalConversationId);
        if (normalizedConversationId == null) {
            return;
        }
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonObject logicalConversations = ensureCodexLogicalConversationsObject(codex);
        logicalConversations.remove(normalizedConversationId);
        writeConfig(config);
    }

    /**
     * 保存运行时分段元数据。
     * 该索引用于表达逻辑会话主干下的物理分段列表，后续历史聚合和上下文迁移都依赖它恢复顺序与父子关系。
     *
     * @param record 待保存的分段记录；传入 null 或无效记录时会忽略
     * @throws IOException 配置读写失败时抛出
     */
    public void saveConversationSegmentRecord(ConversationSegmentRecord record) throws IOException {
        if (record == null || !record.isMeaningful()) {
            return;
        }
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonObject conversationSegments = ensureCodexConversationSegmentsObject(codex);
        conversationSegments.add(record.getSessionId(), record.toJson());
        writeConfig(config);
    }

    /**
     * 读取指定物理分段元数据。
     *
     * @param sessionId 物理分段 sessionId/threadId
     * @return 已保存的分段记录；不存在时返回 null
     * @throws IOException 配置读取失败时抛出
     */
    public ConversationSegmentRecord getConversationSegmentRecord(String sessionId) throws IOException {
        String normalizedSessionId = normalizeSessionBindingSessionId(sessionId);
        if (normalizedSessionId == null) {
            return null;
        }
        JsonObject config = readConfig();
        if (!config.has("codex") || !config.get("codex").isJsonObject()) {
            return null;
        }
        JsonObject codex = config.getAsJsonObject("codex");
        if (!codex.has(CODEX_CONVERSATION_SEGMENTS_KEY) || !codex.get(CODEX_CONVERSATION_SEGMENTS_KEY).isJsonObject()) {
            return null;
        }
        JsonObject conversationSegments = codex.getAsJsonObject(CODEX_CONVERSATION_SEGMENTS_KEY);
        if (!conversationSegments.has(normalizedSessionId)
                || !conversationSegments.get(normalizedSessionId).isJsonObject()) {
            return null;
        }
        return ConversationSegmentRecord.fromJson(conversationSegments.getAsJsonObject(normalizedSessionId));
    }

    /**
     * 列出指定逻辑会话下的全部分段记录，并按分段序号稳定排序。
     * 之所以在服务层完成排序，是为了避免上层历史聚合和恢复链路在多个入口重复实现顺序规则。
     *
     * @param logicalConversationId 逻辑会话唯一标识
     * @return 该逻辑会话下的分段列表；不存在时返回空列表
     * @throws IOException 配置读取失败时抛出
     */
    public List<ConversationSegmentRecord> listConversationSegments(String logicalConversationId) throws IOException {
        String normalizedConversationId = normalizeConversationMetadataId(logicalConversationId);
        List<ConversationSegmentRecord> result = new ArrayList<>();
        if (normalizedConversationId == null) {
            return result;
        }
        JsonObject config = readConfig();
        if (!config.has("codex") || !config.get("codex").isJsonObject()) {
            return result;
        }
        JsonObject codex = config.getAsJsonObject("codex");
        if (!codex.has(CODEX_CONVERSATION_SEGMENTS_KEY) || !codex.get(CODEX_CONVERSATION_SEGMENTS_KEY).isJsonObject()) {
            return result;
        }
        JsonObject conversationSegments = codex.getAsJsonObject(CODEX_CONVERSATION_SEGMENTS_KEY);
        for (Map.Entry<String, JsonElement> entry : conversationSegments.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            ConversationSegmentRecord record = ConversationSegmentRecord.fromJson(entry.getValue().getAsJsonObject());
            if (normalizedConversationId.equals(record.getLogicalConversationId())) {
                result.add(record);
            }
        }
        result.sort((left, right) -> Integer.compare(left.getSegmentIndex(), right.getSegmentIndex()));
        return result;
    }

    /**
     * 删除指定分段元数据。
     *
     * @param sessionId 物理分段 sessionId/threadId
     * @throws IOException 配置读写失败时抛出
     */
    public void deleteConversationSegmentRecord(String sessionId) throws IOException {
        String normalizedSessionId = normalizeSessionBindingSessionId(sessionId);
        if (normalizedSessionId == null) {
            return;
        }
        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonObject conversationSegments = ensureCodexConversationSegmentsObject(codex);
        conversationSegments.remove(normalizedSessionId);
        writeConfig(config);
    }

    /**
     * 级联删除整条逻辑会话及其全部分段索引与会话绑定。
     * 该入口服务于“删除一条历史会话”升级为逻辑会话语义后的清理链路，避免只删除最新分段而残留悬空索引。
     *
     * @param logicalConversationId 目标逻辑会话 id
     * @throws IOException 配置读写失败时抛出
     */
    public void deleteLogicalConversationCascade(String logicalConversationId) throws IOException {
        String normalizedConversationId = normalizeConversationMetadataId(logicalConversationId);
        if (normalizedConversationId == null) {
            return;
        }

        JsonObject config = readConfig();
        JsonObject codex = ensureCodexConfigObject(config);
        JsonObject logicalConversations = ensureCodexLogicalConversationsObject(codex);
        JsonObject conversationSegments = ensureCodexConversationSegmentsObject(codex);
        JsonObject sessionBindings = ensureCodexSessionBindingsObject(codex);

        List<String> sessionIdsToDelete = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : conversationSegments.entrySet()) {
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                continue;
            }
            ConversationSegmentRecord record = ConversationSegmentRecord.fromJson(entry.getValue().getAsJsonObject());
            if (normalizedConversationId.equals(record.getLogicalConversationId())
                    && record.isMeaningful()) {
                sessionIdsToDelete.add(record.getSessionId());
            }
        }

        for (String sessionId : sessionIdsToDelete) {
            conversationSegments.remove(sessionId);
            sessionBindings.remove(sessionId);
        }
        logicalConversations.remove(normalizedConversationId);
        writeConfig(config);
    }

    /**
     * 更新逻辑会话层的标题与收藏元数据。
     * 该方法保留 root/latest/segmentCount 等结构化字段，仅覆写当前调用明确指定的聚合元信息。
     *
     * @param logicalConversationId 目标逻辑会话 id
     * @param title 新标题；传入 null 表示保持原值
     * @param favorited 新收藏状态；传入 null 表示保持原值
     * @param favoritedAt 收藏时间；仅在 favorited 非 null 时生效
     * @throws IOException 配置读写失败时抛出
     */
    public void updateLogicalConversationMetadata(
            String logicalConversationId,
            String title,
            Boolean favorited,
            Long favoritedAt
    ) throws IOException {
        LogicalConversationRecord existingRecord = getLogicalConversationRecord(logicalConversationId);
        if (existingRecord == null || !existingRecord.isMeaningful()) {
            return;
        }

        boolean nextFavorited = favorited != null ? favorited : existingRecord.isFavorited();
        long nextFavoritedAt;
        if (favorited != null) {
            nextFavoritedAt = nextFavorited
                    ? Math.max(0L, favoritedAt != null ? favoritedAt : System.currentTimeMillis())
                    : 0L;
        } else {
            nextFavoritedAt = existingRecord.getFavoritedAt();
        }

        saveLogicalConversationRecord(new LogicalConversationRecord(
                existingRecord.getLogicalConversationId(),
                existingRecord.getRootSessionId(),
                existingRecord.getLatestSessionId(),
                title != null ? title : existingRecord.getTitle(),
                existingRecord.getRuntimeFamily(),
                existingRecord.getProvider(),
                existingRecord.getLastModel(),
                existingRecord.getSegmentCount(),
                existingRecord.getCreatedAt(),
                System.currentTimeMillis(),
                nextFavorited,
                nextFavoritedAt
        ));
    }

    /**
     * 规范化 Codex 会话绑定使用的 sessionId。
     *
     * @param sessionId 原始 sessionId
     * @return 去空白后的 sessionId；为空时返回 null
     */
    private String normalizeSessionBindingSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        return sessionId.trim();
    }

    /**
     * 规范化逻辑会话相关元数据使用的标识。
     * 该方法与 sessionId 规范化规则保持一致，统一收口空串与首尾空白，避免产生重复 key。
     *
     * @param metadataId 原始逻辑会话标识
     * @return 去空白后的标识；为空时返回 null
     */
    private String normalizeConversationMetadataId(String metadataId) {
        if (metadataId == null || metadataId.trim().isEmpty()) {
            return null;
        }
        return metadataId.trim();
    }

    /**
     * 确保 codex 根配置对象存在。
     * 对旧配置还会顺手补齐 modelDisplay 空对象，避免新的展示配置接口读到缺失节点。
     *
     * @param config 整体配置对象
     * @return 可写入的 codex 配置对象
     */
    private JsonObject ensureCodexConfigObject(JsonObject config) {
        if (config.has("codex") && config.get("codex").isJsonObject()) {
            JsonObject codex = config.getAsJsonObject("codex");
            ensureCodexModelDisplayObject(codex);
            return codex;
        }
        JsonObject codex = new JsonObject();
        codex.addProperty("current", "");
        codex.add("providers", new JsonObject());
        codex.add(CODEX_MODEL_DISPLAY_KEY, new JsonObject());
        config.add("codex", codex);
        return codex;
    }

    /**
     * 确保 codex.modelDisplay 为对象节点。
     * 旧配置可能完全缺失该字段，或被外部手工改成了错误类型；这里统一兜底为空对象，方便后续安全写入。
     *
     * @param codex codex 根配置对象
     * @return 可直接写入的 modelDisplay 对象
     */
    private JsonObject ensureCodexModelDisplayObject(JsonObject codex) {
        if (codex.has(CODEX_MODEL_DISPLAY_KEY) && codex.get(CODEX_MODEL_DISPLAY_KEY).isJsonObject()) {
            return codex.getAsJsonObject(CODEX_MODEL_DISPLAY_KEY);
        }
        JsonObject modelDisplay = new JsonObject();
        codex.add(CODEX_MODEL_DISPLAY_KEY, modelDisplay);
        return modelDisplay;
    }

    /**
     * 从当前 codex.providers[*].models 收集模型目录。
     * 该目录是后端返回给前端的等价 schema，字段名与前端类型保持一致，便于直接复用 `providerId::modelId`
     * 的展示与筛选逻辑。
     *
     * @param codex codex 根配置对象
     * @return 发现到的模型目录数组
     */
    private JsonArray collectCodexModelDisplayCatalog(JsonObject codex) {
        JsonArray catalog = new JsonArray();
        appendCodexCliLoginCatalogItems(catalog, codex);
        appendManagedProviderCatalogItems(catalog, codex);
        appendLocalConfigCatalogItems(catalog, codex);
        return catalog;
    }

    /**
     * 构造单个模型目录项。
     * visible 初始值先按默认 true 写入，最终状态由可见性配置统一覆盖，避免目录生成与配置应用交织在一起。
     *
     * @param providerId provider 标识
     * @param providerName provider 展示名
     * @param model 模型原始配置节点
     * @return 前端约定的 catalog 项
     */
    private JsonObject createCodexModelCatalogItem(
            String providerId,
            String providerName,
            JsonObject model,
            String source,
            boolean runnable
    ) {
        String modelId = normalizeString(getOptionalString(model, "id"), "");
        String label = normalizeString(getOptionalString(model, "label"), modelId);

        JsonObject item = new JsonObject();
        item.addProperty("key", buildCodexModelDisplayKey(providerId, modelId));
        item.addProperty("providerId", providerId);
        item.addProperty("providerName", providerName);
        item.addProperty("modelId", modelId);
        item.addProperty("label", label);
        item.addProperty(CODEX_MODEL_SOURCE_KEY, source);
        item.addProperty(CODEX_MODEL_RUNNABLE_KEY, runnable);
        item.addProperty(CODEX_MODEL_DISPLAY_VISIBLE_KEY, true);

        if (model.has(CODEX_MODEL_DESCRIPTION_KEY) && !model.get(CODEX_MODEL_DESCRIPTION_KEY).isJsonNull()) {
            item.add(CODEX_MODEL_DESCRIPTION_KEY, model.get(CODEX_MODEL_DESCRIPTION_KEY).deepCopy());
        }
        if (model.has(CODEX_MODEL_REASONING_EFFORT_KEY) && !model.get(CODEX_MODEL_REASONING_EFFORT_KEY).isJsonNull()) {
            item.add(CODEX_MODEL_REASONING_EFFORT_KEY, model.get(CODEX_MODEL_REASONING_EFFORT_KEY).deepCopy());
        }
        return item;
    }

    /**
     * 追加 CLI Login 默认模型目录项。
     * 这些模型来自当前产品内建的 Codex 默认模型集合，用于让聊天区和设置页在未选中 managed provider 时，
     * 仍能稳定展示 GPT 默认模型，并通过 runnable 区分当前是否已授权读取本地 `~/.codex` 配置。
     *
     * @param catalog 待写入的目录数组
     * @param codex codex 根配置对象
     */
    private void appendCodexCliLoginCatalogItems(JsonArray catalog, JsonObject codex) {
        boolean cliLoginAuthorized = codex.has("localConfigAuthorized")
                && !codex.get("localConfigAuthorized").isJsonNull()
                && codex.get("localConfigAuthorized").getAsBoolean();
        String providerId = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID;
        String providerName = ClaudeCodeGuiBundle.message("provider.codexCliLogin.name");
        for (JsonElement modelElement : buildBuiltinCodexCliModels()) {
            if (modelElement == null || !modelElement.isJsonObject()) {
                continue;
            }
            catalog.add(createCodexModelCatalogItem(
                    providerId,
                    providerName,
                    modelElement.getAsJsonObject(),
                    "codex_cli_login",
                    cliLoginAuthorized
            ));
        }
    }

    /**
     * 追加 managed provider 声明的模型目录项。
     *
     * @param catalog 待写入的目录数组
     * @param codex codex 根配置对象
     */
    private void appendManagedProviderCatalogItems(JsonArray catalog, JsonObject codex) {
        if (!codex.has("providers") || !codex.get("providers").isJsonObject()) {
            return;
        }

        JsonObject providers = codex.getAsJsonObject("providers");
        for (Map.Entry<String, JsonElement> providerEntry : providers.entrySet()) {
            String providerId = normalizeString(providerEntry.getKey(), "");
            if (providerId.isEmpty() || !providerEntry.getValue().isJsonObject()) {
                continue;
            }

            JsonObject provider = providerEntry.getValue().getAsJsonObject();
            JsonArray models = readProviderModels(provider);
            if (models.size() == 0) {
                continue;
            }

            String providerName = normalizeString(getOptionalString(provider, "name"), providerId);
            for (JsonElement modelElement : models) {
                if (modelElement == null || modelElement.isJsonNull() || !modelElement.isJsonObject()) {
                    continue;
                }

                JsonObject model = modelElement.getAsJsonObject();
                String modelId = normalizeString(getOptionalString(model, "id"), "");
                if (modelId.isEmpty()) {
                    continue;
                }

                catalog.add(createCodexModelCatalogItem(
                        providerId,
                        providerName,
                        model,
                        "managed_provider",
                        true
                ));
            }
        }
    }

    /**
     * 追加本地 `~/.codex/config.toml` 中当前生效、但未被其他来源覆盖的模型兜底项。
     * 该兜底项只解决“当前模型无法显示”的问题，不扩展为未知 provider 的完整目录。
     *
     * @param catalog 待写入的目录数组
     * @param codex codex 根配置对象
     */
    private void appendLocalConfigCatalogItems(JsonArray catalog, JsonObject codex) {
        boolean cliLoginAuthorized = codex.has("localConfigAuthorized")
                && !codex.get("localConfigAuthorized").isJsonNull()
                && codex.get("localConfigAuthorized").getAsBoolean();
        if (!cliLoginAuthorized) {
            return;
        }

        JsonObject localState;
        try {
            localState = codexSettingsManager.getCurrentCodexModelState();
        } catch (IOException e) {
            LOG.warn("[CodemossSettings] Failed to read local Codex model state for catalog fallback: " + e.getMessage());
            return;
        }

        String localModelId = normalizeString(getOptionalString(localState, "model"), "");
        if (localModelId.isEmpty()) {
            return;
        }

        String providerId = CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID;
        String compositeKey = buildCodexModelDisplayKey(providerId, localModelId);
        if (catalogContainsKey(catalog, compositeKey)) {
            return;
        }

        JsonObject model = new JsonObject();
        model.addProperty("id", localModelId);
        model.addProperty("label", localModelId);
        String reasoningEffort = normalizeString(getOptionalString(localState, "reasoningEffort"), "");
        if (!reasoningEffort.isEmpty()) {
            model.addProperty(CODEX_MODEL_REASONING_EFFORT_KEY, reasoningEffort);
        }

        catalog.add(createCodexModelCatalogItem(
                providerId,
                ClaudeCodeGuiBundle.message("provider.codexCliLogin.name"),
                model,
                "local_config",
                true
        ));
    }

    /**
     * 构造内建的 Codex CLI 默认模型节点。
     *
     * @return 内建模型数组
     */
    private JsonArray buildBuiltinCodexCliModels() {
        JsonArray models = new JsonArray();
        models.add(createBuiltinCodexModel("gpt-5.5", "gpt-5.5",
                "Frontier model for complex coding, research, and real-world work.", "medium"));
        models.add(createBuiltinCodexModel("gpt-5.4", "gpt-5.4",
                "Strong model for everyday coding.", "medium"));
        models.add(createBuiltinCodexModel("gpt-5.2-codex", "gpt-5.2-codex",
                "Frontier agentic coding model.", "medium"));
        models.add(createBuiltinCodexModel("gpt-5.1-codex-max", "gpt-5.1-codex-max",
                "Codex-optimized flagship for deep and fast reasoning.", "high"));
        models.add(createBuiltinCodexModel("gpt-5.4-mini", "gpt-5.4-mini",
                "Small, fast, and cost-efficient model for simpler coding tasks.", "medium"));
        models.add(createBuiltinCodexModel("gpt-5.3-codex", "gpt-5.3-codex",
                "Coding-optimized model.", "medium"));
        models.add(createBuiltinCodexModel("gpt-5.3-codex-spark", "gpt-5.3-codex-spark",
                "Ultra-fast coding model.", "medium"));
        models.add(createBuiltinCodexModel("gpt-5.2", "gpt-5.2",
                "Optimized for professional work and long-running agents.", "medium"));
        models.add(createBuiltinCodexModel("gpt-5.1-codex-mini", "gpt-5.1-codex-mini",
                "Optimized for Codex. Cheaper, faster, but less capable.", "medium"));
        return models;
    }

    /**
     * 判断指定 provider 是否声明了指定模型。
     * 该校验用于 fresh new tab 解析最近模型与 CLI 模型时兜底过滤失效配置，
     * 避免 UI 命中了已删除 provider/model 后进入不一致状态。
     *
     * @param provider Codex provider 配置
     * @param modelId 待校验的模型 id
     * @return 命中时返回 true
     */
    private boolean providerContainsModel(JsonObject provider, String modelId) {
        return findProviderModelById(provider, modelId) != null;
    }

    /**
     * 在指定 provider 的模型数组中定位目标模型节点。
     * 同时兼容 `models` 与历史 `customModels`，供新建 Tab 默认值解析读取模型元数据。
     *
     * @param provider Codex provider 配置
     * @param modelId 待定位的模型 id
     * @return 命中的模型对象；未命中时返回 null
     */
    private JsonObject findProviderModelById(JsonObject provider, String modelId) {
        String normalizedModelId = normalizeString(modelId, "");
        if (provider == null || normalizedModelId.isEmpty()) {
            return null;
        }
        JsonArray models = readProviderModels(provider);
        for (JsonElement modelElement : models) {
            if (modelElement == null || modelElement.isJsonNull() || !modelElement.isJsonObject()) {
                continue;
            }
            JsonObject model = modelElement.getAsJsonObject();
            if (normalizedModelId.equals(normalizeString(getOptionalString(model, "id"), ""))) {
                return model;
            }
        }
        return null;
    }

    /**
     * 在内建 CLI Login 模型目录中定位目标模型节点。
     * CLI Login provider 是虚拟 provider，没有持久化 `models` 字段，
     * 因此 fresh new tab 回退到内建模型元数据时需要单独从内建目录查询。
     *
     * @param modelId 待定位的模型 id
     * @return 命中的模型对象；未命中时返回 null
     */
    private JsonObject findBuiltinCodexCliModelById(String modelId) {
        String normalizedModelId = normalizeString(modelId, "");
        if (normalizedModelId.isEmpty()) {
            return null;
        }
        JsonArray builtinModels = buildBuiltinCodexCliModels();
        for (JsonElement modelElement : builtinModels) {
            if (modelElement == null || modelElement.isJsonNull() || !modelElement.isJsonObject()) {
                continue;
            }
            JsonObject model = modelElement.getAsJsonObject();
            if (normalizedModelId.equals(normalizeString(getOptionalString(model, "id"), ""))) {
                return model;
            }
        }
        return null;
    }

    /**
     * 读取 provider 的首个可运行模型 id。
     * 新建 Tab 默认值在没有最近模型和 CLI 模型时需要回退到 provider 默认模型，
     * 因此单独抽出该逻辑以便复用并保持回退策略一致。
     *
     * @param provider Codex provider 配置
     * @return 首个可用模型 id；不存在时返回空字符串
     */
    private String readFirstConfiguredModelId(JsonObject provider) {
        JsonObject firstModel = provider == null ? null : findFirstProviderModel(provider);
        return firstModel == null ? "" : normalizeString(getOptionalString(firstModel, "id"), "");
    }

    /**
     * 读取 provider 的首个模型节点。
     *
     * @param provider Codex provider 配置
     * @return 首个模型对象；不存在时返回 null
     */
    private JsonObject findFirstProviderModel(JsonObject provider) {
        if (provider == null) {
            return null;
        }
        JsonArray models = readProviderModels(provider);
        if (models.size() == 0 || !models.get(0).isJsonObject()) {
            return null;
        }
        return models.get(0).getAsJsonObject();
    }

    /**
     * 解析一个仍然可用于新建 Tab 初始化的 provider。
     * 对 CLI Login 虚拟 provider 做单独放行，对普通 provider 则按当前配置只读查询。
     *
     * @param providerId 目标 provider id
     * @return 可用 provider；不可用时返回 null
     * @throws IOException 配置读取失败时抛出
     */
    private JsonObject resolveRunnableCodexProvider(String providerId) throws IOException {
        String normalizedProviderId = normalizeString(providerId, "");
        if (normalizedProviderId.isEmpty()) {
            return null;
        }
        if (CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID.equals(normalizedProviderId)) {
            return getCodexProviderById(normalizedProviderId);
        }
        return getCodexProviderById(normalizedProviderId);
    }

    /**
     * 构造单个内建 Codex 模型节点。
     *
     * @param modelId 模型 id
     * @param label 模型显示名
     * @param description 模型说明
     * @param reasoningEffort 默认推理强度，可为空
     * @return 可直接复用的模型节点
     */
    private JsonObject createBuiltinCodexModel(
            String modelId,
            String label,
            String description,
            String reasoningEffort
    ) {
        JsonObject model = new JsonObject();
        model.addProperty("id", modelId);
        model.addProperty("label", label);
        if (description != null && !description.trim().isEmpty()) {
            model.addProperty(CODEX_MODEL_DESCRIPTION_KEY, description.trim());
        }
        if (reasoningEffort != null && !reasoningEffort.trim().isEmpty()) {
            model.addProperty(CODEX_MODEL_REASONING_EFFORT_KEY, reasoningEffort.trim());
        }
        return model;
    }

    /**
     * 统一读取 provider 的模型数组。
     * 优先读取当前 schema 的 `models`，并兼容历史 `customModels`。
     *
     * @param provider provider 配置
     * @return 模型数组；不存在时返回空数组
     */
    private JsonArray readProviderModels(JsonObject provider) {
        if (provider.has("models") && provider.get("models").isJsonArray()) {
            return provider.getAsJsonArray("models");
        }
        if (provider.has("customModels") && provider.get("customModels").isJsonArray()) {
            return provider.getAsJsonArray("customModels");
        }
        return new JsonArray();
    }

    /**
     * 判断目录中是否已经存在指定复合 key。
     *
     * @param catalog 当前目录数组
     * @param key 待匹配的复合 key
     * @return 存在时返回 true
     */
    private boolean catalogContainsKey(JsonArray catalog, String key) {
        for (JsonElement element : catalog) {
            if (element == null || element.isJsonNull() || !element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (key.equals(normalizeString(getOptionalString(item, "key"), ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成与前端一致的模型展示复合 key。
     * Java 端当前只负责稳定生成，不承担解析职责，因此直接按 `providerId::modelId` 拼接即可。
     *
     * @param providerId provider 标识
     * @param modelId 模型标识
     * @return 复合模型 key
     */
    private String buildCodexModelDisplayKey(String providerId, String modelId) {
        return providerId + "::" + modelId;
    }

    /**
     * 归一化并可选补齐模型可见性配置。
     * 读取场景下会按已发现模型补全缺失项并默认 visible=true；保存场景下只保留合法输入，避免把脏数据直接落盘。
     *
     * @param source 原始可见性配置，允许为 null
     * @param catalog 已发现模型目录；仅读取并补齐缺失项时使用
     * @param fillMissingCatalogEntries 是否按目录补齐缺失模型
     * @return 清洗后的可见性配置
     */
    private JsonObject normalizeCodexModelDisplayVisibility(
            JsonObject source,
            JsonArray catalog,
            boolean fillMissingCatalogEntries
    ) {
        JsonObject normalized = new JsonObject();
        if (source != null) {
            for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                String key = normalizeString(entry.getKey(), "");
                if (key.isEmpty() || entry.getValue() == null || !entry.getValue().isJsonObject()) {
                    continue;
                }

                JsonObject rawVisibility = entry.getValue().getAsJsonObject();
                if (!rawVisibility.has(CODEX_MODEL_DISPLAY_VISIBLE_KEY)
                        || rawVisibility.get(CODEX_MODEL_DISPLAY_VISIBLE_KEY).isJsonNull()) {
                    continue;
                }
                JsonElement rawVisibleValue = rawVisibility.get(CODEX_MODEL_DISPLAY_VISIBLE_KEY);
                if (!rawVisibleValue.isJsonPrimitive() || !rawVisibleValue.getAsJsonPrimitive().isBoolean()) {
                    continue;
                }

                JsonObject visibility = new JsonObject();
                visibility.addProperty(
                        CODEX_MODEL_DISPLAY_VISIBLE_KEY,
                        rawVisibleValue.getAsBoolean()
                );
                normalized.add(key, visibility);
            }
        }

        if (fillMissingCatalogEntries && catalog != null) {
            // 缺省迁移规则：凡是当前能发现到的模型，如果历史配置里还没有记录，就默认补成可见并回写。
            for (JsonElement catalogElement : catalog) {
                if (catalogElement == null || catalogElement.isJsonNull() || !catalogElement.isJsonObject()) {
                    continue;
                }
                JsonObject catalogItem = catalogElement.getAsJsonObject();
                String key = normalizeString(getOptionalString(catalogItem, "key"), "");
                if (key.isEmpty() || normalized.has(key)) {
                    continue;
                }
                JsonObject visibility = new JsonObject();
                visibility.addProperty(CODEX_MODEL_DISPLAY_VISIBLE_KEY, true);
                normalized.add(key, visibility);
            }
        }
        return normalized;
    }

    /**
     * 把可见性配置回填到 catalog。
     * catalog 是前端直接消费的列表视图，因此需要把最终 visible 状态同步到每个目录项，避免前端自行二次推导。
     *
     * @param catalog 模型目录数组
     * @param visibility 归一化后的可见性配置
     */
    private void applyCodexModelDisplayVisibility(JsonArray catalog, JsonObject visibility) {
        for (JsonElement catalogElement : catalog) {
            if (catalogElement == null || catalogElement.isJsonNull() || !catalogElement.isJsonObject()) {
                continue;
            }
            JsonObject catalogItem = catalogElement.getAsJsonObject();
            String key = normalizeString(getOptionalString(catalogItem, "key"), "");
            boolean visible = true;
            if (!key.isEmpty()
                    && visibility.has(key)
                    && visibility.get(key).isJsonObject()
                    && visibility.getAsJsonObject(key).has(CODEX_MODEL_DISPLAY_VISIBLE_KEY)
                    && !visibility.getAsJsonObject(key).get(CODEX_MODEL_DISPLAY_VISIBLE_KEY).isJsonNull()) {
                JsonElement rawVisibleValue = visibility.getAsJsonObject(key).get(CODEX_MODEL_DISPLAY_VISIBLE_KEY);
                if (rawVisibleValue.isJsonPrimitive() && rawVisibleValue.getAsJsonPrimitive().isBoolean()) {
                    visible = rawVisibleValue.getAsBoolean();
                }
            }
            catalogItem.addProperty(CODEX_MODEL_DISPLAY_VISIBLE_KEY, visible);
        }
    }

    /**
     * 确保 codex.sessionBindings 对象存在。
     *
     * @param codex codex 根配置对象
     * @return 可直接写入的 sessionBindings 对象
     */
    private JsonObject ensureCodexSessionBindingsObject(JsonObject codex) {
        if (codex.has("sessionBindings") && codex.get("sessionBindings").isJsonObject()) {
            return codex.getAsJsonObject("sessionBindings");
        }
        JsonObject sessionBindings = new JsonObject();
        codex.add("sessionBindings", sessionBindings);
        return sessionBindings;
    }

    /**
     * 确保 codex.logicalConversations 为对象节点。
     * 该节点用于保存逻辑会话主记录，旧配置缺失该字段时这里统一补齐为空对象。
     *
     * @param codex codex 根配置对象
     * @return 可直接写入的 logicalConversations 对象
     */
    private JsonObject ensureCodexLogicalConversationsObject(JsonObject codex) {
        if (codex.has(CODEX_LOGICAL_CONVERSATIONS_KEY) && codex.get(CODEX_LOGICAL_CONVERSATIONS_KEY).isJsonObject()) {
            return codex.getAsJsonObject(CODEX_LOGICAL_CONVERSATIONS_KEY);
        }
        JsonObject logicalConversations = new JsonObject();
        codex.add(CODEX_LOGICAL_CONVERSATIONS_KEY, logicalConversations);
        return logicalConversations;
    }

    /**
     * 确保 codex.conversationSegments 为对象节点。
     * 该节点用于保存逻辑会话主干下的物理分段索引，缺失时统一补齐为空对象。
     *
     * @param codex codex 根配置对象
     * @return 可直接写入的 conversationSegments 对象
     */
    private JsonObject ensureCodexConversationSegmentsObject(JsonObject codex) {
        if (codex.has(CODEX_CONVERSATION_SEGMENTS_KEY) && codex.get(CODEX_CONVERSATION_SEGMENTS_KEY).isJsonObject()) {
            return codex.getAsJsonObject(CODEX_CONVERSATION_SEGMENTS_KEY);
        }
        JsonObject conversationSegments = new JsonObject();
        codex.add(CODEX_CONVERSATION_SEGMENTS_KEY, conversationSegments);
        return conversationSegments;
    }

    private JsonObject ensureRemoteCollabConfig(JsonObject config) {
        if (migrateRemoteCollabConfig(config)) {
            // migration already updated the in-memory tree
        }
        return config.getAsJsonObject(REMOTE_COLLAB_KEY);
    }

    private boolean migrateRemoteCollabConfig(JsonObject config) {
        JsonObject source = config.has(REMOTE_COLLAB_KEY) && config.get(REMOTE_COLLAB_KEY).isJsonObject()
            ? config.getAsJsonObject(REMOTE_COLLAB_KEY)
            : null;
        JsonObject normalized = normalizeRemoteCollabConfig(source);
        if (!config.has(REMOTE_COLLAB_KEY) || !normalized.toString().equals(config.getAsJsonObject(REMOTE_COLLAB_KEY).toString())) {
            config.add(REMOTE_COLLAB_KEY, normalized);
            return true;
        }
        return false;
    }

    private JsonObject normalizeRemoteCollabConfig(JsonObject source) {
        JsonObject normalized = createDefaultRemoteCollabConfig();
        if (source == null) {
            return normalized;
        }

        if (source.has(ENABLED_KEY) && !source.get(ENABLED_KEY).isJsonNull()) {
            normalized.addProperty(ENABLED_KEY, source.get(ENABLED_KEY).getAsBoolean());
        }

        JsonObject providers = createDefaultProviderConfigs();
        JsonObject providerSource = getOptionalObject(source, PROVIDERS_KEY);
        JsonObject telegramSource = getOptionalObject(providerSource, TELEGRAM_KEY);
        if (telegramSource == null) {
            telegramSource = getOptionalObject(source, TELEGRAM_KEY);
        }
        JsonObject gotifyWebSource = getOptionalObject(providerSource, GOTIFY_WEB_KEY);
        if (gotifyWebSource == null) {
            gotifyWebSource = getOptionalObject(source, GOTIFY_WEB_KEY);
        }
        JsonObject feishuSource = getOptionalObject(providerSource, FEISHU_KEY);
        if (feishuSource == null) {
            feishuSource = getOptionalObject(source, FEISHU_KEY);
        }

        providers.add(TELEGRAM_KEY, normalizeTelegramConfig(telegramSource));
        providers.add(GOTIFY_WEB_KEY, normalizeGotifyWebConfig(gotifyWebSource));
        providers.add(FEISHU_KEY, normalizeFeishuConfig(feishuSource));
        mergeUnknownProviderConfigs(providerSource, providers);
        normalized.add(PROVIDERS_KEY, providers);

        JsonObject debugSource = getOptionalObject(source, DEBUG_KEY);
        normalized.add(DEBUG_KEY, normalizeRemoteCollabDebugConfig(debugSource));
        normalized.addProperty(INTERACTIVE_PROVIDER_ID_KEY, normalizeString(getOptionalString(source, INTERACTIVE_PROVIDER_ID_KEY), TELEGRAM_KEY));
        normalized.add(NOTIFY_PROVIDER_IDS_KEY, normalizeNotifyProviderIds(source, normalized.get(INTERACTIVE_PROVIDER_ID_KEY).getAsString()));
        return normalized;
    }

    /**
     * 统一兼容旧版 Telegram 配置和新版 provider 配置。
     * 这里保留 `saveTelegramConfig/getTelegramConfig` 兼容入口，避免 Telegram 迁移到 provider 前影响既有调用链。
     */
    private JsonObject normalizeTelegramConfig(JsonObject source) {
        JsonObject normalized = createDefaultTelegramConfig();
        if (source == null) {
            return normalized;
        }

        copyBooleanProperty(source, normalized, ENABLED_KEY);
        copyStringProperty(source, normalized, "botToken");
        copyStringProperty(source, normalized, "botUsername");
        copyStringProperty(source, normalized, "chatId");
        copyStringProperty(source, normalized, "boundUserId");
        copyStringProperty(source, normalized, "boundUsername");
        copyStringProperty(source, normalized, "bindingToken");
        copyStringProperty(source, normalized, "connectionStatus");
        copyStringProperty(source, normalized, "lastError");
        copyBooleanProperty(source, normalized, "pollingEnabled");
        copyBooleanProperty(source, normalized, "singleActive");
        copyPositiveIntProperty(source, normalized, "pollIntervalSeconds");
        return normalized;
    }

    /**
     * Gotify + Web 方案当前仍未真正接线，但配置模型需要先准备好默认值与迁移规则，
     * 这样后续接入 handler / UI / provider 时无需再次改动配置存储结构。
     */
    private JsonObject normalizeGotifyWebConfig(JsonObject source) {
        JsonObject normalized = createDefaultGotifyWebConfig();
        if (source == null) {
            return normalized;
        }

        copyBooleanProperty(source, normalized, ENABLED_KEY);
        copyStringProperty(source, normalized, "serverUrl");
        copyStringProperty(source, normalized, "apiToken");
        copyStringProperty(source, normalized, "workspaceBaseUrl");
        copyStringProperty(source, normalized, "connectionStatus");
        copyStringProperty(source, normalized, "lastError");
        copyPositiveIntProperty(source, normalized, "resultPollIntervalSeconds");
        return normalized;
    }

    private JsonObject normalizeRemoteCollabDebugConfig(JsonObject source) {
        JsonObject normalized = createDefaultRemoteCollabDebugConfig();
        if (source == null) {
            return normalized;
        }
        copyBooleanProperty(source, normalized, ENABLED_KEY);
        return normalized;
    }

    private JsonObject normalizeProviderConfig(String providerId, JsonObject providerConfig) {
        if (TELEGRAM_KEY.equals(providerId)) {
            return normalizeTelegramConfig(providerConfig);
        }
        if (GOTIFY_WEB_KEY.equals(providerId)) {
            return normalizeGotifyWebConfig(providerConfig);
        }
        if (FEISHU_KEY.equals(providerId)) {
            return normalizeFeishuConfig(providerConfig);
        }
        return providerConfig == null ? new JsonObject() : providerConfig.deepCopy();
    }

    private void copyStringProperty(JsonObject source, JsonObject target, String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return;
        }
        String value = source.get(key).getAsString();
        target.addProperty(key, value == null ? "" : value);
    }

    private void copyBooleanProperty(JsonObject source, JsonObject target, String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return;
        }
        target.addProperty(key, source.get(key).getAsBoolean());
    }

    private void copyPositiveIntProperty(JsonObject source, JsonObject target, String key) {
        if (!source.has(key) || source.get(key).isJsonNull()) {
            return;
        }
        target.addProperty(key, Math.max(1, source.get(key).getAsInt()));
    }

    private JsonObject createDefaultRemoteCollabConfig() {
        JsonObject remoteCollab = new JsonObject();
        remoteCollab.addProperty(ENABLED_KEY, false);
        remoteCollab.add(DEBUG_KEY, createDefaultRemoteCollabDebugConfig());
        remoteCollab.addProperty(INTERACTIVE_PROVIDER_ID_KEY, TELEGRAM_KEY);
        JsonArray notifyProviderIds = new JsonArray();
        notifyProviderIds.add(TELEGRAM_KEY);
        remoteCollab.add(NOTIFY_PROVIDER_IDS_KEY, notifyProviderIds);
        remoteCollab.add(PROVIDERS_KEY, createDefaultProviderConfigs());
        return remoteCollab;
    }

    private JsonObject createDefaultRemoteCollabDebugConfig() {
        JsonObject debug = new JsonObject();
        debug.addProperty(ENABLED_KEY, false);
        return debug;
    }

    private JsonObject createDefaultProviderConfigs() {
        JsonObject providers = new JsonObject();
        providers.add(TELEGRAM_KEY, createDefaultTelegramConfig());
        providers.add(GOTIFY_WEB_KEY, createDefaultGotifyWebConfig());
        providers.add(FEISHU_KEY, createDefaultFeishuConfig());
        return providers;
    }

    private JsonObject createDefaultTelegramConfig() {
        JsonObject telegram = new JsonObject();
        telegram.addProperty(ENABLED_KEY, true);
        telegram.addProperty("botToken", "");
        telegram.addProperty("botUsername", "");
        telegram.addProperty("chatId", "");
        telegram.addProperty("boundUserId", "");
        telegram.addProperty("boundUsername", "");
        telegram.addProperty("bindingToken", "");
        telegram.addProperty("pollingEnabled", true);
        telegram.addProperty("pollIntervalSeconds", 1);
        telegram.addProperty("singleActive", true);
        telegram.addProperty("connectionStatus", "disabled");
        telegram.addProperty("lastError", "");
        return telegram;
    }

    private JsonObject createDefaultGotifyWebConfig() {
        JsonObject gotifyWeb = new JsonObject();
        gotifyWeb.addProperty(ENABLED_KEY, false);
        gotifyWeb.addProperty("serverUrl", "");
        gotifyWeb.addProperty("apiToken", "");
        gotifyWeb.addProperty("workspaceBaseUrl", "");
        gotifyWeb.addProperty("resultPollIntervalSeconds", 3);
        gotifyWeb.addProperty("connectionStatus", "disabled");
        gotifyWeb.addProperty("lastError", "");
        return gotifyWeb;
    }

    /**
     * Feishu 第一阶段配置模型。
     * 当前先覆盖 appId/appSecret、绑定标识和基础连接状态，后续事件订阅字段继续沿用这个结构扩展。
     */
    private JsonObject createDefaultFeishuConfig() {
        JsonObject feishu = new JsonObject();
        feishu.addProperty(ENABLED_KEY, false);
        feishu.addProperty("appId", "");
        feishu.addProperty("appSecret", "");
        feishu.addProperty("encryptKey", "");
        feishu.addProperty("verificationToken", "");
        feishu.addProperty("botName", "");
        feishu.addProperty("boundOpenId", "");
        feishu.addProperty("boundChatId", "");
        feishu.addProperty("bindingToken", "");
        feishu.addProperty("bindingTokenExpiresAt", 0L);
        feishu.addProperty("eventMode", "long_poll");
        feishu.addProperty("connectionStatus", "disabled");
        feishu.addProperty("lastError", "");
        return feishu;
    }

    private JsonObject normalizeFeishuConfig(JsonObject source) {
        JsonObject normalized = createDefaultFeishuConfig();
        if (source == null) {
            return normalized;
        }

        copyBooleanProperty(source, normalized, ENABLED_KEY);
        copyStringProperty(source, normalized, "appId");
        copyStringProperty(source, normalized, "appSecret");
        copyStringProperty(source, normalized, "encryptKey");
        copyStringProperty(source, normalized, "verificationToken");
        copyStringProperty(source, normalized, "botName");
        copyStringProperty(source, normalized, "boundOpenId");
        copyStringProperty(source, normalized, "boundChatId");
        copyStringProperty(source, normalized, "bindingToken");
        if (source.has("bindingTokenExpiresAt") && !source.get("bindingTokenExpiresAt").isJsonNull()) {
            normalized.addProperty("bindingTokenExpiresAt", Math.max(0L, source.get("bindingTokenExpiresAt").getAsLong()));
        }
        copyStringProperty(source, normalized, "connectionStatus");
        copyStringProperty(source, normalized, "lastError");
        normalized.addProperty("eventMode", normalizeString(getOptionalString(source, "eventMode"), "long_poll"));
        return normalized;
    }

    /**
     * 保留未知 provider 配置，避免当前阶段的配置升级把后续扩展点或手工写入的实验配置直接丢掉。
     */
    private void mergeUnknownProviderConfigs(JsonObject sourceProviders, JsonObject targetProviders) {
        if (sourceProviders == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : sourceProviders.entrySet()) {
            String providerId = normalizeString(entry.getKey(), "");
            if (providerId.isEmpty() || targetProviders.has(providerId) || !entry.getValue().isJsonObject()) {
                continue;
            }
            targetProviders.add(providerId, entry.getValue().getAsJsonObject().deepCopy());
        }
    }

    private JsonArray normalizeNotifyProviderIds(JsonObject source, String interactiveProviderId) {
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        if (source != null && source.has(NOTIFY_PROVIDER_IDS_KEY) && source.get(NOTIFY_PROVIDER_IDS_KEY).isJsonArray()) {
            for (JsonElement element : source.getAsJsonArray(NOTIFY_PROVIDER_IDS_KEY)) {
                if (element == null || element.isJsonNull()) {
                    continue;
                }
                String providerId = normalizeString(element.getAsString(), "");
                if (!providerId.isEmpty()) {
                    normalizedIds.add(providerId);
                }
            }
        }
        if (normalizedIds.isEmpty()) {
            normalizedIds.add(normalizeString(interactiveProviderId, TELEGRAM_KEY));
        }
        JsonArray notifyProviderIds = new JsonArray();
        for (String providerId : new ArrayList<>(normalizedIds)) {
            notifyProviderIds.add(providerId);
        }
        return notifyProviderIds;
    }

    private JsonObject getOptionalObject(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull() || !source.get(key).isJsonObject()) {
            return null;
        }
        return source.getAsJsonObject(key);
    }

    private String getOptionalString(JsonObject source, String key) {
        if (source == null || !source.has(key) || source.get(key).isJsonNull()) {
            return "";
        }
        return source.get(key).getAsString();
    }

    private String normalizeString(String value, String defaultValue) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? defaultValue : normalized;
    }
}
