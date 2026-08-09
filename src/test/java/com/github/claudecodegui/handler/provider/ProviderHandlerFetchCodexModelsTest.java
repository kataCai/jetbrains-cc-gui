package com.github.claudecodegui.handler.provider;

import com.github.claudecodegui.handler.core.HandlerContext;
import com.github.claudecodegui.provider.codex.CodexSDKBridge;
import com.github.claudecodegui.settings.CodemossSettingsService;
import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 ProviderHandler 对“获取 Codex provider 模型列表”消息的分发能力。
 * 这里不重复覆盖 discovery/merge 细节，而是只锁定最外层路由约束：
 * 1. `SUPPORTED_TYPES` 必须显式包含新消息类型。
 * 2. `handle(...)` 必须把请求路由到 Codex provider 分支，而不是落入默认 false。
 */
public class ProviderHandlerFetchCodexModelsTest {

    /**
     * 验证目标：
     * 新增的 `fetch_codex_provider_models` 消息必须被 ProviderHandler 接管；
     * 即使后续因为 provider 不存在而失败，也应该走到 Codex 分支并通过 JS 回调返回错误。
     *
     * 断言意图：
     * 1. `handle(...)` 返回 true，说明消息类型被识别。
     * 2. 当前测试桩没有任何 provider，因此最终应触发 `window.showError`。
     */
    @Test
    public void shouldDispatchFetchCodexProviderModelsMessageToCodexOperations() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        ProviderHandler handler = new ProviderHandler(
                new HandlerContext(
                        null,
                        null,
                        new CodexSDKBridge(),
                        new MissingProviderSettingsService(),
                        jsCallback
                )
        );

        boolean handled = handler.handle("fetch_codex_provider_models", "{\"id\":\"missing-provider\"}");

        assertTrue(handled);
        assertEquals("window.showError", jsCallback.lastFunctionName);
    }

    /**
     * 验证编辑弹窗使用的草稿级模型拉取消息会进入 Codex provider 分支。
     * 该消息不依赖已保存 provider id；当前测试使用缺失 Base URL 的草稿触发校验错误，
     * 只断言最外层路由已识别新消息类型并返回错误回调。
     */
    @Test
    public void shouldDispatchDraftCodexProviderModelsMessageToCodexOperations() {
        RecordingJsCallback jsCallback = new RecordingJsCallback();
        ProviderHandler handler = new ProviderHandler(
                new HandlerContext(
                        null,
                        null,
                        new CodexSDKBridge(),
                        new MissingProviderSettingsService(),
                        jsCallback
                )
        );

        boolean handled = handler.handle(
                "fetch_codex_provider_models_from_draft",
                "{\"providerId\":\"draft-provider\",\"authMode\":\"api_key\",\"requestMode\":\"codex_sdk\",\"apiKey\":\"sk-test\"}"
        );

        assertTrue(handled);
        assertEquals("window.showError", jsCallback.lastFunctionName);
    }

    /**
     * 设置服务桩：始终返回“provider 不存在”。
     * 该桩用于把测试聚焦在最外层消息分发，不依赖任何真实 provider 配置或网络访问。
     */
    private static class MissingProviderSettingsService extends CodemossSettingsService {
        @Override
        public JsonObject getCodexProviderById(String providerId) {
            return null;
        }
    }

    /**
     * 记录最后一次 JS 回调。
     * 这里只需要知道 handler 最终调用了哪个前端函数，因此保留最小字段即可。
     */
    private static class RecordingJsCallback implements HandlerContext.JsCallback {
        private String lastFunctionName = "";

        @Override
        public void callJavaScript(String functionName, String... args) {
            this.lastFunctionName = functionName;
        }

        @Override
        public String escapeJs(String str) {
            return str;
        }
    }
}
