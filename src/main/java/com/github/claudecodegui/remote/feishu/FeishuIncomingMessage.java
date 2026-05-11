package com.github.claudecodegui.remote.feishu;

/**
 * 飞书入站消息的最小归一化模型。
 * 第一版只关心操作者 openId、会话 chatId 和消息文本，避免事件订阅实现尚未稳定前过早耦合官方事件结构。
 */
public final class FeishuIncomingMessage {

    private final String openId;
    private final String chatId;
    private final String text;

    public FeishuIncomingMessage(String openId, String chatId, String text) {
        this.openId = normalize(openId);
        this.chatId = normalize(chatId);
        this.text = normalize(text);
    }

    public String getOpenId() {
        return openId;
    }

    public String getChatId() {
        return chatId;
    }

    public String getText() {
        return text;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
