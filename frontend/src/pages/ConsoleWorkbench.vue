<template>
  <div class="workbench-page">
    <SessionSidebar />
    <ChatTimeline />
    <TraceInspector :show-action-log="false" :show-advanced-panels="false" />
  </div>
</template>

<script setup lang="ts">
import { watch } from 'vue';

import SessionSidebar from '@/components/layout/SessionSidebar.vue';
import TraceInspector from '@/components/inspector/TraceInspector.vue';
import ChatTimeline from '@/components/timeline/ChatTimeline.vue';
import { currentRoute, replace } from '@/router';
import { useSessionStore } from '@/stores/sessionStore';
import type { EventCategory } from '@/types/agent-console';

const sessionStore = useSessionStore();
let applyingQuery = false;

watch(
  () => [
    queryKey(currentRoute.value.query),
    sessionStore.sessions.length,
    sessionStore.selectedSessionDataSource,
  ],
  () => {
    void applyWorkbenchQuery(currentRoute.value.query);
  },
  { immediate: true },
);

watch(
  () => [
    sessionStore.currentSessionId,
    sessionStore.timelineRunFilter,
    sessionStore.timelineFilter,
  ],
  () => {
    syncWorkbenchQuery();
  },
);

async function applyWorkbenchQuery(query: Record<string, string>) {
  applyingQuery = true;
  const sessionId = query.sessionId;
  const runId = query.runId;
  const eventId = query.eventId;
  const category = normalizeCategory(query.category);
  try {
    if (category && category !== sessionStore.timelineFilter) {
      sessionStore.setTimelineFilter(category);
    }
    if (!query.category && sessionStore.timelineFilter !== 'all') {
      sessionStore.setTimelineFilter('all');
    }
    if (sessionId && sessionId !== sessionStore.currentSessionId) {
      await sessionStore.selectSession(sessionId);
    }
    if (runId && runId !== sessionStore.timelineRunFilter) {
      await sessionStore.setTimelineRunFilter(runId);
    }
    if (!runId && sessionStore.timelineRunFilter) {
      await sessionStore.setTimelineRunFilter(null);
    }
    if (eventId) {
      await sessionStore.focusEventAfterSessionLoad(eventId);
    }
  } finally {
    applyingQuery = false;
    syncWorkbenchQuery();
  }
}

function syncWorkbenchQuery() {
  if (applyingQuery || !['/console/runs', '/console/workbench'].includes(currentRoute.value.path)) {
    return;
  }
  const next = {
    ...currentRoute.value.query,
    sessionId: sessionStore.currentSessionId || undefined,
    runId: sessionStore.timelineRunFilter || undefined,
    category: sessionStore.timelineFilter === 'all' ? undefined : sessionStore.timelineFilter,
  };
  if (queryKey(next) !== queryKey(currentRoute.value.query)) {
    replace(currentRoute.value.path, next);
  }
}

function normalizeCategory(value: string | undefined): EventCategory | null {
  const categories: EventCategory[] = ['all', 'run', 'tool', 'approval', 'changeset', 'error', 'system'];
  return categories.includes(value as EventCategory) ? value as EventCategory : null;
}

function queryKey(query: Record<string, string | undefined>) {
  return JSON.stringify(Object.entries(query)
    .filter(([, value]) => value !== undefined && value !== '')
    .sort(([left], [right]) => left.localeCompare(right)));
}
</script>
