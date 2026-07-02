package com.github.claudecodegui.settings;

import com.github.claudecodegui.session.ConversationSegmentRecord;
import com.github.claudecodegui.session.LogicalConversationRecord;
import com.github.claudecodegui.util.PlatformUtils;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证逻辑会话与运行时分段元数据的插件侧持久化行为。
 * 该测试覆盖 ~/.codemoss/config.json 中新增的逻辑会话索引与分段索引读写链路，
 * 用于保证后续“跨模型/跨供应商继续会话”实现有稳定、可恢复的元数据底座。
 */
public class CodemossSettingsServiceConversationMetadataTest {

    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    /**
     * 验证逻辑会话记录能够被正确保存、读取和删除。
     * 该用例覆盖单条逻辑会话元数据的主链路，确保历史聚合层后续能稳定拿到会话根信息。
     */
    @Test
    public void shouldSaveReadAndDeleteLogicalConversationRecord() throws Exception {
        Path tempHome = Files.createTempDirectory("logical-conversation-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        LogicalConversationRecord record = new LogicalConversationRecord(
                "logical-001",
                "session-root-001",
                "session-latest-002",
                "跨供应商继续任务",
                "codex",
                "buycode",
                "gpt-5.4",
                2,
                1719590400000L,
                1719590500000L,
                true,
                1719590500000L
        );

        service.saveLogicalConversationRecord(record);

        LogicalConversationRecord restored = service.getLogicalConversationRecord("logical-001");
        assertNotNull(restored);
        assertEquals("logical-001", restored.getLogicalConversationId());
        assertEquals("session-root-001", restored.getRootSessionId());
        assertEquals("session-latest-002", restored.getLatestSessionId());
        assertEquals("跨供应商继续任务", restored.getTitle());
        assertEquals("codex", restored.getRuntimeFamily());
        assertEquals("buycode", restored.getProvider());
        assertEquals("gpt-5.4", restored.getLastModel());
        assertEquals(2, restored.getSegmentCount());
        assertTrue(restored.isFavorited());
        assertEquals(1719590500000L, restored.getFavoritedAt());

        service.deleteLogicalConversationRecord("logical-001");

        assertNull(service.getLogicalConversationRecord("logical-001"));
    }

    /**
     * 验证同一逻辑会话下的分段记录能够按 segmentIndex 稳定聚合返回。
     * 该排序保证后续历史聚合和上下文迁移在面对多个运行段时具备可预测顺序。
     */
    @Test
    public void shouldSaveReadListAndDeleteConversationSegments() throws Exception {
        Path tempHome = Files.createTempDirectory("conversation-segments-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        ConversationSegmentRecord segmentB = new ConversationSegmentRecord(
                "session-002",
                "logical-001",
                "session-001",
                1,
                "buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "manual_switch_provider",
                "summary_plus_recent_messages",
                1719590500000L
        );
        ConversationSegmentRecord segmentA = new ConversationSegmentRecord(
                "session-001",
                "logical-001",
                null,
                0,
                "codex-cli-login",
                "codex",
                "gpt-5.4",
                "medium",
                "new_conversation",
                "summary_only",
                1719590400000L
        );

        service.saveConversationSegmentRecord(segmentB);
        service.saveConversationSegmentRecord(segmentA);

        ConversationSegmentRecord restored = service.getConversationSegmentRecord("session-002");
        assertNotNull(restored);
        assertEquals("logical-001", restored.getLogicalConversationId());
        assertEquals("session-001", restored.getParentSessionId());
        assertEquals(1, restored.getSegmentIndex());

        List<ConversationSegmentRecord> segments = service.listConversationSegments("logical-001");
        assertEquals(2, segments.size());
        assertEquals("session-001", segments.get(0).getSessionId());
        assertEquals("session-002", segments.get(1).getSessionId());

        service.deleteConversationSegmentRecord("session-001");
        service.deleteConversationSegmentRecord("session-002");

        assertNull(service.getConversationSegmentRecord("session-001"));
        assertEquals(0, service.listConversationSegments("logical-001").size());
    }

    /**
     * 验证逻辑会话标题与收藏状态更新时，只覆盖聚合元数据字段，
     * 不会破坏根分段、最新分段、运行时家族与模型等既有结构字段。
     */
    @Test
    public void shouldUpdateLogicalConversationMetadataWithoutBreakingStructuralFields() throws Exception {
        Path tempHome = Files.createTempDirectory("conversation-metadata-update-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.saveLogicalConversationRecord(new LogicalConversationRecord(
                "logical-001",
                "session-root-001",
                "session-latest-002",
                "Old Title",
                "codex",
                "buycode",
                "gpt-5.4",
                2,
                1719590400000L,
                1719590500000L,
                false,
                0L
        ));

        service.updateLogicalConversationMetadata("logical-001", "New Title", true, 1719590600000L);

        LogicalConversationRecord restored = service.getLogicalConversationRecord("logical-001");
        assertNotNull(restored);
        assertEquals("session-root-001", restored.getRootSessionId());
        assertEquals("session-latest-002", restored.getLatestSessionId());
        assertEquals("codex", restored.getRuntimeFamily());
        assertEquals("buycode", restored.getProvider());
        assertEquals("gpt-5.4", restored.getLastModel());
        assertEquals(2, restored.getSegmentCount());
        assertEquals("New Title", restored.getTitle());
        assertTrue(restored.isFavorited());
        assertEquals(1719590600000L, restored.getFavoritedAt());
    }

    /**
     * 验证删除逻辑会话时会级联清理该会话下的全部分段记录与 session binding，
     * 避免历史删除后遗留孤儿索引继续污染恢复与发送链路。
     */
    @Test
    public void shouldDeleteLogicalConversationCascadeWithSegmentsAndBindings() throws Exception {
        Path tempHome = Files.createTempDirectory("conversation-metadata-cascade-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        service.saveLogicalConversationRecord(new LogicalConversationRecord(
                "logical-001",
                "session-001",
                "session-002",
                "Cascade Target",
                "codex",
                "buycode",
                "gpt-5.4",
                2,
                1719590400000L,
                1719590500000L,
                false,
                0L
        ));
        service.saveConversationSegmentRecord(new ConversationSegmentRecord(
                "session-001",
                "logical-001",
                null,
                0,
                "buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "initial",
                "none",
                1719590400000L
        ));
        service.saveConversationSegmentRecord(new ConversationSegmentRecord(
                "session-002",
                "logical-001",
                "session-001",
                1,
                "buycode",
                "codex",
                "gpt-5.4",
                "medium",
                "continued",
                "session_summary",
                1719590500000L
        ));
        service.saveCodexSessionBinding("session-001", new com.github.claudecodegui.session.CodexSessionBinding(
                "buycode",
                "gpt-5.4",
                "codex_sdk",
                "provider",
                "managed_provider"
        ));
        service.saveCodexSessionBinding("session-002", new com.github.claudecodegui.session.CodexSessionBinding(
                "buycode",
                "gpt-5.4",
                "codex_sdk",
                "provider",
                "managed_provider"
        ));

        service.deleteLogicalConversationCascade("logical-001");

        assertNull(service.getLogicalConversationRecord("logical-001"));
        assertEquals(0, service.listConversationSegments("logical-001").size());
        assertNull(service.getConversationSegmentRecord("session-001"));
        assertNull(service.getConversationSegmentRecord("session-002"));
        assertNull(service.getCodexSessionBinding("session-001"));
        assertNull(service.getCodexSessionBinding("session-002"));
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
