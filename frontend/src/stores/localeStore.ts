import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  localeConfigs,
  messages,
  type LocaleCode,
  type LocaleConfig,
  type MessageKey,
} from '@/i18n/messages';

const STORAGE_KEY = 'ricbot_console_lang';
const DEFAULT_LOCALE: LocaleCode = 'zh-CN';

const supportedCodes = new Set<LocaleCode>(localeConfigs.map((locale) => locale.code));

function normalizeLocale(value: unknown): LocaleCode | null {
  if (value === 'zh') {
    return 'zh-CN';
  }
  if (value === 'en') {
    return 'en-US';
  }
  if (typeof value !== 'string') {
    return null;
  }
  if (supportedCodes.has(value as LocaleCode)) {
    return value as LocaleCode;
  }
  const lower = value.toLowerCase();
  const languageMatch = localeConfigs.find((locale) => locale.code.toLowerCase().startsWith(`${lower.split('-')[0]}-`));
  return languageMatch?.code ?? null;
}

function readStoredLocale(): LocaleCode | null {
  try {
    return normalizeLocale(window.localStorage.getItem(STORAGE_KEY));
  } catch {
    return null;
  }
}

function readBrowserLocale(): LocaleCode | null {
  if (typeof window === 'undefined') {
    return null;
  }
  return normalizeLocale(window.navigator.language);
}

function initialLocale(): LocaleCode {
  return readStoredLocale() ?? readBrowserLocale() ?? DEFAULT_LOCALE;
}

function writeStoredLocale(locale: LocaleCode) {
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // Storage can be unavailable in privacy modes; UI state still updates in memory.
  }
}

function localeConfig(locale: LocaleCode): LocaleConfig {
  return localeConfigs.find((config) => config.code === locale) ?? localeConfigs[0];
}

function applyDocumentLocale(locale: LocaleCode) {
  if (typeof document === 'undefined') {
    return;
  }
  const config = localeConfig(locale);
  document.documentElement.lang = config.code;
  document.documentElement.dir = config.direction;
}

export const useLocaleStore = defineStore('locale', () => {
  const currentLocale = ref<LocaleCode>(initialLocale());
  applyDocumentLocale(currentLocale.value);

  const currentConfig = computed(() => localeConfig(currentLocale.value));
  const direction = computed(() => currentConfig.value.direction);
  const isChinese = computed(() => currentLocale.value === 'zh-CN');
  const isRtl = computed(() => direction.value === 'rtl');

  function setLocale(locale: LocaleCode) {
    currentLocale.value = locale;
    writeStoredLocale(locale);
    applyDocumentLocale(locale);
  }

  function t(key: MessageKey) {
    return messages[currentLocale.value][key] ?? messages[DEFAULT_LOCALE][key] ?? key;
  }

  return {
    currentLocale,
    currentConfig,
    direction,
    isChinese,
    isRtl,
    locales: localeConfigs,
    setLocale,
    t,
  };
});

export type { LocaleCode, MessageKey };
