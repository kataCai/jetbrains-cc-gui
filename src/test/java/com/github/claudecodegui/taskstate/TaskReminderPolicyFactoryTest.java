package com.github.claudecodegui.taskstate;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 task reminder 配置到运行时策略的转换行为。
 */
public class TaskReminderPolicyFactoryTest {

    @Test
    public void shouldBuildPolicyFromTaskReminderConfig() {
        TaskReminderPolicyFactory factory = new TaskReminderPolicyFactory();
        JsonObject config = new JsonObject();

        JsonObject popup = new JsonObject();
        popup.addProperty("enabled", true);
        popup.addProperty("onlyWhenIdeUnfocused", false);
        popup.add("states", states("waiting_confirm"));
        config.add("popup", popup);

        JsonObject balloon = new JsonObject();
        balloon.addProperty("enabled", false);
        balloon.addProperty("onlyWhenIdeUnfocused", true);
        balloon.add("states", states("completed"));
        config.add("balloon", balloon);

        JsonObject sound = new JsonObject();
        sound.addProperty("enabled", true);
        sound.addProperty("onlyWhenIdeUnfocused", false);
        sound.add("states", states("completed"));
        config.add("sound", sound);

        TaskReminderPolicy policy = factory.fromTaskReminderConfig(config);

        TaskReminderPolicy.ReminderDecision waitingDecision = policy.decide(
            snapshot(TaskState.WAITING_CONFIRM),
            false,
            true
        );
        assertTrue(waitingDecision.shouldShowPopup());

        TaskReminderPolicy.ReminderDecision completedDecision = policy.decide(
            snapshot(TaskState.COMPLETED),
            false,
            true
        );
        assertFalse(completedDecision.shouldShowBalloon());
        assertTrue(completedDecision.shouldPlaySound());
        assertTrue(completedDecision.shouldUpdateStatusBar());
    }

    @Test
    public void shouldFilterUnsupportedPopupStates() {
        TaskReminderPolicyFactory factory = new TaskReminderPolicyFactory();
        JsonObject config = new JsonObject();

        JsonObject popup = new JsonObject();
        popup.addProperty("enabled", true);
        popup.addProperty("onlyWhenIdeUnfocused", false);
        popup.add("states", states("waiting_confirm", "completed"));
        config.add("popup", popup);

        TaskReminderPolicy policy = factory.fromTaskReminderConfig(config);

        TaskReminderPolicy.ReminderDecision waitingDecision = policy.decide(
            snapshot(TaskState.WAITING_CONFIRM),
            false,
            true
        );
        TaskReminderPolicy.ReminderDecision completedDecision = policy.decide(
            snapshot(TaskState.COMPLETED),
            false,
            true
        );

        assertTrue(waitingDecision.shouldShowPopup());
        assertFalse(completedDecision.shouldShowPopup());
    }

    @Test
    public void shouldRespectPopupOnlyWhenIdeUnfocusedSetting() {
        TaskReminderPolicyFactory factory = new TaskReminderPolicyFactory();
        JsonObject config = new JsonObject();

        JsonObject popup = new JsonObject();
        popup.addProperty("enabled", true);
        popup.addProperty("onlyWhenIdeUnfocused", true);
        popup.add("states", states("waiting_confirm"));
        config.add("popup", popup);

        TaskReminderPolicy policy = factory.fromTaskReminderConfig(config);

        TaskReminderPolicy.ReminderDecision focusedDecision = policy.decide(
            snapshot(TaskState.WAITING_CONFIRM),
            false,
            true
        );
        TaskReminderPolicy.ReminderDecision unfocusedDecision = policy.decide(
            snapshot(TaskState.WAITING_CONFIRM),
            false,
            false
        );

        assertFalse(focusedDecision.shouldShowPopup());
        assertTrue(unfocusedDecision.shouldShowPopup());
    }

    private static TaskStateSnapshot snapshot(TaskState state) {
        return new TaskStateSnapshot(
            state,
            "session-test",
            "req-test",
            new TaskStateEvent(state, "session-test", "req-test", state.getValue(), System.currentTimeMillis())
        );
    }

    private static JsonArray states(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
