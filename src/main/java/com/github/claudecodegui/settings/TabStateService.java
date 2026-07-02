package com.github.claudecodegui.settings;

import com.github.claudecodegui.session.CodexSessionBinding;
import com.github.claudecodegui.session.SessionRuntimeFamily;
import com.intellij.openapi.components.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Tab State Persistence Service.
 * Saves and restores custom tab names plus per-tab session binding state.
 */
@State(
    name = "ClaudeCodeTabState",
    storages = @Storage("claudeCodeTabState.xml")
)
@Service(Service.Level.PROJECT)
public final class TabStateService implements PersistentStateComponent<TabStateService.State> {

    private static final Logger LOG = Logger.getInstance(TabStateService.class);
    public static final String TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE = "FOLLOW_SESSION_TITLE";
    public static final String TITLE_BINDING_MODE_MANUAL_CUSTOM = "MANUAL_CUSTOM";

    private State myState = new State();

    public static TabStateService getInstance(@NotNull Project project) {
        return project.getService(TabStateService.class);
    }

    @Override
    public @Nullable State getState() {
        return myState;
    }

    @Override
    public void loadState(@NotNull State state) {
        myState = state;
        if (myState.tabNames == null) {
            myState.tabNames = new HashMap<>();
        }
        if (myState.tabSessions == null) {
            myState.tabSessions = new HashMap<>();
        }
        LOG.info("[TabStateService] Loaded tab state with " + myState.tabNames.size()
                + " tab names and " + myState.tabSessions.size() + " tab sessions");
    }

    /**
     * Save a tab name.
     * @param tabIndex the tab index
     * @param tabName the tab name
     */
    public void saveTabName(int tabIndex, String tabName) {
        if (tabName != null && !tabName.trim().isEmpty()) {
            myState.tabNames.put(tabIndex, tabName);
            LOG.info("[TabStateService] Saved tab name: index=" + tabIndex + ", name=" + tabName);
        }
    }

    /**
     * Get a tab name.
     * @param tabIndex the tab index
     * @return the tab name, or null if not set
     */
    @Nullable
    public String getTabName(int tabIndex) {
        return myState.tabNames.get(tabIndex);
    }

    /**
     * Save or update session binding state for a tab.
     */
    public void saveTabSessionState(int tabIndex, @Nullable TabSessionState sessionState) {
        if (sessionState == null) {
            myState.tabSessions.remove(tabIndex);
            LOG.info("[TabStateService] Cleared tab session state: index=" + tabIndex);
            return;
        }
        myState.tabSessions.put(tabIndex, sessionState.copy());
        LOG.info("[TabStateService] Saved tab session state: index=" + tabIndex
                + ", provider=" + sessionState.provider
                + ", runtimeFamily=" + sessionState.getEffectiveRuntimeFamily()
                + ", sessionId=" + sessionState.sessionId
                + ", cwd=" + sessionState.cwd + ")");
    }

    /**
     * Get session binding state for a tab.
     */
    @Nullable
    public TabSessionState getTabSessionState(int tabIndex) {
        TabSessionState state = myState.tabSessions.get(tabIndex);
        return state != null ? state.copy() : null;
    }

    /**
     * 更新指定 Tab 的标题绑定模式。
     * 该状态用于区分页签标题是跟随会话标题，还是已经被用户手动固定。
     *
     * @param tabIndex 页签索引
     * @param titleBindingMode 标题绑定模式
     */
    public void saveTabTitleBindingMode(int tabIndex, @Nullable String titleBindingMode) {
        TabSessionState state = myState.tabSessions.get(tabIndex);
        if (state == null) {
            state = new TabSessionState();
            myState.tabSessions.put(tabIndex, state);
        }
        state.titleBindingMode = normalizeTitleBindingMode(titleBindingMode);
        LOG.info("[TabStateService] Saved tab title binding mode: index=" + tabIndex
                + ", mode=" + state.getEffectiveTitleBindingMode());
    }

    /**
     * Remove a tab name and session state.
     * @param tabIndex the tab index
     */
    public void removeTabName(int tabIndex) {
        myState.tabNames.remove(tabIndex);
        myState.tabSessions.remove(tabIndex);
        LOG.info("[TabStateService] Removed tab state for index: " + tabIndex);
    }

    /**
     * Get all tab names.
     * @return a map from tab index to tab name
     */
    public Map<Integer, String> getAllTabNames() {
        return new HashMap<>(myState.tabNames);
    }

    /**
     * Clear all tab names and session state.
     */
    public void clearAllTabNames() {
        myState.tabNames.clear();
        myState.tabSessions.clear();
        LOG.info("[TabStateService] Cleared all tab names and session state");
    }

    /**
     * Update tab indexes when a tab is removed (re-maps all indexes accordingly).
     * @param removedIndex the index of the removed tab
     */
    public void onTabRemoved(int removedIndex) {
        myState.tabNames.remove(removedIndex);
        myState.tabSessions.remove(removedIndex);

        Map<Integer, String> newTabNames = new HashMap<>();
        for (Map.Entry<Integer, String> entry : myState.tabNames.entrySet()) {
            int oldIndex = entry.getKey();
            if (oldIndex > removedIndex) {
                newTabNames.put(oldIndex - 1, entry.getValue());
            } else {
                newTabNames.put(oldIndex, entry.getValue());
            }
        }
        myState.tabNames = newTabNames;

        Map<Integer, TabSessionState> newTabSessions = new HashMap<>();
        for (Map.Entry<Integer, TabSessionState> entry : myState.tabSessions.entrySet()) {
            int oldIndex = entry.getKey();
            if (oldIndex > removedIndex) {
                newTabSessions.put(oldIndex - 1, entry.getValue());
            } else {
                newTabSessions.put(oldIndex, entry.getValue());
            }
        }
        myState.tabSessions = newTabSessions;

        if (myState.tabCount > 0) {
            myState.tabCount--;
        }

        LOG.info("[TabStateService] Updated tab indexes after removal of index: " + removedIndex
                + ", new count: " + myState.tabCount);
    }

    /**
     * Save the tab count.
     * @param count the number of tabs
     */
    public void saveTabCount(int count) {
        myState.tabCount = count;
        LOG.info("[TabStateService] Saved tab count: " + count);
    }

    /**
     * Get the tab count.
     * @return the number of tabs, defaults to 1
     */
    public int getTabCount() {
        return Math.max(1, myState.tabCount);
    }

    /**
     * Per-tab persisted session snapshot.
     */
    public static class TabSessionState {
        public String provider;
        /**
         * 会话恢复时真正采用的底层运行时家族。
         * 该字段用于把展示 provider 与恢复主干解耦，兼容旧数据缺失该字段时的推断恢复。
         */
        public String runtimeFamily;
        public String sessionId;
        /**
         * 当前 Tab 所属的逻辑会话标识。
         * 该字段把“用户看到的一条连续会话”与底层物理 session 解耦，供历史恢复和跨模型继续场景定位主干使用。
         */
        public String logicalConversationId;
        /**
         * 当前 Tab 恢复后应优先绑定的活动分段 sessionId。
         * 当逻辑会话存在多个运行段时，该字段用于恢复“当前继续点”，而不是退回到首段或任意旧段。
         */
        public String activeSegmentSessionId;
        /**
         * 当前活动分段的父分段 sessionId。
         * 该字段主要用于恢复链路和调试链路定位分段继承关系，缺失时允许兼容回退。
         */
        public String parentSegmentSessionId;
        /**
         * 标记当前 Tab 是否正处于“等待继续分段创建完成”的过渡态。
         * 旧快照未携带该字段时默认视为 false，避免无意义地阻塞正常恢复流程。
         */
        public boolean continuationPending;
        /**
         * 当前继续分段操作的来源分段 sessionId。
         * 该字段用于在恢复或异常诊断时识别上下文迁移来源，不参与普通单段会话的运行。
         */
        public String continuationSourceSessionId;
        public String cwd;
        public String model;
        public String permissionMode;
        public String reasoningEffort;
        /**
         * Codex 会话绑定的 provider id。
         * 仅用于恢复同一条 Codex 会话时校验和回填，不参与 Claude provider 场景。
         */
        public String codexProviderId;
        /**
         * Codex 会话绑定的 requestMode。
         * 当前主要用于诊断和恢复一致性校验，不承载真实传输实现切换。
         */
        public String codexRequestMode;
        /**
         * Codex 会话绑定的 endpoint 来源。
         * 该字段用于恢复时诊断“该会话最初是否走 provider endpoint 或 SDK 默认值”。
         */
        public String codexBaseUrlSource;
        /**
         * Codex 会话绑定的配置来源。
         * 该字段用于恢复时区分 managed provider 与 cli login 等来源。
         */
        public String codexEffectiveConfigSource;
        public String titleBindingMode;

        public TabSessionState copy() {
            TabSessionState copy = new TabSessionState();
            copy.provider = this.provider;
            copy.runtimeFamily = getEffectiveRuntimeFamily();
            copy.sessionId = this.sessionId;
            copy.logicalConversationId = this.logicalConversationId;
            copy.activeSegmentSessionId = this.activeSegmentSessionId;
            copy.parentSegmentSessionId = this.parentSegmentSessionId;
            copy.continuationPending = this.continuationPending;
            copy.continuationSourceSessionId = this.continuationSourceSessionId;
            copy.cwd = this.cwd;
            copy.model = this.model;
            copy.permissionMode = this.permissionMode;
            copy.reasoningEffort = this.reasoningEffort;
            copy.codexProviderId = this.codexProviderId;
            copy.codexRequestMode = this.codexRequestMode;
            copy.codexBaseUrlSource = this.codexBaseUrlSource;
            copy.codexEffectiveConfigSource = this.codexEffectiveConfigSource;
            copy.titleBindingMode = normalizeTitleBindingMode(this.titleBindingMode);
            return copy;
        }

        /**
         * 获取生效中的标题绑定模式。
         * 旧版本持久化数据未写入该字段时，默认按“跟随会话标题”处理。
         *
         * @return 生效的标题绑定模式
         */
        public String getEffectiveTitleBindingMode() {
            return normalizeTitleBindingMode(titleBindingMode);
        }

        /**
         * 获取当前快照生效中的运行时家族。
         * 当旧版本持久化数据尚未落 `runtimeFamily` 时，这里会优先根据 Codex binding 和 provider 自动推断，
         * 保证历史恢复与 Force Refresh 不因为旧数据缺字段而退化。
         *
         * @return 生效中的运行时家族，当前仅返回 `claude` 或 `codex`
         */
        public String getEffectiveRuntimeFamily() {
            return SessionRuntimeFamily.resolve(provider, runtimeFamily, buildCodexBindingSnapshot());
        }

        /**
         * 基于当前持久化字段构造只读 Codex binding 快照。
         * 该快照仅用于运行时家族推断，不承担额外持久化职责。
         *
         * @return 可用于推断的 Codex binding；若字段不足则返回 null
         */
        @Nullable
        public CodexSessionBinding buildCodexBindingSnapshot() {
            CodexSessionBinding binding = new CodexSessionBinding(
                    codexProviderId,
                    model,
                    codexRequestMode,
                    codexBaseUrlSource,
                    codexEffectiveConfigSource
            );
            return binding.isMeaningful() ? binding : null;
        }
    }

    /**
     * Persistent state class.
     */
    public static class State {
        /**
         * Map from tab index to tab name.
         */
        public Map<Integer, String> tabNames = new HashMap<>();

        /**
         * Map from tab index to tab session state.
         */
        public Map<Integer, TabSessionState> tabSessions = new HashMap<>();

        /**
         * Number of tabs.
         */
        public int tabCount = 1;
    }

    private static String normalizeTitleBindingMode(@Nullable String titleBindingMode) {
        if (TITLE_BINDING_MODE_MANUAL_CUSTOM.equals(titleBindingMode)) {
            return TITLE_BINDING_MODE_MANUAL_CUSTOM;
        }
        return TITLE_BINDING_MODE_FOLLOW_SESSION_TITLE;
    }
}
