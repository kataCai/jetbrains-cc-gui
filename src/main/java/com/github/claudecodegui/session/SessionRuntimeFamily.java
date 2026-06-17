package com.github.claudecodegui.session;

import org.jetbrains.annotations.Nullable;

/**
 * 会话恢复运行时家族工具。
 * 该类用于统一表达“当前会话实际应该走哪条恢复链路”，避免不同入口分别根据 display provider、
 * 当前 UI provider 或 Codex binding 做分散判断，导致历史会话恢复和窗口重建后走错分支。
 * <p>
 * 约束说明：
 * 1. `displayProvider` 仍然保留给 UI 展示与历史列表使用，不直接决定恢复分支。
 * 2. `runtimeFamily` 只表达底层恢复主干，目前稳定支持 `claude` 与 `codex`。
 * 3. 旧数据缺失 `runtimeFamily` 时，允许根据 provider 与 Codex binding 做兼容推断。
 * 4. Minimax 等品牌 provider 短期通过该推断逻辑复用 Codex 恢复链路，后续可再补显式落盘。
 */
public final class SessionRuntimeFamily {

    public static final String CLAUDE = "claude";
    public static final String CODEX = "codex";
    public static final String MINIMAX = "minimax";

    private SessionRuntimeFamily() {
    }

    /**
     * 规范化运行时家族值。
     *
     * @param runtimeFamily 原始运行时家族字符串
     * @return 规范化后的运行时家族；无法识别时返回 null
     */
    @Nullable
    public static String normalize(@Nullable String runtimeFamily) {
        if (runtimeFamily == null || runtimeFamily.trim().isEmpty()) {
            return null;
        }
        String normalized = runtimeFamily.trim().toLowerCase();
        if (CODEX.equals(normalized)) {
            return CODEX;
        }
        if (CLAUDE.equals(normalized)) {
            return CLAUDE;
        }
        return null;
    }

    /**
     * 根据展示 provider、显式 runtimeFamily 与 Codex binding 推断真实恢复链路。
     * 该方法优先信任显式 `runtimeFamily`，仅在旧数据缺失时才进入兼容推断。
     *
     * @param displayProvider UI 或历史列表里的 provider
     * @param runtimeFamily 显式记录的运行时家族
     * @param codexBinding Codex 会话绑定快照，可为空
     * @return 最终应采用的运行时家族，默认回退为 `claude`
     */
    public static String resolve(
            @Nullable String displayProvider,
            @Nullable String runtimeFamily,
            @Nullable CodexSessionBinding codexBinding
    ) {
        String normalizedRuntimeFamily = normalize(runtimeFamily);
        if (normalizedRuntimeFamily != null) {
            return normalizedRuntimeFamily;
        }

        if (codexBinding != null && codexBinding.isMeaningful()) {
            return CODEX;
        }

        String normalizedProvider = normalizeProvider(displayProvider);
        if (CODEX.equals(normalizedProvider)) {
            return CODEX;
        }
        if (MINIMAX.equals(normalizedProvider)) {
            return CODEX;
        }
        return CLAUDE;
    }

    /**
     * 判断给定 provider 是否属于当前已知的 Codex 主干展示语义。
     *
     * @param provider 原始 provider
     * @return 归一化后是否为 codex
     */
    public static boolean isCodexProvider(@Nullable String provider) {
        return CODEX.equals(normalize(provider));
    }

    /**
     * 规范化展示层 provider 值。
     * 这里保留 `minimax` 之类的品牌 provider，供兼容推断阶段使用，
     * 避免复用 runtimeFamily 的规范化逻辑后，把有意义的展示 provider 提前折叠为 null。
     *
     * @param provider 原始 provider
     * @return 规范化后的 provider；若为空白则返回 null
     */
    @Nullable
    private static String normalizeProvider(@Nullable String provider) {
        if (provider == null || provider.trim().isEmpty()) {
            return null;
        }
        return provider.trim().toLowerCase();
    }
}
