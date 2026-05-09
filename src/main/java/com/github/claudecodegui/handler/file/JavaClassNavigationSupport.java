package com.github.claudecodegui.handler.file;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Java 类导航支持工具。
 * 负责封装 FQCN 合法性校验与基于 IDEA Java PSI 的精确类解析逻辑，
 * 供 OpenClassHandler 在第二阶段类名点击跳转链路中复用。
 *
 * 适用场景：
 * 1. 前端点击 Markdown 中的 Java FQCN 后，需要在项目内精确定位类定义
 * 2. 只支持 FQCN 形式，例如 com.example.demo.FooService
 *
 * 边界与约束：
 * 1. 第一版不支持短类名模糊匹配
 * 2. 第一版不支持 Kotlin/Groovy/Scala 等非 Java 语言扩展
 * 3. 若当前 IDE 或运行时不具备 Java PSI 能力，应由调用方兜底提示
 */
final class JavaClassNavigationSupport {

    private static final Pattern JAVA_FQCN_PATTERN =
            Pattern.compile("^(?:[a-z_][a-z0-9_]*\\.)+[A-Z][A-Za-z0-9_]*$");

    /**
     * 判断输入是否为受支持的 Java FQCN。
     * 这里要求：
     * 1. 至少包含一个包层级
     * 2. 包名段使用小写/数字/下划线
     * 3. 类名段以大写字母开头
     *
     * @param className 待校验的类名
     * @return 合法返回 true，否则返回 false
     */
    boolean isValidFqcn(String className) {
        return className != null && JAVA_FQCN_PATTERN.matcher(className.trim()).matches();
    }

    /**
     * 在项目作用域内按 FQCN 精确查找 Java 类。
     * 该方法只做解析，不负责导航和错误提示。
     *
     * @param project 当前项目
     * @param className Java FQCN
     * @return 找到则返回 PsiClass，否则返回 null
     */
    @Nullable
    PsiClass findProjectClass(Project project, String className) {
        if (project == null || project.isDisposed() || !isValidFqcn(className)) {
            return null;
        }

        return ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<PsiClass>) () ->
                JavaPsiFacade.getInstance(project)
                        .findClass(className, GlobalSearchScope.projectScope(project))
        );
    }
}
