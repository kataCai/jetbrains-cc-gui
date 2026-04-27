import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ProviderList from './ProviderList';
import type { RemoteCollabProviderOption } from '../hooks/useRemoteCollabSettings';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (_key: string, options?: Record<string, string>) => options?.defaultValue ?? _key,
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
  it('renders provider cards, statuses, and capabilities', () => {
    render(<ProviderList providerOptions={providerOptions} />);

    expect(screen.getAllByText('Telegram').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Gotify + Web').length).toBeGreaterThan(0);
    expect(screen.getByText('connected')).toBeTruthy();
    expect(screen.getAllByText('disabled').length).toBeGreaterThan(0);
    expect(screen.getByText('registered')).toBeTruthy();
    expect(screen.getByText('unregistered')).toBeTruthy();
    expect(screen.getByText('INLINE_ACTION_CALLBACK')).toBeTruthy();
    expect(screen.getByText('RESULT_POLLING')).toBeTruthy();
  });
});
