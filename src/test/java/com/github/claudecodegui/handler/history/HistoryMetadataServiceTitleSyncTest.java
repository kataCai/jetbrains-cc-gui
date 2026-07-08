package com.github.claudecodegui.handler.history;

import com.github.claudecodegui.handler.NodeJsServiceCaller;
import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.github.claudecodegui.session.LogicalConversationRecord;
import com.intellij.openapi.application.ApplicationManager;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * HistoryMetadataService 标题同步测试。
 * 用于验证 update_title 成功后会触发 Tab 标题同步，
 * 以及同步调度在有 IDEA Application 时会回到 EDT 执行。
 */
public class HistoryMetadataServiceTitleSyncTest {

    /**
     * 验证标题更新成功时会触发同步。
     */
    @Test
    public void shouldTriggerTitleSyncWhenUpdateTitleSucceeds() {
        List<String> syncedTitles = new ArrayList<>();
        HistoryMetadataService service = new HistoryMetadataService(
                createHandlerContext(),
                new StubNodeJsServiceCaller("{\"success\":true}"),
                new HistoryTitleSyncCoordinator((sessionId, newTitle, updater) ->
                        syncedTitles.add(sessionId + ":" + newTitle))
        );

        service.handleUpdateTitle("{\"sessionId\":\"session-sync-1\",\"customTitle\":\"新标题\"}");
        waitForAsyncCondition(() -> syncedTitles.size() == 1);

        assertEquals(1, syncedTitles.size());
        assertEquals("session-sync-1:新标题", syncedTitles.get(0));
    }

    /**
     * 验证标题更新失败时不会触发同步。
     */
    @Test
    public void shouldNotTriggerTitleSyncWhenUpdateTitleFails() {
        List<String> syncedTitles = new ArrayList<>();
        HistoryMetadataService service = new HistoryMetadataService(
                createHandlerContext(),
                new StubNodeJsServiceCaller("{\"success\":false,\"error\":\"failed\"}"),
                new HistoryTitleSyncCoordinator((sessionId, newTitle, updater) ->
                        syncedTitles.add(sessionId + ":" + newTitle))
        );

        service.handleUpdateTitle("{\"sessionId\":\"session-sync-2\",\"customTitle\":\"失败标题\"}");
        waitForAsyncWork();

        assertEquals(0, syncedTitles.size());
    }

    /**
     * 验证标题同步调度会在合适线程执行。
     * 当 IDEA Application 存在时，必须运行在 EDT；
     * 单元测试环境下若 Application 不存在，则允许直接同步执行。
     */
    @Test
    public void shouldDispatchTitleSyncOnEdtWhenUpdateTitleSucceeds() throws InterruptedException {
        AtomicBoolean syncCalled = new AtomicBoolean(false);
        AtomicBoolean executedOnEdt = new AtomicBoolean(false);
        AtomicBoolean applicationMissing = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        HistoryMetadataService service = new HistoryMetadataService(
                createHandlerContext(),
                new StubNodeJsServiceCaller("{\"success\":true}"),
                new HistoryTitleSyncCoordinator((sessionId, newTitle, updater) -> {
                    syncCalled.set(true);
                    if (ApplicationManager.getApplication() == null) {
                        applicationMissing.set(true);
                    } else {
                        executedOnEdt.set(ApplicationManager.getApplication().isDispatchThread());
                    }
                    latch.countDown();
                })
        );

        service.handleUpdateTitle("{\"sessionId\":\"session-sync-3\",\"customTitle\":\"EDT标题\"}");

        assertTrue("title sync should be dispatched", latch.await(5, TimeUnit.SECONDS));
        assertTrue("title sync should use EDT when Application exists",
                syncCalled.get() && (applicationMissing.get() || executedOnEdt.get()));
    }

    /**
     * 验证 continued conversation 改标题时，
     * 会同时更新物理 session 标题文件与逻辑会话聚合记录上的标题字段，
     * 避免历史列表聚合标题与当前 Tab 标题再次分叉。
     */
    @Test
    public void shouldUpdateLogicalConversationTitleWhenLogicalConversationIdProvided() throws Exception {
        String logicalConversationId = "logical-title-sync-" + UUID.randomUUID();
        InMemoryCodemossSettingsService settingsService = new InMemoryCodemossSettingsService();
        settingsService.saveLogicalConversationRecord(new LogicalConversationRecord(
                logicalConversationId,
                "session-root-001",
                "session-latest-002",
                "旧标题",
                "codex",
                "buycode",
                "gpt-5.4",
                2,
                1719590400000L,
                1719590500000L,
                false,
                0L
        ));

        HistoryMetadataService service = new HistoryMetadataService(
                createHandlerContext(settingsService),
                new StubNodeJsServiceCaller("{\"success\":true}"),
                new HistoryTitleSyncCoordinator((sessionId, newTitle, updater) -> {
                })
        );

        service.handleUpdateTitle("{\"sessionId\":\"session-latest-002\",\"logicalConversationId\":\""
                + logicalConversationId + "\",\"customTitle\":\"聚合新标题\"}");
        waitForAsyncCondition(() -> {
            try {
                LogicalConversationRecord updated = settingsService.getLogicalConversationRecord(logicalConversationId);
                return updated != null && "聚合新标题".equals(updated.getTitle());
            } catch (Exception e) {
                return false;
            }
        });

        LogicalConversationRecord updated = settingsService.getLogicalConversationRecord(logicalConversationId);
        assertNotNull(updated);
        assertEquals("聚合新标题", updated.getTitle());
        assertEquals("session-root-001", updated.getRootSessionId());
        assertEquals("session-latest-002", updated.getLatestSessionId());
    }

    /**
     * 创建用于测试的 HandlerContext。
     *
     * @return 最小可用的 HandlerContext
     */
    private static HandlerContext createHandlerContext() {
        return createHandlerContext(new CodemossSettingsService());
    }

    /**
     * 创建用于测试的 HandlerContext。
     *
     * @param settingsService 需要注入的设置服务实现
     * @return 最小可用的 HandlerContext
     */
    private static HandlerContext createHandlerContext(CodemossSettingsService settingsService) {
        return new HandlerContext(null, null, null, settingsService, new HandlerContext.JsCallback() {
            @Override
            public void callJavaScript(String functionName, String... args) {
            }

            @Override
            public String escapeJs(String str) {
                return str;
            }
        });
    }

    /**
     * 等待一小段时间，让后台线程完成但不附带任何提前结束条件。
     */
    private static void waitForAsyncWork() {
        waitForAsyncCondition(() -> false);
    }

    /**
     * 等待后台线程与可能存在的 EDT 调度完成。
     *
     * @param condition 提前结束等待的条件；若始终为 false，则仅等待到超时
     * @return 无返回值
     */
    private static void waitForAsyncCondition(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (condition.getAsBoolean()) {
                    return;
                }
                Thread.sleep(50L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for async work", e);
        }
    }

    /**
     * 标题服务调用桩。
     * 用于稳定返回预设的 Node.js 结果，隔离文件系统与外部进程影响。
     */
    private static final class StubNodeJsServiceCaller extends NodeJsServiceCaller {
        private final String result;

        /**
         * 创建预置结果的调用桩。
         *
         * @param result 预设返回值
         */
        private StubNodeJsServiceCaller(String result) {
            super(createHandlerContext());
            this.result = result;
        }

        /**
         * 返回预置的标题更新结果。
         *
         * @param functionName 调用函数名
         * @param sessionId 会话 ID
         * @param customTitle 自定义标题
         * @return 预设结果 JSON
         */
        @Override
        public String callNodeJsTitlesServiceWithParams(String functionName, String sessionId, String customTitle) {
            return result;
        }
    }

    /**
     * 最小化的内存版设置服务。
     * 仅覆盖本测试所需的逻辑会话标题读写能力，避免触碰真实用户目录中的配置文件。
     */
    private static final class InMemoryCodemossSettingsService extends CodemossSettingsService {
        private LogicalConversationRecord logicalConversationRecord;

        /**
         * 保存逻辑会话主记录。
         *
         * @param record 待保存记录
         */
        @Override
        public void saveLogicalConversationRecord(LogicalConversationRecord record) {
            this.logicalConversationRecord = record;
        }

        /**
         * 读取当前内存中的逻辑会话主记录。
         *
         * @param logicalConversationId 逻辑会话 id
         * @return 命中则返回记录，否则返回 null
         */
        @Override
        public LogicalConversationRecord getLogicalConversationRecord(String logicalConversationId) {
            return logicalConversationRecord != null
                    && logicalConversationRecord.getLogicalConversationId().equals(logicalConversationId)
                    ? logicalConversationRecord
                    : null;
        }

        /**
         * 仅更新标题字段，同时保留结构字段不变。
         *
         * @param logicalConversationId 逻辑会话 id
         * @param title 新标题
         * @param favorited 收藏状态
         * @param favoritedAt 收藏时间
         */
        @Override
        public void updateLogicalConversationMetadata(
                String logicalConversationId,
                String title,
                Boolean favorited,
                Long favoritedAt
        ) {
            LogicalConversationRecord existingRecord = getLogicalConversationRecord(logicalConversationId);
            if (existingRecord == null) {
                return;
            }
            logicalConversationRecord = new LogicalConversationRecord(
                    existingRecord.getLogicalConversationId(),
                    existingRecord.getRootSessionId(),
                    existingRecord.getLatestSessionId(),
                    title != null ? title : existingRecord.getTitle(),
                    existingRecord.getRuntimeFamily(),
                    existingRecord.getProvider(),
                    existingRecord.getLastModel(),
                    existingRecord.getSegmentCount(),
                    existingRecord.getCreatedAt(),
                    existingRecord.getUpdatedAt(),
                    favorited != null ? favorited : existingRecord.isFavorited(),
                    favoritedAt != null ? favoritedAt : existingRecord.getFavoritedAt()
            );
        }
    }
}
