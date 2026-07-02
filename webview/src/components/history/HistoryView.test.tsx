import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import type { HistoryData } from '../../types';
import HistoryView from './HistoryView';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: Record<string, unknown>) => {
      const translations: Record<string, string> = {
        'history.totalSessions': `${options?.count} sessions · ${options?.total} messages`,
        'history.messageCount': `${options?.count} messages`,
        'history.selectMode': 'Select',
        'history.exitSelectMode': 'Exit selection',
        'history.selectedSessions': `${options?.count} selected`,
        'history.selectAll': 'Select all',
        'history.clearSelection': 'Clear',
        'history.deleteSelected': 'Delete selected',
        'history.confirmDeleteSelected': 'Confirm Delete',
        'history.deleteSelectedMessage': `Delete ${options?.count} selected sessions?`,
        'history.selectSession': 'Select session',
        'history.selectSessionWithTitle': `Select ${String(options?.title ?? '')}`,
        'history.searchPlaceholder': 'Search session titles...',
        'history.deepSearchTooltip': 'Deep Search',
        'history.favoriteSession': 'Favorite session',
        'history.unfavoriteSession': 'Unfavorite session',
        'common.cancel': 'Cancel',
        'common.delete': 'Delete',
      };
      return translations[key] ?? key;
    },
  }),
}));

vi.mock('../shared/ProviderModelIcon', () => ({
  ProviderModelIcon: () => <span data-testid="provider-icon" />,
}));

vi.mock('../../utils/copyUtils', () => ({
  copyToClipboard: vi.fn(async () => true),
}));

const historyData: HistoryData = {
  success: true,
  total: 10,
  sessions: [
    {
      sessionId: 'session-one',
      title: 'First session',
      messageCount: 4,
      lastTimestamp: new Date().toISOString(),
      provider: 'claude',
    },
    {
      sessionId: 'session-two',
      title: 'Second session',
      messageCount: 6,
      lastTimestamp: new Date().toISOString(),
      provider: 'codex',
    },
  ],
};

describe('HistoryView multi-select', () => {
  it('deletes selected sessions after confirmation without loading them', () => {
    const onLoadSession = vi.fn();
    const onDeleteSession = vi.fn();
    const onDeleteSessions = vi.fn();

    render(
      <HistoryView
        historyData={historyData}
        currentProvider="claude"
        onLoadSession={onLoadSession}
        onDeleteSession={onDeleteSession}
        onDeleteSessions={onDeleteSessions}
        onExportSession={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Select' }));

    fireEvent.click(screen.getByRole('checkbox', { name: 'Select First session' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Second session' }));

    expect(screen.getByText('2 selected')).toBeTruthy();
    expect(onLoadSession).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: 'Delete selected' }));

    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Delete 2 selected sessions?')).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(onDeleteSession).not.toHaveBeenCalled();
    expect(onDeleteSessions).toHaveBeenCalledTimes(1);
    expect(onDeleteSessions).toHaveBeenCalledWith(['session-one', 'session-two']);
    expect(onLoadSession).not.toHaveBeenCalled();
  });
});

describe('HistoryView favorite visibility', () => {
  it('marks favorited session actions for persistent display', () => {
    render(
      <HistoryView
        historyData={{
          ...historyData,
          sessions: [
            {
              ...historyData.sessions![0],
              isFavorited: true,
              favoritedAt: Date.now(),
            },
            historyData.sessions![1],
          ],
        }}
        currentProvider="claude"
        onLoadSession={vi.fn()}
        onDeleteSession={vi.fn()}
        onDeleteSessions={vi.fn()}
        onExportSession={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
      />,
    );

    const favoritedButton = screen.getByRole('button', { name: 'Unfavorite session' });
    const unfavoritedButton = screen.getByRole('button', { name: 'Favorite session' });

    expect(favoritedButton.closest('.history-action-buttons')?.classList.contains('has-favorite')).toBe(true);
    expect(unfavoritedButton.closest('.history-action-buttons')?.classList.contains('has-favorite')).toBe(false);
  });
});

describe('HistoryView logical conversation aggregation', () => {
  it('deduplicates multi-segment sessions by logicalConversationId and uses logical key for load/delete', () => {
    const onLoadSession = vi.fn();
    const onDeleteSession = vi.fn();

    render(
      <HistoryView
        historyData={{
          success: true,
          total: 12,
          sessions: [
            {
              sessionId: 'segment-001',
              logicalConversationId: 'logical-001',
              activeSegmentSessionId: 'segment-002',
              title: 'Continued session',
              messageCount: 5,
              lastTimestamp: '2026-06-29T10:00:00.000Z',
              provider: 'codex',
              runtimeFamily: 'codex',
              segmentCount: 2,
            },
            {
              sessionId: 'segment-002',
              logicalConversationId: 'logical-001',
              activeSegmentSessionId: 'segment-002',
              title: 'Continued session',
              messageCount: 7,
              lastTimestamp: '2026-06-29T11:00:00.000Z',
              provider: 'codex',
              runtimeFamily: 'codex',
              segmentCount: 2,
            },
          ],
        }}
        currentProvider="codex"
        onLoadSession={onLoadSession}
        onDeleteSession={onDeleteSession}
        onDeleteSessions={vi.fn()}
        onExportSession={vi.fn()}
        onToggleFavorite={vi.fn()}
        onUpdateTitle={vi.fn()}
      />,
    );

    expect(screen.getAllByText('Continued session')).toHaveLength(1);
    expect(screen.getByText('7 messages')).toBeTruthy();

    fireEvent.click(screen.getByText('Continued session'));
    expect(onLoadSession).toHaveBeenCalledWith('logical-001', 'codex');

    fireEvent.click(screen.getByRole('button', { name: 'history.deleteSession' }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }));
    expect(onDeleteSession).toHaveBeenCalledWith('logical-001');
  });
});
