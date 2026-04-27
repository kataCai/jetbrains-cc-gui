import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RoutingPolicyPanel from './RoutingPolicyPanel';
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
  it('edits routing policy and submits normalized values', () => {
    const onSave = vi.fn();

    render(
      <RoutingPolicyPanel
        providerOptions={providerOptions}
        interactiveProviderId="telegram"
        notifyProviderIds={['telegram']}
        onSave={onSave}
      />
    );

    fireEvent.click(screen.getByLabelText('Interactive provider: Gotify + Web'));
    fireEvent.click(screen.getByLabelText('Notify provider: Gotify + Web'));
    fireEvent.click(screen.getByRole('button', { name: 'Save routing policy' }));

    expect(onSave).toHaveBeenCalledWith({
      interactiveProviderId: 'gotify_web',
      notifyProviderIds: ['telegram', 'gotify_web'],
    });
  });
});
