import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

import type {
  AgentSession,
  ApprovalEvent,
  ApprovalStatus,
  InspectorMode,
  MessageEvent,
  TimelineEvent,
  ToolCallEvent,
} from '@/types/agent-console';
import { useSessionStore } from './sessionStore';

function modeForEvent(event: TimelineEvent): InspectorMode {
  if (event.kind === 'tool') {
    return 'tool';
  }
  if (event.kind === 'approval') {
    return 'approval';
  }
  if (event.kind === 'message') {
    return 'message';
  }
  return 'trace';
}

function firstInspectableEvent(session: AgentSession): TimelineEvent | null {
  return session.timeline[0] ?? null;
}

export const useInspectorStore = defineStore('inspector', () => {
  const selectedEventId = ref<string | null>(null);
  const inspectorMode = ref<InspectorMode>('trace');
  const localApprovalStatus = ref<Record<string, ApprovalStatus>>({});

  const selectedEvent = computed<TimelineEvent | null>(() => {
    const session = useSessionStore().currentSession;
    return session?.timeline.find((event) => event.id === selectedEventId.value) ?? session?.timeline[0] ?? null;
  });

  const selectedToolCall = computed(() => {
    return selectedEvent.value?.kind === 'tool' ? (selectedEvent.value as ToolCallEvent).toolCall : null;
  });

  const selectedApproval = computed(() => {
    return selectedEvent.value?.kind === 'approval' ? (selectedEvent.value as ApprovalEvent) : null;
  });

  const selectedApprovalStatus = computed<ApprovalStatus | null>(() => {
    const requestId = selectedApproval.value?.approval.requestId;
    if (!requestId) {
      return null;
    }
    return localApprovalStatus.value[requestId] ?? selectedApproval.value?.approval.status ?? null;
  });

  const selectedMessage = computed(() => {
    return selectedEvent.value?.kind === 'message' ? (selectedEvent.value as MessageEvent) : null;
  });

  function selectEvent(eventId: string) {
    const session = useSessionStore().currentSession;
    const event = session?.timeline.find((item) => item.id === eventId);
    if (!event) {
      return;
    }

    selectedEventId.value = event.id;
    inspectorMode.value = modeForEvent(event);
  }

  function resetForSession(session: AgentSession) {
    const event = firstInspectableEvent(session);
    selectedEventId.value = event?.id ?? null;
    inspectorMode.value = event ? modeForEvent(event) : 'trace';
  }

  function approveSelectedApproval() {
    const requestId = selectedApproval.value?.approval.requestId;
    if (requestId) {
      localApprovalStatus.value = { ...localApprovalStatus.value, [requestId]: 'APPROVED' };
    }
  }

  function rejectSelectedApproval() {
    const requestId = selectedApproval.value?.approval.requestId;
    if (requestId) {
      localApprovalStatus.value = { ...localApprovalStatus.value, [requestId]: 'REJECTED' };
    }
  }

  function setApprovalStatus(requestId: string, status: ApprovalStatus) {
    if (requestId) {
      localApprovalStatus.value = { ...localApprovalStatus.value, [requestId]: status };
    }
  }

  return {
    selectedEventId,
    selectedEvent,
    inspectorMode,
    selectedToolCall,
    selectedApproval,
    selectedApprovalStatus,
    selectedMessage,
    localApprovalStatus,
    selectEvent,
    resetForSession,
    approveSelectedApproval,
    rejectSelectedApproval,
    setApprovalStatus,
  };
});
