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
            "read_clipboard_rich",
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
        public String requestId;
        public String trigger;
        public String source;
        public String text;
        public String html;
        public ClipboardImagePayload image;
        public ClipboardImagePayload[] images;
        public ClipboardRichBlock[] orderedBlocks;
    }

    /**
     * rich clipboard 读取请求元信息。
     * 当前主要用于把键盘粘贴 requestId 透传回前端，并在日志里区分不同粘贴入口。
     */
    static class ReadClipboardRichRequest {
        public String requestId;
        public String trigger;
        public String[] nativeItemTypes;
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
            case "read_clipboard_rich" -> {
                handleReadClipboardRich(content);
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
     * 读取系统剪贴板中的富内容，并统一派发到前端 `java-paste-rich-content` 恢复链路。
     * 关键约束：
     * 1. 优先消费插件自己写入的 `RICH_JSON_FLAVOR`，确保多图与 `orderedBlocks` 不丢失；
     * 2. 若系统剪贴板只有原生文本/HTML/单图，也统一包装成富负载，避免右键 Paste 退回纯文本协议；
     * 3. 当剪贴板无可恢复内容或读取失败时，只记录日志，不再依赖旧的 `window.onClipboardRead` 回调。
     */
    private void handleReadClipboardRich(String content) {
        long now = System.currentTimeMillis();
        if (now - lastReadTime < MIN_READ_INTERVAL_MS) {
            LOG.debug("Rich clipboard read rate-limited");
            return;
        }
        lastReadTime = now;
        ReadClipboardRichRequest request = parseReadClipboardRichRequest(content);

        ApplicationManager.getApplication().invokeLater(() -> {
            try {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                String trigger = request != null && request.trigger != null && !request.trigger.isBlank()
                        ? request.trigger
                        : "context-menu";
                LOG.info("[RichPaste][BridgeRead] " + trigger + " requested rich clipboard, flavors="
                        + Arrays.toString(clipboard.getAvailableDataFlavors())
                        + ", requestId=" + (request != null ? request.requestId : null)
                        + ", nativeItemTypes=" + Arrays.toString(
                        request != null && request.nativeItemTypes != null ? request.nativeItemTypes : new String[0]
                ));

                ClipboardRichPayload payload = readClipboardRichPayload(clipboard);
                if (payload == null) {
                    LOG.info("[RichPaste][BridgeRead] Skip rich clipboard paste because no supported flavor was available");
                    return;
                }
                if (request != null) {
                    payload.requestId = request.requestId;
                    payload.trigger = request.trigger;
                }

                LOG.info("[RichPaste][BridgeRead] Dispatch rich clipboard payload: textLength="
                        + (payload.text != null ? payload.text.length() : 0)
                        + ", htmlLength=" + (payload.html != null ? payload.html.length() : 0)
                        + ", imageCount=" + (payload.images != null ? payload.images.length : (payload.image != null ? 1 : 0))
                        + ", orderedBlockCount=" + (payload.orderedBlocks != null ? payload.orderedBlocks.length : 0)
                        + ", source=" + payload.source
                        + ", requestId=" + payload.requestId
                        + ", trigger=" + payload.trigger);
                executeJavaScript(buildRichPasteDispatchScript(payload));
            } catch (Exception e) {
                LOG.warn("Failed to read rich clipboard", e);
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

        LOG.info("[RichCopy][BridgeWrite] Parsed rich clipboard payload: textLength="
                + (payload.text != null ? payload.text.length() : 0)
                + ", htmlLength=" + (payload.html != null ? payload.html.length() : 0)
                + ", imageCount=" + (payload.images != null ? payload.images.length : (payload.image != null ? 1 : 0))
                + ", orderedBlockCount=" + (payload.orderedBlocks != null ? payload.orderedBlocks.length : 0));

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
            LOG.info("[RichCopy][BridgeWrite] createTransferable fallback to StringSelection");
            return new StringSelection(text);
        }
        if (text == null && html == null && image == null && richJson == null) {
            return null;
        }
        LOG.info("[RichCopy][BridgeWrite] createTransferable rich flavors: hasText=" + (text != null)
                + ", hasHtml=" + (html != null)
                + ", hasImageFlavor=" + (image != null)
                + ", hasRichJson=" + (richJson != null));
        return new RichClipboardTransferable(text, html, image, richJson);
    }

    /**
     * 解析 rich clipboard 读取请求元信息。
     * 当调用方仍沿用旧的空字符串协议时，返回空请求对象，保持向后兼容。
     *
     * @param content 原始桥接内容
     * @return 解析后的读取请求元信息；格式非法时返回空请求对象
     */
    static ReadClipboardRichRequest parseReadClipboardRichRequest(String content) {
        if (content == null || content.trim().isEmpty()) {
            return new ReadClipboardRichRequest();
        }
        try {
            ReadClipboardRichRequest request = GSON.fromJson(content, ReadClipboardRichRequest.class);
            return request != null ? request : new ReadClipboardRichRequest();
        } catch (JsonSyntaxException exception) {
            return new ReadClipboardRichRequest();
        }
    }

    /**
     * 从系统剪贴板读取统一的富负载。
     * 优先顺序：
     * 1. 插件自定义 richJson；
     * 2. 原生文本/HTML/图片 flavor；
     * 3. 若没有任一可恢复内容，则返回 null。
     *
     * @param clipboard 系统剪贴板
     * @return 可交给前端统一恢复的富剪贴板负载；无内容时返回 null
     */
    private ClipboardRichPayload readClipboardRichPayload(Clipboard clipboard) throws Exception {
        if (clipboard.isDataFlavorAvailable(RICH_JSON_FLAVOR)) {
            Object richData = clipboard.getData(RICH_JSON_FLAVOR);
            if (richData instanceof String richJson) {
                ClipboardRichPayload payload = parseClipboardRichPayload(richJson);
                if (payload != null) {
                    payload.source = "rich-json";
                    LOG.info("[RichPaste][BridgeRead] Resolved clipboard payload from RICH_JSON_FLAVOR");
                    return payload;
                }
            }
        }

        ClipboardRichPayload payload = new ClipboardRichPayload();
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
            Object textData = clipboard.getData(DataFlavor.stringFlavor);
            if (textData instanceof String text && !text.isEmpty()) {
                payload.text = text;
            }
        }

        if (clipboard.isDataFlavorAvailable(HTML_FLAVOR)) {
            Object htmlData = clipboard.getData(HTML_FLAVOR);
            if (htmlData instanceof String html && !html.isEmpty()) {
                payload.html = html;
            }
        }

        if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
            Object imageData = clipboard.getData(DataFlavor.imageFlavor);
            if (imageData instanceof Image image) {
                ClipboardImagePayload imagePayload = encodeClipboardImagePayload(image);
                if (imagePayload != null) {
                    payload.image = imagePayload;
                    payload.images = new ClipboardImagePayload[]{imagePayload};
                }
            }
        }

        if ((payload.text == null || payload.text.isBlank())
                && (payload.html == null || payload.html.isBlank())
                && payload.image == null
                && (payload.images == null || payload.images.length == 0)) {
            return null;
        }

        payload.orderedBlocks = buildFallbackOrderedBlocks(payload);
        payload.source = "native-flavor";
        LOG.info("[RichPaste][BridgeRead] Resolved clipboard payload from native flavors");
        return payload;
    }

    /**
     * 把系统原生图片编码为统一的 PNG base64 负载。
     * 右键 Paste 的富恢复协议只需要可回贴的稳定图片字节，不依赖原始平台特定编码。
     *
     * @param image 剪贴板中的原生图片
     * @return 统一的图片负载；编码失败时返回 null
     */
    private ClipboardImagePayload encodeClipboardImagePayload(Image image) {
        if (image == null) {
            return null;
        }

        BufferedImage bufferedImage;
        if (image instanceof BufferedImage readyImage) {
            bufferedImage = readyImage;
        } else {
            try {
                MediaTracker tracker = new MediaTracker(new Canvas());
                tracker.addImage(image, 0);
                tracker.waitForID(0);
                if (tracker.isErrorID(0)) {
                    return null;
                }

                int width = image.getWidth(null);
                int height = image.getHeight(null);
                if (width <= 0 || height <= 0) {
                    return null;
                }

                bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = bufferedImage.createGraphics();
                try {
                    graphics.drawImage(image, 0, 0, null);
                } finally {
                    graphics.dispose();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            javax.imageio.ImageIO.write(bufferedImage, "png", output);
            ClipboardImagePayload payload = new ClipboardImagePayload();
            payload.data = Base64.getEncoder().encodeToString(output.toByteArray());
            payload.mediaType = "image/png";
            payload.fileName = "clipboard-image.png";
            return payload;
        } catch (Exception exception) {
            LOG.warn("Failed to encode clipboard image payload", exception);
            return null;
        }
    }

    /**
     * 为原生系统剪贴板构造最小可用的顺序块。
     * 当系统只暴露单图 flavor 时，无法可靠恢复原始图文交错顺序，
     * 这里保底保留“文本在前、图片在后”的稳定恢复协议，避免再次退回到文本专用链路。
     *
     * @param payload 剪贴板负载
     * @return 兜底顺序块数组；若负载为空则返回 null
     */
    private ClipboardRichBlock[] buildFallbackOrderedBlocks(ClipboardRichPayload payload) {
        if (payload == null) {
            return null;
        }

        int imageCount = payload.images != null ? payload.images.length : (payload.image != null ? 1 : 0);
        boolean hasText = payload.text != null && !payload.text.isBlank();
        if (!hasText && imageCount <= 0) {
            return null;
        }

        int blockCount = (hasText ? 1 : 0) + imageCount;
        ClipboardRichBlock[] blocks = new ClipboardRichBlock[blockCount];
        int index = 0;
        if (hasText) {
            ClipboardRichBlock textBlock = new ClipboardRichBlock();
            textBlock.type = "text";
            textBlock.text = payload.text;
            blocks[index++] = textBlock;
        }
        for (int imageIndex = 0; imageIndex < imageCount; imageIndex++) {
            ClipboardRichBlock imageBlock = new ClipboardRichBlock();
            imageBlock.type = "image";
            imageBlock.imageIndex = imageIndex;
            blocks[index++] = imageBlock;
        }
        return blocks;
    }

    /**
     * 构造前端统一富粘贴事件脚本。
     * 右键 Paste 与 IDE 粘贴动作都应尽量走同一 `java-paste-rich-content` 事件，
     * 由输入框侧复用统一的图文恢复逻辑，而不是继续分叉到文本专用桥接。
     *
     * @param payload 富剪贴板负载
     * @return 可直接注入 WebView 的脚本文本
     */
    private String buildRichPasteDispatchScript(ClipboardRichPayload payload) {
        String jsonPayload = GSON.toJson(payload != null ? payload : new ClipboardRichPayload());
        return "(function(){window.dispatchEvent(new CustomEvent('java-paste-rich-content',{detail:"
                + jsonPayload
                + "}));})()";
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
