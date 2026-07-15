package com.github.claudecodegui.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证插件构建指纹读取逻辑。
 * 这些断言用于保证运行时日志可以稳定输出插件版本、构建时间、Git 提交和前端 bundle 哈希，
 * 避免问题排查时只能根据现象猜测用户安装的是否为最新插件包。
 */
public class PluginBuildInfoTest {

    /**
     * 验证构建指纹描述中包含关键字段名和值。
     * 该测试不绑定具体 commit 或构建时间，只要求生成资源能被运行时读取，
     * 并且日志文本具备后续排查可搜索的稳定字段。
     */
    @Test
    public void shouldDescribePluginBuildInfoWithStableDiagnosticFields() {
        PluginBuildInfo buildInfo = PluginBuildInfo.load();

        assertNotNull(buildInfo);
        assertFalse(buildInfo.getPluginVersion().trim().isEmpty());
        assertFalse(buildInfo.getBuildTime().trim().isEmpty());
        assertFalse(buildInfo.getGitCommit().trim().isEmpty());
        assertFalse(buildInfo.getWebviewBundleSha256().trim().isEmpty());
        assertTrue(buildInfo.describeForLog().contains("pluginVersion="));
        assertTrue(buildInfo.describeForLog().contains("webviewBundleSha256="));
    }

    /**
     * 验证 webview bundle 指纹在“模板占位符”和“已回填内嵌哈希”两种形态下保持一致。
     * review 指出旧实现先算哈希再把哈希写回 HTML，导致前端日志和插件资源指纹天然不一致；
     * 这里要求运行时与构建时都基于同一份归一化内容计算指纹。
     */
    @Test
    public void shouldComputeSameBundleFingerprintForTemplateAndStampedHtml() {
        String templateHtml = "<script>const hash=\"__CC_GUI_WEBVIEW_BUNDLE_SHA256__\";</script>";
        String stampedHtml = "<script>const hash=\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\";</script>";

        assertEquals(
                PluginBuildInfo.computeNormalizedWebviewBundleSha256(templateHtml),
                PluginBuildInfo.computeNormalizedWebviewBundleSha256(stampedHtml)
        );
    }
}
