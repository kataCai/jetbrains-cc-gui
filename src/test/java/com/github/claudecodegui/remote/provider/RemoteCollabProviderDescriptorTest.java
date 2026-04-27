package com.github.claudecodegui.remote.provider;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 provider 描述对象会保留基础元信息，并对能力集合做防御性拷贝。
 */
public class RemoteCollabProviderDescriptorTest {

    @Test
    public void shouldExposeImmutableCapabilitySnapshot() {
        EnumSet<RemoteCollabCapability> capabilities = EnumSet.of(
            RemoteCollabCapability.TASK_EVENT_PUSH,
            RemoteCollabCapability.HEALTH_CHECK
        );

        RemoteCollabProviderDescriptor descriptor = new RemoteCollabProviderDescriptor(
            "telegram",
            "Telegram",
            "Telegram remote collaboration",
            capabilities
        );

        capabilities.add(RemoteCollabCapability.RESULT_POLLING);

        Set<RemoteCollabCapability> snapshot = descriptor.getCapabilities();
        assertEquals("telegram", descriptor.getProviderId());
        assertEquals("Telegram", descriptor.getDisplayName());
        assertTrue(snapshot.contains(RemoteCollabCapability.TASK_EVENT_PUSH));
        assertTrue(snapshot.contains(RemoteCollabCapability.HEALTH_CHECK));
        assertFalse(snapshot.contains(RemoteCollabCapability.RESULT_POLLING));
    }
}
