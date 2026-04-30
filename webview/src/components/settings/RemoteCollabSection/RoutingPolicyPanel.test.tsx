import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RoutingPolicyPanel from './RoutingPolicyPanel';
import type { RemoteCollabProviderOption } from '../hooks/useRemoteCollabSettings';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, options?: Record<string, string>) => {
      const template = options?.defaultValue ?? _key;
      return typeof template === 'string'
        ? template.replace(/\{\{(\w+)\}\}/g, (_match, name: string) => options?.[name] ?? '')
        : template;
    },
  }),
}));

const providerOptions: RemoteCollabProviderOption[] = [
  {
    providerId: 'telegram',
    displayName: 'Telegram',
    description: 'Inline chat collaboration',
    capabilities: ['TASK_EVENT_PUSH', 'INLINE_ACTION_CALLBACK'],
    registered: true,
    enabled: true,
    connectionStatus: 'connected',
    config: {},
  },
  {
    providerId: 'gotify_web',
    displayName: 'Gotify + Web',
    description: 'Workspace based collaboration',
    capabilities: ['TASK_EVENT_PUSH', 'RESULT_POLLING'],
    registered: false,
    enabled: false,
    connectionStatus: 'disabled',
    config: {},
  },
];

describe('RoutingPolicyPanel', () => {
  it('starts from summary mode, expands editor, and submits normalized values', () => {
    const onSave = vi.fn();

    render(
      <RoutingPolicyPanel
        providerOptions={providerOptions}
        interactiveProviderId="telegram"
        notifyProviderIds={['telegram', 'gotify_web']}
        onSave={onSave}
      />
    );

    expect(screen.getByText('Edit which provider handles phone interaction and which channels only receive notifications.')).toBeTruthy();
    expect(screen.getAllByText('Interactive provider').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Notify providers').length).toBeGreaterThan(0);
    expect(screen.getByText('Telegram, Gotify + Web')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Edit routing' })).toBeTruthy();
    expect(screen.queryByText('Save routing policy')).toBeNull();
    expect(screen.queryByLabelText('Interactive provider: Gotify + Web')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Edit routing' }));
    expect(screen.getAllByText('Interactive provider').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Notify providers').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByLabelText('Interactive provider: Gotify + Web'));
    fireEvent.click(screen.getByRole('button', { name: 'Save routing policy' }));

    expect(onSave).toHaveBeenCalledWith({
      interactiveProviderId: 'gotify_web',
      notifyProviderIds: ['telegram', 'gotify_web'],
    });
    expect(screen.queryByLabelText('Interactive provider: Gotify + Web')).toBeNull();
  });
});
