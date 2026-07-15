package com.github.claudecodegui.util;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 插件构建指纹信息读取器。
 * 该类从 Gradle 生成的 `plugin-build-info.properties` 中读取插件版本、构建时间、
 * Git 提交和前端 bundle 哈希，并提供稳定的日志描述文本。
 * 适用场景是启动日志、bridge 解压日志和问题排查；它不参与业务分支判断，
 * 因此资源缺失时会回退为 `unknown`，避免影响插件正常加载。
 */
public final class PluginBuildInfo {

    private static final String RESOURCE_NAME = "/plugin-build-info.properties";
    private static final String UNKNOWN = "unknown";
    static final String WEBVIEW_BUNDLE_SHA256_PLACEHOLDER = "__CC_GUI_WEBVIEW_BUNDLE_SHA256__";
    private static final Pattern STAMPED_WEBVIEW_BUNDLE_SHA256_PATTERN =
            Pattern.compile("(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])");

    private final String pluginVersion;
    private final String gitCommit;
    private final String gitBranch;
    private final String buildTime;
    private final String webviewBundleSha256;

    /**
     * 创建不可变构建指纹对象。
     *
     * @param pluginVersion 插件版本号
     * @param gitCommit 构建时对应的 Git 短提交
     * @param gitBranch 构建时所在 Git 分支
     * @param buildTime 构建时间，使用 ISO-8601 字符串
     * @param webviewBundleSha256 前端 HTML bundle 的 SHA-256
     */
    private PluginBuildInfo(
            String pluginVersion,
            String gitCommit,
            String gitBranch,
            String buildTime,
            String webviewBundleSha256
    ) {
        this.pluginVersion = normalize(pluginVersion);
        this.gitCommit = normalize(gitCommit);
        this.gitBranch = normalize(gitBranch);
        this.buildTime = normalize(buildTime);
        this.webviewBundleSha256 = normalize(webviewBundleSha256);
    }

    /**
     * 从 classpath 资源中读取插件构建指纹。
     * 资源由 Gradle `generatePluginBuildInfo` 任务生成；若测试、IDE 热加载或异常打包导致资源不存在，
     * 返回包含 `unknown` 字段的对象，保证调用方仍能输出可搜索日志。
     *
     * @return 当前插件包内嵌的构建指纹
     */
    public static PluginBuildInfo load() {
        Properties properties = new Properties();
        try (InputStream input = PluginBuildInfo.class.getResourceAsStream(RESOURCE_NAME)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (Exception ignored) {
            // 中文注释：构建指纹只用于诊断日志，读取失败不能阻断插件启动或测试执行。
        }
        return new PluginBuildInfo(
                properties.getProperty("pluginVersion"),
                properties.getProperty("gitCommit"),
                properties.getProperty("gitBranch"),
                properties.getProperty("buildTime"),
                properties.getProperty("webviewBundleSha256")
        );
    }

    /**
     * 获取插件版本号。
     *
     * @return 插件版本号；缺失时返回 `unknown`
     */
    public String getPluginVersion() {
        return pluginVersion;
    }

    /**
     * 获取构建时 Git 短提交。
     *
     * @return Git 短提交；缺失时返回 `unknown`
     */
    public String getGitCommit() {
        return gitCommit;
    }

    /**
     * 获取构建时 Git 分支。
     *
     * @return Git 分支；缺失时返回 `unknown`
     */
    public String getGitBranch() {
        return gitBranch;
    }

    /**
     * 获取构建时间。
     *
     * @return ISO-8601 构建时间；缺失时返回 `unknown`
     */
    public String getBuildTime() {
        return buildTime;
    }

    /**
     * 获取前端 bundle 哈希。
     *
     * @return 前端 HTML bundle 的 SHA-256；缺失时返回 `unknown`
     */
    public String getWebviewBundleSha256() {
        return webviewBundleSha256;
    }

    /**
     * 构造适合 idea.log 搜索的单行构建指纹描述。
     * 字段名保持稳定，方便用户反馈日志时直接搜索 `PluginBuildInfo` 或 `webviewBundleSha256`。
     *
     * @return 单行构建指纹文本
     */
    public String describeForLog() {
        return "PluginBuildInfo{"
                + "pluginVersion=" + pluginVersion
                + ", gitCommit=" + gitCommit
                + ", gitBranch=" + gitBranch
                + ", buildTime=" + buildTime
                + ", webviewBundleSha256=" + webviewBundleSha256
                + '}';
    }

    /**
     * 计算前端 bundle 的归一化 SHA-256。
     * 这里会先把最终产物里内嵌的 bundle 哈希回退成统一占位符，再参与摘要计算，
     * 从而保证“模板态 HTML”和“已回填哈希的最终 HTML”得到同一份构建指纹。
     *
     * @param html 原始 HTML 文本，可以是构建前模板，也可以是 copy-dist 后的最终产物
     * @return 归一化后的 SHA-256 十六进制串；输入为空时返回 `unknown`
     */
    static String computeNormalizedWebviewBundleSha256(String html) {
        if (html == null || html.trim().isEmpty()) {
            return UNKNOWN;
        }
        String normalizedHtml = normalizeStampedWebviewBundleHash(html);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = normalizedHtml.getBytes(StandardCharsets.UTF_8);
            digest.update(bytes, 0, bytes.length);
            StringBuilder builder = new StringBuilder();
            for (byte current : digest.digest()) {
                builder.append(String.format("%02x", current & 0xff));
            }
            return builder.toString();
        } catch (Exception ignored) {
            return UNKNOWN;
        }
    }

    /**
     * 将最终产物里内嵌的 bundle 哈希回退成统一占位符。
     * 当前构建只会向 HTML 中注入一个 64 位十六进制 bundle 哈希；若未来不再满足这个前提，
     * 这里宁可保守返回原文本，也不盲目替换多个哈希字面量。
     *
     * @param html 原始 HTML 文本
     * @return 归一化后的 HTML 文本
     */
    static String normalizeStampedWebviewBundleHash(String html) {
        if (html == null || html.isEmpty() || html.contains(WEBVIEW_BUNDLE_SHA256_PLACEHOLDER)) {
            return html;
        }
        Matcher matcher = STAMPED_WEBVIEW_BUNDLE_SHA256_PATTERN.matcher(html);
        if (!matcher.find()) {
            return html;
        }
        String stampedHash = matcher.group();
        if (matcher.find()) {
            return html;
        }
        return html.replace(stampedHash, WEBVIEW_BUNDLE_SHA256_PLACEHOLDER);
    }

    /**
     * 归一化构建字段。
     *
     * @param value 原始字段值
     * @return 非空字段值；空白或 null 统一返回 `unknown`
     */
    private static String normalize(String value) {
        return value == null || value.trim().isEmpty() ? UNKNOWN : value.trim();
    }
}
