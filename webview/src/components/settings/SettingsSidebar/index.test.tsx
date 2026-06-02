import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import SettingsSidebar from './index';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('SettingsSidebar', () => {
  it('只渲染一个远程协作入口，避免重复展示相同功能入口', () => {
    render(
      <SettingsSidebar
        currentTab="basic"
        onTabChange={vi.fn()}
        isCollapsed={false}
        onToggleCollapse={vi.fn()}
      />
    );

    expect(screen.getAllByText('settings.remoteCollab.title')).toHaveLength(1);
  });
});
