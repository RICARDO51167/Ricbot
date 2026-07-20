<template>
  <section class="console-page-card">
    <div class="page-head">
      <div>
        <h2>{{ t('nav.runs') }}</h2>
        <p>{{ t('page.runs.description') }}</p>
      </div>
    </div>
    <div class="runs-page-layout">
      <RunHistory />
      <aside class="run-summary" v-if="selectedRun">
        <h3>{{ selectedRun.runId }}</h3>
        <el-tag :type="selectedRun.status === 'finished' ? 'success' : selectedRun.status === 'failed' ? 'danger' : 'warning'">
          {{ selectedRun.status }}
        </el-tag>
        <p>{{ selectedRun.inputPreview }}</p>
        <dl>
          <div><dt>{{ t('page.runs.session') }}</dt><dd>{{ selectedRun.sessionId }}</dd></div>
          <div><dt>{{ t('page.runs.lastEvent') }}</dt><dd>{{ selectedRun.lastEventName }}</dd></div>
          <div><dt>{{ t('page.runs.tools') }}</dt><dd>{{ selectedRun.toolCallCount }}</dd></div>
          <div><dt>{{ t('page.runs.approvals') }}</dt><dd>{{ selectedRun.approvalCount }}</dd></div>
          <div><dt>{{ t('page.runs.errors') }}</dt><dd>{{ selectedRun.errorCount }}</dd></div>
        </dl>
        <el-button type="primary" @click="openSelectedRun">{{ t('page.runs.openWorkbench') }}</el-button>
      </aside>
      <aside v-else class="run-summary muted">
        <h3>{{ t('page.runs.noRun') }}</h3>
        <p>{{ t('page.runs.noRunDescription') }}</p>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { storeToRefs } from 'pinia';

import RunHistory from '@/components/inspector/RunHistory.vue';
import { currentRoute, navigate, replace } from '@/router';
import { useHistoryStore } from '@/stores/historyStore';
import { useLocaleStore } from '@/stores/localeStore';
import { useSessionStore } from '@/stores/sessionStore';

const historyStore = useHistoryStore();
const sessionStore = useSessionStore();
const { t } = useLocaleStore();
const { runHistory, runStatusFilter, runKeywordFilter, selectedHistoryRunId, runSessionIdFilter } = storeToRefs(historyStore);
let applyingQuery = false;

const selectedRun = computed(() => {
  return runHistory.value.find((run) => run.runId === selectedHistoryRunId.value) ?? null;
});

watch(
  () => queryKey(currentRoute.value.query),
  () => {
    void applyRunsQuery(currentRoute.value.query);
  },
  { immediate: true },
);

watch(
  () => [
    runSessionIdFilter.value,
    runStatusFilter.value,
    runKeywordFilter.value,
    selectedHistoryRunId.value,
  ],
  syncRunsQuery,
);

async function applyRunsQuery(query: Record<string, string>) {
  if (currentRoute.value.path !== '/console/runs') {
    return;
  }
  applyingQuery = true;
  const sessionId = query.sessionId || sessionStore.currentSessionId;
  try {
    await historyStore.loadRunHistory(sessionId, {
      status: normalizeRunStatus(query.status),
      keyword: query.keyword ?? '',
      runId: query.runId ?? '',
    });
  } finally {
    applyingQuery = false;
    syncRunsQuery();
  }
}

function syncRunsQuery() {
  if (applyingQuery || currentRoute.value.path !== '/console/runs') {
    return;
  }
  const next = {
    sessionId: runSessionIdFilter.value || undefined,
    status: runStatusFilter.value === 'all' ? undefined : runStatusFilter.value,
    keyword: runKeywordFilter.value || undefined,
    runId: selectedHistoryRunId.value || undefined,
  };
  if (queryKey(next) !== queryKey(currentRoute.value.query)) {
    replace('/console/runs', next);
  }
}

function openSelectedRun() {
  if (!selectedRun.value) {
    return;
  }
  navigate('/console/workbench', {
    sessionId: selectedRun.value.sessionId,
    runId: selectedRun.value.runId,
  });
}

function normalizeRunStatus(value: string | undefined) {
  return ['queued', 'running', 'finished', 'failed', 'cancelled'].includes(value ?? '')
    ? value as 'queued' | 'running' | 'finished' | 'failed' | 'cancelled'
    : 'all';
}

function queryKey(query: Record<string, string | undefined>) {
  return JSON.stringify(Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== '')
    .sort(([left], [right]) => left.localeCompare(right)));
}
</script>
