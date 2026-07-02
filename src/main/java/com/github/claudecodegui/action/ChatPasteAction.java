package com.github.claudecodegui.action;

import com.github.claudecodegui.handler.ClipboardHandler;
import com.github.claudecodegui.ui.toolwindow.ClaudeChatWindow;
import com.google.gson.Gson;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * Claude 聊天窗口里的粘贴动作。
 *
 * 该动作负责从系统剪贴板读取文本和图片，并把可识别内容注入到 webview。
 * 这里既要兼容传统纯文本粘贴，也要兼容系统或第三方应用放入剪贴板的原生图片对象。
 */
public class ChatPasteAction extends ChatToolWindowAction {

    private static final Logger LOG = Logger.getInstance(ChatPasteAction.class);
    private static final Gson GSON = new Gson();
    private static final String IMAGE_MEDIA_TYPE = "image/png";

    @Override
    protected void performAction(@NotNull AnActionEvent e, @NotNull Project project, @NotNull ClaudeChatWindow chatWindow) {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            LOG.info("[RichPaste][BridgeRead] Clipboard flavors: " + java.util.Arrays.toString(clipboard.getAvailableDataFlavors()));
            if (clipboard.isDataFlavorAvailable(ClipboardHandler.RICH_JSON_FLAVOR)) {
                Object richData = clipboard.getData(ClipboardHandler.RICH_JSON_FLAVOR);
                if (richData instanceof String richJson) {
                    ClipboardHandler.ClipboardRichPayload richPayload = ClipboardHandler.parseClipboardRichPayload(richJson);
                    if (richPayload != null) {
                        LOG.info("[RichPaste][BridgeRead] Using rich clipboard flavor: textLength="
                                + (richPayload.text != null ? richPayload.text.length() : 0)
                                + ", imageCount=" + (richPayload.images != null ? richPayload.images.length : (richPayload.image != null ? 1 : 0))
                                + ", orderedBlockCount=" + (richPayload.orderedBlocks != null ? richPayload.orderedBlocks.length : 0));
                        chatWindow.executeJavaScriptCode(buildRichPasteScript(richPayload));
                        return;
                    }
                }
            }

            String text = "";
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                text = (String) clipboard.getData(DataFlavor.stringFlavor);
                if (text == null) {
                    text = "";
                }
            }

            String imageBase64 = null;
            if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                Object imageData = clipboard.getData(DataFlavor.imageFlavor);
                if (imageData instanceof Image image) {
                    imageBase64 = encodeClipboardImage(image);
                }
            }

            if (text.isEmpty() && imageBase64 == null) {
                return;
            }

            LOG.info("[RichPaste][BridgeRead] Falling back to basic clipboard paste: textLength="
                    + text.length() + ", hasImage=" + (imageBase64 != null));
            chatWindow.executeJavaScriptCode(buildPasteScript(text, imageBase64));
        } catch (Exception ex) {
            LOG.warn("Failed to read clipboard for paste action", ex);
        }
    }

    /**
     * 将系统剪贴板中的原生图片编码为 PNG base64。
     *
     * 这里不再假设 `imageFlavor` 一定返回 `BufferedImage`，而是统一接收 AWT `Image`。
     * 对非 `BufferedImage` 的实现会先绘制到新的 `BufferedImage`，避免因为类型强转失败导致整次粘贴静默失效。
     *
     * @param image 系统剪贴板中的原生图片
     * @return PNG base64；若图片为空、加载失败或编码失败则返回 null
     */
    static String encodeClipboardImage(Image image) {
        BufferedImage bufferedImage = toBufferedImage(image);
        if (bufferedImage == null) {
            return null;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "png", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception exception) {
            LOG.warn("Failed to encode clipboard image", exception);
            return null;
        }
    }

    /**
     * 将任意 AWT 图片归一化为 `BufferedImage`。
     *
     * 该方法用于兼容浏览器、截图工具或操作系统返回的非 `BufferedImage` 图片对象。
     * 如果图片尚未完全加载，这里会通过 `MediaTracker` 等待加载完成后再绘制。
     *
     * @param image 原始 AWT 图片对象
     * @return 可供后续 PNG 编码的 `BufferedImage`；若图片无效或加载失败则返回 null
     */
    static BufferedImage toBufferedImage(Image image) {
        if (image == null) {
            return null;
        }
        if (image instanceof BufferedImage bufferedImage) {
            return bufferedImage;
        }

        try {
            MediaTracker tracker = new MediaTracker(new java.awt.Canvas());
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

            BufferedImage bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = bufferedImage.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            return bufferedImage;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 构建统一的粘贴脚本。
     * 当文本与图片同时存在时，会先派发图片事件，再继续执行现有文本插入逻辑，
     * 从而保证图文消息回贴到聊天输入框时，图片不会被纯文本分支短路。
     *
     * @param text 剪贴板中的文本内容；允许为空字符串
     * @param imageBase64 剪贴板中的 PNG base64；无图片时为 null
     * @return 可直接注入 webview 的脚本片段
     */
    static String buildPasteScript(String text, String imageBase64) {
        String safeText = text != null ? text : "";
        String jsonEncoded = GSON.toJson(safeText);
        String jsonImage = imageBase64 != null ? GSON.toJson(imageBase64) : null;
        StringBuilder script = new StringBuilder("(function(){");

        if (jsonImage != null) {
            script.append("  window.dispatchEvent(new CustomEvent('java-paste-image',{detail:{base64:")
                    .append(jsonImage)
                    .append(",mediaType:'")
                    .append(IMAGE_MEDIA_TYPE)
                    .append("'}}));");
        }

        if (!safeText.isEmpty()) {
            script.append("  var txt=").append(jsonEncoded).append(";")
                    .append("  var el=document.activeElement;")
                    .append("  if(el&&el.getAttribute('contenteditable')==='true'){")
                    .append("    document.execCommand('insertText',false,txt);")
                    .append("  } else if(el&&(el.tagName==='INPUT'||el.tagName==='TEXTAREA')){")
                    .append("    var s=el.selectionStart,e=el.selectionEnd;")
                    .append("    el.setRangeText(txt,s,e,'end');")
                    .append("    el.dispatchEvent(new Event('input',{bubbles:true}));")
                    .append("  } else if(window.onClipboardRead){")
                    .append("    var cb=window.onClipboardRead;")
                    .append("    window.onClipboardRead=undefined;")
                    .append("    cb(txt);")
                    .append("  }");
        }

        script.append("})()");
        return script.toString();
    }

    /**
     * 构建富剪贴板统一粘贴脚本。
     * 当系统剪贴板中携带的是插件自己写入的图文 JSON 负载时，优先派发单一 `java-paste-rich-content` 事件，
     * 由前端输入框一次性恢复文本与多张图片附件，避免旧协议只能恢复首图。
     *
     * @param payload 插件写入系统剪贴板的富剪贴板负载
     * @return 可直接注入 webview 的脚本片段
     */
    static String buildRichPasteScript(ClipboardHandler.ClipboardRichPayload payload) {
        String jsonPayload = GSON.toJson(payload != null ? payload : new ClipboardHandler.ClipboardRichPayload());
        return "(function(){window.dispatchEvent(new CustomEvent('java-paste-rich-content',{detail:"
                + jsonPayload
                + "}));})()";
    }
}
