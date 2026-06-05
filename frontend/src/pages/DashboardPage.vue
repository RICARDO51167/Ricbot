<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>{{ t('nav.dashboard') }}</h2>
        <p>{{ t('page.dashboard.description') }}</p>
      </div>
    </div>
    <DashboardPanel />
  </section>
</template>

<script setup lang="ts">
import DashboardPanel from '@/components/inspector/DashboardPanel.vue';
import { useLocaleStore } from '@/stores/localeStore';

const { t } = useLocaleStore();
</script>
