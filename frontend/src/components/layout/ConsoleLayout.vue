<template>
  <div class="console-layout" :lang="currentLocale" :dir="direction" :class="{ 'is-rtl': isRtl }">
    <MainNavSidebar />
    <section class="console-main">
      <RuntimeHeader />
      <div v-if="showBackendFallbackBanner" class="backend-banner">
        {{ t('top.backendUnavailable') }}
      </div>
      <main class="console-page-scroll">
        <component :is="currentRoute.component" />
      </main>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue';
import { storeToRefs } from 'pinia';

import { currentRoute } from '@/router';
import { useLocaleStore } from '@/stores/localeStore';
import { useRuntimeStore } from '@/stores/runtimeStore';
import { useSessionStore } from '@/stores/sessionStore';
import MainNavSidebar from './MainNavSidebar.vue';
import RuntimeHeader from './RuntimeHeader.vue';

const runtimeStore = useRuntimeStore();
const sessionStore = useSessionStore();
const localeStore = useLocaleStore();
const { currentLocale, direction, isRtl } = storeToRefs(localeStore);
const { t } = localeStore;
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
