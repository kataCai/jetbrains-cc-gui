import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { usePluginModels } from './usePluginModels';
import { STORAGE_KEYS } from '../../../types/provider';

/**
 * `usePluginModels` 兼容性回归测试。
 * 这组测试聚焦旧版 `codex-custom-models` 本地数据在新 UI 下的可读、可解释与同页同步能力：
 * 1. 历史 localStorage 数据必须能被直接读取，避免升级后用户看不到旧别名。
 * 2. 非法条目必须被过滤，避免旧脏数据混入新的供应商配置语义。
 * 3. 同 tab 更新时必须触发自定义刷新事件，保证设置页和聊天区能及时看到最新别名列表。
 */
describe('usePluginModels', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  /**
   * 验证历史 `codex-custom-models` 数据会在 hook 初始化阶段被直接读取。
   * 这是旧“自定义模型”入口升级为“模型别名（高级）”后最关键的兼容要求，
   * 否则用户之前保存的模型 ID 会在新 UI 中消失，造成误判为数据丢失。
   */
  it('应读取历史 codex-custom-models 别名数据', () => {
    localStorage.setItem(STORAGE_KEYS.CODEX_CUSTOM_MODELS, JSON.stringify([
      {
        id: 'gpt-5.4',
        label: 'GPT-5.4',
        description: 'legacy alias',
      },
    ]));

    const { result } = renderHook(() => usePluginModels(STORAGE_KEYS.CODEX_CUSTOM_MODELS));

    expect(result.current.models).toEqual([
      {
        id: 'gpt-5.4',
        label: 'GPT-5.4',
        description: 'legacy alias',
      },
    ]);
  });

  /**
   * 验证 hook 会过滤历史 localStorage 中的非法模型条目。
   * 旧版本可能写入过不完整或被手工污染的数据，新版本必须只保留合法别名，
   * 以免这些脏数据在模型选择列表中表现成可用模型，继续误导用户。
   */
  it('应过滤历史 localStorage 中的非法模型别名条目', () => {
    localStorage.setItem(STORAGE_KEYS.CODEX_CUSTOM_MODELS, JSON.stringify([
      {
        id: 'gpt-5.5',
        label: 'GPT-5.5',
      },
      {
        id: '',
        label: 'broken',
      },
      {
        id: 'missing-label',
      },
      'invalid-row',
    ]));

    const { result } = renderHook(() => usePluginModels(STORAGE_KEYS.CODEX_CUSTOM_MODELS));

    expect(result.current.models).toEqual([
      {
        id: 'gpt-5.5',
        label: 'GPT-5.5',
      },
    ]);
  });

  /**
   * 验证同一个 tab 内更新模型别名后，hook 会通过自定义事件同步最新列表。
   * 这是设置页和聊天区共存时的关键行为：用户刚保存别名，当前页无需刷新就应看到结果。
   */
  it('应在同 tab 更新时通过 localStorageChange 事件同步模型别名', () => {
    const { result } = renderHook(() => usePluginModels(STORAGE_KEYS.CODEX_CUSTOM_MODELS));

    act(() => {
      result.current.updateModels([
        {
          id: 'glm-4.5',
          label: 'GLM-4.5',
          description: 'first alias',
        },
      ]);
    });

    expect(JSON.parse(localStorage.getItem(STORAGE_KEYS.CODEX_CUSTOM_MODELS) || '[]')).toEqual([
      {
        id: 'glm-4.5',
        label: 'GLM-4.5',
        description: 'first alias',
      },
    ]);
    expect(result.current.models).toEqual([
      {
        id: 'glm-4.5',
        label: 'GLM-4.5',
        description: 'first alias',
      },
    ]);

    act(() => {
      localStorage.setItem(STORAGE_KEYS.CODEX_CUSTOM_MODELS, JSON.stringify([
        {
          id: 'glm-4.6',
          label: 'GLM-4.6',
        },
      ]));
      window.dispatchEvent(new CustomEvent('localStorageChange', {
        detail: { key: STORAGE_KEYS.CODEX_CUSTOM_MODELS },
      }));
    });

    expect(result.current.models).toEqual([
      {
        id: 'glm-4.6',
        label: 'GLM-4.6',
      },
    ]);
  });
});
