package com.github.claudecodegui.session;

import com.google.gson.JsonObject;

/**
 * 发送链路使用的运行时意图值对象。
 * 该对象承载“本条消息希望在哪个 runtime 上执行”的稳定快照，并显式区分消息来源和解析策略，
 * 让后端可以在真正发送瞬间判断是否需要静默切段，而不是依赖前端提前改写 live session。
 */
public class SendRuntimeIntent {

    private static final java.util.Map<String, TierTarget> CLAUDE_MODEL_TIER_TARGETS = java.util.Map.of(
            "fast", new TierTarget("claude-haiku-4-5", ""),
            "standard", new TierTarget("claude-sonnet-4-6", ""),
            "advanced", new TierTarget("claude-opus-4-7", ""),
            "review", new TierTarget("claude-opus-4-7", "")
    );
    private static final java.util.Map<String, TierTarget> CODEX_MODEL_TIER_TARGETS = java.util.Map.of(
            "fast", new TierTarget("gpt-5.4-mini", "low"),
            "standard", new TierTarget("gpt-5.5", "medium"),
            "advanced", new TierTarget("gpt-5.4", "high"),
            "review", new TierTarget("gpt-5.5", "high")
    );

    private final String sourceKind;
    private final String resolutionPolicy;
    private final String targetProvider;
    private final String targetRuntimeFamily;
    private final String targetModel;
    private final String targetReasoningEffort;
    private final String targetCodexProviderId;
    private final String targetModelTier;
    private final String lockedBy;

    /**
     * 构造发送运行时意图。
     *
     * @param sourceKind 消息来源类型，例如 chat / locked_task / system
     * @param resolutionPolicy 解析策略，例如 dynamic_at_execution / locked_at_enqueue
     * @param targetProvider 目标聊天 provider，当前为 claude 或 codex
     * @param targetRuntimeFamily 目标运行时家族；为空时按 provider 自动推断
     * @param targetModel 目标模型 ID
     * @param targetReasoningEffort 目标 reasoning effort，仅 Codex runtime 会真正消费
     * @param targetCodexProviderId 目标 Codex provider id；非 Codex runtime 允许为空
     * @param lockedBy 锁定任务来源；普通聊天为空
     */
    public SendRuntimeIntent(
            String sourceKind,
            String resolutionPolicy,
            String targetProvider,
            String targetRuntimeFamily,
            String targetModel,
            String targetReasoningEffort,
            String targetCodexProviderId,
            String lockedBy
    ) {
        this(
                sourceKind,
                resolutionPolicy,
                targetProvider,
                targetRuntimeFamily,
                targetModel,
                targetReasoningEffort,
                targetCodexProviderId,
                "",
                lockedBy
        );
    }

    /**
     * 构造发送运行时意图。
     *
     * @param sourceKind 消息来源类型，例如 chat / locked_task / system
     * @param resolutionPolicy 解析策略，例如 dynamic_at_execution / locked_at_enqueue
     * @param targetProvider 目标聊天 provider，当前为 claude 或 codex
     * @param targetRuntimeFamily 目标运行时家族；为空时按 provider 自动推断
     * @param targetModel 目标模型 ID
     * @param targetReasoningEffort 目标 reasoning effort，仅 Codex runtime 会真正消费
     * @param targetCodexProviderId 目标 Codex provider id；非 Codex runtime 允许为空
     * @param targetModelTier 目标模型档位；仅当调用方未显式给出具体模型时才参与统一解析
     * @param lockedBy 锁定任务来源；普通聊天为空
     */
    public SendRuntimeIntent(
            String sourceKind,
            String resolutionPolicy,
            String targetProvider,
            String targetRuntimeFamily,
            String targetModel,
            String targetReasoningEffort,
            String targetCodexProviderId,
            String targetModelTier,
            String lockedBy
    ) {
        this.sourceKind = firstNonBlank(sourceKind);
        this.resolutionPolicy = firstNonBlank(resolutionPolicy);
        this.targetProvider = firstNonBlank(targetProvider);
        this.targetRuntimeFamily = firstNonBlank(targetRuntimeFamily);
        this.targetModel = firstNonBlank(targetModel);
        this.targetReasoningEffort = firstNonBlank(targetReasoningEffort);
        this.targetCodexProviderId = firstNonBlank(targetCodexProviderId);
        this.targetModelTier = firstNonBlank(targetModelTier);
        this.lockedBy = firstNonBlank(lockedBy);
    }

    /**
     * 从发送 payload 中解析嵌套的 `runtimeIntent`。
     * 缺失或解析失败时返回空对象，保持向后兼容。
     *
     * @param payload send_message / send_message_with_attachments 的 JSON 负载
     * @return 解析后的运行时意图；若 payload 未携带则返回空意图
     */
    public static SendRuntimeIntent fromPayload(JsonObject payload) {
        if (payload == null || !payload.has("runtimeIntent") || payload.get("runtimeIntent").isJsonNull()) {
            return empty();
        }
        try {
            JsonObject runtimeIntent = payload.getAsJsonObject("runtimeIntent");
            if (runtimeIntent == null) {
                return empty();
            }
            return new SendRuntimeIntent(
                    readString(runtimeIntent, "sourceKind"),
                    readString(runtimeIntent, "resolutionPolicy"),
                    readString(runtimeIntent, "targetProvider"),
                    readString(runtimeIntent, "targetRuntimeFamily"),
                    readString(runtimeIntent, "targetModel"),
                    readString(runtimeIntent, "targetReasoningEffort"),
                    readString(runtimeIntent, "targetCodexProviderId"),
                    readString(runtimeIntent, "targetModelTier"),
                    readString(runtimeIntent, "lockedBy")
            );
        } catch (Exception ignored) {
            return empty();
        }
    }

    /**
     * 返回一个空的运行时意图，表示当前请求未显式携带 send-time runtime 信息。
     *
     * @return 空意图对象
     */
    public static SendRuntimeIntent empty() {
        return new SendRuntimeIntent("", "", "", "", "", "", "", "", "");
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public String getResolutionPolicy() {
        return resolutionPolicy;
    }

    public String getTargetProvider() {
        return targetProvider;
    }

    public String getTargetRuntimeFamily() {
        return targetRuntimeFamily;
    }

    public String getTargetModel() {
        return targetModel;
    }

    public String getTargetReasoningEffort() {
        return targetReasoningEffort;
    }

    public String getTargetCodexProviderId() {
        return targetCodexProviderId;
    }

    public String getTargetModelTier() {
        return targetModelTier;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    /**
     * 判断该意图是否携带了足以参与 send-time runtime 决策的有效字段。
     *
     * @return true 表示当前请求显式提供了运行时意图
     */
    public boolean isMeaningful() {
        return hasText(targetProvider)
                || hasText(targetRuntimeFamily)
                || hasText(targetModel)
                || hasText(targetReasoningEffort)
                || hasText(targetCodexProviderId)
                || hasText(targetModelTier);
    }

    /**
     * 判断当前意图是否显式携带了模型档位。
     *
     * @return true 表示本条消息声明了 targetModelTier
     */
    public boolean hasTargetModelTier() {
        return hasText(targetModelTier);
    }

    /**
     * 在真正进入发送链路前，把仅包含 `targetModelTier` 的意图解析成具体 runtime。
     * 如果调用方已经给出明确 `targetModel`，则保留原值并只把档位作为诊断信息。
     *
     * @return 包含解析后 intent、是否发生档位映射以及映射来源的结果对象
     */
    public ModelTierResolutionResult resolveTargetModelTier() {
        if (!hasTargetModelTier()) {
            return new ModelTierResolutionResult(this, "not_requested", false);
        }
        if (hasText(targetModel)) {
            return new ModelTierResolutionResult(this, "payload_explicit_target", false);
        }

        String runtimeFamily = resolveTargetRuntimeFamily();
        String normalizedTier = targetModelTier.trim().toLowerCase(java.util.Locale.ROOT);
        if (SessionRuntimeFamily.CODEX.equals(runtimeFamily)) {
            if (!hasText(targetCodexProviderId)) {
                throw new IllegalArgumentException(
                        "targetCodexProviderId is required when resolving codex targetModelTier=" + targetModelTier
                );
            }
            TierTarget tierTarget = CODEX_MODEL_TIER_TARGETS.get(normalizedTier);
            if (tierTarget == null) {
                throw new IllegalArgumentException("Unsupported codex targetModelTier: " + targetModelTier);
            }
            return new ModelTierResolutionResult(
                    withResolvedTarget(
                            SessionRuntimeFamily.CODEX,
                            SessionRuntimeFamily.CODEX,
                            tierTarget.modelId,
                            firstNonBlank(targetReasoningEffort, tierTarget.reasoningEffort),
                            targetCodexProviderId
                    ),
                    "codex_tier_policy",
                    true
            );
        }
        if (SessionRuntimeFamily.CLAUDE.equals(runtimeFamily)) {
            TierTarget tierTarget = CLAUDE_MODEL_TIER_TARGETS.get(normalizedTier);
            if (tierTarget == null) {
                throw new IllegalArgumentException("Unsupported claude targetModelTier: " + targetModelTier);
            }
            return new ModelTierResolutionResult(
                    withResolvedTarget(
                            SessionRuntimeFamily.CLAUDE,
                            SessionRuntimeFamily.CLAUDE,
                            tierTarget.modelId,
                            firstNonBlank(targetReasoningEffort, tierTarget.reasoningEffort),
                            ""
                    ),
                    "claude_tier_policy",
                    true
            );
        }
        throw new IllegalArgumentException(
                "targetModelTier requires targetProvider/targetRuntimeFamily to resolve to claude or codex"
        );
    }

    /**
     * 解析目标运行时家族。
     * 当前前端总会写入 targetProvider，但这里仍保留兜底推断，
     * 以兼容过渡期 payload 或测试桩只填写部分字段的场景。
     *
     * @return 规范化后的运行时家族，当前为 claude / codex
     */
    public String resolveTargetRuntimeFamily() {
        if (hasText(targetRuntimeFamily)) {
            return targetRuntimeFamily;
        }
        if (SessionRuntimeFamily.CODEX.equalsIgnoreCase(targetProvider) || hasText(targetCodexProviderId)) {
            return SessionRuntimeFamily.CODEX;
        }
        return SessionRuntimeFamily.CLAUDE;
    }

    /**
     * 判断当前会话的 active runtime 是否已经与目标意图一致。
     * 只要 provider/model/reasoning/codex provider 任一关键字段不同，就需要静默切段。
     *
     * @param session 当前活动会话
     * @return true 表示发送前需要先完成 runtime 切换
     */
    public boolean requiresRuntimeSwitch(ClaudeSession session) {
        return hasText(determineSwitchReason(session));
    }

    /**
     * 给出触发 send-time runtime switch 的首个原因，便于日志和测试断言。
     *
     * @param session 当前活动会话
     * @return provider / model / reasoning / codex_provider；若无需切换则返回空串
     */
    public String determineSwitchReason(ClaudeSession session) {
        if (!isMeaningful() || session == null) {
            return "";
        }
        String targetRuntimeFamilyValue = resolveTargetRuntimeFamily();
        validateCodexTargetProviderId(targetRuntimeFamilyValue);
        String currentRuntimeFamily = SessionRuntimeFamily.resolve(
                session.getProvider(),
                null,
                session.getState().getCodexSessionBinding()
        );
        if (!safeEquals(firstNonBlank(targetProvider, targetRuntimeFamilyValue), firstNonBlank(session.getProvider()))) {
            return "provider";
        }
        if (!safeEquals(firstNonBlank(targetModel), firstNonBlank(session.getModel()))) {
            return "model";
        }
        if (SessionRuntimeFamily.CODEX.equals(targetRuntimeFamilyValue)
                && !safeEquals(firstNonBlank(targetReasoningEffort), firstNonBlank(session.getReasoningEffort()))) {
            return "reasoning";
        }
        if (SessionRuntimeFamily.CODEX.equals(targetRuntimeFamilyValue)) {
            String currentCodexProviderId = session.getState().getCodexSessionBinding() != null
                    ? firstNonBlank(session.getState().getCodexSessionBinding().getProviderId())
                    : "";
            if (!safeEquals(firstNonBlank(targetCodexProviderId), currentCodexProviderId)) {
                return "codex_provider";
            }
        }
        if (!safeEquals(firstNonBlank(targetRuntimeFamilyValue), firstNonBlank(currentRuntimeFamily))) {
            return "provider";
        }
        return "";
    }

    /**
     * 输出稳定的日志摘要，避免在多个后端组件里重复拼装字段。
     *
     * @return 适合 idea.log 检索的结构化摘要
     */
    /**
     * 校验 Codex send-time runtime intent 是否携带了完整的 provider 维度。
     * 当前发送目标一旦声明为 Codex，就必须同步带上 targetCodexProviderId；
     * 否则后端既无法确认真正目标 provider，也会把缺字段误判成 codex_provider 差异，
     * 进而在每次发送时重复创建 continued segment。
     *
     * @param targetRuntimeFamilyValue 当前 intent 解析得到的目标 runtime family
     * @throws IllegalArgumentException 当 Codex 目标缺失 providerId 时抛出，强制暴露前端构造问题
     */
    private void validateCodexTargetProviderId(String targetRuntimeFamilyValue) {
        if (SessionRuntimeFamily.CODEX.equals(targetRuntimeFamilyValue) && !hasText(targetCodexProviderId)) {
            throw new IllegalArgumentException(
                    "targetCodexProviderId is required for codex send-time runtime intent"
            );
        }
    }

    public String toLogString() {
        return "{sourceKind=" + firstNonBlank(sourceKind)
                + ", resolutionPolicy=" + firstNonBlank(resolutionPolicy)
                + ", targetProvider=" + firstNonBlank(targetProvider)
                + ", targetRuntimeFamily=" + resolveTargetRuntimeFamily()
                + ", targetModel=" + firstNonBlank(targetModel)
                + ", targetReasoningEffort=" + firstNonBlank(targetReasoningEffort)
                + ", targetCodexProviderId=" + firstNonBlank(targetCodexProviderId)
                + ", targetModelTier=" + firstNonBlank(targetModelTier)
                + ", lockedBy=" + firstNonBlank(lockedBy)
                + "}";
    }

    /**
     * 返回应用模型档位解析后的新意图对象，保留原始来源信息与锁定来源。
     *
     * @param provider 解析后的 provider
     * @param runtimeFamily 解析后的 runtime 家族
     * @param model 解析后的模型 id
     * @param reasoningEffort 解析后的 reasoning effort
     * @param codexProviderId 解析后的 Codex provider id
     * @return 带具体 target runtime 的新意图对象
     */
    private SendRuntimeIntent withResolvedTarget(
            String provider,
            String runtimeFamily,
            String model,
            String reasoningEffort,
            String codexProviderId
    ) {
        return new SendRuntimeIntent(
                sourceKind,
                resolutionPolicy,
                firstNonBlank(provider, targetProvider),
                firstNonBlank(runtimeFamily, targetRuntimeFamily),
                firstNonBlank(model, targetModel),
                firstNonBlank(reasoningEffort, targetReasoningEffort),
                firstNonBlank(codexProviderId, targetCodexProviderId),
                targetModelTier,
                lockedBy
        );
    }

    /**
     * 从 JSON 对象中安全读取字符串字段。
     *
     * @param json 源 JSON
     * @param key 目标字段名
     * @return 去空白后的字符串；缺失时返回空串
     */
    private static String readString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return "";
        }
        return firstNonBlank(json.get(key).getAsString());
    }

    /**
     * 判断字符串是否有有效内容。
     *
     * @param value 待判断字符串
     * @return true 表示非空白
     */
    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 返回第一个非空白字符串。
     *
     * @param values 候选字符串列表
     * @return 第一个非空白值；若都为空则返回空串
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * 对两个字符串做空值安全比较。
     *
     * @param left 左值
     * @param right 右值
     * @return true 表示两侧规范化后完全一致
     */
    private static boolean safeEquals(String left, String right) {
        return firstNonBlank(left).equals(firstNonBlank(right));
    }

    /**
     * 模型档位解析结果。
     * 该对象显式返回“最终可发送的 runtime intent”以及映射来源，便于 SessionHandler
     * 在写诊断日志时区分“只是带了 tier 标签”与“真的发生了 tier -> model 的稳定解析”。
     */
    public static final class ModelTierResolutionResult {
        private final SendRuntimeIntent resolvedIntent;
        private final String mappingSource;
        private final boolean resolvedFromTier;

        /**
         * 构造模型档位解析结果。
         *
         * @param resolvedIntent 解析后应进入发送链路的 runtime intent
         * @param mappingSource 本次解析采用的映射来源摘要
         * @param resolvedFromTier true 表示本次确实通过档位策略补全了具体模型
         */
        public ModelTierResolutionResult(
                SendRuntimeIntent resolvedIntent,
                String mappingSource,
                boolean resolvedFromTier
        ) {
            this.resolvedIntent = resolvedIntent;
            this.mappingSource = firstNonBlank(mappingSource);
            this.resolvedFromTier = resolvedFromTier;
        }

        public SendRuntimeIntent getResolvedIntent() {
            return resolvedIntent;
        }

        public String getMappingSource() {
            return mappingSource;
        }

        public boolean isResolvedFromTier() {
            return resolvedFromTier;
        }
    }

    /**
     * 单个档位映射到的最小运行时目标。
     * 这里故意只保留发送链路真正需要的 model/reasoning 两个字段，避免把 UI 配置状态耦合进后端解析器。
     */
    private static final class TierTarget {
        private final String modelId;
        private final String reasoningEffort;

        /**
         * 构造单个档位的目标模型定义。
         *
         * @param modelId 解析后应落地的具体模型 id
         * @param reasoningEffort 解析后建议使用的 reasoning effort；不适用时传空串
         */
        private TierTarget(String modelId, String reasoningEffort) {
            this.modelId = firstNonBlank(modelId);
            this.reasoningEffort = firstNonBlank(reasoningEffort);
        }
    }
}
