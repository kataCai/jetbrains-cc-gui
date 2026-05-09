package com.github.claudecodegui.handler.file;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JavaClassNavigationSupport 约束测试。
 * 用于验证第二阶段第一版只接受 Java FQCN，不放开短类名和普通英文句子。
 */
public class JavaClassNavigationSupportTest {

    /**
     * 验证标准 Java FQCN 会被识别为合法输入。
     */
    @Test
    public void shouldAcceptJavaFqcn() {
        JavaClassNavigationSupport support = new JavaClassNavigationSupport();

        assertTrue(support.isValidFqcn("com.github.claudecodegui.handler.file.OpenFileHandler"));
    }

    /**
     * 验证短类名和普通句子不会被误判为可导航类名。
     */
    @Test
    public void shouldRejectShortNamesAndPlainProse() {
        JavaClassNavigationSupport support = new JavaClassNavigationSupport();

        assertFalse(support.isValidFqcn("OpenFileHandler"));
        assertFalse(support.isValidFqcn("this.is.just.a.normal.sentence"));
        assertFalse(support.isValidFqcn("com.github.claudecodegui.handler.file.openFileHandler"));
    }
}
