package com.github.claudecodegui.handler;

import org.junit.Test;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ClipboardHandler} 的负载解析与 Transferable 构建测试。
 * 这里只验证纯逻辑边界，不直接触碰系统剪贴板，避免单测依赖桌面环境状态。
 */
public class ClipboardHandlerTest {

    /**
     * 验证单图负载能正确解析基础字段。
     * 该用例覆盖前端 data URL 归一后传入的核心字段映射，避免字段名调整后静默失效。
     */
    @Test
    public void parseClipboardImagePayloadReadsExpectedFields() {
        ClipboardHandler.ClipboardImagePayload payload = ClipboardHandler.parseClipboardImagePayload(
                "{\"data\":\"YWJj\",\"mediaType\":\"image/png\",\"fileName\":\"demo.png\"}"
        );

        assertNotNull(payload);
        assertEquals("YWJj", payload.data);
        assertEquals("image/png", payload.mediaType);
        assertEquals("demo.png", payload.fileName);
    }

    /**
     * 验证富内容负载能同时携带 text、html 与嵌套图片信息。
     * 该场景直接决定消息级复制是否还能把图片一起写入系统剪贴板。
     */
    @Test
    public void parseClipboardRichPayloadReadsNestedImage() {
        ClipboardHandler.ClipboardRichPayload payload = ClipboardHandler.parseClipboardRichPayload(
                "{\"text\":\"hello\",\"html\":\"<p>hello</p>\",\"image\":{\"data\":\"YWJj\",\"mediaType\":\"image/png\"},\"images\":[{\"data\":\"YWJj\",\"mediaType\":\"image/png\"},{\"data\":\"ZGVm\",\"mediaType\":\"image/png\"}],\"orderedBlocks\":[{\"type\":\"text\",\"text\":\"hello\"},{\"type\":\"image\",\"imageIndex\":1}]}"
        );

        assertNotNull(payload);
        assertEquals("hello", payload.text);
        assertEquals("<p>hello</p>", payload.html);
        assertNotNull(payload.image);
        assertEquals("YWJj", payload.image.data);
        assertNotNull(payload.images);
        assertEquals(2, payload.images.length);
        assertNotNull(payload.orderedBlocks);
        assertEquals(2, payload.orderedBlocks.length);
        assertEquals("image", payload.orderedBlocks[1].type);
        assertEquals(Integer.valueOf(1), payload.orderedBlocks[1].imageIndex);
    }

    /**
     * 验证非法 JSON 不会抛异常，而是安全返回 null。
     * 这样桥接层面对异常输入时会直接拒绝写剪贴板，不会影响主线程稳定性。
     */
    @Test
    public void parseInvalidPayloadReturnsNull() {
        assertNull(ClipboardHandler.parseClipboardImagePayload("{not-json"));
        assertNull(ClipboardHandler.parseClipboardRichPayload("{not-json"));
    }

    /**
     * 验证单图负载必须包含非空 base64 数据。
     * 该边界避免空 payload 进入图片解码流程。
     */
    @Test
    public void validateImagePayloadRejectsEmptyData() {
        ClipboardHandler.ClipboardImagePayload payload = new ClipboardHandler.ClipboardImagePayload();
        payload.data = "";
        payload.mediaType = "image/png";

        assertFalse(ClipboardHandler.validateImagePayload(payload));
    }

    /**
     * 验证仅含纯文本的富内容负载会退化为 StringSelection。
     * 这样旧文本复制链路不会因为统一走 rich 接口而改变系统行为。
     */
    @Test
    public void createTransferableFallsBackToStringSelectionForPlainText() {
        ClipboardHandler.ClipboardRichPayload payload = new ClipboardHandler.ClipboardRichPayload();
        payload.text = "plain text";

        Transferable transferable = ClipboardHandler.createTransferable(payload, null);

        assertTrue(transferable instanceof StringSelection);
    }

    /**
     * 验证图文富内容会同时暴露 stringFlavor、text/html 与 imageFlavor。
     * 这是消息级图文复制能在不同目标应用里尽量保留内容结构的关键。
     */
    @Test
    public void createTransferableExposesTextHtmlAndImageFlavors() throws Exception {
        ClipboardHandler.ClipboardRichPayload payload = new ClipboardHandler.ClipboardRichPayload();
        payload.text = "plain text";
        payload.html = "<p>plain text</p>";
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

        Transferable transferable = ClipboardHandler.createTransferable(payload, image);

        assertNotNull(transferable);
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertTrue(transferable.isDataFlavorSupported(DataFlavor.imageFlavor));
        assertTrue(transferable.isDataFlavorSupported(ClipboardHandler.RICH_JSON_FLAVOR));
        assertEquals("plain text", transferable.getTransferData(DataFlavor.stringFlavor));
        assertEquals(image, transferable.getTransferData(DataFlavor.imageFlavor));
    }

    /**
     * 验证右键 Paste 读取系统原生文本剪贴板时，也会被统一包装成 rich payload。
     * 断言意图：
     * 1. 不再退回旧的纯文本 read_clipboard 协议；
     * 2. 至少会补出文本块对应的 `orderedBlocks`，供前端统一走富回贴恢复链路。
     */
    @Test
    public void readClipboardRichPayloadBuildsFallbackOrderedBlocksForPlainTextClipboard() throws Exception {
        Clipboard clipboard = new Clipboard("unit-test");
        clipboard.setContents(new StringSelection("plain text"), null);

        Method method = ClipboardHandler.class.getDeclaredMethod("readClipboardRichPayload", Clipboard.class);
        method.setAccessible(true);

        ClipboardHandler handler = new ClipboardHandler(null);
        ClipboardHandler.ClipboardRichPayload payload =
                (ClipboardHandler.ClipboardRichPayload) method.invoke(handler, clipboard);

        assertNotNull(payload);
        assertEquals("plain text", payload.text);
        assertNotNull(payload.orderedBlocks);
        assertEquals(1, payload.orderedBlocks.length);
        assertEquals("text", payload.orderedBlocks[0].type);
        assertEquals("plain text", payload.orderedBlocks[0].text);
    }
}
