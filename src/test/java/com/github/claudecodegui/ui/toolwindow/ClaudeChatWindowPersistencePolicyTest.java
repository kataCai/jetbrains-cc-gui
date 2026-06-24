package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.settings.TabStateService;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ClaudeChatWindow 持久化与恢复节流策略测试。
 * 该测试聚焦本轮 Android Studio 卡死修复后的两个关键约束：
 * 1. 只有快照真正变化时，才允许请求项目级状态落盘。
 * 2. WebView 的手动刷新与 watchdog 重建必须受到最小触发间隔限制，避免恢复放大。
 */
public class ClaudeChatWindowPersistencePolicyTest {

    /**
     * 验证当持久化前后的快照发生真实变化时，允许进入延迟落盘请求队列。
     * 当前语义不再直接判断“是否立即 flush”，而是判断“是否应请求异步防抖落盘”。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldRequestProjectStateFlushWhenSnapshotChanges() throws Exception {
        TabStateService.TabSessionState persistedState = new TabStateService.TabSessionState();
        persistedState.sessionId = null;
        persistedState.provider = "codex";

        TabStateService.TabSessionState snapshot = new TabStateService.TabSessionState();
        snapshot.sessionId = "session-real-1";
        snapshot.provider = "codex";

        assertTrue(invokeShouldRequestProjectStateFlushAfterSnapshotSave(persistedState, snapshot));
    }

    /**
     * 验证当持久化前后的快照完全等价时，不应再请求项目级状态落盘。
     * 这对应本轮修复中“frontend_ready / reload 热路径 no-op 抑制”的核心约束。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldNotRequestProjectStateFlushWhenSnapshotIsEquivalent() throws Exception {
        TabStateService.TabSessionState persistedState = new TabStateService.TabSessionState();
        persistedState.sessionId = "session-stable-1";
        persistedState.provider = "codex";
        persistedState.runtimeFamily = "codex";
        persistedState.cwd = "/workspace/demo";
        persistedState.model = "gpt-5.4";
        persistedState.permissionMode = "bypassPermissions";
        persistedState.reasoningEffort = "medium";
        persistedState.titleBindingMode = TabStateService.TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE;

        TabStateService.TabSessionState snapshot = persistedState.copy();

        assertFalse(invokeShouldRequestProjectStateFlushAfterSnapshotSave(persistedState, snapshot));
    }

    /**
     * 验证 WebView 恢复节流会阻止过于频繁的重建请求。
     * 该约束用于抑制 manual force refresh 与 watchdog recreate 在短时间内连续放大卡顿。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldThrottleFrequentWebviewRecoveryRequests() throws Exception {
        ClaudeChatWindow window = allocateWindow();
        setLongField(window, "lastWebviewRecoveryRequestAtMs", System.currentTimeMillis());

        assertFalse(invokeCanRequestWebviewRecovery(window, "manual_force_refresh"));
    }

    /**
     * 验证超过最小触发间隔后，新的 WebView 恢复请求可以被放行。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldAllowWebviewRecoveryAfterMinInterval() throws Exception {
        ClaudeChatWindow window = allocateWindow();
        setLongField(window, "lastWebviewRecoveryRequestAtMs", 0L);

        assertTrue(invokeCanRequestWebviewRecovery(window, "watchdog_recreate"));
    }

    /**
     * 反射调用“是否应请求项目级异步落盘”的策略方法。
     *
     * @param persistedState 已持久化旧快照
     * @param snapshot 当前新快照
     * @return 是否应请求异步落盘
     * @throws Exception 反射调用异常
     */
    private boolean invokeShouldRequestProjectStateFlushAfterSnapshotSave(
            TabStateService.TabSessionState persistedState,
            TabStateService.TabSessionState snapshot
    ) throws Exception {
        Method method = ClaudeChatWindow.class.getDeclaredMethod(
                "shouldRequestProjectStateFlushAfterSnapshotSave",
                TabStateService.TabSessionState.class,
                TabStateService.TabSessionState.class
        );
        method.setAccessible(true);
        return (Boolean) method.invoke(allocateWindow(), persistedState, snapshot);
    }

    /**
     * 反射调用 WebView 恢复节流判定方法。
     *
     * @param window 测试窗口实例
     * @param reason 恢复原因
     * @return 是否允许发起恢复
     * @throws Exception 反射调用异常
     */
    private boolean invokeCanRequestWebviewRecovery(ClaudeChatWindow window, String reason) throws Exception {
        Method method = ClaudeChatWindow.class.getDeclaredMethod("canRequestWebviewRecovery", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(window, reason);
    }

    /**
     * 通过 Unsafe 分配未执行构造函数的窗口实例。
     * 本测试只验证纯策略逻辑，不依赖完整的 IDE 或 JCEF 环境。
     *
     * @return 未初始化构造链的窗口实例
     * @throws Exception 反射获取 Unsafe 异常
     */
    private ClaudeChatWindow allocateWindow() throws Exception {
        return (ClaudeChatWindow) getUnsafe().allocateInstance(ClaudeChatWindow.class);
    }

    /**
     * 通过反射设置 long 字段，便于驱动时间相关的恢复节流测试。
     *
     * @param target 目标对象
     * @param fieldName 字段名
     * @param value 目标值
     * @throws Exception 反射设置异常
     */
    private void setLongField(Object target, String fieldName, long value) throws Exception {
        Field field = ClaudeChatWindow.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    /**
     * 获取 Unsafe 实例以便在不执行构造函数的前提下创建对象。
     *
     * @return Unsafe 实例
     * @throws Exception 反射获取异常
     */
    private sun.misc.Unsafe getUnsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
