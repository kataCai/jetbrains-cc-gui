package com.github.claudecodegui.session;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
        Set<Integer> consumedUnkeyedIndexes = new HashSet<>();

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
                } else {
                    if (shouldReplacePlainContentBlock(baseContent, i, block)) {
                        baseContent.set(i, mergeUnkeyedBlock(baseContent.get(i).getAsJsonObject(), block));
                        consumedUnkeyedIndexes.add(i);
                        continue;
                    }

                    int idx = findMatchingUnkeyedBlockIndex(baseContent, block, consumedUnkeyedIndexes);
                    if (idx >= 0) {
                        baseContent.set(idx, mergeUnkeyedBlock(baseContent.get(idx).getAsJsonObject(), block));
                        consumedUnkeyedIndexes.add(idx);
                        continue;
                    }

                    // Fallback: merge with last same-type block instead of adding duplicate
                    int lastSameTypeIdx = findLastSameTypeBlockIndex(baseContent, block);
                    if (lastSameTypeIdx >= 0) {
                        baseContent.set(lastSameTypeIdx,
                                mergeUnkeyedBlock(baseContent.get(lastSameTypeIdx).getAsJsonObject(), block));
                        continue;
                    }
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
        if (block.has("id") && block.get("id").isJsonPrimitive()) {
            return block.get("id").getAsString();
        }

        if (block.has("tool_use_id") && block.get("tool_use_id").isJsonPrimitive()) {
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

        String incomingType = getContentBlockType(incomingBlock);
        if (!"text".equals(incomingType) && !"thinking".equals(incomingType)) {
            return false;
        }

        JsonObject existingBlock = baseContent.get(incomingIndex).getAsJsonObject();
        if (getContentBlockKey(existingBlock) != null) {
            return false;
        }

        return incomingType.equals(getContentBlockType(existingBlock));
    }

    /**
     * 查找与 incoming block 表示同一语义 segment 的无 key block。
     * text/thinking 没有稳定 id，只能结合 type、内容包含关系和 overlap 判断。
     *
     * @param baseContent 现有 content 数组
     * @param incomingBlock 新 block
     * @param consumedUnkeyedIndexes 已被本轮 incoming 消耗的位置
     * @return 匹配位置，未找到时返回 -1
     */
    private int findMatchingUnkeyedBlockIndex(
            JsonArray baseContent,
            JsonObject incomingBlock,
            Set<Integer> consumedUnkeyedIndexes
    ) {
        String incomingType = getContentBlockType(incomingBlock);
        if (incomingType == null) {
            return -1;
        }

        for (int i = 0; i < baseContent.size(); i++) {
            if (consumedUnkeyedIndexes.contains(i)) {
                continue;
            }

            JsonElement existingElement = baseContent.get(i);
            if (!existingElement.isJsonObject()) {
                continue;
            }

            JsonObject existingBlock = existingElement.getAsJsonObject();
            if (getContentBlockKey(existingBlock) != null) {
                continue;
            }

            if (!incomingType.equals(getContentBlockType(existingBlock))) {
                continue;
            }

            if (blocksLikelyRepresentSameSegment(existingBlock, incomingBlock)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * 合并无 key block。
     * 对 text/thinking 使用更完整内容，避免 snapshot 回放时短内容覆盖长内容。
     *
     * @param existingBlock 现有 block
     * @param incomingBlock 新 block
     * @return 合并后的 block
     */
    private JsonObject mergeUnkeyedBlock(JsonObject existingBlock, JsonObject incomingBlock) {
        String type = getContentBlockType(incomingBlock);
        JsonObject merged = incomingBlock.deepCopy();

        if ("text".equals(type)) {
            merged.addProperty("text", preferMoreCompleteContent(
                    getTextContent(existingBlock),
                    getTextContent(incomingBlock)
            ));
            return merged;
        }

        if ("thinking".equals(type)) {
            String thinking = preferMoreCompleteContent(
                    getThinkingContent(existingBlock),
                    getThinkingContent(incomingBlock)
            );
            if (thinking != null && !thinking.isEmpty()) {
                merged.addProperty("thinking", thinking);
                merged.addProperty("text", thinking);
            }
        }

        return merged;
    }

    /**
     * 判断两个无 key block 是否可能代表同一段流式内容。
     *
     * @param existingBlock 现有 block
     * @param incomingBlock 新 block
     * @return true 表示可视为同一 segment
     */
    private boolean blocksLikelyRepresentSameSegment(JsonObject existingBlock, JsonObject incomingBlock) {
        String type = getContentBlockType(incomingBlock);
        if (type == null || !type.equals(getContentBlockType(existingBlock))) {
            return false;
        }

        if ("text".equals(type)) {
            return textLooksRelated(getTextContent(existingBlock), getTextContent(incomingBlock));
        }

        if ("thinking".equals(type)) {
            String existingThinking = getThinkingContent(existingBlock);
            String incomingThinking = getThinkingContent(incomingBlock);
            // During early streaming, thinking content may not yet be populated,
            // so type-based matching alone determines block identity.
            if (existingThinking.isEmpty() || incomingThinking.isEmpty()) {
                return true;
            }
            return textLooksRelated(existingThinking, incomingThinking);
        }

        return existingBlock.equals(incomingBlock);
    }

    /**
     * 查找尾部同类型无 key block。
     * 不跨越 tool_use/tool_result 这类有 key 的结构边界，避免工具前后文本被错误合并。
     *
     * @param baseContent 现有 content 数组
     * @param incomingBlock 新 block
     * @return 匹配位置，未找到时返回 -1
     */
    private int findLastSameTypeBlockIndex(JsonArray baseContent, JsonObject incomingBlock) {
        String incomingType = getContentBlockType(incomingBlock);
        if (incomingType == null) {
            return -1;
        }
        // Only consider the tail of baseContent — do not cross keyed blocks
        // (tool_use, tool_result) to avoid merging content from different segments.
        // E.g., [text_1, tool_use, text_2] should NOT merge text_2 into text_1.
        for (int i = baseContent.size() - 1; i >= 0; i--) {
            JsonElement element = baseContent.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject existingBlock = element.getAsJsonObject();
            // Stop scanning if we hit a keyed block (tool_use, tool_result)
            if (getContentBlockKey(existingBlock) != null) {
                break;
            }
            if (incomingType.equals(getContentBlockType(existingBlock))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 读取 content block 类型。
     *
     * @param block content block
     * @return type 字段值，缺失时返回 null
     */
    private String getContentBlockType(JsonObject block) {
        return block.has("type") && !block.get("type").isJsonNull()
                ? block.get("type").getAsString()
                : null;
    }

    /**
     * 读取 text block 文本。
     *
     * @param block content block
     * @return text 字段值，缺失时返回空字符串
     */
    private String getTextContent(JsonObject block) {
        return block.has("text") && !block.get("text").isJsonNull()
                ? block.get("text").getAsString()
                : "";
    }

    /**
     * 读取 thinking block 文本。
     * 兼容部分 SDK 把 thinking 镜像到 text 字段的情况。
     *
     * @param block content block
     * @return thinking 文本，缺失时回退 text
     */
    private String getThinkingContent(JsonObject block) {
        if (block.has("thinking") && !block.get("thinking").isJsonNull()) {
            return block.get("thinking").getAsString();
        }
        return getTextContent(block);
    }

    /**
     * 判断两段文本是否可能属于同一流式 segment。
     *
     * @param existingText 现有文本
     * @param incomingText 新文本
     * @return true 表示存在包含关系或明显 suffix-prefix overlap
     */
    private boolean textLooksRelated(String existingText, String incomingText) {
        String existing = existingText != null ? existingText : "";
        String incoming = incomingText != null ? incomingText : "";

        if (existing.isEmpty() || incoming.isEmpty()) {
            return existing.isEmpty() && incoming.isEmpty();
        }

        if (existing.equals(incoming)
                || existing.startsWith(incoming)
                || incoming.startsWith(existing)) {
            return true;
        }

        // Check suffix-prefix overlap (streaming may produce partial overlaps)
        int maxOverlap = Math.min(existing.length(), incoming.length());
        maxOverlap = Math.min(maxOverlap, 200);
        int eLen = existing.length();
        for (int overlap = maxOverlap; overlap > 0; overlap--) {
            if (existing.regionMatches(eLen - overlap, incoming, 0, overlap)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 选择更完整的文本内容。
     * 优先保留非空、包含对方或长度更长的内容，减少 snapshot 回退造成的丢字。
     *
     * @param existingText 现有文本
     * @param incomingText 新文本
     * @return 更适合作为最终 block 内容的文本
     */
    private String preferMoreCompleteContent(String existingText, String incomingText) {
        String existing = existingText != null ? existingText : "";
        String incoming = incomingText != null ? incomingText : "";

        if (incoming.isEmpty()) {
            return existing;
        }
        if (existing.isEmpty()) {
            return incoming;
        }
        if (incoming.startsWith(existing)) {
            return incoming;
        }
        if (existing.startsWith(incoming)) {
            return existing;
        }
        return incoming.length() >= existing.length() ? incoming : existing;
    }
}
