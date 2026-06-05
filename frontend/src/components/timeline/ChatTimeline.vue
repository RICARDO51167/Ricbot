<template>
  <section class="timeline-panel panel">
    <div class="panel-head timeline-head">
      <div>
        <h2>{{ t('timeline.title') }}</h2>
        <p>{{ session?.summary }}</p>
        <div class="detail-source">
          <el-tag size="small" :type="selectedSessionDataSource === 'backend' ? 'success' : 'info'">
            {{ t('timeline.detailSource') }}: {{ selectedSessionDataSource === 'backend' ? t('common.backend') : t('common.mock') }}
          </el-tag>
          <el-tag size="small" :type="pollingTagType">
            {{ liveStatusLabel }}
          </el-tag>
          <el-tag v-if="detailLoading" size="small" type="warning">{{ t('timeline.loadingDetail') }}</el-tag>
        </div>
        <div class="timeline-filters">
          <button
            v-for="filter in filters"
            :key="filter.value"
            type="button"
            class="filter-chip"
            :class="{ active: timelineFilter === filter.value }"
            @click="sessionStore.setTimelineFilter(filter.value)"
          >
            {{ filter.label }}
          </button>
        </div>
        <div v-if="timelineRunFilter" class="run-filter-banner">
          <span>当前正在查看 run: {{ timelineRunFilter }}</span>
          <button type="button" class="filter-chip active" @click="sessionStore.clearTimelineRunFilter()">
            Clear run filter
          </button>
          <button type="button" class="filter-chip" :disabled="replayMode" @click="sessionStore.startReplay(timelineRunFilter)">
            Replay Run
          </button>
        </div>
        <div v-if="replayMode" class="replay-banner">
          <span>Replay Mode，不是真实运行中</span>
          <el-button :icon="Play" size="small" :disabled="replayPlaying" @click="sessionStore.playReplay()">Play</el-button>
          <el-button :icon="Pause" size="small" :disabled="!replayPlaying" @click="sessionStore.pauseReplay()">Pause</el-button>
          <el-button :icon="StepForward" size="small" @click="sessionStore.stepReplay()">Step Next</el-button>
          <el-button :icon="Square" size="small" @click="sessionStore.stopReplay()">Stop Replay</el-button>
          <button
            v-for="speed in replaySpeeds"
            :key="speed"
            type="button"
            class="filter-chip"
            :class="{ active: replaySpeed === speed }"
            @click="sessionStore.setReplaySpeed(speed)"
          >
            {{ speed }}x
          </button>
        </div>
        <el-alert v-if="timelineFocusHint" class="timeline-focus-hint" type="info" :title="timelineFocusHint" :closable="false" />
      </div>
      <div class="timeline-actions">
        <el-tag v-if="session" type="info">{{ session.runId }}</el-tag>
        <el-button :icon="RefreshCw" size="small" :disabled="!timelineRunFilter" @click="toggleReplay">
          {{ replayMode ? t('timeline.stop') : t('timeline.replay') }}
        </el-button>
      </div>
    </div>

    <div class="timeline-scroll">
      <div v-if="selectedDetailIsEmpty" class="empty-state">
        {{ t('timeline.emptyDetail') }}
      </div>
      <article
        v-for="event in visibleEvents"
        :key="event.id"
        :id="timelineDomId(event.id)"
        :data-event-id="event.id"
        class="timeline-event"
        :class="{ selected: event.id === selectedEventId, focused: event.id === focusedTimelineEventId, clickable: isInspectable(event) }"
        @click="selectInspectable(event)"
      >
        <div class="event-rail">
          <component :is="iconFor(event.kind)" :size="16" />
        </div>
          <div class="event-body">
            <div class="event-meta">
              <span>{{ event.title }}</span>
              <em class="event-persist-badge" :class="{ live: persistLabel(event) === 'Live' }">
                {{ persistLabel(event) }}
              </em>
              <time>{{ formatTime(event.timestamp) }}</time>
            </div>

          <MessageBubble v-if="event.kind === 'message' || event.kind === 'thought'" :event="event" />
          <ToolCallCard v-else-if="event.kind === 'tool'" :event="event" />
          <div v-else-if="event.kind === 'run'" class="run-card" :class="`severity-${event.severity.toLowerCase()}`">
            <div class="run-card-head">
              <Activity :size="17" />
              <strong>{{ event.title }}</strong>
              <el-tag :type="event.severity === 'ERROR' ? 'danger' : event.severity === 'WARN' ? 'warning' : 'info'">
                {{ event.severity }}
              </el-tag>
            </div>
            <p>{{ event.detail }}</p>
          </div>
          <div v-else-if="event.kind === 'approval'" class="approval-card">
            <div class="approval-card-head">
              <ShieldAlert :size="17" />
              <strong>{{ event.detail }}</strong>
              <el-tag type="warning">{{ event.approval.riskAssessment.riskLevel }}</el-tag>
            </div>
            <p>{{ event.approval.riskAssessment.reasons.join(' / ') }}</p>
          </div>
          <div v-else class="trace-card">
            <div class="trace-title">{{ event.detail }}</div>
            <div class="trace-refs">
              <span v-for="[key, value] in Object.entries(event.refs)" :key="key">{{ key }}={{ value }}</span>
            </div>
          </div>
        </div>
      </article>
    </div>

    <div class="prompt-dock">
      <el-input
        v-model="draftInput"
        :placeholder="submitPlaceholder"
        :prefix-icon="TerminalSquare"
        :disabled="submitDisabled"
        @keyup.enter="submitRun"
      />
      <el-button type="primary" :loading="submittingRun" :disabled="submitDisabled" @click="submitRun">
        {{ activeRunStatus === 'running' ? t('timeline.running') : t('timeline.run') }}
      </el-button>
      <el-button
        data-test="cancel-run"
        type="danger"
        plain
        :loading="cancellingRun"
        :disabled="!canCancelRun"
        @click="cancelRun"
      >
        {{ cancellingRun ? t('timeline.cancelling') : t('timeline.cancel') }}
      </el-button>
    </div>
    <div v-if="submitError || cancelError || lastRunId" class="submit-status">
      <div v-if="activeRunId" class="run-status-line">
        <el-tag :type="runStatusTagType">{{ activeRunStatus || 'queued' }}</el-tag>
        <span>{{ activeRunId }}</span>
      </div>
      <el-alert
        v-if="submitError || cancelError"
        type="error"
        :title="submitError || cancelError"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else
        type="success"
        :title="`${t('timeline.runStarted')}: ${lastRunId}`"
        :closable="false"
        show-icon
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { storeToRefs } from 'pinia';
import { Activity, Bot, CheckCircle2, MessageSquare, Pause, Play, RefreshCw, ShieldAlert, Square, StepForward, TerminalSquare, Wrench } from 'lucide-vue-next';

import { useInspectorStore } from '@/stores/inspectorStore';
import { useLocaleStore } from '@/stores/localeStore';
import { useRuntimeStore } from '@/stores/runtimeStore';
import { useSessionStore } from '@/stores/sessionStore';
import type { TimelineEvent } from '@/types/agent-console';
import MessageBubble from './MessageBubble.vue';
import ToolCallCard from './ToolCallCard.vue';

const sessionStore = useSessionStore();
const runtimeStore = useRuntimeStore();
const inspectorStore = useInspectorStore();
const {
  currentSession: session,
  filteredTimeline: events,
  replayTimeline,
  backendUnavailable,
  detailLoading,
  pollingStatus,
  streamStatus,
  draftInput,
  submittingRun,
  submitError,
  cancellingRun,
  cancelError,
  lastRunId,
  activeRunId,
  activeRunStatus,
  selectedDetailIsEmpty,
  selectedSessionDataSource,
  timelineFilter,
  timelineRunFilter,
  timelineFocusHint,
  pendingFocusEventId,
  focusedTimelineEventId,
  replayMode,
  replayPlaying,
  replaySpeed,
} = storeToRefs(sessionStore);
const { selectedEventId } = storeToRefs(inspectorStore);
const localeStore = useLocaleStore();
const { currentLocale } = storeToRefs(localeStore);
const { t } = localeStore;

const iconMap = {
  message: MessageSquare,
  thought: Bot,
  tool: Wrench,
  approval: ShieldAlert,
  trace: TerminalSquare,
  verification: CheckCircle2,
  run: Activity,
};

const filters = computed(() => [
  { value: 'all' as const, label: t('timeline.filter.all') },
  { value: 'run' as const, label: t('timeline.filter.run') },
  { value: 'tool' as const, label: t('timeline.filter.tool') },
  { value: 'approval' as const, label: t('timeline.filter.approval') },
  { value: 'changeset' as const, label: t('timeline.filter.changeset') },
  { value: 'error' as const, label: t('timeline.filter.error') },
  { value: 'system' as const, label: t('timeline.filter.system') },
]);
const replaySpeeds = [1, 2, 4] as const;

const liveStatusLabel = computed(() => {
  if (backendUnavailable.value || pollingStatus.value === 'error') {
    return t('timeline.backendUnavailable');
  }
  if (streamStatus.value === 'connecting') {
    return t('timeline.connecting');
  }
  if (streamStatus.value === 'live') {
    return t('timeline.liveStream');
  }
  if (streamStatus.value === 'fallback_polling') {
    return t('timeline.fallbackPolling');
  }
  if (streamStatus.value === 'error') {
    return t('timeline.streamError');
  }
  if (detailLoading.value) {
    return t('timeline.polling');
  }
  if (pollingStatus.value === 'polling') {
    return t('timeline.fallbackPolling');
  }
  return t('timeline.paused');
});

const pollingTagType = computed(() => {
  if (backendUnavailable.value || pollingStatus.value === 'error' || streamStatus.value === 'error') {
    return 'danger';
  }
  if (streamStatus.value === 'connecting' || streamStatus.value === 'fallback_polling') {
    return 'warning';
  }
  if (streamStatus.value === 'live' || pollingStatus.value === 'polling') {
    return 'success';
  }
  return 'info';
});

const submitDisabled = computed(() => {
  return submittingRun.value
    || !draftInput.value.trim()
    || backendUnavailable.value
    || selectedSessionDataSource.value !== 'backend'
    || !runtimeStore.runtime.modelConfigured;
});

const submitPlaceholder = computed(() => {
  if (selectedSessionDataSource.value !== 'backend') {
    return t('timeline.mockSubmitDisabled');
  }
  if (!runtimeStore.runtime.modelConfigured) {
    return t('timeline.modelNotConfigured');
  }
  return t('timeline.placeholder');
});

const runStatusTagType = computed(() => {
  if (activeRunStatus.value === 'failed' || activeRunStatus.value === 'cancelled') {
    return 'danger';
  }
  if (activeRunStatus.value === 'finished') {
    return 'success';
  }
  if (activeRunStatus.value === 'running') {
    return 'warning';
  }
  return 'info';
});

const canCancelRun = computed(() => {
  return !!activeRunId.value && (activeRunStatus.value === 'queued' || activeRunStatus.value === 'running') && !cancellingRun.value;
});

const visibleEvents = computed(() => replayMode.value ? replayTimeline.value : events.value);

watch(
  () => [pendingFocusEventId.value, visibleEvents.value.map((event) => event.id).join('|')],
  ([eventId]) => {
    if (eventId && visibleEvents.value.some((event) => event.id === eventId)) {
      void sessionStore.focusEventAfterSessionLoad(String(eventId));
    }
  },
  { flush: 'post' },
);

function iconFor(kind: TimelineEvent['kind']) {
  return iconMap[kind];
}

function persistLabel(event: TimelineEvent) {
  const source = event.eventSource || '';
  if (source === 'console_event_store' || source === 'run_trace' || source === 'trace_store') {
    return 'Persisted';
  }
  return source ? 'Persisted' : 'Live';
}

function isInspectable(event: TimelineEvent) {
  return ['tool', 'approval', 'message', 'trace', 'verification', 'run'].includes(event.kind);
}

function selectInspectable(event: TimelineEvent) {
  if (isInspectable(event)) {
    inspectorStore.selectEvent(event.id);
    void sessionStore.scrollToTimelineEvent(event.id);
  }
}

function submitRun() {
  if (!submitDisabled.value) {
    void sessionStore.submitRun();
  }
}

function cancelRun() {
  if (canCancelRun.value) {
    void sessionStore.cancelActiveRun();
  }
}

function toggleReplay() {
  if (replayMode.value) {
    sessionStore.stopReplay();
  } else {
    sessionStore.startReplay(timelineRunFilter.value);
  }
}

function timelineDomId(eventId: string) {
  return `timeline-event-${eventId}`;
}

function formatTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '--:--:--';
  }
  return new Intl.DateTimeFormat(currentLocale.value, {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date);
}

computed(() => session.value);
</script>
