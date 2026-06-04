<template>
  <section class="history-panel">
    <div class="history-head">
      <h3>Run History</h3>
      <el-tag size="small">{{ filteredRunHistory.length }}</el-tag>
    </div>
    <div class="history-filters">
      <button
        v-for="status in statuses"
        :key="status.value"
        type="button"
        class="filter-chip"
        :class="{ active: runStatusFilter === status.value }"
        @click="setStatus(status.value)"
      >
        {{ status.label }}
      </button>
    </div>
    <el-alert v-if="runHistoryError" type="error" :title="runHistoryError" :closable="false" />
    <div v-else-if="!runHistoryLoading && filteredRunHistory.length === 0" class="history-empty">
      暂无历史 run
    </div>
    <button
      v-for="run in filteredRunHistory"
      :key="run.runId"
      type="button"
      class="history-row"
      :class="{ selected: selectedHistoryRunId === run.runId, active: activeRunId === run.runId }"
      @click="selectRun(run)"
    >
      <span class="history-row-main">
        <strong>{{ run.inputPreview || run.runId }}</strong>
        <em>{{ run.lastEventName || run.status }}</em>
      </span>
      <span class="history-row-stats">
        <el-tag size="small" :type="tagType(run.status)">{{ run.status }}</el-tag>
        <small>{{ duration(run.durationMs) }}</small>
        <small>T{{ run.toolCallCount }}</small>
        <small>A{{ run.approvalCount }}</small>
        <small>C{{ run.changeSetCount }}</small>
        <small v-if="run.errorCount">E{{ run.errorCount }}</small>
      </span>
    </button>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { storeToRefs } from 'pinia';

import type { ConsoleRunStatus } from '@/api/consoleApi';
import type { ConsoleRunHistoryItem } from '@/api/consoleApi';
import { navigate } from '@/router';
import { useHistoryStore } from '@/stores/historyStore';
import { useSessionStore } from '@/stores/sessionStore';

const props = withDefaults(defineProps<{
  navigateOnSelect?: boolean;
}>(), {
  navigateOnSelect: false,
});

const historyStore = useHistoryStore();
const sessionStore = useSessionStore();
const { filteredRunHistory, runHistoryLoading, runHistoryError, selectedHistoryRunId, runStatusFilter } = storeToRefs(historyStore);
const { currentSessionId, selectedSessionDataSource, activeRunId } = storeToRefs(sessionStore);

const statuses = computed(() => [
  { value: 'all' as const, label: 'All' },
  { value: 'running' as const, label: 'Running' },
  { value: 'finished' as const, label: 'Finished' },
  { value: 'failed' as const, label: 'Failed' },
  { value: 'cancelled' as const, label: 'Cancelled' },
]);

onMounted(load);
watch([currentSessionId, selectedSessionDataSource], load);

function load() {
  if (selectedSessionDataSource.value === 'backend' && currentSessionId.value) {
    void historyStore.loadRunHistory(currentSessionId.value);
  }
}

function setStatus(status: 'all' | ConsoleRunStatus) {
  historyStore.setRunStatusFilter(status);
}

function selectRun(run: ConsoleRunHistoryItem) {
  if (props.navigateOnSelect) {
    navigate('/console/workbench', {
      sessionId: run.sessionId || currentSessionId.value,
      runId: run.runId,
    });
    return;
  }
  void historyStore.selectHistoryRun(run.runId);
}

function tagType(status: string) {
  if (status === 'finished') {
    return 'success';
  }
  if (status === 'failed' || status === 'cancelled') {
    return 'danger';
  }
  if (status === 'running') {
    return 'warning';
  }
  return 'info';
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
