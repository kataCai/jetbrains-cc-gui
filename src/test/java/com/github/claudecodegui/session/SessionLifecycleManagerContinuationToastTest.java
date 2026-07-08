package com.github.claudecodegui.session;

import com.github.claudecodegui.i18n.ClaudeCodeGuiBundle;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

/**
 * 验证 continued segment 的完成提示语义与“新建会话”明确区分。
 * 该测试用于约束回归修复目标：切模型继续会话成功后，后端不能再沿用新建会话的提示文案。
 */
public class SessionLifecycleManagerContinuationToastTest {

    /**
     * continued segment 应使用独立的提示文案键，避免用户误解为创建了全新会话。
     */
    @Test
    public void shouldUseDedicatedToastMessageForContinuedConversationReady() {
        String newSessionMessage = ClaudeCodeGuiBundle.message("toast.newSessionCreatedReady");
        String continuedConversationMessage = ClaudeCodeGuiBundle.message("toast.conversationContinuedReady");

        assertFalse("continued segment 提示文案不能为空", continuedConversationMessage == null || continuedConversationMessage.trim().isEmpty());
        assertNotEquals("continued segment 不应继续复用“新建会话”提示语义", newSessionMessage, continuedConversationMessage);
    }
}
