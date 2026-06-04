<template>
  <section class="action-log">
    <div class="action-log-head">
      <h3>{{ t('actionLog.title') }}</h3>
      <el-tag size="small">{{ filteredEvents.length }}</el-tag>
    </div>
    <div v-if="timelineRunFilter" class="action-log-run-filter">
      run={{ timelineRunFilter }}
    </div>
    <div class="action-log-filters">
      <button
        v-for="filter in filters"
        :key="filter.value"
        type="button"
        class="filter-chip"
        :class="{ active: timelineFilter === filter.value }"
        @click="sessionStore.setTimelineFilter(filter.value)"
      >
        {{ filter.label }} <span>{{ countFor(filter.value) }}</span>
      </button>
    </div>
    <button
      v-for="event in filteredEvents"
      :key="event.id"
      type="button"
      class="action-log-row"
      :class="{ selected: selectedEventId === event.id }"
      @click="inspectorStore.selectEvent(event.id)"
    >
      <span class="action-log-meta">
        <strong>{{ event.name || event.title }}</strong>
        <em>{{ event.category || 'system' }} · {{ event.status || event.severity }}</em>
        <code>{{ sourceLabel(event.eventSource) }}</code>
      </span>
      <small>{{ formatTime(event.timestamp) }}</small>
    </button>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';

import { useInspectorStore } from '@/stores/inspectorStore';
import { useLocaleStore } from '@/stores/localeStore';
import { useSessionStore } from '@/stores/sessionStore';

const sessionStore = useSessionStore();
const inspectorStore = useInspectorStore();
const { actionLogEvents, timelineFilter, timelineRunFilter } = storeToRefs(sessionStore);
const { selectedEventId } = storeToRefs(inspectorStore);
const { t } = useLocaleStore();

const filters = computed(() => [
  { value: 'all' as const, label: t('timeline.filter.all') },
  { value: 'run' as const, label: t('timeline.filter.run') },
  { value: 'tool' as const, label: t('timeline.filter.tool') },
  { value: 'approval' as const, label: t('timeline.filter.approval') },
  { value: 'changeset' as const, label: t('timeline.filter.changeset') },
  { value: 'error' as const, label: t('timeline.filter.error') },
  { value: 'system' as const, label: t('timeline.filter.system') },
]);

const filteredEvents = computed(() => {
  if (timelineFilter.value === 'all') {
    return actionLogEvents.value;
  }
  return actionLogEvents.value.filter((event) => eventCategory(event) === timelineFilter.value);
});

function fallbackCategory(kind: string) {
  if (kind === 'run' || kind === 'tool' || kind === 'approval') {
    return kind;
  }
  return 'system';
}

function eventCategory(event: { category?: string; kind: string }) {
  return event.category || fallbackCategory(event.kind);
}

function countFor(category: string) {
  if (category === 'all') {
    return actionLogEvents.value.length;
  }
  return actionLogEvents.value.filter((event) => eventCategory(event) === category).length;
}

function sourceLabel(value?: string) {
  const source = String(value || '').trim();
  return source || 'system';
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '--:--';
  }
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date);
}
</script>
