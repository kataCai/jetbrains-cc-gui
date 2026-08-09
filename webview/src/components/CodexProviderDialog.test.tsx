import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CodexProviderDialog, {
  getCodexProviderModeDescription,
  validateCodexProviderDraft,
} from './CodexProviderDialog';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, string>) => {
      const translations: Record<string, string> = {
        'settings.codexProvider.dialog.addTitle': '新增 Codex 供应商',
        'settings.codexProvider.dialog.editTitle': `编辑 ${options?.name ?? ''}`,
        'settings.codexProvider.dialog.addDescription': '创建一个可运行的 Codex 供应商配置。',
        'settings.codexProvider.dialog.editDescription': '编辑当前 Codex 供应商配置。',
        'settings.codexProvider.dialog.providerPreset': '供应商模板',
        'settings.codexProvider.dialog.providerPresetPlaceholder': '选择模板',
        'settings.codexProvider.dialog.providerName': '供应商名称',
        'settings.codexProvider.dialog.providerNamePlaceholder': '例如：MiniMax',
        'settings.codexProvider.dialog.websiteUrl': '官网链接',
        'settings.codexProvider.dialog.websiteUrlPlaceholder': '例如：https://platform.minimaxi.com',
        'settings.codexProvider.dialog.apiKeyApplyUrl': '获取 API Key',
        'settings.codexProvider.dialog.apiKeyApplyUrlPlaceholder': '例如：https://platform.minimaxi.com/user-center/basic-information/interface-key',
        'settings.provider.dialog.remark': '备注',
        'settings.provider.dialog.remarkPlaceholder': '例如：公司专用账号',
        'settings.codexProvider.dialog.authMode': '认证方式',
        'settings.codexProvider.dialog.authModeHint': '认证方式说明',
        'settings.codexProvider.dialog.requestMode': '请求模式',
        'settings.codexProvider.dialog.requestModeHint': '请求模式说明',
        'settings.codexProvider.dialog.requestModeUnavailableHint': '当前模式暂未落地，请先切换到 Codex SDK。',
        'settings.codexProvider.dialog.requestModeUnavailableOptionSuffix': '（开发中）',
        'settings.codexProvider.dialog.baseUrl': 'Base URL',
        'settings.codexProvider.dialog.baseUrlPlaceholder': '例如：https://api.example.com/v1',
        'settings.codexProvider.dialog.baseUrlHint': '请求级 Base URL',
        'settings.codexProvider.dialog.apiKey': 'API Key',
        'settings.codexProvider.dialog.apiKeyPlaceholder': '请输入本地保存的 API Key',
        'settings.codexProvider.dialog.apiKeyEnv': 'API Key 环境变量',
        'settings.codexProvider.dialog.apiKeyEnvPlaceholder': '例如：OPENAI_API_KEY',
        'settings.codexProvider.dialog.apiKeyEnvHint': '优先读取环境变量',
        'settings.codexProvider.dialog.modelList': '模型列表',
        'settings.codexProvider.dialog.models': '模型列表',
        'settings.codexProvider.dialog.modelAliasHelp': '模型列表决定该 provider 在聊天区可选的模型项。',
        'settings.codexProvider.dialog.addModelRow': '添加模型',
        'settings.codexProvider.fetchModels': '获取模型列表',
        'settings.codexProvider.fetchModelsLoading': '正在获取模型列表',
        'settings.codexProvider.dialog.modelIdPlaceholder': '模型 ID',
        'settings.codexProvider.dialog.modelLabelPlaceholder': '显示名称',
        'settings.codexProvider.dialog.modelDescriptionPlaceholder': '模型描述',
        'settings.codexProvider.dialog.reasoningEffortPlaceholder': '默认推理强度',
        'settings.codexProvider.dialog.advancedJsonToggle': '高级 JSON 编辑',
        'settings.codexProvider.dialog.advancedJsonHelp': '可批量粘贴模型 JSON 数组后同步到结构化列表。',
        'settings.codexProvider.dialog.modelsJsonLabel': '模型 JSON',
        'settings.codexProvider.dialog.applyModelsJson': '应用 JSON',
        'settings.codexProvider.dialog.modelsJsonInvalid': '模型 JSON 格式无效',
        'settings.codexProvider.dialog.nameRequired': '请输入供应商名称',
        'settings.codexProvider.dialog.apiKeyOrEnvRequired': '请至少配置 API Key 或 API Key 环境变量',
        'settings.codexProvider.dialog.baseUrlRequired': '当前请求模式需要配置 Base URL',
        'settings.codexProvider.dialog.modelsRequired': '请至少配置一个模型',
        'settings.codexProvider.dialog.authModeOptions.api_key': 'API Key',
        'settings.codexProvider.dialog.authModeOptions.api_key_env': '环境变量',
        'settings.codexProvider.dialog.authModeOptions.codex_cli_login': 'CLI Login',
        'settings.codexProvider.dialog.authModeOptions.proxy': 'Proxy',
        'settings.codexProvider.dialog.authModeOptions.oauth': 'OAuth',
        'settings.codexProvider.dialog.requestModeOptions.codex_sdk': 'Codex SDK',
        'settings.codexProvider.dialog.requestModeOptions.cc_switch_proxy': 'CC Switch Proxy',
        'settings.codexProvider.dialog.requestModeOptions.custom_adapter': 'Custom Adapter',
        'settings.provider.dialog.required': '*',
        'settings.provider.dialog.showApiKey': '显示',
        'settings.provider.dialog.hideApiKey': '隐藏',
        'settings.provider.dialog.confirmAdd': '保存',
        'settings.provider.dialog.saveChanges': '保存修改',
        'common.cancel': '取消',
        'common.close': '关闭',
        'common.add': '添加',
        'common.delete': '删除',
      };
      return translations[key] ?? options?.defaultValue ?? key;
    },
  }),
}));

/**
 * CodexProviderDialog 回归测试。
 * 这组测试覆盖本次改造后 provider 表单的两个关键目标：
 * 1. 首屏就是 provider-centric 结构化配置，而不是模型 JSON 文本框。
 * 2. 预置模板能够一次性填充官网、Base URL 与默认模型，形成接近 cc-switch 的创建体验。
 */
describe('CodexProviderDialog', () => {
  const onClose = vi.fn();
  const onSave = vi.fn();
  const addToast = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  /**
   * 验证新增 provider 时应展示结构化字段。
   * 该测试明确要求表单包含官网链接、获取 API Key 链接以及结构化模型列表入口，
   * 以防实现仍停留在旧版 textarea(JSON) 方案。
   */
  it('应在新增 provider 时展示结构化配置字段而不是仅暴露 JSON 模型文本框', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    expect(screen.getByLabelText('供应商模板')).toBeTruthy();
    expect(screen.getByLabelText('供应商名称')).toBeTruthy();
    expect(screen.getByLabelText('官网链接')).toBeTruthy();
    expect(screen.getByLabelText('获取 API Key')).toBeTruthy();
    expect(screen.getByText('模型列表决定该 provider 在聊天区可选的模型项。')).toBeTruthy();
    expect(screen.getByRole('button', { name: '添加模型' })).toBeTruthy();
    expect(screen.queryByRole('textbox', { name: '模型列表' })).toBeNull();
  });

  /**
   * 验证选择 MiniMax 模板后，表单会自动预填 provider 名称、官网、Base URL 和默认模型。
   * 这条回归测试的目标是确保 preset 不只是一个显示字段，而是真正承担初始化 provider 的职责。
   */
  it('应在选择预置模板后自动填充 provider 基础字段与默认模型', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    fireEvent.change(screen.getByLabelText('供应商模板'), { target: { value: 'minimax' } });

    expect((screen.getByLabelText('供应商名称') as HTMLInputElement).value).toBe('MiniMax');
    expect((screen.getByLabelText('官网链接') as HTMLInputElement).value).toBe('https://platform.minimaxi.com');
    expect((screen.getByLabelText('Base URL') as HTMLInputElement).value).toBe('https://api.minimaxi.com/v1');
    expect(screen.getByDisplayValue('MiniMax-M2.5')).toBeTruthy();
  });

  /**
   * 验证新增 provider 时可以消费来自模型别名升级入口的草稿数据。
   * 这里只允许预填名称、模板、认证模式和模型列表，不自动注入 baseUrl / apiKey 等需要用户确认的敏感参数。
   */
  it('应在新增 provider 时使用 initialProviderData 预填模型草稿', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        initialProviderData={{
          name: 'Alias Draft',
          providerType: 'custom_gateway',
          presetId: 'custom_gateway',
          authMode: 'api_key',
          requestMode: 'codex_sdk',
          models: [
            {
              id: 'alias-model',
              label: 'Alias Model',
              description: 'Alias Desc',
            },
          ],
        }}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    expect((screen.getByLabelText('供应商名称') as HTMLInputElement).value).toBe('Alias Draft');
    expect((screen.getByLabelText('供应商模板') as HTMLSelectElement).value).toBe('custom_gateway');
    expect((screen.getByLabelText('认证方式') as HTMLSelectElement).value).toBe('api_key');
    expect((screen.getByLabelText('请求模式') as HTMLSelectElement).value).toBe('codex_sdk');
    expect(screen.getByDisplayValue('alias-model')).toBeTruthy();
    expect(screen.getByDisplayValue('Alias Model')).toBeTruthy();
    expect(screen.getByDisplayValue('Alias Desc')).toBeTruthy();
    expect((screen.getByLabelText('Base URL') as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('API Key') as HTMLInputElement).value).toBe('');
  });

  /**
   * 验证保存会把新增字段一并透传给 onSave。
   * 断言 providerType/presetId/websiteUrl/apiKeyApplyUrl 以及结构化 models 都存在，
   * 避免前端表单虽然显示出来，但提交时仍被旧 payload 丢掉。
   */
  it('应在保存时提交 provider 元信息与结构化模型列表', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    fireEvent.change(screen.getByLabelText('供应商模板'), { target: { value: 'custom_gateway' } });
    fireEvent.change(screen.getByLabelText('供应商名称'), { target: { value: 'My Gateway' } });
    fireEvent.change(screen.getByLabelText('官网链接'), { target: { value: 'https://gateway.example.com' } });
    fireEvent.change(screen.getByLabelText('获取 API Key'), { target: { value: 'https://gateway.example.com/token' } });
    fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://gateway.example.com/v1' } });
    fireEvent.change(screen.getByLabelText('API Key'), { target: { value: 'sk-test-12345678' } });
    fireEvent.click(screen.getByRole('button', { name: '添加模型' }));
    fireEvent.change(screen.getByPlaceholderText('模型 ID'), { target: { value: 'gateway-chat' } });
    fireEvent.change(screen.getByPlaceholderText('显示名称'), { target: { value: 'Gateway Chat' } });
    fireEvent.change(screen.getByPlaceholderText('模型描述'), { target: { value: 'Gateway model' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));

    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      name: 'My Gateway',
      providerType: 'custom_gateway',
      presetId: 'custom_gateway',
      websiteUrl: 'https://gateway.example.com',
      apiKeyApplyUrl: 'https://gateway.example.com/token',
      baseUrl: 'https://gateway.example.com/v1',
      apiKey: 'sk-test-12345678',
      models: [
        expect.objectContaining({
          id: 'gateway-chat',
          label: 'Gateway Chat',
          description: 'Gateway model',
        }),
      ],
    }));
  });

  /**
   * 验证高级 JSON 编辑器可以把批量粘贴的模型数组同步回结构化模型列表。
   * 该测试覆盖“高级 JSON 编辑”仅作为结构化编辑的辅助入口，而不是回退到旧主路径：
   * 1. 用户通过 JSON 批量导入模型；
   * 2. 结构化输入行会被同步更新；
   * 3. 保存时仍走统一的 provider payload。
   */
  it('应支持通过高级 JSON 编辑器批量同步模型列表', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    fireEvent.click(screen.getByText('高级 JSON 编辑'));
    fireEvent.change(screen.getByLabelText('模型 JSON'), {
      target: {
        value: JSON.stringify([
          { id: 'json-model-a', label: 'JSON Model A', description: 'from json' },
          { id: 'json-model-b', label: 'JSON Model B' },
        ], null, 2),
      },
    });
    fireEvent.click(screen.getByRole('button', { name: '应用 JSON' }));

    expect(screen.getByDisplayValue('json-model-a')).toBeTruthy();
    expect(screen.getByDisplayValue('JSON Model A')).toBeTruthy();
    expect(screen.getByDisplayValue('json-model-b')).toBeTruthy();
    expect(screen.getByDisplayValue('JSON Model B')).toBeTruthy();
  });

  /**
   * 验证复制入口属于供应商卡片，而不是编辑弹窗内的模型行。
   * 弹窗只保留添加、删除和高级 JSON 编辑能力，避免把“复制整个供应商配置”误解成复制单个模型。
   */
  it('不应在编辑弹窗模型行中显示复制模型入口', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={{
          id: 'provider-with-model',
          name: 'Managed Gateway',
          authMode: 'api_key',
          requestMode: 'codex_sdk',
          baseUrl: 'https://gateway.example.com/v1',
          apiKey: 'sk-test-12345678',
          models: [
            {
              id: 'gateway-chat',
              label: 'Gateway Chat',
              description: 'Gateway model',
              reasoningEffort: 'high',
            },
          ],
        }}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    expect(screen.queryByRole('button', { name: '复制模型' })).toBeNull();
  });

  /**
   * 验证模型列表允许为空时仍可正常保存。
   * 该场景用于覆盖“当前 provider 先保存空配置，再通过同步模型补齐”的工作流，
   * 因此前端不能再用 `modelsRequired` 阻断提交。
   */
  it('应允许保存空模型列表并提交空 models 数组', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={{
          id: 'provider-empty-models',
          name: 'Empty Models Provider',
          authMode: 'api_key',
          requestMode: 'codex_sdk',
          baseUrl: 'https://gateway.example.com/v1',
          apiKey: 'sk-test-12345678',
          models: [],
        }}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '删除' }));
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }));

    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({
      models: [],
    }));
    expect(addToast).not.toHaveBeenCalledWith('请至少配置一个模型', 'error');
  });

  /**
   * 验证新增/复制草稿也能直接触发“获取模型列表”。
   * 当前业务确认该能力不能只限于已保存 provider：用户需要在新增或复制后、保存前，
   * 直接基于当前草稿里的 Base URL 与凭据发现远端模型。
   * 断言意图：
   * 1. `provider === null` 时按钮仍然存在；
   * 2. 点击后会把当前草稿字段交给上层回调，而不是要求先保存。
   */
  it('应允许在新增或复制草稿中直接获取模型列表', () => {
    const onFetchModels = vi.fn();

    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        initialProviderData={{
          name: 'Copied Draft Provider',
          authMode: 'api_key',
          requestMode: 'codex_sdk',
          baseUrl: 'https://copied.example.com/v1',
          apiKey: 'sk-copied',
          models: [],
        }}
        draftRequestId="draft-copy-1"
        onClose={onClose}
        onSave={onSave}
        onFetchModels={onFetchModels}
        addToast={addToast}
      />,
    );

    fireEvent.change(screen.getByLabelText('供应商名称'), { target: { value: 'Edited Draft Provider' } });
    fireEvent.click(screen.getByRole('button', { name: '获取模型列表' }));

    expect(onFetchModels).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Edited Draft Provider',
      authMode: 'api_key',
      requestMode: 'codex_sdk',
      baseUrl: 'https://copied.example.com/v1',
      apiKey: 'sk-copied',
    }));
  });

  /**
   * 验证“获取模型列表”入口只回填当前草稿里的 models 子树。
   * 用户在同步前对 providerName / remark 做出的未保存编辑必须保留，
   * 否则后端刷新 provider 列表后会把正在编辑的草稿整表覆盖掉。
   */
  it('应基于当前草稿获取模型并只追加缺失模型', () => {
    const onFetchModels = vi.fn();
    const baseProvider = {
      id: 'provider-sync-models',
      name: 'Sync Target Provider',
      authMode: 'api_key' as const,
      requestMode: 'codex_sdk' as const,
      baseUrl: 'https://gateway.example.com/v1',
      apiKey: 'sk-test-12345678',
      models: [
        {
          id: 'legacy-model',
          label: 'Legacy Model',
          description: 'legacy description',
        },
      ],
    };

    const { rerender } = render(
      <CodexProviderDialog
        isOpen
        provider={baseProvider}
        draftRequestId="draft-edit-1"
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
        onFetchModels={onFetchModels}
        fetchedDraftModels={null}
        fetchedDraftModelsRevision={0}
      />,
    );

    fireEvent.change(screen.getByLabelText('供应商名称'), { target: { value: 'Edited Provider Name' } });
    fireEvent.change(screen.getByLabelText('备注'), { target: { value: 'Edited Remark' } });
    fireEvent.click(screen.getByRole('button', { name: '获取模型列表' }));

    expect(onFetchModels).toHaveBeenCalledWith(expect.objectContaining({
      id: 'draft-edit-1',
      name: 'Edited Provider Name',
      baseUrl: 'https://gateway.example.com/v1',
      apiKey: 'sk-test-12345678',
    }));

    rerender(
      <CodexProviderDialog
        isOpen
        provider={baseProvider}
        draftRequestId="draft-edit-1"
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
        onFetchModels={onFetchModels}
        fetchedDraftModels={{
          providerId: 'provider-sync-models',
          modelIds: ['legacy-model', 'synced-model'],
          duplicateCount: 0,
          skippedCount: 0,
        }}
        fetchedDraftModelsRevision={1}
      />,
    );

    expect((screen.getByLabelText('供应商名称') as HTMLInputElement).value).toBe('Edited Provider Name');
    expect((screen.getByLabelText('备注') as HTMLInputElement).value).toBe('Edited Remark');
    expect(screen.getByDisplayValue('legacy-model')).toBeTruthy();
    expect(screen.getByDisplayValue('Legacy Model')).toBeTruthy();
    expect(screen.getAllByDisplayValue('synced-model')).toHaveLength(2);
    fireEvent.click(screen.getByRole('button', { name: '高级 JSON 编辑' }));
    expect((screen.getByLabelText('模型 JSON') as HTMLTextAreaElement).value).toContain('"id": "legacy-model"');
    expect((screen.getByLabelText('模型 JSON') as HTMLTextAreaElement).value).toContain('"id": "synced-model"');
  });

  /**
   * 验证当前只有 codex_sdk 可以作为新建 provider 的可选请求模式。
   * 未落地模式仍保留在下拉列表中用于传达规划方向，但必须以 disabled 形式呈现，
   * 防止用户继续创建“看起来能配、实际上跑不起来”的假配置。
   */
  it('应在新建 provider 时禁用未落地的请求模式选项', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={null}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    const requestModeSelect = screen.getByLabelText('请求模式') as HTMLSelectElement;
    const ccSwitchOption = Array.from(requestModeSelect.options).find((option) => option.value === 'cc_switch_proxy');
    const customAdapterOption = Array.from(requestModeSelect.options).find((option) => option.value === 'custom_adapter');

    expect(ccSwitchOption?.disabled).toBe(true);
    expect(customAdapterOption?.disabled).toBe(true);
    expect(ccSwitchOption?.textContent).toContain('开发中');
    expect(customAdapterOption?.textContent).toContain('开发中');
  });

  /**
   * 验证历史 provider 若仍使用未落地模式，设置页会给出显式风险提示并禁止继续保存激活。
   * 这样用户仍可打开旧配置并切换回 codex_sdk，但不会继续把未实现模式作为可运行能力误用。
   */
  it('应在编辑未落地模式的历史 provider 时显示风险提示并禁用保存操作', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={{
          id: 'legacy-proxy-provider',
          name: 'Legacy Proxy',
          authMode: 'api_key',
          requestMode: 'cc_switch_proxy',
          baseUrl: 'http://127.0.0.1:15721',
          apiKey: 'sk-test',
          models: [{ id: 'legacy-model', label: 'Legacy Model' }],
        }}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    expect(screen.getAllByText('当前模式暂未落地，请先切换到 Codex SDK。').length).toBeGreaterThanOrEqual(1);
    expect((screen.getByRole('button', { name: '保存修改' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('CodexProviderDialog request-mode dynamic fields', () => {
  const onClose = vi.fn();
  const onSave = vi.fn();
  const addToast = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders cc_switch_proxy fields and hides codex_sdk baseUrl field', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={{
          id: 'legacy-proxy-provider',
          name: 'Legacy Proxy',
          authMode: 'proxy',
          requestMode: 'cc_switch_proxy',
          apiKey: 'sk-test',
          models: [{ id: 'legacy-model', label: 'Legacy Model' }],
          ccSwitchProxy: {
            proxyEndpoint: 'http://127.0.0.1:15721',
            providerRoute: 'minimax',
            requestPath: '/v1/responses',
            requestHeaders: {
              'x-route': 'minimax',
            },
          },
        }}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    expect(screen.queryByLabelText('Base URL')).toBeNull();
    expect(screen.getByLabelText('CC Switch Proxy Endpoint')).toBeTruthy();
    expect(screen.getByLabelText('Provider Route')).toBeTruthy();
    expect(screen.getByLabelText('Request Path')).toBeTruthy();
    expect(screen.getByLabelText('Proxy Headers JSON')).toBeTruthy();
  });

  it('renders custom_adapter fields and hides codex_sdk baseUrl field', () => {
    render(
      <CodexProviderDialog
        isOpen
        provider={{
          id: 'legacy-adapter-provider',
          name: 'Legacy Adapter',
          authMode: 'api_key',
          requestMode: 'custom_adapter',
          apiKey: 'sk-test',
          models: [{ id: 'adapter-model', label: 'Adapter Model' }],
          customAdapter: {
            adapterId: 'minimax-adapter',
            adapterEndpoint: 'http://127.0.0.1:8080/adapter/codex',
            adapterHeaders: {
              Authorization: 'Bearer adapter',
            },
            adapterExtras: {
              provider: 'minimax',
            },
          },
        }}
        onClose={onClose}
        onSave={onSave}
        addToast={addToast}
      />,
    );

    expect(screen.queryByLabelText('Base URL')).toBeNull();
    expect(screen.getByLabelText('Adapter ID')).toBeTruthy();
    expect(screen.getByLabelText('Adapter Endpoint')).toBeTruthy();
    expect(screen.getByLabelText('Adapter Headers JSON')).toBeTruthy();
    expect(screen.getByLabelText('Adapter Extras JSON')).toBeTruthy();
  });
});

describe('CodexProviderDialog mode validation helpers', () => {
  it('validates cc_switch_proxy required fields independently from codex_sdk baseUrl', () => {
    expect(validateCodexProviderDraft({
      providerName: 'Proxy Provider',
      authMode: 'proxy',
      requestMode: 'cc_switch_proxy',
      baseUrl: '',
      apiKey: 'sk-test',
      apiKeyEnv: '',
      normalizedModels: [{ id: 'proxy-model', label: 'Proxy Model' }],
      ccSwitchProxy: {
        proxyEndpoint: '',
        providerRoute: '',
        requestPath: '/v1/responses',
        requestHeaders: {},
      },
      customAdapter: {
        adapterId: '',
        adapterEndpoint: '',
        adapterHeaders: {},
        adapterExtras: {},
      },
    })).toBe('settings.codexProvider.dialog.proxyEndpointRequired');
  });

  it('validates custom_adapter required fields independently from codex_sdk baseUrl', () => {
    expect(validateCodexProviderDraft({
      providerName: 'Adapter Provider',
      authMode: 'api_key',
      requestMode: 'custom_adapter',
      baseUrl: '',
      apiKey: 'sk-test',
      apiKeyEnv: '',
      normalizedModels: [{ id: 'adapter-model', label: 'Adapter Model' }],
      ccSwitchProxy: {
        proxyEndpoint: '',
        providerRoute: '',
        requestPath: '',
        requestHeaders: {},
      },
      customAdapter: {
        adapterId: '',
        adapterEndpoint: '',
        adapterHeaders: {},
        adapterExtras: {},
      },
    })).toBe('settings.codexProvider.dialog.adapterIdRequired');
  });

  /**
   * 验证空模型列表不再被视为校验错误。
   * 这样用户可以先保存 provider 骨架，再通过“同步模型”入口把远端模型列表补齐回来。
   */
  it('allows an empty model list during draft validation', () => {
    expect(validateCodexProviderDraft({
      providerName: 'Empty Models Provider',
      authMode: 'api_key',
      requestMode: 'codex_sdk',
      baseUrl: 'https://gateway.example.com/v1',
      apiKey: 'sk-test',
      apiKeyEnv: '',
      normalizedModels: [],
      ccSwitchProxy: {
        proxyEndpoint: '',
        providerRoute: '',
        requestPath: '',
        requestHeaders: {},
      },
      customAdapter: {
        adapterId: '',
        adapterEndpoint: '',
        adapterHeaders: {},
        adapterExtras: {},
      },
    })).toBeNull();
  });

  it('exposes a distinct mode description key for each request mode', () => {
    expect(getCodexProviderModeDescription('codex_sdk')).toBe('settings.codexProvider.dialog.modeDescription.codex_sdk');
    expect(getCodexProviderModeDescription('cc_switch_proxy')).toBe('settings.codexProvider.dialog.modeDescription.cc_switch_proxy');
    expect(getCodexProviderModeDescription('custom_adapter')).toBe('settings.codexProvider.dialog.modeDescription.custom_adapter');
  });
});
