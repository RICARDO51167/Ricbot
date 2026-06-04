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

    <div class="language-switch" role="group" aria-label="Language switch">
      <button
        type="button"
        :class="{ active: currentLocale === 'zh' }"
        :aria-pressed="currentLocale === 'zh'"
        @click="setLocale('zh')"
      >
        {{ t('top.language.zh') }}
      </button>
      <button
        type="button"
        :class="{ active: currentLocale === 'en' }"
        :aria-pressed="currentLocale === 'en'"
        @click="setLocale('en')"
      >
        {{ t('top.language.en') }}
      </button>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';

import { useSessionStore } from '@/stores/sessionStore';
import { useLocaleStore } from '@/stores/localeStore';
import { NOT_CONFIGURED, useRuntimeStore } from '@/stores/runtimeStore';

const sessionStore = useSessionStore();
const { currentSession: session } = storeToRefs(sessionStore);
const runtimeStore = useRuntimeStore();
const localeStore = useLocaleStore();
const { currentLocale } = storeToRefs(localeStore);
const { setLocale, t } = localeStore;

const isBackendMode = computed(() => runtimeStore.isBackendConnected && !sessionStore.backendUnavailable);

function displayRuntimeValue(value: string) {
  return value === NOT_CONFIGURED ? t('top.notConfigured') : value;
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
