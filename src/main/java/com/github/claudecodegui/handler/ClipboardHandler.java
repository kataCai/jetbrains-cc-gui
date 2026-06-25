package com.github.claudecodegui.handler;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 聊天窗口剪贴板处理器。
 * 在保留原有纯文本复制能力的前提下，新增单图复制与富内容复制，统一承接 webview 的系统剪贴板桥接请求。
 */
public class ClipboardHandler extends BaseMessageHandler {

    private static final Logger LOG = Logger.getInstance(ClipboardHandler.class);
    private static final Gson GSON = new Gson();
    private static final String[] SUPPORTED_TYPES = {
            "read_clipboard",
            "write_clipboard",
            "write_clipboard_image",
            "write_clipboard_rich"
    };
    private static final DataFlavor HTML_FLAVOR = createHtmlFlavor();
    public static final DataFlavor RICH_JSON_FLAVOR = createRichJsonFlavor();

    private static final long MIN_READ_INTERVAL_MS = 200;
    private static final int MAX_CLIPBOARD_TEXT_SIZE = 10 * 1024 * 1024;
    private static final int MAX_CLIPBOARD_IMAGE_BASE64_SIZE = 25 * 1024 * 1024;
    private static final int MAX_CLIPBOARD_HTML_SIZE = 12 * 1024 * 1024;

    private volatile long lastReadTime = 0;

    /**
     * 剪贴板图片负载。
     * data 为不带 data URL 头的 base64 文本，mediaType 目前前端统一归一到 image/png。
     */
    public static class ClipboardImagePayload {
        public String data;
        public String mediaType;
        public String fileName;
    }

    /**
     * 富剪贴板顺序块。
     * 用于描述文本块与图片块在原始消息中的先后关系，便于聊天输入框后续按需恢复更细粒度的图文顺序。
     */
    public static class ClipboardRichBlock {
        public String type;
        public String text;
        public Integer imageIndex;
    }

    /**
     * 富剪贴板负载。
     * text 用于纯文本目标回退，html 用于保留图文结构，image 用于兼容支持原生图片粘贴的目标应用。
     */
    public static class ClipboardRichPayload {
        public String text;
        public String html;
        public ClipboardImagePayload image;
        public ClipboardImagePayload[] images;
        public ClipboardRichBlock[] orderedBlocks;
    }

    /**
     * 支持 stringFlavor、imageFlavor 与 text/html 的富剪贴板对象。
     * 某些目标应用只读取其中一种 flavor，因此这里尽量同时暴露多种内容表达。
     */
    static class RichClipboardTransferable implements Transferable {
        private final String text;
        private final String html;
        private final Image image;
        private final String richJson;
        private final DataFlavor[] flavors;

        RichClipboardTransferable(String text, String html, Image image, String richJson) {
            this.text = text;
            this.html = html;
            this.image = image;
            this.richJson = richJson;

            DataFlavor[] supported = new DataFlavor[]{
                    text != null ? DataFlavor.stringFlavor : null,
                    html != null ? HTML_FLAVOR : null,
                    image != null ? DataFlavor.imageFlavor : null,
                    richJson != null ? RICH_JSON_FLAVOR : null
            };
            this.flavors = Arrays.stream(supported).filter(flavor -> flavor != null).toArray(DataFlavor[]::new);
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return flavors.clone();
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            for (DataFlavor supportedFlavor : flavors) {
                if (supportedFlavor.equals(flavor)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (DataFlavor.stringFlavor.equals(flavor) && text != null) {
                return text;
            }
            if (HTML_FLAVOR.equals(flavor) && html != null) {
                return html;
            }
            if (DataFlavor.imageFlavor.equals(flavor) && image != null) {
                return image;
            }
            if (RICH_JSON_FLAVOR.equals(flavor) && richJson != null) {
                return richJson;
            }
            throw new UnsupportedFlavorException(flavor);
        }
    }

    public ClipboardHandler(HandlerContext context) {
        super(context);
    }

    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    @Override
    public boolean handle(String type, String content) {
        return switch (type) {
            case "read_clipboard" -> {
                handleReadClipboard();
                yield true;
            }
            case "write_clipboard" -> {
                handleWriteClipboard(content);
                yield true;
            }
            case "write_clipboard_image" -> {
                handleWriteClipboardImage(content);
                yield true;
            }
            case "write_clipboard_rich" -> {
                handleWriteClipboardRich(content);
                yield true;
            }
            default -> false;
        };
    }

    private void handleReadClipboard() {
        long now = System.currentTimeMillis();
        if (now - lastReadTime < MIN_READ_INTERVAL_MS) {
            LOG.debug("Clipboard read rate-limited");
            callJavaScript("window.onClipboardRead", "");
            return;
        }
        lastReadTime = now;

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                    String text = (String) clipboard.getData(DataFlavor.stringFlavor);
                    callJavaScript("window.onClipboardRead", escapeJs(text != null ? text : ""));
                } else {
                    callJavaScript("window.onClipboardRead", "");
                }
            } catch (Exception e) {
                LOG.warn("Failed to read clipboard", e);
                callJavaScript("window.onClipboardRead", "");
            }
        }, ModalityState.any());
    }

    /**
     * 处理纯文本复制请求。
     *
     * @param content 纯文本内容
     */
    private void handleWriteClipboard(String content) {
        if (content != null && content.length() > MAX_CLIPBOARD_TEXT_SIZE) {
            LOG.warn("Clipboard text write rejected: content too large (" + content.length() + " chars)");
            return;
        }
        writeClipboardContents(new StringSelection(content != null ? content : ""));
    }

    /**
     * 处理单图复制请求。
     *
     * @param content 前端传入的单图 JSON 负载
     */
    private void handleWriteClipboardImage(String content) {
        ClipboardImagePayload payload = parseClipboardImagePayload(content);
        if (payload == null) {
            return;
        }

        if (!validateImagePayload(payload)) {
            return;
        }

        BufferedImage image = decodeClipboardImage(payload);
        if (image == null) {
            return;
        }

        writeClipboardContents(new RichClipboardTransferable(null, null, image, null));
    }

    /**
     * 处理富内容复制请求。
     *
     * @param content 前端传入的 text/html/image 组合 JSON 负载
     */
    private void handleWriteClipboardRich(String content) {
        ClipboardRichPayload payload = parseClipboardRichPayload(content);
        if (payload == null) {
            return;
        }

        if (!validateRichPayload(payload)) {
            return;
        }

        ClipboardImagePayload imagePayload = payload.image != null
                ? payload.image
                : (payload.images != null && payload.images.length > 0 ? payload.images[0] : null);
        BufferedImage image = imagePayload != null ? decodeClipboardImage(imagePayload) : null;
        Transferable transferable = createTransferable(payload, image);
        if (transferable == null) {
            LOG.warn("Clipboard rich write skipped: no valid flavor available");
            return;
        }

        writeClipboardContents(transferable);
    }

    /**
     * 在 EDT 上写入系统剪贴板，避免阻塞 CEF 线程，并兼容模态弹窗期间的复制请求。
     *
     * @param transferable 实际写入系统剪贴板的内容对象
     */
    private void writeClipboardContents(Transferable transferable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(transferable, null);
            } catch (Exception e) {
                LOG.warn("Failed to write clipboard", e);
            }
        }, ModalityState.any());
    }

    /**
     * 解析单图剪贴板负载。
     *
     * @param content 原始 JSON 文本
     * @return 解析成功的图片负载；失败时返回 null
     */
    static ClipboardImagePayload parseClipboardImagePayload(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(content, ClipboardImagePayload.class);
        } catch (JsonSyntaxException exception) {
            return null;
        }
    }

    /**
     * 解析富剪贴板负载。
     *
     * @param content 原始 JSON 文本
     * @return 解析成功的富内容负载；失败时返回 null
     */
    public static ClipboardRichPayload parseClipboardRichPayload(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        try {
            return GSON.fromJson(content, ClipboardRichPayload.class);
        } catch (JsonSyntaxException exception) {
            return null;
        }
    }

    /**
     * 校验单图负载边界，防止超大 base64 直接进入 AWT 解码造成 UI 卡顿或 OOM。
     *
     * @param payload 单图负载
     * @return 负载是否合法
     */
    static boolean validateImagePayload(ClipboardImagePayload payload) {
        return payload != null
                && payload.data != null
                && !payload.data.isBlank()
                && payload.data.length() <= MAX_CLIPBOARD_IMAGE_BASE64_SIZE;
    }

    /**
     * 校验富内容负载边界。
     *
     * @param payload 富内容负载
     * @return 是否允许继续写入系统剪贴板
     */
    static boolean validateRichPayload(ClipboardRichPayload payload) {
        if (payload == null) {
            return false;
        }
        boolean hasText = payload.text != null && !payload.text.isBlank();
        boolean hasHtml = payload.html != null && !payload.html.isBlank();
        boolean hasImage = payload.image != null && validateImagePayload(payload.image);
        boolean hasImages = payload.images != null
                && Arrays.stream(payload.images).anyMatch(ClipboardHandler::validateImagePayload);
        if (payload.text != null && payload.text.length() > MAX_CLIPBOARD_TEXT_SIZE) {
            return false;
        }
        if (payload.html != null && payload.html.length() > MAX_CLIPBOARD_HTML_SIZE) {
            return false;
        }
        return hasText || hasHtml || hasImage || hasImages;
    }

    /**
     * 将 base64 图片解码为 BufferedImage。
     * 当前前端已尽量把图片归一为 PNG，因此这里直接用 Toolkit 创建 AWT Image，再绘制成 BufferedImage。
     *
     * @param payload 图片负载
     * @return 可写入剪贴板的 BufferedImage；失败时返回 null
     */
    static BufferedImage decodeClipboardImage(ClipboardImagePayload payload) {
        if (!validateImagePayload(payload)) {
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(payload.data.getBytes(StandardCharsets.UTF_8));
            Image awtImage = Toolkit.getDefaultToolkit().createImage(bytes);
            MediaTracker tracker = new MediaTracker(new Canvas());
            tracker.addImage(awtImage, 0);
            tracker.waitForID(0);
            if (tracker.isErrorID(0)) {
                return null;
            }

            int width = awtImage.getWidth(null);
            int height = awtImage.getHeight(null);
            if (width <= 0 || height <= 0) {
                return null;
            }

            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = bufferedImage.createGraphics();
            try {
                graphics.drawImage(awtImage, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return bufferedImage;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * 根据富内容负载构建可写入系统剪贴板的 Transferable。
     *
     * @param payload 富内容负载
     * @param image 已解码图片，可为空
     * @return 对应的 Transferable；若仅有纯文本则退回 StringSelection
     */
    static Transferable createTransferable(ClipboardRichPayload payload, BufferedImage image) {
        if (payload == null) {
            return null;
        }
        String text = payload.text != null && !payload.text.isBlank() ? payload.text : null;
        String html = payload.html != null && !payload.html.isBlank() ? payload.html : null;
        boolean hasStructuredRichContent = image != null
                || html != null
                || (payload.images != null && payload.images.length > 0)
                || (payload.orderedBlocks != null && payload.orderedBlocks.length > 0);
        String richJson = hasStructuredRichContent ? GSON.toJson(payload) : null;
        if (html == null && image == null && text != null && richJson == null) {
            return new StringSelection(text);
        }
        if (text == null && html == null && image == null && richJson == null) {
            return null;
        }
        return new RichClipboardTransferable(text, html, image, richJson);
    }

    private static DataFlavor createHtmlFlavor() {
        try {
            return new DataFlavor("text/html;class=java.lang.String");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to create HTML data flavor", exception);
        }
    }

    private static DataFlavor createRichJsonFlavor() {
        try {
            return new DataFlavor("application/x-claudecodegui-rich+json;class=java.lang.String");
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Failed to create rich clipboard data flavor", exception);
        }
    }
}
