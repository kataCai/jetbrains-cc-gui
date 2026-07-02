package com.github.claudecodegui.handler.history;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * HistoryMessageInjector 历史快照签名测试。
 * 该测试验证历史消息里包含图片块时，后端生成的快照签名足够稳定，
 * 可以被前端用于判定“这是同一份历史恢复快照”并忽略重复注入。
 */
public class HistoryMessageInjectorSnapshotSignatureTest {

    /**
     * 验证同语义的图片历史消息即使对象实例不同，也会得到相同签名。
     * 这样重复恢复同一份历史快照时，前端才能稳定命中幂等保护。
     */
    @Test
    public void shouldBuildStableSnapshotSignatureForEquivalentImageHistoryMessages() {
        String signatureA = HistoryMessageInjector.buildFrontendSnapshotSignature(List.of(createImageUserMessage(
                "Paste images",
                "2026-06-29T12:00:00.000Z",
                "diagram.png",
                "image/png",
                "data:image/png;base64,AAAA"
        )));
        String signatureB = HistoryMessageInjector.buildFrontendSnapshotSignature(List.of(createImageUserMessage(
                "Paste images",
                "2026-06-29T12:00:00.000Z",
                "diagram.png",
                "image/png",
                "data:image/png;base64,AAAA"
        )));

        assertEquals(signatureA, signatureB);
    }

    /**
     * 验证图片块关键字段发生变化时，快照签名也会同步变化。
     * 这样前端只会忽略真正的重复快照，不会把不同图片内容误判成同一份恢复结果。
     */
    @Test
    public void shouldDifferentiateSnapshotSignatureWhenImagePayloadChanges() {
        String originalSignature = HistoryMessageInjector.buildFrontendSnapshotSignature(List.of(createImageUserMessage(
                "Paste images",
                "2026-06-29T12:00:00.000Z",
                "diagram.png",
                "image/png",
                "data:image/png;base64,AAAA"
        )));
        String changedSignature = HistoryMessageInjector.buildFrontendSnapshotSignature(List.of(createImageUserMessage(
                "Paste images",
                "2026-06-29T12:00:00.000Z",
                "diagram.png",
                "image/png",
                "data:image/png;base64,BBBB"
        )));

        assertNotEquals(originalSignature, changedSignature);
    }

    /**
     * 构造带图片块的前端用户消息。
     * 该结构与 Codex 历史增强恢复后的最终注入格式保持一致，便于直接覆盖真实签名路径。
     *
     * @param content 文本内容
     * @param timestamp 时间戳
     * @param fileName 图片文件名
     * @param mediaType 图片媒体类型
     * @param src 图片数据源
     * @return 前端消息对象
     */
    private static JsonObject createImageUserMessage(
            String content,
            String timestamp,
            String fileName,
            String mediaType,
            String src
    ) {
        JsonObject message = new JsonObject();
        message.addProperty("type", "user");
        message.addProperty("content", content);
        message.addProperty("timestamp", timestamp);

        JsonObject raw = new JsonObject();
        JsonObject innerMessage = new JsonObject();
        JsonArray contentBlocks = new JsonArray();

        JsonObject imageBlock = new JsonObject();
        imageBlock.addProperty("type", "image");
        imageBlock.addProperty("src", src);
        imageBlock.addProperty("mediaType", mediaType);
        imageBlock.addProperty("alt", fileName);
        contentBlocks.add(imageBlock);

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", content);
        contentBlocks.add(textBlock);

        innerMessage.add("content", contentBlocks);
        raw.add("message", innerMessage);
        message.add("raw", raw);
        return message;
    }
}
