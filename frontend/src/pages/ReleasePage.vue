<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>Eval Matrix / Release Report</h2>
        <p>Read-only release evidence. Evaluation execution remains an explicit external action.</p>
      </div>
      <el-button @click="load">Refresh</el-button>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <div v-else class="settings-grid">
      <article>
        <h3>Eval Matrix</h3>
        <JsonViewer :value="evals" />
      </article>
      <article>
        <h3>Release Check</h3>
        <JsonViewer :value="release" />
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';

import { getJson } from '@/api/client';
import JsonViewer from '@/components/inspector/JsonViewer.vue';

const evals = ref<unknown>({ loading: true });
const release = ref<unknown>({ loading: true });
const error = ref('');

async function load() {
  error.value = '';
  try {
    [evals.value, release.value] = await Promise.all([
      getJson<unknown>('/console/api/evals'),
      getJson<unknown>('/console/api/release-check'),
    ]);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause);
  }
}

onMounted(load);
</script>
