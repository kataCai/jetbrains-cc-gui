import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ProviderModelIcon } from './ProviderModelIcon';

describe('ProviderModelIcon', () => {
  it('renders the Xiaomi MiMo icon for MiMo model IDs on Claude-compatible providers', () => {
    const { container } = render(
      <ProviderModelIcon providerId="claude" modelId="mimo-v2.5-pro" colored />,
    );

    // 当前 Xiaomi/MiMo 使用兼容性文本徽标回退实现，稳定契约是可识别的 img 角色与 aria-label，
    // 而不是第三方 SVG 组件才会提供的 <title> 结构。
    const icon = container.querySelector('[aria-label="XiaomiMiMo"]');
    expect(icon).toBeTruthy();
    expect(icon?.getAttribute('role')).toBe('img');
    expect(icon?.textContent).toContain('Mi');
  });
});
