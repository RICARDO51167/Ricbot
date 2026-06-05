<template>
  <header class="runtime-header">
    <div>
      <h1>{{ t(currentRoute.labelKey) }}</h1>
      <p>{{ modeLabel }}</p>
    </div>
    <div class="runtime-header-metrics">
      <div class="run-metric">
        <span>{{ t('common.status') }}</span>
        <strong>{{ modeLabel }}</strong>
      </div>
      <div class="run-metric">
        <span>{{ t('common.provider') }}</span>
        <strong>{{ displayRuntimeValue(runtimeStore.displayProvider) }}</strong>
      </div>
      <div class="run-metric">
        <span>{{ t('common.model') }}</span>
        <strong>{{ displayRuntimeValue(runtimeStore.displayModel) }}</strong>
      </div>
      <div class="run-metric wide">
        <span>{{ t('common.workspace') }}</span>
        <strong>{{ runtimeStore.runtime.workspace || sessionStore.currentSession?.workspace || 'n/a' }}</strong>
      </div>
      <el-select
        data-test="runtime-language-select"
        class="runtime-language-select"
        size="small"
        :model-value="currentLocale"
        :aria-label="t('common.language')"
        @change="selectLocale"
      >
        <el-option
          v-for="locale in locales"
          :key="locale.code"
          :label="locale.nativeLabel"
          :value="locale.code"
        />
      </el-select>
      <span class="language-current">{{ currentConfig.nativeLabel }}</span>
      <el-tag :type="streamTag">{{ streamLabel }}</el-tag>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';

import { currentRoute } from '@/router';
import { type LocaleCode, useLocaleStore } from '@/stores/localeStore';
import { NOT_CONFIGURED, useRuntimeStore } from '@/stores/runtimeStore';
import { useSessionStore } from '@/stores/sessionStore';

const runtimeStore = useRuntimeStore();
const sessionStore = useSessionStore();
const localeStore = useLocaleStore();
const { currentConfig, currentLocale } = storeToRefs(localeStore);
const { locales, setLocale, t } = localeStore;

const modeLabel = computed(() => runtimeStore.isBackendConnected ? t('common.backendConnected') : t('common.mockPreview'));

const streamLabel = computed(() => {
  if (sessionStore.streamStatus === 'live') {
    return t('top.streamLive');
  }
  if (sessionStore.streamStatus === 'fallback_polling' || sessionStore.pollingStatus === 'polling') {
    return t('top.polling');
  }
  if (sessionStore.streamStatus === 'connecting') {
    return t('top.connecting');
  }
  return modeLabel.value;
});

const streamTag = computed(() => {
  if (sessionStore.streamStatus === 'live' || runtimeStore.isBackendConnected) {
    return 'success';
  }
  if (sessionStore.streamStatus === 'connecting' || sessionStore.streamStatus === 'fallback_polling') {
    return 'warning';
  }
  return 'info';
});

function displayRuntimeValue(value: string) {
  return value === NOT_CONFIGURED ? t('common.notConfigured') : value;
}

function selectLocale(value: string) {
  setLocale(value as LocaleCode);
}
</script>
