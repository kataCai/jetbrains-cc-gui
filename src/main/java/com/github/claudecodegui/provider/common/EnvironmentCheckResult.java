package com.github.claudecodegui.provider.common;

/**
 * 环境检查结果。
 * 该对象用于表达 SDKBridge 在真正启动 Node.js 子进程前的环境可用性，
 * 并把失败原因拆分成可区分的类型，避免上层把所有问题都误报成“未找到 Node.js”。
 *
 * <p>适用场景：</p>
 * <p>1. 新建窗口初始化时，需要区分 Node 路径无效、bridge 未提取完成、入口脚本缺失等问题。</p>
 * <p>2. Claude/Codex 发送消息前，需要先做前置预检，避免子进程启动后只返回模糊的退出码。</p>
 * <p>3. 测试与日志需要稳定断言具体失败类别，而不是依赖文案模糊匹配。</p>
 *
 * <p>边界说明：</p>
 * <p>1. 该对象只描述“启动前环境检查”结果，不表示运行中消息处理是否成功。</p>
 * <p>2. 调用方必须优先依据 FailureCode 做逻辑分支，不能只依赖 detailMessage。</p>
 * <p>3. nodePath、nodeVersion、channelScriptPath 允许为空，表示当前分支拿不到对应上下文。</p>
 */
public class EnvironmentCheckResult {

    /**
     * 环境检查失败类型。
     */
    public enum FailureCode {
        /**
         * 未指定或未检测到可用的 Node.js 路径。
         */
        NODE_NOT_FOUND,
        /**
         * Node.js 进程可以启动，但 `node --version` 校验失败。
         */
        NODE_EXECUTION_FAILED,
        /**
         * ai-bridge 目录尚未就绪，通常表示提取仍在进行中或路径解析尚未稳定。
         */
        BRIDGE_NOT_READY,
        /**
         * ai-bridge 目录存在，但核心入口脚本缺失。
         */
        CHANNEL_SCRIPT_MISSING,
        /**
         * 其他未分类异常。
         */
        UNKNOWN
    }

    private final boolean ready;
    private final FailureCode failureCode;
    private final String detailMessage;
    private final String nodePath;
    private final String nodeVersion;
    private final String channelScriptPath;

    private EnvironmentCheckResult(
            boolean ready,
            FailureCode failureCode,
            String detailMessage,
            String nodePath,
            String nodeVersion,
            String channelScriptPath
    ) {
        this.ready = ready;
        this.failureCode = failureCode;
        this.detailMessage = detailMessage;
        this.nodePath = nodePath;
        this.nodeVersion = nodeVersion;
        this.channelScriptPath = channelScriptPath;
    }

    /**
     * 构造成功结果。
     *
     * @param nodePath 当前实际使用的 Node.js 路径
     * @param nodeVersion 校验得到的 Node.js 版本
     * @param channelScriptPath ai-bridge 入口脚本路径
     * @return 成功结果
     */
    public static EnvironmentCheckResult ready(String nodePath, String nodeVersion, String channelScriptPath) {
        return new EnvironmentCheckResult(true, null, null, nodePath, nodeVersion, channelScriptPath);
    }

    /**
     * 构造成失败结果。
     *
     * @param failureCode 结构化失败类型
     * @param detailMessage 用于日志和展示的详细信息
     * @param nodePath 当前参与校验的 Node.js 路径
     * @param nodeVersion 当前已探测到的 Node.js 版本，未知时可为空
     * @param channelScriptPath 当前涉及的 ai-bridge 脚本路径，未知时可为空
     * @return 失败结果
     */
    public static EnvironmentCheckResult failed(
            FailureCode failureCode,
            String detailMessage,
            String nodePath,
            String nodeVersion,
            String channelScriptPath
    ) {
        return new EnvironmentCheckResult(false, failureCode, detailMessage, nodePath, nodeVersion, channelScriptPath);
    }

    /**
     * 当前环境是否可直接用于启动 bridge。
     *
     * @return true 表示环境可用
     */
    public boolean isReady() {
        return ready;
    }

    /**
     * 获取结构化失败类型。
     *
     * @return 失败类型；成功结果时返回 null
     */
    public FailureCode getFailureCode() {
        return failureCode;
    }

    /**
     * 获取详细诊断信息。
     *
     * @return 详细信息；成功结果时可为空
     */
    public String getDetailMessage() {
        return detailMessage;
    }

    /**
     * 获取参与校验的 Node.js 路径。
     *
     * @return Node.js 路径；未知时可为空
     */
    public String getNodePath() {
        return nodePath;
    }

    /**
     * 获取参与校验时得到的 Node.js 版本。
     *
     * @return Node.js 版本；未知时可为空
     */
    public String getNodeVersion() {
        return nodeVersion;
    }

    /**
     * 获取参与校验的 ai-bridge 脚本路径。
     *
     * @return channel-manager.js 绝对路径；未知时可为空
     */
    public String getChannelScriptPath() {
        return channelScriptPath;
    }
}
