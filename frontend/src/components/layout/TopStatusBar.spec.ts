import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';

import TopStatusBar from './TopStatusBar.vue';

describe('TopStatusBar language switch', () => {
  beforeEach(() => {
    window.localStorage.clear();
    setActivePinia(createPinia());
  });

  it('renders Chinese by default and switches to English from the top-right buttons', async () => {
    const wrapper = mount(TopStatusBar, {
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('会话');
    expect(wrapper.text()).toContain('工作区');

    await wrapper.get('button[aria-pressed="false"]').trigger('click');

    expect(wrapper.text()).toContain('Session');
    expect(wrapper.text()).toContain('Workspace');
    expect(window.localStorage.getItem('ricbot_console_lang')).toBe('en');
  });
});
