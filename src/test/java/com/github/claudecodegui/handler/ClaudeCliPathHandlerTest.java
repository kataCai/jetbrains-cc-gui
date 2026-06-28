package com.github.claudecodegui.handler;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 ClaudeCliPathHandler 的纯路径校验逻辑。
 * 这里只覆盖“文件不存在 / 是目录 / 不可执行 / 可执行”四条分支，
 * 避免在不启动 IntelliJ 平台服务的情况下把测试耦合到 PropertiesComponent。
 */
public class ClaudeCliPathHandlerTest {

    /**
     * 获取当前 JVM 可执行文件路径。
     * 这里复用真实可执行文件作为“错误可执行文件”样本，
     * 用于验证 Claude CLI 路径校验不会把任意可执行程序都当成合法目标。
     *
     * @return 当前 JVM 可执行文件绝对路径
     */
    private static String javaExecutable() {
        return ProcessHandle.current()
                .info()
                .command()
                .orElseGet(() -> System.getProperty("java.home") + File.separator + "bin"
                        + File.separator + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe"
                        : "java"));
    }

    /**
     * 获取当前平台下可被 Claude CLI 路径校验接受的测试文件名。
     * Windows 侧优先使用 `.exe` 后缀，避免不同文件系统对无后缀可执行位判断不一致；
     * 其他平台保持 `claude` 原名即可。
     *
     * @return 满足 Claude CLI 命名约束的测试文件名
     */
    private static String validClaudeExecutableName() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win") ? "claude.exe" : "claude";
    }

    /**
     * 验证不存在的文件路径会被拒绝。
     *
     * @param 无
     * @return 无返回值，通过断言验证错误信息前缀
     */
    @Test
    public void validateRejectsNonExistentFile() {
        File missing = new File(System.getProperty("java.io.tmpdir"), "cc-gui-claude-cli-missing-zzz");
        String reason = ClaudeCliPathHandler.validateCliPath(missing, missing.getPath());
        assertNotNull("A non-existent path must be rejected", reason);
        assertTrue("Reason should explain the file is missing: " + reason,
                reason.startsWith("File does not exist"));
    }

    /**
     * 验证目录路径会被拒绝。
     *
     * @param 无
     * @return 无返回值，通过断言验证错误信息前缀
     * @throws IOException 创建临时目录失败时抛出
     */
    @Test
    public void validateRejectsDirectory() throws IOException {
        File dir = Files.createTempDirectory("cc-gui-claude-cli-dir").toFile();
        dir.deleteOnExit();
        String reason = ClaudeCliPathHandler.validateCliPath(dir, dir.getPath());
        assertNotNull("A directory must be rejected", reason);
        assertTrue("Reason should explain the path is a directory: " + reason,
                reason.startsWith("Path is a directory"));
    }

    /**
     * 验证不可执行文件会被拒绝。
     * 某些文件系统无法可靠清除执行位，因此这里在前置条件不满足时跳过。
     *
     * @param 无
     * @return 无返回值，通过断言验证错误信息前缀
     * @throws IOException 创建临时文件失败时抛出
     */
    @Test
    public void validateRejectsNonExecutableFile() throws IOException {
        File file = Files.createTempFile("cc-gui-claude-cli-noexec", ".bin").toFile();
        file.deleteOnExit();
        file.setExecutable(false, false);
        Assume.assumeFalse("Filesystem cannot strip the execute bit", file.canExecute());

        String reason = ClaudeCliPathHandler.validateCliPath(file, file.getPath());
        assertNotNull("A non-executable file must be rejected", reason);
        assertTrue("Reason should explain the file is not executable: " + reason,
                reason.startsWith("File is not executable"));
    }

    /**
     * 验证可执行文件会被接受。
     *
     * @param 无
     * @return 无返回值，通过断言验证返回值为 null
     * @throws IOException 创建临时文件失败时抛出
     */
    @Test
    public void validateAcceptsExecutableFile() throws IOException {
        File dir = Files.createTempDirectory("cc-gui-claude-cli-ok").toFile();
        dir.deleteOnExit();
        File file = new File(dir, validClaudeExecutableName());
        assertTrue("Test precondition: create a named Claude CLI candidate", file.createNewFile());
        file.deleteOnExit();
        assertTrue("Test precondition: set the execute bit", file.setExecutable(true, false));

        String reason = ClaudeCliPathHandler.validateCliPath(file, file.getAbsolutePath());
        assertNull("A usable executable file must pass validation, got: " + reason, reason);
    }

    /**
     * 验证相对路径会在文件存在性校验前被拒绝。
     * 该断言覆盖“设置页文案要求绝对路径，但后端此前未强制”的缺口，
     * 避免不同 Claude 子进程入口因为工作目录不同而对同一配置解析出不同结果。
     *
     * @return 无返回值，通过断言验证错误信息前缀
     */
    @Test
    public void validateRejectsRelativePathBeforeFileChecks() {
        File relative = new File("claude");
        String reason = ClaudeCliPathHandler.validateCliPath(relative, relative.getPath());
        assertNotNull("A relative path must be rejected", reason);
        assertTrue("Reason should explain absolute paths are required: " + reason,
                reason.startsWith("Path must be absolute"));
    }

    /**
     * 验证任意可执行文件不会被误判为 Claude CLI。
     * 这里使用当前 JVM 可执行文件作为稳定样本，确保在不同平台下都能拿到一个真实存在且可执行的文件，
     * 同时它的文件名不应满足 Claude CLI 的命名约束。
     *
     * @return 无返回值，通过断言验证错误信息前缀
     */
    @Test
    public void validateRejectsExecutableThatIsNotClaudeCli() {
        File javaBinary = new File(javaExecutable());
        String reason = ClaudeCliPathHandler.validateCliPath(javaBinary, javaBinary.getPath());
        assertNotNull("A non-Claude executable must be rejected", reason);
        assertTrue("Reason should explain the file name is not a Claude CLI binary: " + reason,
                reason.startsWith("Path must point to a Claude CLI executable"));
    }
}
