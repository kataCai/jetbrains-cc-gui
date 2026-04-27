package com.github.claudecodegui.remote.debug;

import java.util.Objects;

/**
 * ???????????
 * ?????????????????????????????? provider/action ????
 */
public final class RemoteCollabDebugActionDescriptor {

    private final String providerId;
    private final String actionKey;
    private final String displayName;
    private final String description;

    public RemoteCollabDebugActionDescriptor(String providerId, String actionKey, String displayName, String description) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.actionKey = Objects.requireNonNull(actionKey, "actionKey");
        this.displayName = displayName == null ? "" : displayName;
        this.description = description == null ? "" : description;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getActionKey() {
        return actionKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
