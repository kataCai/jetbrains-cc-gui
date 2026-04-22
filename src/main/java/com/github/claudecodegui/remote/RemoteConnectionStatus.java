package com.github.claudecodegui.remote;

/**
 * 远程协作通道连接状态。
 */
public enum RemoteConnectionStatus {
    DISABLED("disabled"),
    DISCONNECTED("disconnected"),
    CONNECTING("connecting"),
    CONNECTED("connected"),
    ERROR("error");

    private final String value;

    RemoteConnectionStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
