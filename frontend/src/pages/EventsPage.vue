<template>
  <div class="events-page">
    <section class="console-page-card">
      <div class="page-head">
        <div>
          <h2>{{ t('nav.events') }}</h2>
          <p>{{ t('page.events.description') }}</p>
        </div>
      </div>
      <EventSearch />
    </section>
    <section class="console-page-card">
      <div class="page-head">
        <div>
          <h2>{{ t('page.events.detailTitle') }}</h2>
          <p>{{ t('page.events.detailDescription') }}</p>
        </div>
      </div>
      <div v-if="selectedEvent" class="event-detail-panel">
        <div class="event-detail-head">
          <strong>{{ selectedEvent.name || selectedEvent.title }}</strong>
          <el-button size="small" @click="openInWorkbench">{{ t('page.events.openWorkbench') }}</el-button>
        </div>
        <JsonViewer :value="selectedEvent" />
      </div>
      <div v-else class="history-empty">{{ t('page.events.noSelection') }}</div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { storeToRefs } from 'pinia';

import EventSearch from '@/components/inspector/EventSearch.vue';
import JsonViewer from '@/components/inspector/JsonViewer.vue';
import { currentRoute, navigate, replace } from '@/router';
import { useHistoryStore } from '@/stores/historyStore';
import { useLocaleStore } from '@/stores/localeStore';

const historyStore = useHistoryStore();
const { t } = useLocaleStore();
const {
  eventSearchKeyword,
  eventSearchCategory,
  eventSearchStatus,
  eventSearchScope,
  eventSearchSessionId,
  eventSearchRunId,
  eventSearchSince,
  eventSearchUntil,
  selectedSearchEventId,
  eventSearchResults,
} = storeToRefs(historyStore);
let applyingQuery = false;

const selectedEvent = computed(() => {
  return eventSearchResults.value.find((event) => event.id === selectedSearchEventId.value) ?? null;
});

watch(
  () => queryKey(currentRoute.value.query),
  () => {
    void applyEventsQuery(currentRoute.value.query);
  },
  { immediate: true },
);

watch(
  () => [
    eventSearchKeyword.value,
    eventSearchCategory.value,
    eventSearchStatus.value,
    eventSearchScope.value,
    eventSearchSessionId.value,
    eventSearchRunId.value,
    eventSearchSince.value,
    eventSearchUntil.value,
    selectedSearchEventId.value,
  ],
  syncEventsQuery,
);

async function applyEventsQuery(query: Record<string, string>) {
  if (currentRoute.value.path !== '/console/events') {
    return;
  }
  applyingQuery = true;
  try {
    eventSearchScope.value = query.sessionId ? 'current' : 'all';
    await historyStore.searchEvents({
      sessionId: query.sessionId ?? '',
      runId: query.runId ?? '',
      category: query.category ?? '',
      status: query.status ?? '',
      keyword: query.keyword ?? '',
      since: query.since ?? '',
      until: query.until ?? '',
    });
    selectedSearchEventId.value = query.eventId ?? '';
  } finally {
    applyingQuery = false;
    syncEventsQuery();
  }
}

function syncEventsQuery() {
  if (applyingQuery || currentRoute.value.path !== '/console/events') {
    return;
  }
  const next = {
    sessionId: eventSearchScope.value === 'current' ? eventSearchSessionId.value || undefined : undefined,
    runId: eventSearchRunId.value || undefined,
    category: eventSearchCategory.value === 'all' ? undefined : eventSearchCategory.value,
    status: eventSearchStatus.value === 'all' ? undefined : eventSearchStatus.value,
    keyword: eventSearchKeyword.value || undefined,
    since: eventSearchSince.value || undefined,
    until: eventSearchUntil.value || undefined,
    eventId: selectedSearchEventId.value || undefined,
  };
  if (queryKey(next) !== queryKey(currentRoute.value.query)) {
    replace('/console/events', next);
  }
}

function openInWorkbench() {
  if (!selectedEvent.value) {
    return;
  }
  navigate('/console/workbench', {
    sessionId: String(selectedEvent.value.sessionId ?? ''),
    eventId: String(selectedEvent.value.id ?? ''),
  });
}

function queryKey(query: Record<string, string | undefined>) {
  return JSON.stringify(Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== '')
    .sort(([left], [right]) => left.localeCompare(right)));
}
</script>
