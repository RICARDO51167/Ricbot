<template>
  <div class="app-shell">
    <TopStatusBar />
    <div v-if="showBackendFallbackBanner" class="backend-banner">
      {{ t('top.backendUnavailable') }}
    </div>
    <main class="workspace-grid">
      <SessionSidebar />
      <ChatTimeline />
      <TraceInspector />
      <ChangeSetPanel />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';

import ChangeSetPanel from '@/components/changes/ChangeSetPanel.vue';
import TraceInspector from '@/components/inspector/TraceInspector.vue';
import ChatTimeline from '@/components/timeline/ChatTimeline.vue';
import { useLocaleStore } from '@/stores/localeStore';
import { useRuntimeStore } from '@/stores/runtimeStore';
import { useSessionStore } from '@/stores/sessionStore';
import SessionSidebar from './SessionSidebar.vue';
import TopStatusBar from './TopStatusBar.vue';

const runtimeStore = useRuntimeStore();
const sessionStore = useSessionStore();
const { t } = useLocaleStore();
const showBackendFallbackBanner = computed(() => runtimeStore.backendUnavailable || sessionStore.backendUnavailable);

onMounted(async () => {
  await Promise.all([
    runtimeStore.loadRuntime(),
    sessionStore.loadFromBackend(),
  ]);
});

onBeforeUnmount(() => {
  sessionStore.stopEventStream();
  sessionStore.stopEventPolling();
});
</script>
