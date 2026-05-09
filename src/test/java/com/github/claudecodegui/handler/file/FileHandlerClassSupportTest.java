package com.github.claudecodegui.handler.file;

import com.github.claudecodegui.handler.core.HandlerContext;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertTrue;

/**
 * FileHandler 类跳转入口测试。
 * 用于验证第二阶段把 open_class 正式接入消息分发支持列表。
 */
public class FileHandlerClassSupportTest {

    /**
     * 验证 FileHandler 已声明支持 open_class 事件。
     */
    @Test
    public void shouldDeclareOpenClassAsSupportedType() {
        FileHandler handler = new FileHandler(createContext());

        assertTrue(Arrays.asList(handler.getSupportedTypes()).contains("open_class"));
    }

    private static HandlerContext createContext() {
        return new HandlerContext(
                null,
                null,
                null,
                null,
                new HandlerContext.JsCallback() {
                    @Override
                    public void callJavaScript(String functionName, String... args) {
                    }

                    @Override
                    public String escapeJs(String str) {
                        return str;
                    }
                }
        );
    }
}
