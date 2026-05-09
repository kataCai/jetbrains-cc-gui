package com.github.claudecodegui.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Message merger.
 * Merges streaming assistant messages, ensuring previously displayed tool steps are not overwritten.
 */
public class MessageMerger {

    /**
     * Merge streaming assistant messages.
     */
    public JsonObject mergeAssistantMessage(JsonObject existingRaw, JsonObject newRaw) {
        if (newRaw == null) {
            return existingRaw != null ? existingRaw.deepCopy() : null;
        }

        if (existingRaw == null) {
            return newRaw.deepCopy();
        }

        JsonObject merged = existingRaw.deepCopy();

        // Merge top-level fields (except "message")
        for (Map.Entry<String, JsonElement> entry : newRaw.entrySet()) {
            if ("message".equals(entry.getKey())) {
                continue;
            }
            merged.add(entry.getKey(), entry.getValue());
        }

        JsonObject incomingMessage = newRaw.has("message") && newRaw.get("message").isJsonObject()
            ? newRaw.getAsJsonObject("message")
            : null;

        if (incomingMessage == null) {
            return merged;
        }

        JsonObject mergedMessage = merged.has("message") && merged.get("message").isJsonObject()
            ? merged.getAsJsonObject("message")
            : new JsonObject();

        // Copy new metadata (keep latest stop_reason, usage, etc.)
        for (Map.Entry<String, JsonElement> entry : incomingMessage.entrySet()) {
            if ("content".equals(entry.getKey())) {
                continue;
            }
            mergedMessage.add(entry.getKey(), entry.getValue());
        }

        mergeAssistantContentArray(mergedMessage, incomingMessage);
        merged.add("message", mergedMessage);
        return merged;
    }

    /**
     * Merge the content array of assistant messages.
     */
    private void mergeAssistantContentArray(JsonObject targetMessage, JsonObject incomingMessage) {
        JsonArray baseContent = targetMessage.has("content") && targetMessage.get("content").isJsonArray()
            ? targetMessage.getAsJsonArray("content")
            : new JsonArray();

        Map<String, Integer> indexByKey = buildContentIndex(baseContent);

        JsonArray incomingContent = incomingMessage.has("content") && incomingMessage.get("content").isJsonArray()
            ? incomingMessage.getAsJsonArray("content")
            : null;

        if (incomingContent == null) {
            targetMessage.add("content", baseContent);
            return;
        }

        for (int i = 0; i < incomingContent.size(); i++) {
            JsonElement element = incomingContent.get(i);
            JsonElement elementCopy = element.deepCopy();

            if (element.isJsonObject()) {
                JsonObject block = element.getAsJsonObject();
                String key = getContentBlockKey(block);
                if (key != null && indexByKey.containsKey(key)) {
                    int idx = indexByKey.get(key);
                    baseContent.set(idx, elementCopy);
                    continue;
                } else if (key != null) {
                    baseContent.add(elementCopy);
                    indexByKey.put(key, baseContent.size() - 1);
                    continue;
                } else if (shouldReplacePlainContentBlock(baseContent, i, block)) {
                    baseContent.set(i, elementCopy);
                    continue;
                }
            }

            baseContent.add(elementCopy);
        }

        targetMessage.add("content", baseContent);
    }

    /**
     * Build an index of content blocks by their unique keys.
     */
    private Map<String, Integer> buildContentIndex(JsonArray contentArray) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < contentArray.size(); i++) {
            JsonElement element = contentArray.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject block = element.getAsJsonObject();
            String key = getContentBlockKey(block);
            if (key != null && !index.containsKey(key)) {
                index.put(key, i);
            }
        }
        return index;
    }

    /**
     * Get the unique key for a content block.
     */
    private String getContentBlockKey(JsonObject block) {
        if (block.has("id") && !block.get("id").isJsonNull()) {
            return block.get("id").getAsString();
        }

        if (block.has("tool_use_id") && !block.get("tool_use_id").isJsonNull()) {
            return "tool_result:" + block.get("tool_use_id").getAsString();
        }

        return null;
    }

    /**
     * 判断当前无唯一 key 的 block 是否应按位置替换旧 block。
     * 仅对 text/thinking 这类流式 phase block 生效，用于避免完整 snapshot
     * 回放时在 tool_use 边界前后重复追加同一语义位置的文本。
     *
     * @param baseContent 旧 content 数组
     * @param incomingIndex 新 block 在 incoming content 中的位置
     * @param incomingBlock 新 block
     * @return true 表示按位置替换，false 表示走原有追加逻辑
     */
    private boolean shouldReplacePlainContentBlock(JsonArray baseContent, int incomingIndex, JsonObject incomingBlock) {
        if (incomingIndex < 0 || incomingIndex >= baseContent.size()) {
            return false;
        }
        if (!baseContent.get(incomingIndex).isJsonObject()) {
            return false;
        }

        String incomingType = getBlockType(incomingBlock);
        if (!"text".equals(incomingType) && !"thinking".equals(incomingType)) {
            return false;
        }

        JsonObject existingBlock = baseContent.get(incomingIndex).getAsJsonObject();
        if (getContentBlockKey(existingBlock) != null) {
            return false;
        }

        return incomingType.equals(getBlockType(existingBlock));
    }

    /**
     * 读取 block.type，缺失时返回 null。
     *
     * @param block content block
     * @return type 字段值
     */
    private String getBlockType(JsonObject block) {
        if (block == null || !block.has("type") || block.get("type").isJsonNull()) {
            return null;
        }
        return block.get("type").getAsString();
    }
}
