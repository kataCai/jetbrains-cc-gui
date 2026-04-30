import { describe, expect, it } from 'vitest';
import en from './locales/en.json';
import zh from './locales/zh.json';

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

  it('keeps remote collaboration Chinese copy readable', () => {
    const remoteCollab = getValue(zh as Record<string, unknown>, 'settings.remoteCollab') as Record<string, unknown>;
    expect(remoteCollab).toBeTruthy();

    for (const value of Object.values(remoteCollab)) {
      expect(typeof value).toBe('string');
      expect(value as string).not.toContain('???');
      expect(value as string).not.toContain('????');
    }
  });
});
