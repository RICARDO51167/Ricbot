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
import { currentRoute } from '@/router';
import { useSessionStore } from '@/stores/sessionStore';

const sessionStore = useSessionStore();

watch(
  () => [currentRoute.value.query, sessionStore.sessions.length, sessionStore.selectedSessionDataSource],
  (query) => {
    void applyWorkbenchQuery(query[0] as Record<string, string>);
  },
  { immediate: true },
);

async function applyWorkbenchQuery(query: Record<string, string>) {
  const sessionId = query.sessionId;
  const runId = query.runId;
  const eventId = query.eventId;
  if (sessionId && sessionId !== sessionStore.currentSessionId) {
    await sessionStore.selectSession(sessionId);
  }
  if (runId) {
    await sessionStore.setTimelineRunFilter(runId);
  }
  if (eventId) {
    await sessionStore.focusEventAfterSessionLoad(eventId);
  }
}
</script>
