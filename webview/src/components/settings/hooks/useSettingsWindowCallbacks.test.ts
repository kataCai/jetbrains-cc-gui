import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSettingsWindowCallbacks, type SettingsWindowCallbacksDeps } from './useSettingsWindowCallbacks';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('useSettingsWindowCallbacks', () => {
  const createDeps = (): SettingsWindowCallbacksDeps => ({
    setNodePath: vi.fn(),
    setNodeVersion: vi.fn(),
    setMinNodeVersion: vi.fn(),
    setSavingNodePath: vi.fn(),
    setWorkingDirectory: vi.fn(),
    setSavingWorkingDirectory: vi.fn(),
    setCommitPrompt: vi.fn(),
    setSavingCommitPrompt: vi.fn(),
    setEditorFontConfig: vi.fn(),
    setIdeTheme: vi.fn(),
    setLocalStreamingEnabled: vi.fn(),
    setCodexSandboxMode: vi.fn(),
    setLocalSendShortcut: vi.fn(),
    setLoading: vi.fn(),
    setCodexLoading: vi.fn(),
    setCodexConfigLoading: vi.fn(),
    setTaskReminderConfig: vi.fn(),
    updateProviders: vi.fn(),
    updateActiveProvider: vi.fn(),
    loadProviders: vi.fn(),
    loadCodexProviders: vi.fn(),
    loadAgents: vi.fn(),
    updateAgents: vi.fn(),
    handleAgentOperationResult: vi.fn(),
    handleAgentImportPreviewResult: vi.fn(),
    handleAgentImportResult: vi.fn(),
    updateCodexProviders: vi.fn(),
    updateActiveCodexProvider: vi.fn(),
    updateCurrentCodexConfig: vi.fn(),
    cleanupAgentsTimeout: vi.fn(),
    showAlert: vi.fn(),
    addToast: vi.fn(),
  });

  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  it('does not auto-request current Claude config on mount', () => {
    const deps = createDeps();

    renderHook(() => useSettingsWindowCallbacks(deps));

    expect(deps.loadProviders).toHaveBeenCalledTimes(1);
    expect(deps.loadCodexProviders).toHaveBeenCalledTimes(1);
    expect(deps.loadAgents).toHaveBeenCalledTimes(1);
    expect(window.sendToJava).not.toHaveBeenCalledWith('get_current_claude_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_node_path:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_working_directory:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_editor_font_config:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_streaming_enabled:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_codex_sandbox_mode:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_commit_prompt:');
    expect(window.sendToJava).toHaveBeenCalledWith('get_task_reminder_config:');
  });

  it('writes canonical task reminder config when updateTaskReminderConfig is received', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    const config = {
      popup: { enabled: true, states: ['waiting_confirm'], onlyWhenIdeUnfocused: false },
      balloon: { enabled: true, states: ['completed'], onlyWhenIdeUnfocused: true },
      sound: {
        enabled: true,
        states: ['completed'],
        onlyWhenIdeUnfocused: true,
        selectedSound: 'default',
        customSoundPath: '',
      },
    };

    window.updateTaskReminderConfig?.(JSON.stringify(config));
    expect(deps.setTaskReminderConfig).toHaveBeenCalledWith({
      ...config,
      system: {
        enabled: false,
        states: ['waiting_confirm', 'final_error', 'completed'],
        onlyWhenIdeUnfocused: true,
      },
    });
  });

  it('merges legacy sound callback into taskReminderConfig.sound', () => {
    const deps = createDeps();
    renderHook(() => useSettingsWindowCallbacks(deps));

    window.updateSoundNotificationConfig?.(JSON.stringify({
      enabled: false,
      states: ['final_error'],
      onlyWhenIdeUnfocused: false,
      selectedSound: 'custom',
      customSoundPath: '/tmp/a.wav',
    }));

    const setCallArg = (deps.setTaskReminderConfig as ReturnType<typeof vi.fn>).mock.calls.at(-1)?.[0];
    expect(typeof setCallArg).toBe('function');

    const prevConfig = {
      popup: { enabled: true, states: ['waiting_confirm'], onlyWhenIdeUnfocused: false },
      balloon: { enabled: true, states: ['completed'], onlyWhenIdeUnfocused: true },
      sound: {
        enabled: true,
        states: ['completed'],
        onlyWhenIdeUnfocused: true,
        selectedSound: 'default',
        customSoundPath: '',
      },
      system: {
        enabled: false,
        states: ['waiting_confirm', 'final_error', 'completed'],
        onlyWhenIdeUnfocused: true,
      },
    };
    const nextConfig = setCallArg(prevConfig);

    expect(nextConfig.popup).toEqual(prevConfig.popup);
    expect(nextConfig.balloon).toEqual(prevConfig.balloon);
    expect(nextConfig.system).toEqual(prevConfig.system);
    expect(nextConfig.sound.enabled).toBe(false);
    expect(nextConfig.sound.states).toEqual(['final_error']);
    expect(nextConfig.sound.selectedSound).toBe('custom');
    expect(nextConfig.sound.customSoundPath).toBe('/tmp/a.wav');
  });
});
