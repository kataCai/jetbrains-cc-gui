import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useSettingsBasicActions } from './useSettingsBasicActions';
import type { CommitAiConfig } from '../../../types/aiFeatureConfig';

describe('useSettingsBasicActions merged settings behavior', () => {
  const defaultCommitAiConfig: CommitAiConfig = {
    provider: null,
    effectiveProvider: 'codex',
    resolutionSource: 'auto',
    models: {
      claude: 'claude-sonnet-4-6',
      codex: 'gpt-5.5',
    },
    availability: {
      claude: true,
      codex: true,
    },
  };

  beforeEach(() => {
    window.sendToJava = vi.fn();
  });

  /**
   * 验证 canonical taskReminder 会写回 set_task_reminder_config。
   * 前置条件：使用默认 hook 状态，并切换 popup channel 的 enabled 标记。
   * 断言意图：并轨后仍以 taskReminder 作为唯一写回协议，而不是退回 legacy sound-only 配置。
   */
  it('sends canonical task reminder config after toggling a task reminder channel', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.handleTaskReminderEnabledChange('popup', false);
    });

    const calls = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls;
    const command = calls.find(([message]) => String(message).startsWith('set_task_reminder_config:'))?.[0];
    expect(command).toBeTruthy();

    const payload = JSON.parse(String(command).slice('set_task_reminder_config:'.length));
    expect(payload.popup.enabled).toBe(false);
    expect(Array.isArray(payload.popup.states)).toBe(true);
  });

  /**
   * 验证 system channel 仍可按主线规范更新。
   * 断言意图：保留当前主线补充的 system 提醒通道，不被 upstream 配置流覆盖掉。
   */
  it('supports the system task reminder channel with canonical defaults', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.handleTaskReminderEnabledChange('system', true);
    });

    const calls = (window.sendToJava as ReturnType<typeof vi.fn>).mock.calls;
    // tsconfig.test 目标仍未提供 Array.prototype.findLast，这里改为从尾到头查找，保持行为一致且避免提升编译目标。
    const command = [...calls]
      .reverse()
      .find((call) => String(call[0]).startsWith('set_task_reminder_config:'))?.[0];
    expect(command).toBeTruthy();

    const payload = JSON.parse(String(command).slice('set_task_reminder_config:'.length));
    expect(payload.system.enabled).toBe(true);
    expect(payload.system.onlyWhenIdeUnfocused).toBe(true);
    expect(payload.system.states).toEqual(['waiting_confirm', 'final_error', 'completed']);
  });

  /**
   * 验证 popup / balloon 的测试事件仍按主线协议发往后端。
   * 断言意图：确保设置页里的提醒测试按钮不会因为并轨而失效。
   */
  it('sends task reminder test events for popup and balloon', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.handleTestPopup();
      result.current.handleTestBalloon();
    });

    expect(window.sendToJava).toHaveBeenCalledWith('test_task_reminder_popup:');
    expect(window.sendToJava).toHaveBeenCalledWith('test_task_reminder_balloon:');
  });

  /**
   * 验证切换 Commit AI provider 只影响 commitAiConfig，不污染 promptEnhancerConfig。
   * 断言意图：覆盖本轮并轨吸收的 AI feature 配置分离语义。
   */
  it('updates commit AI provider without mutating prompt enhancer state', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.setCommitAiConfig(defaultCommitAiConfig);
    });

    const promptEnhancerBefore = result.current.promptEnhancerConfig;

    act(() => {
      result.current.handleCommitAiProviderChange('claude');
    });

    expect(result.current.commitAiConfig.provider).toBe('claude');
    expect(result.current.promptEnhancerConfig).toEqual(promptEnhancerBefore);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'set_commit_ai_config:{"provider":"claude","models":{"claude":"claude-sonnet-4-6","codex":"gpt-5.5"}}'
    );
  });

  /**
   * 验证切换 Commit AI model 时不会串改 Prompt Enhancer 的模型配置。
   * 断言意图：确保两组 provider/model 状态仍彼此独立。
   */
  it('updates commit AI model without mutating prompt enhancer models', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.setCommitAiConfig({
        ...defaultCommitAiConfig,
        provider: 'codex',
        effectiveProvider: 'codex',
        resolutionSource: 'manual',
      });
    });

    const promptEnhancerBefore = result.current.promptEnhancerConfig;

    act(() => {
      result.current.handleCommitAiModelChange('gpt-5.4');
    });

    expect(result.current.commitAiConfig.models.codex).toBe('gpt-5.4');
    expect(result.current.promptEnhancerConfig).toEqual(promptEnhancerBefore);
    expect(window.sendToJava).toHaveBeenCalledWith(
      'set_commit_ai_config:{"provider":"codex","models":{"claude":"claude-sonnet-4-6","codex":"gpt-5.4"}}'
    );
  });

  /**
   * 验证 Codex 历史图片缓存配置会以单条命令整体写回后端。
   * 断言意图：目录、保留天数和容量上限必须作为一个原子配置提交，避免设置页出现部分保存成功的中间态。
   */
  it('sends codex history image cache config as one payload', () => {
    const { result } = renderHook(() => useSettingsBasicActions({}));

    act(() => {
      result.current.setCodexHistoryImageCacheDir('/tmp/codex-history-images');
      result.current.setCodexHistoryImageCacheRetentionDays(45);
      result.current.setCodexHistoryImageCacheMaxSizeMb(2048);
    });

    act(() => {
      result.current.handleSaveCodexHistoryImageCacheConfig();
    });

    expect(window.sendToJava).toHaveBeenCalledWith(
      'set_codex_history_image_cache_config:{"customDir":"/tmp/codex-history-images","retentionDays":45,"maxSizeMb":2048}'
    );
  });
});
