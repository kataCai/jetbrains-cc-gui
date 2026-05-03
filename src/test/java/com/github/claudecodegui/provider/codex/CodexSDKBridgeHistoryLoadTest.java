package com.github.claudecodegui.provider.codex;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * CodexSDKBridge 历史加载测试。
 * 用于验证 Codex 会话在本地存在 jsonl 历史文件时，SDKBridge 能返回可供会话恢复使用的标准消息列表，
 * 而不是始终返回空列表或无法被现有消息解析器识别的原始记录。
 */
public class CodexSDKBridgeHistoryLoadTest {

    /**
     * 验证本地存在 Codex 历史文件时，桥接层会把原始记录标准化为现有会话恢复链路可识别的 user/assistant 消息。
     *
     * @throws IOException 文件创建或清理异常
     */
    @Test
    public void shouldReadMessagesFromLocalCodexHistoryWhenSessionFileExists() throws IOException {
        Path sessionsDir = Files.createTempDirectory("codex-sdk-bridge-history");
        try {
            writeSessionFile(
                    sessionsDir.resolve("2026/05/03"),
                    "session-history-1",
                    line("2026-05-03T10:00:00Z", "response_item",
                            "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello from Codex\"}]}"),
                    line("2026-05-03T10:00:01Z", "event_msg",
                            "{\"type\":\"user_message\",\"message\":\"Explain the previous answer\"}")
            );

            TestableCodexSDKBridge bridge = new TestableCodexSDKBridge(sessionsDir);

            List<JsonObject> messages = bridge.getSessionMessages("session-history-1", "/workspace/demo");

            assertFalse(messages.isEmpty());
            assertEquals("assistant", messages.get(0).get("type").getAsString());
            assertEquals("Hello from Codex",
                    messages.get(0).getAsJsonObject("message")
                            .getAsJsonArray("content")
                            .get(0)
                            .getAsJsonObject()
                            .get("text")
                            .getAsString());
            assertEquals("user", messages.get(1).get("type").getAsString());
            assertEquals("Explain the previous answer",
                    messages.get(1).getAsJsonObject("message")
                            .getAsJsonArray("content")
                            .get(0)
                            .getAsJsonObject()
                            .get("text")
                            .getAsString());
        } finally {
            deleteDirectory(sessionsDir);
        }
    }

    /**
     * 创建一份测试会话文件。
     *
     * @param parentDir 会话目录
     * @param sessionId 会话 ID
     * @param lines jsonl 行内容
     * @return 会话文件路径
     * @throws IOException 写文件异常
     */
    private Path writeSessionFile(Path parentDir, String sessionId, String... lines) throws IOException {
        Files.createDirectories(parentDir);
        Path file = parentDir.resolve("rollout-" + sessionId + ".jsonl");
        Files.write(file, List.of(lines));
        return file;
    }

    /**
     * 构建一行 jsonl 内容。
     *
     * @param timestamp 时间戳
     * @param type 记录类型
     * @param payloadJson payload JSON
     * @return jsonl 行字符串
     */
    private String line(String timestamp, String type, String payloadJson) {
        return "{\"timestamp\":\"" + timestamp + "\",\"type\":\"" + type + "\",\"payload\":" + payloadJson + "}";
    }

    /**
     * 清理测试目录。
     *
     * @param dir 目录路径
     * @throws IOException 删除异常
     */
    private void deleteDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    /**
     * 仅用于测试的 CodexSDKBridge。
     * 通过覆写历史读取入口，把本地临时目录下的会话文件接入到 SDKBridge 恢复链路。
     */
    private static final class TestableCodexSDKBridge extends CodexSDKBridge {
        private final CodexHistoryReader historyReader;

        private TestableCodexSDKBridge(Path sessionsDir) {
            this.historyReader = new CodexHistoryReader(sessionsDir, new com.google.gson.Gson()) {
                @Override
                boolean isCodexLocalConfigAuthorized() {
                    return false;
                }
            };
        }

        @Override
        protected CodexHistoryReader createHistoryReader() {
            return historyReader;
        }
    }
}
