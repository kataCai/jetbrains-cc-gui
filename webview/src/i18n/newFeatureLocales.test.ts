import { describe, expect, it } from 'vitest';
import en from './locales/en.json';
import zh from './locales/zh.json';

const REQUIRED_NEW_FEATURE_KEYS = [
  'settings.basic.taskReminder.label',
  'settings.basic.taskReminder.hint',
  'settings.basic.taskReminder.popup',
  'settings.basic.taskReminder.balloon',
  'settings.basic.taskReminder.sound',
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
});
