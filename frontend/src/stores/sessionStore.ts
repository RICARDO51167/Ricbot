import { computed, nextTick, ref } from 'vue';
import { defineStore } from 'pinia';

import {
  cancelRun,
  getSessionEvents,
  getSessionDetail,
  getSessionEventStreamUrl,
  getRunStatus,
  getSessions,
  getSessionTimeline,
  startSessionRun,
  type ConsoleRunStatus,
  type ConsoleSessionDetail,
  type ConsoleSessionSummary,
  type ConsoleTimelineEvent,
} from '@/api/consoleApi';
import { mockSessions } from '@/mocks/sessions';
import type {
  AgentSession,
  ApprovalRequest,
  ChangeSet,
  ChangedFile,
  DataSource,
  FileChangeType,
  RiskAssessment,
  RiskLevel,
  RunStatus,
  EventCategory,
  TimelineEvent,
  ToolStatus,
} from '@/types/agent-console';
import { useChangeSetStore } from './changeSetStore';
import { useInspectorStore } from './inspectorStore';
import { useRuntimeStore } from './runtimeStore';

export const useSessionStore = defineStore('sessions', () => {
  const sessions = ref<AgentSession[]>(mockSessions);
  const currentSessionId = ref(mockSessions[0]?.id ?? '');
  const dataSource = ref<DataSource>('mock');
  const selectedSessionDataSource = ref<DataSource>('mock');
  const backendUnavailable = ref(false);
  const loading = ref(false);
  const detailLoading = ref(false);
  const detailError = ref('');
  const backendSessionIds = ref(new Set<string>());
  const currentCursor = ref('');
  const pollingStatus = ref<'idle' | 'polling' | 'error'>('idle');
  const streamStatus = ref<'idle' | 'connecting' | 'live' | 'error' | 'fallback_polling'>('idle');
  const streamLastEventId = ref('');
  const streamError = ref('');
  const draftInput = ref('');
  const submittingRun = ref(false);
  const submitError = ref('');
  const lastRunId = ref('');
  const activeRunId = ref('');
  const activeRunStatus = ref<ConsoleRunStatus | ''>('');
  const runStatusLoading = ref(false);
  const runStatusError = ref('');
  const cancellingRun = ref(false);
  const cancelError = ref('');
  const timelineFilter = ref<EventCategory>('all');
  const timelineRunFilter = ref('');
  const timelineFocusHint = ref('');
  const pendingFocusEventId = ref('');
  const replayActive = ref(false);
  const replayIndex = ref(0);
  let pollingTimer: ReturnType<typeof setInterval> | null = null;
  let replayTimer: ReturnType<typeof setInterval> | null = null;
  let eventSource: EventSource | null = null;

  const currentSession = computed(() => sessions.value.find((session) => session.id === currentSessionId.value) ?? null);
  const currentTimeline = computed(() => currentSession.value?.timeline ?? []);
  const filteredTimeline = computed(() => {
    let events = currentTimeline.value;
    if (timelineRunFilter.value) {
      events = events.filter((event) => event.runId === timelineRunFilter.value);
    }
    if (timelineFilter.value !== 'all') {
      events = events.filter((event) => eventCategory(event) === timelineFilter.value);
    }
    return events;
  });
  const replayTimeline = computed(() => {
    if (!replayActive.value) {
      return filteredTimeline.value;
    }
    return filteredTimeline.value.slice(0, replayIndex.value);
  });
  const actionLogEvents = computed(() => filteredTimeline.value.slice(-30).reverse());
  const selectedDetailIsEmpty = computed(() => {
    return selectedSessionDataSource.value === 'backend'
      && !detailLoading.value
      && currentTimeline.value.length === 0;
  });

  async function selectSession(sessionId: string) {
    stopEventStream();
    stopEventPolling();
    stopReplay();
    const nextSession = sessions.value.find((session) => session.id === sessionId);
    if (!nextSession) {
      return;
    }

    currentSessionId.value = nextSession.id;
    resetRunState();
    resetTimelineRunFilter();
    useInspectorStore().resetForSession(nextSession);
    useChangeSetStore().resetForSession(nextSession);

    if (dataSource.value !== 'backend' || !backendSessionIds.value.has(sessionId)) {
      selectedSessionDataSource.value = 'mock';
      detailError.value = '';
      currentCursor.value = '';
      streamLastEventId.value = '';
      return;
    }

    await loadSessionDetail(sessionId);
    if (selectedSessionDataSource.value === 'backend') {
      startEventStream(sessionId);
    }
  }

  function fallbackToMock() {
    stopEventStream();
    stopEventPolling();
    sessions.value = mockSessions;
    currentSessionId.value = mockSessions[0]?.id ?? '';
    dataSource.value = 'mock';
    selectedSessionDataSource.value = 'mock';
    backendUnavailable.value = true;
    detailError.value = '';
    currentCursor.value = '';
    streamLastEventId.value = '';
    streamError.value = '';
    resetRunState();
    resetTimelineRunFilter();
    clearFocusHint();
    stopReplay();
    backendSessionIds.value = new Set();
    const current = sessions.value[0];
    if (current) {
      useInspectorStore().resetForSession(current);
      useChangeSetStore().resetForSession(current);
    }
  }

  async function loadFromBackend() {
    loading.value = true;
    try {
      const response = await getSessions();
      const mapped = (response.items ?? []).map(toAgentSession);
      backendSessionIds.value = new Set(mapped.map((session) => session.id));
      sessions.value = mapped.length > 0 ? mapped : mockSessions;
      currentSessionId.value = sessions.value[0]?.id ?? '';
      dataSource.value = 'backend';
      selectedSessionDataSource.value = mapped.length > 0 ? 'backend' : 'mock';
      backendUnavailable.value = false;
      resetRunState();
      resetTimelineRunFilter();
      const current = sessions.value[0];
      if (current) {
        useInspectorStore().resetForSession(current);
        useChangeSetStore().resetForSession(current);
        if (mapped.length > 0) {
          await loadSessionDetail(current.id);
          startEventStream(current.id);
        }
      }
    } catch {
      fallbackToMock();
    } finally {
      loading.value = false;
    }
  }

  async function loadSessionDetail(sessionId: string) {
    detailLoading.value = true;
    detailError.value = '';
    try {
      const [detail, timeline] = await Promise.all([
        getSessionDetail(sessionId),
        getSessionTimeline(sessionId, timelineQuery()).catch(() => [] as ConsoleTimelineEvent[]),
      ]);
      applyBackendDetail(sessionId, detail, timeline);
      selectedSessionDataSource.value = 'backend';
      await focusPendingEvent();
    } catch (error) {
      detailError.value = error instanceof Error ? error.message : 'Session detail unavailable';
      applyMockDetail(sessionId);
      selectedSessionDataSource.value = 'mock';
      stopEventStream();
      stopEventPolling();
    } finally {
      detailLoading.value = false;
    }
  }

  function applyBackendDetail(sessionId: string, detail: ConsoleSessionDetail, timeline: ConsoleTimelineEvent[]) {
    const index = sessions.value.findIndex((session) => session.id === sessionId);
    if (index < 0) {
      return;
    }
    const current = sessions.value[index];
    const mappedTimeline = timeline.length > 0 ? timeline.map(toTimelineEvent) : timelineFromDetail(detail);
    const changeSet = changeSetFromBackend(detail, sessionId);
    const next: AgentSession = {
      ...current,
      id: detail.sessionId || sessionId,
      title: detail.title || current.title,
      status: normalizeStatus(detail.status || current.status),
      workspace: detail.workspace || current.workspace,
      model: typeof detail.model === 'string' ? detail.model : current.model,
      toolCount: numberOrZero(detail.toolCalls?.length ?? current.toolCount),
      approvalPendingCount: numberOrZero(detail.approvalEvents?.length ?? current.approvalPendingCount),
      changedFileCount: changeSet.changedFiles.length,
      summary: mappedTimeline.length > 0
        ? current.summary
        : '当前 session 暂无详细运行记录',
      timeline: mappedTimeline,
      changeSet,
    };
    sessions.value.splice(index, 1, next);
    currentSessionId.value = next.id;
    currentCursor.value = lastEventId(next.timeline);
    useInspectorStore().resetForSession(next);
    useChangeSetStore().resetForSession(next);
  }

  function applyMockDetail(sessionId: string) {
    const mock = mockSessions.find((session) => session.id === sessionId) ?? mockSessions[0];
    if (!mock) {
      return;
    }
    const index = sessions.value.findIndex((session) => session.id === sessionId);
    if (index >= 0) {
      sessions.value.splice(index, 1, mock);
    } else {
      sessions.value = mockSessions;
    }
    currentSessionId.value = mock.id;
    currentCursor.value = '';
    streamLastEventId.value = '';
    resetRunState();
    useInspectorStore().resetForSession(mock);
    useChangeSetStore().resetForSession(mock);
  }

  function startEventStream(sessionId = currentSessionId.value) {
    stopEventStream();
    stopEventPolling();
    if (dataSource.value !== 'backend' || selectedSessionDataSource.value !== 'backend' || !backendSessionIds.value.has(sessionId)) {
      streamStatus.value = 'idle';
      return;
    }
    if (typeof EventSource === 'undefined') {
      streamStatus.value = 'fallback_polling';
      startEventPolling(sessionId);
      return;
    }

    streamStatus.value = 'connecting';
    streamError.value = '';
    const source = new EventSource(getSessionEventStreamUrl(sessionId, currentCursor.value || undefined));
    eventSource = source;

    source.onopen = () => {
      if (eventSource !== source) {
        return;
      }
      streamStatus.value = 'live';
      stopEventPolling();
    };

    source.addEventListener('timeline', (event) => {
      handleStreamPayload(sessionId, event, source);
    });
    source.addEventListener('timeline_batch', (event) => {
      handleStreamPayload(sessionId, event, source);
    });
    source.addEventListener('heartbeat', () => {
      if (eventSource === source && streamStatus.value === 'connecting') {
        streamStatus.value = 'live';
      }
    });

    source.onerror = () => {
      if (eventSource !== source) {
        return;
      }
      source.close();
      eventSource = null;
      streamStatus.value = 'fallback_polling';
      streamError.value = 'Event stream unavailable; falling back to polling.';
      if (currentSessionId.value === sessionId
        && dataSource.value === 'backend'
        && selectedSessionDataSource.value === 'backend'
        && backendSessionIds.value.has(sessionId)) {
        startEventPolling(sessionId);
      }
    };
  }

  function stopEventStream() {
    if (eventSource) {
      eventSource.close();
      eventSource = null;
    }
    streamStatus.value = 'idle';
  }

  function handleStreamPayload(sessionId: string, event: MessageEvent, source: EventSource) {
    if (eventSource !== source || currentSessionId.value !== sessionId) {
      return;
    }
    try {
      const parsed = JSON.parse(String(event.data ?? '{}')) as {
        event?: ConsoleTimelineEvent;
        events?: ConsoleTimelineEvent[];
        nextCursor?: string;
      };
      const events = parsed.events ?? (parsed.event ? [parsed.event] : []);
      mergeTimelineEvents(events);
      const nextCursor = parsed.nextCursor || lastRawEventId(events);
      if (nextCursor) {
        currentCursor.value = nextCursor;
        streamLastEventId.value = nextCursor;
      }
      streamStatus.value = 'live';
    } catch (error) {
      streamStatus.value = 'error';
      streamError.value = error instanceof Error ? error.message : 'Invalid event stream payload';
    }
  }

  function startEventPolling(sessionId = currentSessionId.value) {
    stopEventPolling();
    if (dataSource.value !== 'backend' || selectedSessionDataSource.value !== 'backend' || !backendSessionIds.value.has(sessionId)) {
      pollingStatus.value = 'idle';
      return;
    }
    pollingStatus.value = 'polling';
    pollingTimer = setInterval(() => {
      void refreshEventsOnce();
    }, 3000);
  }

  function stopEventPolling() {
    if (pollingTimer !== null) {
      clearInterval(pollingTimer);
      pollingTimer = null;
    }
    if (pollingStatus.value === 'polling') {
      pollingStatus.value = 'idle';
    }
  }

  async function refreshEventsOnce() {
    const sessionId = currentSessionId.value;
    if (dataSource.value !== 'backend' || selectedSessionDataSource.value !== 'backend' || !backendSessionIds.value.has(sessionId)) {
      pollingStatus.value = 'idle';
      return;
    }
    try {
      const response = await getSessionEvents(sessionId, currentCursor.value || undefined, timelineQuery());
      mergeTimelineEvents(response.events ?? []);
      currentCursor.value = response.nextCursor || lastEventId(currentTimeline.value);
      pollingStatus.value = 'polling';
    } catch {
      pollingStatus.value = 'error';
    }
  }

  async function submitRun() {
    const input = draftInput.value.trim();
    submitError.value = '';
    cancelError.value = '';
    lastRunId.value = '';
    if (!input) {
      submitError.value = 'Input cannot be blank';
      return;
    }
    if (dataSource.value !== 'backend' || selectedSessionDataSource.value !== 'backend' || backendUnavailable.value) {
      submitError.value = 'Mock Preview 模式暂不提交真实任务';
      return;
    }
    const runtimeStore = useRuntimeStore();
    if (!runtimeStore.runtime.modelConfigured) {
      submitError.value = '模型未配置，无法启动任务';
      return;
    }
    const session = currentSession.value;
    if (!session) {
      submitError.value = 'No session selected';
      return;
    }

    submittingRun.value = true;
    try {
      const response = await startSessionRun(session.id, input, {
        workspace: runtimeStore.runtime.workspace || session.workspace,
        model: runtimeStore.runtime.model ?? session.model,
        mode: 'interactive',
      });
      if (response.status === 'failed') {
        submitError.value = runErrorMessage(response.code, response.message);
        return;
      }
      lastRunId.value = response.runId ?? '';
      activeRunId.value = response.runId ?? '';
      activeRunStatus.value = normalizeRunStatus(response.status);
      draftInput.value = '';
      if (streamStatus.value === 'idle' && pollingStatus.value === 'idle') {
        startEventStream(session.id);
      }
    } catch (error) {
      submitError.value = error instanceof Error ? error.message : 'Run failed';
    } finally {
      submittingRun.value = false;
    }
  }

  async function loadRunStatus(runId = activeRunId.value) {
    if (!runId) {
      return;
    }
    runStatusLoading.value = true;
    runStatusError.value = '';
    try {
      const response = await getRunStatus(runId);
      activeRunId.value = response.runId;
      activeRunStatus.value = normalizeRunStatus(response.status);
    } catch (error) {
      runStatusError.value = error instanceof Error ? error.message : 'Run status unavailable';
    } finally {
      runStatusLoading.value = false;
    }
  }

  async function cancelActiveRun() {
    if (!activeRunId.value || !canCancelStatus(activeRunStatus.value)) {
      return;
    }
    cancellingRun.value = true;
    cancelError.value = '';
    try {
      const response = await cancelRun(activeRunId.value);
      if (response.status === 'failed') {
        cancelError.value = runErrorMessage(response.code, response.message);
        return;
      }
      activeRunStatus.value = normalizeRunStatus(response.status);
    } catch (error) {
      cancelError.value = error instanceof Error ? error.message : 'Cancel failed';
    } finally {
      cancellingRun.value = false;
    }
  }

  function resetRunState() {
    submittingRun.value = false;
    submitError.value = '';
    lastRunId.value = '';
    activeRunId.value = '';
    activeRunStatus.value = '';
    runStatusLoading.value = false;
    runStatusError.value = '';
    cancellingRun.value = false;
    cancelError.value = '';
  }

  function setTimelineFilter(category: EventCategory) {
    timelineFilter.value = category;
    stopReplay();
    if (pendingFocusEventId.value) {
      void focusPendingEvent();
    }
  }

  async function setTimelineRunFilter(runId: string | null) {
    timelineRunFilter.value = runId?.trim() ?? '';
    currentCursor.value = '';
    streamLastEventId.value = '';
    stopReplay();
    stopEventStream();
    stopEventPolling();
    if (dataSource.value === 'backend' && selectedSessionDataSource.value === 'backend' && currentSessionId.value) {
      await loadTimelineForRun(currentSessionId.value, timelineRunFilter.value || null);
      startEventStream(currentSessionId.value);
    }
  }

  async function loadTimelineForRun(sessionId: string, runId: string | null) {
    detailLoading.value = true;
    detailError.value = '';
    try {
      const timeline = await getSessionTimeline(sessionId, {
        ...timelineQuery(),
        runId: runId?.trim() || undefined,
      });
      replaceTimeline(sessionId, timeline.map(toTimelineEvent));
      selectedSessionDataSource.value = 'backend';
      currentCursor.value = lastEventId(currentTimeline.value);
      await focusPendingEvent();
    } catch (error) {
      detailError.value = error instanceof Error ? error.message : 'Timeline unavailable';
    } finally {
      detailLoading.value = false;
    }
  }

  function clearTimelineRunFilter() {
    if (timelineRunFilter.value) {
      void setTimelineRunFilter(null);
    } else {
      stopReplay();
    }
  }

  function resetTimelineRunFilter() {
    timelineRunFilter.value = '';
    stopReplay();
  }

  async function focusEvent(eventId: string) {
    pendingFocusEventId.value = eventId;
    timelineFocusHint.value = '';
    await focusPendingEvent();
  }

  async function focusEventAfterSessionLoad(eventId: string) {
    pendingFocusEventId.value = eventId;
    timelineFocusHint.value = '';
    await focusPendingEvent();
  }

  function clearFocusHint() {
    timelineFocusHint.value = '';
  }

  async function focusPendingEvent() {
    const eventId = pendingFocusEventId.value;
    if (!eventId) {
      return;
    }
    await nextTick();
    const existsInResult = filteredTimeline.value.some((event) => event.id === eventId);
    if (!existsInResult) {
      timelineFocusHint.value = '事件不在当前 timeline 结果中，可尝试清除过滤条件';
      return;
    }
    useInspectorStore().selectEvent(eventId);
    pendingFocusEventId.value = '';
    timelineFocusHint.value = '';
    await scrollToTimelineEvent(eventId);
  }

  async function scrollToTimelineEvent(eventId: string) {
    await nextTick();
    const target = document.getElementById(timelineDomId(eventId));
    if (!target) {
      timelineFocusHint.value = '事件不在当前 timeline 结果中，可尝试清除过滤条件';
      return false;
    }
    if (typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    return true;
  }

  function startReplay() {
    stopReplay();
    if (filteredTimeline.value.length === 0) {
      return;
    }
    replayActive.value = true;
    replayIndex.value = 0;
    replayTimer = setInterval(() => {
      replayIndex.value += 1;
      const event = filteredTimeline.value[replayIndex.value - 1];
      if (event) {
        useInspectorStore().selectEvent(event.id);
      }
      if (replayIndex.value >= filteredTimeline.value.length) {
        stopReplay(false);
      }
    }, 600);
  }

  function stopReplay(reset = true) {
    if (replayTimer !== null) {
      clearInterval(replayTimer);
      replayTimer = null;
    }
    replayActive.value = false;
    if (reset) {
      replayIndex.value = 0;
    }
  }

  function timelineQuery() {
    return {
      category: timelineFilter.value === 'all' ? undefined : timelineFilter.value,
      runId: timelineRunFilter.value || undefined,
    };
  }

  function replaceTimeline(sessionId: string, timeline: TimelineEvent[]) {
    const index = sessions.value.findIndex((session) => session.id === sessionId);
    if (index < 0) {
      return;
    }
    const current = sessions.value[index];
    const next = {
      ...current,
      timeline,
    };
    sessions.value.splice(index, 1, next);
    currentSessionId.value = next.id;
    useInspectorStore().resetForSession(next);
  }

  function mergeTimelineEvents(events: ConsoleTimelineEvent[]) {
    const session = currentSession.value;
    if (!session || events.length === 0) {
      return;
    }
    const seen = new Set(session.timeline.map((event) => event.id));
    const additions = events
      .map((event, index) => toTimelineEvent(event, session.timeline.length + index))
      .filter((event) => {
        if (seen.has(event.id)) {
          return false;
        }
        seen.add(event.id);
        return true;
      });
    if (additions.length === 0) {
      return;
    }
    const nextTimeline = [...session.timeline, ...additions]
      .sort((a, b) => a.timestamp.localeCompare(b.timestamp));
    replaceCurrentSession({
      ...session,
      timeline: nextTimeline,
    });
  }

  function replaceCurrentSession(next: AgentSession) {
    const index = sessions.value.findIndex((session) => session.id === next.id);
    if (index >= 0) {
      sessions.value.splice(index, 1, next);
    }
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    currentTimeline,
    filteredTimeline,
    actionLogEvents,
    dataSource,
    selectedSessionDataSource,
    backendUnavailable,
    loading,
    detailLoading,
    detailError,
    currentCursor,
    pollingStatus,
    streamStatus,
    streamLastEventId,
    streamError,
    draftInput,
    submittingRun,
    submitError,
    lastRunId,
    activeRunId,
    activeRunStatus,
    runStatusLoading,
    runStatusError,
    cancellingRun,
    cancelError,
    timelineFilter,
    timelineRunFilter,
    timelineFocusHint,
    pendingFocusEventId,
    replayActive,
    replayIndex,
    selectedDetailIsEmpty,
    replayTimeline,
    selectSession,
    loadFromBackend,
    loadSessionDetail,
    startEventPolling,
    stopEventPolling,
    startEventStream,
    stopEventStream,
    refreshEventsOnce,
    submitRun,
    loadRunStatus,
    cancelActiveRun,
    setTimelineFilter,
    setTimelineRunFilter,
    loadTimelineForRun,
    clearTimelineRunFilter,
    focusEvent,
    focusEventAfterSessionLoad,
    scrollToTimelineEvent,
    clearFocusHint,
    startReplay,
    stopReplay,
    mergeTimelineEvents,
    fallbackToMock,
  };
});

function toAgentSession(summary: ConsoleSessionSummary): AgentSession {
  const id = summary.id || summary.key || 'backend-session';
  const updatedAt = summary.updatedAt || new Date().toISOString();
  return {
    id,
    project: summary.project || 'Ricbot',
    title: summary.title || id,
    task: summary.task || 'Read-only backend session',
    status: normalizeStatus(summary.status),
    updatedAt,
    workspace: '',
    model: '',
    traceId: '',
    runId: id,
    tokenCount: 0,
    toolCount: numberOrZero(summary.toolCount),
    approvalPendingCount: numberOrZero(summary.approvalPendingCount),
    changedFileCount: numberOrZero(summary.changedFileCount),
    riskLevel: normalizeRisk(summary.riskLevel),
    summary: summary.summary || `Backend session, ${numberOrZero(summary.messageCount)} persisted messages.`,
    timeline: [
      {
        id: `${id}-summary`,
        kind: 'message',
        role: 'system',
        timestamp: updatedAt,
        title: 'Backend session',
        detail: 'Read-only session summary loaded from backend',
        severity: 'INFO',
        source: 'AGENT',
        refs: { sessionId: id },
        content: `Read-only backend session. messageCount=${numberOrZero(summary.messageCount)}`,
      },
    ],
    changeSet: {
      id: `${id}-no-changeset`,
      sessionId: id,
      teamSessionId: '',
      taskId: '',
      baseCommit: '',
      status: 'DRAFT',
      diffSummary: 'No backend ChangeSet attached to this session yet.',
      changedFiles: [],
      suggestedTests: [],
      executedTests: [],
      verifierStatus: 'PENDING',
      verifierReasons: [],
      commitMessage: '',
      updatedAt,
    },
  };
}

function normalizeStatus(value: unknown): RunStatus {
  const normalized = String(value ?? '').trim().toUpperCase().replace(/-/g, '_');
  if (['RUNNING', 'WAITING_APPROVAL', 'PASS', 'FAILED', 'IDLE'].includes(normalized)) {
    return normalized as RunStatus;
  }
  if (['FINISHED', 'COMPLETED', 'SUCCESS', 'SUCCEEDED'].includes(normalized)) {
    return 'PASS';
  }
  return 'IDLE';
}

function toTimelineEvent(event: ConsoleTimelineEvent, index: number): TimelineEvent {
  const payload = isRecord(event.payload) ? event.payload : {};
  const type = String(event.type ?? '').trim();
  const title = String(event.title ?? (type || 'Backend event'));
  const detail = String(event.summary ?? payload.detail ?? payload.content ?? title);
  const timestamp = safeTimestamp(event.time ?? payload.timestamp ?? payload.createdAt ?? payload.updatedAt);
  const base = {
    id: String(event.id ?? `backend-event-${index}`),
    timestamp,
    title,
    detail,
    severity: severityFromStatus(event.status),
    refs: refsFromPayload(payload),
    runId: String((event as Record<string, unknown>).runId ?? payload.runId ?? payload.run_id ?? ''),
    eventType: type,
    name: String((event as Record<string, unknown>).name ?? payload.type ?? (type || 'system_event')),
    category: normalizeEventCategory((event as Record<string, unknown>).category ?? type),
    status: String(event.status ?? (event as Record<string, unknown>).status ?? 'info'),
    actor: String((event as Record<string, unknown>).actor ?? 'system'),
    eventSource: String((event as Record<string, unknown>).source ?? 'console'),
  };

  if (type === 'user_message' || type === 'assistant_message' || type === 'system_message' || type === 'tool_message') {
    const role = type === 'assistant_message' ? 'assistant' : type === 'system_message' || type === 'tool_message' ? 'system' : 'user';
    return {
      ...base,
      kind: 'message',
      role,
      source: 'AGENT',
      content: String(payload.content ?? detail),
    };
  }

  if (type === 'tool_call') {
    return {
      ...base,
      kind: 'tool',
      source: 'TOOL',
      toolCall: {
        id: String(payload.id ?? base.id),
        toolName: String(payload.toolName ?? payload.name ?? (title.replace(/^Tool:\s*/, '') || 'tool_call')),
        status: normalizeToolStatus(event.status ?? payload.status),
        durationMs: numberOrZero(payload.durationMs),
        arguments: asRecord(payload.arguments),
        result: asRecord(payload.result),
        policy: {
          executionPolicy: String(asRecord(payload.policy).executionPolicy ?? 'READ_ONLY_DETAIL'),
          permissionPolicy: String(asRecord(payload.policy).permissionPolicy ?? 'backend persisted session'),
          approvalRequired: Boolean(asRecord(payload.policy).approvalRequired),
        },
        refs: refsFromPayload(payload),
      },
    };
  }

  if (type === 'approval_event') {
    return {
      ...base,
      kind: 'approval',
      source: 'APPROVAL',
      approval: approvalFromPayload(payload),
    };
  }

  if (type === 'run_event') {
    return {
      ...base,
      kind: 'run',
      source: 'AGENT',
      payload,
    };
  }

  return {
    ...base,
    kind: 'trace',
    source: type === 'changeset_event' ? 'TEAM' : 'TRACE',
    payload,
  };
}

function timelineFromDetail(detail: ConsoleSessionDetail): TimelineEvent[] {
  const messages = (detail.messages ?? []).map((message, index) => toTimelineEvent({
    id: `${detail.sessionId}-message-${index}`,
    type: `${String(message.role ?? 'user')}_message`,
    time: String(message.timestamp ?? ''),
    title: `${String(message.role ?? 'user')} message`,
    summary: String(message.content ?? ''),
    status: 'OK',
    payload: message,
  }, index));
  const toolCalls = (detail.toolCalls ?? []).map((toolCall, index) => toTimelineEvent({
    id: `${detail.sessionId}-tool-${String(toolCall.id ?? index)}`,
    type: 'tool_call',
    time: String(toolCall.createdAt ?? ''),
    title: `Tool: ${String(toolCall.toolName ?? 'tool_call')}`,
    summary: String(toolCall.summary ?? toolCall.toolName ?? ''),
    status: String(toolCall.status ?? 'UNKNOWN'),
    payload: toolCall,
  }, messages.length + index));
  const approvals = (detail.approvalEvents ?? []).map((approval, index) => toTimelineEvent({
    id: `${detail.sessionId}-approval-${String(approval.requestId ?? index)}`,
    type: 'approval_event',
    time: String(approval.createdAt ?? ''),
    title: `Approval: ${String(approval.requestId ?? '')}`,
    summary: String(asRecord(approval.riskAssessment).command ?? 'Approval request'),
    status: String(approval.status ?? 'PENDING'),
    payload: approval,
  }, messages.length + toolCalls.length + index));
  const traces = (detail.traceEvents ?? []).map((trace, index) => toTimelineEvent({
    id: `${detail.sessionId}-trace-${index}`,
    type: 'trace_event',
    time: String(trace.timestamp ?? ''),
    title: String(trace.title ?? trace.type ?? 'Trace event'),
    summary: String(trace.detail ?? ''),
    status: String(trace.severity ?? 'INFO'),
    payload: trace,
  }, messages.length + toolCalls.length + approvals.length + index));
  const changes = (detail.changeSets ?? []).map((changeSet, index) => toTimelineEvent({
    id: `${detail.sessionId}-changeset-${String(changeSet.id ?? index)}`,
    type: 'changeset_event',
    time: String(changeSet.updatedAt ?? ''),
    title: `ChangeSet: ${String(changeSet.id ?? '')}`,
    summary: String(changeSet.diffSummary ?? ''),
    status: String(changeSet.status ?? 'DRAFT'),
    payload: changeSet,
  }, messages.length + toolCalls.length + approvals.length + traces.length + index));

  return [...messages, ...toolCalls, ...approvals, ...traces, ...changes].sort((a, b) => a.timestamp.localeCompare(b.timestamp));
}

function changeSetFromBackend(detail: ConsoleSessionDetail, sessionId: string): ChangeSet {
  const raw = detail.changeSets?.[0];
  if (!raw) {
    return emptyBackendChangeSet(sessionId);
  }
  const changedFiles = changedFilesFromBackend(raw);
  return {
    id: String(raw.id ?? `${sessionId}-backend-changeset`),
    sessionId: String(raw.sessionId ?? sessionId),
    teamSessionId: String(raw.teamSessionId ?? ''),
    taskId: String(raw.taskId ?? ''),
    baseCommit: String(raw.baseCommit ?? ''),
    status: normalizeChangeSetStatus(raw.status),
    diffSummary: String(raw.diffSummary ?? 'Backend ChangeSet'),
    changedFiles,
    suggestedTests: stringArray(raw.suggestedTests),
    executedTests: stringArray(raw.executedTests),
    verifierStatus: normalizeVerifierStatus(raw.verifierStatus),
    verifierReasons: stringArray(raw.verifierReasons),
    commitMessage: String(raw.commitMessage ?? ''),
    updatedAt: String(raw.updatedAt ?? new Date().toISOString()),
  };
}

function emptyBackendChangeSet(sessionId: string): ChangeSet {
  return {
    id: `${sessionId}-no-backend-changeset`,
    sessionId,
    teamSessionId: '',
    taskId: '',
    baseCommit: '',
    status: 'DRAFT',
    diffSummary: 'No backend ChangeSet attached to this session yet.',
    changedFiles: [],
    suggestedTests: [],
    executedTests: [],
    verifierStatus: 'PENDING',
    verifierReasons: [],
    commitMessage: '',
    updatedAt: new Date().toISOString(),
  };
}

function changedFilesFromBackend(raw: Record<string, unknown>): ChangedFile[] {
  const patch = String(raw.diffPatch ?? '');
  const paths = Array.isArray(raw.changedFiles) ? raw.changedFiles.map(String) : [];
  return paths.map((path) => ({
    path,
    changeType: changeTypeFromPatch(path, patch),
    additions: countForFile(patch, path, '+'),
    deletions: countForFile(patch, path, '-'),
    diff: filePatch(patch, path),
  }));
}

function filePatch(patch: string, path: string) {
  if (!patch.trim()) {
    return '';
  }
  const marker = `diff --git a/${path} b/${path}`;
  const start = patch.indexOf(marker);
  if (start < 0) {
    return patch;
  }
  const next = patch.indexOf('\ndiff --git ', start + 1);
  return next < 0 ? patch.slice(start) : patch.slice(start, next);
}

function countForFile(patch: string, path: string, prefix: '+' | '-') {
  return filePatch(patch, path)
    .split('\n')
    .filter((line) => line.startsWith(prefix) && !line.startsWith(`${prefix}${prefix}${prefix}`))
    .length;
}

function changeTypeFromPatch(path: string, patch: string): FileChangeType {
  const section = filePatch(patch, path);
  if (section.includes('new file mode')) {
    return 'added';
  }
  if (section.includes('deleted file mode')) {
    return 'deleted';
  }
  return 'modified';
}

function approvalFromPayload(payload: Record<string, unknown>): ApprovalRequest {
  const risk = riskFromPayload(asRecord(payload.riskAssessment));
  const pendingToolCall = asRecord(payload.pendingToolCall);
  return {
    requestId: String(payload.requestId ?? payload.id ?? 'backend-approval'),
    riskAssessment: risk,
    createdAt: String(payload.createdAt ?? new Date().toISOString()),
    expiresAt: String(payload.expiresAt ?? ''),
    status: normalizeApprovalStatus(payload.status),
    pendingToolCall: Object.keys(pendingToolCall).length > 0 ? {
      requestId: String(pendingToolCall.requestId ?? payload.requestId ?? ''),
      toolName: String(pendingToolCall.toolName ?? risk.toolName),
      arguments: asRecord(pendingToolCall.arguments),
      sessionId: String(pendingToolCall.sessionId ?? ''),
      createdAt: String(pendingToolCall.createdAt ?? payload.createdAt ?? ''),
      riskAssessment: riskFromPayload(asRecord(pendingToolCall.riskAssessment ?? payload.riskAssessment)),
      consumed: Boolean(pendingToolCall.consumed),
    } : undefined,
    consumed: Boolean(payload.consumed),
  };
}

function riskFromPayload(payload: Record<string, unknown>): RiskAssessment {
  return {
    riskLevel: normalizeRisk(payload.riskLevel ?? payload.level),
    reasons: stringArray(payload.reasons),
    command: String(payload.command ?? ''),
    toolName: String(payload.toolName ?? ''),
    affectedPaths: stringArray(payload.affectedPaths),
    requiresApproval: Boolean(payload.requiresApproval ?? true),
    blocked: Boolean(payload.blocked),
  };
}

function normalizeToolStatus(value: unknown): ToolStatus {
  const normalized = String(value ?? '').toUpperCase();
  return ['RUNNING', 'SUCCEEDED', 'FAILED', 'WAITING_APPROVAL'].includes(normalized)
    ? normalized as ToolStatus
    : 'RUNNING';
}

function normalizeApprovalStatus(value: unknown): ApprovalRequest['status'] {
  const normalized = String(value ?? '').toUpperCase();
  return ['PENDING', 'APPROVED', 'REJECTED'].includes(normalized)
    ? normalized as ApprovalRequest['status']
    : 'PENDING';
}

function normalizeChangeSetStatus(value: unknown): ChangeSet['status'] {
  const normalized = String(value ?? '').toUpperCase();
  return ['DRAFT', 'VERIFIED', 'APPROVED', 'COMMITTED'].includes(normalized)
    ? normalized as ChangeSet['status']
    : 'DRAFT';
}

function normalizeVerifierStatus(value: unknown): ChangeSet['verifierStatus'] {
  const normalized = String(value ?? '').toUpperCase();
  return ['PASS', 'FAIL', 'PENDING'].includes(normalized)
    ? normalized as ChangeSet['verifierStatus']
    : 'PENDING';
}

function severityFromStatus(value: unknown): TimelineEvent['severity'] {
  const normalized = String(value ?? '').toUpperCase();
  if (normalized.includes('ERROR') || normalized.includes('FAIL') || normalized.includes('REJECT')) {
    return 'ERROR';
  }
  if (normalized.includes('WARN') || normalized.includes('APPROVAL') || normalized.includes('PENDING')) {
    return 'WARN';
  }
  return 'INFO';
}

function refsFromPayload(payload: Record<string, unknown>): Record<string, string> {
  const refs = asRecord(payload.refs);
  const out: Record<string, string> = {};
  for (const key of ['sessionId', 'traceId', 'taskId', 'teamSessionId', 'workspaceId', 'changeSetId']) {
    const value = refs[key] ?? payload[key];
    if (value !== undefined && value !== null && String(value).trim()) {
      out[key] = String(value);
    }
  }
  return out;
}

function safeTimestamp(value: unknown) {
  const raw = String(value ?? '');
  const date = new Date(raw);
  return Number.isNaN(date.getTime()) ? new Date().toISOString() : raw;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function asRecord(value: unknown): Record<string, unknown> {
  return isRecord(value) ? value : {};
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String).filter(Boolean) : [];
}

function normalizeRisk(value: unknown): RiskLevel {
  const normalized = String(value ?? '').trim().toUpperCase();
  return ['SAFE', 'LOW', 'MEDIUM', 'HIGH', 'BLOCKED'].includes(normalized)
    ? normalized as RiskLevel
    : 'SAFE';
}

function numberOrZero(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function runErrorMessage(code?: string, message?: string) {
  if (code === 'model_not_configured') {
    return '模型未配置，无法启动任务';
  }
  if (code === 'blank_input') {
    return 'Input cannot be blank';
  }
  return message || 'Run failed';
}

function normalizeRunStatus(value: unknown): ConsoleRunStatus {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (['queued', 'running', 'finished', 'failed', 'cancelled'].includes(normalized)) {
    return normalized as ConsoleRunStatus;
  }
  return 'queued';
}

function normalizeEventCategory(value: unknown): Exclude<EventCategory, 'all'> {
  const normalized = String(value ?? '').trim().toLowerCase();
  if (['run', 'tool', 'approval', 'changeset', 'error', 'system'].includes(normalized)) {
    return normalized as Exclude<EventCategory, 'all'>;
  }
  if (normalized.includes('approval')) {
    return 'approval';
  }
  if (normalized.includes('changeset') || normalized.includes('diff')) {
    return 'changeset';
  }
  if (normalized.includes('tool')) {
    return 'tool';
  }
  if (normalized.includes('error') || normalized.includes('timeout') || normalized.includes('fail')) {
    return 'error';
  }
  if (normalized.includes('run') || normalized.includes('model') || normalized.includes('capability')) {
    return 'run';
  }
  return 'system';
}

function eventCategory(event: TimelineEvent): Exclude<EventCategory, 'all'> {
  if (event.category) {
    return event.category;
  }
  if (event.severity === 'ERROR') {
    return 'error';
  }
  if (event.kind === 'approval') {
    return 'approval';
  }
  if (event.kind === 'tool') {
    return 'tool';
  }
  if (event.kind === 'run') {
    return 'run';
  }
  if (event.kind === 'verification') {
    return 'changeset';
  }
  return 'system';
}

function timelineDomId(eventId: string) {
  return `timeline-event-${eventId}`;
}

function canCancelStatus(value: ConsoleRunStatus | '') {
  return value === 'queued' || value === 'running';
}

function lastEventId(events: TimelineEvent[]): string {
  return events.length > 0 ? events[events.length - 1]?.id ?? '' : '';
}

function lastRawEventId(events: ConsoleTimelineEvent[]): string {
  const id = events.length > 0 ? events[events.length - 1]?.id : '';
  return id ? String(id) : '';
}
