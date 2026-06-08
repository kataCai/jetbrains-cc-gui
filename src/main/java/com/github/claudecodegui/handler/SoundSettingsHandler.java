package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.util.SoundNotificationService;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;

/**
 * 处理设置页与声音/任务提醒配置之间的桥接。
 *
 * <p>当前这个 handler 同时承担两类职责：
 * 1. 维护新的 canonical `taskReminder` 配置结构；
 * 2. 对旧版 `soundNotification` 接口做兼容投影，避免前端迁移期间断链。
 */
public class SoundSettingsHandler {

    private static final Logger LOG = Logger.getInstance(SoundSettingsHandler.class);

    private final HandlerContext context;
    private final CodemossSettingsService settingsService;
    private final TaskReminderDispatcher taskReminderDispatcher;
    private final Gson gson = new Gson();

    public SoundSettingsHandler(HandlerContext context, TaskReminderDispatcher taskReminderDispatcher) {
        this.context = context;
        this.settingsService = context.getSettingsService();
        this.taskReminderDispatcher = taskReminderDispatcher;
    }

    /**
     * 读取完整的 task reminder 配置并回推给前端。
     * 如果读取失败，则回退到一份与前端默认值对齐的兜底配置。
     */
    public void handleGetTaskReminderConfig() {
        try {
            JsonObject taskReminderConfig = settingsService.getTaskReminderConfig();
            // 统一把“规范结构 + 兼容旧声音结构”一起回推给前端，
            // 这样新设置页和旧声音设置入口都能同时拿到一致结果。
            dispatchTaskReminderConfigUpdate(taskReminderConfig);
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to get task reminder config: " + e.getMessage(), e);
            dispatchTaskReminderConfigUpdate(createFallbackTaskReminderConfig());
        }
    }

    /**
     * 保存完整的 task reminder 配置。
     * 支持直接传 taskReminder 对象，也兼容带外层包装字段的 payload。
     */
    public void handleSetTaskReminderConfig(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            JsonObject taskReminder = json;
            if (json != null && json.has("taskReminder") && json.get("taskReminder").isJsonObject()) {
                // 兼容可能存在的包裹层，允许前端以后扩展 payload 而不破坏当前接口。
                taskReminder = json.getAsJsonObject("taskReminder");
            }
            settingsService.setTaskReminderConfig(taskReminder);
            dispatchTaskReminderConfigUpdate(settingsService.getTaskReminderConfig());
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to set task reminder config: " + e.getMessage(), e);
            invokeLaterSafe(() -> {
                context.callJavaScript(
                    "window.showError",
                    context.escapeJs(ClaudeCodeGuiBundle.message("soundSettings.saveTaskReminderFailed", e.getMessage()))
                );
            });
        }
    }

    /**
     * 读取旧版 sound notification 配置接口。
     * 实际数据源已经迁移到 taskReminder.sound，这里只做兼容投影。
     */
    public void handleGetSoundNotificationConfig() {
        try {
            JsonObject taskReminderConfig = settingsService.getTaskReminderConfig();
            // 老接口现在只作为兼容桥接存在：真实数据源已经迁移到 taskReminder.sound。
            dispatchLegacySoundConfigUpdate(taskReminderConfig);
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to get sound notification config: " + e.getMessage(), e);
            dispatchLegacySoundConfigUpdate(createFallbackTaskReminderConfig());
        }
    }

    /**
     * 兼容旧接口：更新声音提醒是否启用。
     * 最终仍然写入 canonical taskReminder.sound.enabled。
     */
    public void handleSetSoundNotificationEnabled(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean enabled = json != null && json.has("enabled") && json.get("enabled").getAsBoolean();

            settingsService.setSoundNotificationEnabled(enabled);

            LOG.info("[SoundSettingsHandler] Set sound notification enabled: " + enabled);

            dispatchSoundConfigUpdate();
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to set sound notification enabled: " + e.getMessage(), e);
            invokeLaterSafe(() -> {
                context.callJavaScript(
                    "window.showError",
                    context.escapeJs(ClaudeCodeGuiBundle.message("soundSettings.saveSoundNotificationFailed", e.getMessage()))
                );
            });
        }
    }

    /**
     * 兼容旧接口：更新“仅在 IDE 未聚焦时播放声音”。
     */
    public void handleSetSoundOnlyWhenUnfocused(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            boolean onlyWhenUnfocused = json != null && json.has("onlyWhenUnfocused") && json.get("onlyWhenUnfocused").getAsBoolean();

            settingsService.setSoundOnlyWhenUnfocused(onlyWhenUnfocused);

            LOG.info("[SoundSettingsHandler] Set sound only when unfocused: " + onlyWhenUnfocused);

            dispatchSoundConfigUpdate();
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to set sound only when unfocused: " + e.getMessage(), e);
            invokeLaterSafe(() -> {
                context.callJavaScript(
                    "window.showError",
                    context.escapeJs(ClaudeCodeGuiBundle.message("soundSettings.saveSoundNotificationFailed", e.getMessage()))
                );
            });
        }
    }

    /**
     * 更新当前选中的提示音 ID。
     */
    public void handleSetSelectedSound(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String soundId = json != null && json.has("soundId") && !json.get("soundId").isJsonNull()
                ? json.get("soundId").getAsString() : "default";

            settingsService.setSelectedSound(soundId);

            dispatchSoundConfigUpdate();
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to set selected sound: " + e.getMessage(), e);
        }
    }

    /**
     * 保存自定义声音文件路径。
     * 这里会先做文件合法性校验，避免把不可播放的路径持久化进配置。
     */
    public void handleSetCustomSoundPath(String content) {
        try {
            JsonObject json = gson.fromJson(content, JsonObject.class);
            String path = json != null && json.has("path") && !json.get("path").isJsonNull()
                ? json.get("path").getAsString() : null;

            // 只有用户真正填写了路径时才做校验；
            // 空路径表示“恢复默认声音”，不应该被视为错误。
            if (path != null && !path.isEmpty()) {
                SoundNotificationService.ValidationResult validation =
                    SoundNotificationService.getInstance().validateSoundFile(path);

                if (!validation.valid()) {
                    final String errorMsg = validation.errorMessage();
                    invokeLaterSafe(() -> {
                        context.callJavaScript(
                            "window.showError",
                            context.escapeJs(ClaudeCodeGuiBundle.message("soundSettings.invalidAudioFile", errorMsg))
                        );
                    });
                    return;
                }
            }

            settingsService.setCustomSoundPath(path);

            LOG.debug("[SoundSettingsHandler] Set custom sound path: " + path);

            dispatchSoundConfigUpdate();
            invokeLaterSafe(() -> {
                context.callJavaScript("window.showSuccessI18n", context.escapeJs("settings.basic.soundNotification.customSoundSaved"));
            });
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to set custom sound path: " + e.getMessage(), e);
            invokeLaterSafe(() -> {
                context.callJavaScript(
                    "window.showError",
                    context.escapeJs(ClaudeCodeGuiBundle.message("soundSettings.saveCustomSoundFailed", e.getMessage()))
                );
            });
        }
    }

    /**
     * 试听当前声音设置。
     * 不会修改配置，只是临时根据 soundId/path 做一次播放。
     */
    public void handleTestSound(String content) {
        try {
            String soundId = "default";
            String path = null;
            if (content != null && !content.isEmpty()) {
                JsonObject json = gson.fromJson(content, JsonObject.class);
                if (json != null && json.has("soundId") && !json.get("soundId").isJsonNull()) {
                    soundId = json.get("soundId").getAsString();
                }
                if (json != null && json.has("path") && !json.get("path").isJsonNull()) {
                    path = json.get("path").getAsString();
                }
            }

            LOG.debug("[SoundSettingsHandler] Testing sound: " + soundId);
            SoundNotificationService.getInstance().testPlaySound(soundId, path);
        } catch (Exception e) {
            LOG.error("[SoundSettingsHandler] Failed to test sound: " + e.getMessage(), e);
        }
    }

    /**
     * 触发一次 CC GUI 内部 popup 预览，帮助用户确认前端弹窗链路是否可用。
     */
    public void handleTestTaskReminderPopup() {
        if (taskReminderDispatcher == null) {
            LOG.warn("[SoundSettingsHandler] Skip popup reminder preview because dispatcher is unavailable");
            return;
        }
        LOG.info("[SoundSettingsHandler] Trigger popup reminder preview from settings");
        taskReminderDispatcher.dispatchTestPopup();
    }

    /**
     * 触发一次 IDE balloon 预览，帮助用户区分“插件未发送”与“IDE 设置未展示”。
     */
    public void handleTestTaskReminderBalloon() {
        if (taskReminderDispatcher == null) {
            LOG.warn("[SoundSettingsHandler] Skip balloon reminder preview because dispatcher is unavailable");
            return;
        }
        LOG.info("[SoundSettingsHandler] Trigger balloon reminder preview from settings");
        taskReminderDispatcher.dispatchTestBalloon();
    }

    /**
     * 打开系统文件选择器，让用户选择一个自定义提示音文件。
     * 选择完成后会立即写入配置，并把 selectedSound 切到 custom。
     */
    public void handleBrowseSoundFile() {
        invokeLaterSafe(() -> {
            try {
                com.intellij.openapi.fileChooser.FileChooserDescriptor descriptor =
                    new com.intellij.openapi.fileChooser.FileChooserDescriptor(
                        true, false, false, false, false, false
                    )
                    .withFileFilter(file -> {
                        String ext = file.getExtension();
                        return ext != null && (
                            ext.equalsIgnoreCase("wav") ||
                            ext.equalsIgnoreCase("mp3") ||
                            ext.equalsIgnoreCase("aiff")
                        );
                    })
                    .withTitle(ClaudeCodeGuiBundle.message("soundSettings.fileChooserTitle"))
                    .withDescription(ClaudeCodeGuiBundle.message("soundSettings.fileChooserDescription"));

                com.intellij.openapi.fileChooser.FileChooser.chooseFile(
                    descriptor,
                    context.getProject(),
                    null,
                    file -> {
                        if (file != null) {
                            String path = file.getPath();

                            // 浏览文件属于用户的明确选择动作，因此这里直接自动保存，
                            // 省去“选择文件后还要再点一次保存”的重复操作。
                            boolean enabled = false;
                            try {
                                enabled = settingsService.getSoundNotificationEnabled();
                                settingsService.setCustomSoundPath(path);
                                settingsService.setSelectedSound("custom");
                            } catch (Exception e) {
                                LOG.warn("[SoundSettingsHandler] Failed to auto-save selected sound path: " + e.getMessage());
                            }

                            JsonObject response = new JsonObject();
                            response.addProperty("enabled", enabled);
                            response.addProperty("selectedSound", "custom");
                            response.addProperty("customSoundPath", path);
                            context.callJavaScript("window.updateSoundNotificationConfig",
                                context.escapeJs(gson.toJson(response)));
                            context.callJavaScript("window.showSuccessI18n",
                                context.escapeJs("settings.basic.soundNotification.customSoundSaved"));
                        }
                    }
                );
            } catch (Exception e) {
                LOG.error("[SoundSettingsHandler] Failed to open file chooser: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 从 settingsService 读取最新配置并统一回推到前端。
     * 新前端会收到 canonical taskReminderConfig；旧接口也会收到兼容声音配置。
     */
    private void dispatchSoundConfigUpdate() {
        try {
            JsonObject taskReminderConfig = settingsService.getTaskReminderConfig();
            dispatchTaskReminderConfigUpdate(taskReminderConfig);
        } catch (Exception e) {
            dispatchTaskReminderConfigUpdate(createFallbackTaskReminderConfig());
        }
    }

    /**
     * 同时向新旧前端回调推送配置。
     * 新回调用于完整设置页；旧回调用于兼容尚未迁移的声音设置入口。
     */
    private void dispatchTaskReminderConfigUpdate(JsonObject taskReminderConfig) {
        final JsonObject finalTaskReminder = taskReminderConfig;

        invokeLaterSafe(() -> {
            // 新前端优先消费 canonical taskReminderConfig。
            context.callJavaScript("window.updateTaskReminderConfig", context.escapeJs(gson.toJson(finalTaskReminder)));
            // 同时继续喂给旧的声音配置回调，保证老 UI 或未迁移完的逻辑不被断开。
            JsonObject legacySoundConfig = toLegacySoundConfig(finalTaskReminder);
            context.callJavaScript("window.updateSoundNotificationConfig", context.escapeJs(gson.toJson(legacySoundConfig)));
        });
    }

    /**
     * 仅向旧版声音回调推送兼容结构。
     */
    private void dispatchLegacySoundConfigUpdate(JsonObject taskReminderConfig) {
        JsonObject legacySoundConfig = toLegacySoundConfig(taskReminderConfig);
        invokeLaterSafe(() -> {
            context.callJavaScript("window.updateSoundNotificationConfig", context.escapeJs(gson.toJson(legacySoundConfig)));
        });
    }

    /**
     * 把 canonical taskReminderConfig 投影成旧版 soundNotification 结构。
     */
    private JsonObject toLegacySoundConfig(JsonObject taskReminderConfig) {
        JsonObject response = new JsonObject();
        // 旧接口只认 sound 这一小块结构，因此这里只做 sound 子树投影，
        // 明确避免把 popup/balloon 之类新概念“挤进”老接口。
        JsonObject sound = taskReminderConfig != null
            && taskReminderConfig.has("sound")
            && taskReminderConfig.get("sound").isJsonObject()
            ? taskReminderConfig.getAsJsonObject("sound")
            : new JsonObject();

        response.addProperty("enabled", sound.has("enabled") && !sound.get("enabled").isJsonNull()
            ? sound.get("enabled").getAsBoolean()
            : true);
        response.addProperty("onlyWhenUnfocused", sound.has("onlyWhenIdeUnfocused") && !sound.get("onlyWhenIdeUnfocused").isJsonNull()
            ? sound.get("onlyWhenIdeUnfocused").getAsBoolean()
            : true);
        response.addProperty("selectedSound", sound.has("selectedSound") && !sound.get("selectedSound").isJsonNull()
            ? sound.get("selectedSound").getAsString()
            : "default");
        response.addProperty("customSoundPath", sound.has("customSoundPath") && !sound.get("customSoundPath").isJsonNull()
            ? sound.get("customSoundPath").getAsString()
            : "");
        return response;
    }

    /**
     * 构造一份与前端默认值对齐的兜底 task reminder 配置。
     */
    private JsonObject createFallbackTaskReminderConfig() {
        // fallback 与前端 DEFAULT_TASK_REMINDER_CONFIG 对齐，
        // 确保任一侧解析失败时仍能回到同一套默认行为。
        JsonObject taskReminder = new JsonObject();

        JsonObject popup = new JsonObject();
        popup.addProperty("enabled", true);
        popup.addProperty("onlyWhenIdeUnfocused", false);
        popup.add("states", gson.fromJson("[\"waiting_confirm\",\"final_error\"]", com.google.gson.JsonArray.class));
        taskReminder.add("popup", popup);

        JsonObject balloon = new JsonObject();
        balloon.addProperty("enabled", true);
        balloon.addProperty("onlyWhenIdeUnfocused", true);
        balloon.add("states", gson.fromJson("[\"completed\",\"recovered\",\"final_error\"]", com.google.gson.JsonArray.class));
        taskReminder.add("balloon", balloon);

        JsonObject sound = new JsonObject();
        sound.addProperty("enabled", true);
        sound.addProperty("onlyWhenIdeUnfocused", true);
        sound.add("states", gson.fromJson("[\"completed\"]", com.google.gson.JsonArray.class));
        sound.addProperty("selectedSound", "default");
        sound.addProperty("customSoundPath", "");
        taskReminder.add("sound", sound);

        return taskReminder;
    }

    /**
     * 安全地切回 EDT 执行 UI 相关回调。
     * 测试环境下如果 Application 尚未初始化，则直接执行，避免调用链卡死。
     */
    private void invokeLaterSafe(Runnable runnable) {
        Application application = ApplicationManager.getApplication();
        if (application == null || application.isDisposed() || application.isUnitTestMode()) {
            // 单元测试模式下直接同步执行，避免 bridge 回调仍滞留在 invokeLater 队列里，
            // 导致测试线程在断言 updateTaskReminderConfig/updateSoundNotificationConfig 时拿到空结果。
            runnable.run();
            return;
        }
        application.invokeLater(runnable);
    }
}
