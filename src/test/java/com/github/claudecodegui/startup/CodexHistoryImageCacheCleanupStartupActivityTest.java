package com.github.claudecodegui.startup;

import com.github.claudecodegui.provider.codex.CodexHistoryImageCacheService;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

/**
 * 验证启动活动会把 Codex 历史图片缓存清理调度到后台线程。
 * 该测试只关注“是否触发 cleanupCache()”这一行为，
 * 不重复覆盖缓存服务本身的 TTL / 容量删除细节。
 */
public class CodexHistoryImageCacheCleanupStartupActivityTest {

    /**
     * 验证 execute() 会通过 pooled thread 异步触发 cleanupCache。
     *
     * @throws Exception 反射替换 ApplicationManager 测试桩失败时抛出
     */
    @Test
    public void executeSchedulesCleanupOnPooledThread() throws Exception {
        AtomicBoolean cleanupCalled = new AtomicBoolean(false);
        CodexHistoryImageCacheCleanupStartupActivity activity = new CodexHistoryImageCacheCleanupStartupActivity() {
            @Override
            protected CodexHistoryImageCacheService createCacheService() {
                return new CodexHistoryImageCacheService() {
                    @Override
                    public CacheCleanupResult cleanupCache() {
                        cleanupCalled.set(true);
                        return new CacheCleanupResult(0, 0, 0L);
                    }
                };
            }
        };

        Application originalApplication = swapApplicationWithImmediateExecutor();
        try {
            Project project = (Project) Proxy.newProxyInstance(
                    Project.class.getClassLoader(),
                    new Class[]{Project.class},
                    (instance, method, args) -> defaultValue(method.getReturnType())
            );
            Continuation<Unit> continuation = new NoOpContinuation();

            activity.execute(project, continuation);

            assertTrue(cleanupCalled.get());
        } finally {
            restoreApplication(originalApplication);
        }
    }

    private Application swapApplicationWithImmediateExecutor() throws Exception {
        Field field = ApplicationManager.class.getDeclaredField("ourApplication");
        field.setAccessible(true);
        Application original = (Application) field.get(null);
        Application proxy = (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class[]{Application.class},
                (instance, method, args) -> {
                    if ("executeOnPooledThread".equals(method.getName()) && args != null && args.length == 1) {
                        ((Runnable) args[0]).run();
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        field.set(null, proxy);
        return original;
    }

    private void restoreApplication(Application originalApplication) throws Exception {
        Field field = ApplicationManager.class.getDeclaredField("ourApplication");
        field.setAccessible(true);
        field.set(null, originalApplication);
    }

    private Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (returnType == Boolean.TYPE) {
            return false;
        }
        if (returnType == Integer.TYPE) {
            return 0;
        }
        if (returnType == Long.TYPE) {
            return 0L;
        }
        if (returnType == Float.TYPE) {
            return 0F;
        }
        if (returnType == Double.TYPE) {
            return 0D;
        }
        return null;
    }

    /**
     * 最小续体实现。
     * 启动活动测试并不依赖 Kotlin 协程返回值，因此仅提供空实现即可。
     */
    private static final class NoOpContinuation implements Continuation<Unit> {
        @Override
        public @NotNull kotlin.coroutines.CoroutineContext getContext() {
            return kotlin.coroutines.EmptyCoroutineContext.INSTANCE;
        }

        @Override
        public void resumeWith(@NotNull Object result) {
        }
    }
}
