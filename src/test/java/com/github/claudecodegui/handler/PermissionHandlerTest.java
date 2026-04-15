package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.taskstate.TaskReminderDispatcher;
import com.github.claudecodegui.taskstate.TaskState;
import com.github.claudecodegui.taskstate.TaskStateService;
import com.github.claudecodegui.taskstate.TaskStateSnapshot;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证审批弹窗可见性事件会驱动 waiting_confirm 的提醒抑制状态切换。
 */
public class PermissionHandlerTest {

    @Test
    public void shouldDispatchReminderWhenPlanApprovalDialogBecomesVisible() {
        TaskStateService taskStateService = new TaskStateService();
        taskStateService.onPlanApprovalRequested("req-visible");
        RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher();
        PermissionHandler handler = new PermissionHandler(
            createContext(),
            taskStateService,
            dispatcher
        );

        boolean handled = handler.handle(
            "plan_approval_dialog_visibility",
            "{\"requestId\":\"req-visible\",\"visible\":true}"
        );

        assertTrue(handled);
        assertEquals(1, dispatcher.approvalDialogVisibleFlags.size());
        assertTrue(dispatcher.approvalDialogVisibleFlags.get(0));
        assertEquals(TaskState.WAITING_CONFIRM, dispatcher.snapshots.get(0).getState());
    }

    @Test
    public void shouldSuppressReminderPopupBeforePlanApprovalDialogActuallyShows() {
        Application previousApplication = ApplicationManager.getApplication();
        Disposable testDisposable = null;
        if (previousApplication == null) {
            testDisposable = Disposer.newDisposable();
            MockApplication.setUp(testDisposable);
        }

        TaskStateService taskStateService = new TaskStateService();
        RecordingTaskReminderDispatcher dispatcher = new RecordingTaskReminderDispatcher();
        try {
            PermissionHandler handler = new PermissionHandler(
                createContext(),
                taskStateService,
                dispatcher
            );

            handler.showPlanApprovalDialog("req-pending", new com.google.gson.JsonObject());

            assertEquals(1, dispatcher.approvalDialogVisibleFlags.size());
            // 进入 WAITING_CONFIRM 时应立即按“审批弹窗已占位”来分发，避免先弹 reminder popup。
            assertTrue(dispatcher.approvalDialogVisibleFlags.get(0));
            assertEquals(TaskState.WAITING_CONFIRM, dispatcher.snapshots.get(0).getState());
        } finally {
            if (testDisposable != null) {
                Disposer.dispose(testDisposable);
            }
        }
    }

    private static HandlerContext createContext() {
        return new HandlerContext(
            null,
            null,
            null,
            null,
            new HandlerContext.JsCallback() {
                @Override
                public void callJavaScript(String functionName, String... args) {
                }

                @Override
                public String escapeJs(String str) {
                    return str;
                }
            }
        );
    }

    private static class RecordingTaskReminderDispatcher extends TaskReminderDispatcher {
        private final List<Boolean> approvalDialogVisibleFlags = new ArrayList<>();
        private final List<TaskStateSnapshot> snapshots = new ArrayList<>();

        RecordingTaskReminderDispatcher() {
            super(createContext());
        }

        @Override
        public void dispatch(TaskStateSnapshot snapshot, boolean approvalDialogVisible) {
            approvalDialogVisibleFlags.add(approvalDialogVisible);
            snapshots.add(snapshot);
        }
    }
}
