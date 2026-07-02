package com.github.claudecodegui.ui.toolwindow;

import com.github.claudecodegui.settings.TabStateService;

final class TabSessionRestorePolicy {

    private TabSessionRestorePolicy() {
    }

    static boolean shouldLoadHistory(TabStateService.TabSessionState savedState) {
        return savedState != null && isNonEmpty(savedState.sessionId);
    }

    private static boolean isNonEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
