package com.github.claudecodegui.ui.toolwindow;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertTrue;

/**
 * 验证前端诊断日志落到 IDEA 日志前会先做裁剪，避免把超长 HTML 或 base64
 * 直接原样写入日志文件。
 * 该测试覆盖的目标很窄，只检查 Java 侧最终输出字符串是否发生截断，
 * 不依赖完整 JCEF 或真实 IDE 运行环境。
 */
public class ClaudeChatWindowFrontendDebugLogTest {

    /**
     * 验证前端诊断日志里的长字符串会被裁剪后再进入 Java 日志输出。
     * 断言意图：保证 rich paste / history restore 这类 payload 即使携带超长
     * HTML，也不会把整段内容直接塞进 idea.log。
     *
     * @throws Exception 反射调用失败时抛出
     */
    @Test
    public void shouldTruncateFrontendDebugLogDetailsBeforeWritingToIdeaLog() throws Exception {
        Method method = ClaudeChatWindow.class.getDeclaredMethod(
                "buildFrontendDebugLogLine",
                String.class,
                String.class,
                String.class
        );
        method.setAccessible(true);

        String longHtml = "<div>" + "x".repeat(600) + "</div>";
        String logLine = (String) method.invoke(null, "RichPaste.Apply", "sanitize", longHtml);

        assertTrue(logLine.contains("[FrontendDebug][RichPaste.Apply] sanitize"));
        assertTrue(logLine.contains("[truncated"));
        assertTrue(logLine.length() < longHtml.length() + 80);
    }
}
