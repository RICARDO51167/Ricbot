<template>
  <section class="dashboard-panel">
    <div class="history-head">
      <h3>{{ t('dashboard.title') }}</h3>
      <el-button size="small" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
    </div>
    <div class="history-filters">
      <button type="button" class="filter-chip" :class="{ active: scope === 'current' }" @click="setScope('current')">
        {{ t('dashboard.currentSession') }}
      </button>
      <button type="button" class="filter-chip" :class="{ active: scope === 'all' }" @click="setScope('all')">
        {{ t('dashboard.allSessions') }}
      </button>
    </div>
    <div class="event-search-controls">
      <el-input v-model="since" size="small" placeholder="since" clearable @change="load" @keyup.enter="load" />
      <el-input v-model="until" size="small" placeholder="until" clearable @change="load" @keyup.enter="load" />
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <div v-else class="dashboard-grid">
      <div class="metric-card">
        <span>{{ t('dashboard.runs') }}</span>
        <strong>{{ metrics?.runs.total ?? 0 }}</strong>
        <em>{{ percent(metrics?.runs.successRate) }} {{ t('dashboard.success') }}</em>
      </div>
      <div class="metric-card">
        <span>{{ t('dashboard.avgDuration') }}</span>
        <strong>{{ duration(metrics?.runs.avgDurationMs ?? 0) }}</strong>
        <em>failed {{ metrics?.runs.failed ?? 0 }} / cancelled {{ metrics?.runs.cancelled ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>{{ t('dashboard.events') }}</span>
        <strong>{{ metrics?.events.total ?? 0 }}</strong>
        <em>errors {{ metrics?.events.error ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>{{ t('dashboard.tools') }}</span>
        <strong>{{ metrics?.tools.totalCalls ?? 0 }}</strong>
        <em>tool errors {{ metrics?.tools.errorCount ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>{{ t('dashboard.approvals') }}</span>
        <strong>{{ metrics?.approvals.total ?? 0 }}</strong>
        <em>execute {{ metrics?.approvals.approveExecute ?? 0 }} / reject {{ metrics?.approvals.reject ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>{{ t('dashboard.changesets') }}</span>
        <strong>{{ metrics?.changesets.total ?? 0 }}</strong>
        <em>file diffs {{ metrics?.changesets.fileDiffViews ?? 0 }}</em>
      </div>
    </div>
    <div class="dashboard-section">
      <h4>{{ t('dashboard.topTools') }}</h4>
      <div v-if="(metrics?.tools.topTools ?? []).length === 0" class="history-empty">{{ t('dashboard.noToolEvents') }}</div>
      <div v-for="tool in metrics?.tools.topTools ?? []" :key="tool.name" class="dashboard-row">
        <strong>{{ tool.name }}</strong>
        <span>{{ tool.count }} calls · {{ tool.errorCount }} errors</span>
      </div>
    </div>
    <div class="dashboard-section">
      <h4>{{ t('dashboard.recentErrors') }}</h4>
      <div v-if="(metrics?.recentErrors ?? []).length === 0" class="history-empty">{{ t('dashboard.noErrors') }}</div>
      <button
        v-for="event in metrics?.recentErrors ?? []"
        :key="event.id"
        type="button"
        class="dashboard-row clickable"
        @click="focusError(event)"
      >
        <strong>{{ event.name }}</strong>
        <span>{{ event.sessionId }} · {{ event.summary || event.id }}</span>
      </button>
    </div>
    <div class="dashboard-section">
      <h4>{{ t('dashboard.activeSessions') }}</h4>
      <div v-if="(metrics?.activeSessions ?? []).length === 0" class="history-empty">{{ t('dashboard.noSessionActivity') }}</div>
      <button
        v-for="session in metrics?.activeSessions ?? []"
        :key="session.sessionId"
        type="button"
        class="dashboard-row clickable"
        @click="selectSession(session.sessionId)"
      >
        <strong>{{ session.sessionId }}</strong>
        <span>{{ session.eventCount }} events · {{ session.runCount }} runs</span>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { storeToRefs } from 'pinia';

import { getMetricsSummary, type ConsoleMetricsSummary } from '@/api/consoleApi';
import { currentRoute, navigate, replace } from '@/router';
import { useLocaleStore } from '@/stores/localeStore';
import { useSessionStore } from '@/stores/sessionStore';

type DashboardScope = 'current' | 'all';

const sessionStore = useSessionStore();
const { t } = useLocaleStore();
const { currentSessionId, selectedSessionDataSource } = storeToRefs(sessionStore);
const scope = ref<DashboardScope>('current');
const scopedSessionId = ref('');
const since = ref('');
const until = ref('');
const metrics = ref<ConsoleMetricsSummary | null>(null);
const loading = ref(false);
const error = ref('');
let applyingQuery = false;

onMounted(load);
watch([currentSessionId, selectedSessionDataSource, scope, scopedSessionId, since, until], () => {
  syncDashboardQuery();
  void load();
});
watch(
  () => queryKey(currentRoute.value.query),
  () => applyDashboardQuery(currentRoute.value.query),
  { immediate: true },
);

async function load() {
  if (selectedSessionDataSource.value !== 'backend') {
    metrics.value = null;
    error.value = '';
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    metrics.value = await getMetricsSummary({
      sessionId: scope.value === 'current' ? (scopedSessionId.value || currentSessionId.value) : undefined,
      since: since.value || undefined,
      until: until.value || undefined,
    });
  } catch (err) {
    metrics.value = null;
    error.value = err instanceof Error ? err.message : 'Metrics unavailable';
  } finally {
    loading.value = false;
  }
}

function setScope(next: DashboardScope) {
  scope.value = next;
}

function focusError(event: ConsoleMetricsSummary['recentErrors'][number]) {
  navigate('/console/workbench', {
    sessionId: event.sessionId,
    eventId: event.id,
  });
}

function selectSession(sessionId: string) {
  navigate('/console/workbench', { sessionId });
}

function percent(value?: number) {
  return `${Math.round((value ?? 0) * 100)}%`;
}

function duration(value: number) {
  if (!value) {
    return '0ms';
  }
  if (value < 1000) {
    return `${value}ms`;
  }
  return `${Math.round(value / 100) / 10}s`;
}

function applyDashboardQuery(query: Record<string, string>) {
  if (currentRoute.value.path !== '/console/dashboard') {
    return;
  }
  applyingQuery = true;
  scope.value = query.scope === 'all' ? 'all' : 'current';
  scopedSessionId.value = query.sessionId ?? '';
  since.value = query.since ?? '';
  until.value = query.until ?? '';
  applyingQuery = false;
}

function syncDashboardQuery() {
  if (applyingQuery || currentRoute.value.path !== '/console/dashboard') {
    return;
  }
  const next = {
    scope: scope.value === 'all' ? 'all' : undefined,
    sessionId: scope.value === 'current' ? (scopedSessionId.value || currentSessionId.value || undefined) : undefined,
    since: since.value || undefined,
    until: until.value || undefined,
  };
  if (queryKey(next) !== queryKey(currentRoute.value.query)) {
    replace('/console/dashboard', next);
  }
}

function queryKey(query: Record<string, string | undefined>) {
  return JSON.stringify(Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== '')
    .sort(([left], [right]) => left.localeCompare(right)));
}
</script>
