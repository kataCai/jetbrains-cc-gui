package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.settings.TabStateService;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ClaudeChatWindow 持久化策略测试。
 * 用于验证会话绑定从空值切换到真实 sessionId 时，会触发主动落盘判定，
 * 避免 IDE 快速关闭导致 .idea 状态文件仍停留在旧快照。
 */
public class ClaudeChatWindowPersistencePolicyTest {

    /**
     * 验证新快照首次拿到真实 sessionId 时应触发主动落盘。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldFlushWhenSessionIdChangesFromEmptyToRealValue() throws Exception {
        TabStateService.TabSessionState persistedState = new TabStateService.TabSessionState();
        persistedState.sessionId = null;

        TabStateService.TabSessionState snapshot = new TabStateService.TabSessionState();
        snapshot.sessionId = "session-real-1";

        assertTrue(invokeShouldFlushProjectStateAfterSnapshotSave(persistedState, snapshot));
    }

    /**
     * 验证 sessionId 未变化时不应触发额外落盘。
     *
     * @throws Exception 反射调用异常
     */
    @Test
    public void shouldNotFlushWhenSessionIdDoesNotChange() throws Exception {
        TabStateService.TabSessionState persistedState = new TabStateService.TabSessionState();
        persistedState.sessionId = "session-stable-1";

        TabStateService.TabSessionState snapshot = new TabStateService.TabSessionState();
        snapshot.sessionId = "session-stable-1";

        assertFalse(invokeShouldFlushProjectStateAfterSnapshotSave(persistedState, snapshot));
    }

    /**
     * 反射调用持久化策略判定方法。
     * 这里不创建完整窗口实例，直接基于目标方法的纯逻辑进行校验，降低测试环境依赖。
     *
     * @param persistedState 旧快照
     * @param snapshot 新快照
     * @return 是否应触发主动落盘
     * @throws Exception 反射调用异常
     */
    private boolean invokeShouldFlushProjectStateAfterSnapshotSave(
            TabStateService.TabSessionState persistedState,
            TabStateService.TabSessionState snapshot
    ) throws Exception {
        Method method = ClaudeChatWindow.class.getDeclaredMethod(
                "shouldFlushProjectStateAfterSnapshotSave",
                TabStateService.TabSessionState.class,
                TabStateService.TabSessionState.class
        );
        method.setAccessible(true);
        sun.misc.Unsafe unsafe = getUnsafe();
        ClaudeChatWindow window = (ClaudeChatWindow) unsafe.allocateInstance(ClaudeChatWindow.class);
        return (Boolean) method.invoke(window, persistedState, snapshot);
    }

    /**
     * 获取 Unsafe 实例以便分配未调用构造函数的对象。
     * 该测试仅用于调用纯逻辑私有方法，不会访问窗口内部状态。
     *
     * @return Unsafe 实例
     * @throws Exception 反射获取异常
     */
    private sun.misc.Unsafe getUnsafe() throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
