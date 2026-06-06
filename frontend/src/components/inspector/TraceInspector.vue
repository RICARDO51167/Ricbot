<template>
  <aside class="inspector-panel panel">
    <div class="panel-head">
      <div>
        <h2>{{ t('inspector.title') }}</h2>
        <p>{{ subtitle }}</p>
      </div>
      <el-tag>{{ inspectorMode }}</el-tag>
    </div>

    <div v-if="!selectedEvent" class="empty-state">{{ t('inspector.empty') }}</div>
    <div v-else class="unified-meta">
      <span>type={{ selectedEvent.eventType || selectedEvent.kind }}</span>
      <span>name={{ selectedEvent.name || selectedEvent.title }}</span>
      <span>category={{ selectedEvent.category || 'system' }}</span>
      <span>status={{ selectedEvent.status || selectedEvent.severity }}</span>
      <span>actor={{ selectedEvent.actor || 'system' }}</span>
      <span>source={{ selectedEvent.eventSource || selectedEvent.source }}</span>
    </div>
    <div v-if="workspacePath" class="inspector-actions">
      <el-button size="small" type="primary" plain data-test="open-inspector-workspace" @click="openWorkspacePath">
        {{ t('workspace.openInWorkspace') }}
      </el-button>
    </div>
    <section v-if="relatedReferences.length" class="related-files">
      <h3>{{ t('workspace.fileReferences') }} / {{ t('workspace.relatedFiles') }}</h3>
      <div
        v-for="reference in relatedReferences"
        :key="`${reference.normalizedPath}:${reference.line ?? reference.startLine ?? ''}`"
        class="related-file"
        :class="{ selected: reference.normalizedPath === selectedWorkspacePath }"
      >
        <FileReferencePreview :reference="reference">
          <span class="related-file-path">{{ reference.normalizedPath }}</span>
        </FileReferencePreview>
        <span class="related-file-meta">
          {{ t('workspace.source') }}={{ reference.source }}
          <template v-if="reference.line || reference.startLine"> / {{ t('workspace.line') }}={{ reference.line ?? reference.startLine }}</template>
          / {{ t('workspace.confidence') }}={{ reference.confidence }}
        </span>
        <button type="button" class="link-button" data-test="related-file-link" @click="openRelatedFile(reference)">
          {{ t('workspace.openFile') }}
        </button>
      </div>
    </section>

    <ApprovalPanel v-if="inspectorMode === 'approval' && selectedApproval" />

    <div v-if="inspectorMode === 'tool' && selectedToolCall" class="inspector-content">
      <div class="inspector-title">
        <Wrench :size="18" />
        <strong>{{ selectedToolCall.toolName }}</strong>
        <el-tag :type="selectedToolCall.status === 'SUCCEEDED' ? 'success' : 'warning'">
          {{ selectedToolCall.status }}
        </el-tag>
      </div>
      <div class="kv-list">
        <div><span>Duration</span><strong>{{ selectedToolCall.durationMs }}ms</strong></div>
        <div><span>Execution Policy</span><strong>{{ selectedToolCall.policy.executionPolicy }}</strong></div>
        <div><span>Permission Policy</span><strong>{{ selectedToolCall.policy.permissionPolicy }}</strong></div>
      </div>
      <section class="json-section">
        <h3>{{ t('inspector.arguments') }}</h3>
        <JsonViewer :value="selectedToolCall.arguments" />
      </section>
      <section class="json-section">
        <h3>{{ t('inspector.result') }}</h3>
        <JsonViewer :value="selectedToolCall.result" />
      </section>
      <section class="json-section">
        <h3>{{ t('inspector.refs') }}</h3>
        <JsonViewer :value="selectedToolCall.refs" />
      </section>
      <section class="json-section">
        <h3>{{ t('inspector.eventPayload') }}</h3>
        <JsonViewer :value="selectedEvent" />
      </section>
    </div>

    <div v-if="inspectorMode === 'message' && selectedMessage" class="inspector-content">
      <div class="inspector-title">
        <MessageSquare :size="18" />
        <strong>{{ selectedMessage.title }}</strong>
        <el-tag type="info">{{ selectedMessage.role }}</el-tag>
      </div>
      <p class="message-detail">{{ selectedMessage.content }}</p>
      <section class="json-section">
        <h3>{{ t('inspector.messageEvent') }}</h3>
        <JsonViewer :value="selectedMessage" />
      </section>
    </div>

    <div v-if="genericEvent" class="inspector-content">
      <div class="inspector-title">
        <TerminalSquare :size="18" />
        <strong>{{ genericEvent.title }}</strong>
        <el-tag :type="genericEvent.severity === 'WARN' ? 'warning' : genericEvent.severity === 'ERROR' ? 'danger' : 'info'">
          {{ genericEvent.severity }}
        </el-tag>
      </div>
      <p class="message-detail">{{ genericEvent.detail }}</p>
      <section class="json-section">
        <h3>{{ t('inspector.traceEvent') }}</h3>
        <JsonViewer :value="genericEvent" />
      </section>
    </div>
    <ActionLog v-if="showActionLog" />
    <DashboardPanel v-if="showAdvancedPanels" />
    <RunHistory v-if="showAdvancedPanels" />
    <EventSearch v-if="showAdvancedPanels" />
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { storeToRefs } from 'pinia';
import { MessageSquare, TerminalSquare, Wrench } from 'lucide-vue-next';

import { navigate } from '@/router';
import { useChangeSetStore } from '@/stores/changeSetStore';
import { useInspectorStore } from '@/stores/inspectorStore';
import { useLocaleStore } from '@/stores/localeStore';
import { useSessionStore } from '@/stores/sessionStore';
import { useWorkspaceStore } from '@/stores/workspaceStore';
import type { FileReference, FileReferenceSource } from '@/types/file-reference';
import { dedupeFileReferences, extractFileReferencesFromPayload } from '@/utils/fileReferences';
import ActionLog from './ActionLog.vue';
import ApprovalPanel from './ApprovalPanel.vue';
import DashboardPanel from './DashboardPanel.vue';
import EventSearch from './EventSearch.vue';
import FileReferencePreview from '@/components/workspace/FileReferencePreview.vue';
import JsonViewer from './JsonViewer.vue';
import RunHistory from './RunHistory.vue';

withDefaults(defineProps<{
  showActionLog?: boolean;
  showAdvancedPanels?: boolean;
}>(), {
  showActionLog: true,
  showAdvancedPanels: true,
});

const inspectorStore = useInspectorStore();
const sessionStore = useSessionStore();
const changeSetStore = useChangeSetStore();
const workspaceStore = useWorkspaceStore();
const {
  inspectorMode,
  selectedApproval,
  selectedEvent,
  selectedMessage,
  selectedToolCall,
} = storeToRefs(inspectorStore);
const { filteredTimeline } = storeToRefs(sessionStore);
const { currentChangeSet } = storeToRefs(changeSetStore);
const { selectedPath: selectedWorkspacePath } = storeToRefs(workspaceStore);
const { t } = useLocaleStore();

const subtitle = computed(() => {
  if (!selectedEvent.value) {
    return t('inspector.subtitle');
  }
  return selectedEvent.value.refs.traceId ?? selectedEvent.value.detail;
});

const showGenericInspector = computed(() => {
  return Boolean(selectedEvent.value)
    && !(inspectorMode.value === 'approval' && selectedApproval.value)
    && !(inspectorMode.value === 'tool' && selectedToolCall.value)
    && !(inspectorMode.value === 'message' && selectedMessage.value);
});
const genericEvent = computed(() => showGenericInspector.value ? selectedEvent.value : null);

const workspacePath = computed(() => {
  return relatedReferences.value[0]?.normalizedPath ?? '';
});

const relatedReferences = computed(() => {
  const references: FileReference[] = [];
  const selected = selectedEvent.value;
  references.push(...extractFileReferencesFromPayload(selected, {
    source: sourceForEvent(selected),
    eventId: selected?.id,
    runId: selected?.runId,
    toolName: selectedToolCall.value?.toolName,
  }));
  for (const event of filteredTimeline.value) {
    const payload = (event as { payload?: unknown }).payload ?? event;
    references.push(...extractFileReferencesFromPayload(payload, {
      source: sourceForEvent(event),
      eventId: event.id,
      runId: event.runId,
    }));
  }
  for (const file of currentChangeSet.value?.changedFiles ?? []) {
    if (file.path) {
      references.push({
        path: file.path,
        normalizedPath: file.path,
        source: 'changeset',
        changeSetId: currentChangeSet.value?.id,
        confidence: 'high',
      });
    }
  }
  return dedupeFileReferences(references);
});

function openWorkspacePath() {
  if (workspacePath.value) {
    openRelatedFile(relatedReferences.value[0]);
  }
}

function openRelatedFile(reference: FileReference | undefined) {
  if (!reference) {
    return;
  }
  const line = reference.line ?? reference.startLine;
  navigate('/console/workspace', line
    ? { file: reference.normalizedPath, line: String(line) }
    : { file: reference.normalizedPath });
}

function sourceForEvent(event: { kind?: string; category?: string; eventType?: string } | null | undefined): FileReferenceSource {
  if (!event) {
    return 'timeline';
  }
  if (event.kind === 'tool' || event.category === 'tool' || event.eventType === 'tool_call') {
    return 'tool_call';
  }
  if (event.kind === 'approval' || event.category === 'approval') {
    return 'approval';
  }
  if (event.kind === 'trace') {
    return 'trace';
  }
  return 'timeline';
}
</script>
