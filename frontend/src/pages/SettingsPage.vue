<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>{{ t('nav.settings') }}</h2>
        <p>{{ t('page.settings.description') }}</p>
      </div>
      <el-tag :type="runtimeStore.runtime.modelConfigured ? 'success' : 'warning'">
        {{ runtimeStore.runtime.modelConfigured ? t('page.settings.configured') : t('page.settings.notConfigured') }}
      </el-tag>
    </div>
    <div class="settings-grid">
      <div><span>{{ t('common.mode') }}</span><strong>{{ modeLabel }}</strong></div>
      <div><span>{{ t('common.provider') }}</span><strong>{{ displayRuntimeValue(runtimeStore.displayProvider) }}</strong></div>
      <div><span>{{ t('common.model') }}</span><strong>{{ displayRuntimeValue(runtimeStore.displayModel) }}</strong></div>
      <div><span>{{ t('common.workspace') }}</span><strong>{{ runtimeStore.runtime.workspace }}</strong></div>
      <div><span>{{ t('common.version') }}</span><strong>{{ runtimeStore.runtime.version }}</strong></div>
      <div><span>{{ t('common.readonly') }}</span><strong>{{ String(runtimeStore.runtime.readonly) }}</strong></div>
    </div>
    <section class="settings-section">
      <div>
        <h3>{{ t('page.settings.languageTitle') }}</h3>
        <p>{{ t('page.settings.languageDescription') }}</p>
      </div>
      <el-select
        data-test="settings-language-select"
        class="settings-language-select"
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
    </section>
    <el-alert
      v-if="!runtimeStore.runtime.modelConfigured"
      type="warning"
      :title="t('page.settings.modelGuide')"
      :closable="false"
    />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { storeToRefs } from 'pinia';

import { type LocaleCode, useLocaleStore } from '@/stores/localeStore';
import { NOT_CONFIGURED, useRuntimeStore } from '@/stores/runtimeStore';

const runtimeStore = useRuntimeStore();
const localeStore = useLocaleStore();
const { currentConfig, currentLocale } = storeToRefs(localeStore);
const { locales, setLocale, t } = localeStore;

const modeLabel = computed(() => runtimeStore.isBackendConnected ? t('common.backendConnected') : t('common.mockPreview'));

onMounted(() => {
  void runtimeStore.loadRuntime();
});

function displayRuntimeValue(value: string) {
  return value === NOT_CONFIGURED ? t('common.notConfigured') : value;
}

function selectLocale(value: string) {
  setLocale(value as LocaleCode);
}
</script>
