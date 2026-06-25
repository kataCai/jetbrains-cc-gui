package com.github.claudecodegui.action;

import com.github.claudecodegui.handler.ClipboardHandler;
import org.junit.Test;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.MemoryImageSource;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ChatPasteAction} 的富剪贴板脚本构建测试。
 * 这里通过反射锁定图文共存时的脚本行为，确保后续实现不会继续把图片分支短路掉。
 */
public class ChatPasteActionTest {

    /**
     * 验证当文本和图片同时存在时，粘贴脚本仍会派发 `java-paste-image` 事件。
     * 该场景对应“历史图文消息复制后回贴到聊天输入框”，如果脚本里没有图片事件，输入框就无法恢复附件预览。
     */
    @Test
    public void buildPasteScriptIncludesImageDispatchWhenTextAndImageCoexist() throws Exception {
        Method method = ChatPasteAction.class.getDeclaredMethod(
                "buildPasteScript",
                String.class,
                String.class
        );
        method.setAccessible(true);

        String script = (String) method.invoke(null, "hello", "QUJD");

        assertNotNull(script);
        assertTrue(script.contains("java-paste-image"));
        assertTrue(script.contains("var txt="));
    }

    /**
     * 验证富剪贴板协议在包含多图时会优先派发统一的 `java-paste-rich-content` 事件。
     * 这个断言用于保护“历史会话图文消息整体复制再回贴”链路，确保 Java 侧不会继续退化成只能传首图的旧协议。
     */
    @Test
    public void buildRichPasteScriptDispatchesRichClipboardEvent() throws Exception {
        Method method = ChatPasteAction.class.getDeclaredMethod(
                "buildRichPasteScript",
                ClipboardHandler.ClipboardRichPayload.class
        );
        method.setAccessible(true);

        ClipboardHandler.ClipboardRichPayload payload = new ClipboardHandler.ClipboardRichPayload();
        payload.text = "hello";
        ClipboardHandler.ClipboardImagePayload first = new ClipboardHandler.ClipboardImagePayload();
        first.data = "QUJD";
        first.mediaType = "image/png";
        ClipboardHandler.ClipboardImagePayload second = new ClipboardHandler.ClipboardImagePayload();
        second.data = "REVG";
        second.mediaType = "image/png";
        payload.images = new ClipboardHandler.ClipboardImagePayload[]{first, second};

        String script = (String) method.invoke(null, payload);

        assertNotNull(script);
        assertTrue(script.contains("java-paste-rich-content"));
        assertTrue(script.contains("\"images\""));
        assertTrue(script.contains("\"QUJD\""));
        assertTrue(script.contains("\"REVG\""));
    }

    /**
     * 验证纯文本场景不会误派发图片事件。
     * 该断言用于保护现有纯文本粘贴路径，避免修复图文消息后引入文本粘贴回退。
     */
    @Test
    public void buildPasteScriptOmitsImageDispatchForPlainTextOnly() throws Exception {
        Method method = ChatPasteAction.class.getDeclaredMethod(
                "buildPasteScript",
                String.class,
                String.class
        );
        method.setAccessible(true);

        String script = (String) method.invoke(null, "hello", null);

        assertNotNull(script);
        assertTrue(!script.contains("java-paste-image"));
        assertTrue(script.contains("var txt="));
    }

    /**
     * 验证当剪贴板返回的 `imageFlavor` 不是 `BufferedImage` 而是普通 `Image` 实现时，
     * 仍然可以被统一转成 PNG base64，而不是因为强转失败导致整次粘贴链路静默失效。
     */
    @Test
    public void encodeClipboardImageSupportsGenericAwtImage() {
        int[] pixels = new int[] {0xFFFF0000};
        Image genericImage = Toolkit.getDefaultToolkit().createImage(
                new MemoryImageSource(1, 1, pixels, 0, 1)
        );

        String encoded = ChatPasteAction.encodeClipboardImage(genericImage);

        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
    }
}
