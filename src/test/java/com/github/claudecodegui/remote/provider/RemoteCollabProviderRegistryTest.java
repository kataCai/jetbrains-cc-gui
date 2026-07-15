package com.github.claudecodegui.remote.provider;

import com.github.claudecodegui.remote.RemoteConnectionStatus;
import com.github.claudecodegui.remote.RemotePendingRequest;
import com.github.claudecodegui.remote.RemoteTaskEvent;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * 验证 provider 注册表可以按 ID 和能力查询已注册的 provider。
 */
public class RemoteCollabProviderRegistryTest {

    @Test
    public void shouldRegisterProvidersAndQueryByCapability() {
        RemoteCollabProviderRegistry registry = new RemoteCollabProviderRegistry();
        RemoteCollabProvider telegramProvider = new FakeProvider(
            new RemoteCollabProviderDescriptor(
                "telegram",
                "Telegram",
                "Telegram provider",
                EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH, RemoteCollabCapability.BINDING)
            )
        );
        RemoteCollabProvider gotifyProvider = new FakeProvider(
            new RemoteCollabProviderDescriptor(
                "gotify_web",
                "Gotify + Web",
                "Gotify provider",
                EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH, RemoteCollabCapability.RESULT_POLLING)
            )
        );

        registry.register(telegramProvider);
        registry.register(gotifyProvider);

        assertSame(telegramProvider, registry.getProvider("telegram"));
        List<RemoteCollabProvider> pollingProviders = registry.getProvidersSupporting(RemoteCollabCapability.RESULT_POLLING);
        assertEquals(1, pollingProviders.size());
        assertSame(gotifyProvider, pollingProviders.get(0));
        assertEquals(2, registry.getProviders().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectDuplicateProviderId() {
        RemoteCollabProviderRegistry registry = new RemoteCollabProviderRegistry();
        RemoteCollabProvider first = new FakeProvider(
            new RemoteCollabProviderDescriptor(
                "telegram",
                "Telegram",
                "Telegram provider",
                EnumSet.of(RemoteCollabCapability.TASK_EVENT_PUSH)
            )
        );
        RemoteCollabProvider second = new FakeProvider(
            new RemoteCollabProviderDescriptor(
                "telegram",
                "Telegram Mirror",
                "Duplicate provider",
                EnumSet.of(RemoteCollabCapability.RESULT_POLLING)
            )
        );

        registry.register(first);
        registry.register(second);
    }

    private static final class FakeProvider implements RemoteCollabProvider {
        private final RemoteCollabProviderDescriptor descriptor;

        private FakeProvider(RemoteCollabProviderDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        @Override
        public RemoteCollabProviderDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public RemoteConnectionStatus getConnectionStatus() {
            return RemoteConnectionStatus.DISCONNECTED;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void shutdown() {
        }

        @Override
        public void publishTaskEvent(RemoteTaskEvent event) {
        }

        @Override
        public void publishPendingRequest(RemotePendingRequest request) {
        }
    }
}
