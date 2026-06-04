import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  getRunHistory,
  searchEvents as searchEventsApi,
  type ConsoleRunStatus,
  type ConsoleEventSearchQuery,
  type ConsoleRunHistoryItem,
  type ConsoleTimelineEvent,
} from '@/api/consoleApi';
import type { EventCategory } from '@/types/agent-console';
import { useInspectorStore } from './inspectorStore';
import { useSessionStore } from './sessionStore';

type HistoryStatusFilter = 'all' | ConsoleRunStatus;
type SearchStatusFilter = 'all' | 'INFO' | 'SUCCESS' | 'WARN' | 'ERROR' | 'PENDING';
type SearchScope = 'current' | 'all';

export const useHistoryStore = defineStore('history', () => {
  const runHistory = ref<ConsoleRunHistoryItem[]>([]);
  const runHistoryLoading = ref(false);
  const runHistoryError = ref('');
  const selectedHistoryRunId = ref('');
  const runStatusFilter = ref<HistoryStatusFilter>('all');

  const eventSearchResults = ref<ConsoleTimelineEvent[]>([]);
  const eventSearchLoading = ref(false);
  const eventSearchError = ref('');
  const eventSearchKeyword = ref('');
  const eventSearchCategory = ref<EventCategory>('all');
  const eventSearchStatus = ref<SearchStatusFilter>('all');
  const eventSearchScope = ref<SearchScope>('current');
  const eventSearchHint = ref('');

  const filteredRunHistory = computed(() => {
    if (runStatusFilter.value === 'all') {
      return runHistory.value;
    }
    return runHistory.value.filter((run) => run.status === runStatusFilter.value);
  });

  async function loadRunHistory(sessionId: string) {
    runHistoryLoading.value = true;
    runHistoryError.value = '';
    try {
      const response = await getRunHistory(sessionId, {
        status: runStatusFilter.value === 'all' ? undefined : runStatusFilter.value,
      });
      runHistory.value = response.runs ?? [];
    } catch (error) {
      runHistory.value = [];
      runHistoryError.value = error instanceof Error ? error.message : 'Run history unavailable';
    } finally {
      runHistoryLoading.value = false;
    }
  }

  function setRunStatusFilter(status: HistoryStatusFilter) {
    runStatusFilter.value = status;
  }

  async function selectHistoryRun(runId: string) {
    selectedHistoryRunId.value = runId;
    await useSessionStore().setTimelineRunFilter(runId);
  }

  async function searchEvents(extra: Partial<ConsoleEventSearchQuery> = {}) {
    eventSearchLoading.value = true;
    eventSearchError.value = '';
    eventSearchHint.value = '';
    try {
      const sessionStore = useSessionStore();
      const query: ConsoleEventSearchQuery = {
        keyword: eventSearchKeyword.value || undefined,
        category: eventSearchCategory.value === 'all' ? undefined : eventSearchCategory.value,
        status: eventSearchStatus.value === 'all' ? undefined : eventSearchStatus.value,
        sessionId: eventSearchScope.value === 'current' ? sessionStore.currentSessionId : undefined,
        limit: 50,
        ...extra,
      };
      const response = await searchEventsApi(query);
      eventSearchResults.value = response.events ?? [];
    } catch (error) {
      eventSearchResults.value = [];
      eventSearchError.value = error instanceof Error ? error.message : 'Event search unavailable';
    } finally {
      eventSearchLoading.value = false;
    }
  }

  function setEventSearchCategory(category: EventCategory) {
    eventSearchCategory.value = category;
  }

  function setEventSearchStatus(status: SearchStatusFilter) {
    eventSearchStatus.value = status;
  }

  function setEventSearchScope(scope: SearchScope) {
    eventSearchScope.value = scope;
  }

  async function selectSearchEvent(event: ConsoleTimelineEvent) {
    const sessionStore = useSessionStore();
    const inspectorStore = useInspectorStore();
    const eventId = String(event.id ?? '');
    const sessionId = String((event as Record<string, unknown>).sessionId ?? '');
    if (sessionId && sessionId !== sessionStore.currentSessionId) {
      await sessionStore.selectSession(sessionId);
      await sessionStore.focusEventAfterSessionLoad(eventId);
      eventSearchHint.value = sessionStore.timelineFocusHint;
      return;
    }
    await sessionStore.focusEvent(eventId);
    if (sessionStore.timelineFocusHint) {
      eventSearchHint.value = sessionStore.timelineFocusHint;
      return;
    }
    inspectorStore.selectEvent(eventId);
    eventSearchHint.value = '';
  }

  return {
    runHistory,
    runHistoryLoading,
    runHistoryError,
    selectedHistoryRunId,
    runStatusFilter,
    filteredRunHistory,
    eventSearchResults,
    eventSearchLoading,
    eventSearchError,
    eventSearchKeyword,
    eventSearchCategory,
    eventSearchStatus,
    eventSearchScope,
    eventSearchHint,
    loadRunHistory,
    setRunStatusFilter,
    selectHistoryRun,
    searchEvents,
    setEventSearchCategory,
    setEventSearchStatus,
    setEventSearchScope,
    selectSearchEvent,
  };
});
