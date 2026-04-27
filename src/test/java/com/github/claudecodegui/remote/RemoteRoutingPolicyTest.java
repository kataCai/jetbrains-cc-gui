package com.github.claudecodegui.remote;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证远程协作路由策略会对通知 provider 去重，并保留交互 provider。
 */
public class RemoteRoutingPolicyTest {

    @Test
    public void shouldNormalizeNotifyProviderIds() {
        RemoteRoutingPolicy policy = new RemoteRoutingPolicy(
            "gotify_web",
            Arrays.asList("telegram", "gotify_web", "telegram", " ")
        );

        assertEquals("gotify_web", policy.getInteractiveProviderId());
        assertEquals(2, policy.getNotifyProviderIds().size());
        assertTrue(policy.getNotifyProviderIds().contains("telegram"));
        assertTrue(policy.getNotifyProviderIds().contains("gotify_web"));
    }
}
