import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

import { useLocaleStore } from './localeStore';

describe('locale store', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
    Object.defineProperty(window.navigator, 'language', {
      configurable: true,
      value: 'fr-FR',
    });
    setActivePinia(createPinia());
  });

  it('defaults to Chinese for daily use', () => {
    const locale = useLocaleStore();

    expect(locale.currentLocale).toBe('zh-CN');
    expect(locale.t('top.session')).toBe('会话');
  });

  it('supports the required world languages with native labels and direction', () => {
    const locale = useLocaleStore();

    expect(locale.locales.map((item) => item.code)).toEqual(['zh-CN', 'en-US', 'es-ES', 'hi-IN', 'ar-SA']);
    expect(locale.locales.map((item) => item.nativeLabel)).toContain('Español');
    expect(locale.locales.map((item) => item.nativeLabel)).toContain('हिन्दी');
    expect(locale.locales.find((item) => item.code === 'ar-SA')?.direction).toBe('rtl');
  });

  it('persists language changes', () => {
    const locale = useLocaleStore();

    locale.setLocale('en-US');

    expect(locale.currentLocale).toBe('en-US');
    expect(window.localStorage.getItem('ricbot_console_lang')).toBe('en-US');
    expect(locale.t('top.session')).toBe('Session');
  });

  it('uses browser language when storage is empty and supported', () => {
    Object.defineProperty(window.navigator, 'language', {
      configurable: true,
      value: 'es-ES',
    });

    const locale = useLocaleStore();

    expect(locale.currentLocale).toBe('es-ES');
    expect(locale.t('nav.settings')).toBe('Configuración');
  });

  it('applies document language and rtl direction', () => {
    const locale = useLocaleStore();

    locale.setLocale('ar-SA');

    expect(locale.direction).toBe('rtl');
    expect(document.documentElement.lang).toBe('ar-SA');
    expect(document.documentElement.dir).toBe('rtl');
    expect(locale.t('nav.dashboard')).toBe('لوحة المعلومات');
  });

  it('falls back to Chinese when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });

    const locale = useLocaleStore();

    expect(locale.currentLocale).toBe('zh-CN');
  });
});
