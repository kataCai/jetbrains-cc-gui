import { describe, expect, it } from 'vitest';
import { normalizeTaskReminderConfig } from './taskReminder';

describe('taskReminder config normalization', () => {
  it('backfills default system channel when persisted config is missing it', () => {
    const normalized = normalizeTaskReminderConfig({
      popup: {
        enabled: true,
        onlyWhenIdeUnfocused: false,
        states: ['waiting_confirm', 'final_error'],
      },
      balloon: {
        enabled: true,
        onlyWhenIdeUnfocused: true,
        states: ['completed', 'recovered', 'final_error'],
      },
      sound: {
        enabled: true,
        onlyWhenIdeUnfocused: true,
        states: ['completed'],
        selectedSound: 'default',
        customSoundPath: '',
      },
    });

    expect(normalized.system).toEqual({
      enabled: false,
      states: ['waiting_confirm', 'final_error', 'completed'],
      onlyWhenIdeUnfocused: true,
    });
  });

  it('filters unsupported system states from persisted config', () => {
    const normalized = normalizeTaskReminderConfig({
      system: {
        enabled: true,
        onlyWhenIdeUnfocused: true,
        states: ['retrying', 'completed', 'final_error'],
      },
    });

    expect(normalized.system.states).toEqual(['completed', 'final_error']);
  });

  it('filters unsupported popup states from persisted config', () => {
    const normalized = normalizeTaskReminderConfig({
      popup: {
        enabled: true,
        onlyWhenIdeUnfocused: false,
        states: ['completed', 'final_error'],
      },
    });

    expect(normalized.popup.states).toEqual(['final_error']);
  });

  it('falls back to default popup states when persisted popup states are all unsupported', () => {
    const normalized = normalizeTaskReminderConfig({
      popup: {
        enabled: true,
        onlyWhenIdeUnfocused: false,
        states: ['completed'],
      },
    });

    expect(normalized.popup.states).toEqual(['waiting_confirm', 'final_error']);
  });

  it('preserves recovery policy overrides and backfills defaults', () => {
    const normalized = normalizeTaskReminderConfig({
      recoveryPolicy: {
        enabled: false,
        retryTransientErrors: false,
        maxAttempts: 4,
      },
    });

    expect(normalized.recoveryPolicy).toEqual({
      enabled: false,
      recoverCompletedOnParseNoise: true,
      retryTransientErrors: false,
      maxAttempts: 4,
      initialDelayMs: 1200,
    });
  });
});
