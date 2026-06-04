<template>
  <div class="console-layout">
    <MainNavSidebar />
    <section class="console-main">
      <RuntimeHeader />
      <div v-if="showBackendFallbackBanner" class="backend-banner">
        Backend unavailable, using mock data.
      </div>
      <main class="console-page-scroll">
        <component :is="currentRoute.component" />
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';

import { currentRoute } from '@/router';
import { useRuntimeStore } from '@/stores/runtimeStore';
import { useSessionStore } from '@/stores/sessionStore';
import MainNavSidebar from './MainNavSidebar.vue';
import RuntimeHeader from './RuntimeHeader.vue';

const runtimeStore = useRuntimeStore();
const sessionStore = useSessionStore();
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
  sessionStore.stopReplay();
});
</script>
