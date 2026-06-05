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
  const runKeywordFilter = ref('');
  const runSessionIdFilter = ref('');

  const eventSearchResults = ref<ConsoleTimelineEvent[]>([]);
  const eventSearchLoading = ref(false);
  const eventSearchError = ref('');
  const eventSearchKeyword = ref('');
  const eventSearchCategory = ref<EventCategory>('all');
  const eventSearchStatus = ref<SearchStatusFilter>('all');
  const eventSearchScope = ref<SearchScope>('current');
  const eventSearchSessionId = ref('');
  const eventSearchRunId = ref('');
  const eventSearchSince = ref('');
  const eventSearchUntil = ref('');
  const selectedSearchEventId = ref('');
  const eventSearchHint = ref('');

  const filteredRunHistory = computed(() => {
    let runs = runHistory.value;
    if (runStatusFilter.value !== 'all') {
      runs = runs.filter((run) => run.status === runStatusFilter.value);
    }
    const keyword = runKeywordFilter.value.trim().toLowerCase();
    if (keyword) {
      runs = runs.filter((run) => {
        const text = `${run.runId} ${run.inputPreview} ${run.lastEventName} ${run.lastEventSummary}`.toLowerCase();
        return text.includes(keyword);
      });
    }
    return runs;
  });

  async function loadRunHistory(sessionId: string, options: { status?: HistoryStatusFilter; keyword?: string; runId?: string } = {}) {
    const effectiveSessionId = sessionId || runSessionIdFilter.value || useSessionStore().currentSessionId;
    if (!effectiveSessionId) {
      runHistory.value = [];
      return;
    }
    if (options.status) {
      runStatusFilter.value = options.status;
    }
    if (options.keyword !== undefined) {
      runKeywordFilter.value = options.keyword;
    }
    if (options.runId !== undefined) {
      selectedHistoryRunId.value = options.runId;
    }
    runSessionIdFilter.value = effectiveSessionId;
    runHistoryLoading.value = true;
    runHistoryError.value = '';
    try {
      const response = await getRunHistory(effectiveSessionId, {
        status: runStatusFilter.value === 'all' ? undefined : runStatusFilter.value,
        keyword: runKeywordFilter.value || undefined,
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

  function setRunKeywordFilter(keyword: string) {
    runKeywordFilter.value = keyword;
  }

  async function selectHistoryRun(runId: string) {
    selectedHistoryRunId.value = runId;
    await useSessionStore().setTimelineRunFilter(runId);
  }

  async function searchEvents(extra: Partial<ConsoleEventSearchQuery> = {}) {
    if (extra.sessionId !== undefined) {
      eventSearchSessionId.value = extra.sessionId;
      eventSearchScope.value = extra.sessionId ? 'current' : 'all';
    }
    if (extra.runId !== undefined) {
      eventSearchRunId.value = extra.runId;
    }
    if (extra.keyword !== undefined) {
      eventSearchKeyword.value = extra.keyword;
    }
    if (extra.category !== undefined) {
      eventSearchCategory.value = (extra.category || 'all') as EventCategory;
    }
    if (extra.status !== undefined) {
      eventSearchStatus.value = (extra.status || 'all') as SearchStatusFilter;
    }
    if (extra.since !== undefined) {
      eventSearchSince.value = extra.since;
    }
    if (extra.until !== undefined) {
      eventSearchUntil.value = extra.until;
    }
    eventSearchLoading.value = true;
    eventSearchError.value = '';
    eventSearchHint.value = '';
    try {
      const sessionStore = useSessionStore();
      const query: ConsoleEventSearchQuery = {
        keyword: eventSearchKeyword.value || undefined,
        category: eventSearchCategory.value === 'all' ? undefined : eventSearchCategory.value,
        status: eventSearchStatus.value === 'all' ? undefined : eventSearchStatus.value,
        sessionId: eventSearchScope.value === 'current' ? (eventSearchSessionId.value || sessionStore.currentSessionId) : undefined,
        runId: eventSearchRunId.value || undefined,
        since: eventSearchSince.value || undefined,
        until: eventSearchUntil.value || undefined,
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
    selectedSearchEventId.value = eventId;
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
    runKeywordFilter,
    runSessionIdFilter,
    filteredRunHistory,
    eventSearchResults,
    eventSearchLoading,
    eventSearchError,
    eventSearchKeyword,
    eventSearchCategory,
    eventSearchStatus,
    eventSearchScope,
    eventSearchSessionId,
    eventSearchRunId,
    eventSearchSince,
    eventSearchUntil,
    selectedSearchEventId,
    eventSearchHint,
    loadRunHistory,
    setRunStatusFilter,
    setRunKeywordFilter,
    selectHistoryRun,
    searchEvents,
    setEventSearchCategory,
    setEventSearchStatus,
    setEventSearchScope,
    selectSearchEvent,
  };
});
