package com.github.claudecodegui.remote.provider;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 远程协作 provider 的静态描述信息。
 * 该对象只承载展示和能力声明，不负责运行时行为，便于设置页和主链路以统一模型读取方案元数据。
 */
public final class RemoteCollabProviderDescriptor {

    private final String providerId;
    private final String displayName;
    private final String description;
    private final Set<RemoteCollabCapability> capabilities;

    public RemoteCollabProviderDescriptor(
        String providerId,
        String displayName,
        String description,
        Set<RemoteCollabCapability> capabilities
    ) {
        this.providerId = requireText(providerId, "providerId");
        this.displayName = requireText(displayName, "displayName");
        this.description = description == null ? "" : description.trim();
        EnumSet<RemoteCollabCapability> capabilitySet = capabilities == null || capabilities.isEmpty()
            ? EnumSet.noneOf(RemoteCollabCapability.class)
            : EnumSet.copyOf(capabilities);
        this.capabilities = Collections.unmodifiableSet(capabilitySet);
    }

    public String getProviderId() {
        return providerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 返回 provider 声明的能力快照。
     * 这里返回不可变集合，避免外部修改 descriptor 内部状态。
     */
    public Set<RemoteCollabCapability> getCapabilities() {
        return capabilities;
    }

    public boolean supports(RemoteCollabCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
