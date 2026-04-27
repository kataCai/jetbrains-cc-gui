package com.github.claudecodegui.remote.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程协作 provider 注册表。
 * 负责统一管理已接入的 provider，并支持按 providerId 或能力维度查询，供主链路后续做路由分发。
 */
public final class RemoteCollabProviderRegistry {

    private final Map<String, RemoteCollabProvider> providers = new ConcurrentHashMap<>();

    /**
     * 注册一个 provider。
     * 如果 providerId 已存在则直接拒绝，避免两个方案实例覆盖同一个逻辑入口。
     */
    public void register(RemoteCollabProvider provider) {
        RemoteCollabProvider normalized = Objects.requireNonNull(provider, "provider");
        String providerId = normalized.getProviderId();
        RemoteCollabProvider previous = providers.putIfAbsent(providerId, normalized);
        if (previous != null) {
            throw new IllegalArgumentException("Remote collab provider already registered: " + providerId);
        }
    }

    public RemoteCollabProvider getProvider(String providerId) {
        if (providerId == null || providerId.trim().isEmpty()) {
            return null;
        }
        return providers.get(providerId.trim());
    }

    public List<RemoteCollabProvider> getProviders() {
        return Collections.unmodifiableList(new ArrayList<>(providers.values()));
    }

    /**
     * 返回支持指定能力的 provider 列表。
     */
    public List<RemoteCollabProvider> getProvidersSupporting(RemoteCollabCapability capability) {
        List<RemoteCollabProvider> matched = new ArrayList<>();
        for (RemoteCollabProvider provider : providers.values()) {
            if (provider.supports(capability)) {
                matched.add(provider);
            }
        }
        return Collections.unmodifiableList(matched);
    }
}
