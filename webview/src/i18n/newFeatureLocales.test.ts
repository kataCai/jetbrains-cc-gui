import { describe, expect, it } from 'vitest';
import en from './locales/en.json';
import es from './locales/es.json';
import fr from './locales/fr.json';
import hi from './locales/hi.json';
import ja from './locales/ja.json';
import ko from './locales/ko.json';
import ptBR from './locales/pt-BR.json';
import ru from './locales/ru.json';
import zh from './locales/zh.json';
import zhTW from './locales/zh-TW.json';

const ALL_LOCALES = {
  en,
  es,
  fr,
  hi,
  ja,
  ko,
  'pt-BR': ptBR,
  ru,
  zh,
  'zh-TW': zhTW,
} as const;

const REQUIRED_NEW_FEATURE_KEYS = [
  'settings.basic.taskReminder.label',
  'settings.basic.taskReminder.hint',
  'settings.basic.taskReminder.popup',
  'settings.basic.taskReminder.balloon',
  'settings.basic.taskReminder.sound',
  'settings.basic.taskReminder.system',
  'settings.basic.taskReminder.enabled',
  'settings.basic.taskReminder.onlyWhenIdeUnfocused',
  'settings.basic.taskReminder.balloonNotificationsHint',
  'settings.basic.taskReminder.testPopup',
  'settings.basic.taskReminder.testBalloon',
  'settings.basic.rightClickOpenDevTools.label',
  'settings.basic.rightClickOpenDevTools.enabled',
  'settings.basic.rightClickOpenDevTools.disabled',
  'settings.basic.rightClickOpenDevTools.hint',
  'contextMenu.openDevTools',
  'settings.basic.taskReminder.state.waitingConfirm',
  'settings.basic.taskReminder.state.retrying',
  'settings.basic.taskReminder.state.recovered',
  'settings.basic.taskReminder.state.finalError',
  'settings.basic.taskReminder.state.completed',
  'taskReminder.finalErrorTitle',
  'taskReminder.waitingConfirmTitle',
  'taskReminder.openSession',
  'taskReminder.retry',
  'taskReminder.close',
  'taskReminder.later',
  'settings.remoteCollab.title',
  'settings.remoteCollab.description',
  'settings.remoteCollab.enableTitle',
  'settings.remoteCollab.enableDesc',
  'settings.remoteCollab.debugModeTitle',
  'settings.remoteCollab.debugModeDesc',
  'settings.remoteCollab.interactiveProvider',
  'settings.remoteCollab.notifyProviders',
  'settings.remoteCollab.routingPanel',
  'settings.remoteCollab.routingInteractiveLabel',
  'settings.remoteCollab.routingNotifyLabel',
  'settings.remoteCollab.routingSummaryNote',
  'settings.remoteCollab.editRoutingPolicy',
  'settings.remoteCollab.cancelRoutingEdit',
  'settings.remoteCollab.saveRoutingPolicy',
  'settings.remoteCollab.providersPanel',
  'settings.remoteCollab.providerEnabled',
  'settings.remoteCollab.providerDisabled',
  'settings.remoteCollab.providerRegistered',
  'settings.remoteCollab.providerUnregistered',
  'settings.remoteCollab.providerInteractiveRoute',
  'settings.remoteCollab.providerNotifyRoute',
  'settings.remoteCollab.openProviderSettings',
  'settings.remoteCollab.backToProviders',
  'settings.remoteCollab.providerDetailEyebrow',
  'settings.remoteCollab.gotifyWebDescription',
  'settings.remoteCollab.feishuDescription',
  'settings.remoteCollab.feishuConfig',
  'settings.remoteCollab.saveFeishu',
  'settings.remoteCollab.startFeishuBinding',
  'settings.remoteCollab.telegramConfig',
  'settings.remoteCollab.gotifyConfig',
  'settings.remoteCollab.gotifyServerUrl',
  'settings.remoteCollab.gotifyWorkspaceUrl',
  'settings.remoteCollab.gotifyApiToken',
  'settings.remoteCollab.gotifyPollInterval',
  'settings.remoteCollab.saveGotify',
  'settings.remoteCollab.connectionStatus',
  'settings.remoteCollab.sendTestMessage',
  'settings.remoteCollab.debugPanel',
  'settings.remoteCollab.refreshDebugSnapshot',
  'settings.remoteCollab.debugRecentRequests',
  'settings.remoteCollab.debugRecentErrors',
  'settings.remoteCollab.debugRecentActions',
  'settings.remoteCollab.debugLastAction',
  'settings.remoteCollab.debugLastActionMessage',
  'settings.remoteCollab.debugEmpty',
  'settings.remoteCollab.debugRequestsTitle',
  'settings.remoteCollab.debugErrorsTitle',
  'settings.remoteCollab.debugActionsTitle',
  'settings.remoteCollab.telegramDebugPanel',
  'settings.remoteCollab.telegramDebugLastAction',
  'settings.remoteCollab.telegramDebugSendTestMessage',
  'chat.composerMode',
  'chat.chatMode',
  'chat.planMode',
  'chat.planModeLabel',
  'chat.planUnavailableForCodex',
  'chat.planRequiresClaudeHint',
  'chat.planDowngradedForCodex',
  'chat.modeStripLabel',
  'chat.taskState.running',
  'chat.taskState.waiting_confirm',
  'chat.taskState.retrying',
  'chat.taskState.recovered',
  'chat.taskState.completed',
  'chat.taskState.final_error',
  'settings.codexProvider.cliLogin.title',
  'settings.codexProvider.cliLogin.description',
  'settings.codexProvider.cliLogin.readonlyHint',
  'settings.codexProvider.cliLogin.authorizationStatus',
  'settings.codexProvider.cliLogin.currentUsageStatus',
  'settings.codexProvider.cliLogin.authorized',
  'settings.codexProvider.cliLogin.notAuthorized',
  'settings.codexProvider.cliLogin.currentlyUsed',
  'settings.codexProvider.cliLogin.notInUse',
  'settings.codexProvider.cliLogin.authorizeOnly',
  'settings.codexProvider.modelsTitle',
  'settings.codexProvider.modelsDescription',
  'settings.codexProvider.modelsSearchPlaceholder',
  'settings.codexProvider.modelsEmpty',
  'settings.codexProvider.modelsViewAll',
  'settings.codexProvider.modelsCollapse',
  'settings.codexProvider.modelsVisibleSectionTitle',
  'settings.codexProvider.modelsAllSectionTitle',
  'settings.codexProvider.modelsUnavailable',
  'settings.codexProvider.modelsGroupSelectAll',
  'settings.codexProvider.modelsGroupDeselectAll',
  'settings.codexProvider.modelsGroupCount',
  'settings.codexProvider.modelsSource.codex_cli_login',
  'settings.codexProvider.modelsSource.managed_provider',
  'settings.codexProvider.modelsSource.plugin_custom',
  'settings.codexProvider.modelsSource.local_config',
  'settings.codexProvider.requestModeUnavailableBadge',
  'settings.codexProvider.requestModeUnavailableTooltip',
  'settings.codexProvider.fetchModels',
  'settings.codexProvider.fetchModelsLoading',
  'settings.codexProvider.fetchModelsUnsupportedTooltip',
  'settings.codexProvider.fetchModelsResult.added',
  'settings.codexProvider.fetchModelsResult.noNewModels',
  'settings.codexProvider.fetchModelsResult.localRemoteDiscovered',
  'settings.codexProvider.fetchModelsResult.localFallbackRefreshed',
  'settings.codexProvider.fetchModelsResult.removedStaleSuffix',
  'settings.codexProvider.deleteModelConfirmTitle',
  'settings.codexProvider.deleteModelConfirmMessageManaged',
  'settings.codexProvider.deleteModelConfirmMessageReadonly',
] as const;

const REQUIRED_ALL_LOCALE_CODEX_FETCH_KEYS = [
  'settings.codexProvider.dialog.fetchModelsMissingConfigTooltip',
  'settings.codexProvider.copyProvider',
  'settings.codexProvider.fetchModelsResult.draftFetched',
  'settings.codexProvider.fetchModelsResult.added',
  'settings.codexProvider.fetchModelsResult.noNewModels',
  'settings.codexProvider.fetchModelsResult.localRemoteDiscovered',
  'settings.codexProvider.fetchModelsResult.localFallbackRefreshed',
  'settings.codexProvider.fetchModelsResult.removedStaleSuffix',
] as const;

function getValue(locale: Record<string, unknown>, keyPath: string): unknown {
  return keyPath
    .split('.')
    .reduce<unknown>((current, segment) => (
      current && typeof current === 'object' && segment in current
        ? (current as Record<string, unknown>)[segment]
        : undefined
    ), locale);
}

describe('new feature locale coverage', () => {
  it.each(REQUIRED_NEW_FEATURE_KEYS)('defines %s in English and Chinese locales', (keyPath) => {
    expect(getValue(en as Record<string, unknown>, keyPath)).toEqual(expect.any(String));
    expect(getValue(zh as Record<string, unknown>, keyPath)).toEqual(expect.any(String));
  });

  /**
   * 验证 Codex provider 拉模相关文案在所有已支持语言中都完整存在。
   * 该断言专门防止“英文/简中补了新键，但其它 locale 静默回退英文”的回归，
   * 同时覆盖这次修复中被误删的 `fetchModelsResult.*` 旧键。
   */
  it.each(Object.entries(ALL_LOCALES))(
    'defines Codex draft-model-fetch locale keys in %s',
    (_localeName, locale) => {
      for (const keyPath of REQUIRED_ALL_LOCALE_CODEX_FETCH_KEYS) {
        expect(getValue(locale as Record<string, unknown>, keyPath)).toEqual(expect.any(String));
      }
    },
  );

  it('keeps remote collaboration Chinese copy readable', () => {
    const remoteCollab = getValue(zh as Record<string, unknown>, 'settings.remoteCollab') as Record<string, unknown>;
    expect(remoteCollab).toBeTruthy();

    for (const value of Object.values(remoteCollab)) {
      expect(typeof value).toBe('string');
      expect(value as string).not.toContain('???');
      expect(value as string).not.toContain('????');
    }
  });

  it('uses configuration-only copy for provider settings instead of runtime switching copy', () => {
    expect(getValue(en as Record<string, unknown>, 'settings.providersDesc')).toBe(
      'Manage Claude and Codex API provider configurations. Apply runtime provider or model changes from the chat model picker.',
    );
    expect(getValue(zh as Record<string, unknown>, 'settings.providersDesc')).toBe(
      '管理 Claude 和 Codex API 供应商配置。运行时的 provider 和模型切换请在聊天模型选择器中完成。',
    );
    expect(getValue(en as Record<string, unknown>, 'settings.codexProvider.description')).toBe(
      'Manage CC-GUI owned Codex runtime profiles here. Use the chat model picker to apply them per tab.',
    );
    expect(getValue(zh as Record<string, unknown>, 'settings.codexProvider.description')).toBe(
      '在这里管理 CC-GUI 自有的 Codex 运行时配置，按标签页应用时请使用聊天模型选择器。',
    );
  });

  it('drops obsolete settings-page runtime action copy and updates stale runtime provider wording', () => {
    expect(getValue(en as Record<string, unknown>, 'settings.codexProvider.cliLogin.useForRequests')).toBeUndefined();
    expect(getValue(zh as Record<string, unknown>, 'settings.codexProvider.cliLogin.useForRequests')).toBeUndefined();
    expect(getValue(en as Record<string, unknown>, 'settings.codexProvider.cliLogin.currentlyUsingAction')).toBeUndefined();
    expect(getValue(zh as Record<string, unknown>, 'settings.codexProvider.cliLogin.currentlyUsingAction')).toBeUndefined();
    expect(getValue(en as Record<string, unknown>, 'config.runtimeProvider.title')).toBe('Switch Session Provider');
    expect(getValue(zh as Record<string, unknown>, 'config.runtimeProvider.title')).toBe('切换会话供应商');
  });
});
