import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ProviderList from './ProviderList';
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
    currentInstanceReceivesUpdates: true,
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

describe('ProviderList', () => {
  it('renders provider cards, route roles, capabilities, and open action', () => {
    const onOpenProvider = vi.fn();

    render(
      <ProviderList
        providerOptions={providerOptions}
        interactiveProviderId="telegram"
        notifyProviderIds={['telegram', 'gotify_web']}
        onOpenProvider={onOpenProvider}
      />
    );

    expect(screen.getAllByText('Telegram').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Gotify + Web').length).toBeGreaterThan(0);
    expect(screen.getByText('connected')).toBeTruthy();
    expect(screen.getAllByText('disabled').length).toBeGreaterThan(0);
    expect(screen.getByText('registered')).toBeTruthy();
    expect(screen.getByText('unregistered')).toBeTruthy();
    expect(screen.getByText('Interactive route')).toBeTruthy();
    expect(screen.getAllByText('Notify route')).toHaveLength(2);
    expect(screen.getByText('INLINE_ACTION_CALLBACK')).toBeTruthy();
    expect(screen.getByText('RESULT_POLLING')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Open Telegram settings' }));

    expect(onOpenProvider).toHaveBeenCalledWith('telegram');
  });
});
