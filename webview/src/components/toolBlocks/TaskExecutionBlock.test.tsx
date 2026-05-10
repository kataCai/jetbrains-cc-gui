import { fireEvent, render } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import TaskExecutionBlock from './TaskExecutionBlock';

vi.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string) => key,
  }),
}));

describe('TaskExecutionBlock', () => {
  it('keeps the task header expandable without rendering a chevron icon', () => {
    const { container } = render(
      <TaskExecutionBlock
        name="Task"
        input={{
          description: 'Inspect render path',
          subagent_type: 'Explore',
        }}
      />,
    );

    expect(container.querySelector('.task-chevron')).toBeNull();

    fireEvent.click(container.querySelector('.task-header') as HTMLElement);

    expect(container.querySelector('.task-details')).toBeTruthy();
  });
});
