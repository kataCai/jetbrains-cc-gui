package com.github.claudecodegui.bridge;

import com.github.claudecodegui.settings.CodemossSettingsService;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * EnvironmentConfigurator 中 Codex 环境注入边界测试。
 * 重点验证托管 provider 不会再读取本地 `~/.codex/config.toml` 的 `env_key`，
 * 只有 CLI Login 模式才允许同步本地 shell 环境变量。
 */
public class EnvironmentConfiguratorTest {

    /**
     * 验证托管 provider 模式下必须跳过 `env_key` 注入。
     */
    @Test
    public void shouldSkipEnvKeySyncWhenAccessModeIsManaged() {
        TestEnvironmentConfigurator configurator = new TestEnvironmentConfigurator(
                CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED,
                Collections.singleton("OPENAI_API_KEY"),
                Collections.singletonMap("OPENAI_API_KEY", "managed-should-not-use")
        );
        Map<String, String> env = new HashMap<>();

        configurator.configureCodexEnv(env);

        assertFalse(env.containsKey("OPENAI_API_KEY"));
    }

    /**
     * 验证 CLI Login 模式下允许同步 `env_key` 对应的环境变量。
     */
    @Test
    public void shouldSyncEnvKeysWhenAccessModeIsCliLogin() {
        TestEnvironmentConfigurator configurator = new TestEnvironmentConfigurator(
                CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN,
                Collections.singleton("OPENAI_API_KEY"),
                Collections.singletonMap("OPENAI_API_KEY", "cli-login-secret")
        );
        Map<String, String> env = new HashMap<>();

        configurator.configureCodexEnv(env);

        assertEquals("cli-login-secret", env.get("OPENAI_API_KEY"));
    }

    /**
     * 验证即使在 CLI Login 模式下，也不应覆盖上层已经明确传入的环境变量。
     */
    @Test
    public void shouldNotOverrideExistingEnvValueWhenCliLoginSyncsEnvKeys() {
        TestEnvironmentConfigurator configurator = new TestEnvironmentConfigurator(
                CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN,
                Collections.singleton("OPENAI_API_KEY"),
                Collections.singletonMap("OPENAI_API_KEY", "cli-login-secret")
        );
        Map<String, String> env = new HashMap<>();
        env.put("OPENAI_API_KEY", "already-present");

        configurator.configureCodexEnv(env);

        assertEquals("already-present", env.get("OPENAI_API_KEY"));
    }

    /**
     * 用于隔离真实文件系统和 shell 环境的测试桩。
     * 通过覆写访问模式、`env_key` 集合与变量解析结果，保证测试只聚焦分支逻辑本身。
     */
    private static class TestEnvironmentConfigurator extends EnvironmentConfigurator {
        private final String accessMode;
        private final Set<String> envKeys;
        private final Map<String, String> resolvedValues;

        /**
         * @param accessMode 要模拟的运行时访问模式
         * @param envKeys 要模拟从 `config.toml` 解析出的 `env_key`
         * @param resolvedValues 要模拟从系统环境解析出的变量值
         */
        TestEnvironmentConfigurator(String accessMode, Set<String> envKeys, Map<String, String> resolvedValues) {
            this.accessMode = accessMode;
            this.envKeys = new LinkedHashSet<>(envKeys);
            this.resolvedValues = new HashMap<>(resolvedValues);
        }

        /**
         * 返回测试指定的运行时访问模式。
         *
         * @return 当前测试场景下的访问模式
         * @throws IOException 为保持父类签名而保留
         */
        @Override
        protected String getCodexRuntimeAccessMode() throws IOException {
            return accessMode;
        }

        /**
         * 返回测试指定的 `env_key` 集合。
         *
         * @return 模拟解析结果
         */
        @Override
        protected Set<String> parseCodexConfigEnvKeys() {
            return new LinkedHashSet<>(envKeys);
        }

        /**
         * 返回测试指定的变量值。
         *
         * @param envName 变量名
         * @return 模拟变量值
         */
        @Override
        protected String resolveEnvValue(String envName) {
            return resolvedValues.get(envName);
        }
    }
}
