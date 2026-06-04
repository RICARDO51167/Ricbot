<template>
  <section class="dashboard-panel">
    <div class="history-head">
      <h3>Dashboard</h3>
      <el-button size="small" :loading="loading" @click="load">Refresh</el-button>
    </div>
    <div class="history-filters">
      <button type="button" class="filter-chip" :class="{ active: scope === 'current' }" @click="setScope('current')">
        当前 session
      </button>
      <button type="button" class="filter-chip" :class="{ active: scope === 'all' }" @click="setScope('all')">
        全部 session
      </button>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <div v-else class="dashboard-grid">
      <div class="metric-card">
        <span>Runs</span>
        <strong>{{ metrics?.runs.total ?? 0 }}</strong>
        <em>{{ percent(metrics?.runs.successRate) }} success</em>
      </div>
      <div class="metric-card">
        <span>Avg Duration</span>
        <strong>{{ duration(metrics?.runs.avgDurationMs ?? 0) }}</strong>
        <em>failed {{ metrics?.runs.failed ?? 0 }} / cancelled {{ metrics?.runs.cancelled ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>Events</span>
        <strong>{{ metrics?.events.total ?? 0 }}</strong>
        <em>errors {{ metrics?.events.error ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>Tools</span>
        <strong>{{ metrics?.tools.totalCalls ?? 0 }}</strong>
        <em>tool errors {{ metrics?.tools.errorCount ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>Approvals</span>
        <strong>{{ metrics?.approvals.total ?? 0 }}</strong>
        <em>execute {{ metrics?.approvals.approveExecute ?? 0 }} / reject {{ metrics?.approvals.reject ?? 0 }}</em>
      </div>
      <div class="metric-card">
        <span>ChangeSets</span>
        <strong>{{ metrics?.changesets.total ?? 0 }}</strong>
        <em>file diffs {{ metrics?.changesets.fileDiffViews ?? 0 }}</em>
      </div>
    </div>
    <div class="dashboard-section">
      <h4>Top Tools</h4>
      <div v-if="(metrics?.tools.topTools ?? []).length === 0" class="history-empty">暂无工具事件</div>
      <div v-for="tool in metrics?.tools.topTools ?? []" :key="tool.name" class="dashboard-row">
        <strong>{{ tool.name }}</strong>
        <span>{{ tool.count }} calls · {{ tool.errorCount }} errors</span>
      </div>
    </div>
    <div class="dashboard-section">
      <h4>Recent Errors</h4>
      <div v-if="(metrics?.recentErrors ?? []).length === 0" class="history-empty">暂无错误</div>
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
      <h4>Active Sessions</h4>
      <div v-if="(metrics?.activeSessions ?? []).length === 0" class="history-empty">暂无 session 活动</div>
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
import { useHistoryStore } from '@/stores/historyStore';
import { useSessionStore } from '@/stores/sessionStore';

type DashboardScope = 'current' | 'all';

const sessionStore = useSessionStore();
const historyStore = useHistoryStore();
const { currentSessionId, selectedSessionDataSource } = storeToRefs(sessionStore);
const scope = ref<DashboardScope>('current');
const metrics = ref<ConsoleMetricsSummary | null>(null);
const loading = ref(false);
const error = ref('');

onMounted(load);
watch([currentSessionId, selectedSessionDataSource, scope], load);

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
      sessionId: scope.value === 'current' ? currentSessionId.value : undefined,
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
  void historyStore.selectSearchEvent({
    id: event.id,
    sessionId: event.sessionId,
    runId: event.runId,
    name: event.name,
    summary: event.summary,
    time: event.time,
    category: 'error',
    status: 'ERROR',
  });
}

function selectSession(sessionId: string) {
  void sessionStore.selectSession(sessionId);
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
</script>
