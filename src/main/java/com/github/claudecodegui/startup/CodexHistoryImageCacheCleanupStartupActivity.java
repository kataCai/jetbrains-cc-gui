package com.github.claudecodegui.startup;

import com.github.claudecodegui.provider.codex.CodexHistoryImageCacheService;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 项目启动后的 Codex 历史图片缓存清理活动。
 * <p>
 * 该活动负责在 IDE 打开项目后低频异步触发一次缓存治理，
 * 让 retentionDays / maxSizeMb 策略不必等到用户下一次发送图片或手动保存设置后才生效。
 * 设计约束如下：
 * 1. 仅在后台线程执行，避免阻塞 IDE 启动链路；
 * 2. 只调用缓存服务统一入口，不在启动阶段重复实现扫描逻辑；
 * 3. 即使清理失败也只记录日志，不影响项目正常打开。
 */
public class CodexHistoryImageCacheCleanupStartupActivity implements ProjectActivity {

    private static final Logger LOG = Logger.getInstance(CodexHistoryImageCacheCleanupStartupActivity.class);

    /**
     * 构造默认启动清理活动。
     */
    public CodexHistoryImageCacheCleanupStartupActivity() {
    }

    /**
     * 创建缓存服务实例。
     * <p>
     * 提供受保护工厂方法，便于测试替换为可观测的假实现，而不影响正式代码路径。
     *
     * @return 用于执行启动清理的缓存服务
     */
    protected CodexHistoryImageCacheService createCacheService() {
        return new CodexHistoryImageCacheService();
    }

    /**
     * 在项目启动后异步触发一次缓存清理。
     *
     * @param project 当前打开的项目
     * @param continuation Kotlin 协程续体
     * @return Kotlin Unit
     */
    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                createCacheService().cleanupCache();
            } catch (Exception exception) {
                LOG.warn("[CodexHistoryImageCacheCleanupStartup] Failed to cleanup cache on startup: "
                        + exception.getMessage(), exception);
            }
        });
        return Unit.INSTANCE;
    }
}
