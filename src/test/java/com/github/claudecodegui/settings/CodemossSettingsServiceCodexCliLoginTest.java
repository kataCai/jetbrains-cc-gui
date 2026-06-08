package com.github.claudecodegui.settings;

import com.github.claudecodegui.util.PlatformUtils;
import com.github.claudecodegui.session.CodexSessionBinding;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CodemossSettingsServiceCodexCliLoginTest {
    private String originalHomeDir;

    @After
    public void tearDown() throws Exception {
        if (originalHomeDir != null) {
            setCachedHomeDirectory(originalHomeDir);
            originalHomeDir = null;
        }
    }

    @Test
    public void shouldExposeCodexCliLoginAvailabilityViaFacade() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-cli-login-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(
                tempHome.resolve(".codex").resolve("auth.json"),
                "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"token-value\"}}",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexLocalConfigAuthorized(true);

        assertTrue(service.isCodexCliLoginAvailable());
    }

    @Test
    public void shouldReadCodexCliLoginAccountInfoViaFacade() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-cli-account-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));

        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"email\":\"dev@example.com\",\"name\":\"Dev User\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                tempHome.resolve(".codex").resolve("auth.json"),
                "{\"tokens\":{\"id_token\":\"header." + payload + ".signature\"}}",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexLocalConfigAuthorized(true);

        JsonObject accountInfo = service.readCodexCliLoginAccountInfo();

        assertNotNull(accountInfo);
        assertEquals("dev@example.com", accountInfo.get("emailAddress").getAsString());
        assertEquals("Dev User", accountInfo.get("name").getAsString());
    }

    @Test
    public void shouldHideCodexLocalFilesUntilAuthorized() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-cli-unauthorized-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(
                tempHome.resolve(".codex").resolve("config.toml"),
                "model = \"gpt-5\"",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                tempHome.resolve(".codex").resolve("auth.json"),
                "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"token-value\",\"id_token\":\"header.payload.signature\"}}",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();

        assertFalse(service.isCodexLocalConfigAuthorized());
        assertFalse(service.isCodexCliLoginAvailable());
        assertNull(service.readCodexCliLoginAccountInfo());
        assertEquals(0, service.getCurrentCodexConfig().size());
    }

    @Test
    public void shouldReadCodexLocalFilesAfterAuthorization() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-cli-authorized-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));

        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"email\":\"dev@example.com\",\"name\":\"Dev User\"}"
                        .getBytes(StandardCharsets.UTF_8));
        Files.writeString(
                tempHome.resolve(".codex").resolve("config.toml"),
                "model = \"gpt-5\"",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                tempHome.resolve(".codex").resolve("auth.json"),
                "{\"auth_mode\":\"chatgpt\",\"tokens\":{\"access_token\":\"token-value\",\"id_token\":\"header." + payload + ".signature\"}}",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexLocalConfigAuthorized(true);

        assertTrue(service.isCodexLocalConfigAuthorized());
        assertTrue(service.isCodexCliLoginAvailable());
        assertNotNull(service.readCodexCliLoginAccountInfo());
        assertTrue(service.getCurrentCodexConfig().has("config"));
        assertTrue(service.getCurrentCodexConfig().has("auth"));

        service.setCodexLocalConfigAuthorized(false);

        assertFalse(service.isCodexCliLoginAvailable());
        assertNull(service.readCodexCliLoginAccountInfo());
        assertEquals(0, service.getCurrentCodexConfig().size());
    }

    @Test
    public void shouldExposeCodexCurrentModelStateOnlyAfterAuthorization() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-current-model-state-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(
                tempHome.resolve(".codex").resolve("config.toml"),
                "model = \"gpt-5.5\"\nmodel_reasoning_effort = \"high\"",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();

        assertEquals(0, service.getCurrentCodexModelState().size());

        service.setCodexLocalConfigAuthorized(true);

        JsonObject state = service.getCurrentCodexModelState();
        assertEquals("gpt-5.5", state.get("model").getAsString());
        assertEquals("high", state.get("reasoningEffort").getAsString());
    }

    @Test
    public void shouldStripInlineCommentsWhenReadingCodexCurrentModelState() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-current-model-state-inline-comment-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(
                tempHome.resolve(".codex").resolve("config.toml"),
                "model = \"gpt-5.5\" # current gui default\n"
                        + "model_reasoning_effort = \"high\" # ui override\n",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexLocalConfigAuthorized(true);

        JsonObject state = service.getCurrentCodexModelState();
        assertEquals("gpt-5.5", state.get("model").getAsString());
        assertEquals("high", state.get("reasoningEffort").getAsString());
    }

    @Test
    public void shouldExposeCustomBaseUrlMetadataInCodexCurrentModelState() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-current-model-state-base-url-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(
                tempHome.resolve(".codex").resolve("config.toml"),
                "model = \"gpt-5.5\"\n"
                        + "[model_providers.OpenAI]\n"
                        + "base_url = \"https://rayplus.site\"\n",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexLocalConfigAuthorized(true);

        JsonObject state = service.getCurrentCodexModelState();
        assertEquals("https://rayplus.site", state.get("baseUrl").getAsString());
        assertTrue(state.get("usesCustomBaseUrl").getAsBoolean());
    }

    @Test
    public void shouldExposeLocalModelProviderMetadataInCodexCurrentModelState() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-current-model-provider-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));
        Files.writeString(
                tempHome.resolve(".codex").resolve("config.toml"),
                "model_provider = \"LocalOpenAI\"\n"
                        + "[model_providers.LocalOpenAI]\n"
                        + "base_url = \"https://proxy.example.com/v1\"\n",
                StandardCharsets.UTF_8
        );

        CodemossSettingsService service = new CodemossSettingsService();
        service.setCodexLocalConfigAuthorized(true);

        JsonObject state = service.getCurrentCodexModelState();
        assertEquals("LocalOpenAI", state.get("modelProvider").getAsString());
    }

    @Test
    public void shouldResolveCodexRuntimeAccessModeFromAuthorizationAndActiveProvider() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-runtime-access-home");
        useTemporaryHomeDirectory(tempHome);
        Files.createDirectories(tempHome.resolve(".codex"));

        CodemossSettingsService service = new CodemossSettingsService();

        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE, service.getCodexRuntimeAccessMode());

        service.switchCodexProvider(CodexProviderManager.CODEX_CLI_LOGIN_PROVIDER_ID);
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_INACTIVE, service.getCodexRuntimeAccessMode());

        service.setCodexLocalConfigAuthorized(true);
        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_CLI_LOGIN, service.getCodexRuntimeAccessMode());

        JsonObject provider = new JsonObject();
        provider.addProperty("id", "managed-provider");
        provider.addProperty("name", "Managed Provider");
        provider.addProperty("configToml", "model = \"gpt-5\"");
        service.addCodexProvider(provider);
        service.setCodexLocalConfigAuthorized(false);
        service.switchCodexProvider("managed-provider");

        assertEquals(CodemossSettingsService.CODEX_RUNTIME_ACCESS_MANAGED, service.getCodexRuntimeAccessMode());
    }

    /**
     * 验证 Codex 会话绑定元数据能够正确保存、读取和删除。
     * 该测试覆盖插件侧 ~/.codemoss/config.json 持久化链路，确保后续历史会话恢复时有稳定数据源。
     */
    @Test
    public void shouldSaveReadAndDeleteCodexSessionBinding() throws Exception {
        Path tempHome = Files.createTempDirectory("codex-session-binding-home");
        useTemporaryHomeDirectory(tempHome);

        CodemossSettingsService service = new CodemossSettingsService();
        CodexSessionBinding binding = new CodexSessionBinding(
                "minimax-provider",
                "MiniMax-M1",
                "codex_sdk",
                "provider",
                "managed_provider"
        );

        service.saveCodexSessionBinding("session-001", binding);

        CodexSessionBinding restored = service.getCodexSessionBinding("session-001");
        assertNotNull(restored);
        assertEquals("minimax-provider", restored.getProviderId());
        assertEquals("MiniMax-M1", restored.getModel());
        assertEquals("codex_sdk", restored.getRequestMode());
        assertEquals("provider", restored.getBaseUrlSource());
        assertEquals("managed_provider", restored.getEffectiveConfigSource());

        service.deleteCodexSessionBinding("session-001");

        assertNull(service.getCodexSessionBinding("session-001"));
    }

    private void useTemporaryHomeDirectory(Path tempHome) throws Exception {
        if (originalHomeDir == null) {
            originalHomeDir = getCachedHomeDirectory();
        }
        setCachedHomeDirectory(tempHome.toString());
    }

    private String getCachedHomeDirectory() throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void setCachedHomeDirectory(String homeDir) throws Exception {
        Field field = PlatformUtils.class.getDeclaredField("cachedRealHomeDir");
        field.setAccessible(true);
        field.set(null, homeDir);
    }
}
