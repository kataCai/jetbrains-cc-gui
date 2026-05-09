package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiClass;
import com.intellij.pom.Navigatable;

import java.util.concurrent.CompletableFuture;

/**
 * Java 类点击跳转处理器。
 * 负责接收前端传入的 Java FQCN，解析为项目内 PsiClass，并在 IDE 中打开对应类定义。
 *
 * 适用场景：
 * 1. Markdown 文本中的 Java FQCN 被前端识别并点击
 * 2. 需要在当前项目内做精确类跳转
 *
 * 边界与约束：
 * 1. 第一版只支持 FQCN 精确解析
 * 2. 不支持短类名、多候选选择和跨语言导航
 * 3. 失败时通过前端错误消息提示，不抛出交互式选择流程
 */
class OpenClassHandler {

    private static final Logger LOG = Logger.getInstance(OpenClassHandler.class);

    private final HandlerContext context;
    private final JavaClassNavigationSupport navigationSupport;

    OpenClassHandler(HandlerContext context) {
        this(context, new JavaClassNavigationSupport());
    }

    OpenClassHandler(HandlerContext context, JavaClassNavigationSupport navigationSupport) {
        this.context = context;
        this.navigationSupport = navigationSupport;
    }

    /**
     * 处理前端发起的 open_class 请求。
     * 这里会先做输入校验，再在后台线程解析类，最后切回 UI 线程执行导航。
     *
     * @param className 前端传入的 Java FQCN
     */
    void handleOpenClass(String className) {
        LOG.info("Open class request: " + className);

        if (!navigationSupport.isValidFqcn(className)) {
            LOG.warn("Unsupported Java class name: " + className);
            return;
        }

        if (context.getProject() == null || context.getProject().isDisposed()) {
            notifyError("Cannot open class: Java navigation is unavailable in current IDE");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                PsiClass psiClass = navigationSupport.findProjectClass(context.getProject(), className);
                if (psiClass == null) {
                    LOG.warn("Java class not found: " + className);
                    notifyError("Cannot open class: class not found (" + className + ")");
                    return;
                }

                ApplicationManager.getApplication().invokeLater(() -> navigateToClass(psiClass, className), ModalityState.nonModal());
            } catch (Throwable t) {
                LOG.error("Failed to open class: " + className, t);
                notifyError("Cannot open class: Java navigation is unavailable in current IDE");
            }
        });
    }

    /**
     * 在 UI 线程执行类导航。
     * 如果 PSI 元素不可导航，则视为当前运行环境不支持该能力。
     *
     * @param psiClass 解析得到的类
     * @param className 原始 FQCN，用于日志和提示
     */
    private void navigateToClass(PsiClass psiClass, String className) {
        if (context.getProject() == null || context.getProject().isDisposed()) {
            return;
        }

        if (!(psiClass instanceof Navigatable) || !((Navigatable) psiClass).canNavigate()) {
            LOG.warn("Java class is not navigatable: " + className);
            notifyError("Cannot open class: Java navigation is unavailable in current IDE");
            return;
        }

        ((Navigatable) psiClass).navigate(true);
        LOG.info("Successfully opened class: " + className);
    }

    /**
     * 向前端发送错误提示。
     * 统一走 addErrorMessage，保持与现有文件打开失败链路一致。
     *
     * @param message 提示文案
     */
    private void notifyError(String message) {
        ApplicationManager.getApplication().invokeLater(() ->
                context.callJavaScript("addErrorMessage", context.escapeJs(message)), ModalityState.nonModal());
    }
}
