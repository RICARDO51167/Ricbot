import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import { useLocaleStore } from './localeStore';

describe('locale store', () => {
  beforeEach(() => {
    window.localStorage.clear();
    setActivePinia(createPinia());
  });

  it('defaults to Chinese for daily use', () => {
    const locale = useLocaleStore();

    expect(locale.currentLocale).toBe('zh');
    expect(locale.t('top.session')).toBe('会话');
  });

  it('persists language changes', () => {
    const locale = useLocaleStore();

    locale.setLocale('en');

    expect(locale.currentLocale).toBe('en');
    expect(window.localStorage.getItem('ricbot_console_lang')).toBe('en');
    expect(locale.t('top.session')).toBe('Session');
  });

  it('falls back to Chinese when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    const locale = useLocaleStore();

    expect(locale.currentLocale).toBe('zh');
  });
});
