<template>
  <header class="top-status-bar">
    <div class="brand-block">
      <div class="brand-mark">R</div>
      <div>
        <h1>Ricbot Agent Console</h1>
        <p>{{ t('brand.subtitle') }}</p>
      </div>
    </div>

    <div v-if="session" class="run-strip">
      <el-tag :type="isBackendMode ? 'success' : 'info'" effect="dark">
        {{ isBackendMode ? t('top.backendConnected') : t('top.mockPreview') }}
      </el-tag>
      <el-tag :type="statusType">{{ session.status }}</el-tag>
      <div class="run-metric">
        <span>{{ t('top.session') }}</span>
        <strong>{{ session.title }}</strong>
      </div>
      <div class="run-metric wide">
        <span>{{ t('top.workspace') }}</span>
        <strong>{{ runtimeStore.runtime.workspace || session.workspace }}</strong>
      </div>
      <div class="run-metric">
        <span>{{ t('top.provider') }}</span>
        <strong>{{ displayRuntimeValue(runtimeStore.displayProvider) }}</strong>
      </div>
      <div class="run-metric">
        <span>{{ t('top.model') }}</span>
        <strong>{{ displayRuntimeValue(runtimeStore.displayModel) }}</strong>
      </div>
      <div class="run-metric">
        <span>{{ t('top.trace') }}</span>
        <strong>{{ session.traceId }}</strong>
      </div>
      <div class="compact-metrics">
        <el-tag type="info">{{ session.tokenCount.toLocaleString() }} {{ t('top.tokens') }}</el-tag>
        <el-tag>{{ session.toolCount }} {{ t('top.tools') }}</el-tag>
        <el-tag :type="session.approvalPendingCount > 0 ? 'warning' : 'success'">
          {{ session.approvalPendingCount }} {{ t('top.approvals') }}
        </el-tag>
      </div>
    </div>

    <el-select
      data-test="top-language-select"
      class="language-select"
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
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';

import { useSessionStore } from '@/stores/sessionStore';
import { type LocaleCode, useLocaleStore } from '@/stores/localeStore';
import { NOT_CONFIGURED, useRuntimeStore } from '@/stores/runtimeStore';

const sessionStore = useSessionStore();
const { currentSession: session } = storeToRefs(sessionStore);
const runtimeStore = useRuntimeStore();
const localeStore = useLocaleStore();
const { currentConfig, currentLocale } = storeToRefs(localeStore);
const { locales, setLocale, t } = localeStore;

const isBackendMode = computed(() => runtimeStore.isBackendConnected && !sessionStore.backendUnavailable);

function displayRuntimeValue(value: string) {
  return value === NOT_CONFIGURED ? t('top.notConfigured') : value;
}

function selectLocale(value: string) {
  setLocale(value as LocaleCode);
}

const statusType = computed(() => {
  if (!session.value) {
    return 'info';
  }
  if (session.value.status === 'PASS') {
    return 'success';
  }
  if (session.value.status === 'WAITING_APPROVAL') {
    return 'warning';
  }
  if (session.value.status === 'FAILED') {
    return 'danger';
  }
  return 'primary';
});
</script>
