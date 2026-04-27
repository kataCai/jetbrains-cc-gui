package com.github.claudecodegui.remote;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 远程协作路由策略。
 * 负责表达“谁承担交互、谁承担通知”的最小策略模型，后续主链路按该对象决定 pending request 和 task event 的分发目标。
 */
public final class RemoteRoutingPolicy {

    private final String interactiveProviderId;
    private final List<String> notifyProviderIds;

    public RemoteRoutingPolicy(String interactiveProviderId, Collection<String> notifyProviderIds) {
        this.interactiveProviderId = normalize(interactiveProviderId);
        LinkedHashSet<String> normalizedNotifyIds = new LinkedHashSet<>();
        if (notifyProviderIds != null) {
            for (String providerId : notifyProviderIds) {
                String normalized = normalize(providerId);
                if (!normalized.isEmpty()) {
                    normalizedNotifyIds.add(normalized);
                }
            }
        }
        this.notifyProviderIds = Collections.unmodifiableList(new ArrayList<>(normalizedNotifyIds));
    }

    public String getInteractiveProviderId() {
        return interactiveProviderId;
    }

    /**
     * 返回通知 provider 列表。
     * 这里保留去重后的顺序，便于后续设置页和日志展示保持稳定。
     */
    public List<String> getNotifyProviderIds() {
        return notifyProviderIds;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
