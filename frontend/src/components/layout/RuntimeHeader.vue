<template>
  <header class="runtime-header">
    <div>
      <h1>{{ currentRoute.label }}</h1>
      <p>{{ runtimeStore.modeLabel }}</p>
    </div>
    <div class="runtime-header-metrics">
      <div class="run-metric">
        <span>Status</span>
        <strong>{{ runtimeStore.modeLabel }}</strong>
      </div>
      <div class="run-metric">
        <span>Provider</span>
        <strong>{{ runtimeStore.displayProvider }}</strong>
      </div>
      <div class="run-metric">
        <span>Model</span>
        <strong>{{ runtimeStore.displayModel }}</strong>
      </div>
      <div class="run-metric wide">
        <span>Workspace</span>
        <strong>{{ runtimeStore.runtime.workspace || sessionStore.currentSession?.workspace || 'n/a' }}</strong>
      </div>
      <el-tag :type="streamTag">{{ streamLabel }}</el-tag>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import { currentRoute } from '@/router';
import { useRuntimeStore } from '@/stores/runtimeStore';
import { useSessionStore } from '@/stores/sessionStore';

const runtimeStore = useRuntimeStore();
const sessionStore = useSessionStore();

const streamLabel = computed(() => {
  if (sessionStore.streamStatus === 'live') {
    return 'Stream Live';
  }
  if (sessionStore.streamStatus === 'fallback_polling' || sessionStore.pollingStatus === 'polling') {
    return 'Polling';
  }
  if (sessionStore.streamStatus === 'connecting') {
    return 'Connecting';
  }
  return runtimeStore.isBackendConnected ? 'Backend Connected' : 'Mock Preview';
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
</script>
