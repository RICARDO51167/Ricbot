import { beforeEach, describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';

import { useLocaleStore } from '@/stores/localeStore';
import TopStatusBar from './TopStatusBar.vue';

describe('TopStatusBar language switch', () => {
  beforeEach(() => {
    window.localStorage.clear();
    Object.defineProperty(window.navigator, 'language', {
      configurable: true,
      value: 'fr-FR',
    });
    setActivePinia(createPinia());
  });

  it('renders Chinese by default and reacts to the shared language selector state', async () => {
    const wrapper = mount(TopStatusBar, {
      global: {
        plugins: [ElementPlus],
      },
    });

    expect(wrapper.text()).toContain('会话');
    expect(wrapper.text()).toContain('工作区');
    expect(wrapper.find('[data-test="top-language-select"]').exists()).toBe(true);

    useLocaleStore().setLocale('en-US');
    await wrapper.vm.$nextTick();

    expect(wrapper.text()).toContain('Session');
    expect(wrapper.text()).toContain('Workspace');
    expect(window.localStorage.getItem('ricbot_console_lang')).toBe('en-US');
  });
});
