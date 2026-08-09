package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.BaseMessageHandler;
import com.github.claudecodegui.handler.core.HandlerContext;

/**
 * ProviderHandler 负责处理前端发往后端的供应商管理消息。
 * 当前同时覆盖 Claude 与 Codex 两套供应商配置的增删改查、排序、授权与状态查询能力，
 * 并统一作为设置页与运行时桥接事件进入 Provider 领域逻辑的入口。
 *
 * 约束说明：
 * 1. 设置页中的 Codex 卡片现在只承担“配置管理”和“本地配置授权”职责，不再直接驱动会话级切换。
 * 2. `switch_codex_provider` 事件仍然保留给内部默认值机制和兼容路径使用，避免一次性移除后破坏旧调用方；
 *    但聊天区与设置页的显式切换入口已经收敛到标签页运行时模型选择链路。
 * 3. 该处理器本身不做复杂业务判断，具体逻辑下沉到 Claude/Codex 对应的 Operations 类中。
 */
public class ProviderHandler extends BaseMessageHandler {

    private static final String[] SUPPORTED_TYPES = {
            // Claude provider operations
            "get_providers",
            "get_current_claude_config",
            "get_thinking_enabled",
            "set_thinking_enabled",
            "add_provider",
            "update_provider",
            "delete_provider",
            "switch_provider",
            "get_active_provider",
            "preview_cc_switch_import",
            "open_file_chooser_for_cc_switch",
            "save_imported_providers",
            "sort_providers",
            // Codex provider operations
            "get_codex_providers",
            "get_current_codex_config",
            "add_codex_provider",
            "update_codex_provider",
            "delete_codex_provider",
            "switch_codex_provider",
            "authorize_codex_local_config",
            "revoke_codex_local_config_authorization",
            "get_active_codex_provider",
            "test_codex_provider",
            "fetch_codex_provider_models",
            "fetch_codex_provider_models_from_draft",
            "sort_codex_providers"
    };

    private final ClaudeProviderOperations claudeOps;
    private final CodexProviderOperations codexOps;
    private final ProviderImportExportSupport importExportSupport;
    private final ProviderOrderingService orderingService;

    /**
     * 创建 ProviderHandler，并初始化 Claude/Codex 供应商相关的子操作对象。
     *
     * @param context 当前消息处理所需的共享上下文，包含配置、桥接与持久化依赖
     */
    public ProviderHandler(HandlerContext context) {
        super(context);
        this.claudeOps = new ClaudeProviderOperations(context);
        this.codexOps = new CodexProviderOperations(context);
        this.importExportSupport = new ProviderImportExportSupport(context, claudeOps);
        this.orderingService = new ProviderOrderingService(context, claudeOps, codexOps);
    }

    /**
     * 返回当前处理器支持的消息类型列表。
     *
     * @return 可被该处理器识别并处理的消息类型数组
     */
    @Override
    public String[] getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    /**
     * 按消息类型分发供应商管理请求到对应的 Claude/Codex 子操作。
     * 该方法只做路由分发，不直接持有复杂业务状态。
     *
     * @param type 前端发来的消息类型
     * @param content 与消息类型对应的 JSON 或字符串载荷
     * @return 若当前处理器已接管该消息则返回 true，否则返回 false 交给后续处理器
     */
    @Override
    public boolean handle(String type, String content) {
        switch (type) {
            // Claude provider operations
            case "get_providers":
                claudeOps.handleGetProviders();
                return true;
            case "get_current_claude_config":
                claudeOps.handleGetCurrentClaudeConfig();
                return true;
            case "get_thinking_enabled":
                claudeOps.handleGetThinkingEnabled();
                return true;
            case "set_thinking_enabled":
                claudeOps.handleSetThinkingEnabled(content);
                return true;
            case "add_provider":
                claudeOps.handleAddProvider(content);
                return true;
            case "update_provider":
                claudeOps.handleUpdateProvider(content);
                return true;
            case "delete_provider":
                claudeOps.handleDeleteProvider(content);
                return true;
            case "switch_provider":
                claudeOps.handleSwitchProvider(content);
                return true;
            case "get_active_provider":
                claudeOps.handleGetActiveProvider();
                return true;
            case "preview_cc_switch_import":
                importExportSupport.handlePreviewCcSwitchImport();
                return true;
            case "open_file_chooser_for_cc_switch":
                importExportSupport.handleOpenFileChooserForCcSwitch();
                return true;
            case "save_imported_providers":
                importExportSupport.handleSaveImportedProviders(content);
                return true;
            case "sort_providers":
                orderingService.handleSortProviders(content);
                return true;
            // Codex provider operations
            case "get_codex_providers":
                codexOps.handleGetCodexProviders();
                return true;
            case "get_current_codex_config":
                codexOps.handleGetCurrentCodexConfig();
                return true;
            case "add_codex_provider":
                codexOps.handleAddCodexProvider(content);
                return true;
            case "update_codex_provider":
                codexOps.handleUpdateCodexProvider(content);
                return true;
            case "delete_codex_provider":
                codexOps.handleDeleteCodexProvider(content);
                return true;
            case "switch_codex_provider":
                // 仅保留给内部默认 provider 机制和兼容调用链使用。
                // 聊天区与设置页已不再通过该事件直接触发会话级 provider 切换。
                codexOps.handleSwitchCodexProvider(content);
                return true;
            case "authorize_codex_local_config":
                codexOps.handleAuthorizeCodexLocalConfig(content);
                return true;
            case "revoke_codex_local_config_authorization":
                codexOps.handleRevokeCodexLocalConfigAuthorization(content);
                return true;
            case "get_active_codex_provider":
                codexOps.handleGetActiveCodexProvider();
                return true;
            case "test_codex_provider":
                codexOps.handleTestCodexProvider(content);
                return true;
            case "fetch_codex_provider_models":
                codexOps.handleFetchCodexProviderModels(content);
                return true;
            case "fetch_codex_provider_models_from_draft":
                codexOps.handleFetchCodexProviderModelsFromDraft(content);
                return true;
            case "sort_codex_providers":
                orderingService.handleSortCodexProviders(content);
                return true;
            default:
                return false;
        }
    }
}
