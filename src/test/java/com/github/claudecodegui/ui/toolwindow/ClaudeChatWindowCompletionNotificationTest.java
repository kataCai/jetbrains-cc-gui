package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.notifications.ClaudeNotifier;
import com.github.claudecodegui.provider.claude.ClaudeSDKBridge;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.session.ClaudeSession;
import com.intellij.openapi.project.Project;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 验证聊天窗口流结束后的完成通知行为。
 * 这里重点防止 Claude provider 在 onStreamEnded 阶段再次走旧的 showSuccess 直发链，
 * 避免它与统一的 TaskReminderDispatcher COMPLETED 提醒并行生效。
 */
public class ClaudeChatWindowCompletionNotificationTest {

    /**
     * 验证 Claude 正常结束流时，不再额外直发旧完成通知。
     * 当前完成态提示已经统一到任务状态链，这里只允许做流结束收尾，不允许再次补发独立 success toast。
     *
     * @throws Exception 反射设置字段或调用私有方法时的异常
     */
    @Test
    public void shouldNotTriggerLegacySuccessToastWhenClaudeStreamEnds() throws Exception {
        Path projectDir = Files.createTempDirectory("claude-chat-window-completion-test");
        AtomicInteger legacySuccessCalls = new AtomicInteger();
        ClaudeNotifier.setSuccessNotificationInterceptorForTest(
            (project, title, message, playSound) -> legacySuccessCalls.incrementAndGet()
        );
        try {
            ClaudeChatWindow window = allocateWindow();
            Project project = createProject(projectDir);
            ClaudeSession session = new ClaudeSession(project, new ClaudeSDKBridge(), new CodexSDKBridge());
            session.setProvider("claude");
            session.setSessionInfo("session-claude-complete", projectDir.toString());

            setField(window, "project", project);
            setField(window, "session", session);

            Method method = ClaudeChatWindow.class.getDeclaredMethod("onStreamEnded");
            method.setAccessible(true);
            method.invoke(window);

            assertEquals(0, legacySuccessCalls.get());
        } finally {
            ClaudeNotifier.setSuccessNotificationInterceptorForTest(null);
        }
    }

    /**
     * 使用 Unsafe 分配未执行构造函数的窗口对象，避免测试引入完整 JCEF / IDE UI 初始化依赖。
     *
     * @return 未初始化但可反射注入关键字段的 ClaudeChatWindow 实例
     * @throws Exception 获取 Unsafe 或分配实例时的异常
     */
    private static ClaudeChatWindow allocateWindow() throws Exception {
        sun.misc.Unsafe unsafe = getUnsafe();
        return (ClaudeChatWindow) unsafe.allocateInstance(ClaudeChatWindow.class);
    }

    /**
     * 构造一个最小可用的 Project 代理，仅满足当前通知测试需要。
     *
     * @param projectDir 临时项目目录
     * @return Project 代理对象
     */
    private static Project createProject(Path projectDir) {
        return (Project) Proxy.newProxyInstance(
            Project.class.getClassLoader(),
            new Class<?>[]{Project.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getBasePath" -> projectDir.toString();
                case "getName" -> "claude-chat-window-test";
                case "isDisposed" -> false;
                case "isOpen" -> true;
                case "getDisposed" -> null;
                case "toString" -> "claude-chat-window-test-project";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            }
        );
    }

    /**
     * 反射写入目标字段，供未初始化对象补齐最小测试上下文。
     *
     * @param target 目标对象
     * @param fieldName 字段名
     * @param value 字段值
     * @return 无返回值
     * @throws Exception 反射写字段失败时抛出
     */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = ClaudeChatWindow.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /**
     * 获取 Unsafe 实例，用于分配不执行构造函数的对象。
     *
     * @return Unsafe 单例
     * @throws Exception 反射获取失败
     */
    private static sun.misc.Unsafe getUnsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }
}
