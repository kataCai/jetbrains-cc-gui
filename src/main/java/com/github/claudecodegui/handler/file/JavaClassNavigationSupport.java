package com.github.claudecodegui.handler.file;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.pom.Navigatable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.function.Consumer;

/**
 * Java PSI 驱动的类跳转支持工具。
 * 负责在具备 com.intellij.java 能力的 IDE 中，完成类查找、索引期延迟处理以及最终导航。
 * 该类只应由 {@link OpenClassHandler} 通过反射调用，避免在不支持 Java PSI 的 IDE 中提前触发类加载失败。
 *
 * 适用场景：
 * 1. 前端点击 Java FQCN 后，需要在项目或依赖范围内精确定位类定义
 * 2. IDE 正在索引时，导航请求需要延迟到 smart mode 再重试
 *
 * 边界与约束：
 * 1. 当前优先做类级别导航，不处理短类名、多候选选择和方法级跳转
 * 2. 查找范围使用 allScope(project)，允许跳到项目依赖中的类
 * 3. 导航失败时通过回调返回统一错误提示，不直接弹额外交互框
 */
public class JavaClassNavigationSupport {

    private static final Logger LOG = Logger.getInstance(JavaClassNavigationSupport.class);

    /**
     * 提供给测试与上层调用方复用的 Java 全限定类名校验入口。
     * 这里直接复用 {@link OpenClassHandler} 中已经生效的校验规则，避免导航支持层与处理器层出现两套判定逻辑，
     * 从而导致测试断言、前端 linkify 和后端实际导航行为不一致。
     *
     * @param fqcn 待校验的 Java 全限定类名
     * @return 合法返回 true，否则返回 false
     */
    public boolean isValidFqcn(String fqcn) {
        return OpenClassHandler.isValidClassName(fqcn);
    }

    /**
     * 导航到目标类。
     * 当项目处于索引阶段时，会先挂到 runWhenSmart，待索引完成后再执行一次真正的 PSI 查找与导航。
     *
     * @param project 当前项目
     * @param fqcn Java 全限定类名
     * @param onFailure 导航失败时的回调提示
     * @return 请求是否被成功接收；接收后不代表类一定存在，但代表后续会继续处理
     */
    public static boolean navigate(Project project, String fqcn, Consumer<String> onFailure) {
        if (project == null || project.isDisposed() || fqcn == null || fqcn.isBlank()) {
            return false;
        }

        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).runWhenSmart(() -> {
                boolean navigated = navigateWhenSmart(project, fqcn);
                if (!navigated) {
                    LOG.warn("Unable to resolve class after indexing completed: " + fqcn);
                    notifyNavigationFailure(onFailure, fqcn);
                }
            });
            return true;
        }

        boolean navigated = navigateWhenSmart(project, fqcn);
        if (!navigated) {
            notifyNavigationFailure(onFailure, fqcn);
        }

        return true;
    }

    /**
     * 向上层回调统一的“类不存在”错误文案。
     *
     * @param onFailure 错误回调
     * @param fqcn 目标类名
     */
    private static void notifyNavigationFailure(Consumer<String> onFailure, String fqcn) {
        if (onFailure != null) {
            onFailure.accept("Cannot open class: not found (" + fqcn + ")");
        }
    }

    /**
     * 在 smart mode 中完成类解析并调度 UI 导航。
     * 这里先通过 SmartPointer 固化目标 PSI，避免跨线程直接持有裸元素。
     *
     * @param project 当前项目
     * @param fqcn Java 全限定类名
     * @return 找到目标并成功调度导航时返回 true，否则返回 false
     */
    private static boolean navigateWhenSmart(Project project, String fqcn) {
        SmartPsiElementPointer<PsiElement> pointer = ReadAction.compute(() -> {
            PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(fqcn, createClassSearchScope(project));
            if (psiClass == null) {
                return null;
            }

            PsiElement navigationTarget = psiClass.getNavigationElement();
            PsiElement target = navigationTarget != null ? navigationTarget : psiClass;
            return SmartPointerManager.getInstance(project).createSmartPsiElementPointer(target);
        });

        if (pointer == null) {
            return false;
        }

        ApplicationManager.getApplication().invokeLater(() -> navigatePointer(project, pointer), ModalityState.nonModal());
        return true;
    }

    /**
     * 创建类查找范围。
     * 当前使用 allScope(project)，以便支持跳转到项目依赖中的 Java 类，而不仅限于项目源码。
     *
     * @param project 当前项目
     * @return 类查找范围
     */
    static GlobalSearchScope createClassSearchScope(Project project) {
        return GlobalSearchScope.allScope(project);
    }

    /**
     * 在 UI 线程中根据智能指针执行最终导航。
     * 优先复用 PSI 元素自身的 Navigatable 能力；若不可直接导航，则回退到文件 + offset 打开。
     *
     * @param project 当前项目
     * @param pointer 指向目标元素的智能指针
     */
    private static void navigatePointer(Project project, SmartPsiElementPointer<PsiElement> pointer) {
        if (project.isDisposed()) {
            return;
        }

        PsiElement target = pointer.getElement();
        if (target == null || !target.isValid()) {
            return;
        }

        if (target instanceof Navigatable navigatable && navigatable.canNavigate()) {
            navigatable.navigate(true);
            return;
        }

        PsiFile containingFile = target.getContainingFile();
        if (containingFile == null || containingFile.getVirtualFile() == null) {
            return;
        }

        int offset = Math.max(target.getTextOffset(), 0);
        new OpenFileDescriptor(project, containingFile.getVirtualFile(), offset).navigate(true);
    }
}
