package com.github.claudecodegui.ui.toolwindow;

import com.intellij.openapi.project.Project;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

/**
 * ClaudeSDKToolWindow loading panel 兜底行为测试。
 * 该测试聚焦 bridge 已就绪后、聊天窗口初始化再次失败的异常路径，
 * 验证工具窗口不会继续停留在“Preparing AI Bridge...”的误导性状态，
 * 而是把 loading panel 明确切换为聊天窗口初始化失败提示。
 */
public class ClaudeSDKToolWindowLoadingPanelTest {

    /**
     * 验证当聊天窗口替换阶段抛异常时，loading panel 会被回写为明确错误提示。
     * 这里通过反射调用安全包装方法，并故意传入不完整依赖触发内部异常，
     * 以覆盖“异常被捕获后更新错误面板”的回归链路。
     *
     * @throws Exception 当反射调用失败时抛出
     */
    @Test
    public void shouldShowChatWindowInitializationErrorWhenReplacementFails() throws Exception {
        ClaudeSDKToolWindow toolWindow = new ClaudeSDKToolWindow();
        JPanel loadingPanel = new JPanel();
        loadingPanel.add(new JLabel("Preparing AI Bridge..."));

        Method method = ClaudeSDKToolWindow.class.getDeclaredMethod(
                "replaceLoadingPanelWithChatWindowSafely",
                Project.class,
                com.intellij.ui.content.ContentFactory.class,
                com.intellij.ui.content.ContentManager.class,
                com.intellij.ui.content.Content.class,
                JPanel.class
        );
        method.setAccessible(true);

        method.invoke(toolWindow, createProject(), null, null, null, loadingPanel);

        assertEquals(
                "Chat window initialization failed. Please check idea.log and restart IDE.",
                findSingleLabelText(loadingPanel)
        );
    }

    /**
     * 创建最小 Project 代理。
     * 当前测试只依赖项目名称用于日志打印，其余调用允许返回空值以触发替换链路内部异常，
     * 进而验证安全包装方法是否把错误提示稳定写回 loading panel。
     *
     * @return 供 ClaudeSDKToolWindow 测试使用的最小 Project
     */
    private static Project createProject() {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class[]{Project.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "toolwindow-loading-test";
                    case "isDisposed" -> false;
                    default -> method.getReturnType().isPrimitive() ? defaultPrimitiveValue(method.getReturnType()) : null;
                }
        );
    }

    /**
     * 从更新后的 loading panel 中提取唯一的可见文本标签。
     * 当前错误面板结构固定为一层容器包裹一个图标和一个文本标签，
     * 因此这里按同样的结构读取最终文案，避免把布局细节暴露给断言调用方。
     *
     * @param loadingPanel 已被替换内容的 loading panel
     * @return 面板中唯一的文本标签内容
     */
    private static String findSingleLabelText(JPanel loadingPanel) {
        JPanel centerPanel = (JPanel) loadingPanel.getComponent(0);
        for (Component component : centerPanel.getComponents()) {
            if (component instanceof JLabel label) {
                String text = label.getText();
                if (text != null && !text.isEmpty() && !"⚠".equals(text) && !"齿".equals(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    /**
     * 为动态代理补齐基本类型默认值。
     *
     * @param primitiveType 当前方法的返回基本类型
     * @return 对应的零值
     */
    private static Object defaultPrimitiveValue(Class<?> primitiveType) {
        if (primitiveType == boolean.class) {
            return false;
        }
        if (primitiveType == char.class) {
            return '\0';
        }
        return 0;
    }
}
