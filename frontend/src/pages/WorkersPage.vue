<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>Worker / Mailbox / Join / Handoff</h2>
        <p>Read-only projection of durable Team and Worker collaboration facts.</p>
      </div>
      <el-button @click="load">Refresh</el-button>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <JsonViewer v-else :value="projection" />
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { getJson } from '@/api/client';
import JsonViewer from '@/components/inspector/JsonViewer.vue';

const projection = ref<unknown>({ loading: true });
const error = ref('');

async function load() {
  error.value = '';
  try {
    projection.value = await getJson<unknown>('/console/api/team-reports');
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause);
  }
}

onMounted(load);
</script>
