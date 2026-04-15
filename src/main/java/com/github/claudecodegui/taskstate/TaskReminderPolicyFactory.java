package com.github.claudecodegui.taskstate;

import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 负责把设置页持久化的 taskReminder 配置转换为运行时提醒策略。
 */
public class TaskReminderPolicyFactory {

    private static final Logger LOG = Logger.getInstance(TaskReminderPolicyFactory.class);

    private static final Set<TaskState> DEFAULT_STATUS_BAR_STATES = EnumSet.of(
        TaskState.RUNNING,
        TaskState.WAITING_CONFIRM,
        TaskState.RETRYING,
        TaskState.RECOVERED,
        TaskState.FINAL_ERROR,
        TaskState.COMPLETED,
        TaskState.CANCELLED
    );
    private static final Set<TaskState> SUPPORTED_POPUP_STATES = EnumSet.of(
        TaskState.WAITING_CONFIRM,
        TaskState.FINAL_ERROR
    );

    /**
     * 从 settings service 读取最新 task reminder 配置并转换成策略。
     */
    public TaskReminderPolicy fromSettingsService(CodemossSettingsService settingsService) {
        if (settingsService == null) {
            return TaskReminderPolicy.defaults();
        }
        try {
            return fromTaskReminderConfig(settingsService.getTaskReminderConfig());
        } catch (IOException error) {
            LOG.warn("[TaskReminderPolicyFactory] Failed to load task reminder config, fallback to defaults", error);
            return TaskReminderPolicy.defaults();
        }
    }

    /**
     * 把 canonical taskReminder JSON 转为运行时策略。
     */
    public TaskReminderPolicy fromTaskReminderConfig(JsonObject config) {
        if (config == null) {
            return TaskReminderPolicy.defaults();
        }

        JsonObject popupConfig = config.getAsJsonObject("popup");
        JsonObject balloonConfig = config.getAsJsonObject("balloon");
        JsonObject soundConfig = config.getAsJsonObject("sound");

        Set<TaskState> popupStates = parseChannelStates(
            popupConfig,
            SUPPORTED_POPUP_STATES,
            EnumSet.of(TaskState.WAITING_CONFIRM, TaskState.FINAL_ERROR)
        );
        Set<TaskState> balloonStates = parseChannelStates(
            balloonConfig,
            null,
            EnumSet.of(TaskState.COMPLETED, TaskState.RECOVERED, TaskState.FINAL_ERROR)
        );
        Set<TaskState> soundStates = parseChannelStates(
            soundConfig,
            null,
            EnumSet.of(TaskState.COMPLETED)
        );

        boolean popupOnlyWhenIdeUnfocused = getOnlyWhenIdeUnfocused(popupConfig, false);
        boolean balloonOnlyWhenIdeUnfocused = getOnlyWhenIdeUnfocused(balloonConfig, true);
        boolean soundOnlyWhenIdeUnfocused = getOnlyWhenIdeUnfocused(soundConfig, true);

        TaskReminderPolicy policy = new TaskReminderPolicy(
            popupStates,
            balloonStates,
            soundStates,
            DEFAULT_STATUS_BAR_STATES,
            popupOnlyWhenIdeUnfocused,
            true,
            balloonOnlyWhenIdeUnfocused,
            soundOnlyWhenIdeUnfocused
        );
        LOG.debug(
            "[TaskReminderPolicyFactory] popup=" + popupStates
                + ", popupOnlyWhenIdeUnfocused=" + popupOnlyWhenIdeUnfocused
                + ", balloon=" + balloonStates
                + ", balloonOnlyWhenIdeUnfocused=" + balloonOnlyWhenIdeUnfocused
                + ", sound=" + soundStates
                + ", soundOnlyWhenIdeUnfocused=" + soundOnlyWhenIdeUnfocused
        );
        return policy;
    }

    private Set<TaskState> parseChannelStates(JsonObject channelConfig, Set<TaskState> supportedStates, Set<TaskState> fallback) {
        if (channelConfig == null) {
            return fallback;
        }
        if (channelConfig.has("enabled") && !channelConfig.get("enabled").isJsonNull()
            && !channelConfig.get("enabled").getAsBoolean()) {
            return EnumSet.noneOf(TaskState.class);
        }
        if (!channelConfig.has("states") || !channelConfig.get("states").isJsonArray()) {
            return fallback;
        }

        Set<TaskState> states = new HashSet<>();
        JsonArray array = channelConfig.getAsJsonArray("states");
        for (JsonElement element : array) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            TaskState state = parseTaskState(element.getAsString());
            if (state != null && (supportedStates == null || supportedStates.contains(state))) {
                states.add(state);
            } else if (state != null) {
                LOG.debug("[TaskReminderPolicyFactory] Skip unsupported reminder state: " + state.getValue());
            }
        }
        return states.isEmpty() ? fallback : EnumSet.copyOf(states);
    }

    private boolean getOnlyWhenIdeUnfocused(JsonObject channelConfig, boolean fallback) {
        if (channelConfig == null || !channelConfig.has("onlyWhenIdeUnfocused")
            || channelConfig.get("onlyWhenIdeUnfocused").isJsonNull()) {
            return fallback;
        }
        return channelConfig.get("onlyWhenIdeUnfocused").getAsBoolean();
    }

    private TaskState parseTaskState(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        for (TaskState state : TaskState.values()) {
            if (state.getValue().equals(value.trim())) {
                return state;
            }
        }
        return null;
    }
}
