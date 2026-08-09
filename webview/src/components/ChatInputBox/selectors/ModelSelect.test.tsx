import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ModelSelect } from './ModelSelect';
import { CLAUDE_MODELS, CODEX_MODELS } from '../types';
import type { ModelInfo } from '../types';
import { STORAGE_KEYS } from '../../../types/provider';
import { getAppViewport } from '../../../utils/viewport';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => (
      options?.model
      ?? options?.defaultModel
      ?? options?.defaultValue
      ?? key
    ),
  }),
}));

vi.mock('../../../utils/viewport', () => ({
  getAppViewport: vi.fn(() => ({
    width: 1280,
    height: 720,
    top: 0,
    left: 0,
    fixedPosDivisor: 1,
  })),
}));

/**
 * ModelSelect 回归测试。
 * 这一组测试同时覆盖主线的 Codex CLI 默认模型 / 自定义 base_url 提示，
 * 以及 upstream 带来的 Claude 1M 模型基线和扩展后的 Codex 模型列表。
 */
describe('ModelSelect', () => {
  const sonnetModel: ModelInfo = {
    id: 'claude-sonnet-4-6',
    label: 'Sonnet 4.6',
    description: 'Sonnet 4.6 路 Use the default model',
  };
  let scrollMetricsMap: WeakMap<HTMLElement, { clientHeight: number; scrollHeight: number; scrollTop: number }>;
  let viewportState: ReturnType<typeof getAppViewport>;
  let resizeObserverEntries: Array<{
    callback: ResizeObserverCallback;
    observe: ReturnType<typeof vi.fn>;
    disconnect: ReturnType<typeof vi.fn>;
  }>;

  beforeEach(() => {
    localStorage.clear();
    scrollMetricsMap = new WeakMap();
    viewportState = {
      width: 1280,
      height: 720,
      top: 0,
      left: 0,
      fixedPosDivisor: 1,
    };
    vi.mocked(getAppViewport).mockImplementation(() => viewportState);

    resizeObserverEntries = [];
    const ResizeObserverMock = class implements ResizeObserver {
      public callback: ResizeObserverCallback;

      public observe = vi.fn();

      public unobserve = vi.fn();

      public disconnect = vi.fn();

      public constructor(callback: ResizeObserverCallback) {
        this.callback = callback;
        resizeObserverEntries.push({
          callback,
          observe: this.observe,
          disconnect: this.disconnect,
        });
      }
    };
    vi.stubGlobal('ResizeObserver', ResizeObserverMock);
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback: FrameRequestCallback): number => {
      callback(0);
      return 1;
    });
    vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((): void => undefined);
  });

  /**
   * 统一清理当前测试里注入的全局替身，避免后续用例沿用旧的 viewport/observer 状态。
   * 这里显式恢复 spy 与 stub，确保每个用例都从干净环境重新开始。
   *
   * @return void
   */
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  /**
   * 为 JSDOM 下的滚动容器手动注入尺寸和滚动位置。
   * 真实浏览器会自动给出这些布局值，但单测环境需要显式模拟，
   * 否则无法验证“超长列表进入滚动态”以及滚动态在事件驱动下的刷新。
   *
   * @param element 目标滚动容器
   * @param metrics 要注入的滚动尺寸
   * @return void
   */
  const mockScrollMetrics = (
    element: HTMLElement,
    metrics: { clientHeight: number; scrollHeight: number; scrollTop?: number },
  ): void => {
    const nextMetrics = {
      clientHeight: metrics.clientHeight,
      scrollHeight: metrics.scrollHeight,
      scrollTop: metrics.scrollTop ?? 0,
    };
    scrollMetricsMap.set(element, nextMetrics);

    Object.defineProperty(element, 'clientHeight', {
      configurable: true,
      get: () => scrollMetricsMap.get(element)?.clientHeight ?? 0,
    });
    Object.defineProperty(element, 'scrollHeight', {
      configurable: true,
      get: () => scrollMetricsMap.get(element)?.scrollHeight ?? 0,
    });
    Object.defineProperty(element, 'scrollTop', {
      configurable: true,
      get: () => scrollMetricsMap.get(element)?.scrollTop ?? 0,
      set: (value: number) => {
        const current = scrollMetricsMap.get(element);
        if (!current) {
          return;
        }
        scrollMetricsMap.set(element, { ...current, scrollTop: value });
      },
    });
  };

  /**
   * 为模型按钮注入稳定的布局测量结果。
   * dropdown 的最大高度完全依赖按钮的 `getBoundingClientRect()`，
   * 因此测试必须显式控制这些值，才能稳定覆盖 viewport 边界分支。
   *
   * @param button 模型选择按钮
   * @param rect 目标布局矩形
   * @return void
   */
  const mockButtonRect = (
    button: HTMLElement,
    rect: { top: number; left?: number; width?: number; height?: number },
  ): void => {
    const width = rect.width ?? 160;
    const height = rect.height ?? 28;
    const left = rect.left ?? 0;
    Object.defineProperty(button, 'getBoundingClientRect', {
      configurable: true,
      value: () => ({
        x: left,
        y: rect.top,
        top: rect.top,
        left,
        width,
        height,
        right: left + width,
        bottom: rect.top + height,
        toJSON: () => undefined,
      }),
    });
  };

  /**
   * 获取当前渲染出的模型 dropdown 根节点。
   * 新增的高度约束写在 dropdown 根元素的 `maxHeight` 上，
   * 因此后续断言统一通过这个 helper 读取实际内联样式。
   *
   * @return 当前模型 dropdown 元素
   */
  const getModelDropdown = (): HTMLElement => {
    const dropdown = document.querySelector('.selector-dropdown.selector-dropdown--model');
    expect(dropdown).toBeTruthy();
    return dropdown as HTMLElement;
  };

  /**
   * 主动触发测试环境中的 `ResizeObserver` 回调。
   * 真实浏览器会在内容尺寸变化后自动回调，这里手动触发，
   * 用来验证 dropdown 是否会在 observer 链路上重新计算高度和滚动态。
   *
   * @return void
   */
  const triggerResizeObservers = (): void => {
    act(() => {
      resizeObserverEntries.forEach(({ callback }) => {
        callback([], {} as ResizeObserver);
      });
    });
  };

  /**
   * 验证 rerender 后会重新读取本地 Claude 模型映射，
   * 避免设置页更新映射后选择器仍显示旧名称。
   */
  it('rerender 后应读取最新的 Claude 模型映射', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-4' }),
    );

    const { rerender } = render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4');

    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ sonnet: 'glm-5' }),
    );

    rerender(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-5');
  });

  /**
   * 验证没有具体模型映射时，会回退到 main 全局映射，
   * 避免 provider 只配置了 main 时 UI 退化为内置标签。
   */
  it('没有具体映射时应回退到全局 main 映射', () => {
    localStorage.setItem(
      STORAGE_KEYS.CLAUDE_MODEL_MAPPING,
      JSON.stringify({ main: 'glm-4.7' }),
    );

    render(
      <ModelSelect
        value={sonnetModel.id}
        onChange={vi.fn()}
        models={[sonnetModel]}
        currentProvider="claude"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('glm-4.7');
  });

  /**
   * 验证 Codex 聊天区按钮优先保留配置里的原始模型标签大小写，
   * 避免 locale 把配置中本来就是小写的 `gpt-5.5` 覆盖成其他展示值。
   */
  it('应保留 gpt-5.5 配置里的原始小写标签', () => {
    const model: ModelInfo = {
      id: 'gpt-5.5',
      label: 'gpt-5.5',
      description: 'Frontier model for complex coding, research, and real-world work.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('gpt-5.5');
    expect(screen.getByRole('button').textContent).not.toContain('models.codex.gpt55.label');
  });

  /**
   * 验证 Codex catalog 自定义 label 也必须原样展示，
   * 避免 provider 已经返回可用标签时又被前端翻译层二次改写。
   */
  it('应保留 Codex catalog 返回的自定义标签', () => {
    const model: ModelInfo & { rawModelId: string; providerLabel: string } = {
      id: 'managed-openai::gpt-5.5',
      rawModelId: 'gpt-5.5',
      label: 'my-gpt-5.5-lowercase',
      providerLabel: 'Managed OpenAI',
      description: 'Frontier model for complex coding, research, and real-world work.',
    };

    render(
      <ModelSelect
        value={model.id}
        selectedCodexSelectionKey={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('my-gpt-5.5-lowercase');
    expect(screen.getByRole('button').textContent).not.toContain('models.codex.gpt55.label');
  });

  /**
   * 验证其他 Codex 内置模型同样优先展示配置中的原始标签，
   * 避免只有 `gpt-5.5` 修复，而其余小写模型仍继续走翻译键覆盖。
   */
  it('应保留 gpt-5.4-mini 配置里的原始标签', () => {
    const model: ModelInfo = {
      id: 'gpt-5.4-mini',
      label: 'gpt-5.4-mini',
      description: 'Small, fast, and cost-efficient model for simpler coding tasks.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('gpt-5.4-mini');
    expect(screen.getByRole('button').textContent).not.toContain('models.codex.gpt54mini.label');
  });

  /**
   * 验证当前会话模型与 CLI 默认模型不一致时，按钮上会保留 CLI 默认提示，
   * 避免用户误以为 CLI 默认模型已经被会话内模型替换。
   */
  it('会在当前模型与 CLI 默认模型不一致时展示默认模型提示', () => {
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Latest frontier model with enhanced capabilities.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
        defaultCodexModelFromConfig="gpt-5.5"
      />,
    );

    expect(screen.getByRole('button').textContent).toContain('gpt-5.5');
  });

  /**
   * 验证自定义 Codex base_url 时，下拉框会显示风险提示，
   * 这样用户能明确知道上游不保证所有模型均可用。
   */
  it('会在下拉框中展示自定义 Codex base_url 风险提示', () => {
    const model: ModelInfo = {
      id: 'gpt-5.5',
      label: 'gpt-5.5',
      description: 'Frontier model for complex coding, research, and real-world work.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
        codexBaseUrl="https://rayplus.site"
        codexUsesCustomBaseUrl
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('Custom OpenAI base URL')).toBeTruthy();
    expect(screen.getByText('https://rayplus.site may not support all model selections.')).toBeTruthy();
  });

  /**
   * 验证统一 catalog 场景下，Codex 下拉项可以同时展示模型名和 provider 标签。
   * 这样用户在多 provider 并存时，能够直接区分同名模型来自哪个 provider。
   */
  it('会在 Codex catalog 模型项上展示 provider 标签', () => {
    const { container } = render(
      <ModelSelect
        value="managed-openai::gpt-5.5"
        selectedCodexSelectionKey="managed-openai::gpt-5.5"
        onChange={vi.fn()}
        models={[{
          id: 'managed-openai::gpt-5.5',
          rawModelId: 'gpt-5.5',
          label: 'gpt-5.5',
          providerId: 'managed-openai',
          providerLabel: 'Managed OpenAI',
          runnable: true,
        }]}
        currentProvider="codex"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    expect(within(dropdown as HTMLElement).getByText('Managed OpenAI')).toBeTruthy();
  });

  /**
   * 验证 Codex catalog 出现同名模型时，选中态必须按 providerId + modelId 精确匹配。
   * 避免仅凭 raw modelId 回退比较，导致不同供应商的同名模型被同时勾选。
   */
  it('只勾选与复合 key 完全匹配的 Codex catalog 模型项', () => {
    const { container } = render(
      <ModelSelect
        value="gpt-5.4"
        selectedCodexSelectionKey="managed-openai::gpt-5.4"
        onChange={vi.fn()}
        models={[
          {
            id: 'managed-openai::gpt-5.4',
            rawModelId: 'gpt-5.4',
            label: 'gpt-5.4',
            providerId: 'managed-openai',
            providerLabel: 'Managed OpenAI',
            runnable: true,
          },
          {
            id: 'custom_gateway::gpt-5.4',
            rawModelId: 'gpt-5.4',
            label: 'gpt-5.4',
            providerId: 'custom_gateway',
            providerLabel: 'Buycode',
            runnable: true,
          },
        ]}
        currentProvider="codex"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    const selectedOptions = dropdown?.querySelectorAll('.selector-option.selected') ?? [];
    expect(selectedOptions).toHaveLength(1);
    expect(within(selectedOptions[0] as HTMLElement).getByText('Managed OpenAI')).toBeTruthy();
  });

  /**
   * 验证 CLI 未授权等“可见但不可运行”的 catalog 项会以 disabled 形式展示。
   * 这样聊天区可以告知用户该模型存在，但在授权完成前不能直接切换使用。
   */
  it('会禁用不可运行的 Codex catalog 模型项', () => {
    const onChange = vi.fn();

    const { container } = render(
      <ModelSelect
        value="managed-openai::gpt-5.5"
        onChange={onChange}
        models={[
          {
            id: 'managed-openai::gpt-5.5',
            rawModelId: 'gpt-5.5',
            label: 'gpt-5.5',
            providerId: 'managed-openai',
            providerLabel: 'Managed OpenAI',
            runnable: false,
          },
        ]}
        currentProvider="codex"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    const dropdown = container.querySelector('.selector-dropdown');
    expect(dropdown).toBeTruthy();
    const option = within(dropdown as HTMLElement).getByText('Managed OpenAI').closest('.selector-option');
    expect(option?.getAttribute('aria-disabled')).toBe('true');
    fireEvent.click(option as HTMLElement);
    expect(onChange).not.toHaveBeenCalled();
  });

  /**
   * 验证 Claude 内置模型列表只保留基础 ID，
   * 避免旧版 `[1m]` 变体重新回流到静态模型表中。
   */
  it('Claude 内置模型列表应默认使用不带 [1m] 的 Opus 4.6 ID', () => {
    expect(CLAUDE_MODELS.map((model) => model.id)).toContain('claude-opus-4-6');
    expect(CLAUDE_MODELS.map((model) => model.id)).not.toContain('claude-opus-4-6[1m]');
  });

  /**
   * 验证 Codex 内置模型列表包含第二阶段并轨需要的全部模型，
   * 防止后续重构时把 upstream 新增模型意外删掉。
   */
  it('Codex 内置模型列表应与目标设计一致', () => {
    expect(CODEX_MODELS.map((model) => model.id)).toEqual([
      'gpt-5.5',
      'gpt-5.4',
      'gpt-5.2-codex',
      'gpt-5.1-codex-max',
      'gpt-5.4-mini',
      'gpt-5.3-codex',
      'gpt-5.3-codex-spark',
      'gpt-5.2',
      'gpt-5.1-codex-mini',
    ]);
  });
  /**
   * 验证 Codex 场景下点击“添加模型”后，不再直接触发旧的单一路径回调。
   * 正确行为应先展开动作菜单，让用户区分“新增供应商配置”“管理当前供应商模型”和“添加模型别名（高级）”。
   */
  it('Codex 场景下应先展开模型管理动作菜单而不是直接触发添加回调', () => {
    const onAddModel = vi.fn();
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Strong model for everyday coding.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
        onAddModel={onAddModel}
      />,
    );

    fireEvent.click(screen.getByRole('button'));
    fireEvent.click(screen.getByText('models.addModel'));

    expect(screen.getByText('chat.addCodexProviderAction')).toBeTruthy();
    expect(screen.getByText('chat.manageCurrentCodexProviderModelsAction')).toBeTruthy();
    expect(screen.getByText('chat.addCodexModelAliasAction')).toBeTruthy();
    expect(onAddModel).not.toHaveBeenCalled();
  });

  /**
   * 验证正常 viewport 场景下，dropdown 最大高度会按按钮顶部空间收敛。
   * 该用例覆盖“空间足够但小于默认上限”的基础路径，
   * 防止后续修复边界分支时把常规限高逻辑一并改坏。
   */
  it('正常 viewport 场景下应按按钮顶部空间限制 dropdown 最大高度', async () => {
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Strong model for everyday coding.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    const button = screen.getByRole('button');
    mockButtonRect(button, { top: 72 });

    fireEvent.click(button);

    await waitFor(() => {
      expect(getModelDropdown().style.maxHeight).toBe('56px');
    });
  });

  /**
   * 验证 `#app` 存在顶部偏移时，不会因为 app 坐标系与窗口坐标系的差值而错误退回默认 420px。
   * 这是本次修复要覆盖的核心缺陷：
   * 当 app 顶部有偏移但按钮在窗口内仍然可见时，dropdown 应该退回到窗口内真实可见的顶部空间。
   */
  it('app viewport 顶部偏移导致 app 内可用高度为负时应退回到窗口内真实可见高度', async () => {
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Strong model for everyday coding.',
    };

    viewportState = {
      ...viewportState,
      top: 80,
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    const button = screen.getByRole('button');
    mockButtonRect(button, { top: 90 });

    fireEvent.click(button);

    await waitFor(() => {
      expect(getModelDropdown().style.maxHeight).toBe('74px');
    });
  });

  /**
   * 验证按钮顶部空间充足时，dropdown 仍会继续受 420px 默认上限约束。
   * 该断言用于防止修复边界分支时误删掉原有的“最高不超过 420px”保护。
   */
  it('顶部空间足够大时应继续受 420px 上限约束', async () => {
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Strong model for everyday coding.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    const button = screen.getByRole('button');
    mockButtonRect(button, { top: 620 });

    fireEvent.click(button);

    await waitFor(() => {
      expect(getModelDropdown().style.maxHeight).toBe('420px');
    });
  });

  /**
   * 验证窗口尺寸变化后，dropdown 会重新读取最新的布局测量结果并更新最大高度。
   * 这样可以直接覆盖 `window.resize -> refreshDropdownLayout` 这条刷新链路，
   * 避免只靠滚动测试间接推断高度重算仍然有效。
   */
  it('window.resize 后应重新计算 dropdown 最大高度', async () => {
    const model: ModelInfo = {
      id: 'gpt-5.4',
      label: 'gpt-5.4',
      description: 'Strong model for everyday coding.',
    };

    render(
      <ModelSelect
        value={model.id}
        onChange={vi.fn()}
        models={[model]}
        currentProvider="codex"
      />,
    );

    const button = screen.getByRole('button');
    const buttonRectState = { top: 320 };
    mockButtonRect(button, buttonRectState);

    fireEvent.click(button);

    await waitFor(() => {
      expect(getModelDropdown().style.maxHeight).toBe('304px');
    });

    buttonRectState.top = 210;
    mockButtonRect(button, buttonRectState);
    fireEvent(window, new Event('resize'));

    await waitFor(() => {
      expect(getModelDropdown().style.maxHeight).toBe('194px');
    });
  });

  /**
   * 验证内容尺寸变化触发 `ResizeObserver` 后，dropdown 会重新同步滚动态和高度约束。
   * 该用例直接覆盖 observer 回调链路，避免后续重构时 observer 失效但测试仍然全部通过。
   */
  it('ResizeObserver 回调后应同步刷新滚动态与 dropdown 最大高度', async () => {
    const models: ModelInfo[] = Array.from({ length: 18 }, (_, index) => ({
      id: `observer-model-${index}`,
      label: `Observer Model ${index}`,
      description: `Observer Description ${index}`,
    }));

    render(
      <ModelSelect
        value={models[0].id}
        onChange={vi.fn()}
        models={models}
        currentProvider="codex"
      />,
    );

    const button = screen.getByRole('button');
    const buttonRectState = { top: 340 };
    mockButtonRect(button, buttonRectState);

    fireEvent.click(button);

    const scrollBody = await screen.findByTestId('model-select-scroll-body');
    mockScrollMetrics(scrollBody, {
      clientHeight: 220,
      scrollHeight: 220,
      scrollTop: 0,
    });

    await waitFor(() => {
      expect(getModelDropdown().style.maxHeight).toBe('324px');
    });
    expect(scrollBody.classList.contains('selector-dropdown-body--scrollable')).toBe(false);

    buttonRectState.top = 180;
    mockButtonRect(button, buttonRectState);
    mockScrollMetrics(scrollBody, {
      clientHeight: 180,
      scrollHeight: 540,
      scrollTop: 0,
    });
    triggerResizeObservers();

    await waitFor(() => {
      expect(getModelDropdown().style.maxHeight).toBe('164px');
      expect(scrollBody.classList.contains('selector-dropdown-body--scrollable')).toBe(true);
    });
  });

  /**
   * 验证 warning banner、可滚动长列表和 footer 同时存在时仍能稳定共存。
   * 该用例直接覆盖整改清单里的组合场景，确保新增的高度约束不会把 banner 或 footer 挤出可见区，
   * 也不会让 Codex 的“添加模型”入口在滚动态下失效。
   */
  it('warning banner 与 footer 在长列表滚动态下应继续可见且可触发 Codex 管理动作', async () => {
    const onAddModel = vi.fn();
    const models: ModelInfo[] = Array.from({ length: 20 }, (_, index) => ({
      id: `codex-warning-model-${index}`,
      label: `Codex Warning Model ${index}`,
      description: `Codex Warning Description ${index}`,
    }));

    render(
      <ModelSelect
        value={models[0].id}
        onChange={vi.fn()}
        models={models}
        currentProvider="codex"
        onAddModel={onAddModel}
        codexBaseUrl="https://gateway.example.com"
        codexUsesCustomBaseUrl
      />,
    );

    const button = screen.getByRole('button');
    mockButtonRect(button, { top: 260 });
    fireEvent.click(button);

    const dropdown = getModelDropdown();
    const scrollBody = await screen.findByTestId('model-select-scroll-body');
    mockScrollMetrics(scrollBody, {
      clientHeight: 160,
      scrollHeight: 560,
      scrollTop: 0,
    });
    fireEvent.scroll(scrollBody);

    await waitFor(() => {
      expect(dropdown.style.maxHeight).toBe('244px');
      expect(scrollBody.classList.contains('selector-dropdown-body--scrollable')).toBe(true);
    });

    expect(screen.getByText('Custom OpenAI base URL')).toBeTruthy();
    expect(screen.getByText('models.addModel')).toBeTruthy();

    fireEvent.click(screen.getByText('models.addModel'));

    expect(screen.getByText('chat.addCodexProviderAction')).toBeTruthy();
    expect(screen.getByText('chat.manageCurrentCodexProviderModelsAction')).toBeTruthy();
    expect(screen.getByText('chat.addCodexModelAliasAction')).toBeTruthy();
    expect(onAddModel).not.toHaveBeenCalled();
  });

  /**
   * 验证模型列表过长时，会进入“独立滚动主体”模式，
   * 该用例覆盖本次改造的核心回归点：
   * 1. 模型下拉不再单纯无限增高；
   * 2. 列表主体会成为独立滚动容器；
   * 3. 当前实现只保留原生滚动条，不再额外渲染自定义蓝色进度条。
   */
  it('超长模型列表应启用独立滚动主体且不再渲染自定义进度条', async () => {
    const models: ModelInfo[] = Array.from({ length: 30 }, (_, index) => ({
      id: `gpt-custom-${index}`,
      label: `Custom Model ${index}`,
      description: `Description ${index}`,
    }));

    render(
      <ModelSelect
        value={models[0].id}
        onChange={vi.fn()}
        models={models}
        currentProvider="codex"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    const scrollBody = await screen.findByTestId('model-select-scroll-body');
    mockScrollMetrics(scrollBody, {
      clientHeight: 180,
      scrollHeight: 720,
      scrollTop: 0,
    });
    fireEvent.scroll(scrollBody);

    expect(scrollBody).toBeTruthy();
    expect(scrollBody.classList.contains('selector-dropdown-body--scrollable')).toBe(true);
    expect(screen.queryByTestId('model-select-progress')).toBeNull();
    expect(screen.queryByTestId('model-select-progress-thumb')).toBeNull();
  });

  /**
   * 验证滚动主体的可滚动态仍会随最新尺寸刷新。
   * 删除自定义进度条后，组件仍需保留滚动能力判定，
   * 否则后续若要根据滚动态补充样式或可访问性信息，将失去可靠状态来源。
   */
  it('滚动主体在尺寸变化后应继续同步更新可滚动态且不渲染自定义进度条', async () => {
    const models: ModelInfo[] = Array.from({ length: 24 }, (_, index) => ({
      id: `scroll-model-${index}`,
      label: `Scroll Model ${index}`,
      description: `Scroll Description ${index}`,
    }));

    render(
      <ModelSelect
        value={models[0].id}
        onChange={vi.fn()}
        models={models}
        currentProvider="codex"
      />,
    );

    fireEvent.click(screen.getByRole('button'));

    const scrollBody = await screen.findByTestId('model-select-scroll-body');
    mockScrollMetrics(scrollBody, {
      clientHeight: 200,
      scrollHeight: 800,
      scrollTop: 0,
    });
    fireEvent.scroll(scrollBody);

    expect(scrollBody.classList.contains('selector-dropdown-body--scrollable')).toBe(true);
    expect(screen.queryByTestId('model-select-progress')).toBeNull();

    mockScrollMetrics(scrollBody, {
      clientHeight: 200,
      scrollHeight: 200,
      scrollTop: 0,
    });
    fireEvent.scroll(scrollBody);

    expect(scrollBody.classList.contains('selector-dropdown-body--scrollable')).toBe(false);
    expect(screen.queryByTestId('model-select-progress-thumb')).toBeNull();
  });
});
