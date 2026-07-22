import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ButtonArea } from '../ChatInputBox/ButtonArea';
import CodexModelVisibilitySection from './CodexModelVisibilitySection';
import { useCodexProviderManagement } from './hooks/useCodexProviderManagement';
import { useSettingsWindowCallbacks, type SettingsWindowCallbacksDeps } from './hooks/useSettingsWindowCallbacks';
import { resetRuntimeProviderCapabilitiesForTest } from '../../utils/runtimeProviderCapabilities';

vi.mock('react-i18next', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-i18next')>();
  const translations: Record<string, string> = {
    'chat.composerMode': 'Composer mode',
    'chat.chatMode': 'Chat',
    'chat.planModeLabel': 'Plan',
    'chat.planRequiresClaudeHint': 'Plan requires Claude. Switch to Claude to use it.',
    'modes.default.label': 'Default',
    'modes.acceptEdits.label': 'Accept Edits',
    'modes.bypassPermissions.label': 'Bypass Permissions',
    'common.refresh': 'Refresh',
    'common.loading': 'Loading',
    'settings.codexProvider.modelsTitle': 'Models',
    'settings.codexProvider.modelsDescription': 'Control which Codex models appear in the chat model picker.',
    'settings.codexProvider.modelsSearchPlaceholder': 'Search by model or provider',
    'settings.codexProvider.modelsEmpty': 'No matching models',
    'settings.codexProvider.modelsViewAll': 'View All Models',
    'settings.codexProvider.modelsCollapse': 'Collapse',
    'settings.codexProvider.modelsVisibleSectionTitle': 'Visible models',
    'settings.codexProvider.modelsAllSectionTitle': 'All matching models',
    'settings.codexProvider.modelsSource.codex_cli_login': 'CLI Login',
    'settings.codexProvider.modelsSource.managed_provider': 'Managed Provider',
    'settings.codexProvider.modelsSource.plugin_custom': 'Model Alias',
    'settings.codexProvider.modelsSource.local_config': 'Local Config',
    'settings.codexProvider.modelsUnavailable': 'Available after authorization',
    'settings.codexProvider.modelsGroupSelectAll': 'Select All',
    'settings.codexProvider.modelsGroupDeselectAll': 'Deselect All',
    'settings.codexProvider.modelsGroupCount': '{{count}} Models',
  };
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string, options?: Record<string, string | number>) => {
        const template = translations[key] ?? key;
        if (!options) {
          return template;
        }
        return Object.entries(options).reduce(
          (result, [token, value]) => result.replace(`{{${token}}}`, String(value)),
          template,
        );
      },
    }),
  };
});

/**
 * 生成设置页运行时回调依赖。
 * 该集成测试只关注统一模型目录回推，因此除 catalog 相关链路外，其余 setter 与 loader 全部使用空桩。
 *
 * @param management Codex 设置页管理 hook 的返回值
 * @return 可供 `useSettingsWindowCallbacks` 注册的依赖对象
 */
function createSettingsWindowDeps(
  management: ReturnType<typeof useCodexProviderManagement>,
): SettingsWindowCallbacksDeps {
  return {
    setNodePath: vi.fn(),
    setNodeVersion: vi.fn(),
    setMinNodeVersion: vi.fn(),
    setSavingNodePath: vi.fn(),
    setClaudeCliPath: vi.fn(),
    setSavingClaudeCliPath: vi.fn(),
    setWorkingDirectory: vi.fn(),
    setSavingWorkingDirectory: vi.fn(),
    setCodexHistoryImageCacheDir: vi.fn(),
    setCodexHistoryImageCacheResolvedDir: vi.fn(),
    setCodexHistoryImageCacheRetentionDays: vi.fn(),
    setCodexHistoryImageCacheMaxSizeMb: vi.fn(),
    setSavingCodexHistoryImageCache: vi.fn(),
    setCommitPrompt: vi.fn(),
    setSavingCommitPrompt: vi.fn(),
    setProjectCommitPrompt: vi.fn(),
    setSavingProjectCommitPrompt: vi.fn(),
    setCommitAiConfig: vi.fn(),
    setPromptEnhancerConfig: vi.fn(),
    setEditorFontConfig: vi.fn(),
    setUiFontConfig: vi.fn(),
    setIdeTheme: vi.fn(),
    setLocalStreamingEnabled: vi.fn(),
    setCodexSandboxMode: vi.fn(),
    setLocalSendShortcut: vi.fn(),
    setFrontendDebugPanelEnabled: vi.fn(),
    setFrontendDiagnosticArchiveEnabled: vi.fn(),
    setLoading: vi.fn(),
    setCodexLoading: management.setCodexLoading,
    setCodexConfigLoading: management.setCodexConfigLoading,
    setCodexModelCatalogLoading: management.setCodexModelCatalogLoading,
    setSyncingCodexProviderId: management.setSyncingCodexProviderId,
    setTestingCodexProviderId: management.setTestingCodexProviderId,
    setCommitGenerationEnabled: vi.fn(),
    setAiTitleGenerationEnabled: vi.fn(),
    setStatusBarWidgetEnabled: vi.fn(),
    setTaskCompletionNotificationEnabled: vi.fn(),
    setTaskReminderConfig: vi.fn(),
    setRemoteCollabConfig: vi.fn(),
    setRemoteCollabDebugSnapshot: vi.fn(),
    setRemoteCollabProviderOperationResult: vi.fn(),
    setRightClickOpenDevToolsEnabled: vi.fn(),
    updateProviders: vi.fn(),
    updateActiveProvider: vi.fn(),
    loadProviders: vi.fn(),
    loadCodexProviders: management.loadCodexProviders,
    loadCodexModelCatalog: management.loadCodexModelCatalog,
    loadAgents: vi.fn(),
    updateAgents: vi.fn(),
    handleAgentOperationResult: vi.fn(),
    handleAgentImportPreviewResult: vi.fn(),
    handleAgentImportResult: vi.fn(),
    updateCodexProviders: management.updateCodexProviders,
    updateActiveCodexProvider: management.updateActiveCodexProvider,
    updateCurrentCodexConfig: management.updateCurrentCodexConfig,
    updateCodexModelCatalog: management.updateCodexModelCatalog,
    cleanupAgentsTimeout: vi.fn(),
    showAlert: vi.fn(),
    addToast: vi.fn(),
  };
}

/**
 * 同时挂载设置页与聊天区两个统一目录消费者。
 * 设置页侧走 `useCodexProviderManagement + useSettingsWindowCallbacks` 的真实状态路径，
 * 聊天区侧走 `ButtonArea` 对 `window.updateCodexModelCatalog` 的真实订阅路径。
 *
 * @param selectedModel 聊天区当前选中模型
 * @return 组合后的测试视图
 */
function SettingsAndChatCatalogHarness({ selectedModel }: { selectedModel: string }) {
  const management = useCodexProviderManagement();
  useSettingsWindowCallbacks(createSettingsWindowDeps(management));

  return (
    <>
      <CodexModelVisibilitySection
        catalog={management.codexModelCatalog}
        loading={management.codexModelCatalogLoading}
        onRefresh={management.loadCodexModelCatalog}
        onSaveVisibility={management.saveCodexModelVisibility}
      />
      <ButtonArea
        hasInputContent
        selectedModel={selectedModel}
        permissionMode="default"
        currentProvider="codex"
        onSubmit={() => {}}
        onModelSelect={() => {}}
      />
    </>
  );
}

describe('Codex model catalog consistency integration', () => {
  beforeEach(() => {
    resetRuntimeProviderCapabilitiesForTest();
    window.sendToJava = vi.fn();
    localStorage.clear();
  });

  /**
   * 验证目标：统一目录刷新后，设置页模型面板与聊天区模型下拉都应消费同一份最新 catalog。
   * 断言意图：
   * 1. 首次回推 `MiniMax M3` 时，两侧都只显示 M3；
   * 2. 二次回推 `MiniMax M4` 时，两侧都移除 M3，只保留 M4。
   */
  it('keeps settings and chat model consumers aligned after shared catalog refreshes', async () => {
    const firstCatalog = [
      {
        key: 'minimax::MiniMax-M3',
        providerId: 'minimax',
        providerName: 'MiniMax',
        modelId: 'MiniMax-M3',
        label: 'MiniMax M3',
        description: 'First shared catalog model',
        source: 'managed_provider',
        visible: true,
        runnable: true,
      },
    ];
    const secondCatalog = [
      {
        key: 'minimax::MiniMax-M4',
        providerId: 'minimax',
        providerName: 'MiniMax',
        modelId: 'MiniMax-M4',
        label: 'MiniMax M4',
        description: 'Refreshed shared catalog model',
        source: 'managed_provider',
        visible: true,
        runnable: true,
      },
    ];

    const { container, rerender } = render(
      <SettingsAndChatCatalogHarness selectedModel="MiniMax-M3" />,
    );

    act(() => {
      window.updateCodexModelCatalog?.(JSON.stringify(firstCatalog));
    });

    const firstSettingsGroup = await screen.findByTestId('provider-group:minimax');
    expect(firstSettingsGroup.textContent).toContain('MiniMax M3');

    fireEvent.click(screen.getByRole('button', { name: /MiniMax M3/i }));
    let dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('MiniMax M3')).toBeTruthy();
    expect(within(dropdown as HTMLElement).queryByText('MiniMax M4')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /MiniMax M3/i }));

    rerender(<SettingsAndChatCatalogHarness selectedModel="MiniMax-M4" />);

    act(() => {
      window.updateCodexModelCatalog?.(JSON.stringify(secondCatalog));
    });

    const secondSettingsGroup = await screen.findByTestId('provider-group:minimax');
    expect(secondSettingsGroup.textContent).toContain('MiniMax M4');
    expect(secondSettingsGroup.textContent).not.toContain('MiniMax M3');

    fireEvent.click(screen.getByRole('button', { name: /MiniMax M4/i }));
    dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('MiniMax M4')).toBeTruthy();
    expect(within(dropdown as HTMLElement).queryByText('MiniMax M3')).toBeNull();
  });
});
