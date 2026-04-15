import { describe, expect, it } from 'vitest';
import { normalizeTaskReminderConfig } from './taskReminder';

describe('taskReminder config normalization', () => {
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
});
