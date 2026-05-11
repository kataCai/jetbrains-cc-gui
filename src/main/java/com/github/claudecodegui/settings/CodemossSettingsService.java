package com.github.claudecodegui.settings;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.model.ConflictStrategy;
import com.github.claudecodegui.model.DeleteResult;
import com.github.claudecodegui.model.PromptScope;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

/**
 * Codemoss configuration service (Facade pattern).
 * Delegates specific functionality to specialized managers.
 */
public class CodemossSettingsService {

    private static final Logger LOG = Logger.getInstance(CodemossSettingsService.class);
    private static final int CONFIG_VERSION = 2;
    private static final String CODEX_SANDBOX_MODE_WORKSPACE_WRITE = "workspace-write";
    private static final String CODEX_SANDBOX_MODE_DANGER_FULL_ACCESS = "danger-full-access";
    public static final String CODEX_RUNTIME_ACCESS_INACTIVE = "inactive";
    public static final String CODEX_RUNTIME_ACCESS_MANAGED = "managed";
    public static final String CODEX_RUNTIME_ACCESS_CLI_LOGIN = "cli_login";
    private static final String TASK_REMINDER_KEY = "taskReminder";
    private static final String SOUND_NOTIFICATION_KEY = "soundNotification";
    private static final String REMOTE_COLLAB_KEY = "remoteCollab";
    private static final String DEBUG_KEY = "debug";
    private static final String ENABLED_KEY = "enabled";
    private static final String INTERACTIVE_PROVIDER_ID_KEY = "interactiveProviderId";
    private static final String NOTIFY_PROVIDER_IDS_KEY = "notifyProviderIds";
    private static final String PROVIDERS_KEY = "providers";
    private static final String TELEGRAM_KEY = "telegram";
    private static final String GOTIFY_WEB_KEY = "gotify_web";

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
     * Create default config.
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
        codex.addProperty("localConfigAuthorized", false);
        config.add("codex", codex);

        config.add(TASK_REMINDER_KEY, createDefaultTaskReminderConfig());
        config.add(REMOTE_COLLAB_KEY, createDefaultRemoteCollabConfig());

        return config;
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

    // ==================== Codex Provider Management ====================

    public List<JsonObject> getCodexProviders() throws IOException {
        return codexProviderManager.getCodexProviders();
    }

    public JsonObject getActiveCodexProvider() throws IOException {
        return codexProviderManager.getActiveCodexProvider();
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

        providers.add(TELEGRAM_KEY, normalizeTelegramConfig(telegramSource));
        providers.add(GOTIFY_WEB_KEY, normalizeGotifyWebConfig(gotifyWebSource));
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
