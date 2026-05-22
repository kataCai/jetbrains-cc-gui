package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Java 类点击跳转处理器。
 * 负责接收前端传入的 Java 全限定类名，完成输入校验、能力探测和异步导航调度。
 * 这里不直接依赖 Java PSI 类型，而是通过反射调用 {@link JavaClassNavigationSupport}，
 * 以便在不具备 com.intellij.java 能力的 IDE 中仍可安全加载主处理链路。
 *
 * 适用场景：
 * 1. Markdown 文本中的 Java FQCN 被前端识别并点击
 * 2. 前端在启动后探测当前 IDE 是否支持类跳转
 *
 * 边界与约束：
 * 1. 当前仅支持 FQCN 精确跳转，不支持短类名、多候选选择和跨语言导航
 * 2. 若当前 IDE 缺少 Java PSI 或项目不可用，统一通过前端错误提示降级
 * 3. 真实导航逻辑下沉到 JavaClassNavigationSupport，便于后续继续演进 PSI 细节
 */
public class OpenClassHandler {

    private static final Logger LOG = Logger.getInstance(OpenClassHandler.class);
    // Accepts dotted Java identifiers; requires at least one '.', allows '$' for
    // inner classes, and rejects whitespace, '#', and '(' via the showError guards.
    private static final Pattern JAVA_FQCN_PATTERN = Pattern.compile(
        "^[a-zA-Z_$][\\w$]*(?:\\.[a-zA-Z_$][\\w$]*)+$"
    );
    private static final Method NAVIGATE_METHOD = loadNavigateMethod();

    @FunctionalInterface
    interface NavigationInvoker {
        boolean navigate(Project project, String fqcn, Consumer<String> onFailure) throws Exception;
    }

    private final HandlerContext context;
    private final NavigationInvoker navigationInvoker;
    private final boolean classNavigationEnabled;

    OpenClassHandler(HandlerContext context) {
        this(context, createNavigationInvoker(), isClassNavigationEnabled());
    }

    OpenClassHandler(HandlerContext context, NavigationInvoker navigationInvoker) {
        this(context, navigationInvoker, true);
    }

    OpenClassHandler(HandlerContext context, NavigationInvoker navigationInvoker, boolean classNavigationEnabled) {
        this.context = context;
        this.navigationInvoker = navigationInvoker;
        this.classNavigationEnabled = classNavigationEnabled;
    }

    /**
     * 判断当前 IDE 是否具备 Java 类跳转能力。
     * 该能力依赖反射成功加载 JavaClassNavigationSupport 及其 navigate 方法。
     *
     * @return 支持时返回 true，否则返回 false
     */
    public static boolean isClassNavigationEnabled() {
        return NAVIGATE_METHOD != null;
    }

    /**
     * 构建前端所需的 linkify capability JSON。
     * 当前仅暴露 classNavigationEnabled，后续如扩展更多跳转能力可继续在此追加。
     *
     * @return 可直接传给前端的 JSON 字符串
     */
    public static String buildCapabilitiesJson() {
        JsonObject payload = new JsonObject();
        payload.addProperty("classNavigationEnabled", isClassNavigationEnabled());
        return payload.toString();
    }

    /**
     * 校验输入是否为当前受支持的 Java 类名格式。
     * 这里要求至少包含一层包路径，并额外排除方法签名、锚点等非类名形式。
     *
     * @param fqcn 待校验的类名
     * @return 合法返回 true，否则返回 false
     */
    static boolean isValidClassName(String fqcn) {
        if (fqcn == null) {
            return false;
        }

        String trimmed = fqcn.trim();
        if (trimmed.isEmpty()) {
            return false;
        }

        return JAVA_FQCN_PATTERN.matcher(trimmed).matches()
            && !trimmed.contains("#")
            && !trimmed.contains("(");
    }

    /**
     * 处理前端发起的 open_class 请求。
     * 会先做输入和运行时能力校验，再把真实导航放到后台线程，避免阻塞 UI。
     *
     * @param fqcn 前端传入的 Java 全限定类名
     */
    void handleOpenClass(String fqcn) {
        String trimmed = fqcn == null ? "" : fqcn.trim();
        LOG.debug("Open class request: " + trimmed);

        if (!isValidClassName(trimmed)) {
            showError("Cannot open class: invalid class name (" + trimmed + ")");
            return;
        }

        Project project = context.getProject();
        if (project == null || project.isDisposed()) {
            showError("Cannot open class: project is not available");
            return;
        }

        if (!classNavigationEnabled) {
            showError("Cannot open class: Java navigation is not available in this IDE");
            return;
        }

        CompletableFuture.runAsync(() -> executeNavigation(project, trimmed), AppExecutorUtil.getAppExecutorService());
    }

    /**
     * 在后台线程执行真实导航调用。
     * 这里统一收口反射调用异常和 PSI 导航失败，避免不同异常路径暴露给上层。
     *
     * @param project 当前项目
     * @param fqcn 目标类名
     */
    void executeNavigation(Project project, String fqcn) {
        try {
            boolean accepted = navigationInvoker.navigate(project, fqcn, this::showError);
            if (!accepted) {
                LOG.warn("Class navigation request was not accepted: " + fqcn);
                showError("Cannot open class: " + fqcn);
            }
        } catch (IllegalAccessException e) {
            LOG.warn("Failed to open class (reflection access denied): " + fqcn + ", error=" + e.getMessage(), e);
            showError("Cannot open class: " + fqcn);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOG.warn("Failed to open class (PSI navigation error): " + fqcn + ", error=" + cause.getMessage(), cause);
            showError("Cannot open class: " + fqcn);
        } catch (Exception e) {
            LOG.warn("Failed to open class: " + fqcn + ", error=" + e.getMessage(), e);
            showError("Cannot open class: " + fqcn);
        }
    }

    /**
     * 向前端发送统一错误提示。
     * 单元测试或无浏览器上下文时直接同步回调，正常 IDE 运行时则切回非模态 UI 线程。
     *
     * @param message 错误提示文案
     */
    private void showError(String message) {
        if (context.getBrowser() == null
            || ApplicationManager.getApplication() == null
            || ApplicationManager.getApplication().isUnitTestMode()) {
            context.callJavaScript("addErrorMessage", context.escapeJs(message));
            return;
        }

        ApplicationManager.getApplication().invokeLater(() -> {
            context.callJavaScript("addErrorMessage", context.escapeJs(message));
        }, ModalityState.nonModal());
    }

    /**
     * 创建导航调用器。
     * 只有当 JavaClassNavigationSupport 成功加载时，才会返回真实导航实现；否则返回统一降级实现。
     *
     * @return 导航调用器
     */
    private static NavigationInvoker createNavigationInvoker() {
        if (NAVIGATE_METHOD == null) {
            return (project, fqcn, onFailure) -> false;
        }

        return (project, fqcn, onFailure) -> Boolean.TRUE.equals(
            NAVIGATE_METHOD.invoke(null, project, fqcn, onFailure)
        );
    }

    /**
     * 通过反射延迟加载 JavaClassNavigationSupport。
     * 先探测 com.intellij.java 能否使用，再尝试拿到 navigate 方法，避免在不支持 Java PSI 的 IDE 中类加载失败。
     *
     * @return 成功时返回反射方法句柄，否则返回 null
     */
    private static Method loadNavigateMethod() {
        try {
            Class.forName("com.intellij.psi.PsiJavaFile");
            Class<?> navigationSupportClass = Class.forName(
                "com.github.claudecodegui.handler.file.JavaClassNavigationSupport"
            );
            return navigationSupportClass.getMethod("navigate", Project.class, String.class, Consumer.class);
        } catch (ClassNotFoundException e) {
            LOG.info("Java class navigation is unavailable in this IDE");
        } catch (Exception e) {
            LOG.warn("Failed to initialize Java class navigation support: " + e.getMessage(), e);
        }
        return null;
    }
}
